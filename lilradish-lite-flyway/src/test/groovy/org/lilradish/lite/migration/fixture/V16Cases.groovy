package org.lilradish.lite.migration.fixture

import static org.lilradish.lite.migration.fixture.SchemaKeys.*
import static org.lilradish.lite.migration.fixture.SchemaRows.*
import static org.lilradish.lite.migration.fixture.V10Cases.*

final class V16Cases {

    static final String SOURCE_RUN_STEP = "00000020-0000-4000-8000-000000000001"

    // DB-SPECIFIC: data-modifying with uses PostgreSQL's syntax and semantics.
    static final String REACH_SOURCE = "with source_reached as (" +
            insertInto("run_steps", A_RUN_STEP + [run_step_id: literal(SOURCE_RUN_STEP)]) + ") "

    static final Map<String, String> A_SOURCE_HOLD = A_SEARCH_HOLD + [
        run_step_id: literal(REVIEWED_CODE_RUN_STEP), run_id: literal(CODE_RUN), run_step_kind: "'code_step'",
        reason: "'code_step_not_held'", root_run_id: literal(CODE_RUN), source_run_id: literal(CODE_RUN),
        source_run_step_id: literal(CODE_RUN_STEP), source_workflow_step_id: literal(SUB_STEP),
    ]

    static final Map<String, String> A_LATER_SOURCE_HOLD = A_SEARCH_HOLD + [
        reason: "'code_step_not_held'", root_run_id: literal(RUN), source_run_id: literal(SUB_RUN),
        source_run_step_id: literal(SOURCE_RUN_STEP), source_workflow_step_id: literal(SUB_STEP),
    ]

    static final Map<String, String> A_CURRENT_CODE_HOLD = A_HOLD + [
        run_step_id: literal(CODE_RUN_STEP), run_id: literal(CODE_RUN), run_step_kind: "'code_step'",
        reason: "'code_step_not_held'",
    ]

    static final Map<String, String> A_SOURCE_FAILURE = A_FAILURE + [
        reason: "'source_no_longer_matches'", detail: "null", root_run_id: literal(RUN),
        source_run_id: literal(SUB_RUN), source_run_step_id: literal(SOURCE_RUN_STEP),
        source_workflow_step_id: literal(SUB_STEP), source_field: "'answer'",
    ]

    // DB-SPECIFIC: now() uses PostgreSQL's syntax and semantics.
    static final String RELEASE_QUESTION = "update app.run_step_holds set released_at = now()," +
            " released_by = '${WORKFLOW_RUNNER}', released_by_kind = 'system'" +
            " where run_step_hold_id = '${HOLD}'; "

    static final List<Map<String, String>> CASES = [
        attack("run_step_holds_source_together", "run_step_holds",
                insertInto("run_step_holds", A_HOLD + [root_run_id: literal(RUN)])),
        attack("run_step_holds_source_only_for_code_step_not_held", "run_step_holds",
                insertInto("run_step_holds", A_SOURCE_HOLD + [reason: "'entry_stopped'"])),
        attack("run_step_holds_root_fk", "run_step_holds",
                insertInto("run_step_holds", A_SOURCE_HOLD + [run_step_id: literal(SEARCH_RUN_STEP),
                        run_id: literal(RUN), run_step_kind: "'search'"])),
        attack("run_step_holds_source_root_fk", "run_step_holds",
                REACH_SOURCE + insertInto("run_step_holds", A_SOURCE_HOLD + [source_run_id: literal(SUB_RUN),
                        source_run_step_id: literal(SOURCE_RUN_STEP)])),
        attack("run_step_holds_source_step_fk", "run_step_holds",
                insertInto("run_step_holds", A_SOURCE_HOLD + [source_workflow_step_id: literal(STEP)])),
        attack("run_step_failures_source_together", "run_step_failures",
                insertInto("run_step_failures", A_FAILURE + [root_run_id: literal(RUN)])),
        attack("run_step_failures_source_exactly_for_a_source_failure", "run_step_failures",
                insertInto("run_step_failures", A_FAILURE + [reason: "'source_no_longer_matches'"])),
        attack("run_step_failures_source_only_for_produce", "run_step_failures",
                REACH_SOURCE + insertInto("run_step_failures", A_SOURCE_FAILURE + [
                        run_step_id: literal(REVIEWED_RUN_STEP), production_id: literal(REVIEWED_ANSWER),
                        purpose: "'review'"])),
        attack("run_step_failures_source_field_shape", "run_step_failures",
                REACH_SOURCE + insertInto("run_step_failures", A_SOURCE_FAILURE + [source_field: "'Answer'"])),
        attack("run_step_failures_source_field_bounded", "run_step_failures",
                REACH_SOURCE + insertInto("run_step_failures", A_SOURCE_FAILURE + [source_field: literal("a." * 512 + "a")])),
        attack("run_step_failures_root_fk", "run_step_failures",
                insertInto("run_step_failures", A_SOURCE_FAILURE + [root_run_id: literal(CODE_RUN),
                        source_run_id: literal(CODE_RUN), source_run_step_id: literal(CODE_RUN_STEP)])),
        attack("run_step_failures_source_root_fk", "run_step_failures",
                insertInto("run_step_failures", A_SOURCE_FAILURE + [source_run_id: literal(CODE_RUN),
                        source_run_step_id: literal(CODE_RUN_STEP)])),
        attack("run_step_failures_source_step_fk", "run_step_failures",
                REACH_SOURCE + insertInto("run_step_failures", A_SOURCE_FAILURE + [source_workflow_step_id: literal(STEP)])),
    ]

    private V16Cases() {}
}
