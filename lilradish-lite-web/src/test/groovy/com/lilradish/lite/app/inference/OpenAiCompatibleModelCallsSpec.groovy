package com.lilradish.lite.app.inference

import static java.nio.charset.StandardCharsets.UTF_8
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException
import static org.springframework.test.web.client.response.MockRestResponseCreators.withRawStatus

import com.lilradish.lite.app.inference.ModelEndpoint.VendorMode
import com.lilradish.lite.app.inference.ModelEndpoint.VendorModel
import com.lilradish.lite.app.inference.fixture.OverlongBody
import com.lilradish.lite.domain.inference.CallOutcome
import com.lilradish.lite.domain.inference.CallProgress
import com.lilradish.lite.domain.inference.CallRequest
import com.lilradish.lite.domain.inference.DidNotFitReason
import com.lilradish.lite.domain.inference.ModelCallPurpose
import com.lilradish.lite.domain.inference.TurnAway
import com.lilradish.lite.domain.model.DeployedModel
import com.lilradish.lite.domain.model.ModelCatalog
import com.lilradish.lite.domain.model.ModelMode
import com.lilradish.lite.domain.model.ModelName
import com.lilradish.lite.domain.model.SentText
import java.time.Duration
import java.time.Instant
import java.time.InstantSource
import java.util.Objects
import org.springframework.http.HttpMethod
import org.springframework.mock.http.client.MockClientHttpResponse
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.ResponseCreator
import org.springframework.web.client.ResourceAccessException
import spock.lang.Specification
import spock.lang.Timeout

/**
 * The adapter over the client the configuration builds, headers and resend included; only the wire is
 * replaced. A measured count is distinguished from one the model gave.
 */
@Timeout(30)
class OpenAiCompatibleModelCallsSpec extends Specification {

    static final ModelName SAMPLE = new ModelName("sample_model")

    static final ModelMode RESEARCH = new ModelMode("research")

    static final DeployedModel MODEL = new DeployedModel(SAMPLE, [RESEARCH], 200000, new BigDecimal("3"), 8192, [], [])

    static final ModelEndpoint ENDPOINT = new ModelEndpoint(true,
            Duration.ofSeconds(10), Duration.ofSeconds(10),
            [new VendorModel(SAMPLE, "vendor-sample", "https://models.example/v1", "SAMPLE_MODEL_KEY", [new VendorMode(RESEARCH, ReasoningEffort.HIGH)], [])])

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
        outcome == new CallOutcome.Errored(detail, null)
        1 * progress.aboutToSend()
        0 * _
        server.verify()

        where:
        failure                                            || detail
        new SocketTimeoutException("Read timed out")       || "No answer came back: java.net.SocketTimeoutException"
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
                "No answer came back: org.springframework.web.client.ResourceAccessException", null)
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
        outcome == new CallOutcome.CameBack("ok", 9, 1, false, false, null, null)
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
        def unpaired = new DeployedModel(new ModelName("other_model"), [], 200000, new BigDecimal("3"), 8192, [], [])

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
        outcome == new CallOutcome.CameBack(answer, sentCount, cameBackCount, countedByModel, cutOff, null, null)
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

    def "a refusal or filtered completion retains its text and usage but cannot fit a declared answer"() {
        given:
        def modelCalls = modelCalls(0)
        server.expect(requestTo(COMPLETIONS)).andRespond(withRawStatus(200).body(completion(message, finish, usage)))

        when:
        def outcome = modelCalls.call(request(null), progress)

        then:
        outcome == new CallOutcome.CameBack(answer, sentCount, cameBackCount, countedByModel, false,
                DidNotFitReason.MODEL_DECLINED, declinedWords)
        1 * progress.aboutToSend()
        0 * _
        server.verify()

        where:
        message                                          | finish           | usage                                        || answer    | declinedWords   | sentCount | cameBackCount | countedByModel
        '"content":null,"refusal":"I cannot help"'       | "stop"           | null                                         || ""        | "I cannot help" | 9         | 5             | false
        '"content":"","refusal":"I cannot help"'         | "stop"           | null                                         || ""        | "I cannot help" | 9         | 5             | false
        '"content":"","refusal":"I cannot help"'         | "stop"           | '{"prompt_tokens":12,"completion_tokens":4}' || ""        | "I cannot help" | 12        | 4             | true
        '"content":"partial","refusal":"I cannot help"'  | "stop"           | '{"prompt_tokens":12,"completion_tokens":4}' || "partial" | "I cannot help" | 12        | 4             | true
        '"content":"partial","refusal":"I cannot help"'  | "stop"           | null                                         || "partial" | "I cannot help" | 9         | 7             | false
        '"content":"partial"'                            | "content_filter" | null                                         || "partial" | null            | 9         | 3             | false
        '"content":"partial"'                            | "content_filter" | '{"prompt_tokens":12,"completion_tokens":4}' || "partial" | null            | 12        | 4             | true
        '"content":null'                                 | "content_filter" | null                                         || ""        | null            | 9         | 0             | false
        '"content":"","refusal":""'                    | "content_filter" | '{"prompt_tokens":12,"completion_tokens":4}' || ""        | ""              | 12        | 4             | true
        '"content":null,"refusal":"no"'                  | "length"         | null                                         || ""        | "no"            | 9         | 1             | false
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
        outcome == new CallOutcome.CameBack("first", 9, 2, false, false, null, null)
        1 * progress.aboutToSend()
        0 * _
        server.verify()
    }

    def "an IOException while closing does not replace the parsed call result"() {
        given:
        def calls = modelCalls(0)
        def body = new ByteArrayInputStream(raw.getBytes(UTF_8)) {
            @Override
            void close() throws IOException {
                throw new IOException("Imaginary cleanup failure")
            }
        }
        server.expect(requestTo(COMPLETIONS))
                .andRespond({ ignored -> new MockClientHttpResponse(body, 200) } as ResponseCreator)

        when:
        def outcome = calls.call(request(null), progress)

        then:
        if (readable) {
            assert outcome instanceof CallOutcome.CameBack
            assert outcome.answer() == 'ok'
        } else {
            assert outcome instanceof CallOutcome.Errored
            assert outcome.detail() == 'The model service returned a response this system could not read.'
            assert outcome.unreadableResponse().kept().text() == '?'
        }
        1 * progress.aboutToSend()
        0 * _
        server.verify()

        where:
        readable | raw
        true     | '{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}'
        false    | '?'
    }

    def "an unreadable service envelope is an error with bounded service evidence"() {
        given:
        def modelCalls = modelCalls(0)
        server.expect(requestTo(COMPLETIONS)).andRespond(withRawStatus(status).body(body))

        when:
        def outcome = modelCalls.call(request(null), progress)

        then:
        outcome instanceof CallOutcome.Errored
        outcome.detail() == 'The model service returned a response this system could not read.'
        outcome.unreadableResponse().kept().text() == body
        !outcome.unreadableResponse().kept().truncated()
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

    def "a supplementary model character survives one-byte transport reads"() {
        given:
        def modelCalls = modelCalls(0)
        def bytes = completion('"content":"Demo Sample 😀"', 'stop', null).getBytes(UTF_8)
        def stream = new FilterInputStream(new ByteArrayInputStream(bytes)) {
            @Override
            int read(byte[] into, int offset, int asked) {
                super.read(into, offset, Math.min(asked, 1))
            }
        }
        server.expect(requestTo(COMPLETIONS))
                .andRespond({ ignored -> new MockClientHttpResponse(stream, 200) } as ResponseCreator)

        when:
        def outcome = modelCalls.call(request(null), progress)

        then:
        outcome instanceof CallOutcome.CameBack
        outcome.answer() == 'Demo Sample 😀'
        outcome.cameBackCount() == 5L
        1 * progress.aboutToSend()
        0 * _
        server.verify()
    }

    def "invalid UTF-8 in an otherwise readable envelope is a service error with changed evidence"() {
        given:
        def modelCalls = modelCalls(0)
        def invalid = [(byte) 0xE0, (byte) 0x80, (byte) 0x80] as byte[]
        def body = new SequenceInputStream(Collections.enumeration([
                new ByteArrayInputStream('{"choices":[{"message":{"content":"Demo Sample","ignored":"'.getBytes(UTF_8)),
                new ByteArrayInputStream(invalid),
                new ByteArrayInputStream('"}}]}'.getBytes(UTF_8))
        ]))
        server.expect(requestTo(COMPLETIONS))
                .andRespond({ ignored -> new MockClientHttpResponse(body, 200) } as ResponseCreator)

        when:
        def outcome = modelCalls.call(request(null), progress)

        then:
        outcome instanceof CallOutcome.Errored
        outcome.detail() == 'The model service returned a response this system could not read.'
        outcome.unreadableResponse().kept().text().contains('\uFFFD')
        outcome.unreadableResponse().kept().altered()
        !outcome.unreadableResponse().kept().truncated()
        outcome.unreadableResponse().stopped()
        1 * progress.aboutToSend()
        0 * _
        server.verify()
    }

    @Timeout(120)
    def "a malformed byte beyond the evidence bound still makes a parsed response an error"() {
        given:
        def modelCalls = modelCalls(0)
        def invalid = [(byte) 0xE0, (byte) 0x80, (byte) 0x80] as byte[]
        def body = new SequenceInputStream(Collections.enumeration([
                new ByteArrayInputStream('{"choices":[{"message":{"content":"'.getBytes(UTF_8)),
                new RepeatedBytes('x'.getBytes(UTF_8), 8_388_609),
                new ByteArrayInputStream('","ignored":"'.getBytes(UTF_8)),
                new ByteArrayInputStream(invalid),
                new ByteArrayInputStream('"}}]}'.getBytes(UTF_8))
        ]))
        server.expect(requestTo(COMPLETIONS))
                .andRespond({ ignored -> new MockClientHttpResponse(body, 200) } as ResponseCreator)

        when:
        def outcome = modelCalls.call(request(null), progress)

        then:
        outcome instanceof CallOutcome.Errored
        outcome.detail() == 'The model service returned a response this system could not read.'
        outcome.unreadableResponse().kept().truncated()
        outcome.unreadableResponse().kept().text().codePointCount(0, outcome.unreadableResponse().kept().text().length()) ==
                8_388_608
        outcome.unreadableResponse().stopped()
        1 * progress.aboutToSend()
        0 * _
        server.verify()
    }

    @Timeout(120)
    def "an early parser failure keeps only what arrived even when the finite tail meets the evidence edge"() {
        given:
        def modelCalls = modelCalls(0)
        def body = new OverlongBody('?', 8_388_608 + extra)
        server.expect(requestTo(COMPLETIONS))
                .andRespond({ ignored -> new MockClientHttpResponse(body, 200) } as ResponseCreator)

        when:
        def outcome = modelCalls.call(request(null), progress)

        then:
        outcome instanceof CallOutcome.Errored
        outcome.unreadableResponse().kept().text() == '?' + ' ' * (body.served - 1)
        !outcome.unreadableResponse().kept().truncated()
        !outcome.unreadableResponse().kept().altered()
        outcome.unreadableResponse().stopped()
        body.served > 0
        body.served <= 8192
        1 * progress.aboutToSend()
        0 * _
        server.verify()

        where:
        extra << [0, 1]
    }

    @Timeout(120)
    def "an early parser failure leaves a large finite service tail unread"() {
        given:
        def modelCalls = modelCalls(0)
        def body = new OverlongBody('?', 16_777_216)
        server.expect(requestTo(COMPLETIONS))
                .andRespond({ ignored -> new MockClientHttpResponse(body, 200) } as ResponseCreator)

        when:
        def outcome = modelCalls.call(request(null), progress)

        then:
        outcome instanceof CallOutcome.Errored
        !outcome.unreadableResponse().kept().truncated()
        outcome.unreadableResponse().stopped()
        outcome.unreadableResponse().kept().text().length() == body.served
        body.served > 0
        body.served <= 8192
        1 * progress.aboutToSend()
        0 * _
        server.verify()
    }

    @Timeout(120)
    def "two separately bounded completion texts reach the converter through a stream"() {
        given:
        def modelCalls = modelCalls(0)
        def most = 8_388_608
        def escaped = '\\ud83d\\ude00'.getBytes(UTF_8)
        def body = new SequenceInputStream(Collections.enumeration([
                new ByteArrayInputStream('{"choices":[{"message":{"content":"'.getBytes(UTF_8)),
                new RepeatedBytes(escaped, most),
                new ByteArrayInputStream('","refusal":"'.getBytes(UTF_8)),
                new RepeatedBytes(escaped, most),
                new ByteArrayInputStream('"},"finish_reason":"stop"}]}'.getBytes(UTF_8))
        ]))
        server.expect(requestTo(COMPLETIONS))
                .andRespond({ request -> new MockClientHttpResponse(body, 200) } as ResponseCreator)

        when:
        def outcome = modelCalls.call(request(null), progress)

        then:
        outcome instanceof CallOutcome.CameBack
        outcome.didNotFitReason() == DidNotFitReason.MODEL_DECLINED
        outcome.answer().codePointCount(0, outcome.answer().length()) == most
        outcome.declinedWords().codePointCount(0, outcome.declinedWords().length()) == most
        outcome.answer().startsWith("\uD83D\uDE00")
        outcome.declinedWords().endsWith("\uD83D\uDE00")
        !outcome.countedByModel()
        1 * progress.aboutToSend()
        0 * _
        server.verify()
    }

    def "a body running past the two-text completion bound is not read to its end"() {
        given:
        def modelCalls = modelCalls(0)
        def body = new OverlongBody('{"choices":[', EndpointJson.LONGEST_COMPATIBLE_COMPLETION + 1_048_576)
        server.expect(requestTo(COMPLETIONS))
                .andRespond({ request -> new MockClientHttpResponse(body, 200) } as ResponseCreator)

        when:
        def outcome = modelCalls.call(request(null), progress)

        then:
        outcome instanceof CallOutcome.Errored
        outcome.detail() == 'The model service returned a response this system could not read.'
        outcome.unreadableResponse().kept().truncated()
        body.served > EndpointJson.LONGEST_COMPATIBLE_COMPLETION
        body.served < EndpointJson.LONGEST_COMPATIBLE_COMPLETION + 65_536
        outcome.unreadableResponse().stopped()
        1 * progress.aboutToSend()
        0 * _
        server.verify()
    }

    def "the parsed completion keeps its model content and the reader refuses repeated keys"() {
        given:
        def compatible = new OpenAiCompatibleCallConversion()

        expect:
        compatible.convert(EndpointJson.COMPATIBLE_COMPLETION_READER.readTree(
                completion('"content":"Demo Sample answer"', "stop", null))) ==
                new ConvertedCallAnswer("Demo Sample answer", null, null, false, [:], [], null, null, 0L)

        when:
        EndpointJson.COMPATIBLE_COMPLETION_READER.readTree(
                '{"choices":[{"message":{"content":"Demo Sample answer"}}],"choices":[]}')

        then:
        thrown(tools.jackson.core.JacksonException)
    }

    def "a response neither an answer nor a turnaway is an error naming its status and what the endpoint said, bounded"() {
        given:
        def modelCalls = modelCalls(3)
        server.expect(requestTo(COMPLETIONS)).andRespond(withRawStatus(status).body(body))

        when:
        def outcome = modelCalls.call(request(null), progress)

        then:
        outcome == new CallOutcome.Errored(detail, null)
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
        def compatible = new OpenAiCompatibleCallConversion()
        new OpenAiCompatibleModelCalls(new ModelCallTransport([(SAMPLE): builder.build()],
                ENDPOINT.callWays(new ModelCatalog([MODEL])), new ModelCallConversions([compatible], [compatible])))
    }

    private static CallRequest request(ModelMode mode) {
        new CallRequest(MODEL, mode, ModelCallPurpose.PRODUCE, SentText.measure("answer in JSON", "the invoice"))
    }

    private static String completion(String message, String finish, String usage) {
        '{"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"vendor-sample","choices":[' +
                '{"index":0,"message":{' + message + '},"finish_reason":"' + finish + '","logprobs":null}]' +
                (usage == null ? '' : ',"usage":' + usage) + '}'
    }

    /** A finite body without a second copy of either near-limit text in the test fixture. */
    private static final class RepeatedBytes extends InputStream {
        private final byte[] block
        private final long length
        private long served

        RepeatedBytes(byte[] unit, long copies) {
            block = new byte[unit.length * 4096]
            4096.times { index -> System.arraycopy(unit, 0, block, index * unit.length, unit.length) }
            length = Math.multiplyExact(copies, unit.length)
        }

        @Override
        int read() {
            if (served >= length) {
                return -1
            }
            int next = block[(int) (served % block.length)] & 0xff
            served++
            next
        }

        @Override
        int read(byte[] into, int offset, int asked) {
            Objects.checkFromIndexSize(offset, asked, into.length)
            if (asked == 0) {
                return 0
            }
            if (served >= length) {
                return -1
            }
            int given = (int) Math.min((long) asked, length - served)
            int left = given
            while (left > 0) {
                int start = (int) (served % block.length)
                int part = Math.min(left, block.length - start)
                System.arraycopy(block, start, into, offset + given - left, part)
                served += part
                left -= part
            }
            given
        }
    }

}
