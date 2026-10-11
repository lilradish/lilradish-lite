package com.lilradish.lite.app.inference.development

import static com.lilradish.lite.testutil.inference.Askings.text

import com.lilradish.lite.app.inference.ConvertedCallAnswer
import com.lilradish.lite.app.inference.EndpointJson
import com.lilradish.lite.app.inference.ModelCallInbound
import com.lilradish.lite.app.inference.ModelCallOutbound
import com.lilradish.lite.app.inference.SearchObservation
import com.lilradish.lite.app.inference.ToolCallRequest
import com.lilradish.lite.app.inference.ToolUseLimit
import com.lilradish.lite.domain.declaration.Asking
import com.lilradish.lite.domain.declaration.FieldName
import com.lilradish.lite.domain.declaration.Instruction
import com.lilradish.lite.domain.inference.CallOutcome
import com.lilradish.lite.domain.inference.CallProgress
import com.lilradish.lite.domain.inference.CallRequest
import com.lilradish.lite.domain.inference.DidNotFitReason
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
import spock.lang.Specification
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

class DevelopmentToolModelCallsSpec extends Specification {

    static final ModelToolName SEARCH = new ModelToolName("web_search")
    static final FieldName TOPIC = new FieldName("topic")
    static final Asking ASKING = new Asking(new Instruction("Find evidence for the fictional practice case."),
            [text("topic", 1000)], SearchAnswer.asked(new SearchWay("web", SEARCH, 120, 12000, 4000, 4000)))
    static final DeployedModel MODEL = new DeployedModel(new ModelName("sample_model"), [], 1000,
            BigDecimal.ONE, 1000, [], [new DeployedTool(SEARCH, ModelToolKind.WEB_SEARCH, 3, [])])

    def "a development route cannot name a model absent from the catalog"() {
        given:
        def conversion = new CountingSearchConversion()
        def unknown = new ModelName('absent_model')
        def invalid = new DevelopmentCallRoutes([new DevelopmentCallRoutes.Route(unknown, [SEARCH],
                conversion.id(), conversion.id())])
        def valid = new DevelopmentCallRoutes([new DevelopmentCallRoutes.Route(MODEL.name(), [SEARCH],
                conversion.id(), conversion.id())])

        when:
        new DevelopmentToolModelCalls(new ModelCatalog([MODEL]), invalid, [conversion], [conversion])

        then:
        def failure = thrown(IllegalStateException)
        failure.message == 'Development call route names model absent_model, which the catalog does not hold'
        conversion.outboundCalls == 0
        conversion.inboundCalls == 0

        when:
        new DevelopmentToolModelCalls(new ModelCatalog([MODEL]), valid, [conversion], [conversion])

        then:
        noExceptionThrown()
        conversion.outboundCalls == 0
        conversion.inboundCalls == 0
    }

    def "a reversed development tool combination repeats the same canonical route"() {
        given:
        def other = new ModelToolName('other_tool')
        def model = new DeployedModel(MODEL.name(), [], 1000, BigDecimal.ONE, 1000, [],
                [new DeployedTool(SEARCH, ModelToolKind.WEB_SEARCH, 3, []),
                 new DeployedTool(other, ModelToolKind.WEB_SEARCH, 3, [])])
        def conversion = new CountingSearchConversion()
        def routes = [
            new DevelopmentCallRoutes.Route(model.name(), [SEARCH], conversion.id(), conversion.id()),
            new DevelopmentCallRoutes.Route(model.name(), [other], conversion.id(), conversion.id()),
            new DevelopmentCallRoutes.Route(model.name(), [SEARCH, other], conversion.id(), conversion.id()),
        ]
        def duplicate = new DevelopmentCallRoutes.Route(model.name(), [other, SEARCH],
                conversion.id(), conversion.id())

        when:
        new DevelopmentToolModelCalls(new ModelCatalog([model]), new DevelopmentCallRoutes(routes + [duplicate]),
                [conversion], [conversion])

        then:
        def failure = thrown(IllegalStateException)
        failure.message == "Development call route repeats model sample_model and tools ${[other, SEARCH]}"
        conversion.outboundCalls == 0
        conversion.inboundCalls == 0

        when:
        new DevelopmentToolModelCalls(new ModelCatalog([model]), new DevelopmentCallRoutes(routes),
                [conversion], [conversion])

        then:
        noExceptionThrown()
        conversion.outboundCalls == 0
        conversion.inboundCalls == 0
    }

    def "a development route cannot give a model a tool it does not offer"() {
        given:
        def conversion = new CountingSearchConversion()
        def other = new ModelToolName('other_tool')
        def singleton = new DevelopmentCallRoutes.Route(MODEL.name(), [SEARCH], conversion.id(), conversion.id())
        def unoffered = new DevelopmentCallRoutes.Route(MODEL.name(), [other], conversion.id(), conversion.id())

        when:
        new DevelopmentToolModelCalls(new ModelCatalog([MODEL]), new DevelopmentCallRoutes([singleton, unoffered]),
                [conversion], [conversion])

        then:
        def failure = thrown(IllegalStateException)
        failure.message == 'Development call route gives model sample_model tool other_tool, which it does not offer'
        conversion.outboundCalls == 0
        conversion.inboundCalls == 0

        when:
        new DevelopmentToolModelCalls(new ModelCatalog([MODEL]), new DevelopmentCallRoutes([singleton]),
                [conversion], [conversion])

        then:
        noExceptionThrown()
        conversion.outboundCalls == 0
        conversion.inboundCalls == 0
    }

    def "a development combined route cannot replace missing singleton #missingTool with singleton #presentSingleton"() {
        given:
        def other = new ModelToolName('other_tool')
        def model = new DeployedModel(MODEL.name(), [], 1000, BigDecimal.ONE, 1000, [],
                [new DeployedTool(SEARCH, ModelToolKind.WEB_SEARCH, 3, []),
                 new DeployedTool(other, ModelToolKind.WEB_SEARCH, 3, [])])
        def conversion = new CountingSearchConversion()
        def routes = [new DevelopmentCallRoutes.Route(model.name(), [SEARCH, other],
                conversion.id(), conversion.id())]
        if (presentSingleton != null) {
            routes.add(new DevelopmentCallRoutes.Route(model.name(), [new ModelToolName(presentSingleton)],
                    conversion.id(), conversion.id()))
        }

        when:
        new DevelopmentToolModelCalls(new ModelCatalog([model]), new DevelopmentCallRoutes(routes),
                [conversion], [conversion])

        then:
        def failure = thrown(IllegalStateException)
        failure.message == "Development catalog offers model sample_model tool ${missingTool} without a call route"
        conversion.outboundCalls == 0
        conversion.inboundCalls == 0

        where:
        presentSingleton | missingTool
        null             | 'web_search'
        'other_tool'     | 'web_search'
        'web_search'     | 'other_tool'
    }

    def "legal development singletons do not configure an unlisted combined call"() {
        given:
        def other = new ModelToolName('other_tool')
        def model = new DeployedModel(MODEL.name(), [], 1000, BigDecimal.ONE, 1000, [],
                [new DeployedTool(SEARCH, ModelToolKind.WEB_SEARCH, 3, []),
                 new DeployedTool(other, ModelToolKind.WEB_SEARCH, 3, [])])
        def conversion = new CountingSearchConversion()
        def configured = new DevelopmentCallRoutes([
            new DevelopmentCallRoutes.Route(model.name(), [SEARCH], conversion.id(), conversion.id()),
            new DevelopmentCallRoutes.Route(model.name(), [other], conversion.id(), conversion.id()),
        ])
        def calls = new DevelopmentToolModelCalls(new ModelCatalog([model]), configured, [conversion], [conversion])
        def sent = SentText.measure('system', CanonicalJson.write(Payload.toProduce(ASKING,
                [(TOPIC): new JsonString('Imaginary Mooncrate evidence')], null)))
        def request = new ToolCallRequest(new CallRequest(model, null, ModelCallPurpose.PRODUCE, sent),
                [new ToolUseLimit(SEARCH, 3), new ToolUseLimit(other, 3)])
        CallProgress progress = Mock()

        when:
        calls.call(request, progress)

        then:
        def failure = thrown(IllegalArgumentException)
        failure.message == "No development call route for model sample_model and tools ${[other, SEARCH]}"
        conversion.outboundCalls == 0
        conversion.inboundCalls == 0
        0 * _
    }

    def "development singleton calls and an additional combined route remain usable for #selection"() {
        given:
        def other = new ModelToolName('other_tool')
        def model = new DeployedModel(MODEL.name(), [], 1000, BigDecimal.ONE, 1000, [],
                [new DeployedTool(SEARCH, ModelToolKind.WEB_SEARCH, 3, []),
                 new DeployedTool(other, ModelToolKind.WEB_SEARCH, 3, [])])
        def conversion = new CountingSearchConversion()
        def configured = new DevelopmentCallRoutes([
            new DevelopmentCallRoutes.Route(model.name(), [SEARCH], conversion.id(), conversion.id()),
            new DevelopmentCallRoutes.Route(model.name(), [other], conversion.id(), conversion.id()),
            new DevelopmentCallRoutes.Route(model.name(), [other, SEARCH], conversion.id(), conversion.id()),
        ])
        def chosen = selection == 'combined' ? [SEARCH, other] : [new ModelToolName(selection)]
        def sent = SentText.measure('system', CanonicalJson.write(Payload.toProduce(ASKING,
                [(TOPIC): new JsonString('Imaginary Mooncrate evidence')], null)))
        def request = new ToolCallRequest(new CallRequest(model, null, ModelCallPurpose.PRODUCE, sent),
                chosen.collect { new ToolUseLimit(it, 3) })
        CallProgress progress = Mock()

        when:
        def calls = new DevelopmentToolModelCalls(new ModelCatalog([model]), configured,
                [conversion], [conversion])
        def returned = calls.call(request, progress)

        then:
        returned.outcome() instanceof CallOutcome.CameBack
        returned.outcome().sentCount() == 7
        returned.outcome().cameBackCount() == 9
        !chosen.empty
        chosen.size() == (selection == 'combined' ? 2 : 1)
        returned.reportedUses() == [(chosen.first()): 2L]
        !returned.observations().empty
        returned.observations().size() == 2
        returned.observations() == [new SearchObservation(chosen.first(), 'Imaginary Mooncrate evidence',
                '## Invented source notes\nThe practice record concerns: Imaginary Mooncrate evidence\n' +
                '[Imaginary source](https://example.com/practice)\nNo real policy or supplier is represented.'),
                new SearchObservation(chosen.first(), 'Imaginary Mooncrate evidence / evidence ledger',
                        '## Invented evidence ledger\nMira Cloudmere recorded the missing box photograph for: Imaginary Mooncrate evidence\n' +
                        '[Imaginary ledger](https://example.org/evidence)\nThe fictional dispatch record remains open.')]
        EndpointJson.READER.readTree(returned.outcome().answer()).get('body').stringValue()
                .contains('Question received: Imaginary Mooncrate evidence')
        conversion.outboundCalls == 1
        conversion.inboundCalls == 1
        !conversion.sentTools.empty
        conversion.sentTools.size() == chosen.size()
        conversion.sentTools == chosen
        1 * progress.aboutToSend()
        0 * _

        where:
        selection << ['web_search', 'other_tool', 'combined']
    }

    def "an unconvertible development reply was received in full rather than stopped while downloading"() {
        given:
        def outbound = new DevelopmentSearchConversion()
        ModelCallInbound inbound = Stub() {
            id() >> "unconvertible"
            supportedToolKinds() >> ([ModelToolKind.WEB_SEARCH] as Set)
            convert(_) >> null
        }
        def routes = new DevelopmentCallRoutes([new DevelopmentCallRoutes.Route(MODEL.name(), [SEARCH],
                outbound.id(), inbound.id())])
        def calls = new DevelopmentToolModelCalls(new ModelCatalog([MODEL]), routes, [outbound], [inbound])
        def sent = SentText.measure("system", CanonicalJson.write(Payload.toProduce(ASKING,
                [(TOPIC): new JsonString("Demo Sample question")], null)))
        def request = new ToolCallRequest(new CallRequest(MODEL, null, ModelCallPurpose.PRODUCE, sent),
                [new ToolUseLimit(SEARCH, 3)])
        def raw = DevelopmentSearchConversion.replyTo(outbound.convert(request.call(), request.tools(),
                MODEL.name().value(), null))
        CallProgress progress = Mock()

        when:
        def returned = calls.call(request, progress)

        then:
        returned.outcome() instanceof CallOutcome.Errored
        returned.outcome().unreadableResponse().kept().text() == raw
        !returned.outcome().unreadableResponse().kept().altered()
        !returned.outcome().unreadableResponse().kept().truncated()
        !returned.outcome().unreadableResponse().stopped()
        returned.reportedUses().isEmpty()
        returned.observations().isEmpty()
        1 * progress.aboutToSend()
        0 * _
    }

    def "a development inbound parser keeps the full response when nested JSON is damaged: #damaged"() {
        given:
        def outbound = new CountingSearchConversion()
        def inbound = new ParsingSearchConversion(damaged)
        def routes = new DevelopmentCallRoutes([new DevelopmentCallRoutes.Route(MODEL.name(), [SEARCH],
                outbound.id(), inbound.id())])
        def calls = new DevelopmentToolModelCalls(new ModelCatalog([MODEL]), routes, [outbound], [inbound])
        def sent = SentText.measure('system', CanonicalJson.write(Payload.toProduce(ASKING,
                [(TOPIC): new JsonString('Imaginary Mooncrate evidence')], null)))
        def request = new ToolCallRequest(new CallRequest(MODEL, null, ModelCallPurpose.PRODUCE, sent),
                [new ToolUseLimit(SEARCH, 3)])
        def raw = DevelopmentSearchConversion.replyTo(new DevelopmentSearchConversion().convert(
                request.call(), request.tools(), MODEL.name().value(), null))
        CallProgress progress = Mock()

        when:
        def returned = calls.call(request, progress)

        then:
        outbound.outboundCalls == 1
        outbound.inboundCalls == 0
        inbound.parseCalls == 1
        if (damaged) {
            assert inbound.parseFailure instanceof JacksonException
            assert inbound.delegateCalls == 0
            assert returned.outcome() instanceof CallOutcome.Errored
            assert returned.outcome().detail() == 'The model service returned a response this system could not read.'
            assert returned.outcome().unreadableResponse().kept().text() == raw
            assert !returned.outcome().unreadableResponse().kept().altered()
            assert !returned.outcome().unreadableResponse().kept().truncated()
            assert !returned.outcome().unreadableResponse().stopped()
            assert returned.reportedUses().isEmpty()
            assert returned.observations().empty
        } else {
            assert inbound.parseFailure == null
            assert inbound.delegateCalls == 1
            assert returned.outcome() instanceof CallOutcome.CameBack
            assert returned.outcome().answer() == EndpointJson.READER.readTree(raw).get('answer').stringValue()
            assert returned.outcome().sentCount() == 7L
            assert returned.outcome().cameBackCount() == 9L
            assert returned.outcome().countedByModel()
            assert !returned.outcome().cutOff()
            assert returned.outcome().didNotFitReason() == null
            assert returned.outcome().declinedWords() == null
            assert returned.reportedUses() == [(SEARCH): 2L]
            assert !returned.observations().empty
            assert returned.observations().size() == 2
        }
        1 * progress.aboutToSend()
        0 * _

        where:
        damaged << [false, true]
    }

    def "development provider counts are used together or both measured here for #providerSent and #providerCameBack"() {
        given:
        def outbound = new CountingSearchConversion()
        def inbound = new ProviderCountsConversion(providerSent, providerCameBack)
        def model = new DeployedModel(MODEL.name(), [], 1000, new BigDecimal('2'), 1000, [], MODEL.tools())
        def routes = new DevelopmentCallRoutes([new DevelopmentCallRoutes.Route(model.name(), [SEARCH],
                outbound.id(), inbound.id())])
        def calls = new DevelopmentToolModelCalls(new ModelCatalog([model]), routes, [outbound], [inbound])
        def asking = new Asking(new Instruction('Find'), [text('topic', 1000)], ASKING.gives())
        def sent = SentText.measure('S\uD83D\uDE00', CanonicalJson.write(Payload.toProduce(asking,
                [(TOPIC): new JsonString('A\uD83D\uDE00B')], null)))
        def request = new ToolCallRequest(new CallRequest(model, null, ModelCallPurpose.PRODUCE, sent),
                [new ToolUseLimit(SEARCH, 3)])
        CallProgress progress = Mock()

        when:
        def returned = calls.call(request, progress)

        then:
        sent.user() == '{"envelope_version":6,"instruction":"Find","takes":{"topic":"A\uD83D\uDE00B"}}'
        returned.outcome() == new CallOutcome.CameBack('A\uD83D\uDE00B', expectedSent, expectedCameBack,
                countedByModel, true, DidNotFitReason.MODEL_DECLINED, 'No\uD83D\uDE00')
        returned.reportedUses() == [(SEARCH): 2L]
        !returned.observations().empty
        returned.observations().size() == 2
        returned.observations() == [new SearchObservation(SEARCH, 'A\uD83D\uDE00B',
                '## Invented source notes\nThe practice record concerns: A\uD83D\uDE00B\n' +
                '[Imaginary source](https://example.com/practice)\nNo real policy or supplier is represented.'),
                new SearchObservation(SEARCH, 'A\uD83D\uDE00B / evidence ledger',
                        '## Invented evidence ledger\nMira Cloudmere recorded the missing box photograph for: A\uD83D\uDE00B\n' +
                        '[Imaginary ledger](https://example.org/evidence)\nThe fictional dispatch record remains open.')]
        outbound.outboundCalls == 1
        outbound.inboundCalls == 0
        !outbound.sentTools.empty
        outbound.sentTools.size() == 1
        outbound.sentTools == [SEARCH]
        inbound.inboundCalls == 1
        inbound.delegated.sentCount() == providerSent
        inbound.delegated.cameBackCount() == providerCameBack
        inbound.delegated.generatedToolCallCharacters() == 15L
        1 * progress.aboutToSend()
        0 * _

        where:
        providerSent | providerCameBack || expectedSent | expectedCameBack | countedByModel
        7L           | 9L               || 7L           | 9L               | true
        null         | 9L               || 35L          | 11L              | false
        7L           | null             || 35L          | 11L              | false
        null         | null             || 35L          | 11L              | false
    }

    def "development conversion counts only its explicit generated tool-call text by code point"() {
        given:
        def sent = JsonMapper.builder().build().writeValueAsBytes([model: 'sample_model',
                question: CanonicalJson.write(Payload.toProduce(ASKING,
                        [(TOPIC): new JsonString('Demo 😀')], null)), tools: [[name: 'web_search', maximum: 2]]])
        def raw = DevelopmentSearchConversion.replyTo(sent)
        def envelope = EndpointJson.READER.readTree(raw)
        def generated = envelope.get('generated_tool_calls').stringValue()

        when:
        def converted = new DevelopmentSearchConversion().convert(envelope)

        then:
        generated.contains('Demo 😀')
        converted.generatedToolCallCharacters() == generated.codePointCount(0, generated.length())
        converted.generatedToolCallCharacters() < generated.length()
        converted.generatedToolCallCharacters() < raw.codePointCount(0, raw.length())
        converted.reportedUses() == [(SEARCH): 2L]
    }

    private static final class ParsingSearchConversion implements ModelCallInbound {
        private final DevelopmentSearchConversion delegate = new DevelopmentSearchConversion()
        private final boolean damaged
        int parseCalls
        int delegateCalls
        JacksonException parseFailure

        ParsingSearchConversion(boolean damaged) {
            this.damaged = damaged
        }

        @Override
        String id() { 'parsed_search' }

        @Override
        Set<ModelToolKind> supportedToolKinds() { delegate.supportedToolKinds() }

        @Override
        ConvertedCallAnswer convert(JsonNode response) {
            parseCalls++
            def answer = response.get('answer').stringValue()
            try {
                EndpointJson.READER.readTree(damaged ? answer.substring(0, answer.length() - 1) : answer)
            } catch (JacksonException failure) {
                parseFailure = failure
                throw failure
            }
            delegateCalls++
            delegate.convert(response)
        }
    }

    private static final class ProviderCountsConversion implements ModelCallInbound {
        private final DevelopmentSearchConversion delegate = new DevelopmentSearchConversion()
        private final Long sentCount
        private final Long cameBackCount
        int inboundCalls
        ConvertedCallAnswer delegated

        ProviderCountsConversion(Long sentCount, Long cameBackCount) {
            this.sentCount = sentCount
            this.cameBackCount = cameBackCount
        }

        @Override
        String id() { 'provider_counts' }

        @Override
        Set<ModelToolKind> supportedToolKinds() { delegate.supportedToolKinds() }

        @Override
        ConvertedCallAnswer convert(JsonNode response) {
            inboundCalls++
            response.put('answer', 'A\uD83D\uDE00B')
            response.put('generated_tool_calls', '[{"query":"\uD83D\uDE00"}]')
            if (sentCount == null) {
                response.remove('sent_count')
            }
            if (cameBackCount == null) {
                response.remove('came_back_count')
            }
            delegated = delegate.convert(response)
            new ConvertedCallAnswer(delegated.answer(), delegated.sentCount(), delegated.cameBackCount(),
                    true, delegated.reportedUses(), delegated.observations(), DidNotFitReason.MODEL_DECLINED,
                    'No\uD83D\uDE00', delegated.generatedToolCallCharacters())
        }
    }

    private static final class CountingSearchConversion implements ModelCallOutbound, ModelCallInbound {
        private final DevelopmentSearchConversion delegate = new DevelopmentSearchConversion()
        int outboundCalls
        int inboundCalls
        List<ModelToolName> sentTools = []

        @Override
        String id() { delegate.id() }

        @Override
        Set<ModelToolKind> supportedToolKinds() { delegate.supportedToolKinds() }

        @Override
        byte[] convert(CallRequest request, List<ToolUseLimit> tools, String vendorModelId, String mode) {
            outboundCalls++
            sentTools = tools.collect { it.tool() }
            delegate.convert(request, tools, vendorModelId, mode)
        }

        @Override
        ConvertedCallAnswer convert(JsonNode response) {
            inboundCalls++
            delegate.convert(response)
        }
    }
}
