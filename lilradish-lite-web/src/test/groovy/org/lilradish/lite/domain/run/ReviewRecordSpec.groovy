package org.lilradish.lite.domain.run

import static org.lilradish.lite.domain.run.fixture.Runs.PERSON
import static org.lilradish.lite.domain.run.fixture.Runs.assured
import static org.lilradish.lite.domain.run.fixture.Runs.minutes
import static org.lilradish.lite.domain.run.fixture.Runs.personReview
import static org.lilradish.lite.domain.run.fixture.Runs.refused
import static org.lilradish.lite.domain.run.fixture.Runs.value
import static org.lilradish.lite.domain.run.fixture.Runs.valueId

import org.lilradish.lite.domain.inference.DidNotFitReason
import spock.lang.Specification

class ReviewRecordSpec extends Specification {

    def "a review that went wrong is refused as a person's, or deciding anything"() {
        when:
        new ReviewRecord(byPerson ? PERSON : null, minutes(30), false, TryLostReason.ERRORED, null,
                deciding ? [assured(value(1, "answer", true))] : [])

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "ReviewRecord went wrong only as a model's, deciding nothing"

        where:
        byPerson | deciding
        true     | false
        false    | true
        true     | true
    }

    def "a review says why it did not fit exactly where it did not, and is refused saying so elsewhere or not saying it"() {
        expect:
        refusal { new ReviewRecord(null, minutes(30), false, lost, misfit, []) } == refused

        where:
        lost                            | misfit                    || refused
        TryLostReason.DID_NOT_FIT       | DidNotFitReason.UNDECIDED || null
        TryLostReason.ERRORED           | null                      || null
        TryLostReason.NOTHING_CAME_BACK | null                      || null
        null                            | null                      || null
        TryLostReason.DID_NOT_FIT       | null                      || "ReviewRecord says why it did not fit exactly where it did not"
        TryLostReason.ERRORED           | DidNotFitReason.UNDECIDED || "ReviewRecord says why it did not fit exactly where it did not"
        null                            | DidNotFitReason.CUT_OFF   || "ReviewRecord says why it did not fit exactly where it did not"
    }

    def "a review's decision on a value is the one it made of that value, and it has none of a value it did not decide"() {
        given:
        def first = value(1, "first", true)
        def second = value(2, "second", true)
        def review = personReview(minutes(30), [assured(first), refused(second)])

        expect:
        review.decisionOn(valueId(2)) == Optional.of(refused(second))
        review.decisionOn(valueId(3)) == Optional.empty()
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
