package com.lilradish.lite.app.inference

import static com.lilradish.lite.testutil.inference.Askings.text

import com.lilradish.lite.app.inference.development.DevelopmentSearchConversion
import com.lilradish.lite.domain.declaration.Asking
import com.lilradish.lite.domain.declaration.FieldName
import com.lilradish.lite.domain.declaration.Instruction
import com.lilradish.lite.domain.inference.CallOutcome
import com.lilradish.lite.domain.inference.CallProgress
import com.lilradish.lite.domain.inference.CallRequest
import com.lilradish.lite.domain.inference.ModelCallPurpose
import com.lilradish.lite.domain.inference.Payload
import com.lilradish.lite.domain.model.DeployedModel
import com.lilradish.lite.domain.model.DeployedTool
import com.lilradish.lite.domain.model.ModelCatalog
import com.lilradish.lite.domain.model.ModelName
import com.lilradish.lite.domain.model.ModelToolKind
import com.lilradish.lite.domain.model.ModelToolName
import com.lilradish.lite.domain.model.SentText
import com.lilradish.lite.domain.search.SearchAnswer
import com.lilradish.lite.domain.search.SearchWay
import com.lilradish.lite.domain.wire.CanonicalJson
import com.lilradish.lite.domain.wire.JsonValue.JsonString
import com.sun.net.httpserver.HttpServer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import spock.lang.Specification
import spock.lang.Timeout
import tools.jackson.databind.JsonNode

class ModelToolCallsConfigurationIntegrationSpec extends Specification {

    static final ModelToolName SEARCH = new ModelToolName("web_search")
    static final DeployedModel MODEL = new DeployedModel(new ModelName("sample_model"), [], 1000,
            BigDecimal.ONE, 1000, [], [new DeployedTool(SEARCH, ModelToolKind.WEB_SEARCH, 3, [])])
    static final List<String> BASE = [
        "lilradish.model-calls.endpoint.enabled=true",
        "lilradish.model-calls.endpoint.models[0].base-url=https://models.example/v1",
        "lilradish.model-calls.endpoint.models[0].api-key-variable=SAMPLE_MODEL_KEY",
        "lilradish.model-calls.endpoint.connect-timeout=1s",
        "lilradish.model-calls.endpoint.read-timeout=1s",
        "lilradish.model-calls.endpoint.models[0].name=sample_model",
        "lilradish.model-calls.endpoint.models[0].id=vendor-sample",
        "lilradish.model-calls.resend.times=0",
        "lilradish.model-calls.resend.first-wait=1ms",
        "lilradish.model-calls.resend.longest-wait=1ms",
    ]

    def "a model offering a tool without a call way refuses startup naming both"() {
        when:
        Throwable refused = null
        runner().withPropertyValues(BASE as String[]).run { context -> refused = context.startupFailure }

        then:
        messages(refused).any { it.contains("sample_model") && it.contains("web_search") && it.contains("no way") }
    }

    def "a configured tool way whose converter is missing refuses startup by name"() {
        given:
        def route = [
            "lilradish.model-calls.endpoint.models[0].calls[0].tools[0]=web_search",
            "lilradish.model-calls.endpoint.models[0].calls[0].path=/responses",
            "lilradish.model-calls.endpoint.models[0].calls[0].outbound=missing_conversion",
            "lilradish.model-calls.endpoint.models[0].calls[0].inbound=missing_conversion",
        ]

        when:
        Throwable refused = null
        runner().withPropertyValues((BASE + route) as String[]).run { context -> refused = context.startupFailure }

        then:
        messages(refused).any { it.contains("missing_conversion") && it.contains("outbound") }
    }

    def "a registered read-only converter and its tool route build one shared transport"() {
        given:
        def route = [
            "lilradish.model-calls.endpoint.models[0].calls[0].tools[0]=web_search",
            "lilradish.model-calls.endpoint.models[0].calls[0].path=/responses",
            "lilradish.model-calls.endpoint.models[0].calls[0].outbound=development_search",
            "lilradish.model-calls.endpoint.models[0].calls[0].inbound=development_search",
        ]

        when:
        Throwable refused = null
        List<String> calls = null
        runner().withBean(DevelopmentSearchConversion)
                .withPropertyValues((BASE + route) as String[]).run { context ->
                    refused = context.startupFailure
                    if (refused == null) {
                        calls = [context.getBean(ToolModelCalls).class.simpleName,
                                 context.getBean(com.lilradish.lite.domain.inference.ModelCalls).class.simpleName]
                    }
                }

        then:
        refused == null
        calls == ["ConfiguredToolModelCalls", "OpenAiCompatibleModelCalls"]
    }

    def "a converter without tool support cannot be bound to a read-only search tool"() {
        given:
        def route = [
            "lilradish.model-calls.endpoint.models[0].calls[0].tools[0]=web_search",
            "lilradish.model-calls.endpoint.models[0].calls[0].path=/responses",
            "lilradish.model-calls.endpoint.models[0].calls[0].outbound=openai_compatible",
            "lilradish.model-calls.endpoint.models[0].calls[0].inbound=openai_compatible",
        ]

        when:
        Throwable refused = null
        runner().withPropertyValues((BASE + route) as String[]).run { context -> refused = context.startupFailure }

        then:
        messages(refused).any { it.contains("sample_model") && it.contains("web_search") && it.contains("read-only conversion") }
    }

    def "a bound combined route cannot replace either singleton required by the catalog"() {
        given:
        def other = new ModelToolName("other_tool")
        def model = new DeployedModel(MODEL.name(), [], 1000, BigDecimal.ONE, 1000, [],
                [new DeployedTool(SEARCH, ModelToolKind.WEB_SEARCH, 3, []),
                 new DeployedTool(other, ModelToolKind.WEB_SEARCH, 3, [])])
        def route = [
            "lilradish.model-calls.endpoint.models[0].calls[0].tools[0]=web_search",
            "lilradish.model-calls.endpoint.models[0].calls[0].tools[1]=other_tool",
            "lilradish.model-calls.endpoint.models[0].calls[0].path=/responses",
            "lilradish.model-calls.endpoint.models[0].calls[0].outbound=development_search",
            "lilradish.model-calls.endpoint.models[0].calls[0].inbound=development_search",
        ]
        Throwable failure = null

        when:
        runner(new ModelCatalog([model])).withBean(DevelopmentSearchConversion)
                .withPropertyValues((BASE + route) as String[]).run { context -> failure = context.startupFailure }

        then:
        messages(failure).any { it == "The model catalog offers sample_model the tool web_search, but lilradish.model-calls.endpoint.models gives no way to call it with that tool" }
    }

    def "a registered outbound conversion cannot conceal a missing inbound conversion"() {
        given:
        def route = [
            "lilradish.model-calls.endpoint.models[0].calls[0].tools[0]=web_search",
            "lilradish.model-calls.endpoint.models[0].calls[0].path=/responses",
            "lilradish.model-calls.endpoint.models[0].calls[0].outbound=development_search",
            "lilradish.model-calls.endpoint.models[0].calls[0].inbound=missing_inbound",
        ]
        Throwable failure = null

        when:
        runner().withBean(DevelopmentSearchConversion)
                .withPropertyValues((BASE + route) as String[]).run { context -> failure = context.startupFailure }

        then:
        messages(failure).any { it == "No inbound model call converter is named missing_inbound" }
        !messages(failure).any { it.contains("No outbound") }
    }

    @Timeout(10)
    def "registered inbound kind support #supported controls startup before conversion or HTTP"() {
        given:
        def outgoing = new CountedOutbound(new DevelopmentSearchConversion())
        def incoming = new CountedInbound(supported ? new DevelopmentSearchConversion() : new OpenAiCompatibleCallConversion())
        def received = new CopyOnWriteArrayList<String>()
        def addresses = new CopyOnWriteArrayList<List<String>>()
        def workers = Executors.newSingleThreadExecutor()
        def wire = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        wire.executor = workers
        wire.createContext('/') { exchange ->
            exchange.withCloseable {
                byte[] requestBytes = exchange.requestBody.readAllBytes()
                received.add(new String(requestBytes, java.nio.charset.StandardCharsets.UTF_8))
                addresses.add([exchange.requestMethod, exchange.requestURI.toString()])
                byte[] reply = DevelopmentSearchConversion.replyTo(requestBytes).getBytes(java.nio.charset.StandardCharsets.UTF_8)
                exchange.responseHeaders.set('Content-Type', 'application/json')
                exchange.sendResponseHeaders(200, reply.length)
                exchange.responseBody.write(reply)
            }
        }
        wire.start()
        def properties = BASE.findAll { !it.contains('.base-url=') } + [
            "lilradish.model-calls.endpoint.models[0].base-url=http://127.0.0.1:${wire.address.port}/v1".toString(),
            'lilradish.model-calls.endpoint.models[0].calls[0].tools[0]=web_search',
            'lilradish.model-calls.endpoint.models[0].calls[0].path=/responses',
            'lilradish.model-calls.endpoint.models[0].calls[0].outbound=counted_search_outbound',
            'lilradish.model-calls.endpoint.models[0].calls[0].inbound=counted_search_inbound',
        ]
        def asking = new Asking(new Instruction('Find sources for the imaginary Mooncrate practice.'),
                [text('topic', 1000)], SearchAnswer.asked(new SearchWay('web', SEARCH, 120, 12000, 4000, 4000)))
        def sent = SentText.measure('system', CanonicalJson.write(Payload.toProduce(asking,
                [(new FieldName('topic')): new JsonString('Imaginary Mooncrate evidence')], null)))
        def request = new ToolCallRequest(new CallRequest(MODEL, null, ModelCallPurpose.PRODUCE, sent),
                [new ToolUseLimit(SEARCH, 3)])
        CallProgress progress = Mock()
        Throwable failure = null
        ToolCallOutcome returned = null

        when:
        runner().withBean(CountedOutbound, { -> outgoing }).withBean(CountedInbound, { -> incoming })
                .withPropertyValues(properties as String[]).run { context ->
                    failure = context.startupFailure
                    if (failure == null) returned = context.getBean(ToolModelCalls).call(request, progress)
                }

        then:
        if (supported) {
            assert failure == null
            assert returned.outcome() instanceof CallOutcome.CameBack
            assert returned.outcome().sentCount() == 7
            assert returned.outcome().cameBackCount() == 9
            assert returned.reportedUses() == [(SEARCH): 2L]
            assert !returned.observations().empty
            assert returned.observations().size() == 2
            assert returned.observations() == [new SearchObservation(SEARCH, 'Imaginary Mooncrate evidence',
                    '## Invented source notes\nThe practice record concerns: Imaginary Mooncrate evidence\n' +
                    '[Imaginary source](https://example.com/practice)\nNo real policy or supplier is represented.'),
                    new SearchObservation(SEARCH, 'Imaginary Mooncrate evidence / evidence ledger',
                            '## Invented evidence ledger\nMira Cloudmere recorded the missing box photograph for: Imaginary Mooncrate evidence\n' +
                            '[Imaginary ledger](https://example.org/evidence)\nThe fictional dispatch record remains open.')]
            assert received.size() == 1
            assert !addresses.empty
            assert addresses.size() == 1
            assert addresses == [['POST', '/v1/responses']]
            def body = EndpointJson.READER.readTree(received.first())
            assert body.get('model').stringValue() == 'vendor-sample'
            assert body.get('question').stringValue() == sent.user()
            assert body.get('tools').size() == 1
            assert body.get('tools').get(0).get('name').stringValue() == 'web_search'
            assert body.get('tools').get(0).get('maximum').longValue() == 3
        } else {
            assert messages(failure).any { it == 'Model sample_model tool web_search has no read-only conversion for its kind WEB_SEARCH' }
            assert !messages(failure).any { it.startsWith('No inbound') || it.startsWith('No outbound') }
            assert returned == null
            assert received.isEmpty()
            assert addresses.isEmpty()
        }
        outgoing.converted == (supported ? 1 : 0)
        incoming.converted == (supported ? 1 : 0)
        (supported ? 1 : 0) * progress.aboutToSend()
        0 * progress._

        cleanup:
        wire.stop(0)
        workers.shutdownNow()
        assert workers.awaitTermination(3, TimeUnit.SECONDS)

        where:
        supported << [false, true]
    }

    private static final class CountedOutbound implements ModelCallOutbound {
        final ModelCallOutbound delegate
        int converted

        CountedOutbound(ModelCallOutbound delegate) { this.delegate = delegate }

        @Override
        String id() { 'counted_search_outbound' }

        @Override
        Set<ModelToolKind> supportedToolKinds() { delegate.supportedToolKinds() }

        @Override
        byte[] convert(CallRequest request, List<ToolUseLimit> tools, String vendorModelId, String mode) {
            converted++
            delegate.convert(request, tools, vendorModelId, mode)
        }
    }

    private static final class CountedInbound implements ModelCallInbound {
        final ModelCallInbound delegate
        int converted

        CountedInbound(ModelCallInbound delegate) { this.delegate = delegate }

        @Override
        String id() { 'counted_search_inbound' }

        @Override
        Set<ModelToolKind> supportedToolKinds() { delegate.supportedToolKinds() }

        @Override
        ConvertedCallAnswer convert(JsonNode response) {
            converted++
            delegate.convert(response)
        }
    }

    private static ApplicationContextRunner runner(ModelCatalog catalog = new ModelCatalog([MODEL])) {
        new ApplicationContextRunner()
                .withUserConfiguration(ModelCallsConfiguration)
                .withBean(ModelCatalog, { -> catalog })
                .withInitializer { ConfigurableApplicationContext context ->
                    def sources = context.environment.propertySources
                    sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
                    sources.addLast(new MapPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                            [SAMPLE_MODEL_KEY: "test-key"]))
                }
    }

    private static List<String> messages(Throwable failure) {
        List<String> found = []
        for (Throwable cause = failure; cause != null; cause = cause.cause) {
            found << String.valueOf(cause.message)
        }
        found
    }
}
