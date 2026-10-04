package org.lilradish.lite.app.inference

import org.lilradish.lite.app.inference.development.DevelopmentSearchConversion
import org.lilradish.lite.domain.model.DeployedModel
import org.lilradish.lite.domain.model.DeployedTool
import org.lilradish.lite.domain.model.ModelCatalog
import org.lilradish.lite.domain.model.ModelName
import org.lilradish.lite.domain.model.ModelToolKind
import org.lilradish.lite.domain.model.ModelToolName
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import spock.lang.Specification

class ModelToolCallsConfigurationIntegrationSpec extends Specification {

    static final ModelToolName SEARCH = new ModelToolName("web_search")
    static final DeployedModel MODEL = new DeployedModel(new ModelName("sample_model"), [], 1000,
            BigDecimal.ONE, 1000, [], [new DeployedTool(SEARCH, ModelToolKind.WEB_SEARCH, 3, [])])
    static final List<String> BASE = [
        "lilradish.model-calls.endpoint.base-url=https://models.example/v1",
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
                                 context.getBean(org.lilradish.lite.domain.inference.ModelCalls).class.simpleName]
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

    private static ApplicationContextRunner runner() {
        new ApplicationContextRunner()
                .withUserConfiguration(ModelCallsConfiguration)
                .withBean(ModelCatalog, { -> new ModelCatalog([MODEL]) })
                .withInitializer { ConfigurableApplicationContext context ->
                    def sources = context.environment.propertySources
                    sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
                    sources.addLast(new MapPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                            [LILRADISH_MODEL_API_KEY: "test-key"]))
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
