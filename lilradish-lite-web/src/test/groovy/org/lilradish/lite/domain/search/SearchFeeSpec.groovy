package org.lilradish.lite.domain.search

import org.lilradish.lite.domain.model.ToolPrice
import spock.lang.Specification

class SearchFeeSpec extends Specification {

    static final Currency USD = Currency.getInstance("USD")

    static final Currency EUR = Currency.getInstance("EUR")

    static final Currency JPY = Currency.getInstance("JPY")

    def "the price in the currency to read in gives the searches counted times it, exactly and at the price's own scale"() {
        when:
        def fee = SearchFee.of(new SearchUses(uses, true), priced(prices), chosen)

        then:
        fee == new SearchFee.Amount(currency, new BigDecimal(perUse), new BigDecimal(amount))

        where:
        prices                             | chosen | uses           || currency | perUse   | amount
        [USD: "0.0035"]                    | null   | 3              || USD      | "0.0035" | "0.0105"
        [USD: "0.0035"]                    | USD    | 3              || USD      | "0.0035" | "0.0105"
        [USD: "0.01"]                      | null   | 2              || USD      | "0.01"   | "0.02"
        [USD: "0.01"]                      | null   | 0              || USD      | "0.01"   | "0.00"
        [USD: "0"]                         | null   | 7              || USD      | "0"      | "0"
        [USD: "0.01", EUR: "0.02"]         | EUR    | 2              || EUR      | "0.02"   | "0.04"
        [EUR: "0.02", USD: "0.01"]         | USD    | 2              || USD      | "0.01"   | "0.02"
        [JPY: "1.5"]                       | JPY    | 3              || JPY      | "1.5"    | "4.5"
        [USD: "0.0035"]                    | null   | Long.MAX_VALUE || USD      | "0.0035" | "32281802128991715.3245"
    }

    def "no amount is given where the group has chosen a currency searching had no price in, naming it, nor is another used instead"() {
        expect:
        SearchFee.of(new SearchUses(2, true), priced(prices), JPY) == new SearchFee.NotInCurrency(JPY)

        where:
        prices << [[USD: "0.01"], [USD: "0.01", EUR: "0.02"]]
    }

    def "no amount is given where searching was priced in more than one currency and the group has chosen none"() {
        expect:
        SearchFee.of(new SearchUses(2, false), priced(prices), null) == new SearchFee.ManyCurrencies()

        where:
        prices << [[USD: "0.01", EUR: "0.02"], [EUR: "0.02", USD: "0.01", JPY: "1"]]
    }

    def "no amount is given where searching had no price at all, whatever the group has chosen"() {
        expect:
        SearchFee.of(new SearchUses(2, true), [], chosen) == new SearchFee.NoPrice()

        where:
        chosen << [null, USD]
    }

    def "a fee worked out of nothing is refused by name"() {
        when:
        SearchFee.of(uses, prices, null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == message

        where:
        uses                     | prices || message
        null                     | []     || "SearchFee uses must not be null"
        new SearchUses(1, true)  | null   || "SearchFee prices must not be null"
    }

    def "an amount missing its currency, its price or itself is refused by name"() {
        when:
        new SearchFee.Amount(currency, perUse, amount)

        then:
        def refused = thrown(NullPointerException)
        refused.message == message

        where:
        currency | perUse                  | amount                  || message
        null     | new BigDecimal("0.01")  | new BigDecimal("0.02")  || "SearchFee amount currency must not be null"
        USD      | null                    | new BigDecimal("0.02")  || "SearchFee amount perUse must not be null"
        USD      | new BigDecimal("0.01")  | null                    || "SearchFee amount must not be null"
    }

    def "no amount for a chosen currency is refused without naming the currency"() {
        when:
        new SearchFee.NotInCurrency(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "SearchFee chosen must not be null"
    }

    /** Each price by its currency's code, in the order written. */
    private static List<ToolPrice> priced(Map<String, String> prices) {
        prices.collect { code, perUse -> new ToolPrice(Currency.getInstance(code), new BigDecimal(perUse)) }
    }
}
