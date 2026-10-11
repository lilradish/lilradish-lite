package com.lilradish.lite.domain.identity.fixture

/** Labels pinned on the tables of tries, what they took in and gave back, and how they were reviewed. */
final class TryLabelPins {

    static final Map<String, List<String>> PINS = [
        "errored_code_returned_files.errored_code_returned_files_code_only":
                ["step_producer:code", "try_lost_reason:errored"],
        "errored_code_returned_files.lost_reason": ["try_lost_reason:errored"],
        "errored_code_returned_files.producer": ["step_producer:code"],
        "production_inputs.source_run_id": ["run_step_kind:code_step", "run_step_kind:question", "run_step_kind:search"],
        "production_values.needs_review": ["field_standing:always", "field_standing:never"],
        "production_values.production_values_confidence_exactly_for_model_above_confidence":
                ["field_standing:above_confidence", "step_producer:model"],
        "production_values.production_values_field_exactly_for_question": ["run_step_kind:question"],
        "production_values.production_values_field_name_exactly_for_code_step_or_search":
                ["run_step_kind:code_step", "run_step_kind:search"],
        "production_values.production_values_search_stands_always_or_never":
                ["field_standing:always", "field_standing:never", "run_step_kind:search"],
        "production_values.production_values_pinned_exactly_for_question": ["run_step_kind:question"],
        "production_values.production_values_threshold_exactly_for_above_confidence":
                ["field_standing:above_confidence"],
        "productions.created_by_kind": ["subject_kind:system"],
        "productions.ended_by_kind": ["subject_kind:system"],
        "productions.model_call_purpose": ["model_call_purpose:produce"],
        "productions.productions_altered_answer_did_not_fit": ["try_lost_reason:did_not_fit"],
        "productions.productions_author_is_person_or_system": ["subject_kind:person", "subject_kind:system"],
        "productions.productions_beyond_tries_only_for_person": ["subject_kind:person"],
        "productions.productions_code_again_only_for_may_run_again": ["step_producer:code"],
        "productions.productions_code_error_member_exactly_for_not_declared": ["code_error_reason:not_declared"],
        "productions.productions_code_error_path_as_its_reason_names":
                ["code_error_reason:failed_on_this_side", "code_error_reason:file_total_too_large",
                 "code_error_reason:gave_nothing",
                 "code_error_reason:not_declared", "code_error_reason:said_nothing"],
        "productions.productions_code_error_read_by_exactly_for_gives_otherwise":
                ["code_error_reason:gives_otherwise"],
        "productions.productions_code_error_reason_only_for_code_errored":
                ["step_producer:code", "try_lost_reason:errored"],
        "productions.productions_did_not_fit_only_for_model": ["step_producer:model", "try_lost_reason:did_not_fit"],
        "productions.productions_did_not_fit_reason_exactly_for_model_did_not_fit":
                ["step_producer:model", "try_lost_reason:did_not_fit"],
        "productions.productions_ender_is_person_exactly_for_person_producer":
                ["step_producer:person", "subject_kind:person"],
        "productions.productions_ender_is_person_or_system": ["subject_kind:person", "subject_kind:system"],
        "productions.productions_explanation_exactly_for_ended_by_person": ["step_producer:person"],
        "productions.productions_help_exchange_only_for_person": ["step_producer:person"],
        "productions.productions_lost_as_call_ended":
                ["model_call_outcome:came_back", "model_call_outcome:errored", "model_call_outcome:nothing_came_back",
                 "try_lost_reason:did_not_fit", "try_lost_reason:errored", "try_lost_reason:nothing_came_back"],
        "productions.productions_lost_detail_exactly_where_code_threw":
                ["step_producer:code", "try_lost_reason:errored"],
        "productions.productions_lost_only_for_model_or_code": ["step_producer:code", "step_producer:model"],
        "productions.productions_may_run_again_exactly_for_code_step": ["run_step_kind:code_step"],
        "productions.productions_model_call_exactly_for_ended_model": ["step_producer:model"],
        "productions.productions_model_call_is_produce": ["model_call_purpose:produce"],
        "productions.productions_not_kept_only_when_altered": ["did_not_fit_reason:not_kept_as_it_came"],
        "productions.productions_pinned_exactly_for_question_or_search": ["run_step_kind:question", "run_step_kind:search"],
        "productions.productions_producer_is_step_producer_or_person": ["step_producer:person"],
        "productions.productions_returned_by_code_only_for_code_errored":
                ["code_error_reason:file_name_unusable", "code_error_reason:file_too_large",
                 "code_error_reason:file_total_too_large", "code_error_reason:file_type_unusable",
                 "code_error_reason:line_break", "code_error_reason:not_a_term", "code_error_reason:not_declared", "code_error_reason:not_its_kind",
                 "code_error_reason:nothing_given", "code_error_reason:too_long", "code_error_reason:too_long_to_keep",
                 "code_error_reason:too_many", "code_error_reason:unkeepable",
                 "step_producer:code", "try_lost_reason:errored"],
        "productions.productions_try_number_absent_only_for_open_model": ["step_producer:model"],
        "review_decisions.assurance_outcome": ["review_outcome:assured"],
        "review_decisions.review_decisions_assurance_is_assured": ["review_outcome:assured"],
        "review_decisions.review_decisions_explanation_exactly_for_refused": ["review_outcome:refused"],
        "review_decisions.review_decisions_for_length_only_for_refused": ["review_outcome:refused"],
        "review_decisions.review_decisions_one_refusal": ["review_outcome:refused"],
        "reviews.created_by_kind": ["subject_kind:person"],
        "reviews.hold_reason": ["run_step_hold_reason:too_long"],
        "reviews.model_call_purpose": ["model_call_purpose:review"],
        "reviews.reviews_altered_answer_did_not_fit": ["try_lost_reason:did_not_fit"],
        "reviews.reviews_author_is_person_or_system": ["subject_kind:person", "subject_kind:system"],
        "reviews.reviews_author_is_system_exactly_for_model_on_review": ["subject_kind:system"],
        "reviews.reviews_did_not_fit_reason_exactly_for_did_not_fit": ["try_lost_reason:did_not_fit"],
        "reviews.reviews_hold_is_too_long": ["run_step_hold_reason:too_long"],
        "reviews.reviews_lost_as_call_ended":
                ["model_call_outcome:came_back", "model_call_outcome:errored", "model_call_outcome:nothing_came_back",
                 "try_lost_reason:did_not_fit", "try_lost_reason:errored", "try_lost_reason:nothing_came_back"],
        "reviews.reviews_lost_only_for_system": ["subject_kind:system"],
        "reviews.reviews_model_call_exactly_for_system": ["subject_kind:system"],
        "reviews.reviews_model_call_is_review": ["model_call_purpose:review"],
        "reviews.reviews_not_kept_only_when_altered": ["did_not_fit_reason:not_kept_as_it_came"],
        "reviews.reviews_reviewer_is_not_producer": ["subject_kind:person"],
    ]

    private TryLabelPins() {}
}
