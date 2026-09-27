package org.lilradish.lite.domain.registry;

/**
 * What is wrong with a place in a version's content, of the things submitting it refuses; a draft may hold
 * any of them. One field's problems are named in the order these are declared.
 *
 * <p>The published spelling is written out rather than folded from the constant name, for the reason
 * {@link org.lilradish.lite.domain.identity.EstateAct} gives.
 */
public enum ContentProblemCode {
    /** A question tells whoever answers it nothing. */
    INSTRUCTION_MISSING("instruction_missing"),
    /** A question gives nothing back, so nothing downstream could name anything it produced. */
    NOTHING_GIVEN_BACK("nothing_given_back"),
    /** A field holds the name an earlier field beside it holds. */
    NAME_REPEATED("name_repeated", true),
    /** A field of text says nothing of how long its value may be. */
    LONGEST_MISSING("longest_missing", true),
    /** A field of terms names no reference list. */
    LIST_MISSING("list_missing", true),
    /** A field holding many says nothing of how many. */
    MOST_MISSING("most_missing", true),
    /** One value of a field, at its limits, could be written out longer than one value may. */
    LIMIT_PAST_LARGEST("limit_past_largest", true),
    /** A field that holds fields holds none. */
    NO_FIELDS_HELD("no_fields_held", true),
    /** A value given back says nothing of what it takes to stand. */
    STANDING_MISSING("standing_missing", true),
    /** A value standing above a confidence says nothing of which. */
    FLOOR_MISSING("floor_missing", true),
    /** A workflow holds no step, so it could give nothing back and act on nothing. */
    NO_STEPS("no_steps"),
    /** A step holds the name an earlier step holds. */
    STEP_NAME_REPEATED("step_name_repeated"),
    /** A step says nothing yet of what it runs. */
    RUNS_MISSING("runs_missing"),
    /** A step, or a case of it, pins a version the group owning the workflow does not hold. */
    PIN_ELSEWHERE("pin_elsewhere"),
    /**
     * A step names a code step not published to the group owning it, or one taking a term from another group's
     * reference list, which is refused alike so nothing of that group is told.
     */
    CODE_STEP_NOT_PUBLISHED("code_step_not_published"),
    /** A step names a code step whose declaration this release does not hold. */
    CODE_STEP_NOT_DECLARED("code_step_not_declared"),
    /** A step names a code step that takes nothing and gives nothing back, so nothing it did could be seen. */
    CODE_STEP_TAKES_AND_GIVES_NOTHING("code_step_takes_and_gives_nothing"),
    /** A step names a code step that takes a term from a reference list version this store does not hold. */
    CODE_STEP_LIST_MISSING("code_step_list_missing"),
    /** A step names a code step that takes a term from a reference list version not yet in service. */
    CODE_STEP_LIST_NOT_YET_IN_SERVICE("code_step_list_not_yet_in_service"),
    /** A step names a code step that takes a term from a reference list version since retired. */
    CODE_STEP_LIST_RETIRED("code_step_list_retired"),
    /** A step running a question or a code step says nothing of who produces its values. */
    PRODUCER_MISSING("producer_missing"),
    /** A step's values are produced by a model this deployment does not hold. */
    PRODUCER_NOT_HELD("producer_not_held"),
    /** A step's values are produced in a mode its model does not offer. */
    PRODUCER_MODE_NOT_OFFERED("producer_mode_not_offered"),
    /** A step that produces says nothing of how many tries it may make. */
    TRIES_MISSING("tries_missing"),
    /** A step's productions are reviewed by a model this deployment does not hold. */
    REVIEWER_NOT_HELD("reviewer_not_held"),
    /** A step's productions are reviewed in a mode its model does not offer. */
    REVIEWER_MODE_NOT_OFFERED("reviewer_mode_not_offered"),
    /** A model is asked by a step that gives nothing back. */
    MODEL_GIVES_NOTHING("model_gives_nothing"),
    /** A route is bound to nothing to choose its case by. */
    DISCRIMINATOR_MISSING("discriminator_missing"),
    /** What a route chooses its case by is not one term. */
    DISCRIMINATOR_NOT_TERM("discriminator_not_term"),
    /** A route has no case for any term. */
    NO_CASES("no_cases"),
    /** A route's case leads to no workflow at a fixed version. */
    CASE_TARGET_MISSING("case_target_missing"),
    /** A route's case is on a term the list it chooses by does not offer. */
    CASE_NOT_OFFERED("case_not_offered"),
    /** A route's case is on the term an earlier case is on. */
    CASE_REPEATED("case_repeated"),
    /** A route's case leads to a workflow giving back other than what the route declares. */
    CASE_GIVES_OTHERWISE("case_gives_otherwise"),
    /** An input is bound to nothing. */
    INPUT_UNBOUND("input_unbound"),
    /** A binding fills an input that what it binds into never declared. */
    TARGET_UNKNOWN("target_unknown"),
    /** A binding fills an input an earlier binding already fills, or part of it. */
    TARGET_BOUND_TWICE("target_bound_twice"),
    /** A binding's pointer passes through a field holding many, where no place is named. */
    POINTER_INTO_MANY("pointer_into_many"),
    /** A binding points at nothing its source declares. */
    SOURCE_UNKNOWN("source_unknown"),
    /** A binding points at a step that is not earlier. */
    SOURCE_NOT_EARLIER("source_not_earlier"),
    /** What a binding points at does not fit what it fills. */
    SOURCE_DOES_NOT_FIT("source_does_not_fit"),
    /** An input that must be given is bound to what need not be, so it may arrive empty. */
    SOURCE_MAY_BE_EMPTY("source_may_be_empty"),
    /** A constant does not fit what it fills. */
    CONSTANT_DOES_NOT_FIT("constant_does_not_fit"),
    /** A constant's text runs past the most one may hold. */
    CONSTANT_TOO_LONG("constant_too_long"),
    /** A constant holds a character that shows nothing yet changes how text is read. */
    CONSTANT_CONCEALS("constant_conceals"),
    /** A value the workflow gives back is bound to nothing. */
    OUTPUT_UNBOUND("output_unbound"),
    /** A value the workflow gives back is bound to other than a step's. */
    OUTPUT_NOT_FROM_STEP("output_not_from_step"),
    /** Runs of the workflow may be helped, and no model is named to help. */
    HELPER_MISSING("helper_missing"),
    /** Runs of the workflow are helped by a model this deployment does not hold. */
    HELPER_NOT_HELD("helper_not_held"),
    /** Runs of the workflow are helped in a mode its model does not offer. */
    HELPER_MODE_NOT_OFFERED("helper_mode_not_offered"),
    /** A reference list holds no term, so nothing could answer with one. */
    NO_TERMS("no_terms"),
    /** A term is alike, whatever the case either is written in, to an earlier term of the same list. */
    TERM_REPEATED("term_repeated"),
    /** One asking could send more than the most one may, and says by how much. */
    ASKING_PAST_LARGEST("asking_past_largest"),
    /** All a workflow takes, as the one value a run is started with, could be written out longer than one may. */
    TAKES_PAST_LARGEST("takes_past_largest"),
    /** A model reviewing what a code step gives back could be sent more than the most one may, and says by how much. */
    CODE_STEP_REVIEW_PAST_LARGEST("code_step_review_past_largest");

    private final String published;

    private final boolean ofOneField;

    ContentProblemCode(String published) {
        this(published, false);
    }

    ContentProblemCode(String published, boolean ofOneField) {
        this.published = published;
        this.ofOneField = ofOneField;
    }

    public String published() {
        return published;
    }

    /** Whether it is a problem of one declared field on its own, found by walking a declaration. */
    public boolean ofOneField() {
        return ofOneField;
    }
}
