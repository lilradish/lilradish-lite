package org.lilradish.lite.app.run

import static java.nio.charset.StandardCharsets.ISO_8859_1
import static org.mockito.ArgumentMatchers.any
import static org.mockito.BDDMockito.given

import jakarta.servlet.http.HttpServletRequest
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.lilradish.lite.app.estate.EstateRoleGrants
import org.lilradish.lite.app.files.FileOpenAdmission
import org.lilradish.lite.app.files.RunFiles
import org.lilradish.lite.app.identification.UserIdentification
import org.lilradish.lite.app.run.fixture.CodeWorkflow
import org.lilradish.lite.domain.file.FileName
import org.lilradish.lite.domain.file.MediaTypeName
import org.lilradish.lite.domain.file.StoredFile
import org.lilradish.lite.domain.identity.EstateRole
import org.lilradish.lite.domain.identity.GroupId
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.testutil.ApplicationStore
import org.lilradish.lite.testutil.library.LibraryStore
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.bean.override.mockito.MockitoBean
import spock.lang.Specification

/** A stopped reader tested against Tomcat's blocking socket writes rather than MockMvc's memory response. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.tomcat.connection-timeout=300ms")
@Import(ApplicationStore)
class RunFilesDownloadSocketIntegrationSpec extends Specification {

    static final UUID GROUP = UUID.fromString(CodeWorkflow.GROUP)
    static final UUID RUN = UUID.fromString(CodeWorkflow.RUN)
    static final UUID FILE = UUID.fromString("0000000f-0000-4000-8000-00000000f501")
    static final int SIZE = 64 * 1024 * 1024

    @LocalServerPort int port
    @Autowired JdbcClient database
    @Autowired FileOpenAdmission admission
    @MockitoBean RunFiles files
    @MockitoBean UserIdentification identification
    @MockitoBean EstateRoleGrants grants

    def setup() {
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
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(CodeWorkflow.ANN_USER))
        given(grants.heldBy(CodeWorkflow.ANN_USER)).willReturn(EnumSet.allOf(EstateRole))
    }

    def "a client that stops reading is cut off by Tomcat and returns its download permit"() {
        given:
        def opens = new AtomicInteger()
        def stopped = new CountDownLatch(1)
        def file = new StoredFile(FILE, new FileName("Demo Sample.txt"), new MediaTypeName("text/plain"), SIZE)
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
        stopped.await(5, TimeUnit.SECONDS)
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

        cleanup:
        socket?.close()
        held*.content()*.close()
        released?.content()?.close()
    }

    private static final class LongStream extends InputStream {
        private final int maximum
        private final CountDownLatch closed
        private int emitted

        LongStream(int maximum, CountDownLatch closed) {
            this.maximum = maximum
            this.closed = closed
        }

        @Override int read() { emitted++ < maximum ? 65 : -1 }

        @Override int read(byte[] bytes, int offset, int length) {
            if (emitted == maximum) return -1
            int count = Math.min(length, maximum - emitted)
            Arrays.fill(bytes, offset, offset + count, (byte) 65)
            emitted += count
            count
        }

        @Override void close() { closed.countDown() }
    }
}
