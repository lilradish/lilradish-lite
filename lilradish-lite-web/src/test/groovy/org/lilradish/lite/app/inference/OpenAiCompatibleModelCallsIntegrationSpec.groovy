package org.lilradish.lite.app.inference

import static java.nio.charset.StandardCharsets.UTF_8

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.time.Duration
import java.time.InstantSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.lilradish.lite.app.inference.ModelEndpoint.VendorModel
import org.lilradish.lite.domain.inference.CallOutcome
import org.lilradish.lite.domain.inference.CallProgress
import org.lilradish.lite.domain.inference.CallRequest
import org.lilradish.lite.domain.inference.ModelCallPurpose
import org.lilradish.lite.domain.inference.TurnAway
import org.lilradish.lite.domain.model.DeployedModel
import org.lilradish.lite.domain.model.ModelCatalog
import org.lilradish.lite.domain.model.ModelName
import org.lilradish.lite.domain.model.SentText
import spock.lang.Specification
import spock.lang.Timeout

/**
 * Against a real server on a port of its own, over the client the configuration builds: what a
 * replaced wire cannot show is the JDK factory's own deadline, and a send that is left alone while
 * the application stops. Every resend waits an hour, so only the stop can end one in time.
 */
@Timeout(10)
class OpenAiCompatibleModelCallsIntegrationSpec extends Specification {

    static final ModelName SAMPLE = new ModelName("sample_model")

    static final DeployedModel MODEL = new DeployedModel(SAMPLE, [], 200000, new BigDecimal("3"), 8192, [])

    static final String COMPLETION =
            '{"choices":[{"index":0,"message":{"content":"ok"},"finish_reason":"stop","logprobs":null}]}'

    static final String OVERLOADED = '{"error":{"message":"The server is overloaded",' +
            '"type":"service_unavailable_error","param":null,"code":"server_is_overloaded"}}'

    HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.loopbackAddress, 0), 0)

    ExecutorService handling = Executors.newCachedThreadPool()

    CountDownLatch arrived = new CountDownLatch(1)

    CountDownLatch released = new CountDownLatch(1)

    ModelCallShutdown shutdown = new ModelCallShutdown()

    CallProgress progress = Mock()

    def setup() {
        server.executor = handling
        server.start()
        shutdown.start()
    }

    def cleanup() {
        released.countDown()
        server.stop(0)
        handling.shutdownNow()
    }

    def "a send not answered within the read timeout is an error, never thrown"() {
        given:
        answerOnceReleased(200, COMPLETION)
        def modelCalls = modelCalls(Duration.ofMillis(100))

        when:
        def outcome = modelCalls.call(request(), progress)

        then:
        outcome instanceof CallOutcome.Errored
        (outcome as CallOutcome.Errored).detail().startsWith("No answer came back: java.net.http.HttpTimeoutException")
        1 * progress.aboutToSend()
        0 * _
    }

    def "an answer that stops arriving within the read timeout is an error, never thrown"() {
        given:
        server.createContext("/v1/chat/completions") { HttpExchange exchange ->
            exchange.requestBody.readAllBytes()
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write('{"choices":[{"message":{"content":"half an ans'.getBytes(UTF_8))
            exchange.responseBody.flush()
            released.await(10, TimeUnit.SECONDS)
            exchange.close()
        }
        def modelCalls = modelCalls(Duration.ofMillis(300))

        when:
        def outcome = modelCalls.call(request(), progress)

        then:
        outcome instanceof CallOutcome.Errored
        (outcome as CallOutcome.Errored).detail().startsWith("No answer came back: ")
        1 * progress.aboutToSend()
        0 * _
    }

    def "a send in flight when the application stops is left to come back, and a turnaway it brings is not resent"() {
        given:
        answerOnceReleased(status, body)
        def modelCalls = modelCalls(Duration.ofSeconds(5))
        def outcome = new AtomicReference<CallOutcome>()

        when:
        def calling = Thread.start { outcome.set(modelCalls.call(request(), progress)) }
        def sendArrived = arrived.await(5, TimeUnit.SECONDS)
        shutdown.stop()
        released.countDown()
        calling.join()

        then:
        sendArrived
        outcome.get() == expected
        1 * progress.aboutToSend()
        0 * _

        where:
        status | body       || expected
        200    | COMPLETION || new CallOutcome.CameBack("ok", 9, 1, false, false)
        503    | OVERLOADED || new CallOutcome.TurnedAway(new TurnAway("The server is overloaded", false))
    }

    private void answerOnceReleased(int status, String body) {
        server.createContext("/v1/chat/completions") { HttpExchange exchange ->
            exchange.requestBody.readAllBytes()
            arrived.countDown()
            released.await(10, TimeUnit.SECONDS)
            byte[] written = body.getBytes(UTF_8)
            exchange.sendResponseHeaders(status, written.length)
            exchange.responseBody.withCloseable { it.write(written) }
        }
    }

    private OpenAiCompatibleModelCalls modelCalls(Duration readTimeout) {
        def base = new URI("http", null, server.address.hostString, server.address.port, "/v1", null, null)
        def endpoint = new ModelEndpoint(base, Duration.ofSeconds(1), readTimeout,
                [new VendorModel(SAMPLE, "vendor-sample", [])])
        def resend = new TurnAwayResend(new ResendPolicy(3, Duration.ofHours(1), Duration.ofHours(1)),
                shutdown, InstantSource.system())
        new OpenAiCompatibleModelCalls(ModelCallsConfiguration.modelClient(endpoint, "test-key", resend),
                endpoint.completions(), endpoint.pairedWith(new ModelCatalog([MODEL])))
    }

    private static CallRequest request() {
        new CallRequest(MODEL, null, ModelCallPurpose.PRODUCE, SentText.measure("answer in JSON", "the invoice"))
    }
}
