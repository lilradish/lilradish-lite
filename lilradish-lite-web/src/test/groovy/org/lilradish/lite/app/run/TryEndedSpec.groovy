package org.lilradish.lite.app.run

import static org.lilradish.lite.app.run.TryEnded.DID_NOT_FIT
import static org.lilradish.lite.app.run.TryEnded.ERRORED
import static org.lilradish.lite.app.run.TryEnded.NOTHING_CAME_BACK
import static org.lilradish.lite.app.run.TryEnded.OPEN
import static org.lilradish.lite.app.run.TryEnded.REFUSED_FOR_LENGTH
import static org.lilradish.lite.app.run.TryEnded.REFUSED_ON_REVIEW
import static org.lilradish.lite.app.run.TryEnded.STANDS
import static org.lilradish.lite.app.run.TryEnded.WAITING

import org.lilradish.lite.domain.run.TryLostReason
import spock.lang.Specification

class TryEndedSpec extends Specification {

    def "each way a try ended is published under the spelling a reader words it by, in this order and no other"() {
        expect:
        TryEnded.values().collect { [it, it.published()] } == [
                [OPEN, "open"],
                [STANDS, "stands"],
                [REFUSED_ON_REVIEW, "refused_on_review"],
                [REFUSED_FOR_LENGTH, "refused_for_length"],
                [WAITING, "waiting"],
                [DID_NOT_FIT, "did_not_fit"],
                [ERRORED, "errored"],
                [NOTHING_CAME_BACK, "nothing_came_back"],
        ]
    }

    def "a try lost ended as it was lost, spelt as the way it was lost publishes itself"() {
        when:
        def ended = TryEnded.of(lost)

        then:
        ended == expected
        ended.published() == lost.published()

        where:
        lost                            || expected
        TryLostReason.DID_NOT_FIT       || DID_NOT_FIT
        TryLostReason.ERRORED           || ERRORED
        TryLostReason.NOTHING_CAME_BACK || NOTHING_CAME_BACK
    }
}
