package com.lilradish.lite.migration

import static com.lilradish.lite.migration.fixture.ProductionAndCallCases.*
import static com.lilradish.lite.migration.fixture.RunCases.*
import static com.lilradish.lite.migration.fixture.SchemaKeys.*
import static com.lilradish.lite.migration.fixture.SchemaRows.*
import static com.lilradish.lite.migration.fixture.SearchQuestionCases.A_SHAPE_FAILURE
import static com.lilradish.lite.migration.fixture.SourceCodeCases.*
import static com.lilradish.lite.migration.fixture.StepAndDeclarationCases.*

import com.lilradish.lite.migration.fixture.SchemaConstraintSpecification
import org.postgresql.util.PSQLException

/** What a run, its steps, their holds and failures, its stops and its ceiling changes are held to. */
class SchemaConstraintRunIntegrationSpec extends SchemaConstraintSpecification {

    // DB-SPECIFIC: the run, step, hold, failure and ceiling fixture variations below input
    // PostgreSQL native enum labels and JSONB started_with values.

    def "only a step that calls a model may be held by a code source in its own tree"() {
        when:
        def refusedBy = attempt("run_step_holds", first + insertInto("run_step_holds", row))

        then:
        refusedBy == refusal

        where:
        first            | row                                                                                         || refusal
        ""               | A_SOURCE_HOLD                                                                               || null
        REACH_SOURCE     | A_LATER_SOURCE_HOLD                                                                         || null
        RELEASE_QUESTION + REACH_SOURCE | A_LATER_SOURCE_HOLD + [run_step_id: literal(RUN_STEP), run_step_kind: "'question'"] || null
        ""               | A_CURRENT_CODE_HOLD                                                                         || null
        ""               | A_CURRENT_CODE_HOLD + [root_run_id: literal(CODE_RUN), source_run_id: literal(CODE_RUN),
                                             source_run_step_id: literal(CODE_RUN_STEP), source_workflow_step_id: literal(SUB_STEP)] || "run_step_holds_source_only_for_code_step_not_held"
        ""               | A_LATER_SOURCE_HOLD + [source_run_id: literal(RUN), source_run_step_id: literal(RUN_STEP),
                                      source_workflow_step_id: literal(STEP)]                                               || "run_step_holds_source_step_fk"
    }

    def "a run at the top is named, started by a person and its own root, and a run beneath is none of them"() {
        when:
        def refusedBy = attempt("runs", insertInto("runs", (beneath ? A_SUB_RUN : A_RUN) + [name: literal(name),
                root_run_id: literal(root), created_by: literal(author), created_by_kind: literal(kind)]))

        then:
        refusedBy == refusal

        where:
        beneath | name     | root      | author          | kind     || refusal
        false   | "Refund" | SPARE_RUN | STEWARD         | "person" || null
        true    | null     | RUN       | WORKFLOW_RUNNER | "system" || null
        false   | null     | SPARE_RUN | STEWARD         | "person" || "runs_name_exactly_for_top_level"
        true    | "Refund" | RUN       | WORKFLOW_RUNNER | "system" || "runs_name_exactly_for_top_level"
        false   | "Refund" | RUN       | STEWARD         | "person" || "runs_own_root_exactly_for_top_level"
        true    | null     | SPARE_RUN | WORKFLOW_RUNNER | "system" || "runs_own_root_exactly_for_top_level"
        false   | "Refund" | SPARE_RUN | WORKFLOW_RUNNER | "system" || "runs_author_is_person_exactly_for_top_level"
        true    | null     | RUN       | STEWARD         | "person" || "runs_author_is_person_exactly_for_top_level"
    }

    def "a run at the top runs a workflow version of an entry its own group owns"() {
        when:
        def refusedBy = attempt("runs", insertInto("runs", A_RUN + [entry_id: literal(entry),
                entry_version_id: literal(version)]))

        then:
        refusedBy == refusal

        where:
        entry                | version                || refusal
        WORKFLOW_ENTRY       | WORKFLOW_VERSION       || null
        WORKFLOW_ENTRY       | NEXT_WORKFLOW_VERSION  || null
        OTHER_WORKFLOW_ENTRY | OTHER_WORKFLOW_VERSION || "runs_top_level_entry_fk"
        WORKFLOW_ENTRY       | OTHER_WORKFLOW_VERSION || "runs_version_entry_fk"
        ENTRY                | VERSION                || "runs_version_fk"
    }

    /** Whether a version retired may still be started is the application's: retiring is no key. */
    def "a run runs only a version that has been approved"() {
        when:
        def refusedBy = attempt("runs", first + insertInto("runs", A_RUN + [group_id: literal(OTHER_GROUP),
                entry_id: literal(OTHER_WORKFLOW_ENTRY), entry_version_id: literal(version)]))

        then:
        refusedBy == refusal

        where:
        first                   | version                || refusal
        ""                      | OTHER_WORKFLOW_VERSION || null
        ""                      | OTHER_WORKFLOW_DRAFT   || "runs_version_approved_fk"
        APPROVE_THE_OTHER_DRAFT | OTHER_WORKFLOW_DRAFT   || null
    }

    /**
     * A route's run beneath runs the version the case it took leads to, fallback or not, and that may be a
     * workflow another group owns; a version no case of that route leads to is refused, and a draft is refused
     * before any case is asked.
     */
    def "a run beneath a route runs the version the case it took leads to, and no other"() {
        when:
        def refusedBy = attempt("runs", "update app.runs set parent_route_case_id = ${literal(routeCase)}," +
                " entry_id = '${entry}', entry_version_id = '${version}' where run_id = '${SUB_RUN}'")

        then:
        refusedBy == refusal

        where:
        routeCase  | entry                | version                || refusal
        FALLBACK   | WORKFLOW_ENTRY       | NEXT_WORKFLOW_VERSION  || null
        OTHER_CASE | OTHER_WORKFLOW_ENTRY | OTHER_WORKFLOW_VERSION || null
        ROUTE_CASE | OTHER_WORKFLOW_ENTRY | OTHER_WORKFLOW_DRAFT   || "runs_version_approved_fk"
        ROUTE_CASE | OTHER_WORKFLOW_ENTRY | OTHER_WORKFLOW_VERSION || "runs_parent_route_case_fk"
        OTHER_CASE | WORKFLOW_ENTRY       | NEXT_WORKFLOW_VERSION  || "runs_parent_route_case_fk"
        null       | WORKFLOW_ENTRY       | NEXT_WORKFLOW_VERSION  || "runs_parent_route_case_exactly_for_route"
    }

    def "a run beneath is in its parent's tree and its root's group"() {
        when:
        def refusedBy = attempt("runs", insertInto("runs", A_SUB_RUN + [group_id: literal(group), root_run_id: literal(root)]))

        then:
        refusedBy == refusal

        where:
        group       | root      || refusal
        GROUP       | RUN       || null
        OTHER_GROUP | RUN       || "runs_root_fk"
        GROUP       | OTHER_RUN || "runs_parent_fk"
    }

    def "a run beneath is one deeper than its parent, so that no two runs are each the other's parent"() {
        when:
        def refusedBy = attempt("runs", statement)

        then:
        refusedBy == refusal

        where:
        statement                                                          || refusal
        insertInto("runs", A_SUB_RUN)                                      || null
        insertInto("runs", A_SUB_RUN + [depth: "2", parent_depth: "1"])    || "runs_parent_fk"
        insertInto("runs", A_SUB_RUN + [depth: "0", parent_depth: "-1"])   || "runs_depth_zero_exactly_for_top_level"
        TWO_RUNS_EACH_THE_OTHERS_PARENT                                    || "runs_parent_fk"
    }

    def "a run beneath is started by a step of its parent, named whole"() {
        when:
        def refusedBy = attempt("runs", insertInto("runs", A_SUB_RUN + [parent_run_id: literal(parent),
                parent_workflow_step_id: literal(workflowStep), depth: depth, parent_depth: parentDepth]))

        then:
        refusedBy == refusal

        where:
        parent  | workflowStep | depth | parentDepth || refusal
        RUN     | ESCALATE     | "1"   | "0"         || null
        SUB_RUN | ESCALATE     | "2"   | "1"         || "runs_parent_step_fk"
        RUN     | STEP         | "1"   | "0"         || "runs_parent_step_fk"
        RUN     | null         | "1"   | "0"         || "runs_parent_together"
    }

    def "a run beneath is started by a step that starts runs, and that step starts no other"() {
        when:
        def refusedBy = attempt("runs", insertInto("runs", A_SUB_RUN + [parent_run_step_id: literal(step),
                parent_run_step_kind: literal(kind), parent_workflow_step_id: literal(workflowStep),
                parent_route_case_id: literal(routeCase)]))

        then:
        refusedBy == refusal

        where:
        step              | kind       | workflowStep | routeCase  || refusal
        ESCALATE_RUN_STEP | "workflow" | ESCALATE     | null       || null
        RUN_STEP          | "question" | STEP         | null       || "runs_parent_step_is_workflow_or_route"
        ROUTE_RUN_STEP    | "route"    | ROUTE        | ROUTE_CASE || "runs_parent_step_unique"
    }

    def "a run beneath a step running a workflow runs the version it pins, and names a case only beneath a route"() {
        when:
        def refusedBy = attempt("runs", insertInto("runs", A_SUB_RUN + [parent_run_step_kind: literal(kind),
                parent_route_case_id: literal(routeCase), entry_version_id: literal(version)]))

        then:
        refusedBy == refusal

        where:
        kind       | routeCase  | version               || refusal
        "workflow" | null       | NEXT_WORKFLOW_VERSION || null
        "workflow" | null       | WORKFLOW_VERSION      || "runs_parent_pinned_version_fk"
        "workflow" | ROUTE_CASE | NEXT_WORKFLOW_VERSION || "runs_parent_route_case_exactly_for_route"
        "route"    | null       | NEXT_WORKFLOW_VERSION || "runs_parent_route_case_exactly_for_route"
    }

    def "only a run at the top is renamed"() {
        when:
        // DB-SPECIFIC: now() uses PostgreSQL's syntax and semantics.
        def refusedBy = attempt("runs", "update app.runs set name = ${literal(name)}, updated_at = now()," +
                " updated_by = '${STEWARD}' where run_id = '${run}'")

        then:
        refusedBy == refusal

        where:
        run     | name     || refusal
        RUN     | "Refund" || null
        SUB_RUN | null     || "runs_updated_only_for_top_level"
    }

    /** A run beneath holds no input of its own: what it takes is bound from the step that started it. */
    def "only a run at the top is started with something, an object at most 50331648 characters as the store writes it"() {
        when:
        def refusedBy = attempt("runs", "update app.runs set started_with = ${startedWith} where run_id = '${run}'")

        then:
        refusedBy == refusal
        // DB-SPECIFIC: jsonb_build_object(), repeat() and chr() use PostgreSQL's syntax and semantics.

        where:
        run     | startedWith                                                          || refusal
        RUN     | "'{}'"                                                               || null
        RUN     | "jsonb_build_object('complaint', repeat(chr(1), 8388605) || 'a')"  || null
        RUN     | "jsonb_build_object('complaint', repeat(chr(1), 8388605) || 'aa')" || "runs_started_with_bounded"
        RUN     | "'[]'"                                                               || "runs_started_with_is_an_object"
        RUN     | "'\"It never came.\"'"                                               || "runs_started_with_is_an_object"
        RUN     | "null"                                                               || "runs_started_with_exactly_for_top_level"
        SUB_RUN | "'{}'"                                                               || "runs_started_with_exactly_for_top_level"
    }

    def "a step is run as a step of the version its run ran"() {
        when:
        def refusedBy = attempt("run_steps", insertInto("run_steps", A_RUN_STEP + [run_id: literal(run),
                entry_version_id: literal(version), workflow_step_id: literal(step), step_kind: literal(kind),
                pinned_version_id: literal(pinned), pinned_kind: literal(pinnedKind), producer: literal(producer),
                tries: literal(tries)]))

        then:
        refusedBy == refusal

        where:
        run       | version               | step     | kind        | pinned                | pinnedKind | producer | tries || refusal
        SUB_RUN   | NEXT_WORKFLOW_VERSION | SUB_STEP | "code_step" | null                  | null       | "code"   | 3     || null
        OTHER_RUN | WORKFLOW_VERSION      | STEP     | "entry"     | VERSION               | "question" | "model"  | 3     || null
        OTHER_RUN | WORKFLOW_VERSION      | ESCALATE | "entry"     | NEXT_WORKFLOW_VERSION | "workflow" | null     | null  || null
        OTHER_RUN | WORKFLOW_VERSION      | ROUTE    | "route"     | null                  | null       | null     | null  || null
        SUB_RUN   | NEXT_WORKFLOW_VERSION | STEP     | "entry"     | VERSION               | "question" | "model"  | 3     || "run_steps_workflow_step_fk"
        SUB_RUN   | WORKFLOW_VERSION      | STEP     | "entry"     | VERSION               | "question" | "model"  | 3     || "run_steps_run_fk"
    }

    def "a step is run once in each run"() {
        when:
        def refusedBy = attempt("run_steps", insertInto("run_steps", A_RUN_STEP + [run_id: literal(run),
                entry_version_id: literal(WORKFLOW_VERSION), workflow_step_id: literal(STEP), step_kind: "'entry'",
                pinned_version_id: literal(VERSION), pinned_kind: "'question'", producer: "'model'"]))

        then:
        refusedBy == refusal

        where:
        run       || refusal
        OTHER_RUN || null
        RUN       || "run_steps_one_per_workflow_step"
    }

    def "a step run is taken as what its step runs, pinning what that step pins, and only where it runs an entry or search"() {
        when:
        def refusedBy = attempt("run_steps", insertInto("run_steps", A_RUN_STEP + [run_id: literal(OTHER_RUN),
                entry_version_id: literal(WORKFLOW_VERSION), workflow_step_id: literal(step), step_kind: literal(kind),
                pinned_version_id: literal(pinned), pinned_kind: literal(pinnedKind), producer: literal(producer),
                tries: literal(tries), reviewed_by_model: kind == "search" ? "true" : "false"]))

        then:
        refusedBy == refusal

        where:
        step  | kind        | pinned                | pinnedKind | producer | tries || refusal
        STEP  | "entry"     | VERSION               | "question" | "model"  | 3     || null
        SEARCH_STEP | "search" | VERSION | "question" | "model" | 3 || null
        SEARCH_STEP | "search" | LIST_VERSION | "question" | "model" | 3 || "run_steps_pinned_fk"
        STEP  | "code_step" | null                  | null       | "model"  | 3     || "run_steps_step_kind_fk"
        STEP  | "entry"     | NEXT_WORKFLOW_VERSION | "workflow" | null     | null  || "run_steps_pinned_fk"
        STEP  | "entry"     | VERSION               | null       | null     | null  || "run_steps_pinned_together"
    }

    def "a step run pins a version exactly where its step runs an entry or search"() {
        when:
        def refusedBy = attempt("run_steps", insertInto("run_steps", A_RUN_STEP + [run_id: literal(OTHER_RUN),
                entry_version_id: literal(WORKFLOW_VERSION), workflow_step_id: literal(step), step_kind: literal(kind),
                pinned_version_id: literal(pinned), pinned_kind: literal(pinnedKind), producer: "null", tries: "null"]))

        then:
        refusedBy == refusal

        where:
        step  | kind    | pinned  | pinnedKind || refusal
        ROUTE | "route" | null    | null       || null
        STEP  | "entry" | null    | null       || "run_steps_pinned_exactly_for_entry_or_search"
        SEARCH_STEP | "search" | null | null || "run_steps_pinned_exactly_for_entry_or_search"
        ROUTE | "route" | VERSION | "question" || "run_steps_pinned_exactly_for_entry_or_search"
    }

    def "a step run carries who produces for it, as the step says"() {
        when:
        def refusedBy = attempt("run_steps", insertInto("run_steps", A_RUN_STEP + [run_id: "'${OTHER_RUN}'",
                entry_version_id: "'${WORKFLOW_VERSION}'", workflow_step_id: literal(step), step_kind: literal(kind),
                pinned_version_id: literal(pinned), pinned_kind: literal(pinnedKind), producer: literal(producer),
                reviewed_by_model: reviewed, tries: literal(tries)]))

        then:
        refusedBy == refusal

        where:
        step  | kind    | pinned  | pinnedKind | producer | reviewed | tries || refusal
        STEP        | "entry"  | VERSION | "question" | "model"  | "false"  | 3     || null
        SEARCH_STEP | "search" | VERSION | "question" | "model"  | "true"   | 3     || null
        ROUTE       | "route"  | null    | null       | null     | "false"  | null  || null
        STEP  | "entry" | VERSION | "question" | "person" | "false"  | 3     || "run_steps_producer_fk"
        STEP  | "entry" | VERSION | "question" | "model"  | "true"   | 3     || "run_steps_producer_fk"
        STEP  | "entry" | VERSION | "question" | null     | "false"  | null  || "run_steps_producer_exactly_for_a_producing_step"
        ROUTE | "route" | null    | null       | "model"  | "false"  | 3     || "run_steps_producer_exactly_for_a_producing_step"
    }

    def "a step run carries how many tries its step declares, as the step says"() {
        when:
        def refusedBy = attempt("run_steps", insertInto("run_steps", A_RUN_STEP + [run_id: literal(run),
                entry_version_id: literal(version), workflow_step_id: literal(step), step_kind: literal(kind),
                pinned_version_id: literal(pinned), pinned_kind: literal(pinnedKind), producer: literal(producer),
                tries: literal(tries)]))

        then:
        refusedBy == refusal

        where:
        run       | version               | step     | kind        | pinned  | pinnedKind | producer | tries || refusal
        OTHER_RUN | WORKFLOW_VERSION      | STEP     | "entry"     | VERSION | "question" | "model"  | 3     || null
        SUB_RUN   | NEXT_WORKFLOW_VERSION | SUB_STEP | "code_step" | null    | null       | "code"   | 3     || null
        OTHER_RUN | WORKFLOW_VERSION      | STEP     | "entry"     | VERSION | "question" | "model"  | 2     || "run_steps_producer_fk"
        SUB_RUN   | NEXT_WORKFLOW_VERSION | SUB_STEP | "code_step" | null    | null       | "code"   | 4     || "run_steps_producer_fk"
    }

    /** Held to the step only while somebody produces for it, since a key with a null column checks nothing. */
    def "a step run carries its tries exactly where somebody produces for it"() {
        when:
        def refusedBy = attempt("run_steps", insertInto("run_steps", A_RUN_STEP + [run_id: literal(OTHER_RUN),
                entry_version_id: literal(WORKFLOW_VERSION), workflow_step_id: literal(step), step_kind: literal(kind),
                pinned_version_id: literal(pinned), pinned_kind: literal(pinnedKind), producer: literal(producer),
                tries: literal(tries)]))

        then:
        refusedBy == refusal

        where:
        step  | kind    | pinned  | pinnedKind | producer | tries || refusal
        STEP  | "entry" | VERSION | "question" | "model"  | 3     || null
        ROUTE | "route" | null    | null       | null     | null  || null
        STEP  | "entry" | VERSION | "question" | "model"  | null  || "run_steps_producer_together"
        ROUTE | "route" | null    | null       | null     | 3     || "run_steps_producer_together"
    }

    def "a step run is reviewed by a model only where somebody produces for it"() {
        when:
        def refusedBy = attempt("run_steps", insertInto("run_steps", A_RUN_STEP + [run_id: "'${OTHER_RUN}'",
                entry_version_id: "'${WORKFLOW_VERSION}'", workflow_step_id: literal(ROUTE), step_kind: "'route'",
                producer: "null", tries: "null", reviewed_by_model: reviewed]))

        then:
        refusedBy == refusal

        where:
        reviewed || refusal
        "false"  || null
        "true"   || "run_steps_reviewed_by_model_only_for_a_producer"
    }

    /**
     * What a failure says of its step, what the step runs and whether it calls a model, is what the step is: the key
     * into the step admits no other claim, so a rule written against those columns is written against the step.
     */
    def "a failure claims its step calls a model only where the step does"() {
        when:
        def refusedBy = attempt("run_step_failures", insertInto("run_step_failures", A_FAILURE + [
                run_step_id: literal(step), run_step_kind: literal(kind), calls_a_model: calls, reason: literal(reason),
                production_id: literal(production), purpose: literal(purpose)]))

        then:
        refusedBy == refusal

        where:
        step           | kind       | calls   | reason               | production | purpose   || refusal
        RUN_STEP       | "question" | "true"  | "model_not_deployed" | PRODUCTION | "produce" || null
        ROUTE_RUN_STEP | "route"    | "false" | "unclaimed_value"    | null       | null      || null
        ROUTE_RUN_STEP | "route"    | "true"  | "unclaimed_value"    | null       | null      || "run_step_failures_step_fk"
    }

    def "a search question mismatch is stored for its search without a sending try"() {
        given:
        def before = allContents()

        when:
        // DB-SPECIFIC: PostgreSQL :: casts and concat_ws assemble the stored scalar columns.
        def read = readInside(insertInto("run_step_failures", A_SHAPE_FAILURE),
                "select concat_ws('|', run_step_kind::text, reason::text, detail," +
                " coalesce(production_id::text, 'none'), coalesce(purpose::text, 'none')," +
                " coalesce(resolved_at::text, 'none'), coalesce(resolved_by::text, 'none'))" +
                " from app.run_step_failures where reason::text = 'search_question_no_longer_matches'")

        then:
        read == "search|search_question_no_longer_matches|Find evidence · Version 1|none|none|none|none"
        allContents() == before
    }

    def "an ordinary question cannot keep a search question mismatch even when its failure names its real step"() {
        given:
        def before = allContents()

        when:
        session.autoCommit = false
        try {
            execute(insertInto("run_step_failures", A_FAILURE + [
                reason: "'search_question_no_longer_matches'", production_id: "null", purpose: "null",
                detail: "'Find evidence · Version 1'",
            ]))
            execute("set constraints all immediate")
        } finally {
            session.rollback()
            session.autoCommit = true
        }

        then:
        def refused = thrown(PSQLException)
        refused.getSQLState() == "23514"
        refused.serverErrorMessage.table == "run_step_failures"
        refused.serverErrorMessage.constraint == "run_step_failures_search_question_only_for_search"
        allContents() == before
    }

    /** The entry stopped may be the run's own workflow, which holds its next step whatever that step runs. */
    def "a step is held back on an entry stopped whatever it runs, while no other hold on it is unreleased"() {
        when:
        def refusedBy = attempt("run_step_holds", first + insertInto("run_step_holds", A_HOLD + [
                run_step_id: literal(step), run_id: literal(run), run_step_kind: literal(kind), calls_a_model: calls]))

        then:
        refusedBy == refusal

        where:
        first            | step                   | run      | kind        | calls   || refusal
        ""               | ESCALATE_RUN_STEP      | RUN      | "workflow"  | "false" || null
        RELEASE_THE_HOLD | RUN_STEP               | RUN      | "question"  | "true"  || null
        ""               | REVIEWED_CODE_RUN_STEP | CODE_RUN | "code_step" | "true"  || null
        ""               | ROUTE_RUN_STEP         | RUN      | "route"     | "false" || null
        ""               | RUN_STEP               | RUN      | "question"  | "true"  || "run_step_holds_one_unreleased"
    }

    def "a step is held back on length or on being turned away only where it calls a model"() {
        when:
        def refusedBy = attempt("run_step_holds", first + insertInto("run_step_holds", A_HOLD + [
                run_step_id: literal(step), run_id: literal(run), run_step_kind: literal(kind), calls_a_model: calls,
                reason: literal(reason), run_step_send_attempt_id: literal(sending)]))

        then:
        refusedBy == refusal

        where:
        first             | step              | run     | kind        | calls   | reason        | sending      || refusal
        RELEASE_THE_HOLD  | RUN_STEP          | RUN     | "question"  | "true"  | "too_long"    | LONG_ATTEMPT || null
        RELEASE_THE_HOLD  | RUN_STEP          | RUN     | "question"  | "true"  | "turned_away" | SEND_ATTEMPT || null
        RUN_THE_CODE_STEP | SPARE_RUN_STEP    | SUB_RUN | "code_step" | "false" | "too_long"    | LONG_ATTEMPT || "run_step_holds_too_long_or_turned_away_only_for_calls_a_model"
        RUN_THE_CODE_STEP | SPARE_RUN_STEP    | SUB_RUN | "code_step" | "false" | "turned_away" | SEND_ATTEMPT || "run_step_holds_too_long_or_turned_away_only_for_calls_a_model"
        ""                | ESCALATE_RUN_STEP | RUN     | "workflow"  | "false" | "turned_away" | SEND_ATTEMPT || "run_step_holds_too_long_or_turned_away_only_for_calls_a_model"
        ""                | ROUTE_RUN_STEP    | RUN     | "route"     | "false" | "too_long"    | LONG_ATTEMPT || "run_step_holds_too_long_or_turned_away_only_for_calls_a_model"
    }

    def "a step is held back on a code step the release does not hold only where it runs a code step"() {
        when:
        def refusedBy = attempt("run_step_holds", first + insertInto("run_step_holds", A_HOLD + [
                run_step_id: literal(step), run_id: literal(run), run_step_kind: literal(kind), calls_a_model: calls,
                reason: "'code_step_not_held'"]))

        then:
        refusedBy == refusal

        where:
        first             | step              | run     | kind        | calls   || refusal
        RUN_THE_CODE_STEP | SPARE_RUN_STEP    | SUB_RUN | "code_step" | "false" || null
        RELEASE_THE_HOLD  | RUN_STEP          | RUN     | "question"  | "true"  || "run_step_holds_code_step_not_held_for_current_or_source_code"
        ""                | ESCALATE_RUN_STEP | RUN     | "workflow"  | "false" || "run_step_holds_code_step_not_held_for_current_or_source_code"
        ""                | ROUTE_RUN_STEP    | RUN     | "route"     | "false" || "run_step_holds_code_step_not_held_for_current_or_source_code"
    }

    def "a step is held back on length or on being turned away naming the attempt that was, and on nothing else naming one"() {
        when:
        def refusedBy = attempt("run_step_holds", RELEASE_THE_HOLD + insertInto("run_step_holds", A_HOLD + [
                run_step_id: literal(RUN_STEP), run_step_kind: "'question'", calls_a_model: "true", reason: literal(reason),
                run_step_send_attempt_id: literal(sending)]))

        then:
        refusedBy == refusal

        where:
        reason          | sending      || refusal
        "entry_stopped" | null         || null
        "too_long"      | LONG_ATTEMPT || null
        "entry_stopped" | SEND_ATTEMPT || "run_step_holds_attempt_exactly_for_too_long_or_turned_away"
        "too_long"      | null         || "run_step_holds_attempt_exactly_for_too_long_or_turned_away"
        "turned_away"   | null         || "run_step_holds_attempt_exactly_for_too_long_or_turned_away"
    }

    /**
     * An attempt to review that cannot be sent leaves its production waiting on a person, and never holds the step
     * back; one too long was never sent, and so was never turned away.
     */
    def "a step is held back only for an attempt of its own to produce, too long on length and sent where turned away"() {
        when:
        def refusedBy = attempt("run_step_holds", first + insertInto("run_step_holds", A_HOLD + [
                run_step_id: literal(step), run_step_kind: "'question'", calls_a_model: "true", reason: literal(reason),
                run_step_send_attempt_id: literal(sending)]))

        then:
        refusedBy == refusal

        where:
        first            | step              | reason        | sending             || refusal
        RELEASE_THE_HOLD | RUN_STEP          | "too_long"    | LONG_ATTEMPT        || null
        RELEASE_THE_HOLD | RUN_STEP          | "turned_away" | SEND_ATTEMPT        || null
        RELEASE_THE_HOLD | RUN_STEP          | "too_long"    | SEND_ATTEMPT        || "run_step_holds_attempt_fk"
        RELEASE_THE_HOLD | RUN_STEP          | "turned_away" | LONG_ATTEMPT        || "run_step_holds_attempt_fk"
        ""               | REVIEWED_RUN_STEP | "too_long"    | HELD_REVIEW_ATTEMPT || "run_step_holds_attempt_fk"
        ""               | REVIEWED_RUN_STEP | "turned_away" | REVIEW_ATTEMPT      || "run_step_holds_attempt_fk"
        ""               | REVIEWED_RUN_STEP | "too_long"    | LONG_ATTEMPT        || "run_step_holds_attempt_fk"
    }

    def "a step fails for its length or its model only where it calls a model"() {
        when:
        def refusedBy = attempt("run_step_failures", first + insertInto("run_step_failures", A_FAILURE + [
                run_step_id: literal(step), run_id: literal(run), run_step_kind: literal(kind), calls_a_model: calls,
                reason: literal(reason), production_id: literal(production), purpose: literal(purpose)]))

        then:
        refusedBy == refusal

        where:
        first             | step                   | run      | kind        | calls   | reason               | production       | purpose   || refusal
        ""                | RUN_STEP               | RUN      | "question"  | "true"  | "uncuttable_length"  | PRODUCTION       | "produce" || null
        ""                | RUN_STEP               | RUN      | "question"  | "true"  | "model_not_deployed" | PRODUCTION       | "produce" || null
        ""                | REVIEWED_CODE_RUN_STEP | CODE_RUN | "code_step" | "true"  | "model_not_deployed" | CLOSED           | "review"  || null
        TRY_THE_CODE_STEP | SPARE_RUN_STEP         | SUB_RUN  | "code_step" | "false" | "model_not_deployed" | SPARE_PRODUCTION | "review"  || "run_step_failures_sending_failure_only_for_calls_a_model"
        ""                | ROUTE_RUN_STEP         | RUN      | "route"     | "false" | "model_not_deployed" | PRODUCTION       | "produce" || "run_step_failures_sending_failure_only_for_calls_a_model"
        ""                | ESCALATE_RUN_STEP      | RUN      | "workflow"  | "false" | "uncuttable_length"  | PRODUCTION       | "produce" || "run_step_failures_sending_failure_only_for_calls_a_model"
    }

    /**
     * Try sending sends the try the step was on, for what it was being sent for: to produce, a model's; to review, one
     * a model reviews that gave something back. A value no case claimed is mended by no sending.
     */
    def "a step fails for its length or its model on a try it could send for that, and on none for a value no case claimed"() {
        when:
        def refusedBy = attempt("run_step_failures", insertInto("run_step_failures", A_FAILURE + [
                run_step_id: literal(step), run_id: literal(run), run_step_kind: literal(kind), calls_a_model: calls,
                reason: literal(reason), production_id: literal(production), purpose: literal(purpose)]))

        then:
        refusedBy == refusal

        where:
        step                   | run      | kind        | calls   | reason               | production      | purpose   || refusal
        RUN_STEP               | RUN      | "question"  | "true"  | "model_not_deployed" | PRODUCTION      | "produce" || null
        RUN_STEP               | RUN      | "question"  | "true"  | "uncuttable_length"  | PRODUCTION      | "produce" || null
        REVIEWED_RUN_STEP      | RUN      | "question"  | "true"  | "model_not_deployed" | REVIEWED_ANSWER | "review"  || null
        ROUTE_RUN_STEP         | RUN      | "route"     | "false" | "unclaimed_value"    | null            | null      || null
        RUN_STEP               | RUN      | "question"  | "true"  | "model_not_deployed" | null            | null      || "run_step_failures_production_exactly_for_a_sending_failure"
        RUN_STEP               | RUN      | "question"  | "true"  | "uncuttable_length"  | null            | null      || "run_step_failures_production_exactly_for_a_sending_failure"
        ROUTE_RUN_STEP         | RUN      | "route"     | "false" | "unclaimed_value"    | PRODUCTION      | "produce" || "run_step_failures_production_exactly_for_a_sending_failure"
        RUN_STEP               | RUN      | "question"  | "true"  | "model_not_deployed" | PRODUCTION      | null      || "run_step_failures_purpose_together"
        RUN_STEP               | RUN      | "question"  | "true"  | "model_not_deployed" | PRODUCTION      | "help"    || "run_step_failures_purpose_is_produce_or_review"
        REVIEWED_RUN_STEP      | RUN      | "question"  | "true"  | "uncuttable_length"  | REVIEWED_ANSWER | "review"  || "run_step_failures_uncuttable_length_only_for_produce"
        RUN_STEP               | RUN      | "question"  | "true"  | "model_not_deployed" | REVIEWED_ANSWER | "produce" || "run_step_failures_produced_fk"
        RUN_STEP               | RUN      | "question"  | "true"  | "model_not_deployed" | ANSWERED        | "produce" || "run_step_failures_produced_fk"
        REVIEWED_CODE_RUN_STEP | CODE_RUN | "code_step" | "true"  | "model_not_deployed" | CLOSED          | "produce" || "run_step_failures_produced_fk"
        RUN_STEP               | RUN      | "question"  | "true"  | "model_not_deployed" | PRODUCTION      | "review"  || "run_step_failures_reviewed_fk"
        REVIEWED_RUN_STEP      | RUN      | "question"  | "true"  | "model_not_deployed" | PENDING         | "review"  || "run_step_failures_reviewed_fk"
    }

    def "a step fails because no case claimed a value only where it is a route"() {
        when:
        def refusedBy = attempt("run_step_failures", insertInto("run_step_failures", A_FAILURE + [
                run_step_id: literal(step), run_id: literal(RUN), run_step_kind: literal(kind), calls_a_model: calls,
                reason: "'unclaimed_value'", production_id: "null", purpose: "null"]))

        then:
        refusedBy == refusal

        where:
        step              | kind       | calls   || refusal
        ROUTE_RUN_STEP    | "route"    | "false" || null
        RUN_STEP          | "question" | "true"  || "run_step_failures_unclaimed_value_only_for_route"
        ESCALATE_RUN_STEP | "workflow" | "false" || "run_step_failures_unclaimed_value_only_for_route"
    }

    def "a step fails too deep only where it would start a run beneath, and says nothing more"() {
        when:
        def refusedBy = attempt("run_step_failures", insertInto("run_step_failures", A_FAILURE + [
                run_step_id: literal(step), run_id: literal(RUN), run_step_kind: literal(kind), calls_a_model: calls,
                reason: "'too_deep'", production_id: "null", purpose: "null", detail: detail]))

        then:
        refusedBy == refusal

        where:
        step              | kind       | calls   | detail  || refusal
        ROUTE_RUN_STEP    | "route"    | "false" | "null"  || null
        ESCALATE_RUN_STEP | "workflow" | "false" | "null"  || null
        RUN_STEP          | "question" | "true"  | "null"  || "run_step_failures_too_deep_only_for_workflow_or_route"
        ESCALATE_RUN_STEP | "workflow" | "false" | "'Deep'" || "run_step_failures_no_detail_for_too_deep"
        ROUTE_RUN_STEP    | "route"    | "false" | "'Deep'" || "run_step_failures_no_detail_for_too_deep"
    }

    /** What a person reads of a failure on a try is written down; a value no case claimed is that value or none. */
    def "a failure on a try says what went wrong, and a value no case claimed is written as it was or as nothing"() {
        when:
        def refusedBy = attempt("run_step_failures", insertInto("run_step_failures", A_FAILURE + [
                run_step_id: literal(step), run_step_kind: literal(kind), calls_a_model: calls, reason: literal(reason),
                production_id: literal(production), purpose: literal(purpose), detail: detail]))

        then:
        refusedBy == refusal

        where:
        step           | kind       | calls   | reason               | production | purpose   | detail || refusal
        ROUTE_RUN_STEP | "route"    | "false" | "unclaimed_value"    | null       | null      | "'go'" || null
        ROUTE_RUN_STEP | "route"    | "false" | "unclaimed_value"    | null       | null      | "null" || null
        RUN_STEP       | "question" | "true"  | "model_not_deployed" | PRODUCTION | "produce" | "'general'" || null
        RUN_STEP       | "question" | "true"  | "model_not_deployed" | PRODUCTION | "produce" | "null" || "run_step_failures_detail_for_length_or_undeployed"
        RUN_STEP       | "question" | "true"  | "uncuttable_length"  | PRODUCTION | "produce" | "null" || "run_step_failures_detail_for_length_or_undeployed"
    }

    def "a run at the top is stopped by a person, or by a system subject naming the run in its tree whose ceiling was reached"() {
        when:
        def refusedBy = attempt("run_stops", insertInto("run_stops", A_RUN_STOP + [run_id: literal(run),
                root_run_id: literal(root), created_by: literal(author), created_by_kind: literal(kind),
                ceiling_run_id: literal(ceiling)]))

        then:
        refusedBy == refusal

        where:
        run     | root    | author          | kind     | ceiling   || refusal
        RUN     | RUN     | STEWARD         | "person" | null      || null
        RUN     | RUN     | WORKFLOW_RUNNER | "system" | RUN       || null
        RUN     | RUN     | WORKFLOW_RUNNER | "system" | SUB_RUN   || null
        SUB_RUN | SUB_RUN | STEWARD         | "person" | null      || "run_stops_run_fk"
        SUB_RUN | RUN     | STEWARD         | "person" | null      || "run_stops_run_is_root"
        RUN     | RUN     | WORKFLOW_RUNNER | "system" | OTHER_RUN || "run_stops_ceiling_run_fk"
        RUN     | RUN     | STEWARD         | "person" | SUB_RUN   || "run_stops_ceiling_exactly_for_system"
        RUN     | RUN     | WORKFLOW_RUNNER | "system" | null      || "run_stops_ceiling_exactly_for_system"
    }

    def "a run has one stop in force at most, and once opened again may be stopped again"() {
        when:
        def refusedBy = attempt("run_stops", "${first}insert into app.run_stops (run_id, root_run_id, created_by)" +
                " values ('${run}', '${run}', '${STEWARD}')")

        then:
        refusedBy == refusal

        where:
        first              | run       || refusal
        ""                 | RUN       || "run_stops_one_in_force"
        OPEN_THE_RUN_AGAIN | RUN       || null
        ""                 | OTHER_RUN || null
    }

    def "a stop by the system names further ceilings only of runs in its tree, never the one it names, and a person's stop none"() {
        when:
        def refusedBy = attempt("run_stop_additional_ceilings", STOP_BY_THE_SUB_RUNS_CEILING +
                insertInto("run_stop_additional_ceilings", A_FURTHER_CEILING + [run_stop_id: literal(stop),
                        stop_ceiling_run_id: literal(named), ceiling_run_id: literal(further)]))

        then:
        refusedBy == refusal

        where:
        stop             | named   | further   || refusal
        SUB_CEILING_STOP | SUB_RUN | RUN       || null
        RUN_STOP         | RUN     | SUB_RUN   || "run_stop_additional_ceilings_stop_fk"
        SUB_CEILING_STOP | RUN     | SUB_RUN   || "run_stop_additional_ceilings_stop_fk"
        SUB_CEILING_STOP | SUB_RUN | OTHER_RUN || "run_stop_additional_ceilings_ceiling_run_fk"
        SUB_CEILING_STOP | SUB_RUN | SUB_RUN   || "run_stop_additional_ceilings_not_the_stops_own"
    }

    /** Order comes from the position, which is why two changes to one run never share one. */
    def "a change takes a position of one or more that no other change to its run holds"() {
        when:
        def refusedBy = attempt("run_ceiling_changes", insertInto("run_ceiling_changes", A_CHANGE + [
                run_id: literal(run), position: position]))

        then:
        refusedBy == refusal

        where:
        run       | position || refusal
        RUN       | "2"      || null
        OTHER_RUN | "1"      || null
        RUN       | "1"      || "run_ceiling_changes_position_unique"
        RUN       | "0"      || "run_ceiling_changes_position_positive"
        OTHER_RUN | "-1"     || "run_ceiling_changes_position_positive"
    }

    def "a change awaits approval only where it raises or takes away a ceiling there was"() {
        when:
        def refusedBy = attempt("run_ceiling_changes", insertInto("run_ceiling_changes", A_CHANGE + [
                run_id: literal(OTHER_RUN), from_ceiling: literal(from), to_ceiling: literal(to), awaits_approval: awaits]))

        then:
        refusedBy == refusal

        where:
        from | to   | awaits  || refusal
        1000 | 2000 | "true"  || null
        1000 | null | "true"  || null
        1000 | 500  | "false" || null
        1000 | 2000 | "false" || null
        null | 1000 | "false" || null
        null | 1000 | "true"  || "run_ceiling_changes_awaits_approval_only_for_a_raise"
        1000 | 500  | "true"  || "run_ceiling_changes_awaits_approval_only_for_a_raise"
        1000 | 999  | "true"  || "run_ceiling_changes_awaits_approval_only_for_a_raise"
    }

    def "a change's ceiling differs from the one before it, whether either is none or a number"() {
        when:
        def refusedBy = attempt("run_ceiling_changes", insertInto("run_ceiling_changes", A_CHANGE + [
                from_ceiling: literal(from), to_ceiling: literal(to)]))

        then:
        refusedBy == refusal

        where:
        from | to   || refusal
        null | 1000 || null
        1000 | null || null
        1000 | 1001 || null
        1000 | 1000 || "run_ceiling_changes_from_differs_from_to"
        null | null || "run_ceiling_changes_from_differs_from_to"
    }

    def "a change is decided only where it awaits"() {
        when:
        def refusedBy = attempt("run_ceiling_changes", insertInto("run_ceiling_changes", A_CHANGE + [
                run_id: literal(OTHER_RUN), to_ceiling: "2000", awaits_approval: awaits, outcome: literal(outcome),
                decided_at: decidedAt, decided_by: literal(decider)]))

        then:
        refusedBy == refusal
        // DB-SPECIFIC: now() uses PostgreSQL's syntax and semantics.

        where:
        awaits  | outcome     | decidedAt | decider || refusal
        "true"  | null        | "null"    | null    || null
        "true"  | "withdrawn_by_requester" | "now()"   | STEWARD || null
        "false" | null        | "null"    | null    || null
        "false" | "withdrawn_by_requester" | "now()"   | STEWARD || "run_ceiling_changes_outcome_only_for_awaiting"
    }

    def "a change is decided with its outcome, when and by whom, all three or none of them"() {
        when:
        def refusedBy = attempt("run_ceiling_changes", insertInto("run_ceiling_changes", A_CHANGE + [
                run_id: literal(OTHER_RUN), to_ceiling: "2000", awaits_approval: "true", outcome: literal(outcome),
                decided_at: decidedAt, decided_by: literal(decider)]))

        then:
        refusedBy == refusal
        // DB-SPECIFIC: now() uses PostgreSQL's syntax and semantics.

        where:
        outcome    | decidedAt | decider || refusal
        null       | "null"    | null    || null
        "approved" | "now()"   | MEMBER  || null
        "approved" | "null"    | null    || "run_ceiling_changes_decided_together"
        "approved" | "now()"   | null    || "run_ceiling_changes_decided_together"
        null       | "now()"   | MEMBER  || "run_ceiling_changes_decided_together"
    }

    def "a change is approved or refused by another, withdrawn by its requester, or superseded by either"() {
        when:
        def refusedBy = attempt("run_ceiling_changes", insertInto("run_ceiling_changes", A_CHANGE + [
                run_id: literal(OTHER_RUN), to_ceiling: "2000", awaits_approval: "true", outcome: literal(outcome),
                // DB-SPECIFIC: now() uses PostgreSQL's syntax and semantics.
                decided_at: "now()", decided_by: literal(decider)]))

        then:
        refusedBy == refusal

        where:
        outcome     | decider || refusal
        "approved"  | MEMBER  || null
        "refused"   | MEMBER  || null
        "withdrawn_by_requester" | STEWARD || null
        "superseded_by_change" | STEWARD || null
        "superseded_by_change" | MEMBER  || null
        "withdrawn_by_requester" | MEMBER  || "run_ceiling_changes_decider_matches_outcome"
        "approved"  | STEWARD || "run_ceiling_changes_decider_matches_outcome"
        "refused"   | STEWARD || "run_ceiling_changes_decider_matches_outcome"
    }

    def "a run has one change awaiting at most, and any number decided or never awaiting beside it"() {
        when:
        def refusedBy = attempt("run_ceiling_changes", insertInto("run_ceiling_changes", A_CHANGE + [
                to_ceiling: "3000", awaits_approval: awaits, outcome: literal(outcome), decided_at: decidedAt,
                decided_by: literal(decider)]))

        then:
        refusedBy == refusal
        // DB-SPECIFIC: now() uses PostgreSQL's syntax and semantics.

        where:
        awaits  | outcome     | decidedAt | decider || refusal
        "true"  | null        | "null"    | null    || "run_ceiling_changes_one_awaiting"
        "true"  | "refused"   | "now()"   | MEMBER  || null
        "true"  | "withdrawn_by_requester" | "now()"   | STEWARD || null
        "false" | null        | "null"    | null    || null
    }

    def "a change once decided lets the next one await"() {
        when:
        // DB-SPECIFIC: now() uses PostgreSQL's syntax and semantics.
        def refusedBy = attempt("run_ceiling_changes", "update app.run_ceiling_changes set outcome = '${outcome}'," +
                " decided_at = now(), decided_by = '${decider}' where run_ceiling_change_id = '${CHANGE}'; " +
                insertInto("run_ceiling_changes", A_CHANGE + [to_ceiling: "3000", awaits_approval: "true"]))

        then:
        refusedBy == null

        where:
        outcome                  | decider
        "approved"               | MEMBER
        "refused"                | MEMBER
        "withdrawn_by_requester" | STEWARD
        "superseded_by_change"   | MEMBER
    }

    def "a step has one unreleased hold at most, and one released lets it be held again"() {
        when:
        def refusedBy = attempt("run_step_holds", first + insertInto("run_step_holds", A_HOLD + [
                run_step_id: literal(step), run_step_kind: literal(kind), calls_a_model: calls, released_at: releasedAt,
                released_by: literal(releaser)]))

        then:
        refusedBy == refusal
        // DB-SPECIFIC: now() uses PostgreSQL's syntax and semantics.

        where:
        first            | step              | kind       | calls   | releasedAt | releaser        || refusal
        ""               | RUN_STEP          | "question" | "true"  | "null"     | null            || "run_step_holds_one_unreleased"
        ""               | RUN_STEP          | "question" | "true"  | "now()"    | WORKFLOW_RUNNER || null
        RELEASE_THE_HOLD | RUN_STEP          | "question" | "true"  | "null"     | null            || null
        ""               | ESCALATE_RUN_STEP | "workflow" | "false" | "null"     | null            || null
    }

    /** What a person does to have a hold released is recorded as their own act, never as the release. */
    def "a hold is released by a system subject and by nobody else"() {
        when:
        // DB-SPECIFIC: now() uses PostgreSQL's syntax and semantics.
        def refusedBy = attempt("run_step_holds", setting("run_step_holds",
                "released_at = now(), released_by = '${releaser}', released_by_kind = '${kind}'"))

        then:
        refusedBy == refusal

        where:
        releaser        | kind     || refusal
        WORKFLOW_RUNNER | "system" || null
        STEWARD         | "person" || "run_step_holds_releaser_is_system"
        SEEDER          | "seeder" || "run_step_holds_releaser_is_system"
    }

    private Map<String, String> allContents() {
        // DB-SPECIFIC: pg_tables is PostgreSQL's table catalogue.
        def tables = queryForStrings("select tablename from pg_tables where schemaname = 'app' order by tablename")
        assert !tables.empty
        tables.collectEntries { table -> [(table): contentsOf(table)] }
    }

    def "a source that no longer matches is kept for model production with its original field and actual code step"() {
        when:
        def refusedBy = attempt("run_step_failures", REACH_SOURCE + insertInto("run_step_failures",
                A_SOURCE_FAILURE + changed + [reason: literal(reason), source_field: field == null ? "null" : literal(field)]))

        then:
        refusedBy == refusal

        where:
        reason | changed | field || refusal
        "source_no_longer_matches" | [:]                                                                      | "answer"              || null
        "source_no_longer_matches" | [:]                                                                      | "removed.field"       || null
        "source_no_longer_matches" | [:]                                                                      | "a." * 511 + "a"     || null
        "source_no_longer_matches" | [run_step_id: literal(SEARCH_RUN_STEP), run_step_kind: "'search'",
         production_id: literal(SEARCH_PRODUCTION)]                               | "answer"              || null
        "source_no_longer_matches" | [source_run_id: literal(RUN), source_run_step_id: literal(RUN_STEP),
         source_workflow_step_id: literal(STEP)]                                  | "answer"              || "run_step_failures_source_step_fk"
        "source_list_not_here" | [:] | "answer" || null
        "source_list_not_here" | [:] | "removed.field" || null
        "source_list_not_here" | [run_step_id: literal(SEARCH_RUN_STEP), run_step_kind: "'search'",
                                 production_id: literal(SEARCH_PRODUCTION)] | "answer" || null
        "source_list_not_here" | [source_run_id: literal(RUN), source_run_step_id: literal(RUN_STEP),
                                 source_workflow_step_id: literal(STEP)] | "answer" || "run_step_failures_source_step_fk"
        "source_list_not_here" | [root_run_id: "null", source_run_id: "null", source_run_step_id: "null",
                                 source_workflow_step_id: "null"] | null || "run_step_failures_source_exactly_for_a_source_failure"
        "source_list_not_here" | [run_step_id: literal(REVIEWED_RUN_STEP), production_id: literal(REVIEWED_ANSWER),
                                 purpose: "'review'"] | "answer" || "run_step_failures_source_only_for_produce"
    }
}
