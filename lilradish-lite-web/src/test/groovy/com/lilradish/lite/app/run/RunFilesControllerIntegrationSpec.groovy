package com.lilradish.lite.app.run

import static org.mockito.ArgumentMatchers.any
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get

import com.lilradish.lite.app.estate.EstateRoleGrants
import com.lilradish.lite.app.files.FileOpenAdmission
import com.lilradish.lite.app.files.RunFiles
import com.lilradish.lite.app.group.GroupRoles
import com.lilradish.lite.app.identification.UserIdentification
import com.lilradish.lite.domain.file.FileName
import com.lilradish.lite.domain.file.StoredFile
import com.lilradish.lite.domain.identity.EstateRole
import com.lilradish.lite.domain.identity.GroupId
import com.lilradish.lite.domain.identity.GroupRole
import com.lilradish.lite.domain.identity.UserId
import com.lilradish.lite.domain.run.RunId
import com.lilradish.lite.testutil.ReadyServletStreams
import com.lilradish.lite.web.FileTransfers
import jakarta.servlet.Filter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.nio.charset.StandardCharsets
import org.libprunus.spring.error.ApiErrorAutoConfiguration
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper

@WebMvcTest(RunFilesController)
@Import([FileOpenAdmission, ReadyServletStreams, Connections])
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
class RunFilesControllerIntegrationSpec extends Specification {

    static final UUID GROUP = UUID.fromString("00000003-0000-4000-8000-000000001401")
    static final UUID RUN = UUID.fromString("00000008-0000-4000-8000-000000001401")
    static final UUID FILE = UUID.fromString("0000000f-0000-4000-8000-000000001401")
    static final UserId READER = new UserId("001401")
    static final String ADDRESS = "/api/groups/${GROUP}/runs/${RUN}/files/${FILE}"
    static final JsonMapper JSON = JsonMapper.builder().build()

    @Autowired MockMvc mockMvc
    @Autowired FileOpenAdmission admission
    @Autowired FileTransfers transfers
    @MockitoBean UserIdentification identification
    @MockitoBean EstateRoleGrants grants
    @MockitoBean GroupRoles groupRoles
    @MockitoBean JdbcClient database
    @MockitoBean RunFiles files

    private void holding(Set<GroupRole> held) {
        Mockito.when(identification.identify(any(HttpServletRequest))).thenReturn(Optional.of(READER))
        Mockito.when(grants.heldBy(READER)).thenReturn(EnumSet.allOf(EstateRole))
        Mockito.when(groupRoles.heldBy(READER, new GroupId(GROUP))).thenReturn(held)
    }

    private void readable(boolean found) {
        def statement = Mockito.mock(JdbcClient.StatementSpec, Mockito.RETURNS_SELF)
        def query = Mockito.mock(JdbcClient.MappedQuerySpec)
        Mockito.when(database.sql(any(String))).thenReturn(statement)
        Mockito.when(statement.query(Integer)).thenReturn(query)
        Mockito.when(query.optional()).thenReturn(found ? Optional.of(1) : Optional.empty())
    }

    private boolean runCheckedBeforeHolder() {
        def order = Mockito.inOrder(database, files)
        order.verify(database).sql(any(String))
        order.verify(files).open(new RunId(RUN), new GroupId(GROUP), FILE)
        true
    }

    def "a readable run and its held file download as a private attachment and close its stream"() {
        given:
        holding(EnumSet.of(GroupRole.OVERSEER))
        readable(true)
        def bytes = "Demo Sample bytes".getBytes(StandardCharsets.UTF_8)
        def stream = new ClosingStream(bytes)
        def file = new StoredFile(FILE, new FileName("Résumé.txt"), bytes.length)
        Mockito.when(files.open(new RunId(RUN), new GroupId(GROUP), FILE))
                .thenReturn(new RunFiles.OpenedFile(file, stream))

        when:
        def response = mockMvc.perform(get(ADDRESS)).andReturn().response

        then:
        response.status == 200
        response.contentAsByteArray == bytes
        response.getHeader("Content-Type") == "application/octet-stream"
        response.getHeader("Content-Disposition").startsWith("attachment;")
        response.getHeader("Content-Disposition").contains("filename*=UTF-8''")
        response.getHeader("X-Content-Type-Options") == "nosniff"
        response.getHeader("Content-Security-Policy") == "sandbox; default-src 'none'"
        response.getHeader("Cache-Control").contains("no-store")
        response.getHeader("Content-Length") == bytes.length.toString()
        stream.closed
        runCheckedBeforeHolder()
    }

    def "an unreadable run and a missing holder answer with the same existing refusal"() {
        given:
        holding(EnumSet.of(GroupRole.OVERSEER))
        readable(found)
        if (found) {
            Mockito.when(files.open(new RunId(RUN), new GroupId(GROUP), FILE))
                    .thenThrow(RunRefusal.RUN_NOT_IN_VIEW.raised())
        }

        when:
        def response = mockMvc.perform(get(ADDRESS)).andReturn().response
        def body = JSON.readTree(response.contentAsString)

        then:
        response.status == 404
        body.get("code").asString() == "RUN_NOT_IN_VIEW"
        Mockito.mockingDetails(files).invocations.count { it.method.name == "open" } == (found ? 1 : 0)

        where:
        found << [false, true]
    }

    def "an unsuccessful stream is closed"() {
        given:
        holding(EnumSet.of(GroupRole.OVERSEER))
        readable(true)
        def stream = new FailingStream()
        def file = new StoredFile(FILE, new FileName("Demo Sample.txt"), 1)
        Mockito.when(files.open(new RunId(RUN), new GroupId(GROUP), FILE))
                .thenReturn(new RunFiles.OpenedFile(file, stream))

        when:
        def response = mockMvc.perform(get(ADDRESS)).andReturn().response

        then:
        response.status == 500
        stream.closed
    }

    def "a failure while preparing attachment headers closes the opened file"() {
        given:
        holding(EnumSet.of(GroupRole.OVERSEER))
        readable(true)
        def stream = new ClosingStream("Demo Sample bytes".getBytes(StandardCharsets.UTF_8))
        def file = new StoredFile(FILE, new FileName("Demo Sample.txt"), 17)
        Mockito.when(files.open(new RunId(RUN), new GroupId(GROUP), FILE))
                .thenReturn(new RunFiles.OpenedFile(file, stream))
        def request = new MockHttpServletRequest("GET", ADDRESS)
        request.setAttribute(com.lilradish.lite.web.ActAdmission.name + ".group", new GroupId(GROUP))
        request.setAttribute(com.lilradish.lite.web.CallerAdmission.name + ".caller", READER)
        com.lilradish.lite.web.ReadyFileConnections.install(request)
        def response = Mockito.mock(HttpServletResponse)
        Mockito.doThrow(new IllegalStateException("Demo Sample header failed"))
                .when(response).setHeader("Content-Type", "application/octet-stream")

        when:
        new RunFilesController(database, groupRoles, files, new FileOpenAdmission(), transfers).download(RUN.toString(), FILE.toString(), request, response)

        then:
        def failure = thrown(IllegalStateException)
        failure.message == "Demo Sample header failed"
        stream.closed
        runCheckedBeforeHolder()
    }

    def "a fifth download waits then answers busy without opening storage and a released turn serves another"() {
        given:
        holding(EnumSet.of(GroupRole.OVERSEER))
        readable(true)
        def bytes = "Demo Sample bytes".getBytes(StandardCharsets.UTF_8)
        def file = new StoredFile(FILE, new FileName("Demo Sample.txt"), bytes.length)
        Mockito.when(files.open(new RunId(RUN), new GroupId(GROUP), FILE))
                .thenAnswer { new RunFiles.OpenedFile(file, new ClosingStream(bytes)) }
        def held = (0..<FileOpenAdmission.MOST_OPEN).collect {
            admission.open(files, new RunId(RUN), new GroupId(GROUP), FILE)
        }

        when:
        def busy = mockMvc.perform(get(ADDRESS)).andReturn().response

        then:
        busy.status == 503
        JSON.readTree(busy.contentAsString).get("code").asString() == "SERVICE_BUSY"
        Mockito.mockingDetails(files).invocations.count { it.method.name == "open" } == 4

        when:
        held[0].content().close()
        def served = mockMvc.perform(get(ADDRESS)).andReturn().response

        then:
        served.status == 200
        served.contentAsByteArray == bytes

        cleanup:
        held*.content()*.close()
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Connections {
        @Bean
        Filter testDownloadConnections() {
            { request, response, onward ->
                com.lilradish.lite.web.ReadyFileConnections.install(request)
                onward.doFilter(request, response)
            } as Filter
        }
    }

    private static class ClosingStream extends ByteArrayInputStream {
        boolean closed

        ClosingStream(byte[] bytes) { super(bytes) }

        @Override void close() throws IOException {
            closed = true
            super.close()
        }
    }

    private static class FailingStream extends InputStream {
        boolean closed

        @Override int read() throws IOException { throw new IOException("Demo Sample stream failed") }

        @Override void close() throws IOException { closed = true }
    }

}
