package org.lilradish.lite.app.model

import org.lilradish.lite.domain.model.ModelCatalog
import org.lilradish.lite.domain.model.ModelToolName
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import spock.lang.Specification

class ModelToolDeploymentIntegrationSpec extends Specification {

    static final List<String> MODEL = [
        "lilradish.deployed.models[0].name=sample_model",
        "lilradish.deployed.models[0].sent-per-call-limit=1000",
        "lilradish.deployed.models[0].characters-per-unit=1",
        "lilradish.deployed.models[0].came-back-per-call-limit=1000",
    ]

    def "the deployment binds a read-only tool with its own usage bound and per-use price"() {
        given:
        def tool = [
            "lilradish.deployed.models[0].tools[0].name=web_search",
            "lilradish.deployed.models[0].tools[0].kind=web_search",
            "lilradish.deployed.models[0].tools[0].max-uses-per-call=3",
            "lilradish.deployed.models[0].tools[0].prices-per-use[0].currency=EUR",
            "lilradish.deployed.models[0].tools[0].prices-per-use[0].per-use=0.02",
        ]

        when:
        Throwable refused = null
        def offered = null
        def currencies = null
        new ApplicationContextRunner().withUserConfiguration(ModelCatalogConfiguration)
                .withPropertyValues((MODEL + tool) as String[]).run { context ->
                    refused = context.startupFailure
                    if (refused == null) {
                        def catalog = context.getBean(ModelCatalog)
                        offered = catalog.tool(catalog.all()[0].name(), new ModelToolName("web_search")).orElseThrow()
                        currencies = catalog.currencies()*.currencyCode
                    }
                }

        then:
        refused == null
        offered.maxUsesPerCall() == 3
        offered.pricesPerUse()[0].currency().currencyCode == "EUR"
        offered.pricesPerUse()[0].perUse() == new BigDecimal("0.02")
        currencies == ["EUR"]
    }

    def "an invalid tool name or action kind refuses the deployment start"() {
        given:
        def tool = [
            "lilradish.deployed.models[0].tools[0].name=${name}",
            "lilradish.deployed.models[0].tools[0].kind=${kind}",
            "lilradish.deployed.models[0].tools[0].max-uses-per-call=3",
        ]

        when:
        Throwable refused = null
        new ApplicationContextRunner().withUserConfiguration(ModelCatalogConfiguration)
                .withPropertyValues((MODEL + tool) as String[]).run { context -> refused = context.startupFailure }

        then:
        refused != null
        messages(refused).any { it.contains(named) }

        where:
        name          | kind         || named
        "Write_search" | "web_search" || "Write_search"
        "web_search"  | "execute"    || "execute"
    }

    private static List<String> messages(Throwable failure) {
        List<String> found = []
        for (Throwable cause = failure; cause != null; cause = cause.cause) {
            found << String.valueOf(cause.message)
        }
        found
    }
}
