package org.lilradish.lite.domain.run

import static org.lilradish.lite.domain.run.StepState.DONE
import static org.lilradish.lite.domain.run.StepState.FAILED
import static org.lilradish.lite.domain.run.StepState.HELD_BACK
import static org.lilradish.lite.domain.run.StepState.NOT_STARTED
import static org.lilradish.lite.domain.run.StepState.RUNNING
import static org.lilradish.lite.domain.run.StepState.WAITING

import spock.lang.Specification

class StepStateSpec extends Specification {

    def "each step state is published under the spelling a reader words it by, in this order and no other"() {
        expect:
        StepState.values().collect { [it, it.published()] } == [
                [NOT_STARTED, "not_started"],
                [RUNNING, "running"],
                [HELD_BACK, "held_back"],
                [WAITING, "waiting"],
                [FAILED, "failed"],
                [DONE, "done"],
        ]
    }
}
