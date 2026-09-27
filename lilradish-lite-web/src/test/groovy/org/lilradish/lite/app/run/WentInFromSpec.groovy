package org.lilradish.lite.app.run

import static org.lilradish.lite.app.run.WentInFrom.NOT_YET_SENT
import static org.lilradish.lite.app.run.WentInFrom.TRY

import spock.lang.Specification

class WentInFromSpec extends Specification {

    def "what a step's inputs are read from is published under the spelling a reader words it by, in this order and no other"() {
        expect:
        WentInFrom.values().collect { [it, it.published()] } == [[TRY, "try"], [NOT_YET_SENT, "not_yet_sent"]]
    }
}
