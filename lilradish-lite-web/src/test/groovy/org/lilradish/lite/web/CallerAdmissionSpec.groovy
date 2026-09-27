package org.lilradish.lite.web

import static org.lilradish.lite.testutil.FailureDispatch.failedOver

import jakarta.servlet.FilterChain
import org.lilradish.lite.app.identification.UserIdentification
import org.lilradish.lite.domain.identity.UserId
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.servlet.HandlerExceptionResolver
import spock.lang.Specification

/** The door on the dispatch the container runs after a failure, which no dispatcher-driven spec can produce. */
class CallerAdmissionSpec extends Specification {

    static final UserId STEWARD = new UserId("000001")

    private int identified = 0

    private final CallerAdmission door = new CallerAdmission(
            { asked -> identified++; Optional.of(STEWARD) } as UserIdentification, Stub(HandlerExceptionResolver))

    def cleanup() {
        MDC.clear()
    }

    private int passing(MockHttpServletRequest request, MockHttpServletResponse response) {
        def served = 0
        door.doFilter(request, response, { asked, answer -> served++ } as FilterChain)
        served
    }

    def "keeps the page reporting a guarded request's failure from being stored, asking nobody's identity again"() {
        given:
        def request = new MockHttpServletRequest("GET", CallerAdmission.THIS_APPLICATION_ANSWERS + "/standing")
        passing(request, new MockHttpServletResponse())
        def failurePage = new MockHttpServletResponse()

        when:
        def served = passing(failedOver(request), failurePage)

        then:
        failurePage.getHeader("Cache-Control") == "no-store"
        served == 1

        and:
        identified == 1
    }

    def "leaves the page reporting a failure elsewhere to the freshness it declares"() {
        given:
        def request = new MockHttpServletRequest("GET", "/index.html")
        passing(request, new MockHttpServletResponse())
        def failurePage = new MockHttpServletResponse()

        when:
        def served = passing(failedOver(request), failurePage)

        then:
        failurePage.getHeader("Cache-Control") == null
        served == 1

        and:
        identified == 0
    }
}
