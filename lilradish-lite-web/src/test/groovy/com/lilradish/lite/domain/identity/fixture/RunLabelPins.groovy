package com.lilradish.lite.domain.identity.fixture

/** Labels pinned on the tables of runs, their steps, holds, failures, stops and ceiling changes. */
final class RunLabelPins {

    static final Map<String, List<String>> PINS = [
        "run_ceiling_changes.created_by_kind": ["subject_kind:person"],
        "run_ceiling_changes.decided_by_kind": ["subject_kind:person"],
        "run_ceiling_changes.run_ceiling_changes_author_is_person": ["subject_kind:person"],
        "run_ceiling_changes.run_ceiling_changes_decider_matches_outcome":
                ["run_ceiling_change_outcome:superseded_by_change", "run_ceiling_change_outcome:withdrawn_by_requester"],
        "run_ceiling_changes.run_ceiling_changes_decider_is_person": ["subject_kind:person"],
        "run_step_failures.created_by_kind": ["subject_kind:system"],
        "run_step_failures.produced_production_id": ["model_call_purpose:produce"],
        "run_step_failures.production_producer": ["step_producer:model"],
        "run_step_failures.reviewed_production_id": ["model_call_purpose:review"],
        "run_step_failures.resolved_by_kind": ["subject_kind:person"],
        "run_step_failures.run_step_failures_resolver_is_person": ["subject_kind:person"],
        "run_step_failures.run_step_failures_search_question_only_for_search": ["run_step_kind:search"],
        "run_step_failures.source_run_step_kind": ["run_step_kind:code_step"],
        "run_step_failures.run_step_failures_source_exactly_for_a_source_failure":
                ["run_step_failure_reason:source_list_not_here", "run_step_failure_reason:source_no_longer_matches"],
        "run_step_failures.run_step_failures_source_only_for_produce":
                ["model_call_purpose:produce", "run_step_failure_reason:source_list_not_here", "run_step_failure_reason:source_no_longer_matches"],
        "run_step_failures.run_step_failures_author_is_system": ["subject_kind:system"],
        "run_step_failures.run_step_failures_detail_for_length_or_undeployed":
                ["run_step_failure_reason:model_not_deployed", "run_step_failure_reason:uncuttable_length"],
        "run_step_failures.run_step_failures_sending_failure_only_for_calls_a_model":
                ["run_step_failure_reason:model_not_deployed", "run_step_failure_reason:source_list_not_here", "run_step_failure_reason:source_no_longer_matches",
                 "run_step_failure_reason:tool_not_offered", "run_step_failure_reason:uncuttable_length"],
        "run_step_failures.run_step_failures_production_exactly_for_a_sending_failure":
                ["run_step_failure_reason:model_not_deployed", "run_step_failure_reason:source_list_not_here", "run_step_failure_reason:source_no_longer_matches",
                 "run_step_failure_reason:tool_not_offered", "run_step_failure_reason:uncuttable_length"],
        "run_step_failures.run_step_failures_tool_not_offered_only_for_search_produce":
                ["model_call_purpose:produce", "run_step_failure_reason:tool_not_offered", "run_step_kind:search"],
        "run_step_failures.run_step_failures_tool_exactly_for_tool_not_offered":
                ["run_step_failure_reason:tool_not_offered"],
        "run_step_failures.run_step_failures_no_detail_for_too_deep": ["run_step_failure_reason:too_deep"],
        "run_step_failures.run_step_failures_production_is_model": ["step_producer:model"],
        "run_step_failures.run_step_failures_purpose_is_produce_or_review":
                ["model_call_purpose:produce", "model_call_purpose:review"],
        "run_step_failures.run_step_failures_too_deep_only_for_workflow_or_route":
                ["run_step_failure_reason:too_deep", "run_step_kind:route", "run_step_kind:workflow"],
        "run_step_failures.run_step_failures_unclaimed_value_only_for_route":
                ["run_step_failure_reason:unclaimed_value", "run_step_kind:route"],
        "run_step_failures.run_step_failures_uncuttable_length_only_for_produce":
                ["model_call_purpose:produce", "run_step_failure_reason:uncuttable_length"],
        "run_step_holds.attempt_purpose": ["model_call_purpose:produce"],
        "run_step_holds.attempt_too_long": ["run_step_hold_reason:too_long"],
        "run_step_holds.created_by_kind": ["subject_kind:system"],
        "run_step_holds.released_by_kind": ["subject_kind:system"],
        "run_step_holds.source_run_step_kind": ["run_step_kind:code_step"],
        "run_step_holds.run_step_holds_attempt_exactly_for_too_long_or_turned_away":
                ["run_step_hold_reason:too_long", "run_step_hold_reason:turned_away"],
        "run_step_holds.run_step_holds_attempt_is_produce": ["model_call_purpose:produce"],
        "run_step_holds.run_step_holds_author_is_system": ["subject_kind:system"],
        "run_step_holds.run_step_holds_code_step_not_held_for_current_or_source_code":
                ["run_step_hold_reason:code_step_not_held", "run_step_kind:code_step"],
        "run_step_holds.run_step_holds_source_only_for_code_step_not_held": ["run_step_hold_reason:code_step_not_held"],
        "run_step_holds.run_step_holds_search_way_not_held_only_for_search":
                ["run_step_hold_reason:search_way_not_held", "run_step_kind:search"],
        "run_step_holds.run_step_holds_releaser_is_system": ["subject_kind:system"],
        "run_step_holds.run_step_holds_too_long_or_turned_away_only_for_calls_a_model":
                ["run_step_hold_reason:too_long", "run_step_hold_reason:turned_away"],
        "run_steps.calls_a_model": ["step_producer:model"],
        "run_steps.created_by_kind": ["subject_kind:system"],
        "run_steps.kind":
                ["entry_kind:question", "entry_kind:workflow", "run_step_kind:code_step", "run_step_kind:question",
                 "run_step_kind:route", "run_step_kind:search", "run_step_kind:workflow", "step_kind:code_step",
                 "step_kind:route", "step_kind:search"],
        "run_steps.run_steps_author_is_system": ["subject_kind:system"],
        "run_steps.run_steps_pinned_exactly_for_entry_or_search": ["step_kind:entry", "step_kind:search"],
        "run_steps.run_steps_producer_exactly_for_a_producing_step":
                ["run_step_kind:code_step", "run_step_kind:question", "run_step_kind:search"],
        "run_stops.created_by_kind": ["subject_kind:person"],
        "run_stops.opened_again_by_kind": ["subject_kind:person"],
        "run_stops.run_stops_author_is_person_or_system": ["subject_kind:person", "subject_kind:system"],
        "run_stops.run_stops_ceiling_exactly_for_system": ["subject_kind:system"],
        "run_stops.run_stops_opened_again_is_person": ["subject_kind:person"],
        "runs.created_by_kind": ["subject_kind:person"],
        "runs.parent_pinned_version_id": ["run_step_kind:workflow"],
        "runs.runs_author_is_person_exactly_for_top_level": ["subject_kind:person"],
        "runs.runs_author_is_person_or_system": ["subject_kind:person", "subject_kind:system"],
        "runs.runs_editor_is_person": ["subject_kind:person"],
        "runs.runs_parent_route_case_exactly_for_route": ["run_step_kind:route"],
        "runs.runs_parent_step_is_workflow_or_route": ["run_step_kind:route", "run_step_kind:workflow"],
        "runs.updated_by_kind": ["subject_kind:person"],
    ]

    private RunLabelPins() {}
}
