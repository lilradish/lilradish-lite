package org.lilradish.lite.web

import static java.nio.charset.StandardCharsets.UTF_8

import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletException
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.function.LongSupplier
import org.libprunus.core.error.ApiErrorException
import org.lilradish.lite.app.identification.UserIdentification
import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.filling.Filling
import org.lilradish.lite.domain.identity.UserId
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.servlet.HandlerExceptionResolver
import org.springframework.web.servlet.ModelAndView
import spock.lang.Specification
import spock.util.concurrent.PollingConditions
import tools.jackson.databind.JsonNode

/**
 * The turn behind a door that admits everybody under the prefix, the two driven through {@code doFilter} as
 * the container runs them. Where the turn stands in the application's own chain is asked of that chain in
 * {@link LargeBodyAdmissionIntegrationSpec}; an interrupt is what refuses a wait here, the wait itself being
 * far too long to run out in a test.
 */
class LargeBodyAdmissionSpec extends Specification {

    static final int ONE_MEBIBYTE = 1024 * 1024

    static final String UNDER_THE_PREFIX = CallerAdmission.THIS_APPLICATION_ANSWERS + "/standing"

    static final String REFUSED_AS = "This takes one document."

    private final HandlerExceptionResolver refusals = Mock()

    private final AtomicLong now = new AtomicLong()

    private final LargeBodyAdmission admission = new LargeBodyAdmission(refusals, { now.get() } as LongSupplier)

    private final CallerAdmission door = new CallerAdmission(
            { asked -> Optional.of(new UserId("000001")) } as UserIdentification, Stub(HandlerExceptionResolver))

    private final List<Integer> turnsFreeWhileHandled = []

    private final List<Boolean> saidHeldWhileHandled = []

    def cleanup() {
        MDC.clear()
        Thread.interrupted()
    }

    private static MockHttpServletRequest sending(String address, byte[] body) {
        def request = new MockHttpServletRequest("POST", address)
        request.addHeader("Sec-Fetch-Site", "same-origin")
        request.content = body
        request
    }

    /** No length declared, however the body is framed; the framing is said and never asked. */
    private static MockHttpServletRequest undeclared(String method, String address) {
        def request = new MockHttpServletRequest(method, address)
        request.addHeader("Sec-Fetch-Site", "same-origin")
        request.addHeader("Transfer-Encoding", "chunked")
        request
    }

    private int turnsFree() {
        admission.turn.turn.availablePermits()
    }

    private void passing(HttpServletRequest request, MockHttpServletResponse response, FilterChain handling) {
        door.doFilter(request, response, { asked, answer -> admission.doFilter(asked, answer, handling) } as FilterChain)
    }

    private FilterChain handling() {
        return { asked, answer ->
            turnsFreeWhileHandled << turnsFree()
            saidHeldWhileHandled << (LargeBodyAdmission.readDeadline(asked) != null)
        } as FilterChain
    }

    private static final byte[] PAST_A_MEBIBYTE_OF_JSON = ('{"said":"' + "x" * ONE_MEBIBYTE + '"}').getBytes(UTF_8)

    /** Each read answering only once the clock has moved on by {@code apart}, as a sender that slow is read. */
    private static HttpServletRequestWrapper sentSlowly(AtomicLong clock, Duration apart, ByteArrayInputStream sent) {
        def request = sending(UNDER_THE_PREFIX, PAST_A_MEBIBYTE_OF_JSON)
        request.contentType = "application/json"
        ServletInputStream arriving = new ServletInputStream() {
            @Override
            boolean isFinished() {
                sent.available() == 0
            }

            @Override
            boolean isReady() {
                true
            }

            @Override
            void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException()
            }

            @Override
            int read() {
                clock.addAndGet(apart.toNanos())
                sent.read()
            }

            @Override
            int read(byte[] into, int offset, int length) {
                clock.addAndGet(apart.toNanos())
                sent.read(into, offset, length)
            }
        }
        new HttpServletRequestWrapper(request) {
            @Override
            ServletInputStream getInputStream() {
                arriving
            }
        }
    }

    private static FilterChain readingTheBody(List<JsonNode> handled) {
        return { asked, answer -> handled << JsonBody.read(asked, Filling.LARGEST_REQUEST_BYTES, REFUSED_AS) } as FilterChain
    }

    def "a body of at most a mebibyte, or of no declared length on a method carrying none, is handled without the turn being taken: #declared"() {
        when:
        passing(request, new MockHttpServletResponse(), handling())

        then:
        turnsFreeWhileHandled == [1]
        saidHeldWhileHandled == [false]
        0 * refusals._

        where:
        declared                         | request
        "an empty body"                  | sending(UNDER_THE_PREFIX, new byte[0])
        "one byte"                       | sending(UNDER_THE_PREFIX, new byte[1])
        "exactly a mebibyte"             | sending(UNDER_THE_PREFIX, new byte[ONE_MEBIBYTE])
        "a GET of no declared length"    | new MockHttpServletRequest("GET", UNDER_THE_PREFIX)
        "a GET saying it is chunked"     | undeclared("GET", UNDER_THE_PREFIX)
        "a DELETE of no declared length" | undeclared("DELETE", UNDER_THE_PREFIX)
    }

    def "a body past a mebibyte, or of no declared length on a method that may carry one, is handled holding the turn, given back after: #declared"() {
        when:
        passing(request, new MockHttpServletResponse(), handling())

        then:
        turnsFreeWhileHandled == [0]
        saidHeldWhileHandled == [true]

        and: "given back, and no longer said to be held once handled"
        turnsFree() == 1
        LargeBodyAdmission.readDeadline(request) == null
        0 * refusals._

        where:
        declared                        | request
        "one byte past a mebibyte"      | sending(UNDER_THE_PREFIX, new byte[ONE_MEBIBYTE + 1])
        "a POST of no declared length"  | undeclared("POST", UNDER_THE_PREFIX)
        "a PUT of no declared length"   | undeclared("PUT", UNDER_THE_PREFIX)
        "a PATCH of no declared length" | undeclared("PATCH", UNDER_THE_PREFIX)
    }

    def "a request the door admitted nobody for passes by the turn, however large its body: #declared"() {
        when:
        passing(request, new MockHttpServletResponse(), handling())

        then:
        turnsFreeWhileHandled == [1]
        saidHeldWhileHandled == [false]
        0 * refusals._

        where:
        declared                       | request
        "one byte past a mebibyte"     | sending("/uploads", new byte[ONE_MEBIBYTE + 1])
        "a POST of no declared length" | undeclared("POST", "/uploads")
    }

    def "the turn is given back when handling a large body fails"() {
        given:
        def failing = { asked, answer -> turnsFreeWhileHandled << turnsFree(); throw failure } as FilterChain
        def request = sending(UNDER_THE_PREFIX, new byte[ONE_MEBIBYTE + 1])

        when:
        passing(request, new MockHttpServletResponse(), failing)

        then:
        def escaped = thrown(Exception)
        escaped.is(failure)

        and: "held while it was handled, free and no longer said held once it failed, and not answered as a refusal"
        turnsFreeWhileHandled == [0]
        turnsFree() == 1
        LargeBodyAdmission.readDeadline(request) == null
        0 * refusals._

        where:
        failure << [new IOException("gone"), new ServletException("broken"), new IllegalStateException("broken")]
    }

    def "a large body sent whole within the read deadline is read and handled, the turn given back after"() {
        given:
        def sent = new ByteArrayInputStream(PAST_A_MEBIBYTE_OF_JSON)
        def request = sentSlowly(now, LargeBodyAdmission.LONGEST_READ.dividedBy(32), sent)
        List<JsonNode> handled = []

        when:
        passing(request, new MockHttpServletResponse(), readingTheBody(handled))

        then:
        handled*.get("said")*.asString()*.length() == [ONE_MEBIBYTE]
        sent.available() == 0

        and: "the turn free and no longer said held, and nothing refused"
        turnsFree() == 1
        LargeBodyAdmission.readDeadline(request) == null
        0 * refusals._
    }

    def "a large body still arriving past the read deadline is refused as sent too slowly, and the next waiting takes the turn"() {
        given:
        def sent = new ByteArrayInputStream(PAST_A_MEBIBYTE_OF_JSON)
        def request = sentSlowly(now, LargeBodyAdmission.LONGEST_READ.dividedBy(8), sent)
        List<JsonNode> handled = []
        List<Thread> started = []
        def waited = new CompletableFuture<Boolean>()
        def waitingThenReading = { asked, answer ->
            started << Thread.start { waited.complete(admission.turn.take()) }
            new PollingConditions(timeout: 5).eventually {
                assert admission.turn.turn.hasQueuedThreads()
            }
            handled << JsonBody.read(asked, Filling.LARGEST_REQUEST_BYTES, REFUSED_AS)
        } as FilterChain

        when:
        passing(request, new MockHttpServletResponse(), waitingThenReading)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.BODY_SENT_TOO_SLOWLY
        refused.message == "What was sent arrived too slowly to be read; send it again."

        and: "read no further than the ninth read, the first past the deadline, and nothing handled or answered here"
        sent.available() == PAST_A_MEBIBYTE_OF_JSON.length - 9 * JsonBody.CHUNK_BYTES
        handled.isEmpty()
        0 * refusals._

        and: "the turn given back to the one waiting, who holds it now with nobody left waiting, and no longer said held by this request"
        waited.get(5, TimeUnit.SECONDS)
        turnsFree() == 0
        admission.turn.waiting.get() == 0
        LargeBodyAdmission.readDeadline(request) == null

        cleanup:
        admission.turn.give()
        started*.join()
    }

    def "a large body whose wait for the turn is interrupted is refused as busy and never handled"() {
        given:
        def request = sending(UNDER_THE_PREFIX, new byte[ONE_MEBIBYTE + 1])
        def response = new MockHttpServletResponse()
        Thread.currentThread().interrupt()

        when:
        passing(request, response, handling())

        then:
        1 * refusals.resolveException(request, response, null, {
            it instanceof ApiErrorException && it.errorCode() == RefusalCode.SERVICE_BUSY
        }) >> new ModelAndView()
        0 * refusals._

        and: "nothing handled or said held, the turn left free, and the interrupt kept for whoever runs the thread"
        turnsFreeWhileHandled.isEmpty()
        LargeBodyAdmission.readDeadline(request) == null
        turnsFree() == 1
        Thread.interrupted()
    }

    def "a busy refusal nothing renders fails the request rather than answering an empty success"() {
        given:
        Thread.currentThread().interrupt()

        when:
        passing(sending(UNDER_THE_PREFIX, new byte[ONE_MEBIBYTE + 1]), new MockHttpServletResponse(), handling())

        then:
        1 * refusals.resolveException(_, _, null, _) >> null
        def failed = thrown(ServletException)
        (failed.cause as ApiErrorException).errorCode() == RefusalCode.SERVICE_BUSY

        and:
        turnsFreeWhileHandled.isEmpty()
        turnsFree() == 1
        Thread.interrupted()
    }
}
