package org.lilradish.lite.domain.run

import static org.lilradish.lite.domain.run.RunStepHoldReason.CODE_STEP_NOT_HELD
import static org.lilradish.lite.domain.run.RunStepHoldReason.ENTRY_STOPPED
import static org.lilradish.lite.domain.run.RunStepHoldReason.TOO_LONG
import static org.lilradish.lite.domain.run.RunStepHoldReason.TURNED_AWAY

import spock.lang.Specification

class RunStepHoldReasonSpec extends Specification {

    def "each reason a step is held back is published under the spelling a reader words it by, in this order and no other"() {
        expect:
        RunStepHoldReason.values().collect { [it, it.published()] } == [
                [ENTRY_STOPPED, "entry_stopped"],
                [TOO_LONG, "too_long"],
                [TURNED_AWAY, "turned_away"],
                [CODE_STEP_NOT_HELD, "code_step_not_held"],
        ]
    }
}
