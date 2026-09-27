package org.lilradish.lite.app.inference

import java.time.Duration
import java.util.function.Supplier
import org.lilradish.lite.app.inference.ModelEndpoint.VendorMode
import org.lilradish.lite.app.inference.ModelEndpoint.VendorModel
import org.lilradish.lite.domain.inference.ModelCalls
import org.lilradish.lite.domain.model.DeployedModel
import org.lilradish.lite.domain.model.ModelCatalog
import org.lilradish.lite.domain.model.ModelMode
import org.lilradish.lite.domain.model.ModelName
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import spock.lang.Specification

/**
 * The endpoint as the deployment's configuration and environment reach it, bound by the binder the
 * application starts with. The runner closes the context before returning, so what is read inside
 * the consumer is carried out as a value and asserted on afterwards.
 */
class ModelCallsConfigurationIntegrationSpec extends Specification {

    static final String KEY = "sk-test.key_1"

    static final List<String> ENDPOINT = [
        "lilradish.model-calls.endpoint.base-url=https://models.example/v1",
        "lilradish.model-calls.endpoint.connect-timeout=5s",
        "lilradish.model-calls.endpoint.read-timeout=60s",
        "lilradish.model-calls.endpoint.models[0].name=sample_model",
        "lilradish.model-calls.endpoint.models[0].id=vendor-sample",
        "lilradish.model-calls.endpoint.models[0].modes[0].mode=research",
        "lilradish.model-calls.endpoint.models[0].modes[0].reasoning-effort=high",
    ]

    static final List<String> RESEND = [
        "lilradish.model-calls.resend.times=3",
        "lilradish.model-calls.resend.first-wait=1s",
        "lilradish.model-calls.resend.longest-wait=30s",
    ]

    static final ModelName SAMPLE = new ModelName("sample_model")

    static final ModelMode RESEARCH = new ModelMode("research")

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ModelCallsConfiguration)
            .withBean(ModelCatalog, { -> new ModelCatalog([
                new DeployedModel(SAMPLE, [RESEARCH], 200000, new BigDecimal("4"), 8192, [])]) } as Supplier)

    def "without a base address nothing is bound or built, whatever else is set"() {
        when:
        Throwable refused = null
        List<Integer> found = null
        contextRunner.withPropertyValues("lilradish.model-calls.resend.times=-1").run { context ->
            refused = context.startupFailure
            found = [ModelCalls, ModelCallShutdown, ModelEndpoint, ResendPolicy, LongestSend].collect {
                context.getBeanNamesForType(it).length
            }
        }

        then:
        refused == null
        found == [0, 0, 0, 0, 0]
    }

    def "the longest one send may take is the read timeout alone, connecting being timed within it"() {
        when:
        Duration longest = null
        withKey(KEY).withPropertyValues((ENDPOINT + RESEND) as String[]).run { context ->
            longest = context.getBean(LongestSend).duration()
        }

        then:
        longest == Duration.ofSeconds(60)
    }

    def "with an endpoint set, the one model call built is the adapter, beside a running shutdown signal"() {
        when:
        Throwable refused = null
        List<String> modelCalls = null
        Object built = null
        boolean signalRunning = false
        withKey(KEY).withPropertyValues((ENDPOINT + RESEND) as String[]).run { context ->
            refused = context.startupFailure
            modelCalls = context.getBeanNamesForType(ModelCalls) as List
            built = context.getBean(ModelCalls)
            signalRunning = context.getBean(ModelCallShutdown).running
        }

        then:
        refused == null
        modelCalls == ["modelCalls"]
        built instanceof OpenAiCompatibleModelCalls
        signalRunning
    }

    def "built as the application starts even where every bean is made lazy, so a missing key stops the start"() {
        when:
        Throwable refused = null
        withKey(null)
                .withInitializer { ConfigurableApplicationContext context ->
                    context.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor())
                }
                .withPropertyValues((ENDPOINT + RESEND) as String[])
                .run { context -> refused = context.startupFailure }

        then:
        messagesOf(refused).any { it.contains("LILRADISH_MODEL_API_KEY holds no key to call it with") }
    }

    def "binds the endpoint and the resend settings as written"() {
        when:
        ModelEndpoint endpoint = null
        ResendPolicy policy = null
        withKey(KEY).withPropertyValues((ENDPOINT + RESEND) as String[]).run { context ->
            endpoint = context.getBean(ModelEndpoint)
            policy = context.getBean(ResendPolicy)
        }

        then:
        endpoint == new ModelEndpoint(URI.create("https://models.example/v1"), Duration.ofSeconds(5),
                Duration.ofSeconds(60), [new VendorModel(SAMPLE, "vendor-sample", [new VendorMode(RESEARCH, ReasoningEffort.HIGH)])])
        policy == new ResendPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(30))
    }

    def "a list the catalog does not match stops the start, naming the mismatch"() {
        when:
        Throwable refused = null
        withKey(KEY).withPropertyValues((ENDPOINT + RESEND + mismatch) as String[]).run { context ->
            refused = context.startupFailure
        }

        then:
        messagesOf(refused).any { it.contains(named) }

        where:
        mismatch                                                               || named
        "lilradish.model-calls.endpoint.models[0].name=other_model"            || "lists other_model, which the model catalog does not hold"
        "lilradish.model-calls.endpoint.models[0].modes[0].mode=deep"          || "gives sample_model the mode deep, which the model catalog does not offer it"
    }

    /** A nameless model is refused by the list, naming its place; a mode by its model, naming the model. */
    def "a vendor model or mode refused as it is bound is named by its place in the list"() {
        when:
        Throwable refused = null
        withKey(KEY).withPropertyValues((ENDPOINT.findAll { !it.startsWith(leftOut) } + RESEND) as String[]).run { context ->
            refused = context.startupFailure
        }
        def messages = messagesOf(refused)

        then:
        messages.any { it.startsWith("Failed to bind properties under '" + place + "' to ") }
        messages.any { it.contains(named) }

        where:
        leftOut                                                        || place                                              | named
        "lilradish.model-calls.endpoint.models[0].name="               || "lilradish.model-calls.endpoint"                    | "lilradish.model-calls.endpoint.models[0] has no name"
        "lilradish.model-calls.endpoint.models[0].modes[0].reasoning-effort=" || "lilradish.model-calls.endpoint.models[0]" | "gives sample_model the mode research no reasoning-effort"
    }

    def "resend settings left out stop the start, naming the first one missing"() {
        when:
        Throwable refused = null
        withKey(KEY).withPropertyValues(ENDPOINT as String[]).run { context -> refused = context.startupFailure }

        then:
        messagesOf(refused).any { it.contains("lilradish.model-calls.resend.times must be set") }
    }

    def "a key missing from the environment or unusable stops the start, naming the variable and never the value"() {
        when:
        Throwable refused = null
        withKey(inEnvironment)
                .withPropertyValues((ENDPOINT + RESEND + ["LILRADISH_MODEL_API_KEY=from-a-file-secret"]) as String[])
                .run { context -> refused = context.startupFailure }
        def messages = messagesOf(refused)

        then:
        messages.any { it.contains(refusal) && it.contains("LILRADISH_MODEL_API_KEY") }
        messages.every { !it.contains("from-a-file-secret") && !it.contains(unprinted) }

        where:
        inEnvironment         || refusal                                  | unprinted
        null                  || "holds no key to call it with"           | "from-a-file-secret"
        "   "                 || "holds no key to call it with"           | "from-a-file-secret"
        "sk-with a space"     || "holds characters a bearer token cannot" | "sk-with"
        "sk-line\nbreak"      || "holds characters a bearer token cannot" | "sk-line"
    }

    def "a start with no process environment at all stops, naming the variable, and takes no key from elsewhere"() {
        when:
        Throwable refused = null
        contextRunner
                .withInitializer { ConfigurableApplicationContext context ->
                    context.environment.propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
                }
                .withPropertyValues((ENDPOINT + RESEND + ["LILRADISH_MODEL_API_KEY=from-a-file-secret"]) as String[])
                .run { context -> refused = context.startupFailure }
        def messages = messagesOf(refused)

        then:
        messages.any { it.contains("LILRADISH_MODEL_API_KEY holds no key to call it with") }
        messages.every { !it.contains("from-a-file-secret") }
    }

    /** The process environment stood in for by one holding the key as given, or none. */
    private ApplicationContextRunner withKey(String key) {
        contextRunner.withInitializer { ConfigurableApplicationContext context ->
            def sources = context.environment.propertySources
            def name = StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME
            sources.remove(name)
            sources.addLast(new MapPropertySource(name, key == null ? [:] : [LILRADISH_MODEL_API_KEY: key]))
        }
    }

    private static List<String> messagesOf(Throwable failure) {
        List<String> messages = []
        for (Throwable cause = failure; cause != null; cause = cause.cause) {
            messages << String.valueOf(cause.message)
        }
        messages
    }
}
