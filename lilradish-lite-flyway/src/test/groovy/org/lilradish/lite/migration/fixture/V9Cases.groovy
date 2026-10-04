package org.lilradish.lite.migration.fixture

import static org.lilradish.lite.migration.fixture.SchemaKeys.*
import static org.lilradish.lite.migration.fixture.SchemaRows.*

/** V9__step_and_declaration.sql's rules: the rows they are proved beside, and a case attacking each. */
final class V9Cases {

    static final String SEARCH_STEP = "0000000c-0000-4000-8000-000000000007"
    static final String SEARCH_PART = "00000020-0000-4000-8000-000000000001"

    static final List<String> FIXTURE = [
        "insert into app.workflow_steps (workflow_step_id, entry_version_id, position, name, kind, pinned_version_id," +
                " pinned_kind, producer, producer_model, producer_mode, tries, tells_what_happened, created_by)" +
                " values ('${STEP}', '${WORKFLOW_VERSION}', 1, 'summarise', 'entry', '${VERSION}', 'question'," +
                " 'model', 'general', 'ordinary', 3, true, '${STEWARD}')",
        "insert into app.workflow_steps (workflow_step_id, entry_version_id, position, name, kind, created_by)" +
                " values ('${ROUTE}', '${WORKFLOW_VERSION}', 2, 'route_by_category', 'route', '${STEWARD}')",
        "insert into app.workflow_steps (workflow_step_id, entry_version_id, position, name, kind, search_way," +
                " producer, producer_model, producer_mode, reviewer_model, reviewer_mode, tries," +
                " tells_what_happened, search_title_standing, search_body_standing," +
                " search_references_standing, search_other_standing, created_by)" +
                " values ('${SEARCH_STEP}', '${WORKFLOW_VERSION}', 9, 'find_context', 'search', 'web'," +
                " 'model', 'general', 'ordinary', 'general', 'research', 3," +
                " true, 'always', 'never', 'never', 'always', '${STEWARD}')",
        "insert into app.search_question_parts (search_question_part_id, entry_version_id, workflow_step_id," +
                " position, literal_text, created_by) values ('${SEARCH_PART}', '${WORKFLOW_VERSION}'," +
                " '${SEARCH_STEP}', 0, 'Find ', '${STEWARD}')",
        "insert into app.search_question_parts (entry_version_id, workflow_step_id, position," +
                " source_step_id, source_path, created_by) values ('${WORKFLOW_VERSION}', '${SEARCH_STEP}'," +
                " 1, '${STEP}', 'details', '${STEWARD}')",
        "insert into app.search_question_parts (entry_version_id, workflow_step_id, position, source_path," +
                " created_by) values ('${WORKFLOW_VERSION}', '${SEARCH_STEP}', 2, 'complaint', '${STEWARD}')",
        "insert into app.route_cases (route_case_id, entry_version_id, workflow_step_id, term, target_version_id, created_by)" +
                " values ('${ROUTE_CASE}', '${WORKFLOW_VERSION}', '${ROUTE}', 'Billing', '${NEXT_WORKFLOW_VERSION}', '${STEWARD}')," +
                " ('${FALLBACK}', '${WORKFLOW_VERSION}', '${ROUTE}', null, '${NEXT_WORKFLOW_VERSION}', '${STEWARD}')",
        "insert into app.declaration_fields (declaration_field_id, entry_version_id, entry_kind, side, position, name," +
                " kind, text_limit, must_be_given, created_by)" +
                " values ('${FIELD}', '${VERSION}', 'question', 'takes', 1, 'complaint', 'text', 4000, true, '${STEWARD}')," +
                " ('${SIBLING}', '${VERSION}', 'question', 'takes', 2, 'channel', 'text', 32, false, '${STEWARD}')",
        "insert into app.declaration_fields (declaration_field_id, entry_version_id, entry_kind, side, position, name," +
                " kind, must_be_given, standing, created_by)" +
                " values ('${PARENT}', '${VERSION}', 'question', 'gives', 1, 'details', 'fields', false, 'never'," +
                " '${STEWARD}')",
        "insert into app.bindings (binding_id, entry_version_id, workflow_step_id, target_path, source_path, created_by)" +
                " values ('${BINDING}', '${WORKFLOW_VERSION}', '${STEP}', 'complaint', 'complaint', '${STEWARD}')",
        "insert into app.bindings (binding_id, entry_version_id, workflow_step_id, step_kind, source_step_id," +
                " source_path, created_by) values ('${DISCRIMINATOR}', '${WORKFLOW_VERSION}', '${ROUTE}', 'route'," +
                " '${STEP}', 'category', '${STEWARD}')",
    ]

    /** A row every rule admits beside the fixture, which a case or a feature alters in what it is about. */
    static final Map<String, String> A_STEP = [entry_version_id: "'${WORKFLOW_VERSION}'", position: "3",
                                               name: "'reply'", created_by: "'${STEWARD}'"]
    static final Map<String, String> A_SEARCH_PART = [
        entry_version_id: "'${WORKFLOW_VERSION}'", workflow_step_id: "'${SEARCH_STEP}'",
        position: "3", literal_text: "' then report'", created_by: "'${STEWARD}'"]
    static final String SEARCH_PART_ROW = "search_question_part_id = '${SEARCH_PART}'"
    private static String changingSearchPart(String assignments) {
        "update app.search_question_parts set ${assignments} where ${SEARCH_PART_ROW}"
    }

    static final Map<String, String> A_CASE = [entry_version_id: "'${WORKFLOW_VERSION}'", workflow_step_id: "'${ROUTE}'",
                                               term: "'Delivery'", target_version_id: "'${NEXT_WORKFLOW_VERSION}'",
                                               created_by: "'${STEWARD}'"]
    static final Map<String, String> A_FIELD = [entry_version_id: "'${VERSION}'", entry_kind: "'question'",
                                                side: "'takes'", position: "3", name: "'sender'", kind: "'text'",
                                                must_be_given: "false", created_by: "'${STEWARD}'"]
    static final Map<String, String> A_BINDING = [entry_version_id: "'${WORKFLOW_VERSION}'", workflow_step_id: "'${STEP}'",
                                                  target_path: "'channel'", constant: "'\"email\"'",
                                                  created_by: "'${STEWARD}'"]

    static final List<Map<String, String>> CASES = [
        attack("workflow_steps_pk", "workflow_steps", insertInto("workflow_steps",
                A_STEP + [workflow_step_id: "'${STEP}'"])),
        attack("workflow_steps_version_fk", "workflow_steps", insertInto("workflow_steps",
                A_STEP + [entry_version_id: "'${VERSION}'"])),
        attack("workflow_steps_position_unique", "workflow_steps", insertInto("workflow_steps",
                A_STEP + [position: "1"])),
        attack("workflow_steps_position_not_negative", "workflow_steps", setting("workflow_steps", "position = -1")),
        attack("workflow_steps_name_shape", "workflow_steps", setting("workflow_steps", "name = 'Summarise'")),
        attack("workflow_steps_pinned_fk", "workflow_steps", insertInto("workflow_steps", A_STEP + [kind: "'entry'",
                pinned_version_id: "'${LIST_VERSION}'", pinned_kind: "'question'"])),
        attack("workflow_steps_pinned_together", "workflow_steps", setting("workflow_steps", "pinned_kind = null")),
        attack("workflow_steps_pinned_is_question_or_workflow", "workflow_steps",
                setting("workflow_steps", "pinned_version_id = '${LIST_VERSION}', pinned_kind = 'reference_list'")),
        attack("workflow_steps_pinned_only_for_entry", "workflow_steps", setting("workflow_steps", "kind = null")),
        attack("workflow_steps_not_own_version", "workflow_steps", insertInto("workflow_steps", A_STEP + [
                kind: "'entry'", pinned_version_id: "'${WORKFLOW_VERSION}'", pinned_kind: "'workflow'"])),
        attack("workflow_steps_code_step_only_for_code_step", "workflow_steps", insertInto("workflow_steps",
                A_STEP + [code_step: "'${CODE_STEP}'"])),
        attack("workflow_steps_code_step_produced_by_code_or_person", "workflow_steps", insertInto("workflow_steps",
                A_STEP + [kind: "'code_step'", code_step: "'${CODE_STEP}'", producer: "'model'"])),
        attack("workflow_steps_question_produced_by_model_or_person", "workflow_steps", setting("workflow_steps",
                "producer = 'code', producer_model = null, producer_mode = null, tells_what_happened = false")),
        attack("workflow_steps_workflow_or_route_unproduced", "workflow_steps",
                "update app.workflow_steps set tries = 3 where workflow_step_id = '${ROUTE}'"),
        attack("workflow_steps_producer_model_only_for_model", "workflow_steps",
                setting("workflow_steps", "producer = 'person', tells_what_happened = false")),
        attack("workflow_steps_producer_model_together", "workflow_steps",
                setting("workflow_steps", "producer_mode = null")),
        attack("workflow_steps_producer_model_shape", "workflow_steps",
                setting("workflow_steps", "producer_model = 'General'")),
        attack("workflow_steps_producer_mode_shape", "workflow_steps",
                setting("workflow_steps", "producer_mode = 'Research'")),
        attack("workflow_steps_told_only_for_model", "workflow_steps",
                setting("workflow_steps", "producer = 'person', producer_model = null, producer_mode = null")),
        attack("workflow_steps_tries_positive", "workflow_steps", setting("workflow_steps", "tries = 0")),
        attack("workflow_steps_reviewer_model_together", "workflow_steps",
                setting("workflow_steps", "reviewer_model = 'general'")),
        attack("workflow_steps_reviewer_model_shape", "workflow_steps",
                setting("workflow_steps", "reviewer_model = 'General', reviewer_mode = 'research'")),
        attack("workflow_steps_reviewer_mode_shape", "workflow_steps",
                setting("workflow_steps", "reviewer_model = 'general', reviewer_mode = 'Research'")),
        *authorshipAttacks("workflow_steps"),

        attack("workflow_steps_search_only_for_search", "workflow_steps",
                insertInto("workflow_steps", A_STEP + [kind: "'entry'", search_way: "'web'"])),
        attack("workflow_steps_search_way_shape", "workflow_steps",
                insertInto("workflow_steps", A_STEP + [kind: "'search'", search_way: "'Web'"])),
        attack("workflow_steps_search_standing_without_confidence", "workflow_steps",
                insertInto("workflow_steps", A_STEP + [kind: "'search'",
                                                     search_title_standing: "'above_confidence'"])),
        attack("workflow_steps_search_produced_by_model_or_person", "workflow_steps",
                insertInto("workflow_steps", A_STEP + [kind: "'search'", producer: "'code'"])),

        attack("search_question_parts_pk", "search_question_parts",
                insertInto("search_question_parts", A_SEARCH_PART + [search_question_part_id: "'${SEARCH_PART}'"])),
        attack("search_question_parts_step_fk", "search_question_parts",
                insertInto("search_question_parts", A_SEARCH_PART + [entry_version_id: "'${NEXT_WORKFLOW_VERSION}'"])),
        attack("search_question_parts_search_fk", "search_question_parts",
                insertInto("search_question_parts", A_SEARCH_PART + [workflow_step_id: "'${ROUTE}'"])),
        attack("search_question_parts_step_kind_is_search", "search_question_parts",
                insertInto("search_question_parts", A_SEARCH_PART + [step_kind: "'route'"])),
        attack("search_question_parts_position_unique", "search_question_parts",
                insertInto("search_question_parts", A_SEARCH_PART + [position: "0"])),
        attack("search_question_parts_position_not_negative", "search_question_parts",
                changingSearchPart("position = -1")),
        attack("search_question_parts_position_bounded", "search_question_parts",
                changingSearchPart("position = 256")),
        attack("search_question_parts_exactly_one_source", "search_question_parts",
                changingSearchPart("source_step_id = '${STEP}', source_path = 'details'")),
        attack("search_question_parts_literal_visible", "search_question_parts",
                changingSearchPart("literal_text = ''")),
        // DB-SPECIFIC: repeat() uses PostgreSQL's syntax and semantics.
        attack("search_question_parts_literal_bounded", "search_question_parts",
                changingSearchPart("literal_text = repeat('a', 8193)")),
        attack("search_question_parts_source_step_fk", "search_question_parts",
                changingSearchPart("literal_text = null, source_step_id = '${ABSENT}', source_path = 'details'")),
        attack("search_question_parts_not_from_itself", "search_question_parts",
                changingSearchPart("literal_text = null, source_step_id = '${SEARCH_STEP}', source_path = 'details'")),
        attack("search_question_parts_source_path_shape", "search_question_parts",
                changingSearchPart("literal_text = null, source_step_id = '${STEP}', source_path = 'details.0'")),
        // DB-SPECIFIC: repeat() uses PostgreSQL's syntax and semantics.
        attack("search_question_parts_source_path_bounded", "search_question_parts",
                changingSearchPart("literal_text = null, source_step_id = '${STEP}'," +
                        " source_path = 'ab' || repeat('.a', 511)")),
        attack("search_question_parts_author_fk", "search_question_parts",
                changingSearchPart("created_by = '${ABSENT}'")),
        // DB-SPECIFIC: now() uses PostgreSQL's syntax and semantics.
        attack("search_question_parts_editor_person_fk", "search_question_parts",
                changingSearchPart("updated_at = now(), updated_by = '${SEEDER}'")),
        // DB-SPECIFIC: now() uses PostgreSQL's syntax and semantics.
        attack("search_question_parts_editor_is_person", "search_question_parts",
                changingSearchPart("updated_at = now(), updated_by = '${WORKFLOW_RUNNER}'," +
                        " updated_by_kind = 'system'")),
        // DB-SPECIFIC: now() uses PostgreSQL's syntax and semantics.
        attack("search_question_parts_updated_together", "search_question_parts",
                changingSearchPart("updated_at = now()")),
        // DB-SPECIFIC: interval literals use PostgreSQL's syntax and semantics.
        attack("search_question_parts_updated_after_created", "search_question_parts",
                changingSearchPart("updated_at = created_at - interval '1 day', updated_by = '${STEWARD}'")),

        attack("route_cases_pk", "route_cases", insertInto("route_cases", A_CASE + [route_case_id: "'${ROUTE_CASE}'"])),
        attack("route_cases_step_fk", "route_cases", insertInto("route_cases",
                A_CASE + [entry_version_id: "'${NEXT_WORKFLOW_VERSION}'", target_version_id: "'${WORKFLOW_VERSION}'"])),
        attack("route_cases_route_fk", "route_cases", insertInto("route_cases", A_CASE + [workflow_step_id: "'${STEP}'"])),
        attack("route_cases_is_route", "route_cases", insertInto("route_cases", A_CASE + [step_kind: "'entry'"])),
        attack("route_cases_target_fk", "route_cases", insertInto("route_cases",
                A_CASE + [target_version_id: "'${VERSION}'"])),
        attack("route_cases_target_is_workflow", "route_cases", insertInto("route_cases",
                A_CASE + [target_kind: "'question'"])),
        attack("route_cases_not_own_version", "route_cases", insertInto("route_cases",
                A_CASE + [target_version_id: "'${WORKFLOW_VERSION}'"])),
        attack("route_cases_term_visible", "route_cases", setting("route_cases", "term = ''")),
        // DB-SPECIFIC: repeat() uses PostgreSQL's syntax and semantics.
        attack("route_cases_term_bounded", "route_cases", setting("route_cases", "term = repeat('a', 129)")),
        attack("route_cases_one_fallback", "route_cases", insertInto("route_cases", A_CASE + [term: "null"])),
        *authorshipAttacks("route_cases"),

        attack("declaration_fields_pk", "declaration_fields", insertInto("declaration_fields",
                A_FIELD + [declaration_field_id: "'${FIELD}'"])),
        attack("declaration_fields_exactly_one_owner", "declaration_fields", insertInto("declaration_fields",
                A_FIELD + [entry_version_id: "null", entry_kind: "null"])),
        attack("declaration_fields_version_fk", "declaration_fields", insertInto("declaration_fields",
                A_FIELD + [entry_version_id: "'${WORKFLOW_VERSION}'"])),
        attack("declaration_fields_version_together", "declaration_fields", insertInto("declaration_fields",
                A_FIELD + [entry_kind: "null"])),
        attack("declaration_fields_version_is_question_or_workflow", "declaration_fields", insertInto("declaration_fields",
                A_FIELD + [entry_version_id: "'${LIST_VERSION}'", entry_kind: "'reference_list'"])),
        attack("declaration_fields_route_fk", "declaration_fields", insertInto("declaration_fields",
                A_FIELD + [entry_version_id: "null", entry_kind: "null", workflow_step_id: "'${STEP}'",
                           side: "'gives'"])),
        attack("declaration_fields_route_is_route", "declaration_fields", insertInto("declaration_fields",
                A_FIELD + [entry_version_id: "null", entry_kind: "null", workflow_step_id: "'${ROUTE}'",
                           step_kind: "'entry'", side: "'gives'"])),
        attack("declaration_fields_route_only_for_gives", "declaration_fields", insertInto("declaration_fields",
                A_FIELD + [entry_version_id: "null", entry_kind: "null", workflow_step_id: "'${ROUTE}'"])),
        attack("declaration_fields_parent_fk", "declaration_fields", insertInto("declaration_fields",
                A_FIELD + [parent_field_id: "'${ABSENT}'"])),
        attack("declaration_fields_parent_is_fields", "declaration_fields", insertInto("declaration_fields",
                A_FIELD + [side: "'gives'", parent_field_id: "'${PARENT}'", parent_kind: "'text'"])),
        attack("declaration_fields_not_own_parent", "declaration_fields",
                setting("declaration_fields", "parent_field_id = '${FIELD}'")),
        attack("declaration_fields_position_unique", "declaration_fields", insertInto("declaration_fields",
                A_FIELD + [position: "1"])),
        attack("declaration_fields_position_not_negative", "declaration_fields",
                setting("declaration_fields", "position = -1")),
        attack("declaration_fields_name_shape", "declaration_fields", setting("declaration_fields", "name = 'Complaint'")),
        attack("declaration_fields_label_visible", "declaration_fields", setting("declaration_fields", "label = ''")),
        // DB-SPECIFIC: repeat() uses PostgreSQL's syntax and semantics.
        attack("declaration_fields_label_bounded", "declaration_fields",
                setting("declaration_fields", "label = repeat('a', 129)")),
        // DB-SPECIFIC: chr() uses PostgreSQL's syntax and semantics.
        attack("declaration_fields_help_visible", "declaration_fields",
                setting("declaration_fields", "help = 'What' || chr(9) || 'they wrote'")),
        // DB-SPECIFIC: repeat() uses PostgreSQL's syntax and semantics.
        attack("declaration_fields_help_bounded", "declaration_fields",
                setting("declaration_fields", "help = repeat('a', 513)")),
        attack("declaration_fields_text_limit_positive", "declaration_fields",
                setting("declaration_fields", "text_limit = 0")),
        attack("declaration_fields_text_limit_only_for_text", "declaration_fields",
                setting("declaration_fields", "kind = 'number'")),
        attack("declaration_fields_many_limit_positive", "declaration_fields",
                setting("declaration_fields", "holds_many = true, many_limit = 0")),
        attack("declaration_fields_many_limit_only_for_many", "declaration_fields",
                setting("declaration_fields", "many_limit = 5")),
        attack("declaration_fields_term_list_fk", "declaration_fields",
                setting("declaration_fields", "kind = 'term', text_limit = null, term_list_version_id = '${VERSION}'")),
        attack("declaration_fields_term_list_is_reference_list", "declaration_fields", setting("declaration_fields",
                "kind = 'term', text_limit = null, term_list_version_id = '${LIST_VERSION}', term_list_kind = 'question'")),
        attack("declaration_fields_term_list_only_for_term", "declaration_fields",
                setting("declaration_fields", "term_list_version_id = '${LIST_VERSION}'")),
        attack("declaration_fields_must_be_given_on_every_field", "declaration_fields",
                setting("declaration_fields", "must_be_given = null")),
        attack("declaration_fields_standing_only_for_unheld_gives", "declaration_fields",
                setting("declaration_fields", "standing = 'always'")),
        attack("declaration_fields_standing_only_for_a_question", "declaration_fields", insertInto("declaration_fields",
                A_FIELD + [entry_version_id: "'${WORKFLOW_VERSION}'", entry_kind: "'workflow'", side: "'gives'",
                           standing: "'always'"])),
        attack("declaration_fields_standing_threshold_only_for_above_confidence", "declaration_fields",
                "update app.declaration_fields set standing_threshold = 50 where declaration_field_id = '${PARENT}'"),
        attack("declaration_fields_standing_threshold_range", "declaration_fields",
                "update app.declaration_fields set standing = 'above_confidence', standing_threshold = 101" +
                        " where declaration_field_id = '${PARENT}'"),
        *authorshipAttacks("declaration_fields"),

        attack("bindings_pk", "bindings", insertInto("bindings", A_BINDING + [binding_id: "'${BINDING}'"])),
        attack("bindings_version_fk", "bindings", insertInto("bindings",
                A_BINDING + [workflow_step_id: "null", entry_version_id: "'${VERSION}'"])),
        attack("bindings_at_most_one_consumer", "bindings", insertInto("bindings",
                A_BINDING + [route_case_id: "'${ROUTE_CASE}'"])),
        attack("bindings_step_fk", "bindings", insertInto("bindings", A_BINDING + [workflow_step_id: "'${ABSENT}'"])),
        attack("bindings_route_case_fk", "bindings", insertInto("bindings",
                A_BINDING + [workflow_step_id: "null", route_case_id: "'${ABSENT}'"])),
        attack("bindings_no_target_only_for_a_step", "bindings", insertInto("bindings", A_BINDING + [
                workflow_step_id: "null", route_case_id: "'${ROUTE_CASE}'", target_path: "null", step_kind: "'route'"])),
        attack("bindings_step_kind_exactly_for_no_target", "bindings", insertInto("bindings",
                A_BINDING + [step_kind: "'route'"])),
        attack("bindings_route_fk", "bindings", insertInto("bindings",
                A_BINDING + [target_path: "null", step_kind: "'route'"])),
        attack("bindings_step_kind_is_route", "bindings", insertInto("bindings",
                A_BINDING + [target_path: "null", step_kind: "'entry'"])),
        attack("bindings_one_discriminator", "bindings", insertInto("bindings",
                A_BINDING + [workflow_step_id: "'${ROUTE}'", target_path: "null", step_kind: "'route'"])),
        attack("bindings_target_path_shape", "bindings", setting("bindings", "target_path = 'Complaint'")),
        // DB-SPECIFIC: repeat() uses PostgreSQL's syntax and semantics.
        attack("bindings_target_path_bounded", "bindings", setting("bindings", "target_path = 'ab' || repeat('.a', 511)")),
        attack("bindings_exactly_one_source", "bindings", setting("bindings", "constant = '1'")),
        attack("bindings_source_step_fk", "bindings", setting("bindings", "source_step_id = '${ABSENT}'")),
        attack("bindings_not_from_itself", "bindings", setting("bindings", "source_step_id = '${STEP}'")),
        attack("bindings_source_path_shape", "bindings", setting("bindings", "source_path = 'complaint.'")),
        // DB-SPECIFIC: repeat() uses PostgreSQL's syntax and semantics.
        attack("bindings_source_path_bounded", "bindings", setting("bindings", "source_path = 'ab' || repeat('.a', 511)")),
        // DB-SPECIFIC: to_jsonb() and repeat() use PostgreSQL's syntax and semantics.
        attack("bindings_constant_bounded", "bindings", setting("bindings",
                "source_path = null, constant = to_jsonb(repeat('a', 1048575))")),
        *authorshipAttacks("bindings"),
    ]

    private V9Cases() {}
}
