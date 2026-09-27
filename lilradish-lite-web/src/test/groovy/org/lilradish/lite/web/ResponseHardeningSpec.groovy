package org.lilradish.lite.web

import static org.lilradish.lite.testutil.FailureDispatch.failedOver

import jakarta.servlet.FilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import spock.lang.Specification

/**
 * The container may clear headers to serve its failure page, so that page is a fresh response here.
 * Driven through {@code doFilter}, whose guard against filtering twice decides whether a second pass runs.
 */
class ResponseHardeningSpec extends Specification {

    static final Map<String, String> HARDENED = ["X-Content-Type-Options" : "nosniff",
                                                 "X-Frame-Options"        : "DENY",
                                                 "Content-Security-Policy": "frame-ancestors 'none'"]

    private ResponseHardening hardening = new ResponseHardening()

    private static Map<String, String> hardeningOn(MockHttpServletResponse response) {
        HARDENED.keySet().collectEntries { [(it): response.getHeader(it)] }
    }

    /** Set before anything behind it runs, so an answer written further in carries them. */
    def "hardens an answer before anything behind it has answered"() {
        given:
        def response = new MockHttpServletResponse()
        def seenBehind = []
        def onward = { asked, answer -> seenBehind << hardeningOn(answer as MockHttpServletResponse) } as FilterChain

        when:
        hardening.doFilter(new MockHttpServletRequest("GET", address), response, onward)

        then:
        seenBehind == [HARDENED]

        and: "each said once, the policy saying nothing else"
        HARDENED.keySet().every { response.getHeaders(it).size() == 1 }

        where:
        address << ["/", "/assets/index.js", "/api/standing"]
    }

    def "hardens the page the container serves for a failure, on a response that has lost them"() {
        given:
        def request = new MockHttpServletRequest("GET", "/api/standing")
        def calls = 0
        def onward = { asked, answer -> calls++ } as FilterChain
        hardening.doFilter(request, new MockHttpServletResponse(), onward)
        def failurePage = new MockHttpServletResponse()

        when:
        hardening.doFilter(failedOver(request), failurePage, onward)

        then:
        hardeningOn(failurePage) == HARDENED

        and: "the page itself still served, rather than the failure dispatch cut short"
        calls == 2
    }
}
