package org.lilradish.lite.web

import static java.nio.charset.StandardCharsets.ISO_8859_1
import static org.mockito.ArgumentMatchers.any
import static org.mockito.BDDMockito.given

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import jakarta.servlet.http.HttpServletRequest
import org.lilradish.lite.app.estate.EstateRoleGrants
import org.lilradish.lite.app.identification.UserIdentification
import org.lilradish.lite.domain.identity.EstateRole
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.testutil.ApplicationStore
import org.lilradish.lite.testutil.SnapshottingAppender
import org.mockito.Mockito
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "logging.level.org.springframework.web=DEBUG")
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

    private final Logger web = LoggerFactory.getLogger("org.springframework.web") as Logger

    private Level previousWebLevel

    private final SnapshottingAppender logged = new SnapshottingAppender()

    def setup() {
        previousWebLevel = web.level
        logged.start()
        root.addAppender(logged)
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(READER))
        given(grants.heldBy(READER)).willReturn(EnumSet.of(EstateRole.STEWARD))
    }

    def cleanup() {
        web.level = previousWebLevel
        root.detachAppender(logged)
        logged.stop()
    }

    /** The whole answer as it came off the wire, status line first. */
    private String sending(String requestLine, Map<String, String> headers = [:], String body = "") {
        new Socket("localhost", port).withCloseable { socket ->
            socket.soTimeout = 10_000
            def fields = headers.collect { name, value -> "${name}: ${value}\r\n" }.join()
            socket.outputStream.write("${requestLine}\r\nHost: localhost\r\n${fields}Connection: close\r\n\r\n${body}"
                    .getBytes(ISO_8859_1))
            socket.outputStream.flush()
            socket.inputStream.getText(ISO_8859_1.name())
        }
    }

    private void identificationAskedOnce() {
        Mockito.verify(identification).identify(any(HttpServletRequest))
        Mockito.verifyNoMoreInteractions(identification)
    }

    private void grantsAskedOnce() {
        Mockito.verify(grants).heldBy(READER)
        Mockito.verifyNoMoreInteractions(grants)
    }

    /**
     * The container refuses to decode such a query and says so by raising on the first read of any
     * parameter, quoting the bytes it could not decode. That is answered as a request that could not
     * be read, saying nothing about which bytes, and logged nowhere.
     */
    def "a query the container cannot decode is refused as the caller's to fix, quoting nothing and logging nothing"() {
        given:
        web.level = level

        when:
        def answer = sending("GET /api/pool/people?${query} HTTP/1.1")

        then:
        answer.readLines().first().split(" ")[1] == "400"
        answer.contains('"code":"BAD_REQUEST"')
        !answer.contains("%ZZ")
        !answer.contains("detail")
        !answer.contains("cause")
        answer.contains("Cache-Control: no-store")
        answer.contains("Content-Type: application/problem+json")
        answer.substring(answer.indexOf("\r\n\r\n") + 4) ==
                '57\r\n{"instance":"/api/pool/people","status":400,"title":"Bad Request","code":"BAD_REQUEST"}\r\n0\r\n\r\n'

        and:
        logged.list.findAll { it.level == Level.ERROR } == []
        def traced = logged.list.findAll { it.loggerName == RequestTrace.name && it.formattedMessage == "Answered" }
        traced.size() == 1
        traced.first().mdcPropertyMap["userId"] == '"000001"'
        identificationAskedOnce()
        Mockito.verifyNoInteractions(grants)

        where:
        [level, query] << [[Level.INFO, Level.DEBUG],
                ["filter=x%ZZ", "sort=userId&cursor=AA%ZZ", "filter=%E0%A4%A"]].combinations()
    }

    def "an undecodable query on a head request is refused without a body or an error log"() {
        given:
        web.level = level

        when:
        def answer = sending("HEAD /api/pool/people?${query} HTTP/1.1")

        then:
        answer.readLines().first().split(" ")[1] == "400"
        answer.contains("Cache-Control: no-store")
        answer.contains("Content-Type: application/problem+json")
        answer.substring(answer.indexOf("\r\n\r\n") + 4).isEmpty()
        !answer.contains("%ZZ")
        !answer.contains("detail")
        logged.list.findAll { it.level == Level.ERROR } == []
        identificationAskedOnce()
        Mockito.verifyNoInteractions(grants)

        where:
        [level, query] << [[Level.INFO, Level.DEBUG],
                ["filter=x%ZZ", "sort=userId&cursor=AA%ZZ", "filter=%E0%A4%A"]].combinations()
    }

    def "a decoded query still reaches the list for a get or a head request"() {
        given:
        web.level = Level.DEBUG

        when:
        def answer = sending("${method} /api/pool/people${query} HTTP/1.1")

        then:
        answer.readLines().first().split(" ")[1] == "200"
        answer.contains("Cache-Control: no-store")
        !answer.contains('"code":')
        (answer.contains('"items":')) == (method == "GET")
        logged.list.findAll { it.level == Level.ERROR } == []
        identificationAskedOnce()
        grantsAskedOnce()

        where:
        [method, query] << [["GET", "HEAD"],
                ["", "?filter=Demo+Sample", "?filter=Demo%20Sample", "?filter=%25ZZ"]].combinations()
    }

    def "a decoded unknown or repeated parameter keeps the list's refusal"() {
        given:
        web.level = Level.DEBUG

        when:
        def answer = sending("GET /api/pool/people?${query} HTTP/1.1")

        then:
        answer.readLines().first().split(" ")[1] == "400"
        answer.contains('"code":"' + code + '"')
        !answer.contains('"code":"BAD_REQUEST"')
        answer.contains("Cache-Control: no-store")
        logged.list.findAll { it.level == Level.ERROR } == []
        identificationAskedOnce()
        grantsAskedOnce()

        where:
        query                         || code
        "unknown=Demo+Sample"         || "PARAMETER_UNKNOWN"
        "sort=userId&sort=-userId"     || "LIST_SORT_UNUSABLE"
        "filter=one&filter=two"        || "LIST_FILTER_UNUSABLE"
    }

    def "an unsigned caller is refused before a query is decoded on every spelling of an application address"() {
        given:
        web.level = level
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.empty())

        when:
        def answer = sending("GET ${address}?filter=x%ZZ HTTP/1.1")

        then:
        answer.readLines().first().split(" ")[1] == "401"
        answer.contains('"code":"NOT_SIGNED_IN"')
        !answer.contains("%ZZ")
        !answer.contains('"code":"BAD_REQUEST"')
        answer.contains("Cache-Control: no-store")
        logged.list.findAll { it.level == Level.ERROR } == []
        identificationAskedOnce()
        Mockito.verifyNoInteractions(grants)

        where:
        [level, address] << [[Level.INFO, Level.DEBUG],
                ["/api/pool/people", "/%61pi/pool/people", "/api/there-is-no-such-thing"]].combinations()
    }

    def "a change from another origin is refused before identity or query decoding"() {
        given:
        web.level = level
        def form = "name=Demo+Sample"

        when:
        def answer = sending("POST /api/pool/people?filter=x%ZZ HTTP/1.1", [
                "Sec-Fetch-Site": "cross-site",
                "Content-Type": "application/x-www-form-urlencoded",
                "Content-Length": form.getBytes(ISO_8859_1).length.toString(),
        ], form)

        then:
        answer.readLines().first().split(" ")[1] == "403"
        answer.contains('"code":"ORIGIN_UNVERIFIED"')
        !answer.contains("%ZZ")
        !answer.contains('"code":"BAD_REQUEST"')
        answer.contains("Cache-Control: no-store")
        logged.list.findAll { it.level == Level.ERROR } == []
        Mockito.verifyNoInteractions(identification, grants)

        where:
        level << [Level.INFO, Level.DEBUG]
    }

    def "a page outside the application addresses needs no identity or query whitelist"() {
        given:
        web.level = Level.DEBUG

        when:
        def answer = sending("GET /index.html?unknown=%25ZZ HTTP/1.1")

        then:
        answer.readLines().first().split(" ")[1] == "200"
        answer.contains("Content-Type: text/html")
        !answer.contains("Cache-Control: no-store")
        !answer.contains('"code":')
        logged.list.findAll { it.level == Level.ERROR } == []
        Mockito.verifyNoInteractions(identification, grants)
    }
}
