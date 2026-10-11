package com.lilradish.lite.app.library

import org.libprunus.spring.error.ApiErrorHandler
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.context.request.ServletWebRequest
import spock.lang.Specification

class InputReferenceOutletSpec extends Specification {

    def "an input reference refusal leaves an already committed response untouched"() {
        given:
        def response = new MockHttpServletResponse()
        response.status = 202
        response.contentType = "text/plain"
        response.characterEncoding = "UTF-8"
        response.setHeader("X-Demo-Reply", "Demo Sample reply")
        response.writer.write("Demo Sample reply already sent.")
        response.flushBuffer()
        def status = response.status
        def body = response.contentAsByteArray.clone()
        def headers = response.headerNames.collectEntries { name ->
            [(name): response.getHeaders(name).toList()]
        }
        assert body.length > 0
        assert !headers.empty
        def request = new ServletWebRequest(new MockHttpServletRequest(), response)
        def refused = LibraryRefusal.refusingInputs(["unknown_input"])
        def outlet = new InputReferenceOutlet(new ApiErrorHandler())

        when:
        def answered = outlet.referencesRefused(refused, request)

        then:
        answered == null
        response.committed
        response.status == status
        response.contentAsByteArray.length == body.length
        response.contentAsByteArray == body
        response.headerNames.size() == headers.size()
        response.headerNames.collectEntries { name ->
            [(name): response.getHeaders(name).toList()]
        } == headers
    }
}
