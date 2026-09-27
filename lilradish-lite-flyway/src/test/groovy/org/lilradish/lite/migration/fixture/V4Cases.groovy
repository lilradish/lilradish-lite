package org.lilradish.lite.migration.fixture

import static org.lilradish.lite.migration.fixture.SchemaKeys.*
import static org.lilradish.lite.migration.fixture.SchemaRows.*

/** V4__group.sql's rules: the rows they are proved beside, and a case attacking each. */
final class V4Cases {

    static final List<String> FIXTURE = [
        "insert into app.groups (group_id, key, name, created_by)" +
                " values ('${GROUP}', 'SUPPORT', 'Customer support', '${SEEDER}')",
        "insert into app.groups (group_id, key, name, created_by)" +
                " values ('${OTHER_GROUP}', 'FINANCE', 'Finance', '${SEEDER}')",
        "insert into app.group_members (group_member_id, group_id, subject_id, role, created_by)" +
                " values ('${HOLDING}', '${GROUP}', '${STEWARD}', 'owner', '${STEWARD}')",
    ]

    static final List<Map<String, String>> CASES = [
        attack("groups_pk", "groups",
                "insert into app.groups (group_id, key, name, created_by)" +
                        " values ('${GROUP}', 'BILLING', 'Billing', '${STEWARD}')"),
        attack("groups_author_fk", "groups",
                "insert into app.groups (group_id, key, name, created_by)" +
                        " values ('${SPARE_GROUP}', 'BILLING', 'Billing', '${ABSENT}')"),
        attack("groups_editor_person_fk", "groups",
                "update app.groups set updated_at = now(), updated_by = '${SEEDER}'" +
                        " where group_id = '${GROUP}'"),
        attack("groups_editor_is_person", "groups",
                "update app.groups set updated_at = now(), updated_by = '${WORKFLOW_RUNNER}'," +
                        " updated_by_kind = 'system' where group_id = '${GROUP}'"),
        attack("groups_key_unique", "groups",
                "insert into app.groups (group_id, key, name, created_by)" +
                        " values ('${SPARE_GROUP}', 'SUPPORT', 'Billing', '${STEWARD}')"),
        attack("groups_key_shape", "groups",
                "insert into app.groups (group_id, key, name, created_by)" +
                        " values ('${SPARE_GROUP}', 'Billing', 'Billing', '${STEWARD}')"),
        attack("groups_name_unique", "groups",
                "insert into app.groups (group_id, key, name, created_by)" +
                        " values ('${SPARE_GROUP}', 'BILLING', 'CUSTOMER SUPPORT', '${STEWARD}')"),
        attack("groups_updated_together", "groups",
                "update app.groups set updated_at = now() where group_id = '${GROUP}'"),
        attack("groups_updated_after_created", "groups",
                "update app.groups set updated_at = created_at - interval '1 day'," +
                        " updated_by = '${STEWARD}' where group_id = '${GROUP}'"),
        attack("groups_name_visible", "groups",
                "insert into app.groups (group_id, key, name, created_by)" +
                        " values ('${SPARE_GROUP}', 'BILLING', 'Customer' || chr(10) || 'support', '${STEWARD}')"),
        attack("groups_name_bounded", "groups",
                "insert into app.groups (group_id, key, name, created_by)" +
                        " values ('${SPARE_GROUP}', 'BILLING', repeat('F', 129), '${STEWARD}')"),

        attack("group_members_pk", "group_members",
                "insert into app.group_members (group_member_id, group_id, subject_id, role, created_by)" +
                        " values ('${HOLDING}', '${GROUP}', '${MEMBER}', 'operator', '${STEWARD}')"),
        attack("group_members_group_fk", "group_members",
                "insert into app.group_members (group_id, subject_id, role, created_by)" +
                        " values ('${ABSENT}', '${MEMBER}', 'operator', '${STEWARD}')"),
        attack("group_members_person_fk", "group_members",
                "insert into app.group_members (group_id, subject_id, role, created_by)" +
                        " values ('${GROUP}', '${SEEDER}', 'operator', '${STEWARD}')"),
        attack("group_members_is_person", "group_members",
                "insert into app.group_members (group_id, subject_id, subject_kind, role, created_by)" +
                        " values ('${GROUP}', '${WORKFLOW_RUNNER}', 'system', 'operator', '${STEWARD}')"),
        attack("group_members_author_fk", "group_members",
                "insert into app.group_members (group_id, subject_id, role, created_by)" +
                        " values ('${GROUP}', '${MEMBER}', 'operator', '${ABSENT}')"),
        attack("group_members_remover_person_fk", "group_members",
                "update app.group_members set removed_at = now(), removed_by = '${SEEDER}'," +
                        " removal = 'removed_from_group' where group_member_id = '${HOLDING}'"),
        attack("group_members_remover_is_person", "group_members",
                "update app.group_members set removed_at = now(), removed_by = '${WORKFLOW_RUNNER}'," +
                        " removed_by_kind = 'system', removal = 'removed_from_group'" +
                        " where group_member_id = '${HOLDING}'"),
        attack("group_members_removed_together", "group_members",
                "update app.group_members set removed_at = now(), removal = 'role_taken'" +
                        " where group_member_id = '${HOLDING}'"),
        attack("group_members_removed_after_created", "group_members",
                "update app.group_members set removed_at = created_at - interval '1 day'," +
                        " removed_by = '${STEWARD}', removal = 'role_taken' where group_member_id = '${HOLDING}'"),
        attack("group_members_one_current_holding", "group_members",
                "insert into app.group_members (group_id, subject_id, role, created_by)" +
                        " values ('${GROUP}', '${STEWARD}', 'owner', '${STEWARD}')"),
    ]

    private V4Cases() {}
}
