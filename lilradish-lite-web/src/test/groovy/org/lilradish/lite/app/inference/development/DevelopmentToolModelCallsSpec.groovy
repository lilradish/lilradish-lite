package org.lilradish.lite.app.inference.development

import org.lilradish.lite.app.inference.EndpointJson
import org.lilradish.lite.app.inference.SearchObservation
import org.lilradish.lite.app.inference.ToolCallOutcome
import org.lilradish.lite.app.inference.ToolCallRequest
import org.lilradish.lite.app.inference.ToolModelCalls
import org.lilradish.lite.app.inference.ToolUseLimit
import org.lilradish.lite.domain.inference.CallOutcome
import org.lilradish.lite.domain.inference.CallProgress
import org.lilradish.lite.domain.inference.CallRequest
import org.lilradish.lite.domain.inference.ModelCallPurpose
import org.lilradish.lite.domain.model.DeployedModel
import org.lilradish.lite.domain.model.DeployedTool
import org.lilradish.lite.domain.model.ModelCatalog
import org.lilradish.lite.domain.model.ModelName
import org.lilradish.lite.domain.model.ModelToolKind
import org.lilradish.lite.domain.model.ModelToolName
import org.lilradish.lite.domain.model.SentText
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import spock.lang.Specification

class DevelopmentToolModelCallsSpec extends Specification {

    static final ModelToolName SEARCH = new ModelToolName("web_search")
    static final DeployedModel MODEL = new DeployedModel(new ModelName("sample_model"), [], 1000,
            BigDecimal.ONE, 1000, [], [new DeployedTool(SEARCH, ModelToolKind.WEB_SEARCH, 3, [])])

    def "development conversion counts only its explicit generated tool-call text by code point"() {
        given:
        def sent = '{"model":"sample_model","question":"Demo 😀","tools":[{"name":"web_search","maximum":2}]}'
        def raw = DevelopmentSearchConversion.replyTo(sent.getBytes(java.nio.charset.StandardCharsets.UTF_8))
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

    def "development search passes through configured route and both conversions with a fixed four-field answer"() {
        given:
        CallProgress progress = Mock()
        ToolCallOutcome returned = null
        Throwable refused = null
        def runner = new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.config.location=classpath:/config/application-dev.yaml")
                .withUserConfiguration(DevelopmentCallConfiguration)
                .withBean(ModelCatalog, { -> new ModelCatalog([MODEL]) })
                .withBean(DevelopmentSearchConversion)
                .withBean(DevelopmentToolModelCalls)

        when:
        runner.run { context ->
            refused = context.startupFailure
            if (refused == null) {
                def sent = SentText.measure("system", "Demo Sample question")
                returned = context.getBean(ToolModelCalls).call(new ToolCallRequest(
                        new CallRequest(MODEL, null, ModelCallPurpose.PRODUCE, sent),
                        [new ToolUseLimit(SEARCH, maximum)]), progress)
            }
        }

        then:
        refused == null
        returned.outcome() == new CallOutcome.CameBack(
                DevelopmentSearchConversion.ANSWER,
                7, 9, true, false, null, null)
        returned.reportedUses() == [(SEARCH): reported]
        returned.observations() == [new SearchObservation(SEARCH, "Demo Sample question", "Demo Sample page")]
        returned.outcome().answer().contains('"title":"Damaged parcels: refund and claim rules"')
        1 * progress.aboutToSend()
        0 * _

        where:
        maximum || reported
        3L      || 2L
        1L      || 1L
    }
}
