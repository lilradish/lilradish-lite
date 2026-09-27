package org.lilradish.lite.app.run

import static org.lilradish.lite.app.run.WhereKind.FAILED
import static org.lilradish.lite.app.run.WhereKind.HELD_BACK
import static org.lilradish.lite.app.run.WhereKind.OWED_TRY
import static org.lilradish.lite.app.run.WhereKind.RUNNING
import static org.lilradish.lite.app.run.WhereKind.WAITING_ON_REVIEW

import spock.lang.Specification

class WhereKindSpec extends Specification {

    def "each kind of whereabouts is published under the spelling a reader words it by, in this order and no other"() {
        expect:
        WhereKind.values().collect { [it, it.published()] } == [
                [RUNNING, "running"],
                [HELD_BACK, "held_back"],
                [WAITING_ON_REVIEW, "waiting_on_review"],
                [OWED_TRY, "owed_try"],
                [FAILED, "failed"],
        ]
    }
}
