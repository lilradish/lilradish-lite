package org.lilradish.lite.domain.model

import spock.lang.Specification

class DeployedToolSpec extends Specification {

    static final ModelToolName SEARCH = new ModelToolName("web_search")

    def "a tool keeps its positive per-call bound and distinct prices"() {
        given:
        def usd = new ToolPrice(Currency.getInstance("USD"), new BigDecimal("0.01"))
        def eur = new ToolPrice(Currency.getInstance("EUR"), new BigDecimal("0.02"))
        def listed = [usd, eur]

        when:
        def tool = new DeployedTool(SEARCH, ModelToolKind.WEB_SEARCH, 3, listed)
        listed.clear()

        then:
        tool.maxUsesPerCall() == 3
        tool.pricesPerUse() == [usd, eur]
        tool.kind() == ModelToolKind.WEB_SEARCH
    }

    def "invalid tool details are refused before a call can use them"() {
        when:
        new DeployedTool(name, kind, maximum, prices)

        then:
        def refused = thrown(expected)
        refused.message.contains(reason)

        where:
        name   | kind                     | maximum | prices || expected                 | reason
        null   | ModelToolKind.WEB_SEARCH | 1       | []     || NullPointerException     | "name must not be null"
        SEARCH | null                     | 1       | []     || NullPointerException     | "has no read-only kind"
        SEARCH | ModelToolKind.WEB_SEARCH | 0       | []     || IllegalArgumentException | "max uses per call must be positive"
        SEARCH | ModelToolKind.WEB_SEARCH | 1       | [new ToolPrice(Currency.getInstance("USD"), BigDecimal.ONE),
                                                       new ToolPrice(Currency.getInstance("USD"), BigDecimal.TEN)] || IllegalArgumentException | "prices USD more than once"
    }

    def "one-use price is nonnegative in a currency that can hold money"() {
        when:
        new ToolPrice(Currency.getInstance("USD"), amount)

        then:
        def refused = thrown(expected)
        refused.message.contains(reason)

        where:
        amount                 || expected                 | reason
        null                   || NullPointerException     | "has no per-use price"
        new BigDecimal("-0.01") || IllegalArgumentException | "negative per-use price"
    }

    def "tool names obey the same exact spelling rule as model names"() {
        when:
        new ModelToolName(value)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message.contains(reason)

        where:
        value        || reason
        "Web_search" || "start with a lowercase letter"
        "web-search" || "only lowercase letters, digits and _"
        "x" * 64     || "must not exceed 63 characters"
    }
}
