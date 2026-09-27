package org.lilradish.lite.web

import static java.nio.charset.StandardCharsets.ISO_8859_1
import static org.mockito.ArgumentMatchers.any
import static org.mockito.BDDMockito.given

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import jakarta.servlet.http.HttpServletRequest
import org.lilradish.lite.app.estate.EstateRoleGrants
import org.lilradish.lite.app.identification.UserIdentification
import org.lilradish.lite.domain.identity.EstateRole
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.testutil.ApplicationStore
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import spock.lang.Specification

/**
 * A query string the servlet container cannot decode, sent to the container this application really
 * runs in. How the container decodes a query is settled below anything a mock dispatcher stands in
 * for — a mock request is handed its parameters already decoded — so the request is written as bytes
 * on a socket, the one client that neither normalises what it is given nor refuses to send it.
 *
 * <p>The answer is also read for what it leaves in the log: a request of the caller's own making is
 * not a fault of this server's, and a line at error level for one is a page nobody should get.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ApplicationStore)
class RequestLineIntegrationSpec extends Specification {

    static final UserId READER = new UserId("000001")

    @LocalServerPort
    private int port

    @MockitoBean
    private UserIdentification identification

    @MockitoBean
    private EstateRoleGrants grants

    private final Logger root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger

    private final ListAppender<ILoggingEvent> logged = new ListAppender<>()

    def setup() {
        logged.start()
        root.addAppender(logged)
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(READER))
        given(grants.heldBy(READER)).willReturn(EnumSet.of(EstateRole.STEWARD))
    }

    def cleanup() {
        root.detachAppender(logged)
    }

    /** The whole answer as it came off the wire, status line first. */
    private String sending(String requestLine) {
        new Socket("localhost", port).withCloseable { socket ->
            socket.outputStream.write("${requestLine}\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                    .getBytes(ISO_8859_1))
            socket.outputStream.flush()
            socket.inputStream.getText(ISO_8859_1.name())
        }
    }

    /**
     * The container refuses to decode such a query and says so by raising on the first read of any
     * parameter, quoting the bytes it could not decode. That is answered as a request that could not
     * be read, saying nothing about which bytes, and logged nowhere.
     */
    def "a query the container cannot decode is refused as the caller's to fix, quoting nothing and logging nothing"() {
        when:
        def answer = sending("GET /api/pool/people?${query} HTTP/1.1")

        then:
        answer.readLines().first().split(" ")[1] == "400"
        answer.contains('"code":"BAD_REQUEST"')
        !answer.contains("%ZZ")
        !answer.contains("detail")

        and:
        logged.list.findAll { it.level == Level.ERROR } == []

        where:
        query << ["filter=x%ZZ", "sort=userId&cursor=AA%ZZ", "filter=%E0%A4%A"]
    }
}
