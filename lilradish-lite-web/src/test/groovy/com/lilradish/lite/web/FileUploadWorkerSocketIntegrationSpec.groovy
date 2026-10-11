package com.lilradish.lite.web

import static java.nio.charset.StandardCharsets.ISO_8859_1
import static java.nio.charset.StandardCharsets.UTF_8
import static org.mockito.ArgumentMatchers.any
import static org.mockito.BDDMockito.given

import ch.qos.logback.classic.Logger
import com.lilradish.lite.app.estate.EstateRoleGrants
import com.lilradish.lite.app.files.TransferPace
import com.lilradish.lite.app.group.GroupRoles
import com.lilradish.lite.app.identification.UserIdentification
import com.lilradish.lite.domain.identity.EstateRole
import com.lilradish.lite.domain.identity.UserId
import com.lilradish.lite.domain.observability.Correlation
import com.lilradish.lite.testutil.ApplicationStore
import com.lilradish.lite.testutil.KeyValuePairs
import com.lilradish.lite.testutil.SnapshottingAppender
import com.lilradish.lite.testutil.library.LibraryStore
import com.lilradish.lite.testutil.library.TakingWorkflow
import jakarta.servlet.http.HttpServletRequest
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.function.LongSupplier
import javax.sql.DataSource
import org.mockito.Mockito
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.tomcat.autoconfigure.TomcatServerProperties
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import spock.lang.Specification
import spock.util.concurrent.PollingConditions
import tools.jackson.databind.json.JsonMapper

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
        'server.tomcat.connection-timeout=120s', 'lilradish.files.maximum-size=2097152B',
        'lilradish.files.maximum-total-size=6291456B'])
@Import(ApplicationStore)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FileUploadWorkerSocketIntegrationSpec extends Specification {

    static final String GROUP = '00000003-0000-4000-8000-00000000f702'
    static final String PERSON = '00000002-0000-4000-8000-00000000f702'
    static final UserId CALLER = new UserId('00f702')
    static final String ENTRY = '00000006-0000-4000-8000-00000000f702'
    static final String VERSION = '00000007-0000-4000-8000-00000000f702'
    static final String ADDRESS = "/api/groups/${GROUP}/runs"
    static final JsonMapper JSON = JsonMapper.builder().build()

    @LocalServerPort int port
    @Autowired DataSource source
    @Autowired JdbcClient database
    @Autowired LargeBodyAdmission admission
    @Autowired FileTransfers transfers
    @Autowired TomcatServerProperties tomcat
    @Autowired @Qualifier('fileUploadExecutor') ThreadPoolTaskExecutor workers
    @Autowired @Qualifier('applicationTaskExecutor') ThreadPoolTaskExecutor ordinaryWorkers
    @MockitoBean UserIdentification identification
    @MockitoBean EstateRoleGrants grants
    @MockitoBean(name = 'fileTransferTime') LongSupplier clock
    @MockitoSpyBean GroupRoles roles

    private final AtomicLong ahead = new AtomicLong()
    private final List<Socket> sockets = []
    private final CountDownLatch release = new CountDownLatch(1)

    def setup() {
        // DB-SPECIFIC: PostgreSQL uuid cast.
        if (database.sql('select count(*) from groups where group_id = ?::uuid').params(GROUP)
                .query(Integer).single() == 0) {
            def store = new LibraryStore(source)
            store.person(PERSON, CALLER.value(), 'Ben Example')
            store.group(GROUP, 'UPLOADWORKERS', 'Attachment reception')
            store.member(GROUP, PERSON, 'overseer')
            TakingWorkflow.workflow(store, ENTRY, VERSION, GROUP, 'Receive two pictures')
            ['photo', 'copy'].eachWithIndex { name, index ->
                def field = TakingWorkflow.field(store, VERSION, index + 1, name, 'file', [mustBeGiven: true])
                // DB-SPECIFIC: PostgreSQL :: casts the fixture field identifier to uuid.
                database.sql("update declaration_fields set file_types = array['.png'], file_maximum_bytes = 100 " +
                        'where declaration_field_id = ?::uuid').params(field).update()
            }
        }
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(CALLER))
        given(grants.heldBy(CALLER)).willReturn(EnumSet.allOf(EstateRole))
        given(clock.getAsLong()).willAnswer { System.nanoTime() + ahead.get() }
        assert workers.corePoolSize == tomcat.threads.max
        assert workers.maxPoolSize == tomcat.threads.max
        assert workers.threadPoolExecutor.queue.remainingCapacity() == tomcat.acceptCount
        assert !workers.is(ordinaryWorkers)
        assert ordinaryWorkers.corePoolSize == 8
        assert admission.turn.turn.availablePermits() == 1
    }

    def cleanup() {
        release.countDown()
        sockets.each { it.close() }
        new PollingConditions(timeout: 10).eventually {
            assert workers.activeCount == 0
            assert workers.threadPoolExecutor.queue.isEmpty()
            assert admission.turn.turn.availablePermits() == 1
        }
    }

    def 'eight known small quiet bodies leave a ninth upload and the ordinary MVC pool available'() {
        given:
        def before = heldRows()
        List<Socket> quiet = (1..8).collect { index ->
            def socket = connected()
            send(socket, '--Aa\r\nContent-Disposition: form-data; name="document"\r\n\r\n{',
                    "quiet-worker-${index}", 1000)
            socket
        }
        new PollingConditions(timeout: 5).eventually {
            assert workers.activeCount == 8
        }
        assert admission.turn.turn.availablePermits() == 1
        assert ordinaryWorkers.activeCount == 0
        assert ordinaryWorkers.threadPoolExecutor.queue.isEmpty()
        def fast = connected()

        when:
        send(fast, complete('Pictures arriving promptly'), 'ninth-worker')
        def accepted = answer(fast)

        then:
        accepted.status == 201
        heldRows().runs.size() == before.runs.size() + 1
        heldRows().file_contents.size() == before.file_contents.size() + 2
        before.every { table, rows -> heldRows()[table].containsAll(rows) }
        workers.activeCount == 8
        admission.turn.turn.availablePermits() == 1
        ordinaryWorkers.activeCount == 0
        ordinaryWorkers.threadPoolExecutor.queue.isEmpty()

        when:
        quiet.each { it.close() }

        then:
        new PollingConditions(timeout: 5).eventually {
            assert workers.activeCount == 0
        }
        heldRows().runs.size() == before.runs.size() + 1
        heldRows().file_contents.size() == before.file_contents.size() + 2
    }

    def 'a full active and waiting budget refuses an upload without changing stored history'() {
        given:
        occupyReaders()
        tomcat.acceptCount.times { workers.execute { release.await() } }
        assert workers.threadPoolExecutor.queue.size() == tomcat.acceptCount
        def before = heldRows()
        def overflow = connected()

        when:
        send(overflow, complete('Pictures waiting for room'), 'overflow-worker')
        def refused = answer(overflow)

        then:
        refused.status == 503
        JSON.readTree(refused.body).get('code').asString() == 'SERVICE_BUSY'
        JSON.readTree(refused.body).get('detail').asString() ==
                'Too many files are being read; send this request again.'
        refused.headers['retry-after'] != null
        !refused.headers.containsKey('location')
        heldRows() == before
        admission.turn.turn.availablePermits() == 1
        workers.activeCount == tomcat.threads.max
        workers.threadPoolExecutor.queue.size() == tomcat.acceptCount
        ordinaryWorkers.activeCount == 0
    }

    def 'the native deadline removes a queued upload so its replacement fits before any reader is released'() {
        given:
        occupyReaders()
        (tomcat.acceptCount - 1).times { workers.execute { release.await() } }
        Mockito.doAnswer { invocation ->
            def held = invocation.callRealMethod()
            ahead.set(TransferPace.allowedNanos(MultipartFiles.largestSending(6291456)) - TimeUnit.SECONDS.toNanos(3))
            held
        }.when(roles).heldBy(any(), any())
        def before = heldRows()
        def waiting = connected()
        send(waiting, complete('Pictures whose queue wait expires'), 'expired-worker')
        new PollingConditions(timeout: 2).eventually {
            assert workers.threadPoolExecutor.queue.size() == tomcat.acceptCount
        }
        FutureTask queued = workers.threadPoolExecutor.queue.find { it instanceof FutureTask } as FutureTask
        assert queued != null

        when:
        // No reader is released and no further bytes are sent while the original deadline expires.
        def refused = answer(waiting)

        then:
        refused.status == 400
        JSON.readTree(refused.body).get('code').asString() == 'BODY_SENT_TOO_SLOWLY'
        queued.cancelled
        workers.threadPoolExecutor.queue.size() == tomcat.acceptCount - 1
        !workers.threadPoolExecutor.queue.contains(queued)
        heldRows() == before
        admission.turn.turn.availablePermits() == 1

        when:
        ahead.set(0)
        Mockito.doCallRealMethod().when(roles).heldBy(any(), any())
        def replacement = connected()
        send(replacement, complete('Pictures with room to wait'), 'replacement-worker')
        new PollingConditions(timeout: 2).eventually {
            assert workers.threadPoolExecutor.queue.size() == tomcat.acceptCount
        }
        release.countDown()
        def accepted = answer(replacement)

        then:
        accepted.status == 201
        heldRows().runs.size() == before.runs.size() + 1
        heldRows().file_contents.size() == before.file_contents.size() + 2
        before.every { table, rows -> heldRows()[table].containsAll(rows) }
        new PollingConditions(timeout: 5).eventually {
            assert workers.activeCount == 0
            assert workers.threadPoolExecutor.queue.isEmpty()
        }
        admission.turn.turn.availablePermits() == 1
    }

    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    def 'closing active uploads wakes a body reader and removes a waiting upload exactly once'() {
        given:
        def traceLogger = LoggerFactory.getLogger(RequestTrace) as Logger
        def logged = new SnapshottingAppender()
        logged.start()
        traceLogger.addAppender(logged)
        def before = heldRows()
        def reading = connected()
        reading.outputStream.write(("POST ${ADDRESS} HTTP/1.1\r\nHost: localhost\r\nSec-Fetch-Site: same-origin\r\n" +
                "${Correlation.HEADER}: closing-reader\r\nContent-Type: multipart/form-data; boundary=Aa\r\n" +
                'Transfer-Encoding: chunked\r\nConnection: keep-alive\r\n\r\n').getBytes(ISO_8859_1))
        byte[] first = prefix('Pictures interrupted by closure').getBytes(UTF_8)
        reading.outputStream.write((Integer.toHexString(first.length) + '\r\n').getBytes(ISO_8859_1))
        reading.outputStream.write(first)
        reading.outputStream.write('\r\n'.getBytes(ISO_8859_1))
        reading.outputStream.flush()
        // DB-SPECIFIC: PostgreSQL pg_stat_activity and current_database() identify the temporary first-file write.
        new PollingConditions(timeout: 5).eventually {
            assert database.sql("select count(*) from pg_stat_activity where datname = current_database() " +
                    "and state = 'idle in transaction' and query like 'insert into file_contents%'")
                    .query(Integer).single() >= 1
            assert workers.activeCount == 1
            assert admission.turn.turn.availablePermits() == 0
        }
        occupyReaders(tomcat.threads.max - 1)
        (tomcat.acceptCount - 1).times { workers.execute { release.await() } }
        def waiting = connected()
        send(waiting, complete('Pictures waiting when readers close'), 'closing-waiter')
        new PollingConditions(timeout: 5).eventually {
            assert workers.threadPoolExecutor.queue.size() == tomcat.acceptCount
            assert transfers.uploading.size() == 2
        }
        FutureTask queued = workers.threadPoolExecutor.queue.find { it instanceof FutureTask } as FutureTask
        assert queued != null
        long completedBefore = workers.threadPoolExecutor.completedTaskCount

        when:
        transfers.close()
        transfers.close()
        def readerAnswer = answer(reading)
        def waiterAnswer = answer(waiting)

        then:
        readerAnswer.status == 500
        waiterAnswer.status == 500
        !readerAnswer.headers.containsKey('location')
        !waiterAnswer.headers.containsKey('location')
        queued.cancelled
        !workers.threadPoolExecutor.queue.contains(queued)
        heldRows() == before
        new PollingConditions(timeout: 5).eventually {
            assert workers.threadPoolExecutor.completedTaskCount == completedBefore + 1
            assert workers.activeCount + workers.threadPoolExecutor.queue.size() ==
                    tomcat.threads.max + tomcat.acceptCount - 2
            assert admission.turn.turn.availablePermits() == 1
            assert transfers.uploading.isEmpty()
            assert logged.list.count { it.formattedMessage == 'Answered' &&
                it.MDCPropertyMap.correlationId == 'closing-reader' } == 1
            assert logged.list.count { it.formattedMessage == 'Answered' &&
                it.MDCPropertyMap.correlationId == 'closing-waiter' } == 1
        }
        def answers = logged.list.findAll { it.formattedMessage == 'Answered' &&
            it.MDCPropertyMap.correlationId in ['closing-reader', 'closing-waiter'] }
        answers.every { KeyValuePairs.of(it).status == 500 &&
            KeyValuePairs.of(it).route == '/api/groups/{groupId}/runs' }

        when:
        queued.run()
        transfers.close()

        then:
        heldRows() == before
        admission.turn.turn.availablePermits() == 1
        transfers.uploading.isEmpty()
        logged.list.count { it.formattedMessage == 'Answered' &&
            it.MDCPropertyMap.correlationId in ['closing-reader', 'closing-waiter'] } == 2

        cleanup:
        traceLogger?.detachAppender(logged)
        logged?.stop()
    }

    private void occupyReaders(int count = tomcat.threads.max) {
        count.times {
            def started = new CountDownLatch(1)
            workers.execute {
                assert Thread.currentThread().virtual
                started.countDown()
                release.await()
            }
            assert started.await(5, TimeUnit.SECONDS)
        }
        assert workers.activeCount == tomcat.threads.max
    }

    private static void send(Socket socket, String body, String trace, int contentLength = -1) {
        byte[] bytes = body.getBytes(UTF_8)
        int length = contentLength < 0 ? bytes.length : contentLength
        socket.outputStream.write(("POST ${ADDRESS} HTTP/1.1\r\nHost: localhost\r\nSec-Fetch-Site: same-origin\r\n" +
                "${Correlation.HEADER}: ${trace}\r\nContent-Type: multipart/form-data; boundary=Aa\r\n" +
                "Content-Length: ${length}\r\nConnection: keep-alive\r\n\r\n").getBytes(ISO_8859_1))
        socket.outputStream.write(bytes)
        socket.outputStream.flush()
    }

    private Map<String, List<String>> heldRows() {
        ['runs', 'stored_files', 'file_contents', 'run_file_holders'].collectEntries { table ->
            // DB-SPECIFIC: PostgreSQL to_jsonb and text casts.
            [(table): database.sql("select to_jsonb(held)::text from ${table} held order by to_jsonb(held)::text")
                    .query(String).list()]
        }
    }

    private Socket connected() {
        def socket = new Socket('localhost', port)
        socket.soTimeout = 10_000
        sockets.add(socket)
        socket
    }

    private static String prefix(String name) {
        String document = JSON.writeValueAsString([name: name, versionId: VERSION,
                values: [photo: [upload: 0, name: 'first.png'], copy: [upload: 1, name: 'second.png']]])
        '--Aa\r\nContent-Disposition: form-data; name="document"\r\n\r\n' + document +
                '\r\n--Aa\r\nContent-Disposition: form-data; name="upload0"; filename="first.png"\r\n' +
                'Content-Type: image/png\r\n\r\naa\r\n--Aa\r\n' +
                'Content-Disposition: form-data; name="upload1"; filename="second.png"\r\n' +
                'Content-Type: image/png\r\n\r\nbb'
    }

    private static String complete(String name) { prefix(name) + '\r\n--Aa--\r\n' }

    private static Map answer(Socket socket) {
        String status = line(socket.inputStream)
        Map<String, String> headers = [:]
        String header
        while ((header = line(socket.inputStream)) != '') {
            int colon = header.indexOf(':')
            assert colon > 0
            headers[header.substring(0, colon).toLowerCase(Locale.ROOT)] = header.substring(colon + 1).strip()
        }
        def body = new ByteArrayOutputStream()
        if (headers['transfer-encoding'] == 'chunked') {
            int size
            while ((size = Integer.parseInt(line(socket.inputStream).split(';')[0], 16)) != 0) {
                body.write(socket.inputStream.readNBytes(size))
                assert line(socket.inputStream) == ''
            }
            assert line(socket.inputStream) == ''
        } else if (headers['content-length'] != null) {
            body.write(socket.inputStream.readNBytes(Integer.parseInt(headers['content-length'])))
        } else {
            assert headers['connection'] == 'close'
            body.write(socket.inputStream.readAllBytes())
        }
        [status: Integer.parseInt(status.split(' ')[1]), headers: headers, body: body.toString(UTF_8)]
    }

    private static String line(InputStream input) {
        def text = new StringBuilder()
        while (!text.toString().endsWith('\r\n')) {
            int next = input.read()
            assert next >= 0
            text.append((char) next)
        }
        text.substring(0, text.length() - 2)
    }
}
