package org.lilradish.lite.app.run

import static org.lilradish.lite.app.run.StoppedWhat.ENTRY
import static org.lilradish.lite.app.run.StoppedWhat.WORKFLOW

import spock.lang.Specification

class StoppedWhatSpec extends Specification {

    def "what a stop holding a step is on is published under the spelling a reader words it by, in this order and no other"() {
        expect:
        StoppedWhat.values().collect { [it, it.published()] } == [[WORKFLOW, "workflow"], [ENTRY, "entry"]]
    }
}
