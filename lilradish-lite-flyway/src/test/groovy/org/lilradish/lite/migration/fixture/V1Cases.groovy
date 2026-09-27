package org.lilradish.lite.migration.fixture

import static org.lilradish.lite.migration.fixture.SchemaKeys.*
import static org.lilradish.lite.migration.fixture.SchemaRows.*

/** V1__subject.sql's rules: the rows they are proved beside, and a case attacking each. */
final class V1Cases {

    static final List<String> FIXTURE = [
        "insert into app.subjects (subject_id, kind, user_id, created_by)" +
                " values ('${MEMBER}', 'person', '000002', '${SEEDER}')",
        "insert into app.subjects (subject_id, kind, user_id, created_by)" +
                " values ('${UNPOOLED}', 'person', '000003', '${SEEDER}')",
    ]

    /** Each row breaks only the rule it names: a check answers before a unique index, and a unique index
     * before a foreign key, so a row attacking a later rule keeps clear of every earlier one. */
    static final List<Map<String, String>> CASES = [
        attack("subjects_pk", "subjects",
                "insert into app.subjects (subject_id, kind, user_id, created_by)" +
                        " values ('${STEWARD}', 'person', '000099', '${SEEDER}')"),
        attack("subjects_author_fk", "subjects",
                "insert into app.subjects (subject_id, kind, user_id, created_by)" +
                        " values ('${SPARE_SUBJECT}', 'person', '000099', '${ABSENT}')"),
        attack("subjects_user_unique", "subjects",
                "insert into app.subjects (subject_id, kind, user_id, created_by)" +
                        " values ('${SPARE_SUBJECT}', 'person', '000001', '${SEEDER}')"),
        attack("subjects_user_only_for_people", "subjects",
                "insert into app.subjects (subject_id, kind, created_by)" +
                        " values ('${SPARE_SUBJECT}', 'person', '${SEEDER}')"),
        attack("subjects_user_id_visible", "subjects",
                "insert into app.subjects (subject_id, kind, user_id, created_by)" +
                        " values ('${SPARE_SUBJECT}', 'person', '00' || chr(9) || '99', '${SEEDER}')"),
        attack("subjects_user_id_bounded", "subjects",
                "insert into app.subjects (subject_id, kind, user_id, created_by)" +
                        " values ('${SPARE_SUBJECT}', 'person', repeat('9', 257), '${SEEDER}')"),
        attack("subjects_display_name_only_for_people", "subjects",
                "insert into app.subjects (subject_id, kind, display_name, created_by)" +
                        " values ('${SPARE_SUBJECT}', 'system', 'Scheduler', '${SEEDER}')"),
        attack("subjects_display_name_visible", "subjects",
                "insert into app.subjects (subject_id, kind, user_id, display_name, created_by)" +
                        " values ('${SPARE_SUBJECT}', 'person', '000099', 'Ada' || chr(9) || 'Lovelace', '${SEEDER}')"),
        attack("subjects_display_name_bounded", "subjects",
                "insert into app.subjects (subject_id, kind, user_id, display_name, created_by)" +
                        " values ('${SPARE_SUBJECT}', 'person', '000099', repeat('a', 257), '${SEEDER}')"),
    ]

    private V1Cases() {}
}
