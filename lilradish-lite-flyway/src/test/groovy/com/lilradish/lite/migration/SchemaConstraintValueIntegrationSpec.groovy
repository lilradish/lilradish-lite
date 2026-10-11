package com.lilradish.lite.migration

import static com.lilradish.lite.migration.fixture.ProductionAndCallCases.*
import static com.lilradish.lite.migration.fixture.SchemaKeys.*
import static com.lilradish.lite.migration.fixture.SchemaRows.*

import com.lilradish.lite.migration.fixture.SchemaConstraintSpecification

/** What a value a try made, and what each try and run was given, are held to. */
class SchemaConstraintValueIntegrationSpec extends SchemaConstraintSpecification {

    def "a question's value is of a field its pinned version gives back that nothing holds, standing as that field does"() {
        when:
        def refusedBy = attempt("production_values", insertInto("production_values", A_VALUE + [
                production_id: literal(production), declaration_field_id: literal(field), field_standing: literal(standing),
                standing_threshold: literal(threshold)]))

        then:
        refusedBy == refusal

        where:
        production      | field  | standing           | threshold || refusal
        ANSWERED        | ANSWER | "above_confidence" | 70        || null
        REVIEWED_ANSWER | PARENT | "never"            | null      || null
        REVIEWED_ANSWER | FIELD  | "never"            | null      || "production_values_field_fk"
        REVIEWED_ANSWER | PARENT | "always"           | null      || "production_values_field_fk"
        ANSWERED        | ANSWER | "above_confidence" | 99        || "production_values_threshold_fk"
    }

    def "a code step's value names its field, and stands as the release declared"() {
        when:
        def refusedBy = attempt("production_values", insertInto("production_values", A_CODE_VALUE + [
                declaration_field_id: literal(field), field_standing: literal(standing),
                standing_threshold: literal(threshold)]))

        then:
        refusedBy == refusal

        where:
        field  | standing           | threshold || refusal
        null   | "always"           | null      || null
        null   | "never"            | null      || null
        null   | "above_confidence" | 50        || null
        null   | "never"            | 50        || "production_values_threshold_exactly_for_above_confidence"
        ANSWER | "always"           | null      || "production_values_field_exactly_for_question"
    }

    def "a value is of a try that gave something back"() {
        when:
        def refusedBy = attempt("production_values", insertInto("production_values", value +
                [production_id: literal(production)]))

        then:
        refusedBy == refusal

        where:
        value        | production      || refusal
        A_VALUE      | ANSWERED        || null
        A_CODE_VALUE | CODE_PRODUCTION || null
        A_VALUE      | PENDING         || "production_values_production_fk"
        A_CODE_VALUE | CODE_FAILED     || "production_values_production_fk"
    }

    def "a value is at most 50331648 characters as the store writes it"() {
        when:
        def refusedBy = attempt("production_values", insertInto("production_values", A_VALUE + [value: value]))

        then:
        refusedBy == refusal
        // DB-SPECIFIC: to_jsonb(), repeat() and chr() use PostgreSQL's syntax and semantics.

        where:
        value                                           || refusal
        "to_jsonb(repeat(chr(1), 8388607) || 'aaaa')"  || null
        "to_jsonb(repeat(chr(1), 8388607) || 'aaaaa')" || "production_values_value_bounded"
    }

    def "a model says how sure it is exactly where a value stands above a confidence, and a person and code never do"() {
        when:
        def refusedBy = attempt("production_values", first + insertInto("production_values", value + [
                production_id: literal(production), producer: literal(producer), declaration_field_id: literal(field),
                field_standing: literal(standing), standing_threshold: literal(threshold), confidence: literal(confidence)]))

        then:
        refusedBy == refusal

        where:
        first           | value        | production      | producer | field  | standing           | threshold | confidence || refusal
        DROP_SURE_VALUE | A_VALUE      | PRODUCTION      | "model"  | ANSWER | "above_confidence" | 70        | 80         || null
        ""              | A_VALUE      | REVIEWED_ANSWER | "person" | ANSWER | "above_confidence" | 70        | null       || null
        ""              | A_CODE_VALUE | CODE_PRODUCTION | "code"   | null   | "above_confidence" | 50        | null       || null
        DROP_SURE_VALUE | A_VALUE      | PRODUCTION      | "model"  | ANSWER | "above_confidence" | 70        | null       || "production_values_confidence_exactly_for_model_above_confidence"
        ""              | A_VALUE      | PRODUCTION      | "model"  | PARENT | "never"            | null      | 90         || "production_values_confidence_exactly_for_model_above_confidence"
        ""              | A_VALUE      | REVIEWED_ANSWER | "person" | ANSWER | "above_confidence" | 70        | 80         || "production_values_confidence_exactly_for_model_above_confidence"
        ""              | A_CODE_VALUE | CODE_PRODUCTION | "code"   | null   | "above_confidence" | 50        | 80         || "production_values_confidence_exactly_for_model_above_confidence"
    }

    /** At the threshold is enough; a person offers no confidence, and code none either. */
    def "a value needs a review where it stands never, or above a confidence it did not reach or did not offer"() {
        when:
        def needs = readInside(first + insertInto("production_values", value + [
                production_value_id: literal(SPARE_VALUE), production_id: literal(production), producer: literal(producer),
                declaration_field_id: literal(field), field_standing: literal(standing),
                standing_threshold: literal(threshold), confidence: literal(confidence)]),
                // DB-SPECIFIC: :: and text use PostgreSQL's syntax and semantics.
                "select needs_review::text from app.production_values where production_value_id = '${SPARE_VALUE}'")

        then:
        needs == needsReview.toString()

        where:
        first           | value        | production      | producer | field  | standing           | threshold | confidence || needsReview
        DROP_SURE_VALUE | A_VALUE      | PRODUCTION      | "model"  | ANSWER | "above_confidence" | 70        | 80         || false
        DROP_SURE_VALUE | A_VALUE      | PRODUCTION      | "model"  | ANSWER | "above_confidence" | 70        | 70         || false
        DROP_SURE_VALUE | A_VALUE      | PRODUCTION      | "model"  | ANSWER | "above_confidence" | 70        | 69         || true
        ""              | A_VALUE      | REVIEWED_ANSWER | "person" | ANSWER | "above_confidence" | 70        | null       || true
        ""              | A_VALUE      | REVIEWED_ANSWER | "person" | PARENT | "never"            | null      | null       || true
        ""              | A_CODE_VALUE | CODE_PRODUCTION | "code"   | null   | "always"           | null      | null       || false
        ""              | A_CODE_VALUE | CODE_PRODUCTION | "code"   | null   | "never"            | null      | null       || true
        ""              | A_CODE_VALUE | CODE_PRODUCTION | "code"   | null   | "above_confidence" | 50        | null       || true
    }

    def "a value used is recorded against one try, or one run a route started, and never both or neither"() {
        when:
        def refusedBy = attempt("production_inputs", first + insertInto("production_inputs", use + [
                production_id: literal(production), branch_run_id: literal(branchRun)]))

        then:
        refusedBy == refusal

        where:
        first                 | use          | production      | branchRun || refusal
        ""                    | A_USE        | REVIEWED_ANSWER | null      || null
        "" | A_BRANCH_USE | null            | SUB_RUN   || null
        ""                    | A_USE        | null            | null      || "production_inputs_exactly_one_owner"
        "" | A_BRANCH_USE | REVIEWED_ANSWER | SUB_RUN   || "production_inputs_exactly_one_owner"
    }

    def "a try records only what its own step was given, in the run that step is in, and never a discriminator"() {
        when:
        def refusedBy = attempt("production_inputs", insertInto("production_inputs", AN_INPUT_USE + [
                production_id: literal(production), run_step_id: literal(runStep), workflow_step_id: literal(step),
                binding_id: literal(binding)]))

        then:
        refusedBy == refusal

        where:
        production      | runStep           | step     | binding       || refusal
        ANSWERED        | RUN_STEP          | STEP     | BINDING       || null
        REVIEWED_ANSWER | REVIEWED_RUN_STEP | REVIEWED | BINDING       || "production_inputs_binding_fk"
        ANSWERED        | RUN_STEP          | STEP     | DISCRIMINATOR || "production_inputs_binding_fk"
        REVIEWED_ANSWER | RUN_STEP          | STEP     | BINDING       || "production_inputs_try_fk"
        REVIEWED_ANSWER | REVIEWED_RUN_STEP | STEP     | BINDING       || "production_inputs_step_fk"
    }

    def "a run a route started records the value it was chosen by, through that route's discriminator alone"() {
        when:
        def refusedBy = attempt("production_inputs", first + insertInto("production_inputs", A_BRANCH_USE + [
                branch_run_id: literal(branchRun), binding_id: literal(binding)]))

        then:
        refusedBy == refusal

        where:
        first                                              | branchRun | binding       || refusal
        ""                              | SUB_RUN   | DISCRIMINATOR || null
        BIND_AN_INPUT_OF_THE_ROUTE | SUB_RUN   | SPARE_BINDING || "production_inputs_binding_fk"
        START_A_RUN_BENEATH                                | SPARE_RUN | DISCRIMINATOR || "production_inputs_branch_run_fk"
        ""                                                 | OTHER_RUN | DISCRIMINATOR || "production_inputs_branch_run_fk"
    }

    def "a binding reading a step names the value it made, one reading the input may name none, and a constant none"() {
        when:
        def refusedBy = attempt("production_inputs", insertInto("production_inputs", use + [
                source_workflow_step_id: literal(sourceStep), source_run_step_id: literal(sourceRunStep),
                source_run_step_kind: literal(sourceKind), source_production_id: literal(production),
                source_production_value_id: literal(value)]))

        then:
        refusedBy == refusal

        where:
        use                             | sourceStep | sourceRunStep | sourceKind | production | value || refusal
        A_USE                           | STEP       | RUN_STEP      | "question" | PRODUCTION | VALUE || null
        AN_INPUT_USE                    | null       | null          | null       | null       | null  || null
        THE_CONSTANT_GIVEN_TO_THE_MODEL | null       | null          | null       | null       | null  || null
        A_USE                           | null       | null          | null       | null       | null  || "production_inputs_binding_fk"
        A_USE                           | STEP       | RUN_STEP      | "question" | null       | null  || "production_inputs_source_value_for_a_step"
        THE_CONSTANT_GIVEN_TO_THE_MODEL | null       | null          | null       | PRODUCTION | VALUE || "production_inputs_no_source_value_for_a_constant"
    }

    /** Only a run beneath another is started with what a step made; which run of its tree made it no key here says. */
    def "a run's input carries a value made in its own tree, and a run at the top is started with none"() {
        when:
        def refusedBy = attempt("production_inputs", first + insertInto("production_inputs", use + [
                source_production_id: literal(production), source_production_value_id: literal(value)]))

        then:
        refusedBy == refusal

        where:
        first              | use           | production      | value      || refusal
        WITH_A_TRY_BENEATH | A_USE_BENEATH | PRODUCTION      | VALUE      || null
        WITH_A_TRY_BENEATH | A_USE_BENEATH | null            | null       || null
        WITH_A_TRY_BENEATH | A_USE_BENEATH | CODE_PRODUCTION | CODE_VALUE || "production_inputs_source_root_fk"
        ""                 | AN_INPUT_USE  | null            | null       || null
        ""                 | AN_INPUT_USE  | PRODUCTION      | VALUE      || "production_inputs_no_source_value_for_a_top_level_input"
        ""                 | A_USE         | PRODUCTION      | VALUE      || null
    }

    def "a run's input is recorded against the tree the run is in"() {
        when:
        def refusedBy = attempt("production_inputs", insertInto("production_inputs", AN_INPUT_USE + [
                root_run_id: literal(root)]))

        then:
        refusedBy == refusal

        where:
        root      || refusal
        RUN       || null
        OTHER_RUN || "production_inputs_run_fk"
        SUB_RUN   || "production_inputs_run_fk"
    }

    def "a value a question or code step made is traced to a try of that step, in the run the value was used in"() {
        when:
        def refusedBy = attempt("production_inputs", insertInto("production_inputs", A_USE + [
                binding_id: literal(binding), source_workflow_step_id: literal(sourceStep),
                source_production_id: literal(production), source_production_value_id: literal(value)]))

        then:
        refusedBy == refusal

        where:
        binding           | sourceStep | production      | value          || refusal
        REVIEWED_BINDING  | STEP       | PRODUCTION      | VALUE          || null
        REVIEWED_BINDING  | STEP       | ANSWERED        | ANSWERED_VALUE || null
        REVIEWED_BINDING  | STEP       | PRODUCTION      | ANSWERED_VALUE || "production_inputs_source_value_fk"
        REVIEWED_BINDING  | STEP       | CODE_PRODUCTION | CODE_VALUE     || "production_inputs_source_production_fk"
        ESCALATED_BINDING | STEP       | PRODUCTION      | VALUE          || "production_inputs_source_step_fk"
        ESCALATED_BINDING | ESCALATE   | PRODUCTION      | VALUE          || "production_inputs_source_run_step_fk"
    }

    /** Which run beneath made the value no key here says, only that it is of the tree the value was used in. */
    def "a value a workflow or route gave back is traced beneath it within the tree, or to none at all"() {
        when:
        def refusedBy = attempt("production_inputs", first + insertInto("production_inputs", A_USE + [
                binding_id: literal(binding), source_workflow_step_id: literal(sourceStep),
                source_run_step_id: literal(sourceRunStep), source_run_step_kind: literal(sourceKind),
                source_production_id: literal(production), source_production_value_id: literal(value)]))

        then:
        refusedBy == refusal

        where:
        first                            | binding           | sourceStep | sourceRunStep     | sourceKind | production       | value       || refusal
        WITH_A_VALUE_ESCALATE_GAVE_BACK  | ESCALATED_BINDING | ESCALATE   | ESCALATE_RUN_STEP | "workflow" | SPARE_PRODUCTION | SPARE_VALUE || null
        WITH_A_VALUE_THE_ROUTE_GAVE_BACK | SPARE_BINDING     | ROUTE      | ROUTE_RUN_STEP    | "route"    | SPARE_PRODUCTION | SPARE_VALUE || null
        ""                               | ESCALATED_BINDING | ESCALATE   | ESCALATE_RUN_STEP | "workflow" | null             | null        || null
        ""                               | ESCALATED_BINDING | ESCALATE   | ESCALATE_RUN_STEP | "workflow" | CODE_PRODUCTION  | CODE_VALUE  || "production_inputs_source_root_fk"
        WITH_A_VALUE_THE_ROUTE_GAVE_BACK | SPARE_BINDING     | ROUTE      | ROUTE_RUN_STEP    | "route"    | CODE_PRODUCTION  | CODE_VALUE  || "production_inputs_source_root_fk"
        WITH_A_VALUE_ESCALATE_GAVE_BACK  | REVIEWED_BINDING  | STEP       | RUN_STEP          | "question" | SPARE_PRODUCTION | SPARE_VALUE || "production_inputs_source_production_fk"
    }

    def "one input may retain the same maker at two target paths and a third part with no maker"() {
        when:
        def parts = readInside(
                insertInto("production_input_parts", A_INPUT_PART + [target_path: "'customer.id'"]) + "; " +
                insertInto("production_input_parts", A_INPUT_PART + [target_path: "'customer.note'",
                        source_production_id: "null", source_production_value_id: "null"]),
                // DB-SPECIFIC: ::, string_agg() and text use PostgreSQL's syntax and semantics.
                "select string_agg(target_path || ':' || coalesce(source_production_value_id::text, '-')," +
                        " '|' order by target_path) from app.production_input_parts")

        then:
        parts == "customer.id:${VALUE}|customer.name:${VALUE}|customer.note:-" as String
        !parts.contains(CODE_VALUE)
    }

    def "a value is recorded once for each owner and binding, and the same binding again for another try"() {
        when:
        def refusedBy = attempt("production_inputs", first + insertInto("production_inputs", use))

        then:
        refusedBy == refusal

        where:
        first                 | use                             || refusal
        ""                    | THE_CONSTANT_GIVEN_TO_THE_MODEL || null
        "" | A_BRANCH_USE                    || null
        ""                    | A_CONSTANT_USE                  || "production_inputs_try_binding_unique"
        WITH_BRANCH_INPUT     | A_BRANCH_USE                    || "production_inputs_branch_binding_unique"
    }

    def "a recorded direct input or component cannot be deleted"() {
        when:
        def refusedBy = attempt(table, statement)

        then:
        refusedBy == rule

        where:
        table                    | statement                                                                                             || rule
        "production_inputs"      | "delete from app.production_inputs where production_input_id = '${INPUT}'"                       || "production_inputs_immutable"
        "production_input_parts" | "delete from app.production_input_parts where production_input_id = '${INPUT}' and target_path = 'customer.name'" || "production_input_parts_immutable"
    }

    def "a binding a value was recorded through keeps what it fills and what it reads from"() {
        when:
        def first = binding == DISCRIMINATOR ? WITH_BRANCH_INPUT : ""
        def refusedBy = attempt("bindings", first +
                "update app.bindings set ${assignments} where binding_id = '${binding}'")

        then:
        refusedBy == refusal

        where:
        binding          | assignments                                 || refusal
        BINDING          | "source_path = null, constant = '\"x\"'"    || null
        CONSTANT_BINDING | "constant = null, source_path = 'channel'"  || "production_inputs_binding_fk"
        DISCRIMINATOR    | "source_step_id = '${ESCALATE}'"            || "production_inputs_source_step_fk"
    }
}
