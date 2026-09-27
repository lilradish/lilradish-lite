package org.lilradish.lite.domain.run

import static org.lilradish.lite.domain.run.fixture.Runs.PERSON
import static org.lilradish.lite.domain.run.fixture.Runs.STARTED
import static org.lilradish.lite.domain.run.fixture.Runs.position

import spock.lang.Specification

class StepPositionSpec extends Specification {

    def "each position reads as the one state it stands for"() {
        expect:
        position(named).state() == state

        where:
        named                                      || state
        "not started"                              || StepState.NOT_STARTED
        "running code"                             || StepState.RUNNING
        "running its next try"                     || StepState.RUNNING
        "held back on a stop"                      || StepState.HELD_BACK
        "held back too long"                       || StepState.HELD_BACK
        "awaiting a person's review"               || StepState.WAITING
        "awaiting the model's review, turned away" || StepState.WAITING
        "asked"                                    || StepState.WAITING
        "owed"                                     || StepState.WAITING
        "failed with its tries spent"              || StepState.FAILED
        "failed as written down"                   || StepState.FAILED
        "done"                                     || StepState.DONE
    }

    /** One rule for the try an answer or a next asking makes, whichever reads it. */
    def "the try a person may make next is the one owed, the one a stop holds, or the one beyond tries spent, and none elsewhere"() {
        expect:
        position(named).nextTry() == expected

        where:
        named                                     || expected
        "asked"                                   || new StepPosition.Owed(1, true, false, STARTED)
        "owed"                                    || new StepPosition.Owed(2, false, false, STARTED)
        "held back on a stop"                     || new StepPosition.Owed(2, false, false, STARTED)
        "failed with its tries spent"             || new StepPosition.Owed(2, false, true, STARTED)
        "held back on a stop before it was asked" || null
        "held back too long"                      || null
        "failed as written down"                  || null
        "awaiting a person's review"              || null
        "running code"                            || null
        "not started"                             || null
        "done"                                    || null
    }

    def "a hold holds a try a person owes only where it is a stop"() {
        when:
        new StepPosition.HeldBack(RunStepHoldReason.TOO_LONG, STARTED, new StepPosition.Owed(1, false, false, STARTED))

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "StepPosition.HeldBack holds a try owed only on a stop"
    }

    def "values awaiting a review are refused naming none"() {
        when:
        new StepPosition.AwaitingReview(1, [], PERSON, STARTED, null)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "StepPosition.AwaitingReview names a value waiting"
    }

    def "a try owed is refused numbered below one"() {
        when:
        new StepPosition.Owed(number, false, false, STARTED)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "StepPosition.Owed number must be positive: " + number

        where:
        number << [0, -1]
    }
}
