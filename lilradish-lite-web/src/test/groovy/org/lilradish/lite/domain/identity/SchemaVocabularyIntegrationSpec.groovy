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
import org.lilradish.lite.domain.run.RunStepFailureReason
import org.lilradish.lite.domain.run.RunStepHoldReason
import org.lilradish.lite.domain.run.TryLostReason
import org.lilradish.lite.domain.workflow.StepKind
import org.lilradish.lite.domain.workflow.StepProducer
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * A vocabulary written down twice: once as a type the database enforces, once as a type Java
 * dispatches on. Nothing in either artefact can see the other, and a label spelt one way on one side
 * and another way on the other is a value that round-trips as far as the first query and then stops
 * matching anything.
 *
 * <p>Both sides are discovered. The schema's are read from pg_enum against a server the migrations
 * have actually been run into, rather than parsed out of the migration text — ALTER TYPE … ADD VALUE
 * is precisely the shape text parsing gets wrong, and it is also the shape a vocabulary grows by.
 * The Java side is a walk over the compiled classes filtered by {@code isEnum()}.
 *
 * <p>The pairing between them is the one thing written by hand, so it is held total: every type the
 * schema has must be named as owned or named as deliberately unowned, and nothing may be in both
 * lists. What holds the second list is a naming convention — {@code estate_role} would be owned by
 * {@code EstateRole} — asserted against the owned ones as well, so it is a rule this suite follows
 * rather than an assumption it makes. A Java type appearing for an unowned schema type is caught by
 * its name, which is the only thing that exists before anybody has agreed on its labels; catching it
 * by its labels would be silent in exactly the case worth catching, where they were spelt wrongly.
 *
 * <p>What cannot be derived, stated rather than implied: a Java enum that should have been persisted
 * and was not. Most of the domain's enums are deliberately never persisted, so an unpaired Java type
 * is the ordinary case and carries no signal. This holds the schema total against Java, and not Java
 * total against the schema.
 */
class SchemaVocabularyIntegrationSpec extends Specification {

    /**
     * A quoted literal and the type PostgreSQL prints the cast to beside it, as it stores every enum
     * literal in a rule. The inner alternation consumes a doubled quote rather than ending on it, so
     * a label holding an apostrophe is read whole instead of being split in two.
     *
     * <p>The schema qualifier is optional because whether one is printed is a property of the
     * reader: the same constraint comes back as {@code 'person'::subject_kind} to a session with the
     * schema on its search path and as {@code 'person'::app.subject_kind} to one without, so a
     * pattern that accepts only one of them reads every rule on one server and none on another.
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
            "run_step_failure_reason": RunStepFailureReason,
            "run_step_hold_reason": RunStepHoldReason,
            "step_kind": StepKind,
            "step_producer": StepProducer,
            "try_lost_reason": TryLostReason]

    /**
     * Named here rather than absent, so that a type gaining an owner is a change to this file and
     * not a silent pairing. No Java code reads these yet; each moves to OWNERS as its domain comes to.
     */
    static final Set<String> UNOWNED_ON_PURPOSE = [
        "code_step",
        "run_ceiling_change_outcome",
        "run_step_kind",
        "soundness_check_outcome",
        "subject_kind",
    ] as Set

    /**
     * A vocabulary a rule, a default or a generated column pins to one of its labels, which is a second
     * place a label is spelt out. PostgreSQL refuses a literal that is not a label of the type cast to,
     * so what this adds is that the set of such pins is fixed: a pin added against a different label, or
     * one quietly changed to the other, is a change to this map rather than to nothing. Each entry is the
     * type the literal was cast to and the literal itself, because the type is what makes the label mean
     * anything.
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
        "declaration_fields.declaration_fields_version_is_question_or_workflow":
                ["entry_kind:question", "entry_kind:workflow"],
        "declaration_fields.parent_kind": ["field_kind:fields"],
        "declaration_fields.step_kind": ["step_kind:route"],
        "declaration_fields.term_list_kind": ["entry_kind:reference_list"],
        "declaration_fields.updated_by_kind": ["subject_kind:person"],
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
        "model_calls.model_calls_error_detail_exactly_for_errored": ["model_call_outcome:errored"],
        "model_calls.model_calls_request_exactly_for_help": ["model_call_purpose:help"],
        "model_calls.model_calls_root_version_exactly_for_help": ["model_call_purpose:help"],
        "pool_members.pool_members_is_person": ["subject_kind:person"],
        "pool_members.pool_members_remover_is_person": ["subject_kind:person"],
        "pool_members.removed_by_kind": ["subject_kind:person"],
        "pool_members.subject_kind": ["subject_kind:person"],
        "production_inputs.source_run_id": ["run_step_kind:code_step", "run_step_kind:question"],
        "production_values.needs_review": ["field_standing:always", "field_standing:never"],
        "production_values.production_values_confidence_exactly_for_model_above_confidence":
                ["field_standing:above_confidence", "step_producer:model"],
        "production_values.production_values_field_exactly_for_question": ["run_step_kind:question"],
        "production_values.production_values_field_name_exactly_for_code_step": ["run_step_kind:code_step"],
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
                ["code_error_reason:failed_on_this_side", "code_error_reason:gave_nothing",
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
                ["step_producer:code", "try_lost_reason:errored"],
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
        "run_ceiling_changes.run_ceiling_changes_decider_is_not_requester": ["run_ceiling_change_outcome:withdrawn"],
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
        "run_step_failures.run_step_failures_author_is_system": ["subject_kind:system"],
        "run_step_failures.run_step_failures_length_or_undeployed_only_for_calls_a_model":
                ["run_step_failure_reason:model_not_deployed", "run_step_failure_reason:uncuttable_length"],
        "run_step_failures.run_step_failures_production_exactly_for_length_or_undeployed":
                ["run_step_failure_reason:model_not_deployed", "run_step_failure_reason:uncuttable_length"],
        "run_step_failures.run_step_failures_production_is_model": ["step_producer:model"],
        "run_step_failures.run_step_failures_purpose_is_produce_or_review":
                ["model_call_purpose:produce", "model_call_purpose:review"],
        "run_step_failures.run_step_failures_unclaimed_value_only_for_route":
                ["run_step_failure_reason:unclaimed_value", "run_step_kind:route"],
        "run_step_failures.run_step_failures_uncuttable_length_only_for_produce":
                ["model_call_purpose:produce", "run_step_failure_reason:uncuttable_length"],
        "run_step_holds.attempt_purpose": ["model_call_purpose:produce"],
        "run_step_holds.attempt_too_long": ["run_step_hold_reason:too_long"],
        "run_step_holds.created_by_kind": ["subject_kind:system"],
        "run_step_holds.released_by_kind": ["subject_kind:system"],
        "run_step_holds.run_step_holds_attempt_exactly_for_too_long_or_turned_away":
                ["run_step_hold_reason:too_long", "run_step_hold_reason:turned_away"],
        "run_step_holds.run_step_holds_attempt_is_produce": ["model_call_purpose:produce"],
        "run_step_holds.run_step_holds_author_is_system": ["subject_kind:system"],
        "run_step_holds.run_step_holds_code_step_not_held_only_for_code_step":
                ["run_step_hold_reason:code_step_not_held", "run_step_kind:code_step"],
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
        "run_step_send_attempts.run_step_send_attempts_only_for_question_or_code_step":
                ["run_step_kind:code_step", "run_step_kind:question"],
        "run_step_send_attempts.run_step_send_attempts_produce_only_for_question":
                ["model_call_purpose:review", "run_step_kind:question"],
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
                 "run_step_kind:route", "run_step_kind:workflow", "step_kind:code_step", "step_kind:route"],
        "run_steps.run_steps_author_is_system": ["subject_kind:system"],
        "run_steps.run_steps_pinned_exactly_for_entry": ["step_kind:entry"],
        "run_steps.run_steps_producer_exactly_for_question_or_code_step":
                ["run_step_kind:code_step", "run_step_kind:question"],
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

    /** Walked once. It is a walk over every compiled class and several features ask for it. */
    @Shared
    List<Class<?>> javaEnums

    /** Read once per type. Two features ask for the same labels and one asks inside a loop. */
    @Shared
    Map<String, List<String>> labels = [:]

    /**
     * Demanded rather than defaulted. Unset, it would be interpolated as the literal location
     * {@code filesystem:null}, and the failure would point at a directory that does not exist rather
     * than at a property nobody set.
     */
    static final String MIGRATIONS = Objects.requireNonNull(System.getProperty("baseline.location"),
            "baseline.location was not set; the build names it to test and to pitest")

    def setupSpec() {
        DataSource database = server.postgresDatabase
        // A filesystem location, because the migrations are deliberately off this project's test
        // classpath: on it, Spring Boot's Flyway auto-configuration would find them unasked.
        //
        // The two flags are the runner's own, from its application.yaml. Without them this path
        // accepts what the deploy path refuses — a location holding nothing, or a file whose name
        // Flyway will not parse — and the build goes green over an artefact that cannot start.
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
     * By name and not by labels. A type declared unowned has no agreed spelling yet, so the day one
     * appears the thing that identifies it is what it is called — and a check on its labels would go
     * quiet in the one case this exists for, where they were spelt or ordered wrongly.
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

    /** {@code estate_role} would be owned by {@code EstateRole}: the label rule read backwards. */
    private static String conventionalOwnerOf(String type) {
        type.split("_").collect { it.capitalize() }.join()
    }

    /** By the type's own kind rather than through pg_enum, where a type declared with no label yet has no row. */
    private Set<String> enumTypesInSchema() {
        queryForRows("""
            select t.typname
              from pg_type t
             where t.typnamespace = 'app'::regnamespace and t.typtype = 'e'
            """).collect { it[0] } as Set
    }

    /** Ordered by enumsortorder, which is the order the type declares and not the order it was written. */
    private List<String> readLabelsOf(String type) {
        queryForRows("""
            select e.enumlabel
              from pg_type t
              join pg_enum e on e.enumtypid = t.oid
             where t.typnamespace = 'app'::regnamespace and t.typname = '${type}'
             order by e.enumsortorder
            """).collect { it[0] }
    }

    /**
     * Every check in the schema, every partial index predicate, and every column default and generated
     * expression, the last two keyed by table and column, scanned for the cast PostgreSQL prints around an
     * enum literal. Selecting candidates by "this constraint covers a column of an enum type" misses the
     * ordinary shapes: a check over an array of the enum, whose column type is the array type, and a
     * check casting a text column to the enum, whose covered column is the text one. The cast in the
     * stored definition is what is actually being pinned, so that is what is read.
     *
     * <p>Each label is counted once however often one definition names it, so a definition naming a
     * label twice is not a different pin from one naming it once.
     *
     * <p>The definitions come back from the server rather than from the file, so a rule written one
     * way and stored another is read as stored. Doubled quotes inside a literal are consumed by the
     * pattern rather than splitting it in two.
     */
    private Map<String, List<String>> labelsPinnedInDefinitions() {
        def types = labels.keySet()
        def found = [:]
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

    /**
     * Keyed on what the compiler produced, the way the logging walk is, so it cannot drift out of
     * step with the source it was meant to describe.
     */
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
