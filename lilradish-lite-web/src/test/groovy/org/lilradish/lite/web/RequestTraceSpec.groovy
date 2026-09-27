package org.lilradish.lite.web

import static org.lilradish.lite.testutil.FailureDispatch.failedOver

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import jakarta.servlet.FilterChain
import org.lilradish.lite.app.identification.UserIdentification
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.observability.Correlation
import org.lilradish.lite.testutil.KeyValuePairs
import org.lilradish.lite.testutil.SnapshottingAppender
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.server.RequestPath
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.servlet.HandlerExceptionResolver
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.util.ServletRequestPathUtils
import spock.lang.Specification

/**
 * The trace and the door driven through {@code doFilter} as the container runs them, and the second
 * pass made as the container makes it after a failure.
 */
class RequestTraceSpec extends Specification {

    static final UserId STEWARD = new UserId("000001")

    static final String GUARDED = CallerAdmission.THIS_APPLICATION_ANSWERS + "/standing"

    static final String SENT_AS = "2f1c9a7e-upstream-0001"

    static final List<String> NOTHING_CARRIED = [null, null, null]

    private int identified = 0

    private final RequestTrace trace = new RequestTrace()

    private final CallerAdmission door = new CallerAdmission(
            { asked -> identified++; Optional.of(STEWARD) } as UserIdentification, Stub(HandlerExceptionResolver))

    private final Logger traceLogger = LoggerFactory.getLogger(RequestTrace) as Logger

    private final SnapshottingAppender logged = new SnapshottingAppender()

    def setup() {
        logged.start()
        traceLogger.addAppender(logged)
    }

    def cleanup() {
        traceLogger.detachAppender(logged)
        Correlation.clear()
        MDC.clear()
    }

    /** Both filters in the order they stand, around whatever answers behind them. */
    private void passing(MockHttpServletRequest request, MockHttpServletResponse response, Closure answering) {
        trace.doFilter(request, response, { asked, answer -> door.doFilter(asked, answer, answering as FilterChain) }
                as FilterChain)
    }

    private static List<String> contextNow() {
        [Correlation.current(), MDC.get("correlationId"), MDC.get("userId")]
    }

    private List<ILoggingEvent> answeredLines() {
        logged.list.findAll { it.formattedMessage == "Answered" }
    }

    def "carries the caller quoted with its quotes escaped, so it cannot pose as what is printed beside it"() {
        when:
        RequestTrace.carryCaller(new UserId(spelled))

        then:
        MDC.get("userId") == carried

        where:
        spelled           || carried
        "000001"          || '"000001"'
        'a] [x "b\\c'     || '"a] [x \\"b\\\\c"'
    }

    /** Parsed once for this filter and the door, and whatever was cached before is what is left after. */
    def "hands the door the parse of its own address, and puts back the parse it found"() {
        given:
        def request = new MockHttpServletRequest("GET", GUARDED)
        def before = RequestPath.parse("/before", null)
        ServletRequestPathUtils.setParsedRequestPath(before, request)
        def seenBehind = []

        when:
        passing(request, new MockHttpServletResponse(), { asked, answer ->
            seenBehind << ServletRequestPathUtils.getParsedRequestPath(asked).value()
        })

        then:
        seenBehind == [GUARDED]
        identified == 1

        and:
        ServletRequestPathUtils.getParsedRequestPath(request).is(before)
    }

    /** Every file of every page load would otherwise draw an identifier nothing reads. */
    def "settles nothing and logs nothing at an address this application does not answer"() {
        given:
        def request = new MockHttpServletRequest("GET", "/index.html")
        def carried = []
        def capturing = { asked, answer -> carried << contextNow() }

        when:
        passing(request, new MockHttpServletResponse(), capturing)
        passing(failedOver(request), new MockHttpServletResponse(), capturing)

        then:
        carried == [NOTHING_CARRIED, NOTHING_CARRIED]
        logged.list.isEmpty()
    }

    /**
     * Answered by the container once this has run, so the response does not yet hold the answer. The
     * container logs what escaped too, but only after the trace is cleared, so this line is the one carrying it.
     */
    def "logs a failure that escaped once, under the request's trace, as the fault it is answered with"() {
        given:
        def request = new MockHttpServletRequest("GET", GUARDED)
        request.addHeader(Correlation.HEADER, SENT_AS)

        when:
        passing(request, new MockHttpServletResponse(), { asked, answer -> throw new IllegalStateException("escaped") })

        then:
        def escaped = thrown(IllegalStateException)
        escaped.message == "escaped"
        logged.list.findAll { it.level == Level.ERROR }*.throwableProxy*.message == ["escaped"]
        logged.list.find { it.level == Level.ERROR }.MDCPropertyMap == [correlationId: SENT_AS, userId: '"000001"']
        answeredLines()*.keyValuePairs*.find { it.key == "status" }*.value == [500]

        and: "the failure and the answer, and nothing carried on past the request"
        logged.list.size() == 2
        contextNow() == NOTHING_CARRIED
    }

    def "leaves no second line for the dispatch reporting a failure that escaped"() {
        given:
        def request = new MockHttpServletRequest("GET", GUARDED)
        try {
            passing(request, new MockHttpServletResponse(), { asked, answer -> throw new IllegalStateException() })
        } catch (IllegalStateException escaped) {
        }

        when:
        passing(failedOver(request), new MockHttpServletResponse(), { asked, answer -> })

        then:
        answeredLines().size() == 1

        and:
        contextNow() == NOTHING_CARRIED
    }

    def "logs a request and the dispatch reporting its failure under the identifier the caller sent"() {
        given:
        def request = new MockHttpServletRequest("GET", GUARDED)
        request.addHeader(Correlation.HEADER, SENT_AS)
        def carried = []
        def capturing = { asked, answer -> carried << contextNow() }

        when:
        passing(request, new MockHttpServletResponse(), capturing)
        passing(failedOver(request), new MockHttpServletResponse(), capturing)

        then:
        carried == [[SENT_AS, SENT_AS, '"000001"'], [SENT_AS, SENT_AS, '"000001"']]

        and: "the caller identified once, and the thread left carrying none of it"
        identified == 1
        contextNow() == NOTHING_CARRIED
    }

    def "mints an identifier for a request that sent none, and logs the dispatch reporting its failure under it"() {
        given:
        def request = new MockHttpServletRequest("GET", GUARDED)
        def carried = []
        def capturing = { asked, answer -> carried << contextNow() }

        when:
        passing(request, new MockHttpServletResponse(), capturing)
        passing(failedOver(request), new MockHttpServletResponse(), capturing)

        then:
        carried.first()[0] != null
        carried == [[carried.first()[0], carried.first()[0], '"000001"']] * 2

        and:
        contextNow() == NOTHING_CARRIED
    }

    def "replaces an identifier it cannot carry with one of its own, and logs under that one"() {
        given:
        def request = new MockHttpServletRequest("GET", GUARDED)
        request.addHeader(Correlation.HEADER, hostile)
        def carried = []

        when:
        passing(request, new MockHttpServletResponse(), { asked, answer -> carried << contextNow() })

        then:
        carried.first()[0] != null
        carried.first()[0] != hostile
        carried.first()[1] == carried.first()[0]

        where:
        hostile << ["t 000042] ", "a]", "bad\nlevel=ERROR", "x" + Character.toString(0x85), 'a"', "a" * 65]
    }

    def "leaves one line for a request, as its method, the pattern it matched, its status and its time"() {
        given:
        def request = new MockHttpServletRequest(method, GUARDED)
        request.queryString = "search=Lovelace"
        request.addHeader("Sec-Fetch-Site", "same-origin")

        when:
        passing(request, new MockHttpServletResponse(), { asked, answer ->
            asked.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, GUARDED)
            answer.status = 409
        })

        then:
        answeredLines().size() == 1
        KeyValuePairs.of(answeredLines().first()).subMap(["method", "route", "status"]) ==
                [method: recorded, route: GUARDED, status: 409]
        KeyValuePairs.of(answeredLines().first()).durationMs instanceof Long

        and: "nothing else, and nothing of the query"
        KeyValuePairs.of(answeredLines().first()).keySet() == ["method", "route", "status", "durationMs"] as Set
        !KeyValuePairs.of(answeredLines().first()).values()*.toString().any { it.contains("Lovelace") }

        where:
        method                 || recorded
        "GET"                  || "GET"
        "PATCH"                || "PATCH"
        "FROBNICATE" * 1000    || "OTHER"
        "get"                  || "OTHER"
    }

    def "logs no pattern for a request that matched none, rather than the address it was sent to"() {
        when:
        passing(new MockHttpServletRequest("GET", "/api/people/000501-Lovelace"), new MockHttpServletResponse(),
                { asked, answer -> answer.status = 404 })

        then:
        answeredLines().size() == 1
        KeyValuePairs.of(answeredLines().first()).subMap(["method", "status"]) == [method: "GET", status: 404]

        and:
        !KeyValuePairs.of(answeredLines().first()).containsKey("route")
    }
}
