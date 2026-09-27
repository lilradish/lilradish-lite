package org.lilradish.lite.app.model

import org.lilradish.lite.domain.model.DeployedModel
import org.lilradish.lite.domain.model.ModelCatalog
import org.lilradish.lite.domain.model.ModelMode
import org.lilradish.lite.domain.model.ModelName
import org.lilradish.lite.domain.model.ModelPrice
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import spock.lang.Specification

/**
 * The catalog as the deployment's configuration reaches it, bound by the binder the application
 * starts with rather than built by hand, so a rule held only by a constructor nothing binds reddens here.
 *
 * <p>The runner closes the context it started before returning, so what is read inside the consumer
 * is carried out as a value and asserted on afterwards.
 */
class ModelCatalogConfigurationIntegrationSpec extends Specification {

    private static final Currency USD = Currency.getInstance("USD")
    private static final Currency EUR = Currency.getInstance("EUR")
    private static final ModelMode RESEARCH = new ModelMode("research")
    private static final List<String> SECOND_PRICE_IN_USD = [
        "lilradish.deployed.models[1].prices-per-million[1].currency=USD",
        "lilradish.deployed.models[1].prices-per-million[1].sent-per-million=1",
        "lilradish.deployed.models[1].prices-per-million[1].came-back-per-million=1",
    ]

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(ModelCatalogConfiguration)

    def "binds exactly the listed entries into the catalog"() {
        given:
        def properties = entry(0, [name: "sample_model", modes: "research,deep_research"]) +
                entry(1, [
                    name: "other_model",
                    "sent-per-call-limit": "1000",
                    "characters-per-unit": "3.5",
                    "came-back-per-call-limit": "10",
                    "prices-per-million[0].currency": "EUR",
                    "prices-per-million[0].sent-per-million": "0.25",
                    "prices-per-million[0].came-back-per-million": "1.50",
                    "prices-per-million[1].currency": "USD",
                    "prices-per-million[1].sent-per-million": "0.30",
                    "prices-per-million[1].came-back-per-million": "1.75",
                ])

        when:
        Throwable refused = null
        List<DeployedModel> bound = null
        contextRunner.withPropertyValues(properties as String[]).run { context ->
            refused = context.startupFailure
            bound = context.getBean(ModelCatalog).all()
        }

        then:
        refused == null
        bound == [
            new DeployedModel(new ModelName("sample_model"), [RESEARCH, new ModelMode("deep_research")],
                    200000, new BigDecimal("4"), 8192, [new ModelPrice(USD, new BigDecimal("3"), new BigDecimal("15"))]),
            new DeployedModel(new ModelName("other_model"), [RESEARCH], 1000, new BigDecimal("3.5"), 10,
                    [new ModelPrice(EUR, new BigDecimal("0.25"), new BigDecimal("1.50")),
                     new ModelPrice(USD, new BigDecimal("0.30"), new BigDecimal("1.75"))]),
        ]
    }

    /**
     * Loaded as the development start loads it, so a mistake in it reddens the build rather than a
     * redeploy. The test list beside it is identical, so where the bound list came from is asserted too.
     */
    def "binds the development list through the development profile"() {
        when:
        Throwable refused = null
        List<DeployedModel> bound = null
        List<Object> namedByDevelopmentFile = null
        contextRunner
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=dev", "spring.config.location=classpath:/config/")
                .run { context ->
                    refused = context.startupFailure
                    bound = context.getBean(ModelCatalog).all()
                    namedByDevelopmentFile = context.environment.propertySources
                            .findAll { it.name.contains("application-dev.yaml") }
                            .collect { it.getProperty("lilradish.deployed.models[0].name") }
                }

        then:
        refused == null
        namedByDevelopmentFile == ["sample_model"]
        bound == [
            new DeployedModel(new ModelName("sample_model"), [RESEARCH],
                    200000, new BigDecimal("4"), 8192, [new ModelPrice(USD, new BigDecimal("3"), new BigDecimal("15"))]),
        ]
    }

    def "binds a model that offers no mode beyond running as it is, whether its list is empty or left out"() {
        given:
        def properties = entry(0, [name: "sample_model", modes: modes])

        when:
        Throwable refused = null
        List<DeployedModel> bound = null
        contextRunner.withPropertyValues(properties as String[]).run { context ->
            refused = context.startupFailure
            bound = context.getBean(ModelCatalog).all()
        }

        then:
        refused == null
        bound == [
            new DeployedModel(new ModelName("sample_model"), [], 200000, new BigDecimal("4"), 8192,
                    [new ModelPrice(USD, new BigDecimal("3"), new BigDecimal("15"))]),
        ]

        where:
        modes << ["", null]
    }

    def "refuses to start when the deployment lists no models, as the catalog's refusal rather than a binding one"() {
        when:
        Throwable refused = null
        contextRunner.run { context -> refused = context.startupFailure }

        then:
        refused != null
        messagesOf(refused).contains("ModelCatalog holds no models")
        !messagesOf(refused).contains("Failed to bind")
    }

    def "refuses to start when the deployment lists no models even where every bean is otherwise made lazily"() {
        when:
        Throwable refused = null
        contextRunner
                .withInitializer { it.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()) }
                .run { context -> refused = context.startupFailure }

        then:
        refused != null
        messagesOf(refused).contains("ModelCatalog holds no models")
        !messagesOf(refused).contains("Failed to bind")
    }

    def "refuses to start on an entry it cannot hold, naming the entry or the value at fault and not the sound one"() {
        given:
        def properties = entry(0, [name: "sound_model"]) + broken

        when:
        Throwable refused = null
        contextRunner.withPropertyValues(properties as String[]).run { context -> refused = context.startupFailure }

        then:
        refused != null
        def messages = messagesOf(refused)
        messages.contains(named)
        messages.contains(reason)
        !messages.contains("sound_model")

        where:
        broken                                                                        || named                                                        | reason
        entry(1, [name: "Bad_model"])                                                 || "Bad_model"                                                  | "ModelName must start with a lowercase letter"
        entry(1, [name: "bad_Model"])                                                 || "bad_Model"                                                  | "ModelName must contain only lowercase letters, digits and _"
        entry(1, [name: "b" * 64])                                                    || "b" * 64                                                     | "ModelName must not exceed 63 characters"
        entry(1, [name: "1model"])                                                    || "1model"                                                     | "ModelName must start with a lowercase letter"
        entry(1, [name: "bad_model"]) + entry(2, [name: "bad_model"])                 || "bad_model"                                                  | "ModelCatalog lists bad_model more than once"
        entry(1, [name: "bad_model", modes: "Research"])                              || "Research"                                                   | "ModelMode must start with a lowercase letter"
        entry(1, [name: "bad_model", modes: "research,deep-research"])                || "deep-research"                                              | "ModelMode must contain only lowercase letters, digits and _"
        entry(1, [name: "bad_model", modes: null, "modes[0]": "r" * 64])              || "r" * 64                                                     | "ModelMode must not exceed 63 characters"
        entry(1, [name: "bad_model", modes: "research,ordinary"])                     || "for value [ordinary]"                                       | "ModelMode must not be ordinary, which names running a model as it is"
        entry(1, [name: "bad_model", modes: "research,deep_research,research"])       || "bad_model"                                                  | "offers research more than once"
        entry(1, [name: "bad_model", modes: null, "modes[0]": "research", "modes[1]": "research"]) || "bad_model"                                     | "offers research more than once"
        entry(1, [name: "bad_model", modes: null, "modes[0]": ""])                    || "lilradish.deployed.models[1].modes[0]"                      | "ModelMode must not be empty"
        entry(1, [name: "bad_model", "sent-per-call-limit": "0"])                     || "bad_model"                                                  | "sent-per-call limit is missing or not positive: 0"
        entry(1, [name: "bad_model", "characters-per-unit": null])                    || "bad_model"                                                  | "has no characters-per-unit"
        entry(1, [name: "bad_model", "characters-per-unit": "0.00001"])               || "bad_model"                                                  | "characters-per-unit has more than 4 decimal places: 0.00001"
        entry(1, [name: "bad_model", "came-back-per-call-limit": null])               || "bad_model"                                                  | "came-back-per-call limit is missing or not positive: 0"
        entry(1, [name: "bad_model", "prices-per-million[0].sent-per-million": "-1"]) || "USD"                                                        | "has a negative sent price: -1"
        entry(1, [name: "bad_model", "prices-per-million[0].currency": "ABC"])        || "ABC"                                                        | "Failed to convert"
        entry(1, [name: "bad_model", "prices-per-million[0].currency": "usd"])        || "usd"                                                        | "Failed to convert"
        entry(1, [name: "bad_model", "prices-per-million[0].currency": "USDX"])       || "USDX"                                                       | "Failed to convert"
        entry(1, [name: "bad_model", "prices-per-million[0].currency": "XAU"])        || "XAU"                                                        | "is not one anything is priced in"
        entry(1, [name: "bad_model"]) + SECOND_PRICE_IN_USD                           || "bad_model"                                                  | "prices USD more than once"
        entry(1, [name: "bad_model", "prices-per-milion[0].currency": "EUR"])         || "lilradish.deployed.models[1].prices-per-milion[0].currency" | "were left unbound"
    }

    /** One entry of the list, sound in every field not overridden; an override of null leaves the field out. */
    private static List<String> entry(int index, Map<String, String> overrides) {
        def fields = [
            name: "sample_model",
            modes: "research",
            "sent-per-call-limit": "200000",
            "characters-per-unit": "4",
            "came-back-per-call-limit": "8192",
            "prices-per-million[0].currency": "USD",
            "prices-per-million[0].sent-per-million": "3",
            "prices-per-million[0].came-back-per-million": "15",
        ] + overrides
        fields.findAll { it.value != null }.collect { key, value ->
            "lilradish.deployed.models[" + index + "]." + key + "=" + value
        }
    }

    private static String messagesOf(Throwable refused) {
        def messages = []
        for (def cause = refused; cause != null; cause = cause.cause) {
            messages << cause.message
        }
        messages.join("\n")
    }
}
