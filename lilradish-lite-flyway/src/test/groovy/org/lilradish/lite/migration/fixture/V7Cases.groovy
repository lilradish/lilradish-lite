package org.lilradish.lite.migration.fixture

import static org.lilradish.lite.migration.fixture.SchemaKeys.*
import static org.lilradish.lite.migration.fixture.SchemaRows.*

/** V7__entry.sql's rules: the rows they are proved beside, and a case attacking each. */
final class V7Cases {

    static final List<String> FIXTURE = [
        "insert into app.entries (entry_id, group_id, kind, name, created_by) values" +
                " ('${ENTRY}', '${GROUP}', 'question', 'Customer complaint', '${SEEDER}')," +
                " ('${LIST_ENTRY}', '${GROUP}', 'reference_list', 'Complaint categories', '${SEEDER}')," +
                " ('${WORKFLOW_ENTRY}', '${GROUP}', 'workflow', 'Handle a complaint', '${SEEDER}')",
        "insert into app.entry_versions (entry_version_id, entry_id, entry_kind, number, created_by, approved_at," +
                " approved_by, approved_by_kind) values" +
                " ('${VERSION}', '${ENTRY}', 'question', 1, '${SEEDER}', now(), '${SEEDER}', 'seeder')," +
                " ('${LIST_VERSION}', '${LIST_ENTRY}', 'reference_list', 1, '${SEEDER}', now(), '${SEEDER}', 'seeder')," +
                " ('${WORKFLOW_VERSION}', '${WORKFLOW_ENTRY}', 'workflow', 1, '${SEEDER}', now(), '${SEEDER}', 'seeder')," +
                " ('${NEXT_WORKFLOW_VERSION}', '${WORKFLOW_ENTRY}', 'workflow', 2, '${SEEDER}', now(), '${SEEDER}', 'seeder')",
        "insert into app.entry_versions (entry_version_id, entry_id, entry_kind, number, created_by)" +
                " values ('${DRAFT}', '${ENTRY}', 'question', 2, '${STEWARD}')",
        "insert into app.entry_version_submissions (entry_version_submission_id, entry_version_id, created_by)" +
                " values ('${SUBMISSION}', '${DRAFT}', '${STEWARD}')",
        "insert into app.entry_version_writers (entry_version_id, created_by) values ('${DRAFT}', '${STEWARD}')",
        "insert into app.entry_stops (entry_stop_id, entry_id, created_by) values ('${STOP}', '${ENTRY}', '${STEWARD}')",
    ]

    static final List<Map<String, String>> CASES = [
        attack("entries_pk", "entries",
                "insert into app.entries (entry_id, group_id, kind, name, created_by)" +
                        " values ('${ENTRY}', '${GROUP}', 'question', 'Refund request', '${STEWARD}')"),
        attack("entries_group_fk", "entries",
                "insert into app.entries (entry_id, group_id, kind, name, created_by)" +
                        " values ('${SPARE_ENTRY}', '${ABSENT}', 'question', 'Refund request', '${STEWARD}')"),
        attack("entries_author_fk", "entries",
                "insert into app.entries (entry_id, group_id, kind, name, created_by)" +
                        " values ('${SPARE_ENTRY}', '${GROUP}', 'question', 'Refund request', '${ABSENT}')"),
        attack("entries_editor_person_fk", "entries",
                "update app.entries set updated_at = now(), updated_by = '${SEEDER}' where entry_id = '${ENTRY}'"),
        attack("entries_editor_is_person", "entries",
                "update app.entries set updated_at = now(), updated_by = '${WORKFLOW_RUNNER}'," +
                        " updated_by_kind = 'system' where entry_id = '${ENTRY}'"),
        attack("entries_name_unique", "entries",
                "insert into app.entries (entry_id, group_id, kind, name, created_by)" +
                        " values ('${SPARE_ENTRY}', '${GROUP}', 'question', 'CUSTOMER COMPLAINT', '${STEWARD}')"),
        attack("entries_updated_together", "entries",
                "update app.entries set updated_at = now() where entry_id = '${ENTRY}'"),
        attack("entries_updated_after_created", "entries",
                "update app.entries set updated_at = created_at - interval '1 day'," +
                        " updated_by = '${STEWARD}' where entry_id = '${ENTRY}'"),
        attack("entries_name_visible", "entries",
                "insert into app.entries (entry_id, group_id, kind, name, created_by)" +
                        " values ('${SPARE_ENTRY}', '${GROUP}', 'question', 'Refund' || chr(10) || 'request', '${STEWARD}')"),
        attack("entries_name_bounded", "entries",
                "insert into app.entries (entry_id, group_id, kind, name, created_by)" +
                        " values ('${SPARE_ENTRY}', '${GROUP}', 'question', repeat('a', 129), '${STEWARD}')"),
        attack("entries_purpose_visible", "entries", setting("entries", "purpose = 'For' || chr(10) || 'complaints'")),
        attack("entries_purpose_bounded", "entries", setting("entries", "purpose = repeat('a', 513)")),

        attack("entry_versions_pk", "entry_versions",
                "insert into app.entry_versions (entry_version_id, entry_id, entry_kind, number, created_by, approved_at," +
                        " approved_by, approved_by_kind)" +
                        " values ('${VERSION}', '${ENTRY}', 'question', 3, '${SEEDER}', now(), '${SEEDER}', 'seeder')"),
        attack("entry_versions_entry_fk", "entry_versions",
                "insert into app.entry_versions (entry_id, entry_kind, number, created_by)" +
                        " values ('${LIST_ENTRY}', 'question', 2, '${STEWARD}')"),
        attack("entry_versions_number_unique", "entry_versions",
                "insert into app.entry_versions (entry_id, entry_kind, number, created_by, approved_at, approved_by," +
                        " approved_by_kind) values ('${ENTRY}', 'question', 1, '${SEEDER}', now(), '${SEEDER}', 'seeder')"),
        attack("entry_versions_number_positive", "entry_versions",
                "insert into app.entry_versions (entry_id, entry_kind, number, created_by, approved_at, approved_by," +
                        " approved_by_kind) values ('${ENTRY}', 'question', 0, '${SEEDER}', now(), '${SEEDER}', 'seeder')"),
        attack("entry_versions_revision_positive", "entry_versions",
                "update app.entry_versions set revision = 0 where entry_version_id = '${DRAFT}'"),
        attack("entry_versions_author_fk", "entry_versions",
                "insert into app.entry_versions (entry_id, entry_kind, number, created_by, approved_at, approved_by)" +
                        " values ('${ENTRY}', 'question', 3, '${ABSENT}', now(), '${MEMBER}')"),
        attack("entry_versions_approver_kind_fk", "entry_versions",
                "insert into app.entry_versions (entry_id, entry_kind, number, created_by, approved_at, approved_by)" +
                        " values ('${ENTRY}', 'question', 3, '${SEEDER}', now(), '${ABSENT}')"),
        attack("entry_versions_approver_is_person_or_seeder", "entry_versions",
                "insert into app.entry_versions (entry_id, entry_kind, number, created_by, approved_at, approved_by," +
                        " approved_by_kind) values ('${ENTRY}', 'question', 3, '${SEEDER}', now(), '${WORKFLOW_RUNNER}', 'system')"),
        attack("entry_versions_approver_is_opener_exactly_for_seeder", "entry_versions",
                "update app.entry_versions set approved_at = now(), approved_by = '${STEWARD}'" +
                        " where entry_version_id = '${DRAFT}'"),
        attack("entry_versions_seeder_approves_as_it_opens", "entry_versions",
                "insert into app.entry_versions (entry_id, entry_kind, number, created_at, created_by, approved_at," +
                        " approved_by, approved_by_kind) values ('${ENTRY}', 'question', 3, now() - interval '1 day'," +
                        " '${SEEDER}', now(), '${SEEDER}', 'seeder')"),
        attack("entry_versions_retirer_kind_fk", "entry_versions",
                "update app.entry_versions set retired_at = now(), retired_by = '${ABSENT}'" +
                        " where entry_version_id = '${VERSION}'"),
        attack("entry_versions_retirer_is_person_or_seeder", "entry_versions",
                "update app.entry_versions set retired_at = now(), retired_by = '${WORKFLOW_RUNNER}'," +
                        " retired_by_kind = 'system' where entry_version_id = '${VERSION}'"),
        attack("entry_versions_seeder_retires_as_it_opens", "entry_versions",
                "update app.entry_versions set retired_at = now(), retired_by = '${SEEDER}'," +
                        " retired_by_kind = 'seeder' where entry_version_id = '${VERSION}'"),
        attack("entry_versions_approved_together", "entry_versions",
                "update app.entry_versions set approved_at = now() where entry_version_id = '${DRAFT}'"),
        attack("entry_versions_approved_after_created", "entry_versions",
                "update app.entry_versions set approved_at = created_at - interval '1 day'," +
                        " approved_by = '${MEMBER}' where entry_version_id = '${DRAFT}'"),
        attack("entry_versions_retired_together", "entry_versions",
                "update app.entry_versions set retired_at = now() where entry_version_id = '${VERSION}'"),
        attack("entry_versions_retired_after_approved", "entry_versions",
                "update app.entry_versions set retired_at = approved_at - interval '1 day'," +
                        " retired_by = '${STEWARD}' where entry_version_id = '${VERSION}'"),
        attack("entry_versions_retired_once_approved", "entry_versions",
                "update app.entry_versions set retired_at = now(), retired_by = '${STEWARD}'" +
                        " where entry_version_id = '${DRAFT}'"),
        attack("entry_versions_one_unapproved", "entry_versions",
                "insert into app.entry_versions (entry_id, entry_kind, number, created_by)" +
                        " values ('${ENTRY}', 'question', 3, '${STEWARD}')"),

        attack("entry_version_submissions_pk", "entry_version_submissions",
                "insert into app.entry_version_submissions (entry_version_submission_id, entry_version_id, created_by)" +
                        " values ('${SUBMISSION}', '${VERSION}', '${STEWARD}')"),
        attack("entry_version_submissions_version_fk", "entry_version_submissions",
                "insert into app.entry_version_submissions (entry_version_id, created_by)" +
                        " values ('${ABSENT}', '${STEWARD}')"),
        attack("entry_version_submissions_author_kind_fk", "entry_version_submissions",
                "insert into app.entry_version_submissions (entry_version_id, created_by)" +
                        " values ('${VERSION}', '${ABSENT}')"),
        attack("entry_version_submissions_author_is_person_or_seeder", "entry_version_submissions",
                "insert into app.entry_version_submissions (entry_version_id, created_by, created_by_kind)" +
                        " values ('${VERSION}', '${WORKFLOW_RUNNER}', 'system')"),
        attack("entry_version_submissions_withdrawer_person_fk", "entry_version_submissions",
                "update app.entry_version_submissions set withdrawn_at = now(), withdrawn_by = '${SEEDER}'" +
                        " where entry_version_submission_id = '${SUBMISSION}'"),
        attack("entry_version_submissions_withdrawer_is_person", "entry_version_submissions",
                "update app.entry_version_submissions set withdrawn_at = now(), withdrawn_by = '${WORKFLOW_RUNNER}'," +
                        " withdrawn_by_kind = 'system' where entry_version_submission_id = '${SUBMISSION}'"),
        attack("entry_version_submissions_withdrawn_together", "entry_version_submissions",
                "update app.entry_version_submissions set withdrawn_at = now()" +
                        " where entry_version_submission_id = '${SUBMISSION}'"),
        attack("entry_version_submissions_withdrawn_after_created", "entry_version_submissions",
                "update app.entry_version_submissions set withdrawn_at = created_at - interval '1 day'," +
                        " withdrawn_by = '${STEWARD}' where entry_version_submission_id = '${SUBMISSION}'"),
        attack("entry_version_submissions_one_open", "entry_version_submissions",
                "insert into app.entry_version_submissions (entry_version_id, created_by)" +
                        " values ('${DRAFT}', '${STEWARD}')"),

        attack("entry_version_writers_pk", "entry_version_writers",
                "insert into app.entry_version_writers (entry_version_id, created_by) values ('${DRAFT}', '${STEWARD}')"),
        attack("entry_version_writers_version_fk", "entry_version_writers",
                "insert into app.entry_version_writers (entry_version_id, created_by) values ('${ABSENT}', '${MEMBER}')"),
        attack("entry_version_writers_author_person_fk", "entry_version_writers",
                "insert into app.entry_version_writers (entry_version_id, created_by) values ('${DRAFT}', '${SEEDER}')"),
        attack("entry_version_writers_author_is_person", "entry_version_writers",
                "insert into app.entry_version_writers (entry_version_id, created_by, created_by_kind)" +
                        " values ('${DRAFT}', '${WORKFLOW_RUNNER}', 'system')"),

        attack("entry_stops_pk", "entry_stops",
                "insert into app.entry_stops (entry_stop_id, entry_id, created_by, let_go_at, let_go_by)" +
                        " values ('${STOP}', '${ENTRY}', '${STEWARD}', now(), '${STEWARD}')"),
        attack("entry_stops_entry_fk", "entry_stops",
                "insert into app.entry_stops (entry_id, created_by, let_go_at, let_go_by)" +
                        " values ('${ABSENT}', '${STEWARD}', now(), '${STEWARD}')"),
        attack("entry_stops_author_person_fk", "entry_stops",
                "insert into app.entry_stops (entry_id, created_by, let_go_at, let_go_by)" +
                        " values ('${ENTRY}', '${SEEDER}', now(), '${STEWARD}')"),
        attack("entry_stops_author_is_person", "entry_stops",
                "insert into app.entry_stops (entry_id, created_by, created_by_kind, let_go_at, let_go_by)" +
                        " values ('${ENTRY}', '${WORKFLOW_RUNNER}', 'system', now(), '${STEWARD}')"),
        attack("entry_stops_let_go_person_fk", "entry_stops",
                "update app.entry_stops set let_go_at = now(), let_go_by = '${SEEDER}' where entry_stop_id = '${STOP}'"),
        attack("entry_stops_let_go_is_person", "entry_stops",
                "update app.entry_stops set let_go_at = now(), let_go_by = '${WORKFLOW_RUNNER}'," +
                        " let_go_by_kind = 'system' where entry_stop_id = '${STOP}'"),
        attack("entry_stops_let_go_together", "entry_stops",
                "update app.entry_stops set let_go_at = now() where entry_stop_id = '${STOP}'"),
        attack("entry_stops_let_go_after_created", "entry_stops",
                "update app.entry_stops set let_go_at = created_at - interval '1 day'," +
                        " let_go_by = '${STEWARD}' where entry_stop_id = '${STOP}'"),
        attack("entry_stops_one_in_force", "entry_stops",
                "insert into app.entry_stops (entry_id, created_by) values ('${ENTRY}', '${STEWARD}')"),
    ]

    private V7Cases() {}
}
