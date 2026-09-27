package org.lilradish.lite.app.inference

import static java.nio.charset.StandardCharsets.UTF_8
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException
import static org.springframework.test.web.client.response.MockRestResponseCreators.withRawStatus

import groovy.transform.CompileStatic
import java.time.Duration
import java.time.Instant
import java.time.InstantSource
import org.lilradish.lite.app.inference.ModelEndpoint.VendorMode
import org.lilradish.lite.app.inference.ModelEndpoint.VendorModel
import org.lilradish.lite.domain.inference.CallOutcome
import org.lilradish.lite.domain.inference.CallProgress
import org.lilradish.lite.domain.inference.CallRequest
import org.lilradish.lite.domain.inference.ModelCallPurpose
import org.lilradish.lite.domain.inference.TurnAway
import org.lilradish.lite.domain.model.DeployedModel
import org.lilradish.lite.domain.model.ModelCatalog
import org.lilradish.lite.domain.model.ModelMode
import org.lilradish.lite.domain.model.ModelName
import org.lilradish.lite.domain.model.SentText
import org.springframework.http.HttpMethod
import org.springframework.mock.http.client.MockClientHttpResponse
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.ResponseCreator
import org.springframework.web.client.ResourceAccessException
import spock.lang.Specification
import spock.lang.Timeout

/**
 * The adapter over the client the configuration builds, headers and resend included; only the wire is
 * replaced. Three characters to a unit: the 25 characters sent are 9, so a measured count never
 * passes for one the model gave.
 */
@Timeout(30)
class OpenAiCompatibleModelCallsSpec extends Specification {

    static final ModelName SAMPLE = new ModelName("sample_model")

    static final ModelMode RESEARCH = new ModelMode("research")

    static final DeployedModel MODEL = new DeployedModel(SAMPLE, [RESEARCH], 200000, new BigDecimal("3"), 8192, [])

    static final ModelEndpoint ENDPOINT = new ModelEndpoint(URI.create("https://models.example/v1"),
            Duration.ofSeconds(10), Duration.ofSeconds(10),
            [new VendorModel(SAMPLE, "vendor-sample", [new VendorMode(RESEARCH, ReasoningEffort.HIGH)])])

    static final URI COMPLETIONS = URI.create("https://models.example/v1/chat/completions")

    static final String ANSWER = '{\\"total\\":\\"12\\"}'

    static final String OVERLOADED = '{"error":{"message":"The server is overloaded",' +
            '"type":"service_unavailable_error","param":null,"code":"server_is_overloaded"}}'

    CallProgress progress = Mock()

    MockRestServiceServer server

    def "a call turned away for good ends turned away, with the turnaway that ended it"() {
        given:
        def modelCalls = modelCalls(times)
        server.expect(requestTo(COMPLETIONS)).andRespond(withRawStatus(status).body(body))

        when:
        def outcome = modelCalls.call(request(null), progress)

        then:
        outcome == new CallOutcome.TurnedAway(last)
        1 * progress.aboutToSend()
        0 * _
        server.verify()

        where:
        times | status | body                                                                    || last
        3     | 429    | '{"error":{"message":"quota","type":"insufficient_quota","code":null}}' || new TurnAway("quota", true)
        0     | 503    | OVERLOADED                                                              || new TurnAway("The server is overloaded", false)
    }

    def "a resend the progress does not allow ends the call not resent, with nothing more sent"() {
        given:
        def modelCalls = modelCalls(3)
        server.expect(requestTo(COMPLETIONS)).andRespond(withRawStatus(503).body(OVERLOADED))

        when:
        def outcome = modelCalls.call(request(null), progress)

        then:
        outcome == new CallOutcome.NotResent()
        1 * progress.aboutToSend()
        1 * progress.turnedAway(new TurnAway("The server is overloaded", false))
        1 * progress.mayResend() >> false
        0 * _
        server.verify()
    }

    def "a send that fails on the wire or runs out of time is an error saying how, never thrown"() {
        given:
        def modelCalls = modelCalls(3)
        server.expect(requestTo(COMPLETIONS)).andRespond(withException(failure))

        when:
        def outcome = modelCalls.call(request(null), progress)

        then:
        outcome == new CallOutcome.Errored(detail)
        1 * progress.aboutToSend()
        0 * _
        server.verify()

        where:
        failure                                            || detail
        new SocketTimeoutException("Read timed out")       || "No answer came back: java.net.SocketTimeoutException: Read timed out"
        new ConnectException()                             || "No answer came back: java.net.ConnectException"
    }

    def "a failure of access carrying no cause is an error saying so itself, never thrown"() {
        given:
        def modelCalls = modelCalls(3)
        server.expect(requestTo(COMPLETIONS))
                .andRespond({ request -> throw new ResourceAccessException("I/O error: nowhere to send") } as ResponseCreator)

        when:
        def outcome = modelCalls.call(request(null), progress)

        then:
        outcome == new CallOutcome.Errored(
                "No answer came back: org.springframework.web.client.ResourceAccessException: I/O error: nowhere to send")
        1 * progress.aboutToSend()
        0 * _
        server.verify()
    }

    def "a failure in the progress is thrown as it is, with nothing sent"() {
        given:
        def modelCalls = modelCalls(3)
        def failure = new IllegalStateException("the call has already ended")

        when:
        modelCalls.call(request(null), progress)

        then:
        1 * progress.aboutToSend() >> { throw failure }
        0 * _
        def raised = thrown(IllegalStateException)
        raised.is(failure)
        server.verify()
    }

    def "asks for the vendor's model with the system text as a system message, no more back than the model may give"() {
        given:
        def modelCalls = modelCalls(0)
        server.expect(requestTo(COMPLETIONS))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(header("Content-Type", "application/json"))
                .andExpect(header("Accept", "application/json"))
                .andExpect(content().string('{"model":"vendor-sample","messages":[' +
                        '{"role":"system","content":"answer in JSON"},{"role":"user","content":"the invoice"}],' +
                        '"max_completion_tokens":8192' + effort + '}'))
                .andRespond(withRawStatus(200).body(completion('"content":"ok"', "stop", null)))

        when:
        def outcome = modelCalls.call(request(mode), progress)

        then:
        outcome == new CallOutcome.CameBack("ok", 9, 1, false, false)
        1 * progress.aboutToSend()
        0 * _
        server.verify()

        where:
        mode     || effort
        null     || ''
        RESEARCH || ',"reasoning_effort":"high"'
    }

    def "a request for a model the endpoint is not paired with is refused before the progress hears of it"() {
        given:
        def modelCalls = modelCalls(3)
        def unpaired = new DeployedModel(new ModelName("other_model"), [], 200000, new BigDecimal("3"), 8192, [])

        when:
        modelCalls.call(new CallRequest(unpaired, null, ModelCallPurpose.PRODUCE,
                SentText.measure("answer in JSON", "the invoice")), progress)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "No endpoint model is paired with other_model"
        0 * _
        server.verify()
    }

    def "an answer comes back as it is, counted by the model where it gave both counts and measured here otherwise"() {
        given:
        def modelCalls = modelCalls(0)
        server.expect(requestTo(COMPLETIONS)).andRespond(withRawStatus(200).body(completion(message, finish, usage)))

        when:
        def outcome = modelCalls.call(request(null), progress)

        then:
        outcome == new CallOutcome.CameBack(answer, sentCount, cameBackCount, countedByModel, cutOff)
        1 * progress.aboutToSend()
        0 * _
        server.verify()

        where:
        message                          | finish   | usage                                                 || answer             | sentCount | cameBackCount | countedByModel | cutOff
        '"content":"' + ANSWER + '"'     | "stop"   | '{"prompt_tokens":12,"completion_tokens":3}'          || '{"total":"12"}'   | 12        | 3             | true           | false
        '"content":"' + ANSWER + '"'     | "length" | '{"prompt_tokens":12,"completion_tokens":3}'          || '{"total":"12"}'   | 12        | 3             | true           | true
        '"content":"' + ANSWER + '"'     | "stop"   | '{"prompt_tokens":5,"completion_tokens":0}'           || '{"total":"12"}'   | 5         | 0             | true           | false
        '"content":"' + ANSWER + '"'     | "stop"   | '{"prompt_tokens":1,"completion_tokens":3}'           || '{"total":"12"}'   | 1         | 3             | true           | false
        '"content":"' + ANSWER + '"'     | "stop"   | null                                                  || '{"total":"12"}'   | 9         | 5             | false          | false
        '"content":"' + ANSWER + '"'     | "length" | null                                                  || '{"total":"12"}'   | 9         | 5             | false          | true
        '"content":"' + ANSWER + '"'     | "stop"   | '{"prompt_tokens":0,"completion_tokens":3}'           || '{"total":"12"}'   | 9         | 5             | false          | false
        '"content":"' + ANSWER + '"'     | "stop"   | '{"prompt_tokens":12}'                                || '{"total":"12"}'   | 9         | 5             | false          | false
        '"content":"' + ANSWER + '"'     | "stop"   | '{"prompt_tokens":12,"completion_tokens":-1}'         || '{"total":"12"}'   | 9         | 5             | false          | false
        '"content":"' + ANSWER + '"'     | "stop"   | '{"prompt_tokens":1.5,"completion_tokens":3}'         || '{"total":"12"}'   | 9         | 5             | false          | false
        '"content":"' + ANSWER + '"'     | "stop"   | '{"prompt_tokens":"12","completion_tokens":3}'        || '{"total":"12"}'   | 9         | 5             | false          | false
        '"content":"' + ANSWER + '"'     | "stop"   | '{"prompt_tokens":99999999999999999999,"completion_tokens":3}' || '{"total":"12"}' | 9     | 5             | false          | false
        '"content":"' + ANSWER + '"'     | "stop"   | '[12,3]'                                              || '{"total":"12"}'   | 9         | 5             | false          | false
        '"content":null'                 | "stop"   | null                                                  || ""                 | 9         | 0             | false          | false
        '"role":"assistant"'             | "stop"   | null                                                  || ""                 | 9         | 0             | false          | false
        '"content":"\\ud800 and \\u0000"' | "stop"  | null                                                  || "\uD800 and \u0000" | 9        | 3             | false          | false
        '"content":"kept","refusal":""'  | "stop"   | null                                                  || "kept"             | 9         | 2             | false          | false
    }

    def "a refusal or content the filter withheld is never an answer, and comes back empty and not cut off"() {
        given:
        def modelCalls = modelCalls(0)
        server.expect(requestTo(COMPLETIONS)).andRespond(withRawStatus(200).body(completion(message, finish, usage)))

        when:
        def outcome = modelCalls.call(request(null), progress)

        then:
        outcome == new CallOutcome.CameBack("", sentCount, cameBackCount, countedByModel, false)
        1 * progress.aboutToSend()
        0 * _
        server.verify()

        where:
        message                                          | finish           | usage                                        || sentCount | cameBackCount | countedByModel
        '"content":null,"refusal":"I cannot help"'       | "stop"           | null                                         || 9         | 0             | false
        '"content":"partial","refusal":"I cannot help"'  | "stop"           | '{"prompt_tokens":12,"completion_tokens":4}' || 12        | 4             | true
        '"content":"partial"'                            | "content_filter" | null                                         || 9         | 0             | false
        '"content":"partial"'                            | "content_filter" | '{"prompt_tokens":12,"completion_tokens":4}' || 12        | 4             | true
        '"content":null,"refusal":"no"'                  | "length"         | null                                         || 9         | 0             | false
    }

    def "only the first choice is read"() {
        given:
        def modelCalls = modelCalls(0)
        server.expect(requestTo(COMPLETIONS)).andRespond(withRawStatus(200).body('{"choices":[' +
                '{"index":0,"message":{"content":"first"},"finish_reason":"stop"},' +
                '{"index":1,"message":{"content":"second"},"finish_reason":"length"}]}'))

        when:
        def outcome = modelCalls.call(request(null), progress)

        then:
        outcome == new CallOutcome.CameBack("first", 9, 2, false, false)
        1 * progress.aboutToSend()
        0 * _
        server.verify()
    }

    def "a body that is not a readable chat completion is an error, and nothing of it is quoted"() {
        given:
        def modelCalls = modelCalls(0)
        server.expect(requestTo(COMPLETIONS)).andRespond(withRawStatus(status).body(body))

        when:
        def outcome = modelCalls.call(request(null), progress)

        then:
        outcome == new CallOutcome.Errored("HTTP $status came back without a readable chat completion".toString())
        1 * progress.aboutToSend()
        0 * _
        server.verify()

        where:
        status | body
        200    | ''
        200    | 'not json'
        200    | '[]'
        200    | '{}'
        200    | '{"choices":[]}'
        200    | '{"choices":{"message":{"content":"x"}}}'
        200    | '{"choices":[1]}'
        200    | '{"choices":[{"message":"x"}]}'
        200    | '{"choices":[{"message":{"content":5}}]}'
        200    | '{"choices":[{"message":{"content":["x"]}}]}'
        200    | '{"choices":[{"message":{"content":"x"}}]} trailing'
        200    | '{"choices":[{"message":{"content":"x"}}],"choices":[{"message":{"content":"y"}}]}'
        201    | '{}'
    }

    def "a body running past the longest completion the store could keep an answer of is not read to its end"() {
        given:
        def modelCalls = modelCalls(0)
        def body = new OverlongBody('{"choices":[', EndpointJson.LONGEST_READ + 1_048_576)
        server.expect(requestTo(COMPLETIONS))
                .andRespond({ request -> new MockClientHttpResponse(body, 200) } as ResponseCreator)

        when:
        def outcome = modelCalls.call(request(null), progress)

        then:
        outcome == new CallOutcome.Errored("HTTP 200 came back without a readable chat completion")
        body.served > EndpointJson.LONGEST_READ
        body.served < EndpointJson.LONGEST_READ + 65_536
        1 * progress.aboutToSend()
        0 * _
        server.verify()
    }

    def "a response neither an answer nor a turnaway is an error naming its status and what the endpoint said, bounded"() {
        given:
        def modelCalls = modelCalls(3)
        server.expect(requestTo(COMPLETIONS)).andRespond(withRawStatus(status).body(body))

        when:
        def outcome = modelCalls.call(request(null), progress)

        then:
        outcome == new CallOutcome.Errored(detail)
        1 * progress.aboutToSend()
        0 * _
        server.verify()

        where:
        status | body                                                                       || detail
        500    | '{"error":{"message":"Internal error","type":"server_error","code":null}}' || "HTTP 500: Internal error"
        400    | '<html>Bad Request</html>'                                                 || "HTTP 400: <html>Bad Request</html>"
        401    | ''                                                                         || "HTTP 401"
        502    | 'x' * 65536                                                                || "HTTP 502: " + 'x' * 65536
        502    | 'x' * 65537                                                                || "HTTP 502: " + 'x' * 65536
        302    | ''                                                                         || "HTTP 302"
    }

    /** Waits of a millisecond on a running signal: the waits themselves are the resend spec's subject. */
    private OpenAiCompatibleModelCalls modelCalls(int times) {
        def shutdown = new ModelCallShutdown()
        shutdown.start()
        def resend = new TurnAwayResend(new ResendPolicy(times, Duration.ofMillis(1), Duration.ofMillis(1)),
                shutdown, InstantSource.fixed(Instant.parse("2026-09-26T10:00:00Z")))
        def builder = ModelCallsConfiguration.modelClient(ENDPOINT, "test-key", resend).mutate()
        server = MockRestServiceServer.bindTo(builder).build()
        new OpenAiCompatibleModelCalls(builder.build(), ENDPOINT.completions(), ENDPOINT.pairedWith(new ModelCatalog([MODEL])))
    }

    private static CallRequest request(ModelMode mode) {
        new CallRequest(MODEL, mode, ModelCallPurpose.PRODUCE, SentText.measure("answer in JSON", "the invoice"))
    }

    private static String completion(String message, String finish, String usage) {
        '{"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"vendor-sample","choices":[' +
                '{"index":0,"message":{' + message + '},"finish_reason":"' + finish + '","logprobs":null}]' +
                (usage == null ? '' : ',"usage":' + usage) + '}'
    }

    /**
     * Its opening, then spaces, which a parser skips without keeping, to the length given. It ends there
     * rather than never, since a read that ignored the bound would spin where no timeout can stop it.
     */
    @CompileStatic
    static final class OverlongBody extends InputStream {

        final byte[] opening

        final long length

        long served

        OverlongBody(String opening, long length) {
            this.opening = opening.getBytes(UTF_8)
            this.length = length
        }

        @Override
        int read() {
            if (served >= length) {
                return -1
            }
            int next = served < opening.length ? opening[(int) served] : (int) (' ' as char)
            served++
            next
        }

        @Override
        int read(byte[] into, int offset, int asked) {
            if (served < opening.length || served >= length) {
                int next = read()
                if (next < 0) {
                    return -1
                }
                into[offset] = (byte) next
                return 1
            }
            int given = (int) Math.min((long) asked, length - served)
            Arrays.fill(into, offset, offset + given, (byte) 32)
            served += given
            given
        }
    }
}
