package org.lilradish.lite.migration.fixture

import static org.lilradish.lite.migration.fixture.SchemaKeys.*
import static org.lilradish.lite.migration.fixture.SchemaRows.*

/** V3__estate_role.sql's rules: the rows they are proved beside, and a case attacking each. */
final class V3Cases {

    static final List<String> FIXTURE = [
        "insert into app.estate_role_grants (estate_role_grant_id, subject_id, role, created_by)" +
                " values ('${GRANT}', '${MEMBER}', 'watcher', '${STEWARD}')",
    ]

    static final List<Map<String, String>> CASES = [
        attack("estate_role_grants_pk", "estate_role_grants",
                "insert into app.estate_role_grants (estate_role_grant_id, subject_id, role, created_by)" +
                        " values ('${GRANT}', '${MEMBER}', 'steward', '${STEWARD}')"),
        attack("estate_role_grants_person_fk", "estate_role_grants",
                "insert into app.estate_role_grants (subject_id, role, created_by)" +
                        " values ('${SEEDER}', 'watcher', '${STEWARD}')"),
        attack("estate_role_grants_is_person", "estate_role_grants",
                "insert into app.estate_role_grants (subject_id, subject_kind, role, created_by)" +
                        " values ('${WORKFLOW_RUNNER}', 'system', 'watcher', '${STEWARD}')"),
        attack("estate_role_grants_author_fk", "estate_role_grants",
                "insert into app.estate_role_grants (subject_id, role, created_by)" +
                        " values ('${MEMBER}', 'steward', '${ABSENT}')"),
        attack("estate_role_grants_remover_person_fk", "estate_role_grants",
                "update app.estate_role_grants set removed_at = now(), removed_by = '${SEEDER}'" +
                        " where estate_role_grant_id = '${GRANT}'"),
        attack("estate_role_grants_remover_is_person", "estate_role_grants",
                "update app.estate_role_grants set removed_at = now(), removed_by = '${WORKFLOW_RUNNER}'," +
                        " removed_by_kind = 'system' where estate_role_grant_id = '${GRANT}'"),
        attack("estate_role_grants_removed_together", "estate_role_grants",
                "update app.estate_role_grants set removed_at = now() where estate_role_grant_id = '${GRANT}'"),
        attack("estate_role_grants_removed_after_created", "estate_role_grants",
                "update app.estate_role_grants set removed_at = created_at - interval '1 day'," +
                        " removed_by = '${STEWARD}' where estate_role_grant_id = '${GRANT}'"),
        attack("estate_role_grants_one_current_holding", "estate_role_grants",
                "insert into app.estate_role_grants (subject_id, role, created_by)" +
                        " values ('${MEMBER}', 'watcher', '${STEWARD}')"),
    ]

    private V3Cases() {}
}
