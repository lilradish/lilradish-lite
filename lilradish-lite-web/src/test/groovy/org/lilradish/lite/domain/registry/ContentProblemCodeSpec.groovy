package org.lilradish.lite.domain.registry

import spock.lang.Specification

class ContentProblemCodeSpec extends Specification {

    /**
     * Pinned whole and in order: a field's problems are named in the order these are declared, and a page
     * words each by its spelling, so a code renamed or moved is a change to both.
     */
    def "each problem is published under the spelling a reader words it by, in the order they are named"() {
        expect:
        ContentProblemCode.values().collect { [it, it.published()] } == [
                [ContentProblemCode.INSTRUCTION_MISSING, "instruction_missing"],
                [ContentProblemCode.NOTHING_GIVEN_BACK, "nothing_given_back"],
                [ContentProblemCode.NAME_REPEATED, "name_repeated"],
                [ContentProblemCode.LONGEST_MISSING, "longest_missing"],
                [ContentProblemCode.LIST_MISSING, "list_missing"],
                [ContentProblemCode.MOST_MISSING, "most_missing"],
                [ContentProblemCode.LIMIT_PAST_LARGEST, "limit_past_largest"],
                [ContentProblemCode.NO_FIELDS_HELD, "no_fields_held"],
                [ContentProblemCode.STANDING_MISSING, "standing_missing"],
                [ContentProblemCode.FLOOR_MISSING, "floor_missing"],
                [ContentProblemCode.NO_STEPS, "no_steps"],
                [ContentProblemCode.STEP_NAME_REPEATED, "step_name_repeated"],
                [ContentProblemCode.RUNS_MISSING, "runs_missing"],
                [ContentProblemCode.PIN_ELSEWHERE, "pin_elsewhere"],
                [ContentProblemCode.CODE_STEP_NOT_PUBLISHED, "code_step_not_published"],
                [ContentProblemCode.CODE_STEP_NOT_DECLARED, "code_step_not_declared"],
                [ContentProblemCode.CODE_STEP_TAKES_AND_GIVES_NOTHING, "code_step_takes_and_gives_nothing"],
                [ContentProblemCode.CODE_STEP_LIST_MISSING, "code_step_list_missing"],
                [ContentProblemCode.CODE_STEP_LIST_NOT_YET_IN_SERVICE, "code_step_list_not_yet_in_service"],
                [ContentProblemCode.CODE_STEP_LIST_RETIRED, "code_step_list_retired"],
                [ContentProblemCode.PRODUCER_MISSING, "producer_missing"],
                [ContentProblemCode.PRODUCER_NOT_HELD, "producer_not_held"],
                [ContentProblemCode.PRODUCER_MODE_NOT_OFFERED, "producer_mode_not_offered"],
                [ContentProblemCode.TRIES_MISSING, "tries_missing"],
                [ContentProblemCode.REVIEWER_NOT_HELD, "reviewer_not_held"],
                [ContentProblemCode.REVIEWER_MODE_NOT_OFFERED, "reviewer_mode_not_offered"],
                [ContentProblemCode.MODEL_GIVES_NOTHING, "model_gives_nothing"],
                [ContentProblemCode.DISCRIMINATOR_MISSING, "discriminator_missing"],
                [ContentProblemCode.DISCRIMINATOR_NOT_TERM, "discriminator_not_term"],
                [ContentProblemCode.NO_CASES, "no_cases"],
                [ContentProblemCode.CASE_TARGET_MISSING, "case_target_missing"],
                [ContentProblemCode.CASE_NOT_OFFERED, "case_not_offered"],
                [ContentProblemCode.CASE_REPEATED, "case_repeated"],
                [ContentProblemCode.CASE_GIVES_OTHERWISE, "case_gives_otherwise"],
                [ContentProblemCode.INPUT_UNBOUND, "input_unbound"],
                [ContentProblemCode.TARGET_UNKNOWN, "target_unknown"],
                [ContentProblemCode.TARGET_BOUND_TWICE, "target_bound_twice"],
                [ContentProblemCode.POINTER_INTO_MANY, "pointer_into_many"],
                [ContentProblemCode.SOURCE_UNKNOWN, "source_unknown"],
                [ContentProblemCode.SOURCE_NOT_EARLIER, "source_not_earlier"],
                [ContentProblemCode.SOURCE_DOES_NOT_FIT, "source_does_not_fit"],
                [ContentProblemCode.SOURCE_MAY_BE_EMPTY, "source_may_be_empty"],
                [ContentProblemCode.CONSTANT_DOES_NOT_FIT, "constant_does_not_fit"],
                [ContentProblemCode.CONSTANT_TOO_LONG, "constant_too_long"],
                [ContentProblemCode.CONSTANT_CONCEALS, "constant_conceals"],
                [ContentProblemCode.OUTPUT_UNBOUND, "output_unbound"],
                [ContentProblemCode.OUTPUT_NOT_FROM_STEP, "output_not_from_step"],
                [ContentProblemCode.HELPER_MISSING, "helper_missing"],
                [ContentProblemCode.HELPER_NOT_HELD, "helper_not_held"],
                [ContentProblemCode.HELPER_MODE_NOT_OFFERED, "helper_mode_not_offered"],
                [ContentProblemCode.NO_TERMS, "no_terms"],
                [ContentProblemCode.TERM_REPEATED, "term_repeated"],
                [ContentProblemCode.ASKING_PAST_LARGEST, "asking_past_largest"],
                [ContentProblemCode.TAKES_PAST_LARGEST, "takes_past_largest"],
                [ContentProblemCode.CODE_STEP_REVIEW_PAST_LARGEST, "code_step_review_past_largest"],
        ]
    }

    /** Only these are found by walking a declaration's fields; every other is found where it stands. */
    def "a problem of one declared field on its own is so marked, and no other is"() {
        expect:
        ContentProblemCode.values().findAll { it.ofOneField() } == [
                ContentProblemCode.NAME_REPEATED,
                ContentProblemCode.LONGEST_MISSING,
                ContentProblemCode.LIST_MISSING,
                ContentProblemCode.MOST_MISSING,
                ContentProblemCode.LIMIT_PAST_LARGEST,
                ContentProblemCode.NO_FIELDS_HELD,
                ContentProblemCode.STANDING_MISSING,
                ContentProblemCode.FLOOR_MISSING,
        ]
    }
}
