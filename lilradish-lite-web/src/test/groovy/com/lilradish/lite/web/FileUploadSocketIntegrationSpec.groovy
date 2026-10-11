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
import com.lilradish.lite.domain.filling.Filling
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.function.LongSupplier
import javax.sql.DataSource
import org.mockito.Mockito
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import spock.lang.Specification
import spock.util.concurrent.PollingConditions
import tools.jackson.databind.json.JsonMapper

/** The real Spring upload path, its transaction and its final answer, over a Tomcat socket. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
        'server.tomcat.connection-timeout=120s', 'lilradish.files.maximum-size=2097152B',
        'lilradish.files.maximum-total-size=6291456B'])
@Import(ApplicationStore)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FileUploadSocketIntegrationSpec extends Specification {

    static final String GROUP = '00000003-0000-4000-8000-00000000f701'
    static final String PERSON = '00000002-0000-4000-8000-00000000f701'
    static final UserId CALLER = new UserId('00f701')
    static final String ENTRY = '00000006-0000-4000-8000-00000000f701'
    static final String VERSION = '00000007-0000-4000-8000-00000000f701'
    static final String ADDRESS = "/api/groups/${GROUP}/runs"
    static final JsonMapper JSON = JsonMapper.builder().build()

    @LocalServerPort int port
    @Autowired DataSource source
    @Autowired JdbcClient database
    @Autowired LargeBodyAdmission admission
    @Autowired FileTransfers transfers
    @MockitoBean UserIdentification identification
    @MockitoBean EstateRoleGrants grants
    @MockitoBean(name = 'fileTransferTime') LongSupplier clock
    @MockitoBean(name = 'fileUploadExecutor') ThreadPoolTaskExecutor workers
    @MockitoSpyBean GroupRoles roles

    private final AtomicLong ahead = new AtomicLong()
    private final List<Runnable> queued = new CopyOnWriteArrayList<>()
    private final List<List<String>> workerContexts = new CopyOnWriteArrayList<>()
    private final def working = Executors.newSingleThreadExecutor()
    private final def waitingPool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>())
    private final Logger traceLogger = LoggerFactory.getLogger(RequestTrace) as Logger
    private final SnapshottingAppender logged = new SnapshottingAppender()
    private String mode = 'working'

    def setup() {
        // DB-SPECIFIC: PostgreSQL uuid cast.
        if (database.sql('select count(*) from groups where group_id = ?::uuid').params(GROUP)
                .query(Integer).single() == 0) {
            def store = new LibraryStore(source)
            store.person(PERSON, CALLER.value(), 'Ann Example')
            store.group(GROUP, 'UPLOADSOCKET', 'Attachment intake')
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
        given(workers.getThreadPoolExecutor()).willReturn(waitingPool)
        Mockito.doAnswer { invocation ->
            Runnable next = invocation.arguments[0]
            if (mode == 'rejected') throw new RejectedExecutionException('The worker queue is closed.')
            if (mode == 'queued') {
                queued.add(next)
                waitingPool.queue.add(next)
            } else {
                working.execute {
                    next.run()
                    workerContexts.add([MDC.get('correlationId'), MDC.get('userId'), Correlation.CURRENT.get()])
                }
            }
            null
        }.when(workers).execute(any(Runnable))
        logged.start()
        traceLogger.addAppender(logged)
    }

    def cleanup() {
        traceLogger.detachAppender(logged)
        logged.stop()
        working.shutdownNow()
        waitingPool.shutdownNow()
        assert working.awaitTermination(5, TimeUnit.SECONDS)
    }

    def 'a slow second file rolls back the first and answers once before a fresh large request succeeds'() {
        given:
        def historyRequest = connected()
        send(historyRequest, complete('Kept pictures'), 'kept-upload', true)
        assert answer(historyRequest).status == 201
        historyRequest.close()
        def before = heldRows()
        def slow = connected()
        send(slow, prefix('Interrupted pictures'), 'slow-upload', false)
        // DB-SPECIFIC: PostgreSQL pg_stat_activity and current_database().
        new PollingConditions(timeout: 10).eventually {
            assert database.sql("select count(*) from pg_stat_activity where datname = current_database() " +
                    "and state = 'idle in transaction' and query like 'insert into file_contents%'")
                    .query(Integer).single() >= 1
        }
        assert admission.turn.turn.availablePermits() == 0

        when:
        ahead.set(TransferPace.allowedNanos(MultipartFiles.largestSending(6291456)) + TimeUnit.SECONDS.toNanos(1))
        chunk(slow, 'c')
        def refused = answer(slow)

        then:
        refused.status == 400
        JSON.readTree(refused.body).get('code').asString() == 'BODY_SENT_TOO_SLOWLY'
        JSON.readTree(refused.body).get('detail').asString() ==
                'What was sent arrived too slowly to be read; send it again.'
        !refused.headers.containsKey('location')
        slow.inputStream.read() == -1
        heldRows() == before
        new PollingConditions(timeout: 5).eventually {
            assert admission.turn.turn.availablePermits() == 1
            assert audits('slow-upload').size() == 1
        }
        KeyValuePairs.of(audits('slow-upload').first()).status == 400
        KeyValuePairs.of(audits('slow-upload').first()).route == '/api/groups/{groupId}/runs'
        audits('slow-upload').first().MDCPropertyMap.userId == '"00f701"'

        when:
        def retry = connected()
        send(retry, complete('Replacement pictures'), 'retried-upload', true)
        def accepted = answer(retry)

        then:
        accepted.status == 201
        heldRows().runs.size() == before.runs.size() + 1
        heldRows().file_contents.size() == before.file_contents.size() + 2
        before.every { table, rows -> heldRows()[table].containsAll(rows) }
        new PollingConditions(timeout: 5).eventually {
            assert admission.turn.turn.availablePermits() == 1
            assert workerContexts.size() == 3
        }
        workerContexts.every { it == [null, null, null] }
        audits('slow-upload').size() == 1

        cleanup:
        slow?.close()
        retry?.close()
    }

    def 'a quiet second file is cut off by the scheduled deadline and a fresh large request succeeds'() {
        given:
        def before = heldRows()
        long sending = Filling.LARGEST_REQUEST_BYTES + 200L + 3 * 16_540L
        Mockito.doAnswer { invocation ->
            def reached = invocation.callRealMethod()
            ahead.set(TransferPace.allowedNanos(sending) - TimeUnit.SECONDS.toNanos(5))
            reached
        }.when(roles).stillReached(any(), any(), any())
        def quiet = connected()
        send(quiet, prefix('Pictures that stop arriving'), 'quiet-upload', false)
        // DB-SPECIFIC: PostgreSQL pg_stat_activity and current_database().
        new PollingConditions(timeout: 4).eventually {
            assert database.sql("select count(*) from pg_stat_activity where datname = current_database() " +
                    "and state = 'idle in transaction' and query like 'insert into file_contents%'")
                    .query(Integer).single() >= 1
        }
        assert admission.turn.turn.availablePermits() == 0

        when:
        // No further bytes or client close can wake the reader; the scheduled deadline must do so.
        def refused = answer(quiet)

        then:
        refused.status == 400
        JSON.readTree(refused.body).get('code').asString() == 'BODY_SENT_TOO_SLOWLY'
        JSON.readTree(refused.body).get('detail').asString() ==
                'What was sent arrived too slowly to be read; send it again.'
        !refused.headers.containsKey('location')
        quiet.inputStream.read() == -1
        heldRows() == before
        new PollingConditions(timeout: 5).eventually {
            assert admission.turn.turn.availablePermits() == 1
            assert audits('quiet-upload').size() == 1
            assert workerContexts.size() == 1
        }
        KeyValuePairs.of(audits('quiet-upload').first()).status == 400
        KeyValuePairs.of(audits('quiet-upload').first()).route == '/api/groups/{groupId}/runs'
        audits('quiet-upload').first().MDCPropertyMap.userId == '"00f701"'
        workerContexts.first() == [null, null, null]

        when:
        def retry = connected()
        send(retry, complete('Pictures arriving again'), 'quiet-retried-upload', true)
        def accepted = answer(retry)

        then:
        accepted.status == 201
        heldRows().runs.size() == before.runs.size() + 1
        heldRows().file_contents.size() == before.file_contents.size() + 2
        before.every { table, rows -> heldRows()[table].containsAll(rows) }
        new PollingConditions(timeout: 5).eventually {
            assert admission.turn.turn.availablePermits() == 1
            assert workerContexts.size() == 2
        }
        workerContexts.every { it == [null, null, null] }
        audits('quiet-upload').size() == 1

        cleanup:
        quiet?.close()
        retry?.close()
    }

    def 'an already expired queued request is answered without running it and returns its large body turn'() {
        given:
        mode = 'queued'
        Mockito.doAnswer { invocation ->
            def held = invocation.callRealMethod()
            ahead.set(TransferPace.allowedNanos(MultipartFiles.largestSending(6291456)) + TimeUnit.SECONDS.toNanos(1))
            held
        }.when(roles).heldBy(any(), any())
        def before = heldRows()
        def socket = connected()

        when:
        send(socket, prefix('Waiting pictures'), 'queued-upload', false)
        def refused = answer(socket)

        then:
        refused.status == 400
        JSON.readTree(refused.body).get('code').asString() == 'BODY_SENT_TOO_SLOWLY'
        queued.size() == 1
        waitingPool.queue.isEmpty()
        workerContexts.isEmpty()
        heldRows() == before
        new PollingConditions(timeout: 5).eventually {
            assert admission.turn.turn.availablePermits() == 1
            assert audits('queued-upload').size() == 1
        }

        when:
        queued.first().run()

        then:
        heldRows() == before
        workerContexts.isEmpty()
        admission.turn.turn.availablePermits() == 1

        cleanup:
        socket?.close()
    }

    def 'a real Spring submission rejection completes without a missing worker finally retaining the turn'() {
        given:
        mode = 'rejected'
        def before = heldRows()
        def socket = connected()

        when:
        send(socket, prefix('Unstarted pictures'), 'rejected-upload', false)
        def refused = answer(socket)

        then:
        refused.status == 500
        !refused.headers.containsKey('location')
        socket.inputStream.read() == -1
        heldRows() == before
        workerContexts.isEmpty()
        new PollingConditions(timeout: 5).eventually {
            assert admission.turn.turn.availablePermits() == 1
            assert audits('rejected-upload').size() == 1
        }
        KeyValuePairs.of(audits('rejected-upload').first()).status == 500

        cleanup:
        socket?.close()
    }

    def 'a complete body keeps its transaction valid while the group number lock outlasts the transfer deadline'() {
        given:
        def before = heldRows()
        def holder = source.connection
        holder.autoCommit = false
        // DB-SPECIFIC: PostgreSQL pg_advisory_xact_lock() and hashtext() guard this group's run-number allocation.
        try (def locking = holder.prepareStatement('select pg_advisory_xact_lock(1, hashtext(?))')) {
            locking.setString(1, GROUP)
            locking.execute()
        }
        def socket = connected()
        send(socket, complete('Pictures waiting for a number'), 'arrived-upload', true)
        // DB-SPECIFIC: PostgreSQL pg_stat_activity and current_database().
        new PollingConditions(timeout: 10).eventually {
            assert database.sql("select count(*) from pg_stat_activity where datname = current_database() " +
                    "and wait_event = 'advisory' and query like '%pg_advisory_xact_lock%'")
                    .query(Integer).single() >= 1
        }

        when:
        ahead.set(TransferPace.allowedNanos(MultipartFiles.largestSending(6291456)) + TimeUnit.SECONDS.toNanos(1))
        holder.commit()
        def accepted = answer(socket)

        then:
        accepted.status == 201
        heldRows().runs.size() == before.runs.size() + 1
        heldRows().file_contents.size() == before.file_contents.size() + 2
        before.every { table, rows -> heldRows()[table].containsAll(rows) }
        new PollingConditions(timeout: 5).eventually {
            assert admission.turn.turn.availablePermits() == 1
            assert audits('arrived-upload').size() == 1
            assert workerContexts.size() == 1
        }
        KeyValuePairs.of(audits('arrived-upload').first()).status == 201
        workerContexts.first() == [null, null, null]

        cleanup:
        holder?.rollback()
        holder?.close()
        socket?.close()
    }

    def 'request completion before its worker leaves the transaction retains the turn until that worker ends'() {
        given:
        def entered = new AtomicReference<HttpServletRequest>()
        given(identification.identify(any(HttpServletRequest))).willAnswer { invocation ->
            entered.set(invocation.arguments[0] as HttpServletRequest)
            Optional.of(CALLER)
        }
        def holder = source.connection
        holder.autoCommit = false
        // DB-SPECIFIC: PostgreSQL pg_advisory_xact_lock() and hashtext().
        try (def locking = holder.prepareStatement('select pg_advisory_xact_lock(1, hashtext(?))')) {
            locking.setString(1, GROUP)
            locking.execute()
        }
        def socket = connected()
        send(socket, complete('Pictures with a completed request'), 'completed-upload', true)
        // DB-SPECIFIC: PostgreSQL pg_stat_activity and current_database() observe the numbering lock, not body arrival.
        new PollingConditions(timeout: 10).eventually {
            assert database.sql("select count(*) from pg_stat_activity where datname = current_database() " +
                    "and wait_event = 'advisory' and query like '%pg_advisory_xact_lock%'")
                    .query(Integer).single() >= 1
        }

        when:
        entered.get().asyncContext.complete()
        answer(socket)

        then:
        new PollingConditions(timeout: 5).eventually {
            assert audits('completed-upload').size() == 1
        }
        admission.turn.turn.availablePermits() == 0
        workerContexts.isEmpty()

        when:
        holder.commit()

        then:
        new PollingConditions(timeout: 5).eventually {
            assert admission.turn.turn.availablePermits() == 1
            assert workerContexts.size() == 1
        }
        workerContexts.first() == [null, null, null]
        audits('completed-upload').size() == 1

        cleanup:
        holder?.rollback()
        holder?.close()
        socket?.close()
    }

    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    def 'closing a completed request keeps its turn and tracking until its database worker finishes'() {
        given:
        def before = heldRows()
        def entered = new AtomicReference<HttpServletRequest>()
        given(identification.identify(any(HttpServletRequest))).willAnswer { invocation ->
            entered.set(invocation.arguments[0] as HttpServletRequest)
            Optional.of(CALLER)
        }
        def holder = source.connection
        holder.autoCommit = false
        // DB-SPECIFIC: PostgreSQL pg_advisory_xact_lock() and hashtext().
        try (def locking = holder.prepareStatement('select pg_advisory_xact_lock(1, hashtext(?))')) {
            locking.setString(1, GROUP)
            locking.execute()
        }
        def socket = connected()
        send(socket, complete('Pictures awaiting their database worker'), 'closing-completed-upload', true)
        // DB-SPECIFIC: PostgreSQL pg_stat_activity, current_database() and advisory wait_event.
        new PollingConditions(timeout: 10).eventually {
            assert database.sql("select count(*) from pg_stat_activity where datname = current_database() " +
                    "and wait_event = 'advisory' and query like '%pg_advisory_xact_lock%'")
                    .query(Integer).single() >= 1
            assert admission.turn.turn.availablePermits() == 0
            assert transfers.uploading.size() == 1
        }

        when:
        entered.get().asyncContext.complete()
        answer(socket)
        transfers.close()
        transfers.close()

        then:
        new PollingConditions(timeout: 5).eventually {
            assert audits('closing-completed-upload').size() == 1
        }
        admission.turn.turn.availablePermits() == 0
        workerContexts.isEmpty()
        transfers.uploading.size() == 1
        heldRows() == before

        when:
        holder.commit()

        then:
        new PollingConditions(timeout: 5).eventually {
            assert admission.turn.turn.availablePermits() == 1
            assert transfers.uploading.isEmpty()
            assert workerContexts.size() == 1
        }
        workerContexts.first() == [null, null, null]
        audits('closing-completed-upload').size() == 1

        when:
        transfers.close()

        then:
        admission.turn.turn.availablePermits() == 1
        transfers.uploading.isEmpty()
        workerContexts.size() == 1
        audits('closing-completed-upload').size() == 1

        cleanup:
        holder?.rollback()
        holder?.close()
        socket?.close()
    }

    private Map<String, List<String>> heldRows() {
        ['runs', 'stored_files', 'file_contents', 'run_file_holders'].collectEntries { table ->
            // DB-SPECIFIC: PostgreSQL to_jsonb and text casts capture every field of retained history.
            [(table): database.sql("select to_jsonb(held)::text from ${table} held order by to_jsonb(held)::text")
                    .query(String).list()]
        }
    }

    private List audits(String trace) {
        logged.list.findAll { it.formattedMessage == 'Answered' && it.MDCPropertyMap.correlationId == trace }
    }

    private Socket connected() {
        def socket = new Socket('localhost', port)
        socket.soTimeout = 10_000
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

    private static void send(Socket socket, String body, String trace, boolean finished) {
        socket.outputStream.write(("POST ${ADDRESS} HTTP/1.1\r\nHost: localhost\r\nSec-Fetch-Site: same-origin\r\n" +
                "${Correlation.HEADER}: ${trace}\r\nContent-Type: multipart/form-data; boundary=Aa\r\n" +
                'Transfer-Encoding: chunked\r\nConnection: keep-alive\r\n\r\n').getBytes(ISO_8859_1))
        chunk(socket, body)
        if (finished) {
            socket.outputStream.write('0\r\n\r\n'.getBytes(ISO_8859_1))
            socket.outputStream.flush()
        }
    }

    private static void chunk(Socket socket, String text) {
        byte[] bytes = text.getBytes(UTF_8)
        socket.outputStream.write((Integer.toHexString(bytes.length) + '\r\n').getBytes(ISO_8859_1))
        socket.outputStream.write(bytes)
        socket.outputStream.write('\r\n'.getBytes(ISO_8859_1))
        socket.outputStream.flush()
    }

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
