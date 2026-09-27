package org.lilradish.lite.migration.fixture

import static org.lilradish.lite.migration.fixture.SchemaKeys.*
import static org.lilradish.lite.migration.fixture.SchemaRows.*

/** V12__soundness.sql's rules: the rows they are proved beside, and a case attacking each. */
final class V12Cases {

    static final Map<String, String> A_RUNNING_CHECK = [soundness_check_id: literal(SPARE_CHECK), created_by: literal(MEMBER)]

    static final Map<String, String> AN_ENDED_CHECK = A_RUNNING_CHECK + [outcome: "'finished'"]

    /** OTHER_GROUP's count in CHECK, which counted only GROUP. */
    static final Map<String, String> A_COUNT = [soundness_check_id: literal(CHECK), group_id: literal(OTHER_GROUP),
                                                workflows: "3", unsound: "0"]

    static final List<String> FIXTURE = [
        insertInto("soundness_checks", AN_ENDED_CHECK + [soundness_check_id: literal(CHECK)]),
        insertInto("soundness_checks", A_RUNNING_CHECK + [soundness_check_id: literal(RUNNING_CHECK)]),
        insertInto("soundness_counts", A_COUNT + [group_id: literal(GROUP), unsound: "1"]),
    ]

    static final String DELETE_THE_CHECK = "delete from app.soundness_checks where soundness_check_id = '${CHECK}'"

    /** No fixture group is deleted by its count alone: every one is also named by rows of tables made earlier. */
    static final String DELETE_A_COUNTED_GROUP = "insert into app.groups (group_id, key, name, created_by)" +
            " values ('${SPARE_GROUP}', 'BILLING', 'Billing', '${STEWARD}'); " +
            insertInto("soundness_counts", A_COUNT + [group_id: literal(SPARE_GROUP)]) + "; " +
            "delete from app.groups where group_id = '${SPARE_GROUP}'"

    static final List<Map<String, String>> CASES = [
        attack("soundness_checks_pk", "soundness_checks", insertInto("soundness_checks",
                AN_ENDED_CHECK + [soundness_check_id: literal(CHECK)])),
        attack("soundness_checks_author_person_fk", "soundness_checks", insertInto("soundness_checks",
                AN_ENDED_CHECK + [created_by: literal(SEEDER)])),
        attack("soundness_checks_author_is_person", "soundness_checks", insertInto("soundness_checks",
                AN_ENDED_CHECK + [created_by: literal(WORKFLOW_RUNNER), created_by_kind: "'system'"])),
        attack("soundness_checks_one_running", "soundness_checks", insertInto("soundness_checks", A_RUNNING_CHECK)),

        attack("soundness_counts_pk", "soundness_counts", insertInto("soundness_counts",
                A_COUNT + [group_id: literal(GROUP)])),
        attack("soundness_counts_check_fk", "soundness_counts", insertInto("soundness_counts",
                A_COUNT + [soundness_check_id: literal(RUNNING_CHECK)])),
        attack("soundness_counts_check_is_finished", "soundness_counts", insertInto("soundness_counts",
                A_COUNT + [check_outcome: "'cut_short'"])),
        attack("soundness_counts_group_fk", "soundness_counts", insertInto("soundness_counts",
                A_COUNT + [group_id: literal(ABSENT)])),
        attack("soundness_counts_unsound_at_most_workflows", "soundness_counts", insertInto("soundness_counts",
                A_COUNT + [workflows: "1", unsound: "2"])),
        attack("soundness_counts_unsound_not_negative", "soundness_counts", insertInto("soundness_counts",
                A_COUNT + [workflows: "0", unsound: "-1"])),
    ]

    /** RUNNING_CHECK ended as named. */
    static String endTheRunningCheck(String outcome) {
        setting("soundness_checks", "outcome = '${outcome}'") + "; "
    }

    private V12Cases() {}
}
