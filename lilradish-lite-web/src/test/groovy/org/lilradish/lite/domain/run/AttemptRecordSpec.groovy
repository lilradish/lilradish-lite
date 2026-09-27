package org.lilradish.lite.domain.run

import static org.lilradish.lite.domain.inference.ModelCallPurpose.HELP
import static org.lilradish.lite.domain.inference.ModelCallPurpose.PRODUCE
import static org.lilradish.lite.domain.inference.ModelCallPurpose.REVIEW
import static org.lilradish.lite.domain.run.ReviewUnbuiltReason.LIST_NOT_HERE
import static org.lilradish.lite.domain.run.ReviewUnbuiltReason.NO_LONGER_DECLARED
import static org.lilradish.lite.domain.run.ReviewUnbuiltReason.TAKES_NO_LONGER_DECLARED
import static org.lilradish.lite.domain.run.fixture.Runs.attemptId

import spock.lang.Specification

class AttemptRecordSpec extends Specification {

    def "an attempt sends a try to produce it or to review it, and one for help is refused, no try being sent for it"() {
        expect:
        refusal { new AttemptRecord(attemptId(1), purpose, tooLong, null) } == refused

        where:
        purpose | tooLong || refused
        PRODUCE | false   || null
        PRODUCE | true    || null
        REVIEW  | false   || null
        REVIEW  | true    || null
        HELP    | false   || "AttemptRecord sends a try to produce it or to review it"
        HELP    | true    || "AttemptRecord sends a try to produce it or to review it"
    }

    def "an attempt to review that sent nothing is left unbuilt for any reason, and says which"() {
        when:
        def made = new AttemptRecord(attemptId(1), REVIEW, true, unbuilt)

        then:
        made.unbuilt() == unbuilt
        made.tooLong()
        made.purpose() == REVIEW

        where:
        unbuilt << [LIST_NOT_HERE, TAKES_NO_LONGER_DECLARED, NO_LONGER_DECLARED]
    }

    /** Only what a review would send is built from what a release declares now, and one not built sends nothing. */
    def "an attempt is left unbuilt only to review and with nothing sent, and is otherwise refused, nothing made"() {
        given:
        AttemptRecord made = null

        when:
        made = new AttemptRecord(attemptId(1), purpose, tooLong, unbuilt)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "AttemptRecord is left unbuilt only to review, nothing being sent"
        made == null

        where:
        purpose | tooLong | unbuilt
        REVIEW  | false   | LIST_NOT_HERE
        REVIEW  | false   | TAKES_NO_LONGER_DECLARED
        REVIEW  | false   | NO_LONGER_DECLARED
        PRODUCE | true    | NO_LONGER_DECLARED
        PRODUCE | false   | LIST_NOT_HERE
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
