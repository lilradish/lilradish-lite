package org.lilradish.lite.domain.run

import static org.lilradish.lite.domain.run.RunStepHoldReason.ENTRY_STOPPED
import static org.lilradish.lite.domain.run.RunStepHoldReason.TOO_LONG
import static org.lilradish.lite.domain.run.RunStepHoldReason.TURNED_AWAY
import static org.lilradish.lite.domain.run.fixture.Runs.attemptId
import static org.lilradish.lite.domain.run.fixture.Runs.minutes

import spock.lang.Specification

class HoldRecordSpec extends Specification {

    def "a hold names the attempt it is held for, and is spent up only where that attempt's call was turned away"() {
        when:
        new HoldRecord(reason, minutes(10), named ? attemptId(1) : null, spentUp)

        then:
        noExceptionThrown()

        where:
        reason        | named | spentUp
        ENTRY_STOPPED | false | false
        TOO_LONG      | true  | false
        TURNED_AWAY   | true  | false
        TURNED_AWAY   | true  | true
    }

    def "a hold is refused naming an attempt where it is held for none, or naming none where it is held for one"() {
        when:
        new HoldRecord(reason, minutes(10), named ? attemptId(1) : null, false)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "HoldRecord names an attempt exactly where it is held for one"

        where:
        reason        | named
        ENTRY_STOPPED | true
        TOO_LONG      | false
        TURNED_AWAY   | false
    }

    def "a hold is refused as spent up where no call of its was turned away"() {
        when:
        new HoldRecord(reason, minutes(10), reason == ENTRY_STOPPED ? null : attemptId(1), true)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "HoldRecord is spent up only where its call was turned away"

        where:
        reason << [ENTRY_STOPPED, TOO_LONG]
    }
}
