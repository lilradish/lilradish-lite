package org.lilradish.lite.domain.model

import spock.lang.Specification

class ModelCatalogSpec extends Specification {

    private static final Currency USD = Currency.getInstance("USD")
    private static final Currency EUR = Currency.getInstance("EUR")
    private static final Currency JPY = Currency.getInstance("JPY")

    def "refuses a list it cannot hold, naming the model where there is one to name"() {
        when:
        new ModelCatalog(models)

        then:
        def error = thrown(expectedException)
        error.message == expectedMessage

        where:
        models                                                                    || expectedException        | expectedMessage
        []                                                                        || IllegalArgumentException | "ModelCatalog holds no models"
        [model("sample_model"), null]                                             || NullPointerException     | "ModelCatalog holds a null model"
        [model("sample_model"), model("other_model"), model("sample_model", USD)] || IllegalArgumentException | "ModelCatalog lists sample_model more than once"
    }

    def "detaches its list from the caller's"() {
        given:
        def models = new ArrayList([model("sample_model")])

        when:
        def catalog = new ModelCatalog(models)
        models.add(model("other_model"))

        then:
        catalog.all() == [model("sample_model")]
    }

    def "finds a model it holds by name, and nothing under a name it does not hold"() {
        given:
        def catalog = new ModelCatalog([model("sample_model", USD), model("other_model")])

        expect:
        catalog.find(new ModelName("sample_model")) == Optional.of(model("sample_model", USD))
        catalog.find(new ModelName("other_model")) == Optional.of(model("other_model"))
        catalog.find(new ModelName("absent_model")).isEmpty()
    }

    def "lists every model in the order the deployment lists them, and no more"() {
        given:
        def catalog = new ModelCatalog([model("zeta_model"), model("alpha_model"), model("mid_model")])

        expect:
        catalog.all() == [model("zeta_model"), model("alpha_model"), model("mid_model")]
    }

    def "hands back a list of models that cannot be widened after the fact"() {
        given:
        def catalog = new ModelCatalog([model("sample_model")])

        when:
        catalog.all().add(model("other_model"))

        then:
        thrown(UnsupportedOperationException)
        catalog.all() == [model("sample_model")]
    }

    def "prices a held model only in a currency it is priced in"() {
        given:
        def catalog = new ModelCatalog([model("sample_model", USD, EUR), model("other_model", JPY)])

        expect:
        catalog.price(new ModelName(name), currency).map { it.currency() } == Optional.ofNullable(expected)

        where:
        name           | currency || expected
        "sample_model" | USD      || USD
        "sample_model" | EUR      || EUR
        "sample_model" | JPY      || null
        "other_model"  | JPY      || JPY
        "other_model"  | USD      || null
        "absent_model" | USD      || null
    }

    def "answers with the very price the model lists, not one from another model in the same currency"() {
        given:
        def dear = new ModelPrice(USD, 30.0, 150.0)
        def cheap = new ModelPrice(USD, 1.0, 2.0)
        def catalog = new ModelCatalog([
            new DeployedModel(new ModelName("dear_model"), [], 200000, 4.0, 8192, [dear]),
            new DeployedModel(new ModelName("cheap_model"), [], 200000, 4.0, 8192, [cheap]),
        ])

        expect:
        catalog.price(new ModelName("dear_model"), USD) == Optional.of(dear)
        catalog.price(new ModelName("cheap_model"), USD) == Optional.of(cheap)
    }

    def "lists each currency any model is priced in once, ordered by its code"() {
        given:
        def catalog = new ModelCatalog(models)

        expect:
        catalog.currencies() == expected

        where:
        models                                                                                 || expected
        [model("sample_model")]                                                                || []
        [model("sample_model", USD)]                                                           || [USD]
        [model("sample_model", USD, EUR), model("other_model", JPY, USD), model("bare_model")] || [EUR, JPY, USD]
    }

    def "hands back a list of currencies that cannot be widened after the fact"() {
        given:
        def catalog = new ModelCatalog([model("sample_model", USD)])

        when:
        catalog.currencies().add(EUR)

        then:
        thrown(UnsupportedOperationException)
        catalog.currencies() == [USD]
    }

    private static DeployedModel model(String name, Currency... pricedIn) {
        new DeployedModel(new ModelName(name), [], 200000, 4.0, 8192,
                pricedIn.collect { new ModelPrice(it, 3.0, 15.0) })
    }
}
