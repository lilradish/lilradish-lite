package org.lilradish.lite.web

import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.springframework.web.context.support.StaticWebApplicationContext
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping
import org.springframework.web.servlet.resource.NoResourceFoundException
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler
import spock.lang.Specification

/** Asked with no gate in front: over a dispatcher the gate answers the prefix first, hiding what this judges of it. */
class ClientRouteFallbackSpec extends Specification {

    static final String THE_DOCUMENT = 'id="root"'

    private final StaticWebApplicationContext context = new StaticWebApplicationContext()

    def cleanup() {
        context.close()
    }

    private ResourceHttpRequestHandler everyOtherAddress() {
        def servletContext = new MockServletContext()
        context.servletContext = servletContext
        context.refresh()
        def registry = new ResourceHandlerRegistry(context, servletContext)
        new ClientRouteFallback().addResourceHandlers(registry)
        (registry.handlerMapping as SimpleUrlHandlerMapping).urlMap["/**"] as ResourceHttpRequestHandler
    }

    /** What the handler is handed is the path within its pattern, which the dispatcher would have set. */
    private static MockHttpServletRequest asking(String resourcePath) {
        def request = new MockHttpServletRequest("GET", "/" + resourcePath)
        request.setAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, resourcePath)
        request
    }

    def "answers an address under the prefix as missing rather than with the document, the bare prefix included"() {
        given:
        def response = new MockHttpServletResponse()

        when:
        everyOtherAddress().handleRequest(asking(resourcePath), response)

        then:
        thrown(NoResourceFoundException)
        response.contentAsByteArray.length == 0

        where:
        resourcePath << ["api/there-is-no-such-thing", "api"]
    }

    def "answers an address only the loaded page routes with the document"() {
        given:
        def response = new MockHttpServletResponse()

        when:
        everyOtherAddress().handleRequest(asking(resourcePath), response)

        then:
        response.status == 200
        response.contentType.startsWith("text/html")
        response.contentAsString.contains(THE_DOCUMENT)

        where:
        resourcePath << ["groups", "apis/there-is-no-such-thing"]
    }
}
