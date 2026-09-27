package org.lilradish.lite.domain.run

import static org.lilradish.lite.domain.run.ReviewOutcome.ASSURED
import static org.lilradish.lite.domain.run.ReviewOutcome.REFUSED

import spock.lang.Specification

class ReviewOutcomeSpec extends Specification {

    def "each outcome of a review is published under the spelling a reader words it by, in this order and no other"() {
        expect:
        ReviewOutcome.values().collect { [it, it.published()] } == [[ASSURED, "assured"], [REFUSED, "refused"]]
    }
}
