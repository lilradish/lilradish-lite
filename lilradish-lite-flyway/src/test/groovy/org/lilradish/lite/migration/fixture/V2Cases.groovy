package org.lilradish.lite.migration.fixture

import static org.lilradish.lite.migration.fixture.SchemaKeys.*
import static org.lilradish.lite.migration.fixture.SchemaRows.*

/** V2__pool.sql's rules: the rows they are proved beside, and a case attacking each. */
final class V2Cases {

    static final List<String> FIXTURE = [
        "insert into app.pool_members (pool_member_id, subject_id, created_by)" +
                " values ('${STAY}', '${MEMBER}', '${STEWARD}')",
    ]

    static final List<Map<String, String>> CASES = [
        attack("pool_members_pk", "pool_members",
                "insert into app.pool_members (pool_member_id, subject_id, created_by)" +
                        " values ('${STAY}', '${UNPOOLED}', '${STEWARD}')"),
        attack("pool_members_person_fk", "pool_members",
                "insert into app.pool_members (subject_id, created_by) values ('${SEEDER}', '${STEWARD}')"),
        attack("pool_members_is_person", "pool_members",
                "insert into app.pool_members (subject_id, subject_kind, created_by)" +
                        " values ('${WORKFLOW_RUNNER}', 'system', '${STEWARD}')"),
        attack("pool_members_author_fk", "pool_members",
                "insert into app.pool_members (subject_id, created_by) values ('${UNPOOLED}', '${ABSENT}')"),
        attack("pool_members_remover_person_fk", "pool_members",
                "update app.pool_members set removed_at = now(), removed_by = '${SEEDER}'" +
                        " where pool_member_id = '${STAY}'"),
        attack("pool_members_remover_is_person", "pool_members",
                "update app.pool_members set removed_at = now(), removed_by = '${WORKFLOW_RUNNER}'," +
                        " removed_by_kind = 'system' where pool_member_id = '${STAY}'"),
        attack("pool_members_removed_together", "pool_members",
                "update app.pool_members set removed_at = now() where pool_member_id = '${STAY}'"),
        attack("pool_members_removed_after_created", "pool_members",
                "update app.pool_members set removed_at = created_at - interval '1 day'," +
                        " removed_by = '${STEWARD}' where pool_member_id = '${STAY}'"),
        attack("pool_members_one_current_stay", "pool_members",
                "insert into app.pool_members (subject_id, created_by) values ('${MEMBER}', '${STEWARD}')"),
    ]

    private V2Cases() {}
}
