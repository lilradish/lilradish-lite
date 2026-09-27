package org.lilradish.lite.domain.run

import static org.lilradish.lite.domain.run.WaitsOn.ANSWER_STEP
import static org.lilradish.lite.domain.run.WaitsOn.MODEL
import static org.lilradish.lite.domain.run.WaitsOn.REVIEW_AT_GATE
import static org.lilradish.lite.domain.run.WaitsOn.STARTER

import spock.lang.Specification

class WaitsOnSpec extends Specification {

    def "whom a step waits on is published under the spelling a reader words it by, in this order and no other"() {
        expect:
        WaitsOn.values().collect { [it, it.published()] } == [
                [REVIEW_AT_GATE, "review_at_gate"],
                [MODEL, "model"],
                [ANSWER_STEP, "answer_step"],
                [STARTER, "starter"],
        ]
    }
}
