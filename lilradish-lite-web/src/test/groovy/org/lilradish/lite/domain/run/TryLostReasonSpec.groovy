package org.lilradish.lite.domain.run

import static org.lilradish.lite.domain.run.TryLostReason.DID_NOT_FIT
import static org.lilradish.lite.domain.run.TryLostReason.ERRORED
import static org.lilradish.lite.domain.run.TryLostReason.NOTHING_CAME_BACK

import spock.lang.Specification

class TryLostReasonSpec extends Specification {

    def "each way a try is lost is published under the spelling a reader words it by, in this order and no other"() {
        expect:
        TryLostReason.values().collect { [it, it.published()] } == [
                [DID_NOT_FIT, "did_not_fit"],
                [NOTHING_CAME_BACK, "nothing_came_back"],
                [ERRORED, "errored"],
        ]
    }
}
