package org.lilradish.lite.migration.fixture

import static org.lilradish.lite.migration.fixture.SchemaKeys.*
import static org.lilradish.lite.migration.fixture.SchemaRows.*

/** V8__library.sql's rules: the rows they are proved beside, and a case attacking each. */
final class V8Cases {

    static final List<String> FIXTURE = [
        "insert into app.code_step_publications (code_step_publication_id, code_step, group_key, every_group, created_by)" +
                " values ('${PUBLICATION}', '${CODE_STEP}', 'SUPPORT', false, '${SEEDER}')," +
                " ('${EVERYWHERE}', '${CODE_STEP}', null, true, '${SEEDER}')",
        "insert into app.reference_list_versions (entry_version_id, note, created_by)" +
                " values ('${LIST_VERSION}', 'Choose by what is to be put right.', '${STEWARD}')",
        "insert into app.reference_list_terms (reference_list_term_id, entry_version_id, position, term, meaning, created_by)" +
                " values ('${TERM}', '${LIST_VERSION}', 1, 'Billing', 'A charge is disputed.', '${STEWARD}')," +
                " ('${NEXT_TERM}', '${LIST_VERSION}', 2, 'Delivery', 'What was ordered never came.', '${STEWARD}')",
        "insert into app.question_versions (entry_version_id, instruction, created_by)" +
                " values ('${VERSION}', 'Say which category the complaint falls under.', '${STEWARD}')",
        "insert into app.workflow_versions (entry_version_id, ceiling, created_by)" +
                " values ('${WORKFLOW_VERSION}', 1000, '${STEWARD}'), ('${NEXT_WORKFLOW_VERSION}', null, '${STEWARD}')",
    ]

    /** A row every rule admits beside the fixture, which a case or a feature alters in what it is about. */
    static final Map<String, String> A_PUBLICATION = [code_step: "'${CODE_STEP}'", group_key: "'BILLING'",
                                                     every_group: "false", created_by: "'${SEEDER}'"]
    static final Map<String, String> A_TERM = [entry_version_id: "'${LIST_VERSION}'", position: "3",
                                               term: "'Returns'", meaning: "'Something is sent back.'",
                                               created_by: "'${STEWARD}'"]

    static final List<Map<String, String>> CASES = [
        attack("code_step_publications_pk", "code_step_publications", insertInto("code_step_publications",
                A_PUBLICATION + [code_step_publication_id: "'${PUBLICATION}'"])),
        attack("code_step_publications_author_seeder_fk", "code_step_publications", insertInto("code_step_publications",
                A_PUBLICATION + [created_by: "'${STEWARD}'"])),
        attack("code_step_publications_author_is_seeder", "code_step_publications", insertInto("code_step_publications",
                A_PUBLICATION + [created_by: "'${STEWARD}'", created_by_kind: "'person'"])),
        attack("code_step_publications_exactly_one_form", "code_step_publications", insertInto("code_step_publications",
                A_PUBLICATION + [group_key: "null"])),
        attack("code_step_publications_group_key_shape", "code_step_publications", insertInto("code_step_publications",
                A_PUBLICATION + [group_key: "'Billing'"])),
        attack("code_step_publications_group_key_unique", "code_step_publications", insertInto("code_step_publications",
                A_PUBLICATION + [group_key: "'SUPPORT'"])),
        attack("code_step_publications_one_every_group", "code_step_publications", insertInto("code_step_publications",
                A_PUBLICATION + [group_key: "null", every_group: "true"])),

        attack("reference_list_versions_pk", "reference_list_versions",
                "insert into app.reference_list_versions (entry_version_id, created_by) values ('${LIST_VERSION}', '${STEWARD}')"),
        attack("reference_list_versions_version_fk", "reference_list_versions",
                "insert into app.reference_list_versions (entry_version_id, created_by) values ('${VERSION}', '${STEWARD}')"),
        attack("reference_list_versions_is_reference_list", "reference_list_versions",
                "insert into app.reference_list_versions (entry_version_id, entry_kind, created_by)" +
                        " values ('${VERSION}', 'question', '${STEWARD}')"),
        *authorshipAttacks("reference_list_versions"),
        attack("reference_list_versions_note_visible", "reference_list_versions",
                setting("reference_list_versions", "note = 'Choose' || chr(13) || 'well'")),
        attack("reference_list_versions_note_bounded", "reference_list_versions",
                setting("reference_list_versions", "note = repeat('a', 2049)")),

        attack("reference_list_terms_pk", "reference_list_terms", insertInto("reference_list_terms",
                A_TERM + [reference_list_term_id: "'${TERM}'"])),
        attack("reference_list_terms_version_fk", "reference_list_terms", insertInto("reference_list_terms",
                A_TERM + [entry_version_id: "'${VERSION}'"])),
        attack("reference_list_terms_position_unique", "reference_list_terms", insertInto("reference_list_terms",
                A_TERM + [position: "1"])),
        attack("reference_list_terms_position_not_negative", "reference_list_terms",
                setting("reference_list_terms", "position = -1")),
        *authorshipAttacks("reference_list_terms"),
        attack("reference_list_terms_term_visible", "reference_list_terms",
                setting("reference_list_terms", "term = 'Bill' || chr(9) || 'ing'")),
        attack("reference_list_terms_term_bounded", "reference_list_terms",
                setting("reference_list_terms", "term = repeat('a', 129)")),
        attack("reference_list_terms_meaning_visible", "reference_list_terms",
                setting("reference_list_terms", "meaning = 'A charge' || chr(10) || 'is disputed.'")),
        attack("reference_list_terms_meaning_bounded", "reference_list_terms",
                setting("reference_list_terms", "meaning = repeat('a', 513)")),

        attack("question_versions_pk", "question_versions",
                "insert into app.question_versions (entry_version_id, created_by) values ('${VERSION}', '${STEWARD}')"),
        attack("question_versions_version_fk", "question_versions",
                "insert into app.question_versions (entry_version_id, created_by) values ('${LIST_VERSION}', '${STEWARD}')"),
        attack("question_versions_is_question", "question_versions",
                "insert into app.question_versions (entry_version_id, entry_kind, created_by)" +
                        " values ('${LIST_VERSION}', 'reference_list', '${STEWARD}')"),
        *authorshipAttacks("question_versions"),
        attack("question_versions_instruction_visible", "question_versions",
                setting("question_versions", "instruction = 'Say' || chr(13) || 'which'")),
        attack("question_versions_instruction_bounded", "question_versions",
                setting("question_versions", "instruction = repeat('a', 8193)")),

        attack("workflow_versions_pk", "workflow_versions",
                "insert into app.workflow_versions (entry_version_id, created_by) values ('${WORKFLOW_VERSION}', '${STEWARD}')"),
        attack("workflow_versions_version_fk", "workflow_versions",
                "insert into app.workflow_versions (entry_version_id, created_by) values ('${VERSION}', '${STEWARD}')"),
        attack("workflow_versions_is_workflow", "workflow_versions",
                "insert into app.workflow_versions (entry_version_id, entry_kind, created_by)" +
                        " values ('${VERSION}', 'question', '${STEWARD}')"),
        *authorshipAttacks("workflow_versions"),
        attack("workflow_versions_ceiling_positive", "workflow_versions", setting("workflow_versions", "ceiling = 0")),
        attack("workflow_versions_helper_only_for_may_be_helped", "workflow_versions",
                setting("workflow_versions", "helper_model = 'general', helper_mode = 'ordinary'")),
        attack("workflow_versions_helper_model_together", "workflow_versions",
                setting("workflow_versions", "may_be_helped = true, helper_model = 'general'")),
        attack("workflow_versions_helper_model_shape", "workflow_versions",
                setting("workflow_versions", "may_be_helped = true, helper_model = 'General'")),
        attack("workflow_versions_helper_mode_shape", "workflow_versions",
                setting("workflow_versions", "may_be_helped = true, helper_model = 'general', helper_mode = 'Research'")),
    ]

    private V8Cases() {}
}
