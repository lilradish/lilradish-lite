package org.lilradish.lite.domain.run

import static org.lilradish.lite.domain.run.RunState.DONE
import static org.lilradish.lite.domain.run.RunState.FAILED
import static org.lilradish.lite.domain.run.RunState.RUNNING
import static org.lilradish.lite.domain.run.RunState.STOPPED

import spock.lang.Specification

class RunStateSpec extends Specification {

    /** A reader words a run's state off these, so a constant renamed without its spelling staying put reads as unknown. */
    def "each state is published under the spelling a reader words it by, in this order and no other"() {
        expect:
        RunState.values().collect { [it, it.published()] } == [
                [RUNNING, "running"],
                [STOPPED, "stopped"],
                [FAILED, "failed"],
                [DONE, "done"],
        ]
    }
}
