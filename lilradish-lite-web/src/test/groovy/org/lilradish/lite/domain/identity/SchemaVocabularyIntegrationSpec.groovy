package org.lilradish.lite.domain.identity

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.Locale
import java.util.regex.Pattern
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import org.lilradish.lite.domain.codestep.CodeErrorReason
import org.lilradish.lite.domain.declaration.DeclarationSide
import org.lilradish.lite.domain.declaration.FieldKind
import org.lilradish.lite.domain.declaration.FieldStanding
import org.lilradish.lite.domain.inference.DidNotFitReason
import org.lilradish.lite.domain.inference.ModelCallOutcome
import org.lilradish.lite.domain.inference.ModelCallPurpose
import org.lilradish.lite.domain.members.GroupMemberRemoval
import org.lilradish.lite.domain.registry.EntryKind
import org.lilradish.lite.domain.run.ReviewOutcome
import org.lilradish.lite.domain.run.ReviewUnbuiltReason
import org.lilradish.lite.domain.run.RunCeilingChangeOutcome
import org.lilradish.lite.domain.run.RunStepFailureReason
import org.lilradish.lite.domain.run.RunStepHoldReason
import org.lilradish.lite.domain.run.TryLostReason
import org.lilradish.lite.domain.workflow.StepKind
import org.lilradish.lite.domain.workflow.StepProducer
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * Every schema vocabulary must be named as owned or deliberately unowned, never both.
 * Java enums may remain unpersisted, so completeness is enforced only from schema to Java.
 */
class SchemaVocabularyIntegrationSpec extends Specification {

    /**
     * Doubled quotes must remain inside a label rather than ending it.
     * Schema qualifiers depend on the session's search path, so both forms must match.
     */
    static final Pattern CAST_TO_AN_ENUM = Pattern.compile(/'((?:[^']|'')*)'::(?:\w+\.)?(\w+)/)

    static final Map<String, Class<? extends Enum>> OWNERS = [
            "code_error_reason": CodeErrorReason,
            "declaration_side": DeclarationSide,
            "did_not_fit_reason": DidNotFitReason,
            "entry_kind": EntryKind,
            "estate_role": EstateRole,
            "field_kind": FieldKind,
            "field_standing": FieldStanding,
            "group_member_removal": GroupMemberRemoval,
            "group_role": GroupRole,
            "model_call_outcome": ModelCallOutcome,
            "model_call_purpose": ModelCallPurpose,
            "review_outcome": ReviewOutcome,
            "review_unbuilt_reason": ReviewUnbuiltReason,
            "run_ceiling_change_outcome": RunCeilingChangeOutcome,
            "run_step_failure_reason": RunStepFailureReason,
            "run_step_hold_reason": RunStepHoldReason,
            "step_kind": StepKind,
            "step_producer": StepProducer,
            "try_lost_reason": TryLostReason]

    /** Unowned vocabularies must be named explicitly so an owner cannot appear silently. */
    static final Set<String> UNOWNED_ON_PURPOSE = [
        "code_step",
        "run_step_kind",
        "soundness_check_outcome",
        "subject_kind",
    ] as Set

    /**
     * Pins must be explicit so changes between valid labels are caught as well as invalid labels.
     * A label's type is part of its identity.
     */
    static final Map<String, List<String>> PINNED_TO_A_LABEL = [
        "bindings.bindings_editor_is_person": ["subject_kind:person"],
        "bindings.bindings_step_kind_is_route": ["step_kind:route"],
        "bindings.updated_by_kind": ["subject_kind:person"],
        "code_step_publications.code_step_publications_author_is_seeder": ["subject_kind:seeder"],
        "code_step_publications.created_by_kind": ["subject_kind:seeder"],
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
        "errored_code_returned_files.errored_code_returned_files_code_only":
                ["step_producer:code", "try_lost_reason:errored"],
        "errored_code_returned_files.lost_reason": ["try_lost_reason:errored"],
        "errored_code_returned_files.producer": ["step_producer:code"],
        "entries.entries_editor_is_person": ["subject_kind:person"],
        "entries.updated_by_kind": ["subject_kind:person"],
        "entry_stops.created_by_kind": ["subject_kind:person"],
        "entry_stops.entry_stops_author_is_person": ["subject_kind:person"],
        "entry_stops.entry_stops_let_go_is_person": ["subject_kind:person"],
        "entry_stops.let_go_by_kind": ["subject_kind:person"],
        "entry_version_submissions.created_by_kind": ["subject_kind:person"],
        "entry_version_submissions.entry_version_submissions_author_is_person_or_seeder":
                ["subject_kind:person", "subject_kind:seeder"],
        "entry_version_submissions.entry_version_submissions_withdrawer_is_person": ["subject_kind:person"],
        "entry_version_submissions.withdrawn_by_kind": ["subject_kind:person"],
        "entry_version_writers.created_by_kind": ["subject_kind:person"],
        "entry_version_writers.entry_version_writers_author_is_person": ["subject_kind:person"],
        "entry_versions.approved_by_kind": ["subject_kind:person"],
        "entry_versions.entry_versions_approver_is_opener_exactly_for_seeder": ["subject_kind:seeder"],
        "entry_versions.entry_versions_approver_is_person_or_seeder": ["subject_kind:person", "subject_kind:seeder"],
        "entry_versions.entry_versions_retirer_is_person_or_seeder": ["subject_kind:person", "subject_kind:seeder"],
        "entry_versions.entry_versions_seeder_approves_as_it_opens": ["subject_kind:seeder"],
        "entry_versions.entry_versions_seeder_retires_as_it_opens": ["subject_kind:seeder"],
        "entry_versions.retired_by_kind": ["subject_kind:person"],
        "estate_role_grants.estate_role_grants_is_person": ["subject_kind:person"],
        "estate_role_grants.estate_role_grants_remover_is_person": ["subject_kind:person"],
        "estate_role_grants.removed_by_kind": ["subject_kind:person"],
        "estate_role_grants.subject_kind": ["subject_kind:person"],
        "group_currencies.created_by_kind": ["subject_kind:person"],
        "group_currencies.group_currencies_author_is_person": ["subject_kind:person"],
        "group_currencies.group_currencies_editor_is_person": ["subject_kind:person"],
        "group_currencies.updated_by_kind": ["subject_kind:person"],
        "group_members.group_members_is_person": ["subject_kind:person"],
        "group_members.group_members_remover_is_person": ["subject_kind:person"],
        "group_members.removed_by_kind": ["subject_kind:person"],
        "group_members.subject_kind": ["subject_kind:person"],
        "groups.groups_editor_is_person": ["subject_kind:person"],
        "groups.updated_by_kind": ["subject_kind:person"],
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
        "pool_members.pool_members_is_person": ["subject_kind:person"],
        "pool_members.pool_members_remover_is_person": ["subject_kind:person"],
        "pool_members.removed_by_kind": ["subject_kind:person"],
        "pool_members.subject_kind": ["subject_kind:person"],
        "production_inputs.source_run_id": ["run_step_kind:code_step", "run_step_kind:question", "run_step_kind:search"],
        "production_inputs.production_inputs_search_part_only_for_search_try": ["run_step_kind:search"],
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
        "productions.productions_pinned_exactly_for_question": ["run_step_kind:question"],
        "productions.productions_producer_is_step_producer_or_person": ["step_producer:person"],
        "productions.productions_returned_by_code_only_for_code_errored":
                ["code_error_reason:file_name_unusable", "code_error_reason:file_too_large",
                 "code_error_reason:file_total_too_large", "code_error_reason:file_type_unusable",
                 "code_error_reason:line_break", "code_error_reason:not_a_term", "code_error_reason:not_declared", "code_error_reason:not_its_kind",
                 "code_error_reason:nothing_given", "code_error_reason:too_long", "code_error_reason:too_long_to_keep",
                 "code_error_reason:too_many", "code_error_reason:unkeepable",
                 "step_producer:code", "try_lost_reason:errored"],
        "productions.productions_try_number_absent_only_for_open_model": ["step_producer:model"],
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
        "route_cases.route_cases_editor_is_person": ["subject_kind:person"],
        "route_cases.route_cases_is_route": ["step_kind:route"],
        "route_cases.route_cases_target_is_workflow": ["entry_kind:workflow"],
        "route_cases.step_kind": ["step_kind:route"],
        "route_cases.target_kind": ["entry_kind:workflow"],
        "route_cases.updated_by_kind": ["subject_kind:person"],
        "run_ceiling_changes.created_by_kind": ["subject_kind:person"],
        "run_ceiling_changes.decided_by_kind": ["subject_kind:person"],
        "run_ceiling_changes.run_ceiling_changes_author_is_person": ["subject_kind:person"],
        "run_ceiling_changes.run_ceiling_changes_decider_matches_outcome":
                ["run_ceiling_change_outcome:superseded_by_change", "run_ceiling_change_outcome:withdrawn_by_requester"],
        "run_ceiling_changes.run_ceiling_changes_decider_is_person": ["subject_kind:person"],
        "run_help_exchanges.created_by_kind": ["subject_kind:person"],
        "run_help_exchanges.model_call_purpose": ["model_call_purpose:help"],
        "run_help_exchanges.run_help_exchanges_answer_only_for_came_back": ["model_call_outcome:came_back"],
        "run_help_exchanges.run_help_exchanges_author_is_person": ["subject_kind:person"],
        "run_help_exchanges.run_help_exchanges_model_call_is_help": ["model_call_purpose:help"],
        "run_step_failures.created_by_kind": ["subject_kind:system"],
        "run_step_failures.produced_production_id": ["model_call_purpose:produce"],
        "run_step_failures.production_producer": ["step_producer:model"],
        "run_step_failures.reviewed_production_id": ["model_call_purpose:review"],
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
        "run_step_send_attempts.run_step_send_attempts_search_question_exactly_for_search":
                ["model_call_purpose:produce", "run_step_kind:search"],
        "run_step_send_attempts.run_step_send_attempts_production_is_model": ["step_producer:model"],
        "run_step_send_attempts.run_step_send_attempts_purpose_is_produce_or_review":
                ["model_call_purpose:produce", "model_call_purpose:review"],
        "run_step_send_attempts.run_step_send_attempts_too_long_review_only_for_system":
                ["model_call_purpose:review", "subject_kind:system"],
        "run_step_send_attempts.run_step_send_attempts_unbuilt_only_for_review_not_sent":
                ["model_call_purpose:review"],
        "run_steps.calls_a_model": ["step_producer:model"],
        "run_steps.created_by_kind": ["subject_kind:system"],
        "run_steps.kind":
                ["entry_kind:question", "entry_kind:workflow", "run_step_kind:code_step", "run_step_kind:question",
                 "run_step_kind:route", "run_step_kind:search", "run_step_kind:workflow", "step_kind:code_step",
                 "step_kind:route", "step_kind:search"],
        "run_steps.run_steps_author_is_system": ["subject_kind:system"],
        "run_steps.run_steps_pinned_exactly_for_entry": ["step_kind:entry"],
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
        "search_question_parts.search_question_parts_editor_is_person": ["subject_kind:person"],
        "search_question_parts.search_question_parts_step_kind_is_search": ["step_kind:search"],
        "search_question_parts.step_kind": ["step_kind:search"],
        "search_question_parts.updated_by_kind": ["subject_kind:person"],
        "soundness_checks.created_by_kind": ["subject_kind:person"],
        "soundness_checks.soundness_checks_author_is_person": ["subject_kind:person"],
        "soundness_counts.check_outcome": ["soundness_check_outcome:finished"],
        "soundness_counts.soundness_counts_check_is_finished": ["soundness_check_outcome:finished"],
        "subjects.subjects_display_name_only_for_people": ["subject_kind:person"],
        "subjects.subjects_user_only_for_people": ["subject_kind:person"],
        "workflow_steps.updated_by_kind": ["subject_kind:person"],
        "workflow_steps.workflow_steps_code_step_only_for_code_step": ["step_kind:code_step"],
        "workflow_steps.workflow_steps_code_step_produced_by_code_or_person":
                ["step_kind:code_step", "step_producer:model"],
        "workflow_steps.workflow_steps_editor_is_person": ["subject_kind:person"],
        "workflow_steps.workflow_steps_pinned_is_question_or_workflow": ["entry_kind:question", "entry_kind:workflow"],
        "workflow_steps.workflow_steps_pinned_only_for_entry": ["step_kind:entry"],
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

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    @AutoCleanup
    Connection session

    @Shared
    List<Class<?>> javaEnums

    @Shared
    Map<String, List<String>> labels = [:]

    /**
     * Require the location before interpolation so an unset property cannot become a directory name.
     */
    static final String MIGRATIONS = Objects.requireNonNull(System.getProperty("baseline.location"),
            "baseline.location was not set; the build names it to test and to pitest")

    def setupSpec() {
        DataSource database = server.postgresDatabase
        // Migrations stay off the test classpath to avoid unrequested Flyway auto-configuration.
        // Missing locations and invalid migration names must fail as they do at deployment.
        Flyway.configure()
                .dataSource(database)
                .schemas("app")
                .locations("filesystem:" + MIGRATIONS)
                .failOnMissingLocations(true)
                .validateMigrationNaming(true)
                .load()
                .migrate()
        session = database.connection
        javaEnums = walkForJavaEnums()
        enumTypesInSchema().each { labels[it] = readLabelsOf(it) }
    }

    def "every vocabulary the schema declares is either owned by a Java type or declared unowned"() {
        expect:
        labels.keySet() == OWNERS.keySet() + UNOWNED_ON_PURPOSE

        and: "and no type is in both lists, which would let an owner hide behind being unowned"
        OWNERS.keySet().disjoint(UNOWNED_ON_PURPOSE)

        and: "asserted of something, rather than passing because the schema declares nothing"
        !labels.isEmpty()
    }

    def "an owned vocabulary's labels are its owner's constants, in the same spelling and the same order"() {
        expect:
        labels[type] == owner.enumConstants.collect { it.name().toLowerCase(Locale.ROOT) }

        and: "and it is a set worth comparing rather than two empties agreeing"
        !labels[type].isEmpty()

        and: "and its owner is the type the naming convention names, which is what the unowned ones are held to"
        owner.simpleName == conventionalOwnerOf(type)

        where:
        type << OWNERS.keySet()
        owner << OWNERS.values()
    }

    /**
     * Unowned types have no agreed labels, so owner detection must use names even when labels disagree.
     */
    def "no Java type already exists for a vocabulary declared unowned"() {
        expect:
        javaEnums.every { it.simpleName != conventionalOwnerOf(type) }

        where:
        type << UNOWNED_ON_PURPOSE
    }

    def "every owner named here is a type the walk over the compiled classes actually finds"() {
        expect:
        javaEnums.containsAll(OWNERS.values())

        and: "and the walk reaches far more than the pairing does, which is why it is not the population"
        javaEnums.size() > OWNERS.size()
    }

    def "every label a rule, a default or a generated column pins is declared here, and is one its type has"() {
        given:
        def pinned = labelsPinnedInDefinitions()

        expect:
        pinned == PINNED_TO_A_LABEL

        and: "and each one names a type this schema has and a label that type carries"
        pinned.values().flatten().every { pin ->
            def (type, label) = pin.split(":", 2)
            labels.containsKey(type) && label in labels[type]
        }

        and: "asserted of something, rather than passing because the schema pins nothing"
        !pinned.isEmpty()
    }

    private static String conventionalOwnerOf(String type) {
        type.split("_").collect { it.capitalize() }.join()
    }

    /** By the type's own kind rather than through pg_enum, where a type declared with no label yet has no row. */
    private Set<String> enumTypesInSchema() {
        // DB-SPECIFIC: pg_type and ::regnamespace identify PostgreSQL's enum types in a schema.
        queryForRows("""
            select t.typname
              from pg_type t
             where t.typnamespace = 'app'::regnamespace and t.typtype = 'e'
            """).collect { it[0] } as Set
    }

    private List<String> readLabelsOf(String type) {
        // DB-SPECIFIC: pg_type, pg_enum, enumsortorder and ::regnamespace read PostgreSQL's enum declaration order.
        queryForRows("""
            select e.enumlabel
              from pg_type t
              join pg_enum e on e.enumtypid = t.oid
             where t.typnamespace = 'app'::regnamespace and t.typname = '${type}'
             order by e.enumsortorder
            """).collect { it[0] }
    }

    /**
     * Inspect stored casts, since column types cannot identify every enum pin.
     * Read the server's definitions, since it may rewrite migration expressions.
     */
    private Map<String, List<String>> labelsPinnedInDefinitions() {
        def types = labels.keySet()
        def found = [:]
        // DB-SPECIFIC: PostgreSQL's pg_* catalogs, ::regnamespace, pg_get_constraintdef() and pg_get_expr()
        // return stored check, index predicate, default and generated expressions.
        queryForRows("""
            select coalesce(t.relname, d.typname) || '.' || con.conname, pg_get_constraintdef(con.oid)
              from pg_constraint con
              left join pg_class t on t.oid = con.conrelid
              left join pg_type d on d.oid = con.contypid
             where con.connamespace = 'app'::regnamespace and con.contype = 'c'
            union all
            select t.relname || '.' || idx.relname, pg_get_expr(i.indpred, i.indrelid)
              from pg_index i
              join pg_class idx on idx.oid = i.indexrelid
              join pg_class t on t.oid = i.indrelid
              join pg_namespace n on n.oid = idx.relnamespace
             where n.nspname = 'app' and i.indpred is not null
            union all
            select t.relname || '.' || a.attname, pg_get_expr(d.adbin, d.adrelid)
              from pg_attrdef d
              join pg_class t on t.oid = d.adrelid
              join pg_attribute a on a.attrelid = d.adrelid and a.attnum = d.adnum
             where t.relnamespace = 'app'::regnamespace
            """).each { row ->
            def pins = []
            def casts = CAST_TO_AN_ENUM.matcher(row[1] as String)
            while (casts.find()) {
                def type = casts.group(2)
                if (type in types) {
                    pins << type + ":" + casts.group(1).replace("''", "'")
                }
            }
            if (!pins.isEmpty()) {
                found[row[0]] = pins.unique().toSorted()
            }
        }
        found
    }

    private static List<Class<?>> walkForJavaEnums() {
        def classes = Path.of(GroupRole.protectionDomain.codeSource.location.toURI())
        Files.walk(classes.resolve("org/lilradish/lite")).withCloseable { paths ->
            paths.filter { it.toString().endsWith(".class") }
                    .map { load(classes.relativize(it)) }
                    .filter { it.isEnum() }
                    .toList()
        }
    }

    private static Class<?> load(Path relative) {
        Class.forName(relative.toString().replace(".class", "").replace("/", "."), false,
                SchemaVocabularyIntegrationSpec.classLoader)
    }

    private List<List<String>> queryForRows(String query) {
        session.createStatement().withCloseable { statement ->
            statement.executeQuery(query).withCloseable { rows ->
                def found = []
                def columns = rows.metaData.columnCount
                while (rows.next()) {
                    found << (1..columns).collect { rows.getString(it) }
                }
                found
            }
        }
    }
}
