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
 * A body the client stops sending partway, to the container this application really runs in: how the container
 * reports a read that cannot finish is settled below anything a mock dispatcher stands in for, so the request is
 * written as bytes on a socket, and the container's read timeout is cut short so that waiting it out is quick.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.tomcat.connection-timeout=300ms")
@Import(ApplicationStore)
class ClientGoneIntegrationSpec extends Specification {

    static final UserId READER = new UserId("000001")

    /** A body taken as JSON, and a change taking none, which is left waiting only while nothing of one arrives. */
    static final String BRING_IN = "POST /api/pool/people"

    static final String REMOVE = "DELETE /api/pool/people/00000001-0000-4000-8000-000000000002"

    /** Declares more than is sent after it, so the server is left waiting for the rest. */
    static final String HEADERS = " HTTP/1.1\r\nHost: localhost\r\nSec-Fetch-Site: same-origin\r\n" +
            "Content-Type: application/json\r\nContent-Length: 64\r\nConnection: close\r\n\r\n"

    static final String PART_OF_THE_BODY = '{"userId":'

    @LocalServerPort
    private int port

    @MockitoBean
    private UserIdentification identification

    @MockitoBean
    private EstateRoleGrants grants

    private final Logger root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger

    private final Logger outlet = LoggerFactory.getLogger(RefusalOutlet) as Logger

    private final ListAppender<ILoggingEvent> logged = new ListAppender<>()

    def setup() {
        logged.start()
        root.addAppender(logged)
        outlet.level = Level.DEBUG
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(READER))
        given(grants.heldBy(READER)).willReturn(EnumSet.of(EstateRole.STEWARD))
    }

    def cleanup() {
        outlet.level = null
        root.detachAppender(logged)
    }

    /** Whatever came back on the wire once the client stopped sending, closing its half first where asked. */
    private String sendingPartOfTheBody(String requestLine, String sent, boolean closingItsHalf) {
        new Socket("localhost", port).withCloseable { socket ->
            socket.soTimeout = 10_000
            socket.outputStream.write((requestLine + HEADERS + sent).getBytes(ISO_8859_1))
            socket.outputStream.flush()
            if (closingItsHalf) {
                socket.shutdownOutput()
            }
            socket.inputStream.getText(ISO_8859_1.name())
        }
    }

    /**
     * The container has already settled the answer by the time the read fails, so this server writes none of its
     * own: the client's closing is answered as a bad request and its stalling as a timeout, where either still reads.
     */
    def "a body the client stops sending is answered as the client's doing, and logged once at debug and nowhere higher"() {
        when:
        def answer = sendingPartOfTheBody(requestLine, sent, closingItsHalf)

        then:
        answer.readLines().first() == "HTTP/1.1 ${status} "
        answer.contains(/"code":"${code}"/)
        !answer.contains("INTERNAL")

        and:
        logged.list.findAll { it.level.isGreaterOrEqual(Level.WARN) } == []
        def told = logged.list.findAll { it.loggerName == RefusalOutlet.name }
        told*.level == [Level.DEBUG]
        told.first().formattedMessage.startsWith("Looks like the client has gone away")
        told.first().throwableProxy == null

        where:
        requestLine | sent             | closingItsHalf || status | code
        BRING_IN    | PART_OF_THE_BODY | true           || 400    | "BAD_REQUEST"
        BRING_IN    | PART_OF_THE_BODY | false          || 408    | "REQUEST_TIMEOUT"
        REMOVE      | ""               | true           || 400    | "BAD_REQUEST"
        REMOVE      | ""               | false          || 408    | "REQUEST_TIMEOUT"
    }
}
