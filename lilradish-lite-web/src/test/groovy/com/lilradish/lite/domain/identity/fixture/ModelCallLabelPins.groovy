package com.lilradish.lite.domain.identity.fixture

/** Labels pinned on the tables of attempts at sending, calls to a model, their searches, turnaways and help. */
final class ModelCallLabelPins {

    static final Map<String, List<String>> PINS = [
        "model_call_searches.model_call_outcome": ["model_call_outcome:came_back"],
        "model_call_searches.model_call_searches_came_back": ["model_call_outcome:came_back"],
        "model_call_tool_uses.model_call_purpose": ["model_call_purpose:produce"],
        "model_call_tool_uses.model_call_tool_uses_purpose_is_produce": ["model_call_purpose:produce"],
        "model_call_tool_uses.model_call_tool_uses_counted_exactly_once_taken_up":
                ["model_call_outcome:came_back", "model_call_outcome:errored", "model_call_outcome:nothing_came_back"],
        "model_call_tool_uses.model_call_tool_uses_said_only_for_came_back": ["model_call_outcome:came_back"],
        "model_call_tool_uses.model_call_tool_uses_record_only_for_came_back": ["model_call_outcome:came_back"],
        "model_call_turnaways.created_by_kind": ["subject_kind:system"],
        "model_call_turnaways.model_call_turnaways_author_is_system": ["subject_kind:system"],
        "model_call_turnaways.model_call_turnaways_model_call_outcome_is_turned_away": ["model_call_outcome:turned_away"],
        "model_calls.created_by_kind": ["subject_kind:system"],
        "model_calls.model_calls_answer_exactly_for_came_back": ["model_call_outcome:came_back"],
        "model_calls.model_calls_attempt_exactly_for_produce_or_review":
                ["model_call_purpose:produce", "model_call_purpose:review"],
        "model_calls.model_calls_author_is_system": ["subject_kind:system"],
        "model_calls.model_calls_came_back_count_exactly_for_came_back": ["model_call_outcome:came_back"],
        "model_calls.model_calls_counted_by_model_only_for_came_back": ["model_call_outcome:came_back"],
        "model_calls.model_calls_declined_words_only_for_came_back": ["model_call_outcome:came_back"],
        "model_calls.model_calls_error_detail_exactly_for_errored": ["model_call_outcome:errored"],
        "model_calls.model_calls_request_exactly_for_help": ["model_call_purpose:help"],
        "model_calls.model_calls_root_version_exactly_for_help": ["model_call_purpose:help"],
        "model_calls.model_calls_unreadable_response_only_for_errored": ["model_call_outcome:errored"],
        "run_help_exchanges.created_by_kind": ["subject_kind:person"],
        "run_help_exchanges.model_call_purpose": ["model_call_purpose:help"],
        "run_help_exchanges.run_help_exchanges_answer_only_for_came_back": ["model_call_outcome:came_back"],
        "run_help_exchanges.run_help_exchanges_author_is_person": ["subject_kind:person"],
        "run_help_exchanges.run_help_exchanges_model_call_is_help": ["model_call_purpose:help"],
        "run_step_send_attempts.created_by_kind": ["subject_kind:person"],
        "run_step_send_attempts.payload_scope_id": ["model_call_purpose:review"],
        "run_step_send_attempts.produced_production_id": ["model_call_purpose:produce"],
        "run_step_send_attempts.produced_with": ["model_call_purpose:produce"],
        "run_step_send_attempts.production_producer": ["step_producer:model"],
        "run_step_send_attempts.reviewed_production_id": ["model_call_purpose:review"],
        "run_step_send_attempts.reviewed_with": ["model_call_purpose:review"],
        "run_step_send_attempts.run_step_send_attempts_author_is_person_or_system":
                ["subject_kind:person", "subject_kind:system"],
        "run_step_send_attempts.run_step_send_attempts_only_for_a_producing_step":
                ["run_step_kind:code_step", "run_step_kind:question", "run_step_kind:search"],
        "run_step_send_attempts.run_step_send_attempts_produce_only_for_question_or_search":
                ["model_call_purpose:review", "run_step_kind:question", "run_step_kind:search"],
        "run_step_send_attempts.run_step_send_attempts_production_is_model": ["step_producer:model"],
        "run_step_send_attempts.run_step_send_attempts_purpose_is_produce_or_review":
                ["model_call_purpose:produce", "model_call_purpose:review"],
        "run_step_send_attempts.run_step_send_attempts_too_long_review_only_for_system":
                ["model_call_purpose:review", "subject_kind:system"],
        "run_step_send_attempts.run_step_send_attempts_unbuilt_only_for_review_not_sent":
                ["model_call_purpose:review"],
    ]

    private ModelCallLabelPins() {}
}
