package com.lilradish.lite.migration.fixture

import static com.lilradish.lite.migration.fixture.RunCases.A_SEARCH_FAILURE
import static com.lilradish.lite.migration.fixture.SchemaKeys.*
import static com.lilradish.lite.migration.fixture.SchemaRows.*
import static com.lilradish.lite.migration.fixture.StepAndDeclarationCases.A_STEP

/** Search question markers and the separate ending of a failure made before a send. */
final class SearchQuestionCases {

    // DB-SPECIFIC: The maps and cases below use PostgreSQL native run_step_failure_reason,
    // step_kind, entry_kind and subject_kind enum labels, transaction-time now() and verbose
    // interval quantity/unit input.
    static final Map<String, String> A_SHAPE_FAILURE = A_SEARCH_FAILURE + [
        reason: "'search_question_no_longer_matches'", production_id: "null", purpose: "null", tool: "null",
        detail: "'Find evidence · Version 1'",
    ]

    static final List<Map<String, String>> CASES = [
        attack("question_versions_search_way_shape", "question_versions",
                setting("question_versions", "search_way = 'Web'")),
        attack("workflow_steps_search_pins_question", "workflow_steps",
                insertInto("workflow_steps", A_STEP + [kind: "'search'",
                    pinned_version_id: literal(NEXT_WORKFLOW_VERSION), pinned_kind: "'workflow'"])),
        attack("run_step_failures_search_question_only_for_search", "run_step_failures",
                insertInto("run_step_failures", A_SHAPE_FAILURE + [detail: "null"])),
        attack("run_step_failures_resolved_together", "run_step_failures",
                insertInto("run_step_failures", A_SHAPE_FAILURE + [resolved_by: literal(STEWARD)])),
        attack("run_step_failures_resolved_only_for_search_question", "run_step_failures",
                insertInto("run_step_failures", A_SEARCH_FAILURE + [resolved_by: literal(STEWARD), resolved_at: "now()"])),
        attack("run_step_failures_resolved_not_before_failure", "run_step_failures",
                insertInto("run_step_failures", A_SHAPE_FAILURE + [created_at: "now()", resolved_by: literal(STEWARD),
                    resolved_at: "now() - interval '1 second'"])),
        attack("run_step_failures_resolver_is_person", "run_step_failures",
                insertInto("run_step_failures", A_SHAPE_FAILURE + [resolved_by: literal(WORKFLOW_RUNNER),
                    resolved_by_kind: "'system'", resolved_at: "now()"])),
        attack("run_step_failures_resolver_person_fk", "run_step_failures",
                insertInto("run_step_failures", A_SHAPE_FAILURE + [resolved_by: literal(ABSENT), resolved_at: "now()"])),
    ]

    private SearchQuestionCases() {}
}
