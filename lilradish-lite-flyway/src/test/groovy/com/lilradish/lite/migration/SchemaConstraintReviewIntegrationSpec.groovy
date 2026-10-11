package com.lilradish.lite.migration

import static com.lilradish.lite.migration.fixture.ProductionAndCallCases.*
import static com.lilradish.lite.migration.fixture.SchemaKeys.*
import static com.lilradish.lite.migration.fixture.SchemaRows.*

import com.lilradish.lite.migration.fixture.SchemaConstraintSpecification

/** What a review, its decisions and a refusal for length are held to. */
class SchemaConstraintReviewIntegrationSpec extends SchemaConstraintSpecification {

    def "a person reviews where no model is named to, the model named where one is, and a person refuses for length either way"() {
        when:
        def refusedBy = attempt("reviews", first + insertInto("reviews", aReviewOf(production) + [
                created_by: literal(author), created_by_kind: literal(kind), model_call_id: literal(call),
                model_call_outcome: literal(outcome), run_step_hold_id: literal(hold), hold_run_id: literal(holdRun)]))

        then:
        refusedBy == refusal

        where:
        first                  | production      | author          | kind     | call        | outcome     | hold | holdRun || refusal
        DROP_THE_ANSWER_REVIEW | ANSWERED        | STEWARD         | "person" | null        | null        | null | null    || null
        ""                     | ANSWERED        | STEWARD         | "person" | null        | null        | HOLD | RUN     || null
        ""                     | REVIEWED_ANSWER | STEWARD         | "person" | null        | null        | HOLD | RUN     || null
        DROP_THE_MODEL_REVIEW  | REVIEWED_ANSWER | WORKFLOW_RUNNER | "system" | REVIEW_CALL | "came_back" | null | null    || null
        ""                     | REVIEWED_ANSWER | STEWARD         | "person" | null        | null        | null | null    || "reviews_author_is_system_exactly_for_model_on_review"
        ""                     | REVIEWED_ANSWER | WORKFLOW_RUNNER | "system" | REVIEW_CALL | "came_back" | HOLD | RUN     || "reviews_author_is_system_exactly_for_model_on_review"
        ""                     | ANSWERED        | WORKFLOW_RUNNER | "system" | REVIEW_CALL | "came_back" | null | null    || "reviews_author_is_system_exactly_for_model_on_review"
    }

    /** What the model would be sent being too long is no hold on the step: the production waits on a person instead. */
    def "a person reviews on a step a model reviews only in its place, where what it would be sent was too long"() {
        when:
        def refusedBy = attempt("reviews", insertInto("reviews", aReviewOf(LONG_ANSWER) + [
                created_by: literal(author), created_by_kind: literal(kind), model_call_id: literal(call),
                model_call_outcome: literal(outcome), too_long_attempt_id: literal(tooLong)]))

        then:
        refusedBy == refusal

        where:
        author          | kind     | call        | outcome     | tooLong             || refusal
        STEWARD         | "person" | null        | null        | HELD_REVIEW_ATTEMPT || null
        STEWARD         | "person" | null        | null        | null                || "reviews_author_is_system_exactly_for_model_on_review"
        WORKFLOW_RUNNER | "system" | REVIEW_CALL | "came_back" | HELD_REVIEW_ATTEMPT || "reviews_author_is_system_exactly_for_model_on_review"
    }

    def "a review in the model's place names an attempt to review that production which was too long to send"() {
        when:
        def refusedBy = attempt("reviews", first + insertInto("reviews", aReviewOf(production) + [
                too_long_attempt_id: literal(tooLong)]))

        then:
        refusedBy == refusal

        where:
        first                  | production      | tooLong             || refusal
        ""                     | LONG_ANSWER     | HELD_REVIEW_ATTEMPT || null
        DROP_THE_MODEL_REVIEW  | REVIEWED_ANSWER | HELD_REVIEW_ATTEMPT || "reviews_too_long_attempt_fk"
        DROP_THE_MODEL_REVIEW  | REVIEWED_ANSWER | REVIEW_ATTEMPT      || "reviews_too_long_attempt_fk"
        DROP_THE_MODEL_REVIEW  | REVIEWED_ANSWER | LONG_ATTEMPT        || "reviews_too_long_attempt_fk"
        DROP_THE_ANSWER_REVIEW | ANSWERED        | HELD_REVIEW_ATTEMPT || "reviews_too_long_attempt_fk"
    }

    /**
     * A review that did not fit, went wrong or never came back spent the try as one that was taken would have. The
     * rows refused here name a call no row holds, which the unique index refuses before any key is looked up.
     */
    def "a production is reviewed once, whoever reviewed it and whatever became of that, and refused for length beside"() {
        when:
        def refusedBy = attempt("reviews", first + insertInto("reviews", aReviewOf(production) + [
                created_by: literal(author), created_by_kind: literal(kind), model_call_id: literal(call),
                model_call_outcome: literal(outcome), run_step_hold_id: literal(hold), hold_run_id: literal(holdRun),
                too_long_attempt_id: literal(tooLong)]))

        then:
        refusedBy == refusal

        where:
        first                       | production      | author          | kind     | call        | outcome     | hold | holdRun | tooLong             || refusal
        DROP_THE_MODEL_REVIEW       | REVIEWED_ANSWER | WORKFLOW_RUNNER | "system" | REVIEW_CALL | "came_back" | null | null    | null                || null
        ""                          | REVIEWED_ANSWER | STEWARD         | "person" | null        | null        | HOLD | RUN     | null                || null
        ""                          | LONG_ANSWER     | STEWARD         | "person" | null        | null        | null | null    | HELD_REVIEW_ATTEMPT || null
        ""                          | REVIEWED_ANSWER | WORKFLOW_RUNNER | "system" | ABSENT      | "came_back" | null | null    | null                || "reviews_one_on_review"
        THE_MODEL_REVIEW_WENT_WRONG | REVIEWED_ANSWER | WORKFLOW_RUNNER | "system" | ABSENT      | "came_back" | null | null    | null                || "reviews_one_on_review"
        STEWARD_IN_THE_MODELS_PLACE | LONG_ANSWER     | WORKFLOW_RUNNER | "system" | ABSENT      | "came_back" | null | null    | null                || "reviews_one_on_review"
    }

    def "a person never reviews what they produced, and a model may review what it produced itself"() {
        when:
        def refusedBy = attempt("reviews", first + insertInto("reviews", aReviewOf(production) + [
                created_by: literal(author), created_by_kind: literal(kind), model_call_id: literal(call),
                model_call_outcome: literal(outcome), run_step_hold_id: literal(hold), hold_run_id: literal(holdRun),
                too_long_attempt_id: literal(tooLong)]))

        then:
        refusedBy == refusal

        where:
        first                       | production       | author          | kind     | call              | outcome     | hold | holdRun | tooLong             || refusal
        ""                          | CODE_PRODUCTION  | MEMBER          | "person" | null              | null        | null | null    | null                || null
        ""                          | ANSWERED         | MEMBER          | "person" | null              | null        | HOLD | RUN     | null                || null
        aModelTryAndItsReviewCall() | SPARE_PRODUCTION | WORKFLOW_RUNNER | "system" | SPARE_REVIEW_CALL | "came_back" | null | null    | null                || null
        ""                          | ANSWERED         | MEMBER          | "person" | null              | null        | null | null    | null                || "reviews_reviewer_is_not_producer"
        ""                          | LONG_ANSWER      | MEMBER          | "person" | null              | null        | null | null    | HELD_REVIEW_ATTEMPT || "reviews_reviewer_is_not_producer"
    }

    /** A refusal that says nothing of why came back and did not fit; only a call that went wrong makes a review so. */
    def "a model's review ends as its call did, and one that came back may still not fit"() {
        when:
        def refusedBy = attempt("reviews", first + insertInto("reviews",
                A_MODEL_REVIEW + [model_call_outcome: literal(outcome), lost_reason: literal(lost),
                                  did_not_fit_reason: literal(lost == "did_not_fit" ? "undecided" : null)]))

        then:
        refusedBy == refusal

        where:
        first                      | outcome     | lost                || refusal
        DROP_THE_MODEL_REVIEW      | "came_back" | null                || null
        DROP_THE_MODEL_REVIEW      | "came_back" | "did_not_fit"       || null
        THE_REVIEW_CALL_WENT_WRONG | "errored"   | "errored"           || null
        DROP_THE_MODEL_REVIEW      | "came_back" | "errored"           || "reviews_lost_as_call_ended"
        DROP_THE_MODEL_REVIEW      | "came_back" | "nothing_came_back" || "reviews_lost_as_call_ended"
        THE_REVIEW_CALL_WENT_WRONG | "errored"   | "did_not_fit"       || "reviews_lost_as_call_ended"
        THE_REVIEW_CALL_WENT_WRONG | "errored"   | null                || "reviews_lost_as_call_ended"
    }

    def "a model's review whose answer is not kept as it came back ends as one that did not fit"() {
        when:
        def refusedBy = attempt("reviews", DROP_THE_MODEL_REVIEW +
                "update app.model_calls set answer_altered = ${altered} where model_call_id = '${REVIEW_CALL}'; " +
                insertInto("reviews", A_MODEL_REVIEW + [call_answer_altered: carried, lost_reason: literal(lost),
                                                        did_not_fit_reason: literal(reason)]))

        then:
        refusedBy == refusal

        where:
        altered | carried | lost          | reason                || refusal
        "true"  | "true"  | "did_not_fit" | "not_kept_as_it_came" || null
        "true"  | "true"  | null          | null                  || "reviews_altered_answer_did_not_fit"
        "true"  | "false" | "did_not_fit" | "undecided"           || "reviews_model_call_fk"
        "false" | "true"  | "did_not_fit" | "not_kept_as_it_came" || "reviews_model_call_fk"
    }

    /** As a try's is: found once as what came back was read, and never read again from it. */
    def "a model's review says why it did not fit exactly where it did not, and that it was not kept only where it was not"() {
        when:
        def refusedBy = attempt("reviews", first +
                "update app.model_calls set answer_altered = ${altered} where model_call_id = '${REVIEW_CALL}'; " +
                insertInto("reviews", A_MODEL_REVIEW + [model_call_outcome: literal(outcome), call_answer_altered: altered,
                                                        lost_reason: literal(lost), did_not_fit_reason: literal(reason)]))

        then:
        refusedBy == refusal

        where:
        first                      | outcome     | altered | lost          | reason                || refusal
        DROP_THE_MODEL_REVIEW      | "came_back" | "false" | "did_not_fit" | "undecided"           || null
        DROP_THE_MODEL_REVIEW      | "came_back" | "true"  | "did_not_fit" | "words_missing"       || null
        DROP_THE_MODEL_REVIEW      | "came_back" | "true"  | "did_not_fit" | "not_kept_as_it_came" || null
        DROP_THE_MODEL_REVIEW      | "came_back" | "false" | "did_not_fit" | null                  || "reviews_did_not_fit_reason_exactly_for_did_not_fit"
        DROP_THE_MODEL_REVIEW      | "came_back" | "false" | null          | "undecided"           || "reviews_did_not_fit_reason_exactly_for_did_not_fit"
        THE_REVIEW_CALL_WENT_WRONG | "errored"   | "false" | "errored"     | "undecided"           || "reviews_did_not_fit_reason_exactly_for_did_not_fit"
        DROP_THE_MODEL_REVIEW      | "came_back" | "false" | "did_not_fit" | "not_kept_as_it_came" || "reviews_not_kept_only_when_altered"
    }

    def "a review is of a try that gave something back"() {
        when:
        def refusedBy = attempt("reviews", insertInto("reviews", aReviewOf(production)))

        then:
        refusedBy == refusal

        where:
        production      || refusal
        CODE_PRODUCTION || null
        CODE_FAILED     || "reviews_production_fk"
    }

    /** The hold may be in a run beneath the one that made the value, which is where a value started with is carried. */
    def "a refusal for length names a hold in the tree of the production it refuses"() {
        when:
        def refusedBy = attempt("reviews", first + insertInto("reviews", aReviewOf(production) + [
                run_step_hold_id: literal(hold), hold_run_id: literal(holdRun)]))

        then:
        refusedBy == refusal

        where:
        first                 | production      | hold       | holdRun || refusal
        ""                    | ANSWERED        | HOLD       | RUN     || null
        HOLD_THE_STEP_BENEATH | ANSWERED        | SPARE_HOLD | SUB_RUN || null
        HOLD_THE_STEP_BENEATH | CODE_PRODUCTION | SPARE_HOLD | SUB_RUN || "reviews_hold_run_fk"
        ""                    | ANSWERED        | HOLD       | SUB_RUN || "reviews_hold_fk"
    }

    def "a refusal for length names a hold on length and the run it is in, or neither"() {
        when:
        def refusedBy = attempt("reviews", DROP_THE_ANSWER_REVIEW + insertInto("reviews", aReviewOf(ANSWERED) + [
                run_step_hold_id: literal(hold), hold_run_id: literal(holdRun), hold_reason: literal(reason)]))

        then:
        refusedBy == refusal

        where:
        hold | holdRun | reason        || refusal
        null | null    | "too_long"    || null
        HOLD | RUN     | "too_long"    || null
        HOLD | null    | "too_long"    || "reviews_hold_together"
        null | RUN     | "too_long"    || "reviews_hold_together"
        HOLD | RUN     | "turned_away" || "reviews_hold_is_too_long"
    }

    def "a value is decided on review only where it needs a review, and refused whatever it needed where it is for length"() {
        when:
        def refusedBy = attempt("review_decisions", first + insertInto("review_decisions", A_DECISION + [
                review_id: literal(review), production_value_id: literal(value), production_id: literal(production),
                for_length: forLength, value_needs_review: needs, outcome: literal(outcome),
                length_units: forLength == "true" ? "10" : "null",
                excess_units: forLength == "true" ? "1" : "null",
                explanation: literal(explanation), assured_in_review_id: "null"]))

        then:
        refusedBy == refusal

        where:
        first                        | review       | value          | production | forLength | needs   | outcome   | explanation         || refusal
        REVIEW_THE_ANSWER_AGAIN      | SPARE_REVIEW | ANSWERED_VALUE | ANSWERED   | "false"   | "true"  | "assured" | null                || null
        REVIEW_THE_ANSWER_AGAIN      | SPARE_REVIEW | ANSWERED_VALUE | ANSWERED   | "false"   | "true"  | "refused" | "Too long to send." || null
        REFUSE_PRODUCTION_FOR_LENGTH | SPARE_REVIEW | SURE_VALUE     | PRODUCTION | "true"    | "false" | "refused" | "Too long to send." || null
        ""                           | REVIEW       | SURE_VALUE     | PRODUCTION | "false"   | "false" | "assured" | null                || "review_decisions_on_review_only_for_needing_review"
        REFUSE_PRODUCTION_FOR_LENGTH | SPARE_REVIEW | SURE_VALUE     | PRODUCTION | "true"    | "false" | "assured" | null                || "review_decisions_for_length_only_for_refused"
    }

    def "a length refusal keeps exact bigint quantities while its ordinary review has neither"() {
        given:
        def before = contentsOf("review_decisions")

        when:
        // DB-SPECIFIC: PostgreSQL text casts retain bigint quantities without rounding.
        def kept = readInside(insertInto("review_decisions", A_DECISION + [length_units: length, excess_units: excess]),
                "select cast(length_units as text) || ':' || cast(excess_units as text) " +
                "from app.review_decisions where review_id = '${LENGTH_REVIEW}'")

        then:
        kept == length + ":" + excess
        contentsOf("review_decisions") == before
        session.autoCommit

        where:
        length                | excess
        "0"                   | "1"
        "9223372036854775807" | "9223372036854775807"
    }

    def "length quantities are absent together on review and never admitted there independently"() {
        when:
        def refusedBy = attempt("review_decisions", REVIEW_THE_ANSWER_AGAIN + insertInto("review_decisions",
                A_DECISION + ON_REVIEW + [review_id: literal(SPARE_REVIEW), length_units: length, excess_units: excess]))

        then:
        refusedBy == refusal

        where:
        length | excess || refusal
        "null" | "null" || null
        "null" | "1"    || "review_decisions_length_quantities_together"
        "10"   | "1"    || "review_decisions_length_quantities_exactly_for_length"
    }

    def "only a refusal says why"() {
        when:
        def refusedBy = attempt("review_decisions", REVIEW_THE_ANSWER_AGAIN + insertInto("review_decisions", A_DECISION +
                ON_REVIEW + [review_id: literal(SPARE_REVIEW), outcome: literal(outcome), explanation: literal(explanation)]))

        then:
        refusedBy == refusal

        where:
        outcome   | explanation || refusal
        "assured" | null        || null
        "refused" | "No order." || null
        "assured" | "Fine."     || "review_decisions_explanation_exactly_for_refused"
        "refused" | null        || "review_decisions_explanation_exactly_for_refused"
    }

    def "a value decided is one of the production reviewed, needing a review or not as it does, by a review that decided"() {
        when:
        def refusedBy = attempt("review_decisions", first + insertInto("review_decisions", A_DECISION + ON_REVIEW + [
                review_id: literal(review), production_value_id: literal(value), production_id: literal(production),
                for_length: forLength, value_needs_review: needs]))

        then:
        refusedBy == refusal

        where:
        first                                              | review       | value          | production      | forLength | needs  || refusal
        A_VALUE_OF_REVIEWED_ANSWER + REVIEWED_BY_THE_MODEL | SPARE_REVIEW | SPARE_VALUE    | REVIEWED_ANSWER | "false"   | "true" || null
        A_VALUE_OF_REVIEWED_ANSWER                         | MODEL_REVIEW | SPARE_VALUE    | REVIEWED_ANSWER | "false"   | "true" || "review_decisions_review_fk"
        ""                                                 | REVIEW       | SURE_VALUE     | PRODUCTION      | "false"   | "true" || "review_decisions_value_fk"
    }

    def "a value that needed a review is refused for length only once a review has assured it"() {
        when:
        def refusedBy = attempt("review_decisions", first + insertInto("review_decisions", A_DECISION + [
                review_id: literal(review), production_value_id: literal(value), production_id: literal(production),
                value_needs_review: needs, assured_in_review_id: literal(assuredIn)]))

        then:
        refusedBy == refusal

        where:
        first                        | review        | value          | production | needs   | assuredIn     || refusal
        ""                           | LENGTH_REVIEW | ANSWERED_VALUE | ANSWERED   | "true"  | ANSWER_REVIEW || null
        REFUSE_PRODUCTION_FOR_LENGTH | SPARE_REVIEW  | SURE_VALUE     | PRODUCTION | "false" | null          || null
        ""                           | LENGTH_REVIEW | ANSWERED_VALUE | ANSWERED   | "true"  | null          || "review_decisions_assurance_exactly_for_length_needing_review"
        REFUSE_PRODUCTION_FOR_LENGTH | SPARE_REVIEW  | SURE_VALUE     | PRODUCTION | "false" | ANSWER_REVIEW || "review_decisions_assurance_exactly_for_length_needing_review"
        DROP_THE_ASSURANCE           | LENGTH_REVIEW | ANSWERED_VALUE | ANSWERED   | "true"  | ANSWER_REVIEW || "review_decisions_assurance_fk"
    }

    def "a value decided on review may still be refused for length, and a value refused is never refused again"() {
        when:
        def refusedBy = attempt("review_decisions", first + insertInto("review_decisions", A_DECISION + [
                review_id: literal(review), production_value_id: literal(value), production_id: literal(production),
                assured_in_review_id: literal(assuredIn)]))

        then:
        refusedBy == refusal

        where:
        first                        | review        | value          | production | assuredIn     || refusal
        ""                           | LENGTH_REVIEW | ANSWERED_VALUE | ANSWERED   | ANSWER_REVIEW || null
        REFUSE_PRODUCTION_FOR_LENGTH | SPARE_REVIEW  | VALUE          | PRODUCTION | REVIEW        || "review_decisions_one_refusal"
    }
}
