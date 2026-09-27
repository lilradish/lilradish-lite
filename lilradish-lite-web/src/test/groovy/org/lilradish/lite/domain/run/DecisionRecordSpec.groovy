package org.lilradish.lite.domain.run

import static org.lilradish.lite.domain.run.ReviewOutcome.ASSURED
import static org.lilradish.lite.domain.run.ReviewOutcome.REFUSED
import static org.lilradish.lite.domain.run.fixture.Runs.valueId

import spock.lang.Specification

class DecisionRecordSpec extends Specification {

    def "a decision says why where it refused, and nothing where it assured"() {
        when:
        new DecisionRecord(valueId(1), outcome, why)

        then:
        noExceptionThrown()

        where:
        outcome | why
        ASSURED | null
        REFUSED | "Not what was asked."
    }

    def "a decision is refused saying why where it assured, or saying nothing where it refused"() {
        when:
        new DecisionRecord(valueId(1), outcome, why)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "DecisionRecord says why exactly where it refused"

        where:
        outcome | why
        ASSURED | "Looks right."
        REFUSED | null
    }
}
