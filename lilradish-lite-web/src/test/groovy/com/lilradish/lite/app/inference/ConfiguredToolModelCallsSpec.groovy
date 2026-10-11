package com.lilradish.lite.app.inference

import static org.springframework.test.web.client.match.MockRestRequestMatchers.content
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import static org.springframework.test.web.client.response.MockRestResponseCreators.withRawStatus

import com.lilradish.lite.app.inference.ModelEndpoint.CallKey
import com.lilradish.lite.app.inference.ModelEndpoint.CallWay
import com.lilradish.lite.app.inference.ModelEndpoint.VendorCall
import com.lilradish.lite.app.inference.ModelEndpoint.VendorModel
import com.lilradish.lite.app.inference.development.DevelopmentSearchConversion
import com.lilradish.lite.app.inference.fixture.OverlongBody
import com.lilradish.lite.domain.inference.CallOutcome
import com.lilradish.lite.domain.inference.CallProgress
import com.lilradish.lite.domain.inference.CallRequest
import com.lilradish.lite.domain.inference.DidNotFitReason
import com.lilradish.lite.domain.inference.ModelCallPurpose
import com.lilradish.lite.domain.model.DeployedModel
import com.lilradish.lite.domain.model.DeployedTool
import com.lilradish.lite.domain.model.ModelCatalog
import com.lilradish.lite.domain.model.ModelName
import com.lilradish.lite.domain.model.ModelToolKind
import com.lilradish.lite.domain.model.ModelToolName
import com.lilradish.lite.domain.model.SentText
import com.sun.net.httpserver.HttpServer
import java.time.Duration
import java.time.Instant
import java.time.InstantSource
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.springframework.mock.http.client.MockClientHttpResponse
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.ResponseCreator
import spock.lang.Specification
import tools.jackson.databind.JsonNode

class ConfiguredToolModelCallsSpec extends Specification {

    static final ModelName MODEL_NAME = new ModelName("sample_model")
    static final ModelToolName SEARCH = new ModelToolName("web_search")
    static final DeployedModel MODEL = new DeployedModel(MODEL_NAME, [], 1000, BigDecimal.ONE, 1000, [],
            [new DeployedTool(SEARCH, ModelToolKind.WEB_SEARCH, 3, [])])
    static final URI ADDRESS = URI.create("https://models.example/v1/responses")
    static final ModelEndpoint ENDPOINT = new ModelEndpoint(true,
            Duration.ofSeconds(1), Duration.ofSeconds(1), [new VendorModel(MODEL_NAME, "vendor-sample", "https://models.example/v1", "SAMPLE_MODEL_KEY", [], [])])
    static final String RAW = '{"provider":"response"}'

    CallProgress progress = Mock()
    MockRestServiceServer server

    def "converted decline words require a model decline"() {
        when:
        new ConvertedCallAnswer("Demo Sample answer", null, null, false, [:], [], reason,
                "Demo Sample decline", 0L)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "ConvertedCallAnswer has decline words only with model decline"

        where:
        reason << [null, DidNotFitReason.NOT_THE_SHAPE]
    }

    def "a converted answer keeps model content and reported search evidence without changing the model count"() {
        given:
        def calls = calls(new FixedConversion(true))
        server.expect(requestTo(ADDRESS)).andExpect(content().string('{"question":"question"}'))
                .andRespond(withRawStatus(200).body(RAW))

        when:
        def returned = calls.call(request(), progress)

        then:
        returned.outcome() == new CallOutcome.CameBack('{"title":"Demo Sample"}', 7, 9, true, false, null, null)
        returned.reportedUses() == [(SEARCH): 2L]
        returned.observations() == [new SearchObservation(SEARCH, "question", "Demo Sample page")]
        1 * progress.aboutToSend()
        0 * _
        server.verify()
    }

    def "an unconvertible successful response is an unreadable service envelope"() {
        given:
        def calls = calls(new FixedConversion(false))
        server.expect(requestTo(ADDRESS)).andRespond(withRawStatus(200).body(RAW))

        when:
        def returned = calls.call(request(), progress)

        then:
        returned.outcome() instanceof CallOutcome.Errored
        returned.outcome().detail() == 'The model service returned a response this system could not read.'
        returned.outcome().unreadableResponse().kept().text() == RAW
        !returned.outcome().unreadableResponse().kept().altered()
        !returned.outcome().unreadableResponse().kept().truncated()
        !returned.outcome().unreadableResponse().stopped()
        returned.reportedUses().isEmpty()
        returned.observations().isEmpty()
        1 * progress.aboutToSend()
        0 * _
        server.verify()
    }

    def "tool-generated text joins fallback measurement only when either provider count is absent"() {
        given:
        def calls = calls(new FixedConversion(true, sentCount, cameBackCount, 5L))
        server.expect(requestTo(ADDRESS)).andRespond(withRawStatus(200).body(RAW))

        when:
        def returned = calls.call(request(), progress)

        then:
        returned.outcome() instanceof CallOutcome.CameBack
        returned.outcome().cameBackCount() == expected
        returned.outcome().countedByModel() == countedByModel
        1 * progress.aboutToSend()
        0 * _
        server.verify()

        where:
        sentCount | cameBackCount || expected | countedByModel
        7L        | 9L            || 9L       | true
        7L        | null          || 28L      | false
        null      | 9L            || 28L      | false
        null      | null          || 28L      | false
    }

    def "a readable tool envelope may carry model content that does not fit the declared answer"() {
        given:
        def converter = new DevelopmentSearchConversion()
        def calls = calls(converter, converter)
        def raw = '{"answer":"not-json","tool":"web_search","query":"question",' +
                '"found":"Demo Sample page","uses":2,"generated_tool_calls":"call",' +
                '"observations":[{"query":"question","found":"Demo Sample page"},{"query":"second question","found":"Imaginary second page"}]}'
        server.expect(requestTo(ADDRESS)).andRespond(withRawStatus(200).body(raw))

        when:
        def returned = calls.call(request(), progress)

        then:
        returned.outcome() instanceof CallOutcome.CameBack
        returned.outcome().answer() == 'not-json'
        returned.outcome().didNotFitReason() == null
        returned.outcome().cameBackCount() == 12L
        returned.reportedUses() == [(SEARCH): 2L]
        1 * progress.aboutToSend()
        0 * _
        server.verify()
    }

    def "malformed successful provider data keeps bounded service evidence with a neutral error"() {
        given:
        def converter = new DevelopmentSearchConversion()
        def calls = calls(converter, converter)
        server.expect(requestTo(ADDRESS)).andRespond(withRawStatus(200).body(raw))

        when:
        def returned = calls.call(request(), progress)

        then:
        returned.outcome() instanceof CallOutcome.Errored
        returned.outcome().detail() == 'The model service returned a response this system could not read.'
        returned.outcome().unreadableResponse().kept().text() == raw
        returned.reportedUses().isEmpty()
        returned.observations().isEmpty()
        1 * progress.aboutToSend()
        0 * _
        server.verify()

        where:
        raw << [
                '{"answer":"Demo Sample","tool":"Bad-Tool","query":"question","found":"page","uses":2,"generated_tool_calls":"call","observations":[{"query":"question","found":"page"},{"query":"second question","found":"second page"}]}',
                '{"answer":"Demo Sample","tool":"web_search","query":"question","found":"page","uses":-1,"generated_tool_calls":"call","observations":[{"query":"question","found":"page"},{"query":"second question","found":"second page"}]}'
        ]
    }

    def "tool service bytes with malformed UTF-8 cannot become a successful converted answer"() {
        given:
        def calls = calls(new FixedConversion(true))
        def body = new SequenceInputStream(Collections.enumeration([
                new ByteArrayInputStream('{"provider":"'.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                new ByteArrayInputStream([(byte) 0xE0, (byte) 0x80, (byte) 0x80] as byte[]),
                new ByteArrayInputStream('"}'.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        ]))
        server.expect(requestTo(ADDRESS))
                .andRespond({ ignored -> new MockClientHttpResponse(body, 200) } as ResponseCreator)

        when:
        def returned = calls.call(request(), progress)

        then:
        returned.outcome() instanceof CallOutcome.Errored
        returned.outcome().detail() == 'The model service returned a response this system could not read.'
        returned.outcome().unreadableResponse().kept().text().contains('\uFFFD')
        returned.outcome().unreadableResponse().kept().altered()
        returned.outcome().unreadableResponse().stopped()
        returned.reportedUses().isEmpty()
        1 * progress.aboutToSend()
        0 * _
        server.verify()
    }

    def "a converter programming error is propagated rather than treated as bad provider data"() {
        given:
        def calls = calls(new FixedConversion(true), new BrokenInbound())
        boolean failOnClose = closeFails
        def body = new ByteArrayInputStream(RAW.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            @Override
            void close() throws IOException {
                if (failOnClose) {
                    throw new IOException("Imaginary cleanup failure")
                }
                super.close()
            }
        }
        server.expect(requestTo(ADDRESS))
                .andRespond({ ignored -> new MockClientHttpResponse(body, 200) } as ResponseCreator)

        when:
        calls.call(request(), progress)

        then:
        def broken = thrown(IllegalStateException)
        broken.message == "conversion implementation failed"
        1 * progress.aboutToSend()
        0 * _
        server.verify()

        where:
        closeFails << [false, true]
    }

    def "an overlong tool response has a neutral error and does not read to its end"() {
        given:
        def calls = calls(new FixedConversion(true))
        def body = new OverlongBody('{"answer":', EndpointJson.LONGEST_READ + 1_048_576)
        server.expect(requestTo(ADDRESS))
                .andRespond({ ignored -> new MockClientHttpResponse(body, 200) } as ResponseCreator)

        when:
        def returned = calls.call(request(), progress)

        then:
        returned.outcome() instanceof CallOutcome.Errored
        returned.outcome().detail() == 'The model service returned a response this system could not read.'
        returned.outcome().unreadableResponse().kept().truncated()
        returned.reportedUses().isEmpty()
        returned.observations().isEmpty()
        body.served > EndpointJson.LONGEST_READ
        body.served < EndpointJson.LONGEST_READ + 65_536
        returned.outcome().unreadableResponse().stopped()
        1 * progress.aboutToSend()
        0 * _
        server.verify()
    }

    def "a valid unconfigured tool combination is refused before conversion or sending while its configured counterpart sends"() {
        given:
        def other = new ModelToolName("other_tool")
        def model = new DeployedModel(MODEL_NAME, [], 1000, BigDecimal.ONE, 1000, [],
                [new DeployedTool(SEARCH, ModelToolKind.WEB_SEARCH, 3, []),
                 new DeployedTool(other, ModelToolKind.WEB_SEARCH, 3, [])])
        def workers = Executors.newSingleThreadExecutor()
        def received = new CopyOnWriteArrayList<String>()
        def wire = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
        wire.executor = workers
        wire.createContext("/") { exchange ->
            exchange.withCloseable {
                received.add(new String(exchange.requestBody.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                byte[] body = RAW.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.length)
                exchange.responseBody.write(body)
            }
        }
        wire.start()
        def routes = [new VendorCall([SEARCH], "/first", "counted", "counted"),
                      new VendorCall([other], "/second", "counted", "counted")]
        if (configured) routes.add(new VendorCall([SEARCH, other], "/combined", "counted", "counted"))
        def endpoint = new ModelEndpoint(true, Duration.ofSeconds(1), Duration.ofSeconds(1),
                [new VendorModel(MODEL_NAME, "vendor-sample", "http://127.0.0.1:${wire.address.port}/v1".toString(),
                        "SAMPLE_MODEL_KEY", [], routes)])
        def shutdown = new ModelCallShutdown()
        shutdown.start()
        def resend = new TurnAwayResend(new ResendPolicy(0, Duration.ofMillis(1), Duration.ofMillis(1)),
                shutdown, InstantSource.fixed(Instant.parse("2026-09-30T10:00:00Z")))
        ModelCallOutbound outbound = Mock()
        ModelCallInbound inbound = Mock()
        outbound.id() >> "counted"
        inbound.id() >> "counted"
        def transport = new ModelCallTransport([(MODEL_NAME): ModelCallsConfiguration.modelClient(endpoint, "test-key", resend)],
                endpoint.callWays(new ModelCatalog([model])),
                new ModelCallConversions([outbound, new OpenAiCompatibleCallConversion()], [inbound, new OpenAiCompatibleCallConversion()]))
        def calls = new ConfiguredToolModelCalls(transport)
        def request = new ToolCallRequest(new CallRequest(model, null, ModelCallPurpose.PRODUCE,
                SentText.measure("system", "question")), [new ToolUseLimit(SEARCH, 3), new ToolUseLimit(other, 3)])
        def answer = new ConvertedCallAnswer("Imaginary combined answer", 7L, 9L, false, [:], [], null, null, 0L)
        Throwable refusal = null
        def returned = null

        when:
        try {
            returned = calls.call(request, progress)
        } catch (IllegalArgumentException failure) {
            refusal = failure
        }

        then:
        (configured ? 1 : 0) * outbound.convert(request.call(), request.tools(), "vendor-sample", null) >> '{"combined":true}'.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        (configured ? 1 : 0) * inbound.convert(_) >> answer
        (configured ? 1 : 0) * progress.aboutToSend()
        0 * progress._
        if (configured) {
            assert refusal == null
            assert returned.outcome() == new CallOutcome.CameBack("Imaginary combined answer", 7L, 9L, true, false, null, null)
            assert received == ['{"combined":true}']
        } else {
            assert refusal.message.contains("sample_model")
            assert refusal.message.contains("web_search")
            assert refusal.message.contains("other_tool")
            assert returned == null
            assert received.isEmpty()
        }

        cleanup:
        shutdown.stop()
        wire.stop(0)
        workers.shutdownNow()
        assert workers.awaitTermination(3, TimeUnit.SECONDS)

        where:
        configured << [false, true]
    }

    private ConfiguredToolModelCalls calls(FixedConversion converter) {
        calls(converter, converter)
    }

    private ConfiguredToolModelCalls calls(ModelCallOutbound outbound, ModelCallInbound inbound) {
        def shutdown = new ModelCallShutdown()
        shutdown.start()
        def resend = new TurnAwayResend(new ResendPolicy(0, Duration.ofMillis(1), Duration.ofMillis(1)),
                shutdown, InstantSource.fixed(Instant.parse("2026-09-30T10:00:00Z")))
        def builder = ModelCallsConfiguration.modelClient(ENDPOINT, "test-key", resend).mutate()
        server = MockRestServiceServer.bindTo(builder).build()
        def way = new CallWay(ADDRESS, new VendorModel(MODEL_NAME, "vendor-sample", "https://models.example/v1", "SAMPLE_MODEL_KEY", [], []), outbound.id(), inbound.id())
        def conversions = new ModelCallConversions([outbound], [inbound])
        new ConfiguredToolModelCalls(new ModelCallTransport([(MODEL_NAME): builder.build()], [(new CallKey(MODEL_NAME, [SEARCH])): way],
                conversions))
    }

    private static ToolCallRequest request() {
        def call = new CallRequest(MODEL, null, ModelCallPurpose.PRODUCE, SentText.measure("system", "question"))
        new ToolCallRequest(call, [new ToolUseLimit(SEARCH, 3)])
    }

    private static final class BrokenInbound implements ModelCallInbound {
        @Override
        String id() { "broken" }

        @Override
        Set<ModelToolKind> supportedToolKinds() { [ModelToolKind.WEB_SEARCH] as Set }

        @Override
        ConvertedCallAnswer convert(JsonNode response) {
            throw new IllegalStateException("conversion implementation failed")
        }
    }

    private static final class FixedConversion implements ModelCallOutbound, ModelCallInbound {
        private final boolean readable
        private final Long sentCount
        private final Long cameBackCount
        private final long generatedToolCallCharacters

        FixedConversion(boolean readable) {
            this(readable, 7L, 9L, 0L)
        }

        FixedConversion(boolean readable, Long sentCount, Long cameBackCount, long generatedToolCallCharacters) {
            this.readable = readable
            this.sentCount = sentCount
            this.cameBackCount = cameBackCount
            this.generatedToolCallCharacters = generatedToolCallCharacters
        }

        @Override
        String id() { "fixed" }

        @Override
        Set<ModelToolKind> supportedToolKinds() { [ModelToolKind.WEB_SEARCH] as Set }

        @Override
        byte[] convert(CallRequest request, List<ToolUseLimit> tools, String vendorModel, String mode) {
            '{"question":"question"}'.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        }

        @Override
        ConvertedCallAnswer convert(JsonNode response) {
            readable ? new ConvertedCallAnswer('{"title":"Demo Sample"}', sentCount, cameBackCount, false,
                    [(SEARCH): 2L], [new SearchObservation(SEARCH, "question", "Demo Sample page")], null, null,
                    generatedToolCallCharacters) : null
        }
    }
}
