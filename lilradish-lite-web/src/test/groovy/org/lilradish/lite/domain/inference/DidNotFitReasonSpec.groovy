package org.lilradish.lite.domain.inference

import static org.lilradish.lite.domain.inference.DidNotFitReason.CONFIDENCE_MISSING
import static org.lilradish.lite.domain.inference.DidNotFitReason.CONFIDENCE_NOT_A_PERCENT
import static org.lilradish.lite.domain.inference.DidNotFitReason.CONFIDENCE_UNASKED
import static org.lilradish.lite.domain.inference.DidNotFitReason.CUT_OFF
import static org.lilradish.lite.domain.inference.DidNotFitReason.FIELD_MISSING
import static org.lilradish.lite.domain.inference.DidNotFitReason.FIELD_UNKNOWN
import static org.lilradish.lite.domain.inference.DidNotFitReason.NOTHING_GIVEN
import static org.lilradish.lite.domain.inference.DidNotFitReason.NOT_A_TERM
import static org.lilradish.lite.domain.inference.DidNotFitReason.NOT_ITS_KIND
import static org.lilradish.lite.domain.inference.DidNotFitReason.NOT_KEPT_AS_IT_CAME
import static org.lilradish.lite.domain.inference.DidNotFitReason.NOT_THE_SHAPE
import static org.lilradish.lite.domain.inference.DidNotFitReason.TOO_LONG
import static org.lilradish.lite.domain.inference.DidNotFitReason.TOO_LONG_TO_KEEP
import static org.lilradish.lite.domain.inference.DidNotFitReason.TOO_MANY
import static org.lilradish.lite.domain.inference.DidNotFitReason.UNDECIDED
import static org.lilradish.lite.domain.inference.DidNotFitReason.UNKEEPABLE
import static org.lilradish.lite.domain.inference.DidNotFitReason.WORDS_MISSING
import static org.lilradish.lite.domain.inference.DidNotFitReason.WORDS_TOO_LONG
import static org.lilradish.lite.domain.inference.DidNotFitReason.WORDS_UNKEEPABLE

import spock.lang.Specification

class DidNotFitReasonSpec extends Specification {

    def "each way an answer does not fit is published under the spelling a reader words it by, in this order and no other"() {
        expect:
        DidNotFitReason.values().collect { [it, it.published()] } == [
                [NOT_THE_SHAPE, "not_the_shape"],
                [FIELD_MISSING, "field_missing"],
                [FIELD_UNKNOWN, "field_unknown"],
                [NOTHING_GIVEN, "nothing_given"],
                [NOT_ITS_KIND, "not_its_kind"],
                [TOO_LONG, "too_long"],
                [TOO_MANY, "too_many"],
                [NOT_A_TERM, "not_a_term"],
                [UNKEEPABLE, "unkeepable"],
                [TOO_LONG_TO_KEEP, "too_long_to_keep"],
                [CONFIDENCE_MISSING, "confidence_missing"],
                [CONFIDENCE_UNASKED, "confidence_unasked"],
                [CONFIDENCE_NOT_A_PERCENT, "confidence_not_a_percent"],
                [UNDECIDED, "undecided"],
                [WORDS_MISSING, "words_missing"],
                [WORDS_TOO_LONG, "words_too_long"],
                [WORDS_UNKEEPABLE, "words_unkeepable"],
                [CUT_OFF, "cut_off"],
                [NOT_KEPT_AS_IT_CAME, "not_kept_as_it_came"],
        ]
    }
}
