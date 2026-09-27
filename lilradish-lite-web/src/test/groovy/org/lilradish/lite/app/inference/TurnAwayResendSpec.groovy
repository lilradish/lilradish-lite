package org.lilradish.lite.app.inference

import static java.nio.charset.StandardCharsets.UTF_8
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException
import static org.springframework.test.web.client.response.MockRestResponseCreators.withRawStatus

import java.time.Duration
import java.time.Instant
import java.time.InstantSource
import java.util.concurrent.atomic.AtomicReference
import org.lilradish.lite.domain.inference.CallProgress
import org.lilradish.lite.domain.inference.TurnAway
import org.springframework.http.HttpStatusCode
import org.springframework.http.client.ClientHttpResponse
import org.springframework.mock.http.client.MockClientHttpResponse
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.ResponseCreator
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import spock.lang.Specification
import spock.lang.Timeout

/**
 * Driven through a real client, the resend registered on it as the adapter's is: only the wire is
 * replaced. Waits are scripted rather than slept, save where the shutdown signal itself is the subject.
 */
@Timeout(10)
class TurnAwayResendSpec extends Specification {

    static final URI COMPLETIONS = URI.create("https://models.example/v1/chat/completions")

    static final String BODY = '{"model":"vendor-sample"}'

    static final InstantSource CLOCK = InstantSource.fixed(Instant.parse("2026-09-26T10:00:00Z"))

    static final Duration SECOND = Duration.ofSeconds(1)

    static final String SLOW_DOWN =
            '{"error":{"message":"Too fast","type":"rate_limit_error","param":null,"code":"slow_down"}}'

    static final String OVERLOADED = '{"error":{"message":"The server is overloaded",' +
            '"type":"service_unavailable_error","param":null,"code":"server_is_overloaded"}}'

    static final String QUOTA = '{"error":{"message":"You exceeded your current quota",' +
            '"type":"insufficient_quota","param":null,"code":"insufficient_quota"}}'

    static final TurnAway TOO_FAST = new TurnAway("Too fast", false)

    static final TurnAway BUSY = new TurnAway("The server is overloaded", false)

    static final TurnAway SPENT_UP = new TurnAway("You exceeded your current quota", true)

    CallProgress progress = Mock()

    ScriptedWait waits = new ScriptedWait()

    MockRestServiceServer server

    def "a response that is not a turnaway is handed on untouched and unread after one announced send"() {
        given:
        def client = client(resends(3))
        expectSend(withRawStatus(status).body(SLOW_DOWN))

        when:
        def (handedOn, read) = sendReading(client)

        then:
        1 * progress.aboutToSend()
        0 * _

        and:
        handedOn == status
        read == SLOW_DOWN
        waits.waited == []
        server.verify()

        where:
        status << [200, 400, 401, 404, 408, 500, 502, 504, 529]
    }

    def "each busy turnaway is passed on and waited out, and resent only once the progress allows it"() {
        given:
        def client = client(resends(3))
        expectSend(withRawStatus(429).body(SLOW_DOWN).header("Retry-After", "3"))
        expectSend(withRawStatus(503).body(OVERLOADED).header(RetryAfter.MILLISECONDS, "1500"))
        expectSend(withRawStatus(200))

        when:
        def handedOn = send(client)

        then:
        1 * progress.aboutToSend()

        then:
        1 * progress.turnedAway(TOO_FAST)

        then:
        1 * progress.mayResend() >> true

        then:
        1 * progress.turnedAway(BUSY)

        then:
        1 * progress.mayResend() >> true
        0 * _

        and:
        handedOn == 200
        waits.waited == [Duration.ofSeconds(3), Duration.ofSeconds(2)]
        server.verify()
    }

    def "a turnaway asking to be sent again at a date is waited out until then, by the resend's own clock"() {
        given:
        def client = client(resends(3))
        expectSend(withRawStatus(503).body(OVERLOADED).header("Retry-After", "Sat, 26 Sep 2026 10:00:07 GMT"))
        expectSend(withRawStatus(200))

        when:
        def handedOn = send(client)

        then:
        1 * progress.aboutToSend()
        1 * progress.turnedAway(BUSY)
        1 * progress.mayResend() >> true
        0 * _

        and:
        handedOn == 200
        waits.waited == [Duration.ofSeconds(7)]
        server.verify()
    }

    def "with no resends allowed, the first turnaway ends the call unannounced, spent up only by a 429's own error"() {
        given:
        def client = client(resends(0))
        expectSend(withRawStatus(status).body(body))

        when:
        send(client)

        then:
        def ended = thrown(CallTurnedAway)
        ended.last() == new TurnAway(said, spentUp)

        and:
        1 * progress.aboutToSend()
        0 * _
        waits.waited == []
        server.verify()

        where:
        status | body                                                                        || said                         | spentUp
        429    | QUOTA                                                                       || "You exceeded your current quota" | true
        429    | '{"error":{"message":"m","type":"insufficient_quota","param":null,"code":null}}' || "m"                     | true
        429    | '{"error":{"message":"m","type":"t","code":"insufficient_quota","note":"' + 'p' * 10000 + '"}}' || "m" | true
        429    | SLOW_DOWN                                                                   || "Too fast"                   | false
        429    | '{"error":{"code":"429","message":"Rate limit is exceeded."}}'              || "Rate limit is exceeded."    | false
        429    | '{"error":{"message":"q","type":"t","code":1}}'                             || "q"                          | false
        429    | '{"object":"error","message":"busy","type":"insufficient_quota","code":429}' || '{"object":"error","message":"busy","type":"insufficient_quota","code":429}' | false
        429    | '{"error":{"message":"","type":"t","code":"slow_down"}}'                   || '{"error":{"message":"","type":"t","code":"slow_down"}}' | false
        429    | '{"error":"insufficient_quota"}'                                            || '{"error":"insufficient_quota"}' | false
        429    | '{"error":{"message":"m","code":"slow_down"},"error":{"message":"x","code":"insufficient_quota"}}' || '{"error":{"message":"m","code":"slow_down"},"error":{"message":"x","code":"insufficient_quota"}}' | false
        429    | '<html>Too Many Requests</html>'                                           || '<html>Too Many Requests</html>' | false
        429    | ''                                                                          || null                         | false
        429    | 'x' * 65536                                                                 || 'x' * 65536                  | false
        429    | 'x' * 65537                                                                 || 'x' * 65536                  | false
        429    | 'x' * 65535 + Character.toString(0xE9)                                      || 'x' * 65535                  | false
        429    | 'x' * 65534 + Character.toString(0x20AC)                                    || 'x' * 65534                  | false
        429    | 'x' * 65533 + Character.toString(0x1F600)                                   || 'x' * 65533                  | false
        503    | OVERLOADED                                                                  || "The server is overloaded"   | false
        503    | QUOTA                                                                       || "You exceeded your current quota" | false
    }

    def "every code the vendor gives for what may be spent being used up is spent up"() {
        given:
        def client = client(resends(0))
        expectSend(withRawStatus(429).body('{"error":{"message":"spent","type":"billing","param":null,"code":"' + code + '"}}'))

        when:
        send(client)

        then:
        def ended = thrown(CallTurnedAway)
        ended.last() == new TurnAway("spent", true)

        and:
        1 * progress.aboutToSend()
        0 * _
        waits.waited == []
        server.verify()

        where:
        code << ["insufficient_quota", "usage_limit_exceeded", "credit_balance_exhausted",
                 "organization_spend_limit_exceeded", "project_spend_limit_exceeded",
                 "organization_usage_limit_exceeded"]
    }

    def "a spent-up turnaway ends the call at once though resends are left, having waited for none"() {
        given:
        def client = client(resends(3))
        expectSend(withRawStatus(503).body(OVERLOADED))
        expectSend(withRawStatus(429).body(QUOTA))

        when:
        send(client)

        then:
        def ended = thrown(CallTurnedAway)
        ended.last() == SPENT_UP

        and:
        1 * progress.aboutToSend()
        1 * progress.turnedAway(BUSY)
        1 * progress.mayResend() >> true
        0 * _
        waits.waited == [SECOND]
        server.verify()
    }

    def "once every resend is spent, the last turnaway ends the call and is never passed on"() {
        given:
        def client = client(resends(2))
        expectSend(withRawStatus(503).body(OVERLOADED))
        expectSend(withRawStatus(429).body(SLOW_DOWN))
        expectSend(withRawStatus(503).body(''))

        when:
        send(client)

        then:
        def ended = thrown(CallTurnedAway)
        ended.last() == new TurnAway(null, false)

        and:
        1 * progress.aboutToSend()
        1 * progress.turnedAway(BUSY)
        1 * progress.turnedAway(TOO_FAST)
        2 * progress.mayResend() >> true
        0 * _
        waits.waited == [SECOND, Duration.ofSeconds(2)]
        server.verify()
    }

    def "a resend the progress does not allow is not made"() {
        given:
        def client = client(resends(3))
        expectSend(withRawStatus(503).body(OVERLOADED))

        when:
        send(client)

        then:
        thrown(CallNotResent)

        and:
        1 * progress.aboutToSend()
        1 * progress.turnedAway(BUSY)
        1 * progress.mayResend() >> false
        0 * _
        waits.waited == [SECOND]
        server.verify()
    }

    def "a wait cut short by an interrupt or the shutdown signal ends the call, the resend never asked about"() {
        given:
        def client = client(resends(3))
        expectSend(withRawStatus(429).body(SLOW_DOWN))
        waits.ending = ending

        when:
        send(client)

        then:
        thrown(CallNotResent)
        Thread.interrupted() == interrupting

        and:
        1 * progress.aboutToSend()
        1 * progress.turnedAway(TOO_FAST)
        0 * _
        waits.waited == [SECOND]
        server.verify()

        where:
        ending             || interrupting
        Ending.INTERRUPTED || true
        Ending.SIGNALLED   || false
    }

    def "a turnaway heard once the application is stopping ends the call at once, and is never passed on"() {
        given:
        def shutdown = new ModelCallShutdown()
        shutdown.start()
        shutdown.stop()
        def client = client(new ResendPolicy(3, Duration.ofHours(1), Duration.ofHours(1)), shutdown)
        expectSend(withRawStatus(503).body(OVERLOADED))

        when:
        send(client)

        then:
        def ended = thrown(CallTurnedAway)
        ended.last() == BUSY

        and:
        1 * progress.aboutToSend()
        0 * _
        server.verify()
    }

    def "a shutdown signalled during the wait ends it at once, and nothing more is sent"() {
        given:
        def shutdown = new ModelCallShutdown()
        shutdown.start()
        def client = client(new ResendPolicy(3, Duration.ofHours(1), Duration.ofHours(1)), shutdown)
        expectSend(withRawStatus(503).body(OVERLOADED))
        def failure = new AtomicReference<Throwable>()

        when:
        def calling = Thread.start {
            try {
                send(client)
            } catch (Throwable ended) {
                failure.set(ended)
            }
        }
        while (calling.state != Thread.State.TIMED_WAITING && calling.alive) {
            Thread.onSpinWait()
        }
        shutdown.stop()
        calling.join()

        then:
        failure.get() instanceof CallNotResent

        and:
        1 * progress.aboutToSend()
        1 * progress.turnedAway(BUSY)
        0 * _
        server.verify()
    }

    def "a failure in the progress is thrown as it is, and nothing more is sent"() {
        given:
        def failure = new IllegalStateException("the call has already ended")
        def client = client(resends(3))
        sends.times { expectSend(withRawStatus(503).body(OVERLOADED)) }

        when:
        send(client)

        then:
        1 * progress.aboutToSend() >> { if (failing == "aboutToSend") throw failure }
        turnedAways * progress.turnedAway(BUSY) >> { if (failing == "turnedAway") throw failure }
        mayResends * progress.mayResend() >> { if (failing == "mayResend") throw failure; true }
        0 * _

        and:
        def raised = thrown(IllegalStateException)
        raised.is(failure)
        waits.waited == waited
        server.verify()

        where:
        failing       || sends | turnedAways | mayResends | waited
        "aboutToSend" || 0     | 0           | 0          | []
        "turnedAway"  || 1     | 1           | 0          | []
        "mayResend"   || 1     | 1           | 1          | [SECOND]
    }

    def "a call carrying no progress is refused before anything is sent"() {
        given:
        def client = client(resends(3))

        when:
        send(client, carried)

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "A model call carries no CallProgress, so nothing it sends would be on record"
        server.verify()

        where:
        carried << [null, "progress"]
    }

    def "a turned-away response is closed before the progress hears of it, and before the call ends"() {
        given:
        def client = client(resends(1))
        def first = new ClosingResponse(503)
        def last = new ClosingResponse(503)
        server.expect(requestTo(COMPLETIONS)).andRespond({ request -> first } as ResponseCreator)
        server.expect(requestTo(COMPLETIONS)).andRespond({ request -> last } as ResponseCreator)
        boolean closedWhenPassedOn = false
        progress.turnedAway(_) >> { closedWhenPassedOn = first.closed }
        progress.mayResend() >> true

        when:
        send(client)

        then:
        thrown(CallTurnedAway)
        closedWhenPassedOn
        last.closed
        server.verify()
    }

    def "a response whose status cannot be read is closed, and fails the call as the wire failed"() {
        given:
        def client = client(resends(3))
        def unreadable = new UnreadableStatusResponse()
        server.expect(requestTo(COMPLETIONS)).andRespond({ request -> unreadable } as ResponseCreator)

        when:
        send(client)

        then:
        def failed = thrown(ResourceAccessException)
        failed.cause.is(UnreadableStatusResponse.FAILURE)
        unreadable.closed

        and:
        1 * progress.aboutToSend()
        0 * _
        waits.waited == []
        server.verify()
    }

    def "a turnaway whose body fails to arrive is still a busy turnaway, waited out as its headers ask"() {
        given:
        def client = client(resends(3))
        def unreadable = new UnreadableBodyResponse(429)
        unreadable.headers.add("Retry-After", "5")
        server.expect(requestTo(COMPLETIONS)).andRespond({ request -> unreadable } as ResponseCreator)
        expectSend(withRawStatus(200))

        when:
        def handedOn = send(client)

        then:
        1 * progress.aboutToSend()
        1 * progress.turnedAway(new TurnAway(null, false))
        1 * progress.mayResend() >> true
        0 * _

        and:
        handedOn == 200
        unreadable.closed
        waits.waited == [Duration.ofSeconds(5)]
        server.verify()
    }

    def "a send that fails on the wire is no turnaway, and fails the call as it is"() {
        given:
        def client = client(resends(3))
        server.expect(requestTo(COMPLETIONS)).andRespond(withException(new SocketTimeoutException("timed out")))

        when:
        send(client)

        then:
        def failed = thrown(ResourceAccessException)
        failed.cause instanceof SocketTimeoutException

        and:
        1 * progress.aboutToSend()
        0 * _
        waits.waited == []
        server.verify()
    }

    private static ResendPolicy resends(int times) {
        new ResendPolicy(times, SECOND, Duration.ofSeconds(30))
    }

    private RestClient client(ResendPolicy policy, ResendWait wait = waits) {
        def builder = RestClient.builder().requestInterceptor(new TurnAwayResend(policy, wait, CLOCK))
        server = MockRestServiceServer.bindTo(builder).build()
        builder.build()
    }

    private void expectSend(ResponseCreator response) {
        server.expect(requestTo(COMPLETIONS)).andExpect(content().string(BODY)).andRespond(response)
    }

    private int send(RestClient client, Object carried = progress) {
        def request = client.post().uri(COMPLETIONS).body(BODY)
        if (carried != null) {
            request.attribute(TurnAwayResend.PROGRESS, carried)
        }
        request.exchange({ sent, ClientHttpResponse response -> response.statusCode.value() }, true)
    }

    private List<Object> sendReading(RestClient client) {
        client.post().uri(COMPLETIONS).body(BODY).attribute(TurnAwayResend.PROGRESS, progress).exchange({
            sent, ClientHttpResponse response -> [response.statusCode.value(), new String(response.body.readAllBytes(), UTF_8)]
        }, true)
    }

    enum Ending {
        WAITED_OUT,
        SIGNALLED,
        INTERRUPTED
    }

    /** Records every wait asked of it, and ends each as scripted: waited out unless told otherwise. */
    static final class ScriptedWait implements ResendWait {

        final List<Duration> waited = []

        Ending ending = Ending.WAITED_OUT

        @Override
        boolean stopped() {
            false
        }

        @Override
        boolean waitedOut(Duration wait) throws InterruptedException {
            waited << wait
            if (ending == Ending.INTERRUPTED) {
                throw new InterruptedException("stopped while waiting")
            }
            ending == Ending.WAITED_OUT
        }
    }

    static class ClosingResponse extends MockClientHttpResponse {

        boolean closed

        ClosingResponse(int status) {
            super(new byte[0], status)
        }

        @Override
        void close() {
            closed = true
            super.close()
        }
    }

    static final class UnreadableStatusResponse extends ClosingResponse {

        static final IOException FAILURE = new IOException("the connection was reset")

        UnreadableStatusResponse() {
            super(200)
        }

        @Override
        HttpStatusCode getStatusCode() throws IOException {
            throw FAILURE
        }
    }

    static final class UnreadableBodyResponse extends ClosingResponse {

        UnreadableBodyResponse(int status) {
            super(status)
        }

        @Override
        InputStream getBody() throws IOException {
            throw new IOException("the connection was reset")
        }
    }
}
