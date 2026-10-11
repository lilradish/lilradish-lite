package com.lilradish.lite.domain.run

import static com.lilradish.lite.domain.run.ReviewOutcome.ASSURED
import static com.lilradish.lite.domain.run.ReviewOutcome.REFUSED
import static com.lilradish.lite.domain.run.fixture.Runs.valueId

import spock.lang.Specification

class DecisionRecordSpec extends Specification {

    def "a decision says why where it refused, and nothing where it assured"() {
        when:
        new DecisionRecord(valueId(1), outcome, why, null, null)

        then:
        noExceptionThrown()

        where:
        outcome | why
        ASSURED | null
        REFUSED | "Not what was asked."
    }

    def "a decision is refused saying why where it assured, or saying nothing where it refused"() {
        when:
        new DecisionRecord(valueId(1), outcome, why, null, null)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "DecisionRecord says why exactly where it refused"

        where:
        outcome | why
        ASSURED | "Looks right."
        REFUSED | null
    }

    def "a length refusal keeps its historical contribution and excess without an assurance"() {
        when:
        def decision = new DecisionRecord(valueId(1), REFUSED, "Made again, shorter.", length, excess)

        then:
        decision.lengthUnits() == length
        decision.excessUnits() == excess
        decision.outcome() != ASSURED
        decision.value() == valueId(1)

        where:
        length         | excess
        0L             | 1L
        Long.MAX_VALUE | Long.MAX_VALUE
    }

    def "length quantities cannot be half present, negative, zero excess or an assurance"() {
        when:
        new DecisionRecord(valueId(1), outcome, outcome == REFUSED ? "Made again, shorter." : null, length, excess)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == message

        where:
        outcome | length | excess || message
        REFUSED | 10L    | null   || "A length refusal keeps both quantities together"
        REFUSED | null   | 1L     || "A length refusal keeps both quantities together"
        REFUSED | -1L    | 1L     || "A length refusal keeps a nonnegative contribution and positive excess"
        REFUSED | 10L    | 0L     || "A length refusal keeps a nonnegative contribution and positive excess"
        ASSURED | 10L    | 1L     || "A length refusal keeps a nonnegative contribution and positive excess"
    }
}
