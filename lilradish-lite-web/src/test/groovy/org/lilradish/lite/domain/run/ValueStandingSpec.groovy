package org.lilradish.lite.domain.run

import static org.lilradish.lite.domain.run.ValueStanding.REFUSED
import static org.lilradish.lite.domain.run.ValueStanding.REFUSED_FOR_LENGTH
import static org.lilradish.lite.domain.run.ValueStanding.STANDS
import static org.lilradish.lite.domain.run.ValueStanding.WAITING_ON_REVIEW
import static org.lilradish.lite.domain.run.fixture.Runs.assured
import static org.lilradish.lite.domain.run.fixture.Runs.lengthReview
import static org.lilradish.lite.domain.run.fixture.Runs.lostReview
import static org.lilradish.lite.domain.run.fixture.Runs.minutes
import static org.lilradish.lite.domain.run.fixture.Runs.modelReview
import static org.lilradish.lite.domain.run.fixture.Runs.personReview
import static org.lilradish.lite.domain.run.fixture.Runs.refused
import static org.lilradish.lite.domain.run.fixture.Runs.value
import static org.lilradish.lite.domain.run.fixture.Runs.yielded

import org.lilradish.lite.domain.workflow.StepProducer
import spock.lang.Specification

class ValueStandingSpec extends Specification {

    def "each standing is published under the spelling a reader words it by, in this order and no other"() {
        expect:
        ValueStanding.values().collect { [it, it.published()] } == [
                [STANDS, "stands"],
                [WAITING_ON_REVIEW, "waiting_on_review"],
                [REFUSED, "refused"],
                [REFUSED_FOR_LENGTH, "refused_for_length"],
        ]
    }

    /**
     * A review for length is never the review that decides values waiting, whatever it says of one; a review that
     * went wrong is the one review the try may have, so what it was to decide can never stand.
     */
    def "a value stands needing no review or assured on review, is refused where a review refused it or went wrong, and waits otherwise"() {
        given:
        def held = value(1, "answer", needsReview)
        def other = value(2, "other", true)
        def reviews = []
        switch (review) {
            case "a person assured it": reviews << personReview(minutes(30), [assured(held), refused(other)]); break
            case "the model assured it": reviews << modelReview(minutes(30), [assured(held)]); break
            case "a person refused it": reviews << personReview(minutes(30), [refused(held), assured(other)]); break
            case "the model refused it": reviews << modelReview(minutes(30), [refused(held)]); break
            case "a review decided only another": reviews << personReview(minutes(30), [assured(other)]); break
            case "the model's review went wrong": reviews << lostReview(minutes(30)); break
            case "only one for length, assuring it": reviews << lengthReview(minutes(30), [assured(held)]); break
            case "only one for length, refusing another": reviews << lengthReview(minutes(30), [refused(other)]); break
        }

        expect:
        ValueStanding.of(yielded(1, StepProducer.MODEL, [held, other], reviews), held) == standing

        where:
        needsReview | review                                  || standing
        false       | "none"                                  || STANDS
        false       | "only one for length, assuring it"      || STANDS
        true        | "a person assured it"                   || STANDS
        true        | "the model assured it"                  || STANDS
        true        | "a person refused it"                   || REFUSED
        true        | "the model refused it"                  || REFUSED
        true        | "the model's review went wrong"         || REFUSED
        false       | "the model's review went wrong"         || STANDS
        true        | "none"                                  || WAITING_ON_REVIEW
        true        | "a review decided only another"         || WAITING_ON_REVIEW
        true        | "only one for length, assuring it"      || WAITING_ON_REVIEW
        true        | "only one for length, refusing another" || WAITING_ON_REVIEW
    }

    def "a value refused for its length is refused for that, whatever was decided of it on review"() {
        given:
        def held = value(1, "answer", needsReview)
        def reviews = [lengthReview(minutes(40), [refused(held)])]
        if (onReview == "assured") {
            reviews << personReview(minutes(30), [assured(held)])
        } else if (onReview == "refused") {
            reviews << personReview(minutes(30), [refused(held)])
        }

        expect:
        ValueStanding.of(yielded(1, StepProducer.PERSON, [held], reviews), held) == REFUSED_FOR_LENGTH

        where:
        needsReview | onReview
        false       | "nothing"
        true        | "nothing"
        true        | "assured"
        true        | "refused"
    }
}
