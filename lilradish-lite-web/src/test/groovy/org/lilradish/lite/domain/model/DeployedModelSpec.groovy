package org.lilradish.lite.domain.model

import spock.lang.Specification

class DeployedModelSpec extends Specification {

    private static final ModelName NAME = new ModelName("sample_model")
    private static final ModelMode RESEARCH = new ModelMode("research")
    private static final ModelMode DEEP_RESEARCH = new ModelMode("deep_research")
    private static final List<ModelMode> RESEARCH_ONLY = [RESEARCH]
    private static final BigDecimal FOUR = new BigDecimal("4")
    private static final ModelPrice IN_USD = new ModelPrice(Currency.getInstance("USD"), 3.0, 15.0)
    private static final ModelPrice IN_EUR = new ModelPrice(Currency.getInstance("EUR"), 2.8, 14.0)
    private static final ModelPrice IN_USD_AGAIN = new ModelPrice(Currency.getInstance("USD"), 1.0, 1.0)

    def "keeps what it was given, the modes and the prices each in the order listed"() {
        when:
        def model = new DeployedModel(NAME, [RESEARCH, DEEP_RESEARCH], 1, new BigDecimal("3.5"), 8192,
                [IN_EUR, IN_USD])

        then:
        model.name() == NAME
        model.modes() == [RESEARCH, DEEP_RESEARCH]
        model.sentPerCallLimit() == 1
        model.charactersPerUnit() == new BigDecimal("3.5")
        model.cameBackPerCallLimit() == 8192
        model.pricesPerMillion() == [IN_EUR, IN_USD]
    }

    def "keeps a characters-per-unit as written, down to four decimal places however many zeros trail it"() {
        when:
        def model = new DeployedModel(NAME, RESEARCH_ONLY, 200000, new BigDecimal(written), 8192, [])

        then:
        model.charactersPerUnit().toPlainString() == plain
        model.charactersPerUnit().scale() == new BigDecimal(written).scale()

        where:
        written      || plain
        "0.0001"     || "0.0001"
        "4.000000"   || "4.000000"
        "1E+3"       || "1000"
        "12345678.9" || "12345678.9"
    }

    def "detaches its modes and prices from the caller's collections"() {
        given:
        def modes = new ArrayList([RESEARCH])
        def prices = new ArrayList([IN_USD])

        when:
        def model = new DeployedModel(NAME, modes, 200000, FOUR, 8192, prices)
        modes.add(DEEP_RESEARCH)
        prices.add(IN_EUR)

        then:
        model.modes() == [RESEARCH]
        model.pricesPerMillion() == [IN_USD]
    }

    def "hands back modes that cannot be widened after the fact"() {
        given:
        def model = new DeployedModel(NAME, RESEARCH_ONLY, 200000, FOUR, 8192, [])

        when:
        model.modes().add(DEEP_RESEARCH)

        then:
        thrown(UnsupportedOperationException)
        model.modes() == RESEARCH_ONLY
    }

    def "hands back prices that cannot be widened after the fact"() {
        given:
        def model = new DeployedModel(NAME, RESEARCH_ONLY, 200000, FOUR, 8192, [IN_USD])

        when:
        model.pricesPerMillion().add(IN_EUR)

        then:
        thrown(UnsupportedOperationException)
        model.pricesPerMillion() == [IN_USD]
    }

    def "offers no mode beyond running as it is where none is listed, and is held all the same"() {
        when:
        def model = new DeployedModel(NAME, modes, 200000, FOUR, 8192, [IN_USD])

        then:
        model.modes() == []
        model.name() == NAME
        model.pricesPerMillion() == [IN_USD]

        where:
        modes << [null, []]
    }

    def "holds no price where none is listed at all"() {
        when:
        def model = new DeployedModel(NAME, RESEARCH_ONLY, 200000, FOUR, 8192, null)

        then:
        model.pricesPerMillion() == []
        model.modes() == RESEARCH_ONLY
    }

    def "accepts modes held in a list that cannot be asked whether it holds a null"() {
        when:
        def model = new DeployedModel(NAME, List.of(RESEARCH, DEEP_RESEARCH), 200000, FOUR, 8192, [])

        then:
        model.modes() == [RESEARCH, DEEP_RESEARCH]
    }

    def "refuses an entry it cannot hold, naming the model in every refusal that has one to name"() {
        when:
        new DeployedModel(name, modes, sentPerCallLimit, charactersPerUnit, cameBackPerCallLimit, prices)

        then:
        def error = thrown(expectedException)
        error.message == expectedMessage

        where:
        name | modes                                | sentPerCallLimit | charactersPerUnit          | cameBackPerCallLimit | prices                         || expectedException        | expectedMessage
        null | RESEARCH_ONLY                        | 200000           | FOUR                       | 8192                 | []                             || NullPointerException     | "DeployedModel name must not be null"
        NAME | [RESEARCH, null]                     | 200000           | FOUR                       | 8192                 | []                             || NullPointerException     | "DeployedModel sample_model holds a null mode"
        NAME | [RESEARCH, DEEP_RESEARCH, RESEARCH]  | 200000           | FOUR                       | 8192                 | []                             || IllegalArgumentException | "DeployedModel sample_model offers research more than once"
        NAME | RESEARCH_ONLY                        | 0                | FOUR                       | 8192                 | []                             || IllegalArgumentException | "DeployedModel sample_model sent-per-call limit is missing or not positive: 0"
        NAME | RESEARCH_ONLY                        | -1               | FOUR                       | 8192                 | []                             || IllegalArgumentException | "DeployedModel sample_model sent-per-call limit is missing or not positive: -1"
        NAME | RESEARCH_ONLY                        | 200000           | null                       | 8192                 | []                             || NullPointerException     | "DeployedModel sample_model has no characters-per-unit"
        NAME | RESEARCH_ONLY                        | 200000           | new BigDecimal("0.0000")   | 8192                 | []                             || IllegalArgumentException | "DeployedModel sample_model characters-per-unit is not positive: 0.0000"
        NAME | RESEARCH_ONLY                        | 200000           | new BigDecimal("-0.5")     | 8192                 | []                             || IllegalArgumentException | "DeployedModel sample_model characters-per-unit is not positive: -0.5"
        NAME | RESEARCH_ONLY                        | 200000           | new BigDecimal("0.00001")  | 8192                 | []                             || IllegalArgumentException | "DeployedModel sample_model characters-per-unit has more than 4 decimal places: 0.00001"
        NAME | RESEARCH_ONLY                        | 200000           | new BigDecimal("4.000010") | 8192                 | []                             || IllegalArgumentException | "DeployedModel sample_model characters-per-unit has more than 4 decimal places: 4.000010"
        NAME | RESEARCH_ONLY                        | 200000           | FOUR                       | 0                    | []                             || IllegalArgumentException | "DeployedModel sample_model came-back-per-call limit is missing or not positive: 0"
        NAME | RESEARCH_ONLY                        | 200000           | FOUR                       | Long.MIN_VALUE       | []                             || IllegalArgumentException | "DeployedModel sample_model came-back-per-call limit is missing or not positive: -9223372036854775808"
        NAME | RESEARCH_ONLY                        | 200000           | FOUR                       | 8192                 | [IN_USD, null]                 || NullPointerException     | "DeployedModel sample_model holds a null price"
        NAME | RESEARCH_ONLY                        | 200000           | FOUR                       | 8192                 | [IN_USD, IN_EUR, IN_USD_AGAIN] || IllegalArgumentException | "DeployedModel sample_model prices USD more than once"
    }

    /** A ratio that no double holds exactly is where binary arithmetic would round a fitting count up past its limit. */
    def "counts characters as units rounded up, exactly in decimal"() {
        given:
        def model = new DeployedModel(NAME, RESEARCH_ONLY, 200000, new BigDecimal(ratio), 8192, [])

        expect:
        model.unitsOf(characters) == units

        where:
        ratio    | characters                 || units
        "4"      | 0                          || 0
        "4"      | 1                          || 1
        "4"      | 4                          || 1
        "4"      | 5                          || 2
        "4"      | 800000                     || 200000
        "4"      | 800001                     || 200001
        "2.5"    | 10                         || 4
        "2.5"    | 11                         || 5
        "0.1"    | 3                          || 30
        "3.3333" | 9                          || 3
        "3.3333" | 10                         || 4
        "1E+1"   | 11                         || 2
        "0.0001" | 2L * Integer.MAX_VALUE     || 42949672940000
    }

    def "refuses to count a negative number of characters, naming the model"() {
        given:
        def model = new DeployedModel(NAME, RESEARCH_ONLY, 200000, FOUR, 8192, [])

        when:
        model.unitsOf(-1)

        then:
        def error = thrown(IllegalArgumentException)
        error.message == "DeployedModel sample_model cannot count a negative number of characters: -1"
    }

    /** A count held at the most a long holds is past counting; units past what a long holds are held there too. */
    def "counts the units past its limit, none where it fits, and all a long holds past counting"() {
        given:
        def model = new DeployedModel(NAME, RESEARCH_ONLY, sentPerCallLimit, new BigDecimal(ratio), 8192, [])

        expect:
        model.unitsPast(characters) == past

        where:
        sentPerCallLimit | ratio | characters         || past
        4                | "2.5" | 0                  || 0
        4                | "2.5" | 10                 || 0
        4                | "2.5" | 11                 || 1
        30               | "0.1" | 3                  || 0
        30               | "0.1" | 4                  || 10
        200000           | "4"   | 800001             || 1
        200000           | "4"   | Long.MAX_VALUE - 1 || Math.ceilDiv(Long.MAX_VALUE - 1, 4L) - 200000
        200000           | "4"   | Long.MAX_VALUE     || Long.MAX_VALUE
        30               | "0.1" | Long.MAX_VALUE - 1 || Long.MAX_VALUE
    }

    /** At a tenth of a character per unit, binary arithmetic would put the exact fit one unit over. */
    def "takes exactly as many characters as its limit times its characters per unit, and not one more"() {
        given:
        def model = new DeployedModel(NAME, RESEARCH_ONLY, sentPerCallLimit, new BigDecimal(ratio), 8192, [])

        expect:
        model.takes(characters) == taken

        where:
        sentPerCallLimit | ratio | characters || taken
        4                | "2.5" | 0          || true
        4                | "2.5" | 10         || true
        4                | "2.5" | 11         || false
        30               | "0.1" | 3          || true
        30               | "0.1" | 4          || false
        200000           | "4"   | 800000     || true
        200000           | "4"   | 800001     || false
        30               | "0.1" | Long.MAX_VALUE - 1 || false
    }

    def "refuses to judge a negative number of characters, naming the model"() {
        given:
        def model = new DeployedModel(NAME, RESEARCH_ONLY, 200000, FOUR, 8192, [])

        when:
        model."$judgement"(-1)

        then:
        def error = thrown(IllegalArgumentException)
        error.message == "DeployedModel sample_model cannot count a negative number of characters: -1"

        where:
        judgement << ["unitsPast", "takes"]
    }
}
