package org.lilradish.lite.web

import static org.mockito.ArgumentMatchers.any
import static org.mockito.BDDMockito.given
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.core.OutputStreamAppender
import ch.qos.logback.core.encoder.LayoutWrappingEncoder
import jakarta.servlet.http.HttpServletRequest
import org.libprunus.spring.error.ApiErrorAutoConfiguration
import org.lilradish.lite.app.estate.EstateRoleGrants
import org.lilradish.lite.app.identification.UserIdentification
import org.lilradish.lite.app.standing.Holdings
import org.lilradish.lite.app.standing.StandingController
import org.lilradish.lite.domain.identity.EstateRole
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.observability.Correlation
import org.lilradish.lite.testutil.GroupRolesStoodIn
import org.lilradish.lite.testutil.KeyValuePairs
import org.lilradish.lite.testutil.SnapshottingAppender
import org.lilradish.lite.web.fixture.ActProbeController
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import spock.lang.Specification

/**
 * Which requests leave a line and what it carries, asked over a real dispatcher behind the real filters:
 * which layer answered a request is exactly what decides whether anything nearer the handler saw it.
 */
@WebMvcTest(StandingController)
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
@Import(ActProbeController)
@GroupRolesStoodIn
class RequestTraceIntegrationSpec extends Specification {

    static final UserId READER = new UserId("000001")

    static final String TRACED_AS = "2f1c9a7e-upstream-0001"

    @Autowired
    private MockMvc mockMvc

    @MockitoBean
    private UserIdentification identification

    @MockitoBean
    private EstateRoleGrants grants

    @MockitoBean
    private Holdings holdings

    private final Logger traceLogger = LoggerFactory.getLogger(RequestTrace) as Logger

    private final SnapshottingAppender logged = new SnapshottingAppender()

    def setup() {
        logged.start()
        traceLogger.addAppender(logged)
        given(grants.heldBy(any())).willReturn(EnumSet.noneOf(EstateRole))
    }

    def cleanup() {
        traceLogger.detachAppender(logged)
    }

    private MvcResult sending(String method, String address, UserId caller, boolean fromHere, String tracedAs) {
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.ofNullable(caller))
        def sent = request(HttpMethod.valueOf(method), address).header(Correlation.HEADER, tracedAs)
        mockMvc.perform(fromHere ? sent.header("Sec-Fetch-Site", "same-origin") : sent).andReturn()
    }

    private MvcResult sending(String method, String address, UserId caller, boolean fromHere) {
        sending(method, address, caller, fromHere, TRACED_AS)
    }

    /** Everything a line says, wherever it says it. */
    private static List<String> everythingIn(ILoggingEvent line) {
        [line.formattedMessage] + KeyValuePairs.of(line).values()*.toString() + line.MDCPropertyMap.values()
    }

    private static layout() {
        def root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        ((root.getAppender("CONSOLE") as OutputStreamAppender).encoder as LayoutWrappingEncoder).layout
    }

    /** Read inside the request, the identification being the one thing this application runs there. */
    def "carries the identifier the caller sent into the request and every line it logs"() {
        given:
        def carried = []
        given(identification.identify(any(HttpServletRequest))).willAnswer {
            carried << [Correlation.current(), MDC.get("correlationId")]
            Optional.of(READER)
        }

        when:
        def answered = mockMvc.perform(request(HttpMethod.GET, "/api/standing").header(Correlation.HEADER, TRACED_AS))
                .andReturn()

        then:
        answered.response.status == 200
        carried == [[TRACED_AS, TRACED_AS]]
    }

    def "replaces an identifier it cannot carry, and logs under the replacement"() {
        given:
        def carried = []
        given(identification.identify(any(HttpServletRequest))).willAnswer {
            carried << [Correlation.current(), MDC.get("correlationId")]
            Optional.of(READER)
        }

        when:
        def answered = mockMvc.perform(request(HttpMethod.GET, "/api/standing").header(Correlation.HEADER, hostile))
                .andReturn()

        then:
        answered.response.status == 200
        carried.size() == 1
        carried.first()[0] != null
        carried.first()[1] == carried.first()[0]

        and:
        carried.first()[0] != hostile

        where:
        hostile << ["t 000042] ", "bad\nlevel=ERROR", 'a" userId="000002', "", "a" * 65]
    }

    /** Asked of a refused request too: a let-go on the way out of the handler is one a refusal skips. */
    def "leaves the thread carrying nothing, whether the request was answered or refused"() {
        when:
        def answered = sending("GET", "/api/standing", caller, false)

        then:
        answered.response.status == status

        and:
        Correlation.current() == null
        MDC.get("correlationId") == null
        MDC.get("userId") == null

        where:
        caller || status
        READER || 200
        null   || 401
    }

    def "leaves one line for every request under the prefix, whichever layer answered it and however"() {
        when:
        def answered = sending(method, address, caller, fromHere)

        then:
        answered.response.status == status
        logged.list.size() == 1
        KeyValuePairs.of(logged.list.first()).subMap(["method", "status"]) == [method: method, status: status]
        KeyValuePairs.of(logged.list.first()).route == route

        and: "under the identifier the request is traced by, and the caller only once the door let one by"
        logged.list.first().MDCPropertyMap.correlationId == TRACED_AS
        logged.list.first().MDCPropertyMap.userId == user

        where:
        method    | address                          | caller | fromHere || status | route                            | user
        "GET"     | "/api/standing"                  | READER | false    || 200    | "/api/standing"                  | '"000001"'
        "GET"     | "/api/standing"                  | null   | false    || 401    | null                             | null
        "PATCH"   | "/api/standing"                  | READER | false    || 403    | null                             | null
        "PATCH"   | "/api/standing"                  | READER | true     || 405    | null                             | '"000001"'
        "OPTIONS" | "/api/standing"                  | READER | false    || 200    | null                             | '"000001"'
        "GET"     | ActProbeController.ASKING_AN_ACT | READER | false    || 403    | ActProbeController.ASKING_AN_ACT | '"000001"'
        "GET"     | "/api/there-is-no-such"          | READER | false    || 404    | "/**"                            | '"000001"'
    }

    /** An address carries whatever identifies somebody, and a query whatever somebody typed. */
    def "logs neither the address as it was sent nor anything in its query"() {
        when:
        sending("GET", address, READER, false)

        then:
        logged.list.size() == 1
        everythingIn(logged.list.first()).every { !it.contains("000501") && !it.contains("Lovelace") }

        where:
        address << ["/api/standing?search=Lovelace&user=000501", "/api/people/000501-Lovelace?search=Lovelace"]
    }

    /** The document and the files it loads are nobody's to audit, and every page load asks for them. */
    def "leaves no line for an address outside the prefix"() {
        when:
        def answered = mockMvc.perform(request(HttpMethod.GET, address).accept(MediaType.TEXT_HTML)).andReturn()

        then:
        answered.response.status == 200
        logged.list.isEmpty()

        where:
        address << ["/", "/index.html", "/system/people"]
    }

    /** Read off the console layout the configuration sets, so its keys are held level with the filter's. */
    def "shows the line, who called and what it was traced by through the configured layout"() {
        given:
        sending("GET", "/api/standing", READER, false)

        when:
        def rendered = layout().doLayout(logged.list.first())

        then:
        rendered.contains("[" + TRACED_AS + ' "000001"] ')
        ['method="GET"', 'route="/api/standing"', 'status="200"'].every { rendered.contains(it) }
    }

    def "shows who called quoted, so a user number spelt with brackets and quotes cannot pose as another"() {
        given:
        sending("GET", "/api/standing", new UserId('000001"] [x 000042'), false)

        when:
        def rendered = layout().doLayout(logged.list.first())

        then:
        rendered.contains("[" + TRACED_AS + ' "000001\\"] [x 000042"] ')

        and:
        !rendered.contains('"000001"]')
    }

    def "shows the brackets empty on a line logged outside any request"() {
        given:
        def root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        sending("GET", "/api/standing", READER, false)

        when:
        def rendered = layout().doLayout(new LoggingEvent(getClass().name, root, Level.INFO, "after", null, null))

        then:
        rendered.contains("[ ] ")

        and:
        !rendered.contains("000001")
        !rendered.contains(TRACED_AS)
    }
}
