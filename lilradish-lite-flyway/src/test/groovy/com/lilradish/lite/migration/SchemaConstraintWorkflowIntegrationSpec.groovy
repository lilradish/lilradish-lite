package com.lilradish.lite.migration

import static com.lilradish.lite.migration.fixture.ProductionAndCallCases.*
import static com.lilradish.lite.migration.fixture.RunCases.*
import static com.lilradish.lite.migration.fixture.SchemaKeys.*
import static com.lilradish.lite.migration.fixture.SchemaRows.*
import static com.lilradish.lite.migration.fixture.StepAndDeclarationCases.*

import com.lilradish.lite.migration.fixture.SchemaConstraintSpecification

/** What a workflow version, its steps, cases, fields, bindings and the text and order they hold are held to. */
class SchemaConstraintWorkflowIntegrationSpec extends SchemaConstraintSpecification {

    /** Where a name is held to one shape: the table, how a row carrying one is written, and the rule refusing it. */
    // DB-SPECIFIC: these prequoted row variations input PostgreSQL native step_kind,
    // entry_kind and step_producer labels.
    static final List<List<String>> NAMED = [
        ["declaration_fields", setting("declaration_fields", "name = %s"), "declaration_fields_name_shape"],
        ["workflow_steps", setting("workflow_steps", "name = %s"), "workflow_steps_name_shape"],
        ["workflow_steps", insertInto("workflow_steps", A_STEP + [kind: "'entry'", pinned_version_id: "'${VERSION}'",
                pinned_kind: "'question'", producer: "'model'", producer_mode: "'ordinary'", producer_model: "%s"]),
         "workflow_steps_producer_model_shape"],
        ["workflow_steps", insertInto("workflow_steps", A_STEP + [kind: "'entry'", pinned_version_id: "'${VERSION}'",
                pinned_kind: "'question'", reviewer_mode: "'ordinary'", reviewer_model: "%s"]),
         "workflow_steps_reviewer_model_shape"],
        ["workflow_steps", insertInto("workflow_steps", A_STEP + [kind: "'entry'", pinned_version_id: "'${VERSION}'",
                pinned_kind: "'question'", producer: "'model'", producer_model: "'general'", producer_mode: "%s"]),
         "workflow_steps_producer_mode_shape"],
        ["workflow_steps", insertInto("workflow_steps", A_STEP + [kind: "'entry'", pinned_version_id: "'${VERSION}'",
                pinned_kind: "'question'", reviewer_model: "'general'", reviewer_mode: "%s"]),
         "workflow_steps_reviewer_mode_shape"],
        ["workflow_versions", setting("workflow_versions",
                "may_be_helped = true, helper_mode = 'ordinary', helper_model = %s"),
         "workflow_versions_helper_model_shape"],
        ["workflow_versions", setting("workflow_versions",
                "may_be_helped = true, helper_model = 'general', helper_mode = %s"),
         "workflow_versions_helper_mode_shape"],
        ["production_values", "update app.production_values set field_name = %s" +
                " where production_value_id = '${CODE_VALUE}'", "production_values_field_name_shape"],
    ]

    /**
     * Where a mode is carried under a key to the one a step or a version names: the table, how a row carrying one is
     * written, the rule refusing its shape, and the key refusing a name that is not the one named.
     */
    static final List<List<String>> MODE_CARRIED = [
        ["run_step_send_attempts", insertInto("run_step_send_attempts", A_SEND_ATTEMPT + [mode: "%s"]),
         "run_step_send_attempts_mode_shape", "run_step_send_attempts_producer_model_fk"],
        ["model_calls", insertInto("model_calls", A_HELP_CALL + [mode: "%s"]), "model_calls_mode_shape",
         "model_calls_helper_fk"],
    ]

    /** Each with whether it is a name; anything outside printable ASCII is written as a code point. */
    static final List<List<Object>> NAMES = [
        ["a", true],
        ["a" * 63, true],
        ["z9_", true],
        ["a" * 64, false],
        ["", false],
        ["A", false],
        ["aB", false],
        ["1a", false],
        ["_a", false],
        ["a-b", false],
        ["a b", false],
        ["a" + Character.toString(0x000A), false],
        [Character.toString(0x00E9), false],
        ["a" + Character.toString(0x0131), false],
        [Character.toString(0x212A), false],
        [Character.toString(0xFF41), false],
    ]

    static final List<List<String>> PATHS_IN_BINDINGS = [
        ["target_path = %s", "bindings_target_path_shape"],
        ["source_path = %s", "bindings_source_path_shape"],
    ]

    def "a step's optional display name is visible and bounded independently of its machine name"() {
        given:
        execute("update app.workflow_steps set label = 'Earlier display'")

        when:
        def refusedBy = attempt("workflow_steps", setting("workflow_steps", "label = ${literal(label)}"))

        then:
        refusedBy == refusal

        where:
        label                                  || refusal
        null                                   || null
        "Risk review"                          || null
        "リスク確認"                             || null
        "\u202ETriage"                         || null
        Character.toString(0x1F600).repeat(128) || null
        ""                                     || "workflow_steps_label_visible"
        "Risk\nreview"                         || "workflow_steps_label_visible"
        "a".repeat(129)                        || "workflow_steps_label_bounded"
        Character.toString(0x1F600).repeat(129) || "workflow_steps_label_bounded"
    }

    def "a ceiling is at least one, and left empty where there is none"() {
        when:
        def refusedBy = attempt("workflow_versions", setting("workflow_versions", "ceiling = ${ceiling}"))

        then:
        refusedBy == refusal

        where:
        ceiling               || refusal
        "1"                   || null
        "9223372036854775807" || null
        "null"                || null
        "0"                   || "workflow_versions_ceiling_positive"
    }

    def "a helper model and mode are named only where the version's runs may be helped, and may be named later"() {
        when:
        def refusedBy = attempt("workflow_versions", setting("workflow_versions",
                "may_be_helped = ${allowed}, helper_model = ${literal(model)}, helper_mode = ${literal(mode)}"))

        then:
        refusedBy == refusal

        where:
        allowed | model     | mode       || refusal
        true    | "general" | "ordinary" || null
        true    | null      | null       || null
        false   | "general" | "ordinary" || "workflow_versions_helper_only_for_may_be_helped"
    }

    def "a helper model is named with its mode"() {
        when:
        def refusedBy = attempt("workflow_versions", setting("workflow_versions",
                "may_be_helped = true, helper_model = ${literal(model)}, helper_mode = ${literal(mode)}"))

        then:
        refusedBy == refusal

        where:
        model     | mode       || refusal
        "general" | "ordinary" || null
        null      | null       || null
        "general" | null       || "workflow_versions_helper_model_together"
        null      | "research" || "workflow_versions_helper_model_together"
    }

    def "a step pins an entry or a search question, and names a code step only where it runs one"() {
        when:
        def refusedBy = attempt("workflow_steps", insertInto("workflow_steps", A_STEP + [kind: literal(kind),
                pinned_version_id: literal(pinned), pinned_kind: literal(pinnedKind), code_step: literal(codeStep),
                producer: literal(kind == "code_step" ? "code" : null)]))

        then:
        refusedBy == refusal

        where:
        kind        | pinned           | pinnedKind | codeStep  || refusal
        null        | null             | null       | null      || null
        "entry"     | null             | null       | null      || null
        "code_step" | null             | null       | null      || null
        "entry"     | VERSION          | "question" | null      || null
        "search"    | VERSION          | "question" | null      || null
        "search"    | null             | null       | null      || null
        "search"    | NEXT_WORKFLOW_VERSION | "workflow" | null    || "workflow_steps_search_pins_question"
        "entry"     | LIST_VERSION     | null       | null      || "workflow_steps_pinned_together"
        "code_step" | null             | null       | CODE_STEP || null
        "route"     | null             | null       | null      || null
        null        | VERSION          | "question" | null      || "workflow_steps_pinned_only_for_entry_or_search"
        "route"     | VERSION          | "question" | null      || "workflow_steps_pinned_only_for_entry_or_search"
        null        | null             | null       | CODE_STEP || "workflow_steps_code_step_only_for_code_step"
        "entry"     | null             | null       | CODE_STEP || "workflow_steps_code_step_only_for_code_step"
    }

    def "a step never pins the version it is in"() {
        when:
        def refusedBy = attempt("workflow_steps", insertInto("workflow_steps", A_STEP + [kind: "'entry'",
                entry_version_id: literal(version), pinned_version_id: literal(WORKFLOW_VERSION), pinned_kind: "'workflow'"]))

        then:
        refusedBy == refusal

        where:
        version               || refusal
        NEXT_WORKFLOW_VERSION || null
        WORKFLOW_VERSION      || "workflow_steps_not_own_version"
    }

    def "what a step runs decides who may produce its values, and a workflow or a route has nobody produce or review"() {
        when:
        def refusedBy = attempt("workflow_steps", insertInto("workflow_steps", A_STEP + [kind: literal(kind),
                pinned_version_id: literal(pinned), pinned_kind: literal(pinnedKind), code_step: literal(codeStep),
                producer: literal(producer), producer_model: literal(producerModel), producer_mode: literal(producerMode),
                tries: literal(tries), reviewer_model: literal(reviewerModel), reviewer_mode: literal(reviewerMode)]))

        then:
        refusedBy == refusal

        where:
        kind        | pinned                | pinnedKind | codeStep  | producer | tries | reviewerModel || refusal
        "entry"     | VERSION               | "question" | null      | "model"  | 3     | null          || null
        "entry"     | VERSION               | "question" | null      | "person" | 3     | "general"     || null
        "entry"     | VERSION               | "question" | null      | null     | null  | null          || null
        "entry"     | VERSION               | "question" | null      | "code"   | 3     | null          || "workflow_steps_question_produced_by_model_or_person"
        "code_step" | null                  | null       | CODE_STEP | "code"   | 3     | "general"     || null
        "code_step" | null                  | null       | CODE_STEP | null     | null  | null          || null
        "code_step" | null                  | null       | CODE_STEP | "person" | 3     | null          || null
        "code_step" | null                  | null       | CODE_STEP | "model"  | 3     | null          || "workflow_steps_code_step_produced_by_code_or_person"
        "entry"     | NEXT_WORKFLOW_VERSION | "workflow" | null      | null     | null  | null          || null
        "entry"     | NEXT_WORKFLOW_VERSION | "workflow" | null      | "person" | null  | null          || "workflow_steps_workflow_or_route_unproduced"
        "entry"     | NEXT_WORKFLOW_VERSION | "workflow" | null      | null     | 3     | null          || "workflow_steps_workflow_or_route_unproduced"
        "entry"     | NEXT_WORKFLOW_VERSION | "workflow" | null      | null     | null  | "general"     || "workflow_steps_workflow_or_route_unproduced"
        "route"     | null                  | null       | null      | null     | null  | null          || null
        "route"     | null                  | null       | null      | "model"  | null  | null          || "workflow_steps_workflow_or_route_unproduced"
        "route"     | null                  | null       | null      | null     | 1     | null          || "workflow_steps_workflow_or_route_unproduced"
        "route"     | null                  | null       | null      | null     | null  | "general"     || "workflow_steps_workflow_or_route_unproduced"
        null        | null                  | null       | null      | "code"   | 3     | null          || null

        producerModel = producer == "model" ? "general" : null
        producerMode = producer == "model" ? "ordinary" : null
        reviewerMode = reviewerModel == null ? null : "research"
    }

    def "a producing model and its mode are named together, and only where a model produces"() {
        when:
        def refusedBy = attempt("workflow_steps", insertInto("workflow_steps", A_STEP + [kind: "'entry'",
                pinned_version_id: literal(VERSION), pinned_kind: "'question'", producer: literal(producer),
                producer_model: literal(producerModel), producer_mode: literal(producerMode)]))

        then:
        refusedBy == refusal

        where:
        producer | producerModel | producerMode || refusal
        "model"  | "general"     | "ordinary"   || null
        "model"  | "general"     | "research"   || null
        "model"  | null          | null         || null
        "model"  | "general"     | null         || "workflow_steps_producer_model_together"
        "model"  | null          | "ordinary"   || "workflow_steps_producer_model_together"
        "person" | null          | null         || null
        "person" | "general"     | null         || "workflow_steps_producer_model_only_for_model"
        "person" | null          | "ordinary"   || "workflow_steps_producer_model_only_for_model"
        null     | "general"     | "ordinary"   || "workflow_steps_producer_model_only_for_model"
    }

    def "a step is told what happened only where a model produces"() {
        when:
        def refusedBy = attempt("workflow_steps", insertInto("workflow_steps", A_STEP + [kind: "'entry'",
                pinned_version_id: literal(VERSION), pinned_kind: "'question'", producer: literal(producer),
                tells_what_happened: literal(tells)]))

        then:
        refusedBy == refusal

        where:
        producer | tells || refusal
        "model"  | true  || null
        "model"  | false || null
        "person" | false || null
        null     | false || null
        "person" | true  || "workflow_steps_told_only_for_model"
        null     | true  || "workflow_steps_told_only_for_model"
    }

    def "a step's tries are at least one, and left empty until they are chosen"() {
        when:
        def refusedBy = attempt("workflow_steps", insertInto("workflow_steps", A_STEP + [kind: "'entry'",
                pinned_version_id: literal(VERSION), pinned_kind: "'question'", producer: "'person'", tries: tries]))

        then:
        refusedBy == refusal

        where:
        tries  || refusal
        "1"    || null
        "null" || null
        "-1"   || "workflow_steps_tries_positive"
    }

    def "a reviewing model is named with its mode, whoever produces"() {
        when:
        def refusedBy = attempt("workflow_steps", insertInto("workflow_steps", A_STEP + [kind: "'entry'",
                pinned_version_id: literal(VERSION), pinned_kind: "'question'", producer: literal(producer),
                producer_model: literal(producer == "model" ? "general" : null),
                producer_mode: literal(producer == "model" ? "ordinary" : null),
                reviewer_model: literal(reviewerModel), reviewer_mode: literal(reviewerMode)]))

        then:
        refusedBy == refusal

        where:
        producer | reviewerModel | reviewerMode || refusal
        "model"  | null          | null         || null
        "model"  | "general"     | "research"   || null
        "model"  | "thorough"    | "ordinary"   || null
        "person" | "general"     | "ordinary"   || null
        "person" | "general"     | null         || "workflow_steps_reviewer_model_together"
        "person" | null          | "ordinary"   || "workflow_steps_reviewer_model_together"
    }

    def "a field, a step, a model and a mode are named in lower-case English letters, digits and underscores"() {
        when:
        def refusedBy = attempt(table, String.format(statement, literal(name)))

        then:
        refusedBy == (holds ? null : rule)

        where:
        [table, statement, rule, name, holds] << [NAMED, NAMES].combinations().collect { it.flatten() }
    }

    /** No name here is the one named, so a name reaching the key is a name the shape let through. */
    def "a mode an attempt or a call carries is refused for its shape before the key naming the mode is asked"() {
        when:
        def refusedBy = attempt(table, String.format(statement, literal(name)))

        then:
        refusedBy == (holds ? key : rule)

        where:
        [table, statement, rule, key, name, holds] << [MODE_CARRIED, NAMES].combinations().collect { it.flatten() }
    }

    def "a case is in the version its route is, and is held by a route"() {
        when:
        def refusedBy = attempt("route_cases", insertInto("route_cases",
                A_CASE + [entry_version_id: literal(version), workflow_step_id: literal(step),
                          target_version_id: literal(target)]))

        then:
        refusedBy == refusal

        where:
        version               | step  | target                || refusal
        WORKFLOW_VERSION      | ROUTE | NEXT_WORKFLOW_VERSION || null
        WORKFLOW_VERSION      | ROUTE | null                  || null
        NEXT_WORKFLOW_VERSION | ROUTE | WORKFLOW_VERSION      || "route_cases_step_fk"
        WORKFLOW_VERSION      | STEP  | NEXT_WORKFLOW_VERSION || "route_cases_route_fk"
        WORKFLOW_VERSION      | ROUTE | WORKFLOW_VERSION      || "route_cases_not_own_version"
    }

    def "a route has one fallback at most, and any number of cases with a term beside it"() {
        when:
        def refusedBy = attempt("route_cases", insertInto("route_cases", A_CASE + [term: literal(term)]))

        then:
        refusedBy == refusal

        where:
        term       || refusal
        null       || "route_cases_one_fallback"
        "Delivery" || null
        "Billing"  || null
    }

    def "a field belongs to one question or workflow version, or to one route, and a route's only give back"() {
        when:
        def refusedBy = attempt("declaration_fields", insertInto("declaration_fields", A_FIELD + [
                entry_version_id: literal(version), entry_kind: literal(kind), workflow_step_id: literal(step),
                side: literal(side)]))

        then:
        refusedBy == refusal

        where:
        version          | kind             | step  | side    || refusal
        VERSION          | "question"       | null  | "takes" || null
        WORKFLOW_VERSION | "workflow"       | null  | "takes" || null
        null             | null             | ROUTE | "gives" || null
        VERSION          | "question"       | ROUTE | "gives" || "declaration_fields_exactly_one_owner"
        null             | null             | null  | "takes" || "declaration_fields_exactly_one_owner"
        null             | "question"       | ROUTE | "gives" || "declaration_fields_version_together"
        LIST_VERSION     | "reference_list" | null  | "takes" || "declaration_fields_version_is_question_or_workflow"
        null             | null             | ROUTE | "takes" || "declaration_fields_route_only_for_gives"
    }

    def "a field is held by a field of the same owner, on the same side, that holds fields"() {
        when:
        def refusedBy = attempt("declaration_fields", insertInto("declaration_fields", A_FIELD + [
                entry_version_id: literal(version), entry_kind: literal(kind), side: literal(side),
                parent_field_id: literal(parent)]))

        then:
        refusedBy == refusal

        where:
        version          | kind       | side    | parent || refusal
        VERSION          | "question" | "gives" | PARENT || null
        VERSION          | "question" | "takes" | PARENT || "declaration_fields_parent_fk"
        WORKFLOW_VERSION | "workflow" | "gives" | PARENT || "declaration_fields_parent_fk"
        VERSION          | "question" | "takes" | FIELD  || "declaration_fields_parent_fk"
    }

    def "a length and a count are at least one"() {
        when:
        def refusedBy = attempt("declaration_fields", insertInto("declaration_fields", A_FIELD + [
                holds_many: "true", text_limit: literal(textLimit), many_limit: literal(manyLimit)]))

        then:
        refusedBy == refusal

        where:
        textLimit | manyLimit || refusal
        1         | 1         || null
        0         | 1         || "declaration_fields_text_limit_positive"
        1         | 0         || "declaration_fields_many_limit_positive"
    }

    def "a length, a count and a list are declared only on the fields they belong to, and may wait to be"() {
        when:
        def refusedBy = attempt("declaration_fields", insertInto("declaration_fields", A_FIELD + [
                kind: literal(kind), holds_many: many, text_limit: literal(textLimit), many_limit: literal(manyLimit),
                term_list_version_id: literal(list)]))

        then:
        refusedBy == refusal

        where:
        kind     | many    | textLimit | manyLimit | list         || refusal
        "text"   | "false" | 4000      | null      | null         || null
        "text"   | "false" | null      | null      | null         || null
        "text"   | "true"  | 4000      | 10        | null         || null
        "fields" | "true"  | null      | 3         | null         || null
        "term"   | "false" | null      | null      | LIST_VERSION || null
        "term"   | "false" | null      | null      | null         || null
        "number" | "false" | 4000      | null      | null         || "declaration_fields_text_limit_only_for_text"
        "text"   | "false" | null      | 10        | null         || "declaration_fields_many_limit_only_for_many"
        "text"   | "false" | null      | null      | LIST_VERSION || "declaration_fields_term_list_only_for_term"
    }

    def "text says whether it holds one line or several, while old rows keep no choice and other kinds carry none"() {
        when:
        def refusedBy = attempt("declaration_fields", insertInto("declaration_fields", A_FIELD + [
                kind: literal(kind), text_limit: kind == "text" ? "100" : "null", text_lines: literal(lines)]))

        then:
        refusedBy == refusal

        where:
        kind     | lines     || refusal
        "text"   | "one"     || null
        "text"   | "several" || null
        "text"   | null      || null
        "text"   | "later"   || "declaration_fields_text_lines_known"
        "number" | null      || null
        "number" | "one"     || "declaration_fields_text_lines_only_for_text"
    }

    def "every field, taken or given back, at every level, says whether it must be given"() {
        when:
        def refusedBy = attempt("declaration_fields", insertInto("declaration_fields", A_FIELD + [
                side: literal(side), parent_field_id: literal(parent), must_be_given: literal(must)]))

        then:
        refusedBy == refusal

        where:
        side    | parent | must  || refusal
        "takes" | null   | true  || null
        "takes" | null   | false || null
        "gives" | null   | true  || null
        "gives" | PARENT | false || null
        "takes" | null   | null  || "declaration_fields_must_be_given_on_every_field"
        "gives" | null   | null  || "declaration_fields_must_be_given_on_every_field"
        "gives" | PARENT | null  || "declaration_fields_must_be_given_on_every_field"
    }

    def "only what a question gives back, held by no other field, says what it takes to stand"() {
        when:
        def refusedBy = attempt("declaration_fields", insertInto("declaration_fields", A_FIELD + [
                entry_version_id: literal(version), entry_kind: literal(kind), side: literal(side),
                parent_field_id: literal(parent), standing: literal(standing), standing_threshold: literal(threshold)]))

        then:
        refusedBy == refusal

        where:
        version          | kind       | side    | parent | standing           | threshold || refusal
        VERSION          | "question" | "gives" | null   | "always"           | null      || null
        VERSION          | "question" | "gives" | null   | null               | null      || null
        VERSION          | "question" | "gives" | null   | "above_confidence" | null      || null
        VERSION          | "question" | "gives" | null   | "above_confidence" | 1         || null
        VERSION          | "question" | "gives" | null   | "above_confidence" | 100       || null
        WORKFLOW_VERSION | "workflow" | "gives" | null   | null               | null      || null
        VERSION          | "question" | "gives" | PARENT | "never"            | null      || "declaration_fields_standing_only_for_unheld_gives"
        VERSION          | "question" | "takes" | null   | "always"           | null      || "declaration_fields_standing_only_for_unheld_gives"
        WORKFLOW_VERSION | "workflow" | "gives" | null   | "always"           | null      || "declaration_fields_standing_only_for_a_question"
        VERSION          | "question" | "gives" | null   | "never"            | 50        || "declaration_fields_standing_threshold_only_for_above_confidence"
        VERSION          | "question" | "gives" | null   | null               | 50        || "declaration_fields_standing_threshold_only_for_above_confidence"
        VERSION          | "question" | "gives" | null   | "above_confidence" | 0         || "declaration_fields_standing_threshold_range"
        VERSION          | "question" | "gives" | null   | "above_confidence" | 101       || "declaration_fields_standing_threshold_range"
    }

    def "a binding fills a step's input, a case's or an output, and only a route's discriminator fills nothing"() {
        when:
        def refusedBy = attempt("bindings", insertInto("bindings", A_BINDING + [workflow_step_id: literal(step),
                route_case_id: literal(routeCase), target_path: literal(target), step_kind: literal(stepKind)]))

        then:
        refusedBy == refusal

        where:
        step  | routeCase  | target      | stepKind || refusal
        STEP  | null       | "channel"   | null     || null
        null  | ROUTE_CASE | "complaint" | null     || null
        null  | null       | "summary"   | null     || null
        STEP  | ROUTE_CASE | "channel"   | null     || "bindings_at_most_one_consumer"
        null  | ROUTE_CASE | null        | "route"  || "bindings_no_target_only_for_a_step"
        null  | null       | null        | "route"  || "bindings_no_target_only_for_a_step"
        ROUTE | null       | null        | null     || "bindings_step_kind_exactly_for_no_target"
        STEP  | null       | "channel"   | "route"  || "bindings_step_kind_exactly_for_no_target"
        STEP  | null       | null        | "entry"  || "bindings_step_kind_is_route"
        STEP  | null       | null        | "route"  || "bindings_route_fk"
        ROUTE | null       | null        | "route"  || "bindings_one_discriminator"
    }

    def "a binding and what it fills and reads from are in one workflow version"() {
        when:
        def refusedBy = attempt("bindings", insertInto("bindings", A_BINDING + [entry_version_id: literal(version),
                workflow_step_id: literal(step), route_case_id: literal(routeCase), constant: literal(constant),
                source_step_id: literal(sourceStep), source_path: literal(sourcePath)]))

        then:
        refusedBy == refusal

        where:
        version               | step | routeCase  | sourceStep | sourcePath | constant || refusal
        WORKFLOW_VERSION      | null | null       | STEP       | "summary"  | null     || null
        NEXT_WORKFLOW_VERSION | null | null       | null       | null       | '"x"'    || null
        NEXT_WORKFLOW_VERSION | STEP | null       | null       | null       | '"x"'    || "bindings_step_fk"
        NEXT_WORKFLOW_VERSION | null | ROUTE_CASE | null       | null       | '"x"'    || "bindings_route_case_fk"
        NEXT_WORKFLOW_VERSION | null | null       | STEP       | "summary"  | null     || "bindings_source_step_fk"
    }

    def "a path names fields by name, one after another, and never a place among many"() {
        when:
        def refusedBy = attempt("bindings", setting("bindings", String.format(assignment, literal(path))))

        then:
        refusedBy == (holds ? null : rule)

        where:
        [assignment, rule, path, holds] << [PATHS_IN_BINDINGS, PATHS].combinations().collect { it.flatten() }
    }

    def "a binding reads from one source: a constant, this workflow's input, or another step"() {
        when:
        def refusedBy = attempt("bindings", insertInto("bindings", A_BINDING + [
                source_step_id: literal(sourceStep), source_path: literal(sourcePath), constant: literal(constant)]))

        then:
        refusedBy == refusal

        where:
        sourceStep | sourcePath | constant || refusal
        null       | "channel"  | null     || null
        null       | null       | '"x"'    || null
        ROUTE      | "category" | null     || null
        null       | null       | null     || "bindings_exactly_one_source"
        null       | "channel"  | '"x"'    || "bindings_exactly_one_source"
        ROUTE      | null       | '"x"'    || "bindings_exactly_one_source"
        ROUTE      | null       | null     || "bindings_exactly_one_source"
        STEP       | "summary"  | null     || "bindings_not_from_itself"
    }

    def "a constant is at most 1048576 characters as written out, whatever shape holds it"() {
        when:
        def refusedBy = attempt("bindings", setting("bindings", "source_path = null, constant = ${constant}"))

        then:
        refusedBy == refusal
        // DB-SPECIFIC: to_jsonb(), jsonb_build_object(), jsonb_agg(), repeat(), chr() and generate_series() use PostgreSQL's syntax and semantics.

        where:
        constant                                                           || refusal
        "to_jsonb(repeat('a', 1048574))"                                   || null
        "(select jsonb_agg(chr(1)) from generate_series(1, 8192))"         || null
        "to_jsonb(repeat('a', 1048575))"                                   || "bindings_constant_bounded"
        "jsonb_build_object(repeat('k', 1100000), 1)"                      || "bindings_constant_bounded"
        "(select jsonb_agg(12345) from generate_series(1, 200000))"        || "bindings_constant_bounded"
    }

    def "an optional question drawing keeps its Markdown text without changing its instruction"() {
        expect:
        readInside(setting("question_versions", "drawing = '## {{complaint}}'"),
                "select drawing || '|' || instruction from app.question_versions where entry_version_id = '${VERSION}'") ==
                "## {{complaint}}|Say which category the complaint falls under."
        readInside(setting("question_versions", "drawing = null"),
                "select coalesce(drawing, 'absent') from app.question_versions where entry_version_id = '${VERSION}'") ==
                "absent"
    }

    def "text is held to its length"() {
        when:
        def refusedBy = attempt(table, setting(table, assignment))

        then:
        refusedBy == refusal
        // DB-SPECIFIC: repeat() uses PostgreSQL's syntax and semantics.

        where:
        table                     | assignment                        || refusal
        "reference_list_versions" | "note = repeat('a', 2048)"        || null
        "reference_list_versions" | "note = null"                     || null
        "reference_list_versions" | "note = ''"                       || "reference_list_versions_note_visible"
        "question_versions"       | "instruction = repeat('a', 8192)" || null
        "question_versions"       | "instruction = null"              || null
        "question_versions"       | "instruction = ''"                || "question_versions_instruction_visible"
        "question_versions"       | "drawing = repeat('a', 8192)"     || null
        "question_versions"       | "drawing = ''"                    || "question_versions_drawing_visible"
        "reference_list_terms"    | "term = repeat('a', 128)"         || null
        "reference_list_terms"    | "term = ''"                       || "reference_list_terms_term_visible"
        "reference_list_terms"    | "meaning = repeat('a', 512)"      || null
        "reference_list_terms"    | "meaning = ''"                    || "reference_list_terms_meaning_visible"
        "route_cases"             | "term = repeat('a', 128)"         || null
        "entries"                 | "purpose = repeat('a', 512)"      || null
        "entries"                 | "purpose = 'a'"                   || null
        "entries"                 | "purpose = ''"                    || "entries_purpose_visible"
        "declaration_fields"      | "label = repeat('a', 128)"        || null
        "declaration_fields"      | "label = ''"                      || "declaration_fields_label_visible"
        "declaration_fields"      | "help = repeat('a', 512)"         || null
        "declaration_fields"      | "help = ''"                       || "declaration_fields_help_visible"
        "runs"                    | "name = repeat('a', 128)"         || null
        "runs"                    | "name = ''"                       || "runs_name_visible"
        "run_step_failures"       | "detail = repeat('a', 2048)"      || null
        "run_step_failures"       | "detail = ''"                     || "run_step_failures_detail_visible"
        "model_call_turnaways"    | "said = repeat('a', 2048)"        || null
        "model_call_turnaways"    | "said = null"                     || null
        "model_call_turnaways"    | "said = ''"                       || "model_call_turnaways_said_visible"
        "run_help_exchanges"      | "question = repeat('a', 2048)"    || null
        "run_help_exchanges"      | "question = ''"                   || "run_help_exchanges_question_visible"
        "run_help_exchanges"      | "answer = repeat('a', 8388608)"   || null
        "run_help_exchanges"      | "answer = null"                   || null
        "productions"             | "explanation = repeat('a', 2048)" || null
        "review_decisions"        | "explanation = repeat('a', 2048)" || null
        "review_decisions"        | "explanation = ''"                || "review_decisions_explanation_visible"
    }

    def "only prose holds a tab or a line feed, and nothing holds another control character"() {
        when:
        def refusedBy = attempt(table, setting(table, assignment))

        then:
        refusedBy == refusal
        // DB-SPECIFIC: chr() uses PostgreSQL's syntax and semantics.

        where:
        table                     | assignment                                      || refusal
        "reference_list_versions" | "note = 'a' || chr(9) || chr(10) || 'b'"        || null
        "reference_list_versions" | "note = 'a' || chr(133) || 'b'"                 || "reference_list_versions_note_visible"
        "question_versions"       | "instruction = 'a' || chr(10) || chr(9) || 'b'" || null
        "question_versions"       | "drawing = 'a' || chr(10) || chr(9) || 'b'"     || null
        "question_versions"       | "drawing = 'a' || chr(13) || 'b'"             || "question_versions_drawing_visible"
        "question_versions"       | "instruction = 'a' || chr(8) || 'b'"            || "question_versions_instruction_visible"
        "question_versions"       | "instruction = 'a' || chr(11) || 'b'"           || "question_versions_instruction_visible"
        "reference_list_terms"    | "meaning = 'a' || chr(9) || 'b'"                || "reference_list_terms_meaning_visible"
        "route_cases"             | "term = 'Bill' || chr(9) || 'ing'"              || "route_cases_term_visible"
        "run_step_failures"       | "detail = 'a' || chr(9) || chr(10) || 'b'"      || null
        "run_step_failures"       | "detail = 'a' || chr(13) || 'b'"                || "run_step_failures_detail_visible"
        "entries"                 | "purpose = 'a' || chr(9) || 'b'"                || "entries_purpose_visible"
        "declaration_fields"      | "label = 'a' || chr(10) || 'b'"                 || "declaration_fields_label_visible"
        "declaration_fields"      | "help = 'a' || chr(10) || 'b'"                  || "declaration_fields_help_visible"
        "runs"                    | "name = 'a' || chr(9) || 'b'"                   || "runs_name_visible"
        "model_call_turnaways"    | "said = 'a' || chr(9) || chr(10) || 'b'"        || null
        "model_call_turnaways"    | "said = 'a' || chr(13) || 'b'"                  || "model_call_turnaways_said_visible"
        "run_help_exchanges"      | "question = 'a' || chr(10) || 'b'"              || null
        "run_help_exchanges"      | "answer = 'a' || chr(10) || chr(9) || 'b'"      || null
        "run_help_exchanges"      | "answer = 'a' || chr(133) || 'b'"              || "run_help_exchanges_answer_visible"
        "productions"             | "explanation = 'a' || chr(9) || chr(10) || 'b'" || null
        "productions"             | "explanation = 'a' || chr(8) || 'b'"            || "productions_explanation_visible"
        "review_decisions"        | "explanation = 'a' || chr(10) || 'b'"           || null
        "review_decisions"        | "explanation = 'a' || chr(127) || 'b'"          || "review_decisions_explanation_visible"
    }

    def "an ordered row may stand at position zero, and never before it"() {
        when:
        def refusedBy = attempt(table, "update app.${table} set position = ${position} where ${ROW_OF[table]}")

        then:
        refusedBy == (position < 0 ? "${table}_position_not_negative".toString() : null)

        where:
        [table, position] << [["reference_list_terms", "workflow_steps", "declaration_fields"], [0, -1]].combinations()
    }

    /**
     * Two rows trading places pass through a moment where both hold one position, so what is proved is
     * that the collision between the statements is let through and the order at the end is still held.
     */
    def "rows trade positions one statement at a time, and the positions are held unique when it ends"() {
        when:
        def refusedBy = attempt(table, "update app.${table} set position = 2 where ${key} = '${first}';" +
                " update app.${table} set position = 1 where ${key} = '${second}'")

        then:
        refusedBy == null

        where:
        table                  | key                      | first | second
        "reference_list_terms" | "reference_list_term_id" | TERM  | NEXT_TERM
        "workflow_steps"       | "workflow_step_id"       | STEP  | ROUTE
        "declaration_fields"   | "declaration_field_id"   | FIELD | SIBLING
    }
}
