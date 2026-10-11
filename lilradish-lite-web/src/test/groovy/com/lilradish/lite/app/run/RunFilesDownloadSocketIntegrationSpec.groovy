package com.lilradish.lite.app.run

import static java.nio.charset.StandardCharsets.ISO_8859_1
import static org.mockito.ArgumentMatchers.any
import static org.mockito.BDDMockito.given

import com.lilradish.lite.app.estate.EstateRoleGrants
import com.lilradish.lite.app.files.FileOpenAdmission
import com.lilradish.lite.app.files.RunFiles
import com.lilradish.lite.app.files.TransferPace
import com.lilradish.lite.app.identification.UserIdentification
import com.lilradish.lite.app.run.fixture.CodeWorkflow
import com.lilradish.lite.domain.file.FileName
import com.lilradish.lite.domain.file.StoredFile
import com.lilradish.lite.domain.identity.EstateRole
import com.lilradish.lite.domain.identity.GroupId
import com.lilradish.lite.domain.run.RunId
import com.lilradish.lite.testutil.ApplicationStore
import com.lilradish.lite.testutil.library.LibraryStore
import jakarta.servlet.Filter
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletResponseWrapper
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.function.LongSupplier
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.bean.override.mockito.MockitoBean
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

/** A stopped reader and a queued final buffer tested over a real Tomcat connection. */
// DB-SPECIFIC: this context's SQL fixture uses PostgreSQL uuid casts, now(), native
// subject_kind/group_role/entry_kind label inputs and the JSONB started_with target.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.tomcat.connection-timeout=120s")
@Import([ApplicationStore, SocketBuffers])
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class RunFilesDownloadSocketIntegrationSpec extends Specification {

    static final UUID GROUP = UUID.fromString(CodeWorkflow.GROUP)
    static final UUID RUN = UUID.fromString(CodeWorkflow.RUN)
    static final UUID FILE = UUID.fromString("0000000f-0000-4000-8000-00000000f501")
    static final int SIZE = 8 * 1024 * 1024

    @LocalServerPort int port
    @Autowired JdbcClient database
    @Autowired FileOpenAdmission admission
    @Autowired OutputState outputState
    @MockitoBean RunFiles files
    @MockitoBean UserIdentification identification
    @MockitoBean EstateRoleGrants grants
    @MockitoBean(name = 'fileTransferTime') LongSupplier clock
    private final AtomicLong ahead = new AtomicLong()

    def setup() {
        outputState.written.set(0)
        outputState.expected = 0
        outputState.pending = new CountDownLatch(1)
        if (database.sql('select count(*) from groups where group_id = ?::uuid').params(CodeWorkflow.GROUP)
                .query(Integer).single() == 0) {
        database.sql("insert into subjects (subject_id, kind, user_id, display_name, created_by) values (?::uuid, 'person', ?, ?, ?::uuid)")
                .params(CodeWorkflow.ANN, CodeWorkflow.ANN_USER.value(), "Demo Sample", LibraryStore.SEEDER).update()
        database.sql("insert into groups (group_id, key, name, created_by) values (?::uuid, ?, ?, ?::uuid)")
                .params(CodeWorkflow.GROUP, "FILESOCKET", "Demo Sample", LibraryStore.FIRST_STEWARD).update()
        database.sql("insert into group_members (group_id, subject_id, role, created_by) values (?::uuid, ?::uuid, 'overseer', ?::uuid)")
                .params(CodeWorkflow.GROUP, CodeWorkflow.ANN, LibraryStore.FIRST_STEWARD).update()
        database.sql("insert into entries (entry_id, group_id, kind, name, created_by) values (?::uuid, ?::uuid, 'workflow', ?, ?::uuid)")
                .params(CodeWorkflow.WORKFLOW, CodeWorkflow.GROUP, "Demo Sample", LibraryStore.SEEDER).update()
        database.sql("""
                insert into entry_versions (entry_version_id, entry_id, entry_kind, number, created_by,
                                            approved_at, approved_by, approved_by_kind)
                values (?::uuid, ?::uuid, 'workflow', 1, ?::uuid, now(), ?::uuid, 'seeder')
                """).params(CodeWorkflow.VERSION, CodeWorkflow.WORKFLOW, LibraryStore.SEEDER,
                LibraryStore.SEEDER).update()
        database.sql("""
                insert into workflow_versions (entry_version_id, may_be_helped, helper_model, helper_mode, created_by)
                values (?::uuid, true, 'general', 'ordinary', ?::uuid)
                """).params(CodeWorkflow.VERSION, LibraryStore.SEEDER).update()
        database.sql("""
                insert into runs (run_id, group_id, number, name, entry_id, entry_version_id, root_run_id, depth,
                                  started_with, created_by)
                values (?::uuid, ?::uuid, 1, 'Demo Sample', ?::uuid, ?::uuid, ?::uuid, 0, '{}', ?::uuid)
                """).params(CodeWorkflow.RUN, CodeWorkflow.GROUP, CodeWorkflow.WORKFLOW, CodeWorkflow.VERSION,
                CodeWorkflow.RUN, CodeWorkflow.ANN).update()
        }
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(CodeWorkflow.ANN_USER))
        given(grants.heldBy(CodeWorkflow.ANN_USER)).willReturn(EnumSet.allOf(EstateRole))
        given(clock.getAsLong()).willAnswer { System.nanoTime() + ahead.get() }
    }

    def "a client that stops reading is cut off by the transfer deadline and returns its download permit"() {
        given:
        def opens = new AtomicInteger()
        def stopped = new CountDownLatch(1)
        def file = new StoredFile(FILE, new FileName("Demo Sample.txt"), SIZE)
        Mockito.when(files.open(new RunId(RUN), new GroupId(GROUP), FILE)).thenAnswer {
            new RunFiles.OpenedFile(file, opens.incrementAndGet() == 4
                    ? new LongStream(SIZE, stopped)
                    : new ByteArrayInputStream([65] as byte[]))
        }
        def held = (0..<FileOpenAdmission.MOST_OPEN - 1).collect {
            admission.open(files, new RunId(RUN), new GroupId(GROUP), FILE)
        }
        def socket = new Socket()
        socket.receiveBufferSize = 1024
        socket.connect(new InetSocketAddress("localhost", port))
        socket.soTimeout = 5_000

        when:
        socket.outputStream.write(("GET /api/groups/${GROUP}/runs/${RUN}/files/${FILE} HTTP/1.1\r\n" +
                "Host: localhost\r\nConnection: close\r\n\r\n").getBytes(ISO_8859_1))
        socket.outputStream.flush()
        def headers = new StringBuilder()
        while (!headers.toString().endsWith("\r\n\r\n")) {
            int next = socket.inputStream.read()
            assert next >= 0
            headers.append((char) next)
        }
        assert socket.inputStream.read() >= 0

        then:
        headers.toString().startsWith("HTTP/1.1 200")
        stopped.await(70, TimeUnit.SECONDS)
        opens.get() == 4

        when:
        def released = admission.open(files, new RunId(RUN), new GroupId(GROUP), FILE)
        int rest = 0
        byte[] chunk = new byte[8192]
        int count
        while ((count = socket.inputStream.read(chunk)) >= 0) rest += count

        then:
        released.file().size() == SIZE
        rest < SIZE
        opens.get() == 5

        when:
        released.content().close()
        def small = new StoredFile(FILE, new FileName('receipt.txt'), 1)
        Mockito.when(files.open(new RunId(RUN), new GroupId(GROUP), FILE))
                .thenReturn(new RunFiles.OpenedFile(small, new ByteArrayInputStream([66] as byte[])))
        def fresh = connected()
        ask(fresh, true)
        def normal = received(fresh)

        then:
        normal.status == 200
        normal.body == [66] as byte[]

        cleanup:
        socket?.close()
        fresh?.close()
        held*.content()*.close()
        released?.content()?.close()
    }

    def 'source EOF while the last write is queued retains admission until the deadline cuts that tail off'() {
        given:
        int bytes = SIZE
        outputState.expected = bytes
        def ended = new CountDownLatch(1)
        def closed = new CountDownLatch(1)
        def streamed = new LongStream(bytes, closed, ended)
        def file = new StoredFile(FILE, new FileName('statement.txt'), bytes)
        def opens = new AtomicInteger()
        Mockito.when(files.open(new RunId(RUN), new GroupId(GROUP), FILE)).thenAnswer {
            new RunFiles.OpenedFile(file, opens.incrementAndGet() == 4
                    ? streamed : new ByteArrayInputStream([65] as byte[]))
        }
        def held = (0..<FileOpenAdmission.MOST_OPEN - 1).collect {
            admission.open(files, new RunId(RUN), new GroupId(GROUP), FILE)
        }
        def socket = connected(1024)

        when:
        ask(socket, true)
        def headers = receivedHeaders(socket)
        int received = socket.inputStream.readNBytes(1).length

        then:
        headers.status == 200
        received == 1
        ended.await(5, TimeUnit.SECONDS)
        outputState.pending.await(5, TimeUnit.SECONDS)
        closed.count == 1
        streamed.closes.get() == 0
        admission.turns.availablePermits() == 0

        when:
        ahead.set(TransferPace.allowedNanos(bytes) + TimeUnit.SECONDS.toNanos(1))

        then:
        closed.await(70, TimeUnit.SECONDS)
        streamed.closes.get() == 1
        admission.turns.availablePermits() == 1

        when:
        int rest = drained(socket.inputStream)

        then:
        received + rest < bytes
        streamed.closes.get() == 1

        cleanup:
        socket?.close()
        held*.content()*.close()
    }

    def 'a completed first download cannot close the next download on its keep alive connection'() {
        given:
        def firstClosed = new CountDownLatch(1)
        def secondReading = new CountDownLatch(1)
        def secondMayRead = new CountDownLatch(1)
        def secondClosed = new CountDownLatch(1)
        def opens = new AtomicInteger()
        def file = new StoredFile(FILE, new FileName('receipt.txt'), 1)
        Mockito.when(files.open(new RunId(RUN), new GroupId(GROUP), FILE)).thenAnswer {
            new RunFiles.OpenedFile(file, opens.incrementAndGet() == 1
                    ? new ShortStream(firstClosed)
                    : new AwaitedStream(secondReading, secondMayRead, secondClosed))
        }
        def socket = connected()

        when:
        ask(socket, false, true)
        def first = received(socket)

        then:
        first.status == 200
        first.body == [65] as byte[]
        firstClosed.await(5, TimeUnit.SECONDS)

        when:
        ahead.set(TimeUnit.SECONDS.toNanos(20))
        ask(socket, false)

        then:
        secondReading.await(5, TimeUnit.SECONDS)

        when:
        def second = received(socket)
        ahead.set(TransferPace.allowedNanos(1) + TimeUnit.SECONDS.toNanos(1))

        then:
        second.status == 200
        second.body == [66] as byte[]
        secondClosed.count == 1

        when:
        secondMayRead.countDown()

        then:
        secondClosed.await(5, TimeUnit.SECONDS)
        opens.get() == 2

        when:
        ask(socket, false)
        def third = received(socket)

        then:
        third.status == 200
        third.body == [66] as byte[]
        opens.get() == 3

        cleanup:
        secondMayRead?.countDown()
        socket?.close()
    }

    def 'a HEAD download closes its source without a body and leaves the connection ready for GET'() {
        given:
        def closes = new AtomicInteger()
        def file = new StoredFile(FILE, new FileName('Picture.txt'), 1)
        Mockito.when(files.open(new RunId(RUN), new GroupId(GROUP), FILE)).thenAnswer {
            new RunFiles.OpenedFile(file, new ByteArrayInputStream([65] as byte[]) {
                @Override void close() { closes.incrementAndGet() }
            })
        }
        def socket = connected()

        when:
        socket.outputStream.write(("HEAD /api/groups/${GROUP}/runs/${RUN}/files/${FILE} HTTP/1.1\r\n" +
                'Host: localhost\r\nConnection: keep-alive\r\n\r\n').getBytes(ISO_8859_1))
        socket.outputStream.flush()
        def head = receivedHeaders(socket)

        then:
        head.status == 200
        head.fields['content-length'] == '1'
        head.fields['content-type'] == 'application/octet-stream'
        head.fields['content-disposition'].startsWith('attachment;')
        head.fields['content-disposition'].contains('Picture.txt')
        head.fields['x-content-type-options'] == 'nosniff'
        head.fields['content-security-policy'] == "sandbox; default-src 'none'"
        head.fields['cache-control'] == 'private, no-store'
        new PollingConditions(timeout: 5).eventually {
            assert closes.get() == 1
            assert admission.turns.availablePermits() == FileOpenAdmission.MOST_OPEN
        }

        when:
        ask(socket, false)
        def get = received(socket)

        then:
        get.status == 200
        get.body == [65] as byte[]
        new PollingConditions(timeout: 5).eventually {
            assert closes.get() == 2
            assert admission.turns.availablePermits() == FileOpenAdmission.MOST_OPEN
        }

        cleanup:
        socket?.close()
    }

    private Socket connected(int receiveBytes = 0) {
        def socket = new Socket()
        if (receiveBytes > 0) socket.receiveBufferSize = receiveBytes
        socket.connect(new InetSocketAddress('localhost', port))
        socket.soTimeout = 5_000
        socket
    }

    private static void ask(Socket socket, boolean close, boolean encoded = false) {
        String runs = encoded ? 'r%75ns' : 'runs'
        socket.outputStream.write(("GET /api/groups/${GROUP}/${runs}/${RUN}/files/${FILE} HTTP/1.1\r\n" +
                "Host: localhost\r\nConnection: ${close ? 'close' : 'keep-alive'}\r\n\r\n").getBytes(ISO_8859_1))
        socket.outputStream.flush()
    }

    private static Map received(Socket socket) {
        def headers = receivedHeaders(socket)
        int size = Integer.parseInt(headers.fields['content-length'])
        byte[] body = socket.inputStream.readNBytes(size)
        assert body.length == size
        [status: headers.status, body: body]
    }

    private static Map receivedHeaders(Socket socket) {
        String status = line(socket.inputStream)
        assert status.startsWith('HTTP/1.1 ')
        Map<String, String> fields = [:]
        String header
        while ((header = line(socket.inputStream)) != '') {
            int colon = header.indexOf(':')
            fields[header.substring(0, colon).toLowerCase(Locale.ROOT)] = header.substring(colon + 1).strip()
        }
        [status: Integer.parseInt(status.split(' ')[1]), fields: fields]
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

    private static int drained(InputStream input) {
        int received = 0
        byte[] chunk = new byte[8192]
        try {
            int count
            while ((count = input.read(chunk)) >= 0) received += count
        } catch (java.net.SocketException reset) {
            // A deadline may reset the connection, with every byte already read still counted.
        }
        received
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SocketBuffers {
        @Bean OutputState outputState() { new OutputState() }

        @Bean
        Filter observedOutput(OutputState state) {
            { request, response, onward ->
                if (state.expected > 0) {
                    response.bufferSize = Math.toIntExact(state.expected + 64 * 1024)
                }
                onward.doFilter(request, new HttpServletResponseWrapper(response as HttpServletResponse) {
                    ServletOutputStream observed
                    @Override ServletOutputStream getOutputStream() {
                        if (observed == null) {
                            def destination = super.getOutputStream()
                            observed = new ServletOutputStream() {
                                @Override boolean isReady() {
                                    boolean ready = destination.isReady()
                                    if (!ready && state.expected > 0 && state.written.get() == state.expected) {
                                        state.pending.countDown()
                                    }
                                    ready
                                }
                                @Override void setWriteListener(WriteListener listener) {
                                    destination.setWriteListener(listener)
                                }
                                @Override void write(int value) {
                                    destination.write(value)
                                    state.written.incrementAndGet()
                                }
                                @Override void write(byte[] bytes, int offset, int length) {
                                    destination.write(bytes, offset, length)
                                    state.written.addAndGet(length)
                                }
                                @Override void flush() { destination.flush() }
                                @Override void close() { destination.close() }
                            }
                        }
                        observed
                    }
                })
            } as Filter
        }

        @Bean
        WebServerFactoryCustomizer<TomcatServletWebServerFactory> smallSocketWrites() {
            { factory -> factory.addConnectorCustomizers { connector ->
                assert connector.setProperty('socket.txBufSize', '1024')
            } }
        }
    }

    private static final class OutputState {
        final AtomicLong written = new AtomicLong()
        volatile long expected
        volatile CountDownLatch pending = new CountDownLatch(1)
    }

    private static final class ShortStream extends ByteArrayInputStream {
        private final CountDownLatch closed
        ShortStream(CountDownLatch closed) { super([65] as byte[]); this.closed = closed }
        @Override void close() { closed.countDown() }
    }

    private static final class AwaitedStream extends ByteArrayInputStream {
        private final CountDownLatch reading
        private final CountDownLatch permitted
        private final CountDownLatch closed
        private boolean begun
        AwaitedStream(CountDownLatch reading, CountDownLatch permitted, CountDownLatch closed) {
            super([66] as byte[])
            this.reading = reading
            this.permitted = permitted
            this.closed = closed
        }
        @Override int read(byte[] bytes, int offset, int length) {
            if (!begun) {
                begun = true
                return super.read(bytes, offset, length)
            }
            reading.countDown()
            assert permitted.await(5, TimeUnit.SECONDS)
            super.read(bytes, offset, length)
        }
        @Override void close() { closed.countDown() }
    }

    private static final class LongStream extends InputStream {
        private final int maximum
        private final CountDownLatch closed
        private int emitted
        private final CountDownLatch eof
        final AtomicInteger closes = new AtomicInteger()

        LongStream(int maximum, CountDownLatch closed, CountDownLatch eof = null) {
            this.maximum = maximum
            this.closed = closed
            this.eof = eof
        }

        @Override int read() { emitted++ < maximum ? 65 : -1 }

        @Override int read(byte[] bytes, int offset, int length) {
            if (emitted == maximum) {
                eof?.countDown()
                return -1
            }
            int count = Math.min(length, maximum - emitted)
            Arrays.fill(bytes, offset, offset + count, (byte) 65)
            emitted += count
            count
        }

        @Override void close() { closes.incrementAndGet(); closed.countDown() }
    }
}
