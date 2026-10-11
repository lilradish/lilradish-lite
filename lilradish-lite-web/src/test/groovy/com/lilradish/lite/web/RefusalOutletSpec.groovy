package com.lilradish.lite.web

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.lilradish.lite.domain.failure.RefusalCode
import org.apache.catalina.connector.ClientAbortException
import org.libprunus.core.error.ApiErrorException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.HttpMessageNotWritableException
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.util.DisconnectedClientHelper
import spock.lang.Specification
import spock.lang.Timeout

class RefusalOutletSpec extends Specification {

    private Logger outletLogger
    private Level previousLevel
    private ListAppender<ILoggingEvent> logged

    def setup() {
        outletLogger = LoggerFactory.getLogger(RefusalOutlet) as Logger
        previousLevel = outletLogger.level
        logged = new ListAppender<ILoggingEvent>()
        logged.start()
        outletLogger.addAppender(logged)
        outletLogger.level = Level.DEBUG
    }

    def cleanup() {
        outletLogger.level = previousLevel
        outletLogger.detachAppender(logged)
        logged.stop()
    }

    def "answers busy with its fallback when no sentence source was installed"() {
        given:
        def outlet = new RefusalOutlet()
        def response = new MockHttpServletResponse()

        when:
        def answered = outlet.handleApiError(new ApiErrorException(RefusalCode.SERVICE_BUSY, "Said."),
                new ServletWebRequest(new MockHttpServletRequest(), response))

        then:
        answered.statusCode.value() == 503
        (answered.body as ProblemDetail).status == 503
        (answered.body as ProblemDetail).properties.code == "SERVICE_BUSY"
        (answered.body as ProblemDetail).detail == "Said."
        answered.headers.getFirst("Retry-After") == "30"
        logged.list*.level == [Level.WARN]
        logged.list*.throwableProxy == [null]
        response.status == 200
        response.contentAsString.isEmpty()
        !response.committed
    }

    @Timeout(5)
    def "answers a fault whose two causes form a cycle without showing either cause"() {
        given:
        def first = new RuntimeException("First private fault.")
        def second = new RuntimeException("Second private fault.")
        first.initCause(second)
        second.initCause(first)
        def response = new MockHttpServletResponse()

        when:
        def answered = new RefusalOutlet().handleUnexpected(first,
                new ServletWebRequest(new MockHttpServletRequest(), response))

        then:
        answered.statusCode.value() == 500
        (answered.body as ProblemDetail).status == 500
        (answered.body as ProblemDetail).properties.code == "INTERNAL"
        (answered.body as ProblemDetail).detail == null
        logged.list*.level == [Level.ERROR]
        logged.list*.throwableProxy.collect { it != null } == [true]
        response.status == 200
        response.contentAsString.isEmpty()
        !response.committed
    }

    def "answers an onward connection fault even when its cause names a client gone"() {
        given:
        def raised = new DataAccessResourceFailureException("An onward connection failed.",
                new ClientAbortException(new EOFException()))
        def outlet = new RefusalOutlet()
        def response = new MockHttpServletResponse()

        expect:
        outlet.raisedForTheClient(raised)
        !DisconnectedClientHelper.isClientDisconnectedException(raised)

        when:
        def answered = outlet.handleUnexpected(raised,
                new ServletWebRequest(new MockHttpServletRequest(), response))

        then:
        answered.statusCode.value() == 500
        (answered.body as ProblemDetail).properties.code == "INTERNAL"
        (answered.body as ProblemDetail).detail == null
        logged.list*.level == [Level.ERROR]
        logged.list*.throwableProxy.collect { it != null } == [true]
        response.status == 200
        response.contentAsString.isEmpty()
        !response.committed
    }

    def "answers an onward connection fault while writing instead of treating its inner cause as a departed client"() {
        given:
        def raised = new DataAccessResourceFailureException("An onward connection failed.",
                new ClientAbortException(new EOFException()))
        def unwritable = new HttpMessageNotWritableException("Could not write JSON", raised)
        def outlet = new RefusalOutlet()
        def response = new MockHttpServletResponse()

        expect:
        outlet.raisedForTheClient(unwritable)
        !DisconnectedClientHelper.isClientDisconnectedException(unwritable)

        when:
        def answered = outlet.handleException(unwritable,
                new ServletWebRequest(new MockHttpServletRequest(), response))

        then:
        answered.statusCode.value() == 500
        (answered.body as ProblemDetail).properties.code == "INTERNAL_SERVER_ERROR"
        (answered.body as ProblemDetail).detail == "Failed to write request"
        logged.list.isEmpty()
        response.status == 200
        response.contentAsString.isEmpty()
        !response.committed
    }

    def "refuses a method whose supported methods are #known without repeating its token"() {
        given:
        def refused = supported == null
                ? new HttpRequestMethodNotSupportedException("FROBNICATE")
                : new HttpRequestMethodNotSupportedException("FROBNICATE", supported)
        def response = new MockHttpServletResponse()

        when:
        def answered = new RefusalOutlet().handleException(refused,
                new ServletWebRequest(new MockHttpServletRequest(), response))

        then:
        answered.statusCode.value() == 405
        (answered.body as ProblemDetail).status == 405
        (answered.body as ProblemDetail).properties.code == "METHOD_NOT_ALLOWED"
        (answered.body as ProblemDetail).detail == "This address does not take that method."
        answered.headers.allow == [HttpMethod.OPTIONS] as Set
        response.status == 200
        response.contentAsString.isEmpty()
        !response.committed

        where:
        known     | supported
        "unknown" | null
        "empty"   | Collections.emptyList()
    }

    def "keeps incoming header values while replacing Allow without changing the incoming headers"() {
        given:
        def refused = new HttpRequestMethodNotSupportedException("FROBNICATE", ["GET"])
        def incoming = new HttpHeaders()
        incoming.add("X-Example", "one")
        incoming.add("X-Example", "two")
        incoming.allow = [HttpMethod.POST] as Set
        def original = HttpHeaders.copyOf(incoming)
        def response = new MockHttpServletResponse()

        when:
        def answered = new RefusalOutlet().handleHttpRequestMethodNotSupported(refused, incoming,
                HttpStatus.METHOD_NOT_ALLOWED, new ServletWebRequest(new MockHttpServletRequest(), response))

        then:
        answered.statusCode.value() == 405
        (answered.body as ProblemDetail).properties.code == "METHOD_NOT_ALLOWED"
        (answered.body as ProblemDetail).detail == "This address does not take that method."
        answered.headers.get("X-Example") == ["one", "two"]
        answered.headers.allow == [HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS] as Set
        incoming == original
        incoming.get("X-Example") == ["one", "two"]
        incoming.allow == [HttpMethod.POST] as Set
        response.status == 200
        response.contentAsString.isEmpty()
        !response.committed
    }
}
