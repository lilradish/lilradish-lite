package org.lilradish.lite.domain.run

import static org.lilradish.lite.domain.run.fixture.Runs.attemptId
import static org.lilradish.lite.domain.run.fixture.Runs.key

import org.lilradish.lite.domain.inference.ModelCallOutcome
import spock.lang.Specification

class CallRecordSpec extends Specification {

    def "a call is spent up only once it ended turned away, and any call may be not spent up"() {
        expect:
        refusal { new CallRecord(new ModelCallId(key(1100)), attemptId(1), outcome, spentUp) } == refused

        where:
        [outcome, spentUp] << [ModelCallOutcome.values().toList() + [null], [false, true]].combinations()
        refused = spentUp && outcome != ModelCallOutcome.TURNED_AWAY
                ? "CallRecord is spent up only once it ended turned away"
                : null
    }

    /** The message it was refused with, none where it was made. */
    private static String refusal(Closure<?> making) {
        try {
            making()
            null
        } catch (IllegalArgumentException refused) {
            refused.message
        }
    }
}
