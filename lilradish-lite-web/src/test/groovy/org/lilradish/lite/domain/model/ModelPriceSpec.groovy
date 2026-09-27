package org.lilradish.lite.domain.model

import spock.lang.Specification

class ModelPriceSpec extends Specification {

    private static final Currency USD = Currency.getInstance("USD")

    def "keeps each price as given, scale included, from nothing upwards in a currency of any minor unit"() {
        when:
        def price = new ModelPrice(Currency.getInstance(code), sent, cameBack)

        then:
        price.currency().currencyCode == code
        price.sentPerMillion() == sent
        price.sentPerMillion().scale() == sent.scale()
        price.cameBackPerMillion() == cameBack
        price.cameBackPerMillion().scale() == cameBack.scale()

        where:
        code  | sent                       | cameBack
        "USD" | new BigDecimal("3.50")     | new BigDecimal("15")
        "JPY" | new BigDecimal("0")        | new BigDecimal("0.000")
        "BHD" | new BigDecimal("0.000001") | new BigDecimal("1234567.123456789")
    }

    def "refuses a code with no minor unit, since no money figure is ever worked out in one, naming it"() {
        when:
        new ModelPrice(Currency.getInstance(code), 1.0, 1.0)

        then:
        def error = thrown(IllegalArgumentException)
        error.message == "ModelPrice currency " + code + " is not one anything is priced in"

        where:
        code << ["XXX", "XTS", "XAU", "XDR"]
    }

    def "refuses a missing or negative price, naming the currency and the price"() {
        when:
        new ModelPrice(currency, sent, cameBack)

        then:
        def error = thrown(expectedException)
        error.message == expectedMessage

        where:
        currency | sent                        | cameBack                || expectedException        | expectedMessage
        null     | 1.0                         | 1.0                     || NullPointerException     | "ModelPrice currency must not be null"
        USD      | null                        | 1.0                     || NullPointerException     | "ModelPrice in USD has no sent price"
        USD      | new BigDecimal("-0.000001") | 1.0                     || IllegalArgumentException | "ModelPrice in USD has a negative sent price: -0.000001"
        USD      | 1.0                         | null                    || NullPointerException     | "ModelPrice in USD has no came-back price"
        USD      | 1.0                         | new BigDecimal("-1E+2") || IllegalArgumentException | "ModelPrice in USD has a negative came-back price: -100"
    }
}
