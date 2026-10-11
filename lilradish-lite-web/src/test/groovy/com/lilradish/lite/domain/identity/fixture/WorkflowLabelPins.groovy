package com.lilradish.lite.domain.identity.fixture

/** Labels pinned on the tables a workflow, a question or a reference list version is declared in. */
final class WorkflowLabelPins {

    static final Map<String, List<String>> PINS = [
        "bindings.bindings_editor_is_person": ["subject_kind:person"],
        "bindings.bindings_step_kind_is_route": ["step_kind:route"],
        "bindings.updated_by_kind": ["subject_kind:person"],
        "declaration_fields.declaration_fields_editor_is_person": ["subject_kind:person"],
        "declaration_fields.declaration_fields_parent_is_fields": ["field_kind:fields"],
        "declaration_fields.declaration_fields_route_is_route": ["step_kind:route"],
        "declaration_fields.declaration_fields_route_only_for_gives": ["declaration_side:gives"],
        "declaration_fields.declaration_fields_standing_only_for_a_question": ["entry_kind:question"],
        "declaration_fields.declaration_fields_standing_only_for_unheld_gives": ["declaration_side:gives"],
        "declaration_fields.declaration_fields_standing_threshold_only_for_above_confidence":
                ["field_standing:above_confidence"],
        "declaration_fields.declaration_fields_term_list_is_reference_list": ["entry_kind:reference_list"],
        "declaration_fields.declaration_fields_term_list_only_for_term": ["field_kind:term"],
        "declaration_fields.declaration_fields_text_limit_only_for_text": ["field_kind:text"],
        "declaration_fields.declaration_fields_text_lines_only_for_text": ["field_kind:text"],
        "declaration_fields.declaration_fields_version_is_question_or_workflow":
                ["entry_kind:question", "entry_kind:workflow"],
        "declaration_fields.parent_kind": ["field_kind:fields"],
        "declaration_fields.step_kind": ["step_kind:route"],
        "declaration_fields.term_list_kind": ["entry_kind:reference_list"],
        "declaration_fields.updated_by_kind": ["subject_kind:person"],
        "question_versions.entry_kind": ["entry_kind:question"],
        "question_versions.question_versions_editor_is_person": ["subject_kind:person"],
        "question_versions.question_versions_is_question": ["entry_kind:question"],
        "question_versions.updated_by_kind": ["subject_kind:person"],
        "reference_list_terms.reference_list_terms_editor_is_person": ["subject_kind:person"],
        "reference_list_terms.updated_by_kind": ["subject_kind:person"],
        "reference_list_versions.entry_kind": ["entry_kind:reference_list"],
        "reference_list_versions.reference_list_versions_editor_is_person": ["subject_kind:person"],
        "reference_list_versions.reference_list_versions_is_reference_list": ["entry_kind:reference_list"],
        "reference_list_versions.updated_by_kind": ["subject_kind:person"],
        "route_cases.route_cases_editor_is_person": ["subject_kind:person"],
        "route_cases.route_cases_is_route": ["step_kind:route"],
        "route_cases.route_cases_target_is_workflow": ["entry_kind:workflow"],
        "route_cases.step_kind": ["step_kind:route"],
        "route_cases.target_kind": ["entry_kind:workflow"],
        "route_cases.updated_by_kind": ["subject_kind:person"],
        "workflow_steps.updated_by_kind": ["subject_kind:person"],
        "workflow_steps.workflow_steps_code_step_only_for_code_step": ["step_kind:code_step"],
        "workflow_steps.workflow_steps_code_step_produced_by_code_or_person":
                ["step_kind:code_step", "step_producer:model"],
        "workflow_steps.workflow_steps_editor_is_person": ["subject_kind:person"],
        "workflow_steps.workflow_steps_pinned_is_question_or_workflow": ["entry_kind:question", "entry_kind:workflow"],
        "workflow_steps.workflow_steps_pinned_only_for_entry_or_search": ["step_kind:entry", "step_kind:search"],
        "workflow_steps.workflow_steps_search_pins_question": ["entry_kind:question", "step_kind:search"],
        "workflow_steps.workflow_steps_producer_model_only_for_model": ["step_producer:model"],
        "workflow_steps.workflow_steps_question_produced_by_model_or_person":
                ["entry_kind:question", "step_producer:code"],
        "workflow_steps.workflow_steps_search_only_for_search": ["step_kind:search"],
        "workflow_steps.workflow_steps_search_produced_by_model_or_person": ["step_kind:search", "step_producer:code"],
        "workflow_steps.workflow_steps_search_standing_without_confidence":
                ["field_standing:always", "field_standing:never"],
        "workflow_steps.workflow_steps_told_only_for_model": ["step_producer:model"],
        "workflow_steps.workflow_steps_workflow_or_route_unproduced": ["entry_kind:workflow", "step_kind:route"],
        "workflow_versions.entry_kind": ["entry_kind:workflow"],
        "workflow_versions.updated_by_kind": ["subject_kind:person"],
        "workflow_versions.workflow_versions_editor_is_person": ["subject_kind:person"],
        "workflow_versions.workflow_versions_is_workflow": ["entry_kind:workflow"],
    ]

    private WorkflowLabelPins() {}
}
