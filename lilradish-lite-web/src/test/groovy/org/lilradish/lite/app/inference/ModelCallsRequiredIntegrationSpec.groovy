package org.lilradish.lite.app.inference

import java.util.function.Supplier
import org.lilradish.lite.domain.inference.CallOutcome
import org.lilradish.lite.domain.inference.ModelCalls
import org.lilradish.lite.domain.model.DeployedModel
import org.lilradish.lite.domain.model.ModelCatalog
import org.lilradish.lite.domain.model.ModelMode
import org.lilradish.lite.domain.model.ModelName
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import spock.lang.Specification

/**
 * What a deployment is told when nothing, or more than one thing, calls its models, or a setting under
 * the model calls' prefix is one nothing binds. The runner closes the context it started before
 * returning, so what is read inside the consumer is carried out as a value and asserted on afterwards.
 */
class ModelCallsRequiredIntegrationSpec extends Specification {

    static final ModelCalls ANSWERING = { request, progress -> new CallOutcome.NotResent() } as ModelCalls

    static final String SECRET = "sk-a-secret-value"

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(ModelCallsRequired)

    def "refuses to start where nothing calls a model, saying how to configure one"() {
        when:
        Throwable refused = null
        contextRunner.run { context -> refused = context.startupFailure }

        then:
        refused.message.contains("carries no ModelCalls")
        refused.message.contains("lilradish.model-calls.endpoint.base-url")
    }

    def "refuses to start where more than one could, naming each"() {
        when:
        Throwable refused = null
        contextRunner
                .withBean("endpointModelCalls", ModelCalls, { -> ANSWERING } as Supplier)
                .withBean("standInModelCalls", ModelCalls, { -> ANSWERING } as Supplier)
                .run { context -> refused = context.startupFailure }

        then:
        refused.message.contains("more than one ModelCalls")
        refused.message.contains("endpointModelCalls, standInModelCalls")
    }

    def "starts where exactly one calls the models, whatever its scope, and never refuses it"() {
        when:
        Throwable refused = null
        int modelCalls = -1
        contextRunner.withBean(ModelCalls, { -> ANSWERING } as Supplier,
                { BeanDefinition definition -> definition.scope = scope } as BeanDefinitionCustomizer).run { context ->
            refused = context.startupFailure
            modelCalls = context.getBeanNamesForType(ModelCalls).length
        }

        then:
        refused == null
        modelCalls == 1

        where:
        scope << [BeanDefinition.SCOPE_SINGLETON, BeanDefinition.SCOPE_PROTOTYPE]
    }

    def "starts with the endpoint's own configuration, its model calls seen before any is built and every setting it binds known"() {
        when:
        Throwable refused = null
        List<String> modelCalls = null
        contextRunner
                .withUserConfiguration(ModelCallsConfiguration)
                .withBean(ModelCatalog, { -> new ModelCatalog([new DeployedModel(new ModelName("sample_model"),
                        [new ModelMode("research")], 200000, new BigDecimal("4"), 8192, [])]) } as Supplier)
                .withInitializer { ConfigurableApplicationContext context ->
                    def sources = context.environment.propertySources
                    def name = StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME
                    sources.remove(name)
                    sources.addLast(new MapPropertySource(name, [LILRADISH_MODEL_API_KEY: "sk-test.key_1"]))
                }
                .withPropertyValues((ModelCallsConfigurationIntegrationSpec.ENDPOINT
                        + ModelCallsConfigurationIntegrationSpec.RESEND) as String[])
                .run { context ->
                    refused = context.startupFailure
                    modelCalls = context.getBeanNamesForType(ModelCalls) as List
                }

        then:
        refused == null
        modelCalls == ["modelCalls"]
    }

    def "refuses to start on a setting under the prefix that nothing binds, naming it and never what it holds"() {
        when:
        Throwable refused = null
        contextRunner.withBean(ModelCalls, { -> ANSWERING } as Supplier)
                .withPropertyValues(setting + "=" + SECRET)
                .run { context -> refused = context.startupFailure }
        def causes = causesOf(refused)

        then:
        refused.message == "Nothing binds " + named + ". The model endpoint's key is read from the environment" +
                " variable LILRADISH_MODEL_API_KEY alone."
        causes.every { !String.valueOf(it.message).contains(SECRET) }
        causes.every { !(it instanceof BindException) }

        where:
        setting                                               || named
        "lilradish.model-calls.endpoint.api-key"              || "lilradish.model-calls.endpoint.api-key"
        "lilradish.model-calls.endpoint.apiKey"               || "lilradish.model-calls.endpoint.apikey"
        "lilradish.model-calls.api-key"                       || "lilradish.model-calls.api-key"
        "lilradish.model-calls.resend.key"                    || "lilradish.model-calls.resend.key"
        "lilradish.model-calls.endpoint.models[0].key"        || "lilradish.model-calls.endpoint.models[0].key"
        "lilradish.model-calls.endpoint.models[0].modes[1].k" || "lilradish.model-calls.endpoint.models[0].modes[1].k"
        "lilradish.model-calls.endpoint.models"               || "lilradish.model-calls.endpoint.models"
    }

    /** Started, nothing was refused, so no report exists that could carry the secret. */
    def "leaves the process environment to the binder, which binds the endpoint and never reports a name from it"() {
        when:
        Throwable refused = null
        List<String> modelCalls = null
        contextRunner
                .withUserConfiguration(ModelCallsConfiguration)
                .withBean(ModelCatalog, { -> new ModelCatalog([new DeployedModel(new ModelName("sample_model"),
                        [new ModelMode("research")], 200000, new BigDecimal("4"), 8192, [])]) } as Supplier)
                .withInitializer { ConfigurableApplicationContext context ->
                    def sources = context.environment.propertySources
                    def name = StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME
                    sources.remove(name)
                    sources.addLast(new SystemEnvironmentPropertySource(name, [LILRADISH_MODEL_API_KEY: "sk-test.key_1",
                            LILRADISH_MODELCALLS_ENDPOINT_APIKEY: SECRET] as Map<String, Object>))
                }
                .withPropertyValues((ModelCallsConfigurationIntegrationSpec.ENDPOINT
                        + ModelCallsConfigurationIntegrationSpec.RESEND) as String[])
                .run { context ->
                    refused = context.startupFailure
                    modelCalls = context.getBeanNamesForType(ModelCalls) as List
                }

        then:
        refused == null
        modelCalls == ["modelCalls"]
    }

    private static List<Throwable> causesOf(Throwable failure) {
        List<Throwable> causes = []
        for (Throwable cause = failure; cause != null; cause = cause.cause) {
            causes << cause
        }
        causes
    }
}
