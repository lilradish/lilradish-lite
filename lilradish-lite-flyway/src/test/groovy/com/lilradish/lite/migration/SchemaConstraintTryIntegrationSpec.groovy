package com.lilradish.lite.migration

import static com.lilradish.lite.migration.fixture.ProductionAndCallCases.*
import static com.lilradish.lite.migration.fixture.SchemaKeys.*
import static com.lilradish.lite.migration.fixture.SchemaRows.*

import com.lilradish.lite.migration.fixture.SchemaConstraintSpecification

/** What a try, how it ended and what code gone wrong says of it are held to. */
class SchemaConstraintTryIntegrationSpec extends SchemaConstraintSpecification {

    // DB-SPECIFIC: the fixture variations and statement helpers below input PostgreSQL native
    // step_producer, try_lost_reason, did_not_fit_reason and code_error_reason labels.

    /** Each reason code gone wrong may be given for that is about a field of the code step. */
    static final List<String> ABOUT_A_FIELD = ["nothing_given", "not_its_kind", "line_break", "too_long", "too_many", "not_a_term",
                                               "unkeepable", "too_long_to_keep", "file_type_unusable",
                                               "file_name_unusable", "file_too_large", "takes_a_list_not_here",
                                               "gives_a_list_not_here", "takes_otherwise", "gives_otherwise"]

    /** Each reason about no field at all; the one left, a member nothing declares, may be about either. */
    static final List<String> ABOUT_NO_FIELD = ["gave_nothing", "failed_on_this_side", "said_nothing",
                                              "file_total_too_large"]

    /** Where a try names a field by path: the column, and the rule holding it to a path's shape and length. */
    static final List<List<String>> PATHS_IN_PRODUCTIONS = [
        ["code_error_path", "productions_code_error_path_shape", "productions_code_error_path_bounded"],
        ["code_error_read_by_output", "productions_code_error_read_by_output_shape",
         "productions_code_error_read_by_output_bounded"],
    ]

    def "a try is produced by whoever its step names, or by a person in their place"() {
        when:
        def refusedBy = attempt("productions", insertInto("productions", aTry(step, producedBy) + [
                try_number: literal(number)]))

        then:
        refusedBy == refusal

        where:
        step              | producedBy | number || refusal
        RUN_STEP          | "model"    | 3      || null
        RUN_STEP          | "person"   | 3      || null
        REVIEWED_RUN_STEP | "person"   | 3      || null
        CODE_RUN_STEP     | "code"     | 3      || null
        CODE_RUN_STEP     | "person"   | 3      || null
        REVIEWED_RUN_STEP | "code"     | 1      || "productions_producer_is_step_producer_or_person"
        CODE_RUN_STEP     | "model"    | 3      || "productions_producer_is_step_producer_or_person"
    }

    /** One try at a time beyond them is the application's; that nothing but a person asks for one is held here. */
    def "a try beyond what its step declares is asked for by a person and never by the system"() {
        when:
        def refusedBy = attempt("productions", insertInto("productions", aTry(step, producedBy) + [
                try_number: literal(number), created_by: literal(asker), created_by_kind: literal(kind)]))

        then:
        refusedBy == refusal

        where:
        step          | producedBy | number | asker           | kind     || refusal
        RUN_STEP      | "model"    | 3      | WORKFLOW_RUNNER | "system" || null
        RUN_STEP      | "model"    | 4      | MEMBER          | "person" || null
        CODE_RUN_STEP | "code"     | 4      | STEWARD         | "person" || null
        RUN_STEP      | "model"    | 4      | WORKFLOW_RUNNER | "system" || "productions_beyond_tries_only_for_person"
        CODE_RUN_STEP | "code"     | 4      | WORKFLOW_RUNNER | "system" || "productions_beyond_tries_only_for_person"
    }

    /** The first try of such a step is the only one code runs; a person answering in its place is a try like any other. */
    def "code that may not run again is never run for a value a second time, by itself or when asked"() {
        when:
        def refusedBy = attempt("productions", insertInto("productions", aTry(step, producedBy) + [
                try_number: literal(number), created_by: literal(asker), created_by_kind: literal(kind)]))

        then:
        refusedBy == refusal

        where:
        step                   | producedBy | number | asker           | kind     || refusal
        REVIEWED_CODE_RUN_STEP | "person"   | 2      | MEMBER          | "person" || null
        CODE_RUN_STEP          | "code"     | 3      | WORKFLOW_RUNNER | "system" || null
        REVIEWED_CODE_RUN_STEP | "code"     | 2      | WORKFLOW_RUNNER | "system" || "productions_code_again_only_for_may_run_again"
        REVIEWED_CODE_RUN_STEP | "code"     | 2      | STEWARD         | "person" || "productions_code_again_only_for_may_run_again"
    }

    /** Whoever answers it, a code step's try says what the release said then; nothing but code has anything to say. */
    def "a try says whether its code may run again exactly where its step runs a code step"() {
        when:
        def refusedBy = attempt("productions", insertInto("productions", aTry(step, producedBy) + [
                may_run_again: literal(again)]))

        then:
        refusedBy == refusal

        where:
        step          | producedBy | again   || refusal
        RUN_STEP      | "person"   | null    || null
        CODE_RUN_STEP | "person"   | "false" || null
        CODE_RUN_STEP | "code"     | "true"  || null
        RUN_STEP      | "person"   | "false" || "productions_may_run_again_exactly_for_code_step"
        RUN_STEP      | "model"    | "true"  || "productions_may_run_again_exactly_for_code_step"
        CODE_RUN_STEP | "person"   | null    || "productions_may_run_again_exactly_for_code_step"
    }

    def "a person's try, once answered, says why they said it, and nobody else's does"() {
        when:
        def refusedBy = attempt("productions", insertInto("productions", aTry(step, producedBy) + [
                explanation: literal(explanation)] + (ended ? [:] : [ended_at: "null", ended_by: "null"])))

        then:
        refusedBy == refusal

        where:
        step          | producedBy | ended | explanation || refusal
        RUN_STEP      | "person"   | true  | "Read it."  || null
        RUN_STEP      | "person"   | false | null        || null
        CODE_RUN_STEP | "code"     | false | null        || null
        RUN_STEP      | "model"    | false | null        || null
        RUN_STEP      | "person"   | false | "Read it."  || "productions_explanation_exactly_for_ended_by_person"
        CODE_RUN_STEP | "code"     | true  | "Read it."  || "productions_explanation_exactly_for_ended_by_person"
    }

    def "only an open model production may have no try number"() {
        when:
        def refusedBy = attempt("productions", insertInto("productions", aTry(step, producer) + [
                try_number: "null"] + (ended ? [:] : [ended_at: "null", ended_by: "null", explanation: "null"])))

        then:
        refusedBy == refusal

        where:
        step          | producer | ended || refusal
        RUN_STEP      | "model"  | false || null
        RUN_STEP      | "person" | false || "productions_try_number_absent_only_for_open_model"
        RUN_STEP      | "person" | true  || "productions_try_number_absent_only_for_open_model"
        CODE_RUN_STEP | "code"   | false || "productions_try_number_absent_only_for_open_model"
        CODE_RUN_STEP | "code"   | true  || "productions_try_number_absent_only_for_open_model"
    }

    def "a completed model production retains its try number"() {
        when:
        def refusedBy = attempt("productions", aModelTryWithItsCall("came_back") + endTheModelTry("came_back", null) +
                "update app.productions set try_number = null where production_id = '${SPARE_PRODUCTION}'")

        then:
        refusedBy == "productions_try_number_absent_only_for_open_model"
    }

    def "an unnumbered model production prevents another production from taking its place"() {
        given:
        def pending = insertInto("productions", aTry(RUN_STEP, "model") + [try_number: "null"]) + "; "

        when:
        def refusedBy = attempt("productions", pending + insertInto("productions", aTry(RUN_STEP, producer) + [
                try_number: numbered ? "3" : "null", ended_at: "null", ended_by: "null", explanation: "null"]))

        then:
        refusedBy == "productions_one_unended"

        where:
        producer | numbered
        "model"  | false
        "model"  | true
        "person" | true
    }

    def "a step has one try waiting at most, whoever produces it, and one answered lets the next wait"() {
        when:
        def refusedBy = attempt("productions", first + insertInto("productions", aTry(step, producer) + [
                explanation: "null", ended_at: "null", ended_by: "null"]))

        then:
        refusedBy == refusal

        where:
        first                  | step              | producer || refusal
        ""                     | REVIEWED_RUN_STEP | "person" || "productions_one_unended"
        ""                     | REVIEWED_RUN_STEP | "model"  || "productions_one_unended"
        ANSWER_THE_WAITING_TRY | REVIEWED_RUN_STEP | "model"  || null
        ""                     | RUN_STEP          | "person" || null
    }

    def "a model's try ends as its call ended, and names that call once it has"() {
        when:
        def refusedBy = attempt("productions", aModelTryWithItsCall(called) + endTheModelTry(named, lost))

        then:
        refusedBy == refusal

        where:
        called              | named               | lost                || refusal
        "came_back"         | "came_back"         | null                || null
        "came_back"         | "came_back"         | "did_not_fit"       || null
        "errored"           | "errored"           | "errored"           || null
        "nothing_came_back" | "nothing_came_back" | "nothing_came_back" || null
        "errored"           | "errored"           | null                || "productions_lost_as_call_ended"
        "came_back"         | "came_back"         | "nothing_came_back" || "productions_lost_as_call_ended"
        "nothing_came_back" | "nothing_came_back" | "errored"           || "productions_lost_as_call_ended"
        "turned_away"       | "turned_away"       | null                || "productions_lost_as_call_ended"
        "errored"           | "came_back"         | null                || "productions_model_call_fk"
        null                | "came_back"         | null                || "productions_model_call_fk"
    }

    def "a model's try whose answer is not kept as it came back ends as one that did not fit"() {
        when:
        // DB-SPECIFIC: now() uses PostgreSQL's syntax and semantics.
        def refusedBy = attempt("productions", aModelTryWithItsCall("came_back") +
                "update app.model_calls set answer_altered = ${altered} where model_call_id = '${SPARE_CALL}'; " +
                "update app.productions set ended_at = now(), ended_by = '${WORKFLOW_RUNNER}'," +
                " model_call_id = '${SPARE_CALL}', model_call_outcome = 'came_back', call_answer_altered = ${carried}," +
                " lost_reason = ${literal(lost)}," +
                " did_not_fit_reason = ${literal(reason)} where production_id = '${SPARE_PRODUCTION}'")

        then:
        refusedBy == refusal

        where:
        altered | carried | lost          | reason                || refusal
        "true"  | "true"  | "did_not_fit" | "not_the_shape"       || null
        "true"  | "true"  | "did_not_fit" | "not_kept_as_it_came" || null
        "true"  | "true"  | "did_not_fit" | "cut_off"             || null
        "true"  | "true"  | "did_not_fit" | null                  || "productions_did_not_fit_reason_exactly_for_model_did_not_fit"
        "true"  | "true"  | null          | null                  || "productions_altered_answer_did_not_fit"
        "true"  | "false" | "did_not_fit" | "not_the_shape"       || "productions_model_call_fk"
        "false" | "true"  | "did_not_fit" | "not_the_shape"       || "productions_model_call_fk"
    }

    def "a try is lost only by a model or by code, and only once it has ended"() {
        when:
        def refusedBy = attempt("productions", insertInto("productions", aTry(step, producer) + [
                lost_reason: literal(lost), lost_detail: literal(detail), returned_by_code: literal(returned),
                did_not_fit_reason: literal(reason)] +
                (ended ? [:] : [ended_at: "null", ended_by: "null"])))

        then:
        refusedBy == refusal

        where:
        step          | producer | ended | lost                | detail      | returned | reason          || refusal
        CODE_RUN_STEP | "code"   | true  | "errored"           | "It broke." | null     | null            || null
        CODE_RUN_STEP | "code"   | true  | "errored"           | "It broke." | "{}"     | null            || "productions_returned_by_code_only_for_code_errored"
        CODE_RUN_STEP | "code"   | true  | "nothing_came_back" | null        | null     | null            || null
        CODE_RUN_STEP | "code"   | true  | "did_not_fit"       | null        | "{}"     | null            || "productions_did_not_fit_only_for_model"
        CODE_RUN_STEP | "code"   | true  | "errored"           | "It broke." | null     | "not_the_shape" || "productions_did_not_fit_reason_exactly_for_model_did_not_fit"
        CODE_RUN_STEP | "code"   | false | "nothing_came_back" | null        | null     | null            || "productions_lost_only_for_ended"
        RUN_STEP      | "person" | true  | "nothing_came_back" | null        | null     | null            || "productions_lost_only_for_model_or_code"
    }

    def "a try says what went wrong, and that it was cut, only where code went wrong"() {
        when:
        def refusedBy = attempt("productions", insertInto("productions", aTry(CODE_RUN_STEP, "code") + [
                lost_reason: literal(lost), lost_detail: literal(detail), lost_detail_truncated: truncated]))

        then:
        refusedBy == refusal

        where:
        lost                | detail      | truncated || refusal
        "errored"           | "It broke." | "false"   || null
        "errored"           | "It broke." | "true"    || null
        "errored"           | null        | "false"   || "productions_lost_detail_exactly_where_code_threw"
        "nothing_came_back" | "It broke." | "false"   || "productions_lost_detail_exactly_where_code_threw"
        null                | null        | "true"    || "productions_truncated_only_for_lost_detail"
    }

    /** Counted as the store counts characters, a character outside the basic plane being one, as the code counts it. */
    def "what went wrong and what code gave back are bounded in characters, one past each bound refused"() {
        when:
        def refusedBy = attempt("productions", insertInto("productions", aTry(CODE_RUN_STEP, "code") + [
                lost_reason: "'errored'",
                // DB-SPECIFIC: repeat() and chr() use PostgreSQL's syntax and semantics.
                lost_detail: returnedCount == null ? "repeat(chr(128512), ${detailCount})" : "null",
                code_error_reason: returnedCount == null ? "null" : "'too_long'",
                code_error_path: returnedCount == null ? "null" : "'receipt'",
                // DB-SPECIFIC: repeat() and chr() use PostgreSQL's syntax and semantics.
                returned_by_code: returnedCount == null ? "null" : "repeat(chr(128512), ${returnedCount})"]))

        then:
        refusedBy == refusal

        where:
        detailCount | returnedCount || refusal
        2048        | null          || null
        2049        | null          || "productions_lost_detail_bounded"
        null        | 8388608       || null
        null        | 8388609       || "productions_returned_by_code_bounded"
    }

    def "a model's try says nothing of what went wrong, which its call says"() {
        when:
        def refusedBy = attempt("productions", aModelTryWithItsCall("errored") + endTheModelTry("errored", "errored") +
                "update app.productions set lost_detail = ${literal(detail)} where production_id = '${SPARE_PRODUCTION}'")

        then:
        refusedBy == refusal

        where:
        detail      || refusal
        null        || null
        "It broke." || "productions_lost_detail_exactly_where_code_threw"
    }

    def "what code gave back is kept only beside code gone wrong, and code that threw keeps nothing"() {
        when:
        def refusedBy = attempt("productions", insertInto("productions", aTry(CODE_RUN_STEP, "code") + [
                lost_reason: literal(lost), lost_detail: literal(detail), returned_by_code: literal(returned)]))

        then:
        refusedBy == refusal

        where:
        lost                | detail      | returned            || refusal
        "errored"           | "It broke." | "{\"total\": 9999}" || "productions_returned_by_code_only_for_code_errored"
        "errored"           | "It broke." | null                || null
        "nothing_came_back" | null        | "{\"total\": 9999}" || "productions_returned_by_code_only_for_code_errored"
        null                | null        | "{\"total\": 9999}" || "productions_returned_by_code_only_for_code_errored"
    }

    def "code gone wrong says why once: as this system's reason, or where it threw, in its own words"() {
        when:
        def refusedBy = attempt("productions", insertInto("productions", aTry(CODE_RUN_STEP, "code") + [
                lost_reason: literal(lost), code_error_reason: literal(reason), code_error_path: literal(path),
                lost_detail: literal(detail), returned_by_code: literal(returned)]))

        then:
        refusedBy == refusal

        where:
        lost                | reason         | path      | detail      | returned            || refusal
        "errored"           | "too_long"     | "receipt" | null        | "{\"total\": 9999}" || null
        "errored"           | "gave_nothing" | null      | null        | null                || null
        "errored"           | "said_nothing" | null      | null        | null                || null
        "errored"           | null           | null      | "It broke." | null                || null
        "errored"           | "said_nothing" | null      | "It broke." | null                || "productions_lost_detail_exactly_where_code_threw"
        "errored"           | "too_long"     | "receipt" | "It broke." | null                || "productions_lost_detail_exactly_where_code_threw"
        "errored"           | null           | null      | null        | null                || "productions_lost_detail_exactly_where_code_threw"
        "nothing_came_back" | "gave_nothing" | null      | null        | null                || "productions_code_error_reason_only_for_code_errored"
        null                | "gave_nothing" | null      | null        | null                || "productions_code_error_reason_only_for_code_errored"
    }

    def "a model's try carries no reason code went wrong for, what went wrong with it being on its call"() {
        when:
        def refusedBy = attempt("productions", aModelTryWithItsCall("errored") + endTheModelTry("errored", "errored") +
                "update app.productions set code_error_reason = ${literal(reason)}" +
                " where production_id = '${SPARE_PRODUCTION}'")

        then:
        refusedBy == refusal

        where:
        reason                || refusal
        null                  || null
        "failed_on_this_side" || "productions_code_error_reason_only_for_code_errored"
    }

    def "every reason code goes wrong for is about a field, about none, or about a member either way"() {
        expect:
        // DB-SPECIFIC: PostgreSQL enum_range reads native code_error_reason labels; the SELECT-list
        // set-returning call expands one row per label, and ::text casts it to text.
        queryForStrings("select unnest(enum_range(null::app.code_error_reason))::text") ==
                (ABOUT_A_FIELD + ABOUT_NO_FIELD + ["not_declared"]) as Set

        and: "each in one list only, so none is judged twice or both ways"
        (ABOUT_A_FIELD + ABOUT_NO_FIELD).toSet().size() == ABOUT_A_FIELD.size() + ABOUT_NO_FIELD.size()
        !(ABOUT_A_FIELD + ABOUT_NO_FIELD).contains("not_declared")
    }

    /** What reads the field is named for the one reason that needs it, so it is not what refuses the row here. */
    def "#reason names the field it is about exactly where it is about one"() {
        when:
        def refusedBy = attempt("productions", insertInto("productions", A_CODE_FAULT + [
                code_error_reason: literal(reason), code_error_path: literal(path),
                code_error_read_by_step: literal(reason == "gives_otherwise" ? REVIEWED_CODE : null)]))

        then:
        refusedBy == refusal

        where:
        [reason, path] << [ABOUT_A_FIELD + ABOUT_NO_FIELD, ["lines.sku", null]].combinations()
        refusal = (path != null) == (reason in ABOUT_A_FIELD) ? null : "productions_code_error_path_as_its_reason_names"
    }

    def "what reads a field that no longer matches is named, once, exactly where that is why code went wrong"() {
        when:
        def refusedBy = attempt("productions", insertInto("productions", A_CODE_FAULT + [
                code_error_reason: literal(reason), code_error_path: literal(path),
                code_error_read_by_step: literal(step), code_error_read_by_output: literal(output)]))

        then:
        refusedBy == refusal

        where:
        reason                | path      | step          | output    || refusal
        "gives_otherwise"     | "receipt" | REVIEWED_CODE | null      || null
        "gives_otherwise"     | "receipt" | null          | "result"  || null
        "gives_otherwise"     | "receipt" | null          | null      || "productions_code_error_read_by_exactly_for_gives_otherwise"
        "gives_otherwise"     | "receipt" | REVIEWED_CODE | "result"  || "productions_code_error_read_by_exactly_for_gives_otherwise"
        "takes_otherwise"     | "receipt" | REVIEWED_CODE | null      || "productions_code_error_read_by_exactly_for_gives_otherwise"
        "gives_a_list_not_here" | "grade" | null          | "result"  || "productions_code_error_read_by_exactly_for_gives_otherwise"
        "failed_on_this_side" | null      | null          | "result"  || "productions_code_error_read_by_exactly_for_gives_otherwise"
        "gives_otherwise"     | "receipt" | ABSENT        | null      || "productions_code_error_read_by_step_fk"
    }

    /** At the first level nothing holds it, so the field it names is none. */
    def "a member nothing declares is named exactly where that is the reason, wherever it is held"() {
        when:
        def refusedBy = attempt("productions", insertInto("productions", A_CODE_FAULT + [
                code_error_reason: literal(reason), code_error_path: literal(path), code_error_member: literal(member)]))

        then:
        refusedBy == refusal

        where:
        reason         | path      | member  || refusal
        "not_declared" | null      | "extra" || null
        "not_declared" | "lines"   | "extra" || null
        "not_declared" | "lines"   | ""      || null
        "not_declared" | null      | null    || "productions_code_error_member_exactly_for_not_declared"
        "too_long"     | "receipt" | "extra" || "productions_code_error_member_exactly_for_not_declared"
        "gave_nothing" | null      | ""      || "productions_code_error_member_exactly_for_not_declared"
    }

    def "#column is a path of field names"() {
        when:
        def refusedBy = attempt("productions", insertInto("productions", A_CODE_FAULT_READ + [
                code_error_read_by_step: "null", code_error_read_by_output: "'result'", (column): literal(path)]))

        then:
        refusedBy == (holds ? null : shape)

        where:
        [column, shape, path, holds] << [PATHS_IN_PRODUCTIONS.collect { it.take(2) }, PATHS].combinations()
                .collect { it.flatten() }
    }

    /** Counted as the store counts characters; the longest held is the longest path any declaration holds. */
    def "#column is at most 1023 characters, one past refused"() {
        when:
        def refusedBy = attempt("productions", insertInto("productions", A_CODE_FAULT_READ + [
                code_error_read_by_step: "null", code_error_read_by_output: "'result'", (column): written]))

        then:
        refusedBy == (held ? null : bound)
        // DB-SPECIFIC: repeat() uses PostgreSQL's syntax and semantics.

        where:
        [column, bound, written, held] << [PATHS_IN_PRODUCTIONS.collect { [it[0], it[2]] },
                                           [["'a' || repeat('.a', 511)", true], ["'ab' || repeat('.a', 511)", false]]]
                .combinations().collect { it.flatten() }
    }

    /** The store holds the line to no control, a tab and a line feed among them; the rest one line refuses, cleaning takes out. */
    def "a member's name holds no control character, and at most 64 characters"() {
        when:
        def refusedBy = attempt("productions", insertInto("productions", A_CODE_FAULT + [
                code_error_reason: "'not_declared'", code_error_member: member]))

        then:
        refusedBy == refusal
        // DB-SPECIFIC: repeat() and chr() use PostgreSQL's syntax and semantics.

        where:
        member                             || refusal
        "repeat('a', 64)"                  || null
        "repeat(chr(128512), 64)"          || null
        "repeat('a', 63) || chr(8230)"     || null
        "'a' || chr(8203) || 'b'"          || null
        "'a b'"                            || null
        "repeat('a', 65)"                  || "productions_code_error_member_bounded"
        "repeat(chr(128512), 65)"          || "productions_code_error_member_bounded"
        "'a' || chr(9) || 'b'"             || "productions_code_error_member_one_line"
        "'a' || chr(10) || 'b'"            || "productions_code_error_member_one_line"
        "'a' || chr(127) || 'b'"           || "productions_code_error_member_one_line"
        "'a' || chr(133) || 'b'"           || "productions_code_error_member_one_line"
    }

    /** Counted as the store counts characters, a character outside the basic plane being one. */
    def "a member's name is said to be cut only where one is named, and only at 64 characters"() {
        when:
        def refusedBy = attempt("productions", insertInto("productions", A_CODE_FAULT + [
                code_error_reason: literal(reason), code_error_path: literal(path), code_error_member: member,
                code_error_member_truncated: truncated]))

        then:
        refusedBy == refusal
        // DB-SPECIFIC: repeat() and chr() use PostgreSQL's syntax and semantics.

        where:
        reason         | path      | member                    | truncated || refusal
        "not_declared" | null      | "repeat('a', 64)"         | "true"    || null
        "not_declared" | null      | "repeat(chr(128512), 64)" | "true"    || null
        "not_declared" | null      | "repeat('a', 64)"         | "false"   || null
        "not_declared" | null      | "repeat('a', 63)"         | "true"    || "productions_code_error_member_truncated_at_bound"
        "not_declared" | null      | "''"                      | "true"    || "productions_code_error_member_truncated_at_bound"
        "too_long"     | "receipt" | "null"                    | "true"    || "productions_truncated_only_for_code_error_member"
        "gave_nothing" | null      | "null"                    | "true"    || "productions_truncated_only_for_code_error_member"
    }

    def "a person's try names an answered question to the helper asked in its own tree, whoever asked it"() {
        when:
        def refusedBy = attempt("productions", first + insertInto("productions", aTry(step, "person") + [
                run_help_exchange_id: literal(exchange)]))

        then:
        refusedBy == refusal

        where:
        first                 | step          | exchange       || refusal
        ""                    | CODE_RUN_STEP | HELP           || null
        ""                    | RUN_STEP      | HELP           || "productions_help_exchange_fk"
        ASK_WITHOUT_AN_ANSWER | CODE_RUN_STEP | SPARE_EXCHANGE || "productions_help_exchange_fk"
    }

    def "only a person's try names a question to the helper"() {
        when:
        def refusedBy = attempt("productions", insertInto("productions", aTry(CODE_RUN_STEP, producer) + [
                run_help_exchange_id: literal(HELP)]))

        then:
        refusedBy == refusal

        where:
        producer || refusal
        "person" || null
        "code"   || "productions_help_exchange_only_for_person"
    }
}
