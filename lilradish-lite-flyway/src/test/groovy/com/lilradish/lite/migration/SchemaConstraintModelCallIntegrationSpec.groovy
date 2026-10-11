package com.lilradish.lite.migration

import static com.lilradish.lite.migration.fixture.ProductionAndCallCases.*
import static com.lilradish.lite.migration.fixture.RunCases.*
import static com.lilradish.lite.migration.fixture.SchemaKeys.*
import static com.lilradish.lite.migration.fixture.SchemaRows.*
import static com.lilradish.lite.migration.fixture.SearchQuestionCases.A_SHAPE_FAILURE

import com.lilradish.lite.migration.fixture.SchemaConstraintSpecification
import org.postgresql.util.PSQLException

/** What an attempt at sending, a call to a model, what it kept, its turnaways and a question to the
 * helper are held to. */
class SchemaConstraintModelCallIntegrationSpec extends SchemaConstraintSpecification {

    // DB-SPECIFIC: the fixture variations and statement helpers below input PostgreSQL native
    // run_step_kind, model_call_purpose, subject_kind, review_unbuilt_reason and model_call_outcome labels.

    def "only a producing step is tried sending, and code is sent only to review"() {
        when:
        def refusedBy = attempt("run_step_send_attempts", insertInto("run_step_send_attempts", A_SEND_ATTEMPT + [
                run_step_id: literal(step), run_id: literal(run), run_step_kind: literal(kind),
                workflow_step_id: literal(workflowStep), purpose: literal(purpose), production_id: literal(production)]))

        then:
        refusedBy == refusal

        where:
        step                   | run      | kind        | workflowStep  | purpose   | production || refusal
        RUN_STEP               | RUN      | "question"  | STEP          | "produce" | PRODUCTION || null
        REVIEWED_CODE_RUN_STEP | CODE_RUN | "code_step" | REVIEWED_CODE | "review"  | CLOSED     || null
        REVIEWED_CODE_RUN_STEP | CODE_RUN | "code_step" | REVIEWED_CODE | "produce" | CLOSED     || "run_step_send_attempts_produce_only_for_question_or_search"
        ESCALATE_RUN_STEP      | RUN      | "workflow"  | ESCALATE      | "produce" | PRODUCTION || "run_step_send_attempts_only_for_a_producing_step"
        ROUTE_RUN_STEP         | RUN      | "route"     | ROUTE         | "produce" | PRODUCTION || "run_step_send_attempts_only_for_a_producing_step"
    }

    def "what happens to a step is recorded against the run it is a step of, and as what that step runs"() {
        when:
        def refusedBy = attempt(table, insertInto(table, WHAT_HAPPENS_TO_A_STEP[table] + [run_id: literal(run)] +
                (claimsAnother ? CLAIMING_ANOTHER_KIND[table] : [:])))

        then:
        refusedBy == (run == RUN && !claimsAnother ? null : "${table}_step_fk".toString())

        where:
        [table, run, claimsAnother] << [WHAT_HAPPENS_TO_A_STEP.keySet().toList(), [RUN, SUB_RUN], [false, true]]
                .combinations()
    }

    def "an attempt at sending is a person's or a system subject's"() {
        when:
        def refusedBy = attempt("run_step_send_attempts", insertInto("run_step_send_attempts",
                A_SEND_ATTEMPT + [created_by: literal(author), created_by_kind: literal(kind)]))

        then:
        refusedBy == refusal

        where:
        author          | kind     || refusal
        STEWARD         | "person" || null
        WORKFLOW_RUNNER | "system" || null
        SEEDER          | "seeder" || "run_step_send_attempts_author_is_person_or_system"
    }

    def "an attempt at sending is made to produce or to review, and never for the helper"() {
        when:
        def refusedBy = attempt("run_step_send_attempts", insertInto("run_step_send_attempts",
                A_SEND_ATTEMPT + anAttempt(step, purpose, production)))

        then:
        refusedBy == refusal

        where:
        step              | purpose   | production      || refusal
        RUN_STEP          | "produce" | PRODUCTION      || null
        REVIEWED_RUN_STEP | "review"  | REVIEWED_ANSWER || null
        RUN_STEP          | "help"    | PRODUCTION      || "run_step_send_attempts_purpose_is_produce_or_review"
    }

    def "an attempt to produce names the model and mode the approved step names to produce with"() {
        when:
        def refusedBy = attempt("run_step_send_attempts", insertInto("run_step_send_attempts",
                A_SEND_ATTEMPT + [model: literal(model), mode: literal(mode)]))

        then:
        refusedBy == refusal

        where:
        model     | mode       || refusal
        "general" | "ordinary" || null
        "general" | "research" || "run_step_send_attempts_producer_model_fk"
        "other"   | "ordinary" || "run_step_send_attempts_producer_model_fk"
    }

    /** The word the store keeps for no mode is compared as any other, so a key carrying it is never switched off. */
    def "an attempt to produce with the model as it is runs is refused on a step naming it in a mode"() {
        when:
        def refusedBy = attempt("run_step_send_attempts", PRODUCE_IN_RESEARCH_BENEATH +
                insertInto("run_step_send_attempts", A_SEND_ATTEMPT + [run_step_id: literal(SPARE_RUN_STEP),
                        run_id: literal(SUB_RUN), workflow_step_id: literal(SPARE_STEP),
                        production_id: literal(SPARE_PRODUCTION), mode: literal(mode)]))

        then:
        refusedBy == refusal

        where:
        mode       || refusal
        "research" || null
        "ordinary" || "run_step_send_attempts_producer_model_fk"
    }

    def "an attempt to review names the model and mode the approved step names to review with, and a step that names one"() {
        when:
        def refusedBy = attempt("run_step_send_attempts", insertInto("run_step_send_attempts",
                A_SEND_ATTEMPT + anAttempt(step, "review", production) + [mode: literal(mode)]))

        then:
        refusedBy == refusal

        where:
        step                   | production      | mode       || refusal
        REVIEWED_RUN_STEP      | REVIEWED_ANSWER | "research" || null
        REVIEWED_RUN_STEP      | REVIEWED_ANSWER | "ordinary" || "run_step_send_attempts_reviewer_model_fk"
        RUN_STEP               | PRODUCTION      | "ordinary" || "run_step_send_attempts_reviewer_model_fk"
        REVIEWED_CODE_RUN_STEP | CLOSED          | "ordinary" || null
        REVIEWED_CODE_RUN_STEP | CLOSED          | "research" || "run_step_send_attempts_reviewer_model_fk"
    }

    def "an attempt to produce names a model's try of its own step"() {
        when:
        def refusedBy = attempt("run_step_send_attempts", insertInto("run_step_send_attempts",
                A_SEND_ATTEMPT + [production_id: literal(production)]))

        then:
        refusedBy == refusal

        where:
        production      || refusal
        PRODUCTION      || null
        ANSWERED        || "run_step_send_attempts_produced_fk"
        REVIEWED_ANSWER || "run_step_send_attempts_produced_fk"
    }

    def "an attempt to review names a try of its own step that gave something back"() {
        when:
        def refusedBy = attempt("run_step_send_attempts", insertInto("run_step_send_attempts",
                A_SEND_ATTEMPT + anAttempt(REVIEWED_RUN_STEP, "review", production)))

        then:
        refusedBy == refusal

        where:
        production      || refusal
        REVIEWED_ANSWER || null
        PENDING         || "run_step_send_attempts_reviewed_fk"
        PRODUCTION      || "run_step_send_attempts_reviewed_fk"
    }

    def "what would be sent is kept as it was, as JSON text of at most 8388608 characters"() {
        when:
        def refusedBy = attempt("run_step_send_attempts", insertInto("run_step_send_attempts",
                A_SEND_ATTEMPT + [payload: payload]))

        then:
        refusedBy == refusal
        // DB-SPECIFIC: chr() and repeat() use PostgreSQL's syntax and semantics.

        where:
        payload                                                     || refusal
        "'{' || chr(9) || chr(10) || chr(13) || ' }'"               || null
        "'{\"a\": \"' || chr(127) || chr(133) || chr(159) || '\"}'" || null
        "'\"' || repeat('a', 8388606) || '\"'"                      || null
        "''"                                                        || "run_step_send_attempts_payload_is_json"
        "'{\"a\": \"' || chr(9) || '\"}'"                           || "run_step_send_attempts_payload_is_json"
        "'{' || chr(11) || '}'"                                     || "run_step_send_attempts_payload_is_json"
        "'{' || chr(31) || '}'"                                     || "run_step_send_attempts_payload_is_json"
        "'Summarise the complaint.'"                                || "run_step_send_attempts_payload_is_json"
        "'\"' || repeat('a', 8388607) || '\"'"                      || "run_step_send_attempts_payload_bounded"
    }

    def "an attempt holds what it would send, or names an attempt holding the same, and never both"() {
        when:
        def refusedBy = attempt("run_step_send_attempts", insertInto("run_step_send_attempts",
                A_SEND_ATTEMPT + [payload: payload, repeats_attempt_id: literal(repeated)]))

        then:
        refusedBy == refusal

        where:
        payload | repeated     || refusal
        "'{}'"  | null         || null
        "null"  | SEND_ATTEMPT || null
        "null"  | null         || "run_step_send_attempts_repeats_exactly_for_no_payload"
        "'{}'"  | SEND_ATTEMPT || "run_step_send_attempts_repeats_exactly_for_no_payload"
    }

    /**
     * Named directly, so what was sent is read from one row and never down a chain of repeats. What a step is sent to
     * produce is the same whichever try it is for; what a try is sent to be reviewed is that try's own.
     */
    def "an attempt repeats one for the same purpose that holds what it sent: of its step to produce, of its try to review"() {
        when:
        def refusedBy = attempt("run_step_send_attempts", first + insertInto("run_step_send_attempts",
                A_SEND_ATTEMPT + anAttempt(step, purpose, production) + [payload: "null",
                        repeats_attempt_id: literal(repeated)]))

        then:
        refusedBy == refusal

        where:
        first                           | step              | purpose   | production       | repeated       || refusal
        ""                              | RUN_STEP          | "produce" | PRODUCTION       | SEND_ATTEMPT   || null
        A_TRY_LEFT_WAITING_BY_A_FAILURE | RUN_STEP          | "produce" | SPARE_PRODUCTION | SEND_ATTEMPT   || null
        ""                              | REVIEWED_RUN_STEP | "review"  | REVIEWED_ANSWER  | REVIEW_ATTEMPT || null
        ANSWER_THE_WAITING_TRY          | REVIEWED_RUN_STEP | "review"  | PENDING          | REVIEW_ATTEMPT || "run_step_send_attempts_repeats_fk"
        ""                              | REVIEWED_RUN_STEP | "review"  | REVIEWED_ANSWER  | SEND_ATTEMPT   || "run_step_send_attempts_repeats_fk"
        ""                              | REVIEWED_RUN_STEP | "produce" | REVIEWED_ANSWER  | REVIEW_ATTEMPT || "run_step_send_attempts_repeats_fk"
        REPEAT_THE_SEND_ATTEMPT         | RUN_STEP          | "produce" | PRODUCTION       | SPARE_ATTEMPT  || "run_step_send_attempts_repeats_fk"
    }

    /** Once one is, a person reviews in the model's place, and no attempt at reviewing that try follows it. */
    def "a try has one attempt at reviewing it measured too long at most"() {
        when:
        def refusedBy = attempt("run_step_send_attempts", first + insertInto("run_step_send_attempts",
                A_SEND_ATTEMPT + anAttempt(REVIEWED_RUN_STEP, "review", production) + [too_long: "true",
                        created_by: literal(WORKFLOW_RUNNER), created_by_kind: "'system'"]))

        then:
        refusedBy == refusal

        where:
        first                  | production      || refusal
        ANSWER_THE_WAITING_TRY | PENDING         || null
        ""                     | REVIEWED_ANSWER || null
        ""                     | LONG_ANSWER     || "run_step_send_attempts_one_too_long_review"
    }

    /**
     * Too long is measured and never said: a person who wrote one would have the review handed to them. What a
     * person sends again after the call was turned away every time is measured afresh, and may be too long in turn.
     */
    def "an attempt at reviewing is measured too long by the system alone, whether or not it repeats one"() {
        when:
        def refusedBy = attempt("run_step_send_attempts", insertInto("run_step_send_attempts", A_SEND_ATTEMPT +
                anAttempt(REVIEWED_RUN_STEP, "review", REVIEWED_ANSWER) + [too_long: tooLong, payload: payload,
                        repeats_attempt_id: literal(repeated), created_by: literal(author), created_by_kind: literal(kind)]))

        then:
        refusedBy == refusal

        where:
        tooLong | payload | repeated       | author          | kind     || refusal
        "true"  | "'{}'"  | null           | WORKFLOW_RUNNER | "system" || null
        "true"  | "null"  | REVIEW_ATTEMPT | WORKFLOW_RUNNER | "system" || null
        "false" | "null"  | REVIEW_ATTEMPT | STEWARD         | "person" || null
        "true"  | "'{}'"  | null           | STEWARD         | "person" || "run_step_send_attempts_too_long_review_only_for_system"
        "true"  | "null"  | REVIEW_ATTEMPT | STEWARD         | "person" || "run_step_send_attempts_too_long_review_only_for_system"
    }

    /**
     * What a review would be sent is not built where the release no longer allows it: nothing is held of it and
     * nothing sent, so a person reviews as for one too long; a repeat is of something built, so none is unbuilt.
     */
    def "an attempt at reviewing is left unbuilt only as sending nothing and holding nothing"() {
        when:
        def refusedBy = attempt("run_step_send_attempts", insertInto("run_step_send_attempts", A_SEND_ATTEMPT +
                anAttempt(REVIEWED_RUN_STEP, "review", REVIEWED_ANSWER) + [too_long: tooLong, payload: payload,
                        unbuilt_reason: literal(unbuilt), repeats_attempt_id: literal(repeated),
                        created_by: literal(WORKFLOW_RUNNER), created_by_kind: "'system'"]))

        then:
        refusedBy == refusal

        where:
        tooLong | payload | unbuilt                    | repeated       || refusal
        "true"  | "null"  | "list_not_here"            | null           || null
        "true"  | "null"  | "takes_no_longer_declared" | null           || null
        "true"  | "null"  | "no_longer_declared"       | null           || null
        "false" | "null"  | "no_longer_declared"       | null           || "run_step_send_attempts_unbuilt_only_for_review_not_sent"
        "true"  | "'{}'"  | "no_longer_declared"       | null           || "run_step_send_attempts_unbuilt_only_for_review_not_sent"
        "true"  | "null"  | "no_longer_declared"       | REVIEW_ATTEMPT || "run_step_send_attempts_repeats_exactly_for_no_payload"
        "true"  | "null"  | null                       | null           || "run_step_send_attempts_repeats_exactly_for_no_payload"
    }

    /** The key a repeat names does not act on the row it names: changing what a repeat relies on is refused. */
    def "an attempt a repeat names keeps its purpose, and for reviewing keeps the try it reviews"() {
        when:
        def refusedBy = attempt("run_step_send_attempts", first + change)

        then:
        refusedBy == refusal

        where:
        first                     | change                                                                                                                     || refusal
        REPEAT_THE_REVIEW_ATTEMPT | "update app.run_step_send_attempts set production_id = '${LONG_ANSWER}' where run_step_send_attempt_id = '${REVIEW_ATTEMPT}'" || "run_step_send_attempts_repeats_fk"
        REPEAT_THE_SEND_ATTEMPT   | "update app.run_step_send_attempts set purpose = 'review' where run_step_send_attempt_id = '${SEND_ATTEMPT}'"               || "run_step_send_attempts_repeats_fk"
    }

    def "a try waiting on a model is tried sending again after its step failed"() {
        when:
        def refusedBy = attempt("model_calls", A_TRY_LEFT_WAITING_BY_A_FAILURE +
                insertInto("run_step_send_attempts", A_SEND_ATTEMPT + [run_step_send_attempt_id: literal(SPARE_NEXT_ATTEMPT),
                        production_id: literal(SPARE_PRODUCTION), payload: "null",
                        repeats_attempt_id: literal(SPARE_ATTEMPT), answers_failure_id: literal(SPARE_FAILURE)]) + "; " +
                insertInto("model_calls", A_PRODUCE_CALL + [run_step_send_attempt_id: literal(SPARE_NEXT_ATTEMPT),
                        production_id: literal(SPARE_PRODUCTION)]))

        then:
        refusedBy == null
    }

    /** A route's failure is on no try, and so is never answered. */
    def "a failure is answered once at most, by an attempt at sending the try it was on for what it failed sending"() {
        when:
        def refusedBy = attempt("run_step_send_attempts", first + insertInto("run_step_send_attempts",
                A_SEND_ATTEMPT + anAttempt(step, purpose, production) + [answers_failure_id: literal(failure)]))

        then:
        refusedBy == refusal

        where:
        first                           | step                   | purpose   | production       | failure       || refusal
        A_TRY_LEFT_WAITING_BY_A_FAILURE | RUN_STEP               | "produce" | SPARE_PRODUCTION | SPARE_FAILURE || null
        FAIL_TO_REVIEW_THE_ANSWER       | REVIEWED_RUN_STEP      | "review"  | REVIEWED_ANSWER  | SPARE_FAILURE || null
        FAIL_TO_REVIEW_THE_MODELS_TRY   | REVIEWED_RUN_STEP      | "review"  | SPARE_PRODUCTION | SPARE_FAILURE || null
        FAIL_TO_REVIEW_THE_MODELS_TRY   | REVIEWED_RUN_STEP      | "produce" | SPARE_PRODUCTION | SPARE_FAILURE || "run_step_send_attempts_failure_fk"
        FAIL_TO_REVIEW_THE_MODELS_TRY   | REVIEWED_RUN_STEP      | "review"  | REVIEWED_ANSWER  | SPARE_FAILURE || "run_step_send_attempts_failure_fk"
        A_TRY_LEFT_WAITING_BY_A_FAILURE | RUN_STEP               | "produce" | PRODUCTION       | SPARE_FAILURE || "run_step_send_attempts_failure_fk"
        A_TRY_LEFT_WAITING_BY_A_FAILURE | REVIEWED_RUN_STEP      | "review"  | REVIEWED_ANSWER  | SPARE_FAILURE || "run_step_send_attempts_failure_fk"
        ""                              | REVIEWED_CODE_RUN_STEP | "review"  | CLOSED           | UNDEPLOYED    || "run_step_send_attempts_failure_unique"
        ""                              | RUN_STEP               | "produce" | PRODUCTION       | FAILURE       || "run_step_send_attempts_failure_fk"
    }


    def "a failure before a search send can remain open or be resolved separately by a person"() {
        when:
        // DB-SPECIFIC: now() supplies PostgreSQL's current transaction time.
        def refusedBy = attempt("run_step_failures", insertInto("run_step_failures", A_SHAPE_FAILURE +
                (resolved ? [resolved_by: literal(STEWARD), created_at: "now()", resolved_at: "now()"] : [:])))

        then:
        refusedBy == null

        where:
        resolved << [false, true]
    }

    def "a search shape failure has neither a production nor a sending purpose"() {
        when:
        def refusedBy = attempt("run_step_failures", insertInto("run_step_failures", A_SHAPE_FAILURE + [
                production_id: literal(production), purpose: literal(purpose)]))

        then:
        refusedBy == refusal

        where:
        production        | purpose   || refusal
        null              | null      || null
        SEARCH_PRODUCTION | "produce" || "run_step_failures_production_exactly_for_a_sending_failure"
        null              | "produce" || "run_step_failures_purpose_together"
    }

    def "a shape failure cannot be attached to an ordinary question step"() {
        expect:
        attempt("run_step_failures", insertInto("run_step_failures", A_SHAPE_FAILURE + [
                run_step_id: literal(RUN_STEP), run_step_kind: "'question'"])) ==
                "run_step_failures_search_question_only_for_search"
    }

    def "an attempt cannot answer the separately ended search failure and an old sending failure cannot be resolved"() {
        when:
        def statement = insertInto("run_step_failures",
                (separate ? A_SHAPE_FAILURE : A_SEARCH_FAILURE) + [run_step_failure_id: literal(SPARE_FAILURE)]) + "; " +
                (separate ? insertInto("run_step_send_attempts", A_SEARCH_ATTEMPT +
                        [answers_failure_id: literal(SPARE_FAILURE)]) :
                        "update app.run_step_failures set resolved_at = created_at, resolved_by = '${STEWARD}'" +
                        " where run_step_failure_id = '${SPARE_FAILURE}'")
        def refusedBy = attempt(separate ? "run_step_send_attempts" : "run_step_failures", statement)

        then:
        refusedBy == refusal

        where:
        separate || refusal
        true     || "run_step_send_attempts_failure_fk"
        false    || "run_step_failures_resolved_only_for_search_question"
    }

    def "the search input binding and failure resolver kind are non-null store columns: #table.#column"() {
        given:
        def before = contentsOf(table)
        def row = table == "production_inputs" ? A_SEARCH_USE + [binding_id: "null"] :
                A_SHAPE_FAILURE + [resolved_by_kind: "null"]
        session.autoCommit = false

        when:
        execute(insertInto(table, row))

        then:
        def refused = thrown(PSQLException)
        refused.SQLState == "23502"
        refused.serverErrorMessage.table == table
        refused.serverErrorMessage.column == column
        session.rollback()
        session.setAutoCommit(true)
        contentsOf(table) == before

        cleanup:
        if (!session.autoCommit) {
            session.rollback()
            session.autoCommit = true
        }

        where:
        table               | column
        "production_inputs" | "binding_id"
        "run_step_failures"  | "resolved_by_kind"
    }

    def "a call to produce or to review goes through an attempt made for that, on its step, to the same model and mode"() {
        when:
        def refusedBy = attempt("model_calls", first + insertInto("model_calls", A_PRODUCE_CALL + CAME_BACK + [
                purpose: literal(purpose), run_step_send_attempt_id: literal(SPARE_ATTEMPT), run_step_id: literal(step),
                mode: literal(mode), production_id: literal(production)]))

        then:
        refusedBy == refusal

        where:
        first     | purpose   | step              | mode       | production      || refusal
        PRODUCING | "produce" | RUN_STEP          | "ordinary" | PRODUCTION      || null
        REVIEWING | "review"  | REVIEWED_RUN_STEP | "research" | REVIEWED_ANSWER || null
        PRODUCING | "review"  | RUN_STEP          | "ordinary" | PRODUCTION      || "model_calls_attempt_fk"
        PRODUCING | "produce" | REVIEWED_RUN_STEP | "ordinary" | PRODUCTION      || "model_calls_attempt_fk"
        PRODUCING | "produce" | RUN_STEP          | "research" | PRODUCTION      || "model_calls_attempt_fk"
        REVIEWING | "review"  | REVIEWED_RUN_STEP | "ordinary" | REVIEWED_ANSWER || "model_calls_attempt_fk"
        REVIEWING | "review"  | REVIEWED_RUN_STEP | "research" | PENDING         || "model_calls_attempt_fk"
    }

    def "a step has one call out at a time, and a call turned away every time is no longer out"() {
        when:
        def refusedBy = attempt("model_calls", first + insertInto("model_calls", A_CODE_REVIEW_CALL + (ended ? CAME_BACK : [:]) +
                [run_step_send_attempt_id: literal(SPARE_ATTEMPT)]))

        then:
        refusedBy == refusal

        where:
        first                                        | ended || refusal
        REVIEWING_THE_CODE                           | true  || null
        TURN_AWAY_THE_CALL_OUT + REVIEWING_THE_CODE  | false || null
        REVIEWING_THE_CODE                           | false || "model_calls_one_unended_per_step"
    }

    def "a call to the helper is made only where the version at the top may be helped, to the model and mode it names"() {
        when:
        def refusedBy = attempt("model_calls", insertInto("model_calls", A_HELP_CALL + [run_id: literal(run),
                root_run_id: literal(run), root_version_id: literal(version), model: literal(model), mode: literal(mode)]))

        then:
        refusedBy == refusal

        where:
        run      | version               | model     | mode       || refusal
        CODE_RUN | NEXT_WORKFLOW_VERSION | "general" | "ordinary" || null
        RUN      | WORKFLOW_VERSION      | "general" | "ordinary" || "model_calls_helper_fk"
        CODE_RUN | NEXT_WORKFLOW_VERSION | "other"   | "ordinary" || "model_calls_helper_fk"
        CODE_RUN | NEXT_WORKFLOW_VERSION | "general" | "research" || "model_calls_helper_fk"
    }

    /** The word the store keeps for no mode is compared as any other, so a key carrying it is never switched off. */
    def "a call to the helper as it is runs is refused where the version names the helper in a mode"() {
        when:
        def refusedBy = attempt("model_calls", setting("workflow_versions",
                "may_be_helped = true, helper_model = 'general', helper_mode = 'research'") + "; " +
                insertInto("model_calls", A_HELP_CALL + [run_id: literal(RUN), root_run_id: literal(RUN),
                        root_version_id: literal(WORKFLOW_VERSION), mode: literal(mode)]))

        then:
        refusedBy == refusal

        where:
        mode       || refusal
        "research" || null
        "ordinary" || "model_calls_helper_fk"
    }

    def "a call says what came back, and how much, only where something did"() {
        when:
        def refusedBy = attempt("model_calls", insertInto("model_calls", A_HELP_CALL + [outcome: literal(outcome),
                came_back_count: literal(cameBack), answer: answer, error_detail: literal(detail), ended_at: endedAt]))

        then:
        refusedBy == refusal
        // DB-SPECIFIC: now() uses PostgreSQL's syntax and semantics.

        where:
        outcome             | cameBack | answer | detail       | endedAt || refusal
        null                | null     | "null" | null         | "null"  || null
        "came_back"         | 300      | "'{}'" | null         | "now()" || null
        "came_back"         | 0        | "''"   | null         | "now()" || null
        "nothing_came_back" | null     | "null" | null         | "now()" || null
        "turned_away"       | null     | "null" | null         | "now()" || null
        "nothing_came_back" | 300      | "null" | null         | "now()" || "model_calls_came_back_count_exactly_for_came_back"
        "errored"           | 300      | "null" | "Timed out." | "now()" || "model_calls_came_back_count_exactly_for_came_back"
        "turned_away"       | null     | "'{}'" | null         | "now()" || "model_calls_answer_exactly_for_came_back"
    }

    def "a call says what went wrong only where it went wrong"() {
        when:
        // DB-SPECIFIC: now() uses PostgreSQL's syntax and semantics.
        def refusedBy = attempt("model_calls", insertInto("model_calls", A_HELP_CALL + [outcome: literal(outcome),
                came_back_count: literal(cameBack), answer: answer, error_detail: literal(detail), ended_at: "now()"]))

        then:
        refusedBy == refusal

        where:
        outcome     | cameBack | answer | detail       || refusal
        "errored"   | null     | "null" | "Timed out." || null
        "came_back" | 300      | "'{}'" | null         || null
        "came_back" | 300      | "'{}'" | "Timed out." || "model_calls_error_detail_exactly_for_errored"
        "errored"   | null     | "null" | null         || "model_calls_error_detail_exactly_for_errored"
    }

    def "a call says what went wrong was cut only where there is something to cut"() {
        when:
        def refusedBy = attempt("model_calls", insertInto("model_calls", A_HELP_CALL + [outcome: literal(outcome),
                came_back_count: literal(cameBack), answer: answer, error_detail: literal(detail),
                // DB-SPECIFIC: now() uses PostgreSQL's syntax and semantics.
                error_detail_truncated: truncated, ended_at: "now()"]))

        then:
        refusedBy == refusal

        where:
        outcome     | cameBack | answer | detail       | truncated || refusal
        "errored"   | null     | "null" | "Timed out." | "false"   || null
        "errored"   | null     | "null" | "Timed out." | "true"    || null
        "came_back" | 300      | "'{}'" | null         | "true"    || "model_calls_truncated_only_for_error_detail"
    }

    def "a call says what is kept of its answer is not as it came back only where an answer came back"() {
        when:
        def refusedBy = attempt("model_calls", insertInto("model_calls", A_HELP_CALL + [outcome: literal(outcome),
                came_back_count: literal(cameBack), answer: answer, error_detail: literal(detail),
                // DB-SPECIFIC: now() uses PostgreSQL's syntax and semantics.
                answer_altered: altered, ended_at: "now()"]))

        then:
        refusedBy == refusal

        where:
        outcome             | cameBack | answer | detail       | altered || refusal
        "came_back"         | 300      | "'{}'" | null         | "true"  || null
        "came_back"         | 300      | "'{}'" | null         | "false" || null
        "errored"           | null     | "null" | "Timed out." | "true"  || "model_calls_answer_altered_only_for_answer"
        "nothing_came_back" | null     | "null" | null         | "true"  || "model_calls_answer_altered_only_for_answer"
    }

    def "the stored answer and refusal flags have their declared types, nullability and defaults"() {
        expect:
        // DB-SPECIFIC: pg_attribute, pg_attrdef, pg_get_expr() and format_type() expose PostgreSQL's column definitions.
        // DB-SPECIFIC: ::text and ::regclass use PostgreSQL's cast syntax and relation lookup.
        queryForStrings("""
            select a.attname || '|' || format_type(a.atttypid, a.atttypmod) || '|' ||
                   a.attnotnull::text || '|' || coalesce(pg_get_expr(d.adbin, d.adrelid), 'none')
              from pg_attribute a
              left join pg_attrdef d on d.adrelid = a.attrelid and d.adnum = a.attnum
             where a.attrelid = 'app.model_calls'::regclass
               and a.attname in ('answer_truncated', 'declined_words',
                                'declined_words_altered', 'declined_words_truncated')
            """) == [
                "answer_truncated|boolean|true|false",
                "declined_words|text|false|none",
                "declined_words_altered|boolean|true|false",
                "declined_words_truncated|boolean|true|false"
        ] as Set
    }

    def "an omitted refusal and an explicit empty refusal stay different while new flags default to false"() {
        given:
        def before = contentsOf("model_calls")

        when:
        // DB-SPECIFIC: ::text uses PostgreSQL's cast syntax.
        def read = readInside(wordsCallStatement(operation, fields), """
            select (declined_words is null)::text || '|' ||
                   coalesce(length(declined_words)::text, 'none') || '|' ||
                   answer_truncated::text || '|' || declined_words_altered::text || '|' ||
                   declined_words_truncated::text
              from app.model_calls where model_call_id = '${wordsCallId(operation)}'
            """)

        then:
        read == expected
        contentsOf("model_calls") == before

        where:
        operation | fields                  || expected
        "insert"  | [:]                     || "true|none|false|false|false"
        "insert"  | [declined_words: "''"]   || "false|0|false|false|false"
        "update"  | [declined_words: "null"] || "true|none|false|false|false"
        "update"  | [declined_words: "''"]   || "false|0|false|false|false"
    }

    def "each call outcome keeps the appropriate nullable refusal without tightening its text"() {
        given:
        def before = contentsOf("model_calls")

        when:
        // DB-SPECIFIC: ::text uses PostgreSQL's cast syntax.
        def read = readInside(wordsCallStatement(operation, fields), """
            select coalesce(outcome::text, 'unfinished') || '|' ||
                   coalesce(length(declined_words)::text, 'none')
              from app.model_calls where model_call_id = '${wordsCallId(operation)}'
            """)

        then:
        read == expected
        contentsOf("model_calls") == before

        where:
        [operation, row] << [
                ["insert", "update"],
                [
                        [fields: [outcome: "null", ended_at: "null", came_back_count: "null", answer: "null"],
                         expected: "unfinished|none"],
                        [fields: [outcome: "'nothing_came_back'", came_back_count: "null", answer: "null"],
                         expected: "nothing_came_back|none"],
                        [fields: [outcome: "'turned_away'", came_back_count: "null", answer: "null"],
                         expected: "turned_away|none"],
                        [fields: [outcome: "'errored'", came_back_count: "null", answer: "null",
                                  error_detail: "'Demo Sample error'"], expected: "errored|none"],
                        [fields: [:], expected: "came_back|none"],
                        [fields: [answer: "''", declined_words: "''"], expected: "came_back|0"],
                        // DB-SPECIFIC: chr() constructs the character on PostgreSQL's server.
                        [fields: [answer: "chr(1)", declined_words: "chr(1)"], expected: "came_back|1"]
                ]
        ].combinations()
        fields = row.fields
        expected = row.expected
    }

    def "altered text may be short or empty and truncation also declares alteration"() {
        given:
        def before = contentsOf("model_calls")
        def fields = [answer: "''", declined_words: "''", answer_altered: altered,
                      answer_truncated: truncated, declined_words_altered: altered,
                      declined_words_truncated: truncated]

        when:
        // DB-SPECIFIC: ::text uses PostgreSQL's cast syntax.
        def read = readInside(wordsCallStatement(operation, fields), """
            select length(answer)::text || '|' || length(declined_words)::text || '|' ||
                   answer_altered::text || '|' || answer_truncated::text || '|' ||
                   declined_words_altered::text || '|' || declined_words_truncated::text
              from app.model_calls where model_call_id = '${wordsCallId(operation)}'
            """)

        then:
        read == "0|0|${altered}|${truncated}|${altered}|${truncated}"
        contentsOf("model_calls") == before

        where:
        [operation, flags] << [["insert", "update"],
                               [["false", "false"], ["true", "false"], ["true", "true"]]].combinations()
        altered = flags[0]
        truncated = flags[1]
    }

    def "answer and refusal shape rules reject #operation with the precise rule and leave the table unchanged"() {
        given:
        def before = contentsOf("model_calls")

        when:
        execute(wordsCallStatement(operation, row.fields))

        then:
        def failure = thrown(PSQLException)
        failure.getSQLState() == "23514"
        failure.serverErrorMessage.schema == "app"
        failure.serverErrorMessage.table == "model_calls"
        failure.serverErrorMessage.constraint == row.rule
        contentsOf("model_calls") == before

        where:
        [operation, row] << [
                ["insert", "update"],
                [
                        [fields: [answer_truncated: "true"],
                         rule: "model_calls_answer_truncated_only_for_altered_answer"],
                        [fields: [outcome: "null", ended_at: "null", came_back_count: "null", answer: "null",
                                  declined_words: "'Demo Sample'"], rule: "model_calls_declined_words_only_for_came_back"],
                        [fields: [outcome: "'nothing_came_back'", came_back_count: "null", answer: "null",
                                  declined_words: "''"], rule: "model_calls_declined_words_only_for_came_back"],
                        [fields: [outcome: "'turned_away'", came_back_count: "null", answer: "null",
                                  declined_words: "''"], rule: "model_calls_declined_words_only_for_came_back"],
                        [fields: [outcome: "'errored'", came_back_count: "null", answer: "null",
                                  error_detail: "'Demo Sample error'", declined_words: "''"],
                         rule: "model_calls_declined_words_only_for_came_back"],
                        [fields: [declined_words_altered: "true"],
                         rule: "model_calls_declined_words_altered_only_for_declined_words"],
                        [fields: [declined_words_altered: "true", declined_words_truncated: "true"],
                         rule: "model_calls_declined_words_altered_only_for_declined_words"],
                        [fields: [declined_words: "'Demo Sample'", declined_words_truncated: "true"],
                         rule: "model_calls_declined_words_truncated_only_for_altered_words"],
                        [fields: [outcome: "null", ended_at: "null", came_back_count: "null", answer: "null",
                                  answer_altered: "true", answer_truncated: "true"],
                         rule: "model_calls_answer_altered_only_for_answer"]
                ]
        ].combinations()
    }

    def "new stored text flags reject SQL NULL on #operation at the exact column"() {
        given:
        def before = contentsOf("model_calls")

        when:
        execute(wordsCallStatement(operation, [(column): "null"]))

        then:
        def failure = thrown(PSQLException)
        failure.getSQLState() == "23502"
        failure.serverErrorMessage.schema == "app"
        failure.serverErrorMessage.table == "model_calls"
        failure.serverErrorMessage.column == column
        contentsOf("model_calls") == before

        where:
        [operation, column] << [["insert", "update"],
                               ["answer_truncated", "declined_words_altered", "declined_words_truncated",
                                "unreadable_response_altered", "unreadable_response_truncated",
                                "unreadable_response_stopped"]].combinations()
    }

    def "stored answer and refusal bounds count characters rather than UTF8 bytes on #operation"() {
        given:
        def before = contentsOf("model_calls")
        // DB-SPECIFIC: repeat() and chr() build the large Unicode text on PostgreSQL's server.
        def text = "repeat(chr(${codePoint}), ${characters})"

        when:
        // DB-SPECIFIC: ::text uses PostgreSQL's cast syntax.
        def read = readInside(wordsCallStatement(operation, [(column): text]), """
            select length(${column})::text || '|' || octet_length(${column})::text
              from app.model_calls where model_call_id = '${wordsCallId(operation)}'
            """)

        then:
        read == "${characters}|${characters * (codePoint == 97 ? 1 : 4)}"
        contentsOf("model_calls") == before

        where:
        [operation, column, codePoint, characters] << [
                ["insert", "update"], ["answer", "declined_words"], [97, 128512], [8388607, 8388608]
        ].combinations()
    }

    def "stored answer and refusal refuse one character beyond their bound on #operation without residue"() {
        given:
        def before = contentsOf("model_calls")
        // DB-SPECIFIC: repeat() and chr() build the large Unicode text on PostgreSQL's server.
        def text = "repeat(chr(${codePoint}), 8388609)"

        when:
        execute(wordsCallStatement(operation, [(column): text]))

        then:
        def failure = thrown(PSQLException)
        failure.getSQLState() == "23514"
        failure.serverErrorMessage.schema == "app"
        failure.serverErrorMessage.table == "model_calls"
        failure.serverErrorMessage.constraint == "model_calls_${column}_bounded"
        contentsOf("model_calls") == before

        where:
        [operation, column, codePoint] << [
                ["insert", "update"], ["answer", "declined_words"], [97, 128512]
        ].combinations()
    }

    def "unreadable response columns have their declared types, nullability and defaults"() {
        expect:
        // DB-SPECIFIC: pg_attribute, pg_attrdef, pg_get_expr() and format_type() expose PostgreSQL's column definitions.
        // DB-SPECIFIC: ::text and ::regclass use PostgreSQL's cast syntax and relation lookup.
        queryForStrings("""
            select a.attname || '|' || format_type(a.atttypid, a.atttypmod) || '|' ||
                   a.attnotnull::text || '|' || coalesce(pg_get_expr(d.adbin, d.adrelid), 'none')
              from pg_attribute a
              left join pg_attrdef d on d.adrelid = a.attrelid and d.adnum = a.attnum
             where a.attrelid = 'app.model_calls'::regclass
               and a.attname in ('unreadable_response', 'unreadable_response_altered',
                                'unreadable_response_truncated', 'unreadable_response_stopped')
            """) == [
                "unreadable_response|text|false|none",
                "unreadable_response_altered|boolean|true|false",
                "unreadable_response_truncated|boolean|true|false",
                "unreadable_response_stopped|boolean|true|false"
        ] as Set
    }

    def "unreadable response text stays nullable and preserves empty text and permitted flags on #operation"() {
        given:
        def before = contentsOf("model_calls")

        when:
        // DB-SPECIFIC: ::text uses PostgreSQL's cast syntax.
        def read = readInside(unreadableCallStatement(operation, row.fields), """
            select coalesce(outcome::text, 'unfinished') || '|' || (unreadable_response is null)::text || '|' ||
                   coalesce(length(unreadable_response)::text, 'none') || '|' ||
                   unreadable_response_altered::text || '|' || unreadable_response_truncated::text
              from app.model_calls where model_call_id = '${wordsCallId(operation)}'
            """)

        then:
        read == row.expected
        contentsOf("model_calls") == before

        where:
        [operation, row] << [["insert", "update"], [
                [fields: [:], expected: "errored|true|none|false|false"],
                [fields: [unreadable_response: "null"], expected: "errored|true|none|false|false"],
                [fields: [unreadable_response: "''"], expected: "errored|false|0|false|false"],
                [fields: [unreadable_response: "''", unreadable_response_altered: "true"],
                 expected: "errored|false|0|true|false"],
                [fields: [unreadable_response: "''", unreadable_response_altered: "true",
                          unreadable_response_truncated: "true"], expected: "errored|false|0|true|true"],
                // DB-SPECIFIC: chr() builds control and replacement characters on PostgreSQL's server.
                [fields: [unreadable_response: "chr(1) || chr(10) || chr(65533)", unreadable_response_altered: "true"],
                 expected: "errored|false|3|true|false"],
                [fields: [outcome: "null", ended_at: "null", error_detail: "null"],
                 expected: "unfinished|true|none|false|false"],
                [fields: [outcome: "'came_back'", answer: "'{}'", came_back_count: "300", error_detail: "null"],
                 expected: "came_back|true|none|false|false"],
                [fields: [outcome: "'turned_away'", error_detail: "null"],
                 expected: "turned_away|true|none|false|false"],
                [fields: [outcome: "'nothing_came_back'", error_detail: "null"],
                 expected: "nothing_came_back|true|none|false|false"]
        ]].combinations()
    }

    def "an unreadable response keeps downloading stopped independently of its capture flags on #operation"() {
        given:
        def before = contentsOf("model_calls")

        when:
        // DB-SPECIFIC: ::text uses PostgreSQL's cast syntax.
        def read = readInside(unreadableCallStatement(operation, [unreadable_response: "''",
                unreadable_response_altered: altered, unreadable_response_truncated: truncated,
                unreadable_response_stopped: stopped]), """
            select unreadable_response || '|' || unreadable_response_altered::text || '|' ||
                   unreadable_response_truncated::text || '|' || unreadable_response_stopped::text
              from app.model_calls where model_call_id = '${wordsCallId(operation)}'
            """)

        then:
        read == "|${altered}|${truncated}|${stopped}"
        contentsOf("model_calls") == before

        where:
        [operation, flags, stopped] << [["insert", "update"],
                [["false", "false"], ["true", "false"], ["true", "true"]], ["false", "true"]].combinations()
        altered = flags[0]
        truncated = flags[1]
    }

    def "unreadable response rules reject an invalid row on #operation without residue"() {
        given:
        def before = contentsOf("model_calls")

        when:
        execute(unreadableCallStatement(operation, row.fields))

        then:
        def failure = thrown(PSQLException)
        failure.getSQLState() == "23514"
        failure.serverErrorMessage.schema == "app"
        failure.serverErrorMessage.table == "model_calls"
        failure.serverErrorMessage.constraint == row.rule
        contentsOf("model_calls") == before

        where:
        [operation, row] << [["insert", "update"], [
                [fields: [outcome: "null", ended_at: "null", error_detail: "null", unreadable_response: "''"],
                 rule: "model_calls_unreadable_response_only_for_errored"],
                [fields: [outcome: "'came_back'", answer: "'{}'", came_back_count: "300", error_detail: "null",
                          unreadable_response: "''"], rule: "model_calls_unreadable_response_only_for_errored"],
                [fields: [outcome: "'turned_away'", error_detail: "null", unreadable_response: "''"],
                 rule: "model_calls_unreadable_response_only_for_errored"],
                [fields: [outcome: "'nothing_came_back'", error_detail: "null", unreadable_response: "''"],
                 rule: "model_calls_unreadable_response_only_for_errored"],
                [fields: [unreadable_response_altered: "true"],
                 rule: "model_calls_unreadable_response_altered_only_for_response"],
                [fields: [unreadable_response_stopped: "true"],
                 rule: "model_calls_unreadable_response_stopped_only_for_response"],
                [fields: [unreadable_response: "''", unreadable_response_truncated: "true"],
                 rule: "model_calls_unreadable_response_truncated_only_for_altered"],
                [fields: [unreadable_response_truncated: "true"],
                 rule: "model_calls_unreadable_response_truncated_only_for_altered"],
                [fields: [unreadable_response_altered: "true", unreadable_response_truncated: "true"],
                 rule: "model_calls_unreadable_response_altered_only_for_response"]
        ]].combinations()
    }

    def "unreadable response bounds count characters rather than UTF8 bytes on #operation"() {
        given:
        def before = contentsOf("model_calls")
        // DB-SPECIFIC: repeat() and chr() build the large Unicode text on PostgreSQL's server.
        def text = "repeat(chr(${codePoint}), ${characters})"

        when:
        // DB-SPECIFIC: ::text uses PostgreSQL's cast syntax.
        def read = readInside(unreadableCallStatement(operation, [unreadable_response: text]), """
            select length(unreadable_response)::text || '|' || octet_length(unreadable_response)::text
              from app.model_calls where model_call_id = '${wordsCallId(operation)}'
            """)

        then:
        read == "${characters}|${characters * (codePoint == 97 ? 1 : 4)}"
        contentsOf("model_calls") == before

        where:
        [operation, codePoint, characters] << [["insert", "update"], [97, 128512], [8388607, 8388608]].combinations()
    }

    def "unreadable response refuses one character beyond its bound on #operation without residue"() {
        given:
        def before = contentsOf("model_calls")
        // DB-SPECIFIC: repeat() and chr() build the large Unicode text on PostgreSQL's server.
        def text = "repeat(chr(${codePoint}), 8388609)"

        when:
        execute(unreadableCallStatement(operation, [unreadable_response: text]))

        then:
        def failure = thrown(PSQLException)
        failure.getSQLState() == "23514"
        failure.serverErrorMessage.schema == "app"
        failure.serverErrorMessage.table == "model_calls"
        failure.serverErrorMessage.constraint == "model_calls_unreadable_response_bounded"
        contentsOf("model_calls") == before

        where:
        [operation, codePoint] << [["insert", "update"], [97, 128512]].combinations()
    }

    def "a call says the model did its counting only where something came back"() {
        when:
        def refusedBy = attempt("model_calls", insertInto("model_calls", A_HELP_CALL + [outcome: literal(outcome),
                came_back_count: literal(cameBack), answer: answer, error_detail: literal(detail), ended_at: endedAt,
                counted_by_model: counted]))

        then:
        refusedBy == refusal
        // DB-SPECIFIC: now() uses PostgreSQL's syntax and semantics.

        where:
        outcome             | cameBack | answer | detail       | endedAt | counted || refusal
        "came_back"         | 300      | "'{}'" | null         | "now()" | "true"  || null
        "came_back"         | 300      | "'{}'" | null         | "now()" | "false" || null
        "errored"           | null     | "null" | "Timed out." | "now()" | "true"  || "model_calls_counted_by_model_only_for_came_back"
        "nothing_came_back" | null     | "null" | null         | "now()" | "true"  || "model_calls_counted_by_model_only_for_came_back"
        "turned_away"       | null     | "null" | null         | "now()" | "true"  || "model_calls_counted_by_model_only_for_came_back"
        null                | null     | "null" | null         | "null"  | "true"  || "model_calls_counted_by_model_only_for_came_back"
    }

    def "a call still out may say the model did its counting as it comes back, and not as it goes wrong"() {
        when:
        def refusedBy = attempt("model_calls", endTheSpareHelpCall(outcome, "true"))

        then:
        refusedBy == refusal

        where:
        outcome     || refusal
        "came_back" || null
        "errored"   || "model_calls_counted_by_model_only_for_came_back"
    }

    def "a turnaway keeps what the model said, and says it was cut only where it said something"() {
        when:
        def refusedBy = attempt("model_call_turnaways", insertInto("model_call_turnaways",
                A_TURNAWAY + [said: literal(said), said_truncated: truncated]))

        then:
        refusedBy == refusal

        where:
        said    | truncated || refusal
        "Busy." | "false"   || null
        "Busy." | "true"    || null
        null    | "false"   || null
        null    | "true"    || "model_call_turnaways_truncated_only_for_said"
    }

    def "a turnaway says what may be spent was used up only of a call that ended turned away, and carries that outcome"() {
        when:
        def refusedBy = attempt("model_call_turnaways", first + insertInto("model_call_turnaways", A_TURNAWAY + [
                model_call_id: literal(call), spent_up: spentUp, model_call_outcome: literal(carried)]))

        then:
        refusedBy == refusal

        where:
        first                  | call     | spentUp | carried       || refusal
        TURN_AWAY_THE_CALL_OUT | OUT_CALL | "true"  | "turned_away" || null
        TURN_AWAY_THE_CALL_OUT | OUT_CALL | "false" | null          || null
        ""                     | CALL     | "true"  | "turned_away" || "model_call_turnaways_model_call_outcome_fk"
        ""                     | OUT_CALL | "true"  | "turned_away" || "model_call_turnaways_model_call_outcome_fk"
        ""                     | CALL     | "true"  | "came_back"   || "model_call_turnaways_model_call_outcome_is_turned_away"
        TURN_AWAY_THE_CALL_OUT | OUT_CALL | "true"  | null          || "model_call_turnaways_model_call_outcome_exactly_for_spent_up"
        TURN_AWAY_THE_CALL_OUT | OUT_CALL | "false" | "turned_away" || "model_call_turnaways_model_call_outcome_exactly_for_spent_up"
    }

    def "a call has one turnaway saying what may be spent was used up, and any number of others after it"() {
        when:
        def refusedBy = attempt("model_call_turnaways", insertInto("model_call_turnaways", A_TURNAWAY + [
                model_call_id: literal(SPENT_CALL), spent_up: spentUp, model_call_outcome: literal(carried)]))

        then:
        refusedBy == refusal

        where:
        spentUp | carried       || refusal
        "false" | null          || null
        "true"  | "turned_away" || "model_call_turnaways_one_spent_up_per_call"
    }

    def "a call a turnaway says was spent up stays turned away, and one turned away only plainly may still change"() {
        when:
        def refusedBy = attempt("model_calls", first +
                "update app.model_calls set outcome = 'nothing_came_back' where model_call_id = '${call}'")

        then:
        refusedBy == refusal

        where:
        first                          | call       || refusal
        ""                             | SPENT_CALL || "model_call_turnaways_model_call_outcome_fk"
        TURN_AWAY_THE_CALL_OUT_PLAINLY | OUT_CALL   || null
    }

    def "a turnaway is sent again only once it came, and only where it did not spend its model up"() {
        when:
        def refusedBy = attempt("model_call_turnaways", "update app.model_call_turnaways set resent_at = ${resent}" +
                " where model_call_turnaway_id = '${turnaway}'")

        then:
        refusedBy == refusal
        // DB-SPECIFIC: interval literals use PostgreSQL's syntax and semantics.

        where:
        turnaway       | resent                                || refusal
        TURNAWAY       | "created_at"                          || null
        TURNAWAY       | "created_at + interval '1 second'"    || null
        SPENT_TURNAWAY | "created_at"                          || "model_call_turnaways_resent_only_unspent"
    }

    def "a question to the helper is asked through a call to the helper about its own run"() {
        when:
        def refusedBy = attempt("run_help_exchanges", insertInto("run_help_exchanges", A_HELP + [run_id: literal(run),
                root_run_id: literal(run), model_call_id: literal(call)]))

        then:
        refusedBy == refusal

        where:
        run      | call            || refusal
        CODE_RUN | SPARE_HELP_CALL || null
        RUN      | SPARE_HELP_CALL || "run_help_exchanges_model_call_fk"
    }

    def "a question to the helper has an answer only where its call came back"() {
        when:
        def refusedBy = attempt("run_help_exchanges", first + insertInto("run_help_exchanges", A_HELP + [
                model_call_outcome: literal(claimed), answer: literal(answer)]))

        then:
        refusedBy == refusal

        where:
        first                            | claimed     | answer                    || refusal
        ""                               | null        | null                      || null
        endTheSpareHelpCall("came_back") | "came_back" | "It was the mail server." || null
        endTheSpareHelpCall("errored")   | "errored"   | null                      || null
        endTheSpareHelpCall("errored")   | "errored"   | "It was the mail server." || "run_help_exchanges_answer_only_for_came_back"
        endTheSpareHelpCall("errored")   | "came_back" | "It was the mail server." || "run_help_exchanges_model_call_outcome_fk"
    }

    private static String wordsCallId(String operation) {
        operation == "insert" ? "00000000-0000-4000-8000-000000009901" : SPARE_HELP_CALL
    }

    private static String wordsCallStatement(String operation, Map<String, String> fields) {
        def values = A_HELP_CALL + CAME_BACK + fields
        if (operation == "insert") {
            return insertInto("model_calls", values + [model_call_id: "'${wordsCallId(operation)}'"])
        }
        "update app.model_calls set " + values.collect { key, value -> "${key} = ${value}" }.join(", ") +
                " where model_call_id = '${wordsCallId(operation)}'"
    }

    private static String unreadableCallStatement(String operation, Map<String, String> fields) {
        wordsCallStatement(operation, ERRORED + [answer: "null", came_back_count: "null"] + fields)
    }
}
