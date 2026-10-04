package org.lilradish.lite.app.run;

import static java.util.Objects.requireNonNull;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.log.annotation.DoNotLog;
import org.lilradish.lite.app.change.Author;
import org.lilradish.lite.app.inference.ToolCallOutcome;
import org.lilradish.lite.domain.codestep.CodeError;
import org.lilradish.lite.domain.codestep.CodeOutcome;
import org.lilradish.lite.domain.declaration.FieldName;
import org.lilradish.lite.domain.declaration.FieldStanding;
import org.lilradish.lite.domain.filling.FillPath;
import org.lilradish.lite.domain.identity.SystemPrincipal;
import org.lilradish.lite.domain.identity.UserId;
import org.lilradish.lite.domain.inference.CallOutcome;
import org.lilradish.lite.domain.inference.Confidence;
import org.lilradish.lite.domain.inference.DidNotFitReason;
import org.lilradish.lite.domain.inference.Envelope;
import org.lilradish.lite.domain.inference.ForeignProse;
import org.lilradish.lite.domain.inference.KeptAnswer;
import org.lilradish.lite.domain.inference.ModelCallOutcome;
import org.lilradish.lite.domain.inference.ModelCallPurpose;
import org.lilradish.lite.domain.model.DeployedTool;
import org.lilradish.lite.domain.model.ModelMode;
import org.lilradish.lite.domain.model.ModelToolName;
import org.lilradish.lite.domain.model.ToolPrice;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.lilradish.lite.domain.run.HoldRecord;
import org.lilradish.lite.domain.run.InputRecord;
import org.lilradish.lite.domain.run.ModelCallId;
import org.lilradish.lite.domain.run.ProductionId;
import org.lilradish.lite.domain.run.ProductionValueId;
import org.lilradish.lite.domain.run.ReviewOutcome;
import org.lilradish.lite.domain.run.ReviewUnbuiltReason;
import org.lilradish.lite.domain.run.RunId;
import org.lilradish.lite.domain.run.RunSnapshot;
import org.lilradish.lite.domain.run.RunStepFailureId;
import org.lilradish.lite.domain.run.RunStepFailureReason;
import org.lilradish.lite.domain.run.RunStepHoldReason;
import org.lilradish.lite.domain.run.RunStepId;
import org.lilradish.lite.domain.run.RunStepSendAttemptId;
import org.lilradish.lite.domain.run.StepInputs;
import org.lilradish.lite.domain.run.StepSnapshot;
import org.lilradish.lite.domain.run.TryLostReason;
import org.lilradish.lite.domain.search.SearchRecord;
import org.lilradish.lite.domain.search.SearchUses;
import org.lilradish.lite.domain.wire.CanonicalJson;
import org.lilradish.lite.domain.wire.JsonValue;
import org.lilradish.lite.domain.wire.StoreLabels;
import org.lilradish.lite.domain.workflow.ModelChoice;
import org.lilradish.lite.domain.workflow.Pointer;
import org.lilradish.lite.domain.workflow.StepProducer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Every row a run's steps are written with, each written only under the tree's lock that {@link LockedTree} is
 * the proof of. A guarded write finding nothing to change was changed outside that lock, and fails; a call's end
 * alone may find it ended already, and says so.
 */
@Component
final class EngineWrites {

    // DB-SPECIFIC: returning, greatest, now(), enum and jsonb casts, booleans and exists(…) selected as columns,
    // limit and is not distinct from are PostgreSQL's.
    private static final String AFFECTED_FILE_OUTPUTS = """
            with recursive file_fields (declaration_field_id, parent_field_id, name) as (
                select field.declaration_field_id, field.parent_field_id, field.name
                  from runs run join declaration_fields field on field.entry_version_id = run.entry_version_id
                 where run.run_id = :run and run.root_run_id = :root and run.group_id = :group
                   and field.side = 'gives' and field.kind = 'file'
                union
                select parent.declaration_field_id, parent.parent_field_id, parent.name
                  from declaration_fields parent
                  join file_fields child on child.parent_field_id = parent.declaration_field_id
            )
            select distinct target.name
              from runs run
              join productions made on made.run_id = run.run_id
              join run_steps step on step.run_step_id = made.run_step_id
              join production_values value on value.production_id = made.production_id
              left join declaration_fields produced_field
                   on produced_field.declaration_field_id = value.declaration_field_id
              join bindings output on output.entry_version_id = run.entry_version_id
                   and output.workflow_step_id is null and output.route_case_id is null
                   and output.source_step_id = step.workflow_step_id
                   and split_part(output.source_path, '.', 1) = coalesce(value.field_name, produced_field.name)
              join file_fields target on target.parent_field_id is null
                   and target.name = split_part(output.target_path, '.', 1)
             where run.run_id = :run and run.root_run_id = :root and run.group_id = :group
               and made.production_id = :try and made.yielded
               and coalesce(value.field_name, produced_field.name) = any(cast(:fields as text[]))
               and (not value.needs_review or exists (
                    select 1 from review_decisions decision
                     where decision.production_value_id = value.production_value_id
                       and not decision.for_length and decision.outcome = 'assured'))
               and not exists (select 1 from review_decisions decision
                                where decision.production_value_id = value.production_value_id
                                  and decision.for_length and decision.outcome = 'refused')
            """;

    /* What the step declares is copied from the version, so the keys holding a step to it hold by construction. */
    private static final String RUN_STEP = """
            insert into run_steps (run_id, entry_version_id, workflow_step_id, step_kind, pinned_version_id,
                                   pinned_kind, producer, reviewed_by_model, tries, created_by, created_by_kind)
            select :run, step.entry_version_id, step.workflow_step_id, step.kind, step.pinned_version_id,
                   step.pinned_kind, step.producer, step.reviewed_by_model, step.tries, :system, 'system'
              from workflow_steps step
             where step.workflow_step_id = :step and step.entry_version_id = :version
            returning run_step_id
            """;

    private static final String TRY_ASKED = """
            insert into productions (run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                                     reviewed_by_model, tries, pinned_version_id, try_number, producer,
                                     may_run_again, created_by, created_by_kind)
            select step.run_step_id, step.run_id, :root, step.kind, step.producer, step.reviewed_by_model,
                   step.tries, step.pinned_version_id, :number, cast(:producer as step_producer),
                   cast(:mayRunAgain as boolean), %s, '%s'
              from run_steps step
             where step.run_step_id = :runStep
            returning production_id
            """;

    private static final String TRY_ASKED_BY_SYSTEM = TRY_ASKED.formatted(":system", "system");

    private static final String TRY_ASKED_BY_CALLER = TRY_ASKED.formatted(Author.OF_CALLER, "person");

    /* Where it was read from is traced through the value named, so no id is carried that the store could not hold. */
    private static final String INPUT = """
            insert into production_inputs (production_id, run_id, root_run_id, run_step_id, run_step_kind,
                                           workflow_step_id, binding_id, binding_reads_a_constant,
                                           source_workflow_step_id, source_run_step_id, source_run_step_kind,
                                           source_production_id, source_production_value_id)
            select try.production_id, try.run_id, try.root_run_id, try.run_step_id, try.run_step_kind,
                   step.workflow_step_id, binding.binding_id, binding.reads_a_constant, binding.source_step_id,
                   source_step.run_step_id, source_step.kind, source.production_id, source.production_value_id
              from productions try
              join run_steps step on step.run_step_id = try.run_step_id
              join bindings binding on binding.binding_id = :binding and binding.workflow_step_id = step.workflow_step_id
              left join production_values source on source.production_value_id = cast(:source as uuid)
              left join run_steps source_step on source_step.run_id = try.run_id
                                             and source_step.workflow_step_id = binding.source_step_id
             where try.production_id = :try
               and (cast(:source as uuid) is null or source.production_value_id is not null)
            returning production_input_id
            """;

    private static final String INPUT_PART = """
            insert into production_input_parts (production_input_id, root_run_id, target_path,
                                                source_production_id, source_production_value_id)
            select taken.production_input_id, taken.root_run_id, :targetPath,
                   source.production_id, source.production_value_id
              from production_inputs taken
              left join production_values source on source.production_value_id = cast(:source as uuid)
             where taken.production_input_id = :input
               and (cast(:source as uuid) is null or source.production_value_id is not null)
            """;

    private static final String SEARCH_INPUT = """
            insert into production_inputs (production_id, run_id, root_run_id, run_step_id, run_step_kind,
                                           workflow_step_id, search_question_part_id, binding_reads_a_constant,
                                           source_workflow_step_id, source_run_step_id, source_run_step_kind,
                                           source_production_id, source_production_value_id)
            select try.production_id, try.run_id, try.root_run_id, try.run_step_id, try.run_step_kind,
                   step.workflow_step_id, part.search_question_part_id, false, part.source_step_id,
                   source_step.run_step_id, source_step.kind, source.production_id, source.production_value_id
              from productions try
              join run_steps step on step.run_step_id = try.run_step_id
              join search_question_parts part on part.search_question_part_id = :part
                   and part.workflow_step_id = step.workflow_step_id and part.literal_text is null
              left join production_values source on source.production_value_id = cast(:source as uuid)
              left join run_steps source_step on source_step.run_id = try.run_id
                   and source_step.workflow_step_id = part.source_step_id
             where try.production_id = :try and try.run_step_kind = 'search'
               and (cast(:source as uuid) is null or source.production_value_id is not null)
            returning production_input_id
            """;

    private static final String BENEATH = """
            with minted as (select uuidv7() as run_id)
            insert into runs (run_id, group_id, number, entry_id, entry_version_id,
                              parent_run_id, parent_run_step_id, parent_run_step_kind,
                              parent_workflow_step_id, parent_route_case_id, root_run_id,
                              depth, parent_depth, created_by, created_by_kind)
            select minted.run_id, above.group_id, %s, version.entry_id, version.entry_version_id,
                   above.run_id, step.run_step_id, step.kind, step.workflow_step_id,
                   cast(:routeCase as uuid), above.root_run_id,
                   above.depth + 1, above.depth, :system, 'system'
              from minted, runs above
              join run_steps step on step.run_id = above.run_id and step.run_step_id = :runStep
              join entry_versions version on version.entry_version_id = :version
             where above.run_id = :above and above.root_run_id = :root
            returning run_id
            """.formatted(RunNumbering.next("above.group_id"));

    private static final String BRANCH_INPUT = """
            insert into production_inputs (branch_run_id, run_id, root_run_id, run_step_id, run_step_kind,
                                           workflow_step_id, binding_id, binding_reads_a_constant,
                                           source_workflow_step_id, source_run_step_id, source_run_step_kind,
                                           source_production_id, source_production_value_id)
            select child.run_id, above.run_id, above.root_run_id, step.run_step_id, step.kind,
                   step.workflow_step_id, binding.binding_id, binding.reads_a_constant, binding.source_step_id,
                   source_step.run_step_id, source_step.kind, source.production_id, source.production_value_id
              from runs child
              join runs above on above.run_id = child.parent_run_id
              join run_steps step on step.run_step_id = child.parent_run_step_id
              join bindings binding on binding.binding_id = :binding
                                   and binding.workflow_step_id = step.workflow_step_id
              left join production_values source on source.production_value_id = cast(:source as uuid)
              left join run_steps source_step on source_step.run_id = above.run_id
                                             and source_step.workflow_step_id = binding.source_step_id
             where child.run_id = :child and child.root_run_id = :root
               and (cast(:source as uuid) is null or source.production_value_id is not null)
            """;

    /* One on no try never ends, so a step that has one is never written another. */
    private static final String FAILED_ON_NO_TRY = """
            insert into run_step_failures (run_step_id, run_id, run_step_kind, calls_a_model,
                                           reason, detail, created_by, created_by_kind)
            select step.run_step_id, step.run_id, step.kind, step.calls_a_model,
                   cast(:reason as run_step_failure_reason), :detail, :system, 'system'
              from run_steps step
             where step.run_step_id = :runStep and step.run_id = :run
                   and not exists (select 1 from run_step_failures failure
                                    where failure.run_id = step.run_id
                                      and failure.run_step_id = step.run_step_id
                                      and failure.production_id is null)
            """;

    private static final String HOLD = """
            insert into run_step_holds (run_step_id, run_id, run_step_kind, calls_a_model, reason, created_by,
                                        created_by_kind)
            select step.run_step_id, step.run_id, step.kind, step.calls_a_model,
                   cast(:reason as run_step_hold_reason), :system, 'system'
              from run_steps step
             where step.run_step_id = :runStep
            """;

    private static final String RELEASE = """
            update run_step_holds
               set released_at = greatest(now(), created_at), released_by = :system, released_by_kind = 'system'
             where run_step_id = :runStep and released_at is null and reason = cast(:reason as run_step_hold_reason)
            """;

    /* The step and the try are the attempt's own, so a hold names the attempt it is for and no other step's. */
    private static final String HOLD_ON_ATTEMPT = """
            insert into run_step_holds (run_step_id, run_id, run_step_kind, calls_a_model, reason,
                                        run_step_send_attempt_id, created_by, created_by_kind)
            select step.run_step_id, step.run_id, step.kind, step.calls_a_model, cast(:reason as run_step_hold_reason),
                   attempt.run_step_send_attempt_id, :system, 'system'
              from run_step_send_attempts attempt
              join run_steps step on step.run_step_id = attempt.run_step_id
              join runs run on run.run_id = attempt.run_id and run.root_run_id = :root
             where attempt.run_step_send_attempt_id = :attempt
            """;

    private static final String FAILURE = """
            insert into run_step_failures (run_step_id, run_id, run_step_kind, calls_a_model, reason, production_id,
                                           purpose, detail, created_by, created_by_kind)
            select step.run_step_id, step.run_id, step.kind, step.calls_a_model,
                   cast(:reason as run_step_failure_reason), try.production_id, cast(:purpose as model_call_purpose),
                   :detail, :system, 'system'
              from run_steps step
              join productions try on try.run_step_id = step.run_step_id and try.production_id = :try
             where step.run_step_id = :runStep
            """;

    private static final String ATTEMPT = """
            insert into run_step_send_attempts (run_step_id, run_id, run_step_kind, workflow_step_id, purpose, model,
                                                mode, production_id, too_long, unbuilt_reason, payload, search_question,
                                                repeats_attempt_id, answers_failure_id, created_by, created_by_kind)
            select step.run_step_id, step.run_id, step.kind, step.workflow_step_id,
                   cast(:purpose as model_call_purpose), :model, :mode, try.production_id, :tooLong,
                   cast(:unbuilt as review_unbuilt_reason), cast(:payload as text), cast(:question as text), cast(:repeats as uuid),
                   cast(:answers as uuid), %s, '%s'
              from run_steps step
              join productions try on try.run_step_id = step.run_step_id and try.production_id = :try
             where step.run_step_id = :runStep
            returning run_step_send_attempt_id
            """;

    private static final String ATTEMPT_BY_SYSTEM = ATTEMPT.formatted(":system", "system");

    private static final String ATTEMPT_BY_CALLER = ATTEMPT.formatted(Author.OF_CALLER, "person");

    /* A repeat names the attempt holding its payload and never another repeat, so one join reaches what was sent. */
    private static final String STORED = """
            select holder.run_step_send_attempt_id, holder.payload, holder.search_question, call.envelope_version
              from run_step_send_attempts attempt
              join run_step_send_attempts holder on holder.run_step_send_attempt_id
                       = coalesce(attempt.repeats_attempt_id, attempt.run_step_send_attempt_id)
              join model_calls call on call.run_step_send_attempt_id = attempt.run_step_send_attempt_id
             where attempt.run_step_send_attempt_id = :attempt and call.root_run_id = :root
            """;

    /* What the call names of its attempt is copied from it, so the key holding it to that attempt holds. */
    private static final String CALL = """
            with numbered as (
                update productions production
                   set try_number = coalesce(production.try_number,
                           (select coalesce(max(other.try_number), 0) + 1 from productions other
                             where other.run_step_id = production.run_step_id
                               and other.production_id <> production.production_id))
                  from run_step_send_attempts attempt
                 where attempt.run_step_send_attempt_id = :attempt and not attempt.too_long
                   and attempt.purpose = 'produce' and attempt.production_id = production.production_id
                   and production.root_run_id = :root and production.producer = 'model'
                   and production.ended_at is null
                returning production.production_id)
            insert into model_calls (run_id, root_run_id, purpose, run_step_send_attempt_id, run_step_id,
                                     production_id, model, mode, envelope_version, sent_count, created_by,
                                     created_by_kind)
            select attempt.run_id, :root, attempt.purpose, attempt.run_step_send_attempt_id, attempt.run_step_id,
                   attempt.production_id, attempt.model, attempt.mode, :envelope, :sent, :system, 'system'
              from run_step_send_attempts attempt
              join productions production on production.production_id = attempt.production_id
             where attempt.run_step_send_attempt_id = :attempt and not attempt.too_long
               and production.root_run_id = :root
               and (attempt.purpose = 'review' or attempt.production_id in (select production_id from numbered))
            returning model_call_id
            """;

    private static final String UNNUMBER_TURNED_AWAY = """
            update productions production set try_number = null
              from model_calls call
             where call.model_call_id = :call and call.root_run_id = :root
               and call.outcome = 'turned_away' and call.purpose = 'produce'
               and call.production_id = production.production_id and production.ended_at is null
            """;

    /* Each end is guarded by the call being out still, so an end that comes late finds it ended and changes nothing. */
    private static final String CALL_ENDED = """
            update model_calls
               set outcome = cast(:outcome as model_call_outcome), ended_at = greatest(now(), created_at)%s
             where model_call_id = :call and root_run_id = :root and outcome is null
            """;

    private static final String CALL_CAME_BACK = CALL_ENDED.formatted("""
            , sent_count = :sent, came_back_count = :cameBack,
                   counted_by_model = :counted, answer = :answer, answer_altered = :altered,
                   answer_truncated = :answerTruncated, declined_words = :words,
                   declined_words_altered = :wordsAltered, declined_words_truncated = :wordsTruncated""");

    private static final String CALL_ERRORED = CALL_ENDED.formatted(
            ", error_detail = :detail, error_detail_truncated = :cut, unreadable_response = :unreadable,"
                    + " unreadable_response_altered = :unreadableAltered,"
                    + " unreadable_response_truncated = :unreadableTruncated");

    /* Ended as turned away or as nothing came back, with nothing else written on the call. */
    private static final String CALL_ENDED_ALONE = CALL_ENDED.formatted("");

    /* Ended after its call, which the key naming the call's outcome requires. */
    private static final String MODEL_TRY_ENDED = """
            update productions
               set ended_at = greatest(now(), created_at), ended_by = :system, ended_by_kind = 'system',
                   model_call_id = :call, model_call_outcome = cast(:outcome as model_call_outcome),
                   call_answer_altered = :altered, lost_reason = cast(:lost as try_lost_reason),
                   did_not_fit_reason = cast(:misfit as did_not_fit_reason)
             where production_id = :try and root_run_id = :root and ended_at is null
               and producer = cast(:producer as step_producer)
            """;

    /* Guarded by how the call stands: out still for one it is sent again after, ended for the one that ended it. */
    private static final String TURNAWAY = """
            insert into model_call_turnaways (model_call_id, said, said_truncated, spent_up, model_call_outcome,
                                              created_by, created_by_kind)
            select call.model_call_id, cast(:said as text), :cut, :spentUp, cast(:spentOutcome as model_call_outcome),
                   :system, 'system'
              from model_calls call
             where call.model_call_id = :call and call.root_run_id = :root
               and call.outcome is not distinct from cast(:callOutcome as model_call_outcome)
            """;

    private static final String CALL_STANDING = """
            select call.outcome is null as unended, %s as stopped
              from model_calls call
             where call.model_call_id = :call and call.root_run_id = :root
            """.formatted(RunTree.stopInForce("call.root_run_id"));

    private static final String RESENT = """
            update model_call_turnaways turnaway
               set resent_at = greatest(now(), turnaway.created_at)
             where turnaway.resent_at is null
               and turnaway.model_call_turnaway_id = (%s)
            """.formatted(newestTurnaway(":call"));

    private static final String END_WAITING_CALLS = """
            with ended as (
                update model_calls call
                   set outcome = 'turned_away', ended_at = greatest(now(), call.created_at)
                 where call.root_run_id = :root and call.outcome is null
                   and exists (select 1 from model_call_turnaways latest
                                where latest.model_call_turnaway_id = (%s) and latest.resent_at is null)
                returning call.purpose, call.production_id, call.run_step_send_attempt_id),
            unnumbered as (
                update productions production set try_number = null from ended
                 where ended.purpose = 'produce' and ended.production_id = production.production_id
                   and production.ended_at is null)
            select run_step_send_attempt_id from ended where purpose = 'produce'
            """.formatted(newestTurnaway("call.model_call_id"));

    /* No earlier than the last opening again, as a person's stop is, so the stops of a run read in the order made. */
    // DB-SPECIFIC: clock_timestamp() is PostgreSQL's current wall time.
    private static final String STOPPED_AT_CEILING = """
            insert into run_stops (run_id, root_run_id, ceiling_run_id, created_at, created_by, created_by_kind)
            select :root, :root, :ceiling, greatest(clock_timestamp(), max(stop.opened_again_at)), :system, 'system'
              from run_stops stop
             where stop.run_id = :root
            returning run_stop_id
            """;

    // DB-SPECIFIC: unnest and array casts are PostgreSQL's.
    private static final String ALSO_REACHED = """
            insert into run_stop_additional_ceilings (run_stop_id, root_run_id, stop_ceiling_run_id, ceiling_run_id)
            select :stop, :root, :ceiling, further from unnest(cast(:further as uuid[])) further
            """;

    /* Ended before its values are written, which only a try that gave something back may hold. */
    private static final String ANSWERED = """
            update productions
               set ended_at = greatest(now(), created_at), ended_by = %s, ended_by_kind = 'person',
                   explanation = :why
             where production_id = :try and ended_at is null and producer = 'person'
            """.formatted(Author.OF_CALLER);

    /* A question try takes its field and standing rule from its pinned version. */
    private static final String QUESTION_VALUE = """
            insert into production_values (production_id, run_step_kind, producer, pinned_version_id,
                                           declaration_field_id, field_standing, standing_threshold, value,
                                           confidence)
            select try.production_id, try.run_step_kind, try.producer, try.pinned_version_id,
                   field.declaration_field_id, field.standing, field.standing_threshold,
                   cast(cast(:value as text) as jsonb), cast(:confidence as integer)
              from productions try
              join declaration_fields field
                on field.declaration_field_id = :field and field.entry_version_id = try.pinned_version_id
             where try.production_id = :try
            """;

    /* Guarded by the try being open still, so an end that comes after it was ended otherwise changes nothing. */
    private static final String CODE_ENDED = """
            update productions
               set ended_at = greatest(now(), created_at), ended_by = :system, ended_by_kind = 'system',
                   lost_reason = cast(:lost as try_lost_reason),
                   code_error_reason = cast(:reason as code_error_reason), code_error_path = :path,
                   code_error_member = :member, code_error_member_truncated = :memberCut,
                   code_error_read_by_step = cast(:readByStep as uuid),
                   code_error_read_by_output = :readByOutput, lost_detail = :detail, lost_detail_truncated = :cut,
                   returned_by_code = :returned
             where production_id = :try and root_run_id = :root and producer = 'code' and ended_at is null
            """;

    /* Every try of code under the tree still open, which nothing runs once the system that ran it has stopped. */
    private static final String CODE_LOST = """
            update productions
               set ended_at = greatest(now(), created_at), ended_by = :system, ended_by_kind = 'system',
                   lost_reason = cast(:lost as try_lost_reason)
             where root_run_id = :root and producer = 'code' and ended_at is null
            returning production_id
            """;

    /* A code try takes its standing rule from its release, not a workflow version. */
    private static final String CODE_VALUE = """
            insert into production_values (production_id, run_step_kind, producer, field_name, field_standing,
                                           standing_threshold, value)
            select try.production_id, try.run_step_kind, try.producer, :field, cast(:standing as field_standing),
                   cast(:floor as integer), cast(cast(:value as text) as jsonb)
              from productions try
             where try.production_id = :try and try.run_step_kind = 'code_step'
            """;

    /* Where the step names a model to review, a person reviews only in its place, naming the attempt nothing was sent
     * for, too long for it or not built. */
    private static final String REVIEW = """
            insert into reviews (production_id, run_step_id, root_run_id, production_ended_by, reviewed_by_model,
                                 too_long_attempt_id, created_by, created_by_kind)
            select try.production_id, try.run_step_id, try.root_run_id, try.ended_by, try.reviewed_by_model,
                   (select attempt.run_step_send_attempt_id from run_step_send_attempts attempt
                     where attempt.reviewed_production_id = try.production_id and attempt.too_long), %s, 'person'
              from productions try
             where try.production_id = :try and try.yielded
            returning review_id
            """.formatted(Author.OF_CALLER);

    /* Written after the call ended, which the key naming the call's outcome requires. */
    private static final String REVIEWED_BY_MODEL = """
            insert into reviews (production_id, run_step_id, root_run_id, production_ended_by, reviewed_by_model,
                                 model_call_id, model_call_outcome, call_answer_altered, lost_reason,
                                 did_not_fit_reason, created_by, created_by_kind)
            select try.production_id, try.run_step_id, try.root_run_id, try.ended_by, try.reviewed_by_model, :call,
                   cast(:outcome as model_call_outcome), :altered, cast(:lost as try_lost_reason),
                   cast(:misfit as did_not_fit_reason), :system, 'system'
              from productions try
             where try.production_id = :try and try.root_run_id = :root and try.yielded
            returning review_id
            """;

    private static final String DECISION = """
            insert into review_decisions (review_id, production_value_id, production_id, for_length,
                                          value_needs_review, outcome, explanation)
            select :review, held.production_value_id, held.production_id, false, held.needs_review,
                   cast(:outcome as review_outcome), cast(:why as text)
              from production_values held
             where held.production_value_id = :value and held.production_id = :try
            """;

    private static final UUID SYSTEM = SystemPrincipal.WORKFLOW_RUNNER.subject().value();

    private static final String TURNED_AWAY = StoreLabels.label(ModelCallOutcome.TURNED_AWAY);

    private final JdbcClient database;

    EngineWrites(JdbcClient database) {
        this.database = database;
    }

    // DB-SPECIFIC: limit is PostgreSQL's.
    /**
     * The newest turnaway of the call {@code call} names, by when it was written and by its time-ordered key where
     * two were written in one instant: the one a resend marks, and the one a start reads a call as waiting on.
     */
    static String newestTurnaway(String call) {
        return """
                select newest.model_call_turnaway_id from model_call_turnaways newest
                 where newest.model_call_id = %s
                 order by newest.created_at desc, newest.model_call_turnaway_id desc
                 limit 1""".formatted(call);
    }

    /** The step's row in the run, written with the first thing written about it there. */
    RunStepId runStep(LockedTree tree, RunSnapshot run, StepSnapshot step) {
        held(tree, run);
        RunStepId written = step.runStep();
        if (written != null) {
            return written;
        }
        return new RunStepId(database.sql(RUN_STEP)
                .param("run", run.run().value())
                .param("system", SYSTEM)
                .param("step", step.planned().id().value())
                .param("version", run.version().value())
                .query(UUID.class)
                .single());
    }

    /**
     * A try asked for: by the system where {@code asker} is none, and otherwise by that person, with what it
     * takes as the store reads it now. Whether its code may be run again is said exactly where its step runs code,
     * as the release says it as the try is asked.
     */
    ProductionId tried(
            LockedTree tree,
            RunSnapshot run,
            RunStepId runStep,
            int number,
            StepProducer producer,
            @Nullable UserId asker,
            @Nullable Boolean mayRunAgain,
            List<InputRecord> inputs) {
        held(tree, run);
        JdbcClient.StatementSpec asked = asker == null
                ? database.sql(TRY_ASKED_BY_SYSTEM).param("system", SYSTEM)
                : database.sql(TRY_ASKED_BY_CALLER).param("caller", asker.value());
        ProductionId written = new ProductionId(asked.param("root", run.root().value())
                .param("runStep", runStep.value())
                .param("number", producer == StepProducer.MODEL ? null : number)
                .param("producer", StoreLabels.label(producer))
                .param("mayRunAgain", mayRunAgain)
                .query(UUID.class)
                .single());
        for (InputRecord input : inputs) {
            ProductionValueId source = input.source();
            JdbcClient.StatementSpec taking = input.searchQuestionPart() == null
                    ? database.sql(INPUT).param("binding", input.binding())
                    : database.sql(SEARCH_INPUT).param("part", input.searchQuestionPart());
            UUID recorded = taking.param(
                            "source", source == null ? null : source.value().toString())
                    .param("try", written.value())
                    .query(UUID.class)
                    .single();
            for (InputRecord.Part part : input.parts()) {
                ProductionValueId maker = part.source();
                changedOne(
                        database.sql(INPUT_PART)
                                .param("input", recorded)
                                .param(
                                        "targetPath",
                                        part.targetPath().stream()
                                                .map(FieldName::value)
                                                .collect(java.util.stream.Collectors.joining(".")))
                                .param(
                                        "source",
                                        maker == null ? null : maker.value().toString())
                                .update(),
                        "a component of input " + recorded);
            }
        }
        return written;
    }

    RunId beneath(
            LockedTree tree,
            RunSnapshot above,
            StepSnapshot step,
            EntryVersionId version,
            @Nullable UUID routeCase,
            StepInputs.@Nullable BindingRecord discriminator) {
        held(tree, above);
        if ((routeCase == null) != (discriminator == null)) {
            throw new IllegalArgumentException("A branch needs its discriminator, and only a branch has one");
        }
        if (discriminator != null && !discriminator.parts().isEmpty()) {
            throw new IllegalArgumentException("A route discriminator is not composite");
        }
        RunStepId runStep = runStep(tree, above, step);
        RunNumbering.hold(database, above.group());
        RunId child = new RunId(database.sql(BENEATH)
                .param("routeCase", routeCase)
                .param("system", SYSTEM)
                .param("runStep", runStep.value())
                .param("version", version.value())
                .param("above", above.run().value())
                .param("root", tree.root().value())
                .query(UUID.class)
                .optional()
                .orElseThrow(
                        () -> new IllegalStateException("Run " + above.run().value()
                                + " holds no step " + runStep.value() + " or version " + version.value()
                                + " to start a run beneath")));
        if (discriminator != null) {
            ProductionValueId source = discriminator.source();
            changedOne(
                    database.sql(BRANCH_INPUT)
                            .param("child", child.value())
                            .param("root", tree.root().value())
                            .param("binding", discriminator.binding().id())
                            .param(
                                    "source",
                                    source == null ? null : source.value().toString())
                            .update(),
                    "the discriminator of branch " + child.value());
        }
        return child;
    }

    /** A route failed on the term no case claimed, written as it is; none where nothing was given. */
    void unclaimed(LockedTree tree, RunSnapshot run, StepSnapshot step, @Nullable String said) {
        failedOnNoTry(tree, run, step, RunStepFailureReason.UNCLAIMED_VALUE, said);
    }

    /** A step that would have started a run deeper than runs may go, failed so and starting none. */
    void tooDeep(LockedTree tree, RunSnapshot run, StepSnapshot step) {
        failedOnNoTry(tree, run, step, RunStepFailureReason.TOO_DEEP, null);
    }

    private void failedOnNoTry(
            LockedTree tree, RunSnapshot run, StepSnapshot step, RunStepFailureReason reason, @Nullable String detail) {
        held(tree, run);
        RunStepId runStep = runStep(tree, run, step);
        changedOne(
                database.sql(FAILED_ON_NO_TRY)
                        .param("reason", StoreLabels.label(reason))
                        .param("runStep", runStep.value())
                        .param("run", run.run().value())
                        .param("detail", detail)
                        .param("system", SYSTEM)
                        .update(),
                "a failure on no try of step " + runStep.value());
    }

    /** A hold on the try the step would have made: on a stop, or on a code step the release does not hold. */
    void held(LockedTree tree, RunSnapshot run, RunStepId runStep, RunStepHoldReason reason) {
        held(tree, run);
        changedOne(
                database.sql(HOLD)
                        .param("reason", StoreLabels.label(unattempted(reason)))
                        .param("runStep", runStep.value())
                        .param("system", SYSTEM)
                        .update(),
                "a hold on step " + runStep.value());
    }

    void released(LockedTree tree, RunSnapshot run, RunStepId runStep, RunStepHoldReason reason) {
        held(tree, run);
        changedOne(
                database.sql(RELEASE)
                        .param("reason", StoreLabels.label(unattempted(reason)))
                        .param("runStep", runStep.value())
                        .param("system", SYSTEM)
                        .update(),
                "the hold on step " + runStep.value());
    }

    void heldOnSource(LockedTree tree, RunSnapshot run, RunStepId runStep, HoldRecord.SourceRecord source) {
        held(tree, run);
        requireNonNull(source, "An absent code source hold names its source");
        changedOne(
                database.sql("""
                        insert into run_step_holds (run_step_id, run_id, run_step_kind, calls_a_model,
                            reason, created_by, created_by_kind, root_run_id, source_run_id,
                            source_run_step_id, source_workflow_step_id)
                        select target.run_step_id, target.run_id, target.kind, target.calls_a_model,
                               'code_step_not_held', :system, 'system', targetRun.root_run_id,
                               source.run_id, source.run_step_id, source.workflow_step_id
                          from run_steps target
                          join runs targetRun on targetRun.run_id = target.run_id
                          join runs sourceRun on sourceRun.root_run_id = targetRun.root_run_id
                          join run_steps source on source.run_id = sourceRun.run_id
                         where target.run_step_id = :runStep and target.run_id = :run
                           and targetRun.root_run_id = :root and target.calls_a_model
                           and source.run_id = :sourceRun and source.run_step_id = :sourceRunStep
                           and source.workflow_step_id = :sourceStep and source.kind = 'code_step'
                        """)
                        .param("runStep", runStep.value())
                        .param("run", run.run().value())
                        .param("root", tree.root().value())
                        .param("system", SYSTEM)
                        .param("sourceRun", source.run().value())
                        .param("sourceRunStep", source.runStep().value())
                        .param("sourceStep", source.step())
                        .update(),
                "a hold on the code source of step " + runStep.value());
    }

    private static RunStepHoldReason unattempted(RunStepHoldReason reason) {
        if (reason != RunStepHoldReason.ENTRY_STOPPED
                && reason != RunStepHoldReason.CODE_STEP_NOT_HELD
                && reason != RunStepHoldReason.SEARCH_WAY_NOT_HELD) {
            throw new IllegalArgumentException("A hold on length or on a turnaway names the attempt it is for");
        }
        return reason;
    }

    /** The hold on length or on a turnaway released, by the system, for the try it held to be sent again. */
    void releasedToSend(LockedTree tree, RunSnapshot run, RunStepId runStep, RunStepHoldReason reason) {
        held(tree, run);
        changedOne(
                database.sql(RELEASE)
                        .param("reason", StoreLabels.label(onAnAttempt(reason)))
                        .param("runStep", runStep.value())
                        .param("system", SYSTEM)
                        .update(),
                "the hold on step " + runStep.value());
    }

    private static RunStepHoldReason onAnAttempt(RunStepHoldReason reason) {
        if (reason != RunStepHoldReason.TOO_LONG && reason != RunStepHoldReason.TURNED_AWAY) {
            throw new IllegalArgumentException("Only a hold on length or on a turnaway names an attempt");
        }
        return reason;
    }

    /** A hold on the step {@code attempt} would produce, for it: too long to send, or its call turned away. */
    void heldOn(LockedTree tree, RunStepSendAttemptId attempt, RunStepHoldReason reason) {
        held(tree);
        onAnAttempt(reason);
        changedOne(
                database.sql(HOLD_ON_ATTEMPT)
                        .param("reason", StoreLabels.label(reason))
                        .param("attempt", attempt.value())
                        .param("root", tree.root().value())
                        .param("system", SYSTEM)
                        .update(),
                "a hold for attempt " + attempt.value());
    }

    /**
     * What failed a try before anything was sent to a model to produce it or to review it, for {@code purpose}, said
     * in {@code detail}.
     */
    void failed(
            LockedTree tree,
            RunSnapshot run,
            RunStepId runStep,
            ProductionId aTry,
            ModelCallPurpose purpose,
            RunStepFailureReason reason,
            String detail) {
        held(tree, run);
        changedOne(
                database.sql(FAILURE)
                        .param("reason", StoreLabels.label(reason))
                        .param("try", aTry.value())
                        .param("purpose", StoreLabels.label(purpose))
                        .param("detail", detail)
                        .param("system", SYSTEM)
                        .param("runStep", runStep.value())
                        .update(),
                "a failure of try " + aTry.value());
    }

    void sourceFailure(
            LockedTree tree,
            RunSnapshot run,
            RunStepId runStep,
            ProductionId aTry,
            StepInputs.SourceFailureRecord failure) {
        held(tree, run);
        HoldRecord.SourceRecord source = failure.source().maker();
        changedOne(
                database.sql("""
                        insert into run_step_failures (run_step_id, run_id, run_step_kind, calls_a_model,
                            reason, production_id, purpose, detail, created_by, created_by_kind,
                            root_run_id, source_run_id, source_run_step_id, source_workflow_step_id, source_field)
                        select target.run_step_id, target.run_id, target.kind, target.calls_a_model,
                               cast(:reason as run_step_failure_reason), try.production_id, 'produce', :detail, :system, 'system',
                               targetRun.root_run_id, source.run_id, source.run_step_id,
                               source.workflow_step_id, :field
                          from run_steps target
                          join runs targetRun on targetRun.run_id = target.run_id
                          join productions try on try.run_step_id = target.run_step_id
                          join runs sourceRun on sourceRun.root_run_id = targetRun.root_run_id
                          join run_steps source on source.run_id = sourceRun.run_id
                         where target.run_step_id = :runStep and target.run_id = :run
                           and targetRun.root_run_id = :root and target.calls_a_model
                           and try.production_id = :try and try.producer = 'model' and try.ended_at is null
                           and source.run_id = :sourceRun and source.run_step_id = :sourceRunStep
                           and source.workflow_step_id = :sourceStep and source.kind = 'code_step'
                        """)
                        .param("runStep", runStep.value())
                        .param("run", run.run().value())
                        .param("root", tree.root().value())
                        .param("try", aTry.value())
                        .param("system", SYSTEM)
                        .param("sourceRun", source.run().value())
                        .param("sourceRunStep", source.runStep().value())
                        .param("sourceStep", source.step())
                        .param("reason", StoreLabels.label(failure.reason()))
                        .param("field", failure.source().field())
                        .param("detail", "A field read from code no longer matches its current declaration.")
                        .update(),
                "a source failure of try " + aTry.value());
    }

    void toolNotOffered(LockedTree tree, RunSnapshot run, RunStepId runStep, ProductionId aTry, ModelToolName tool) {
        held(tree, run);
        changedOne(
                database.sql("""
                        insert into run_step_failures (run_step_id, run_id, run_step_kind, calls_a_model, reason,
                                                       production_id, purpose, tool, detail, created_by, created_by_kind)
                        select step.run_step_id, step.run_id, step.kind, step.calls_a_model, 'tool_not_offered',
                               try.production_id, 'produce', :tool, :detail, :system, 'system'
                          from run_steps step
                          join productions try on try.run_step_id = step.run_step_id and try.production_id = :try
                         where step.run_step_id = :runStep
                        """)
                        .param("runStep", runStep.value())
                        .param("try", aTry.value())
                        .param("tool", tool.value())
                        .param("detail", "The model no longer offers the tool this search needs.")
                        .param("system", SYSTEM)
                        .update(),
                "the missing tool of try " + aTry.value());
    }

    /**
     * An attempt to send a try to the model {@code choice} names, to produce it or to review it, the presser's where
     * there is one and else the system's; nothing is sent where it is {@code tooLong}. A model run as it is is written
     * in the word the store keeps for that.
     */
    RunStepSendAttemptId attempted(
            LockedTree tree,
            RunSnapshot run,
            RunStepId runStep,
            ProductionId aTry,
            ModelCallPurpose purpose,
            ModelChoice choice,
            boolean tooLong,
            @Nullable String payload,
            @Nullable RunStepSendAttemptId repeats,
            @Nullable RunStepFailureId answers,
            @Nullable UserId presser) {
        return attempted(tree, run, runStep, aTry, purpose, choice, tooLong, payload, null, repeats, answers, presser);
    }

    RunStepSendAttemptId attempted(
            LockedTree tree,
            RunSnapshot run,
            RunStepId runStep,
            ProductionId aTry,
            ModelCallPurpose purpose,
            ModelChoice choice,
            boolean tooLong,
            @Nullable String payload,
            @Nullable String question,
            @Nullable RunStepSendAttemptId repeats,
            @Nullable RunStepFailureId answers,
            @Nullable UserId presser) {
        held(tree, run);
        if ((payload == null) == (repeats == null)) {
            throw new IllegalArgumentException("An attempt holds what it sends exactly where it repeats none");
        }
        JdbcClient.StatementSpec attempt = presser == null
                ? database.sql(ATTEMPT_BY_SYSTEM).param("system", SYSTEM)
                : database.sql(ATTEMPT_BY_CALLER).param("caller", presser.value());
        return inserted(
                attempt.param("question", question),
                runStep,
                aTry,
                purpose,
                choice,
                tooLong,
                null,
                payload,
                repeats,
                answers);
    }

    /**
     * The system's attempt to have the model {@code choice} names review {@code aTry}, written where what it would be
     * sent could not be built, for {@code unbuilt}: nothing measured or sent, a person reviewing in the model's place.
     */
    void unbuilt(
            LockedTree tree,
            RunSnapshot run,
            RunStepId runStep,
            ProductionId aTry,
            ModelChoice choice,
            ReviewUnbuiltReason unbuilt,
            @Nullable RunStepFailureId answers) {
        held(tree, run);
        requireNonNull(unbuilt, "EngineWrites unbuilt must not be null");
        inserted(
                database.sql(ATTEMPT_BY_SYSTEM).param("system", SYSTEM).param("question", null),
                runStep,
                aTry,
                ModelCallPurpose.REVIEW,
                choice,
                true,
                unbuilt,
                null,
                null,
                answers);
    }

    private static RunStepSendAttemptId inserted(
            JdbcClient.StatementSpec attempt,
            RunStepId runStep,
            ProductionId aTry,
            ModelCallPurpose purpose,
            ModelChoice choice,
            boolean tooLong,
            @Nullable ReviewUnbuiltReason unbuilt,
            @Nullable String payload,
            @Nullable RunStepSendAttemptId repeats,
            @Nullable RunStepFailureId answers) {
        ModelMode mode = choice.mode();
        return new RunStepSendAttemptId(attempt.param("purpose", StoreLabels.label(purpose))
                .param("model", choice.model().value())
                .param("mode", mode == null ? ModelMode.RESERVED_FOR_AS_IT_IS : mode.value())
                .param("tooLong", tooLong)
                .param("unbuilt", unbuilt == null ? null : StoreLabels.label(unbuilt))
                .param("payload", payload)
                .param("repeats", repeats == null ? null : repeats.value().toString())
                .param("answers", answers == null ? null : answers.value().toString())
                .param("try", aTry.value())
                .param("runStep", runStep.value())
                .query(UUID.class)
                .single());
    }

    /** What the call {@code attempt} let through sent, and the attempt holding it, to be sent again as it was. */
    SentRecord stored(LockedTree tree, RunStepSendAttemptId attempt) {
        held(tree);
        return database.sql(STORED)
                .param("attempt", attempt.value())
                .param("root", tree.root().value())
                .query((result, number) -> new SentRecord(
                        new RunStepSendAttemptId(result.getObject("run_step_send_attempt_id", UUID.class)),
                        requireNonNull(result.getString("payload"), "a repeat names an attempt holding its payload"),
                        result.getInt("envelope_version"),
                        result.getString("search_question")))
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "Tree " + tree.root().value() + " holds no call attempt " + attempt.value() + " sent"));
    }

    JsonValue.JsonObject searchReviewRecord(LockedTree tree, ModelCallId call) {
        held(tree);
        JsonValue.JsonObject flags = database.sql("""
                        select uses.searches_cut, uses.searches_not_as_it_came, uses.searches_left_out
                          from model_call_tool_uses uses
                          join model_calls call on call.model_call_id = uses.model_call_id
                          join run_steps step on step.run_step_id = call.run_step_id
                         where call.model_call_id = :call and call.root_run_id = :root
                           and call.purpose = 'produce' and call.outcome = 'came_back'
                           and uses.model_call_outcome = 'came_back' and step.step_kind = 'search'
                        """)
                .param("call", call.value())
                .param("root", tree.root().value())
                .query((row, number) -> new JsonValue.JsonObject(List.of(
                        new JsonValue.JsonMember("cut", new JsonValue.JsonBoolean(row.getBoolean("searches_cut"))),
                        new JsonValue.JsonMember(
                                "not_as_it_came", new JsonValue.JsonBoolean(row.getBoolean("searches_not_as_it_came"))),
                        new JsonValue.JsonMember(
                                "left_out",
                                new JsonValue.JsonNumber(BigDecimal.valueOf(row.getInt("searches_left_out")))))))
                .optional()
                .orElseThrow(() -> new IllegalStateException("A completed search production holds its tool record"));
        List<JsonValue> searches = database.sql("""
                        select position, searched_for, found from model_call_searches
                         where model_call_id = :call order by position
                        """)
                .param("call", call.value())
                .query((row, number) -> {
                    if (row.getInt("position") != number + 1) {
                        throw new IllegalStateException("A search record keeps consecutive positions");
                    }
                    List<JsonValue.JsonMember> members = new ArrayList<>();
                    String searched = row.getString("searched_for");
                    String found = row.getString("found");
                    if (searched != null) {
                        members.add(new JsonValue.JsonMember("searched_for", new JsonValue.JsonString(searched)));
                    }
                    if (found != null) {
                        members.add(new JsonValue.JsonMember("found", new JsonValue.JsonString(found)));
                    }
                    return (JsonValue) new JsonValue.JsonObject(members);
                })
                .list();
        List<JsonValue.JsonMember> members = new ArrayList<>(flags.members());
        members.addFirst(new JsonValue.JsonMember("searches", new JsonValue.JsonArray(searches)));
        return new JsonValue.JsonObject(members);
    }

    /** The call {@code attempt} lets through, written down as sent before it is, and counted as measured here. */
    ModelCallId called(LockedTree tree, RunSnapshot run, RunStepSendAttemptId attempt, long sentCount) {
        held(tree, run);
        return new ModelCallId(database.sql(CALL)
                .param("root", run.root().value())
                .param("envelope", Envelope.VERSION)
                .param("sent", sentCount)
                .param("system", SYSTEM)
                .param("attempt", attempt.value())
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException("Attempt " + attempt.value()
                        + " was too long to send, or was written outside its tree's lock")));
    }

    void searchSent(LockedTree tree, RunSnapshot run, ModelCallId call, DeployedTool tool) {
        held(tree, run);
        changedOne(
                database.sql("""
                        insert into model_call_tool_uses (model_call_id, run_id, tool, most_uses)
                        select call.model_call_id, call.run_id, :tool, :maximum
                          from model_calls call
                          join run_steps step on step.run_step_id = call.run_step_id
                         where call.model_call_id = :call and call.root_run_id = :root
                           and call.run_id = :run and call.purpose = 'produce' and step.step_kind = 'search'
                        """)
                        .param("call", call.value())
                        .param("root", tree.root().value())
                        .param("run", run.run().value())
                        .param("tool", tool.name().value())
                        .param("maximum", tool.maxUsesPerCall())
                        .update(),
                "the tool of search call " + call.value());
        for (ToolPrice price : tool.pricesPerUse()) {
            changedOne(
                    database.sql("""
                            insert into model_call_tool_prices (model_call_id, currency, per_use)
                            values (:call, :currency, :price)
                            """)
                            .param("call", call.value())
                            .param("currency", price.currency().getCurrencyCode())
                            .param("price", price.perUse())
                            .update(),
                    "a price of search call " + call.value());
        }
    }

    void searchCameBack(LockedTree tree, ModelCallId call, ToolCallOutcome reports) {
        held(tree);
        RequestedTool requested = requireNonNull(requestedTool(tree, call), "a search call holds its requested tool");
        SearchUses uses = requireNonNull(SearchUses.counted(
                ModelCallOutcome.CAME_BACK, reports.reportedUses().get(requested.tool()), requested.maximum()));
        SearchRecord.CallSearchRecord record = SearchRecord.kept(reports.observations().stream()
                .filter(observation -> observation.tool().equals(requested.tool()))
                .map(observation ->
                        new org.lilradish.lite.domain.search.SearchSaid(observation.searchedFor(), observation.found()))
                .toList());
        countedSearch(tree, call, ModelCallOutcome.CAME_BACK, uses, record);
        int position = 0;
        for (SearchRecord search : record.searches()) {
            changedOne(
                    database.sql("""
                            insert into model_call_searches (model_call_id, position, searched_for, found)
                            values (:call, :position, :searchedFor, :found)
                            """)
                            .param("call", call.value())
                            .param("position", ++position)
                            .param("searchedFor", search.searchedFor())
                            .param("found", search.found())
                            .update(),
                    "a record of search call " + call.value());
        }
    }

    private void searchLost(LockedTree tree, ModelCallId call, ModelCallOutcome outcome) {
        RequestedTool requested = requestedTool(tree, call);
        if (requested != null) {
            countedSearch(
                    tree,
                    call,
                    outcome,
                    requireNonNull(SearchUses.counted(outcome, null, requested.maximum())),
                    new SearchRecord.CallSearchRecord(List.of(), false, false, 0));
        }
    }

    private @Nullable RequestedTool requestedTool(LockedTree tree, ModelCallId call) {
        return database.sql("""
                        select step.step_kind = 'search' and call.purpose = 'produce' as searching,
                               uses.tool, uses.most_uses
                          from model_calls call
                          join run_steps step on step.run_step_id = call.run_step_id
                          left join model_call_tool_uses uses on uses.model_call_id = call.model_call_id
                         where call.model_call_id = :call and call.root_run_id = :root
                        """)
                .param("call", call.value())
                .param("root", tree.root().value())
                .query((row, number) -> {
                    if (!row.getBoolean("searching")) {
                        return Optional.<RequestedTool>empty();
                    }
                    return Optional.of(new RequestedTool(
                            new ModelToolName(
                                    requireNonNull(row.getString("tool"), "a search call holds its requested tool")),
                            row.getLong("most_uses")));
                })
                .single()
                .orElse(null);
    }

    private void countedSearch(
            LockedTree tree,
            ModelCallId call,
            ModelCallOutcome outcome,
            SearchUses uses,
            SearchRecord.CallSearchRecord record) {
        changedOne(
                database.sql("""
                        update model_call_tool_uses uses
                           set model_call_outcome = cast(:outcome as model_call_outcome),
                               uses = :uses, uses_said = :said, searches_cut = :cut,
                               searches_not_as_it_came = :altered, searches_left_out = :leftOut
                          from model_calls call
                         where uses.model_call_id = call.model_call_id and call.model_call_id = :call
                           and call.root_run_id = :root and uses.uses is null
                        """)
                        .param("call", call.value())
                        .param("root", tree.root().value())
                        .param("outcome", StoreLabels.label(outcome))
                        .param("uses", uses.uses())
                        .param("said", uses.reported())
                        .param("cut", record.cut())
                        .param("altered", record.notAsItCame())
                        .param("leftOut", record.leftOut())
                        .update(),
                "the count of search call " + call.value());
    }

    private record RequestedTool(ModelToolName tool, long maximum) {}

    /** False where the call had ended already, and then nothing is written. */
    boolean cameBack(
            LockedTree tree,
            ModelCallId call,
            CallOutcome.CameBack cameBack,
            KeptAnswer kept,
            @Nullable KeptAnswer declinedWords) {
        held(tree);
        return ended(database.sql(CALL_CAME_BACK)
                .param("outcome", StoreLabels.label(ModelCallOutcome.CAME_BACK))
                .param("sent", cameBack.sentCount())
                .param("cameBack", cameBack.cameBackCount())
                .param("counted", cameBack.countedByModel())
                .param("answer", kept.text())
                .param("altered", kept.altered())
                .param("answerTruncated", kept.truncated())
                .param("words", declinedWords == null ? null : declinedWords.text())
                .param("wordsAltered", declinedWords != null && declinedWords.altered())
                .param("wordsTruncated", declinedWords != null && declinedWords.truncated())
                .param("call", call.value())
                .param("root", tree.root().value())
                .update());
    }

    /** False where the call had ended already, and then nothing is written. */
    boolean errored(LockedTree tree, ModelCallId call, ForeignProse detail, @Nullable KeptAnswer unreadable) {
        held(tree);
        boolean changed = ended(database.sql(CALL_ERRORED)
                .param("outcome", StoreLabels.label(ModelCallOutcome.ERRORED))
                .param("detail", detail.text())
                .param("cut", detail.truncated())
                .param("unreadable", unreadable == null ? null : unreadable.text())
                .param("unreadableAltered", unreadable != null && unreadable.altered())
                .param("unreadableTruncated", unreadable != null && unreadable.truncated())
                .param("call", call.value())
                .param("root", tree.root().value())
                .update());
        if (changed) {
            searchLost(tree, call, ModelCallOutcome.ERRORED);
        }
        return changed;
    }

    /** False where the call had ended already, and then nothing is written. */
    boolean turnedAway(LockedTree tree, ModelCallId call) {
        held(tree);
        boolean changed = ended(database.sql(CALL_ENDED_ALONE)
                .param("outcome", TURNED_AWAY)
                .param("call", call.value())
                .param("root", tree.root().value())
                .update());
        if (changed) {
            database.sql(UNNUMBER_TURNED_AWAY)
                    .param("call", call.value())
                    .param("root", tree.root().value())
                    .update();
        }
        return changed;
    }

    /**
     * Nothing came back for {@code call}, what was sent staying counted as measured here and what came back unknown.
     * False where the call had ended already, and then nothing is written.
     */
    boolean nothingCameBack(LockedTree tree, ModelCallId call) {
        held(tree);
        boolean changed = ended(database.sql(CALL_ENDED_ALONE)
                .param("outcome", StoreLabels.label(ModelCallOutcome.NOTHING_CAME_BACK))
                .param("call", call.value())
                .param("root", tree.root().value())
                .update());
        if (changed) {
            searchLost(tree, call, ModelCallOutcome.NOTHING_CAME_BACK);
        }
        return changed;
    }

    void searchValue(LockedTree tree, ProductionId aTry, FieldName field, FieldStanding standing, JsonValue value) {
        held(tree);
        if (standing != FieldStanding.ALWAYS && standing != FieldStanding.NEVER) {
            throw new IllegalArgumentException("A search's value stands always or never");
        }
        changedOne(
                database.sql("""
                        insert into production_values (production_id, run_step_kind, producer, field_name,
                                                       field_standing, value)
                        select try.production_id, try.run_step_kind, try.producer, :field,
                               cast(:standing as field_standing), cast(cast(:value as text) as jsonb)
                          from productions try where try.production_id = :try and try.run_step_kind = 'search'
                           and try.root_run_id = :root
                        """)
                        .param("try", aTry.value())
                        .param("root", tree.root().value())
                        .param("field", field.value())
                        .param("standing", StoreLabels.label(standing))
                        .param("value", value instanceof JsonValue.JsonNull ? null : CanonicalJson.write(value))
                        .update(),
                "a search value of try " + aTry.value());
    }

    /**
     * A model's try ended as {@code call} ended, by the system: lost where {@code lost} is one, and why its answer
     * did not fit exactly where it did not.
     */
    void modelTryEnded(
            LockedTree tree,
            ProductionId aTry,
            ModelCallId call,
            ModelCallOutcome outcome,
            boolean answerAltered,
            @Nullable TryLostReason lost,
            @Nullable DidNotFitReason misfit) {
        held(tree);
        changedOne(
                database.sql(MODEL_TRY_ENDED)
                        .param("system", SYSTEM)
                        .param("call", call.value())
                        .param("outcome", StoreLabels.label(outcome))
                        .param("altered", answerAltered)
                        .param("lost", lost == null ? null : StoreLabels.label(lost))
                        .param("misfit", misfit == null ? null : StoreLabels.label(misfit))
                        .param("try", aTry.value())
                        .param("root", tree.root().value())
                        .param("producer", StoreLabels.label(StepProducer.MODEL))
                        .update(),
                "the end of try " + aTry.value());
    }

    /** One value a model gave back, beside the key of its field, and how sure it was exactly where it was asked. */
    void modelValue(LockedTree tree, ProductionId aTry, UUID field, JsonValue value, @Nullable Confidence sure) {
        held(tree);
        changedOne(
                database.sql(QUESTION_VALUE)
                        .param("try", aTry.value())
                        .param("field", field)
                        .param("value", value instanceof JsonValue.JsonNull ? null : CanonicalJson.write(value))
                        .param("confidence", sure == null ? null : sure.percent())
                        .update(),
                "a value of try " + aTry.value());
    }

    /**
     * One time a model turned {@code call} away: one it is to be sent again after while the call is out, or the
     * one that ended it once it has ended turned away. Nothing said readable is none.
     */
    void turnaway(LockedTree tree, ModelCallId call, @Nullable ForeignProse said, boolean spentUp, boolean ending) {
        held(tree);
        if (spentUp && !ending) {
            throw new IllegalArgumentException("A call turned away as spent up is never sent again");
        }
        int recorded = database.sql(TURNAWAY)
                .param("said", said == null ? null : said.text())
                .param("cut", said != null && said.truncated())
                .param("spentUp", spentUp)
                .param("spentOutcome", spentUp ? TURNED_AWAY : null)
                .param("system", SYSTEM)
                .param("call", call.value())
                .param("root", tree.root().value())
                .param("callOutcome", ending ? TURNED_AWAY : null)
                .update();
        if (recorded == 0 && !ending && !standing(tree, call).unended()) {
            return;
        }
        changedOne(recorded, "a turnaway of call " + call.value());
    }

    /** Whether the call has ended or its tree is stopped, so a late resend cannot go ahead. */
    boolean stoppedWhileOut(LockedTree tree, ModelCallId call) {
        held(tree);
        CallStanding standing = standing(tree, call);
        return !standing.unended() || standing.stopped();
    }

    private CallStanding standing(LockedTree tree, ModelCallId call) {
        return database.sql(CALL_STANDING)
                .param("call", call.value())
                .param("root", tree.root().value())
                .query((result, number) -> new CallStanding(result.getBoolean("unended"), result.getBoolean("stopped")))
                .optional()
                .orElseThrow(() ->
                        new IllegalStateException("Tree " + tree.root().value() + " holds no call " + call.value()));
    }

    void endWaitingCalls(LockedTree tree) {
        held(tree);
        List<RunStepSendAttemptId> ended = database.sql(END_WAITING_CALLS)
                .param("root", tree.root().value())
                .query((result, number) ->
                        new RunStepSendAttemptId(result.getObject("run_step_send_attempt_id", UUID.class)))
                .list();
        for (RunStepSendAttemptId attempt : ended) {
            heldOn(tree, attempt, RunStepHoldReason.TURNED_AWAY);
        }
    }

    /** {@code call} sent again after its newest turnaway, on record before it is. */
    void resent(LockedTree tree, ModelCallId call) {
        held(tree);
        changedOne(
                database.sql(RESENT).param("call", call.value()).update(),
                "the newest turnaway of call " + call.value());
    }

    /** The tree stopped once by the system for every ceiling {@code reached} names, the first being the topmost. */
    void stoppedAtCeilings(LockedTree tree, List<RunId> reached) {
        held(tree);
        if (reached.isEmpty() || Set.copyOf(reached).size() != reached.size()) {
            throw new IllegalArgumentException("A stop is made for one ceiling at least, each once: " + reached);
        }
        UUID stop = database.sql(STOPPED_AT_CEILING)
                .param("root", tree.root().value())
                .param("ceiling", reached.getFirst().value())
                .param("system", SYSTEM)
                .query(UUID.class)
                .single();
        List<RunId> further = reached.subList(1, reached.size());
        if (!further.isEmpty()) {
            // An array binds as one parameter; a collection would be spread into a placeholder per element.
            database.sql(ALSO_REACHED)
                    .param("stop", stop)
                    .param("root", tree.root().value())
                    .param("ceiling", reached.getFirst().value())
                    .param(
                            "further",
                            further.stream().map(run -> run.value().toString()).toArray(String[]::new))
                    .update();
        }
        endWaitingCalls(tree);
    }

    /**
     * A person's answer to an open try of a question: the try ended by them with why they said it, then each
     * value, beside the key of the field it is the value of.
     */
    void answered(
            LockedTree tree,
            RunSnapshot run,
            ProductionId aTry,
            UserId caller,
            String why,
            List<AnsweredRecord> values) {
        held(tree, run);
        changedOne(
                database.sql(ANSWERED)
                        .param("try", aTry.value())
                        .param("caller", caller.value())
                        .param("why", why)
                        .update(),
                "the end of try " + aTry.value());
        for (AnsweredRecord value : values) {
            changedOne(
                    database.sql(QUESTION_VALUE)
                            .param("try", aTry.value())
                            .param("field", value.field())
                            .param(
                                    "value",
                                    value.value() instanceof JsonValue.JsonNull
                                            ? null
                                            : CanonicalJson.write(value.value()))
                            .param("confidence", null)
                            .update(),
                    "a value of try " + aTry.value());
        }
    }

    /**
     * A code try ended as its code came to, by the system: every value it gave back, or gone wrong saying why, as
     * this system's reason or in what the code said, and keeping what it gave back. False where the try was ended
     * already, and then nothing is written.
     */
    boolean codeEnded(LockedTree tree, ProductionId aTry, CodeOutcome outcome) {
        held(tree);
        JdbcClient.StatementSpec ending =
                database.sql(CODE_ENDED).param("system", SYSTEM).param("try", aTry.value());
        List<CodeOutcome.Given> values =
                switch (outcome) {
                    case CodeOutcome.Gave gave -> {
                        why(ending.param("lost", null).param("returned", null), null, null);
                        yield gave.values();
                    }
                    case CodeOutcome.Errored errored -> {
                        ending.param("lost", StoreLabels.label(TryLostReason.ERRORED))
                                .param("returned", errored.returned());
                        switch (errored.wentWrong()) {
                            case CodeError.Fault fault -> why(ending, fault, null);
                            case CodeError.Said said -> why(ending, null, said.detail());
                        }
                        yield List.of();
                    }
                };
        int changed = ending.param("root", tree.root().value()).update();
        if (changed > 1) {
            throw new IllegalStateException("Ended try " + aTry.value() + " as " + changed + " rows");
        }
        if (changed == 0) {
            return false;
        }
        codeValues(aTry, values);
        return true;
    }

    /** One verified file retained from a code try that ended errored. */
    record KeptCodeFile(FillPath path, UUID file) {
        KeptCodeFile {
            requireNonNull(path, "EngineWrites.KeptCodeFile path must not be null");
            requireNonNull(file, "EngineWrites.KeptCodeFile file must not be null");
        }
    }

    void codeFilesKept(LockedTree tree, RunId run, ProductionId aTry, List<KeptCodeFile> files) {
        held(tree);
        if (files.isEmpty()) {
            return;
        }
        String[] paths = files.stream().map(file -> filePath(file.path())).toArray(String[]::new);
        String[] ids = files.stream().map(file -> file.file().toString()).toArray(String[]::new);
        int inserted = database.sql("""
                        insert into errored_code_returned_files (production_id, run_id, file_id, field_path)
                        select :try, :run, item.file, item.path
                          from unnest(cast(:paths as text[]), cast(:files as uuid[])) as item(path, file)
                        """)
                .param("try", aTry.value())
                .param("run", run.value())
                .param("paths", paths)
                .param("files", ids)
                .update();
        if (inserted != files.size()) {
            throw new IllegalStateException("A code try kept a different number of files than it verified");
        }
    }

    private static String filePath(FillPath path) {
        StringBuilder named = new StringBuilder();
        for (FillPath.Step step : path.steps()) {
            switch (step) {
                case FillPath.Named field -> {
                    if (!named.isEmpty()) {
                        named.append('.');
                    }
                    named.append(field.name().value());
                }
                case FillPath.Place place ->
                    named.append('[').append(place.index()).append(']');
            }
        }
        if (named.length() > 2048) {
            throw new IllegalStateException("A code file path exceeds the stored path bound");
        }
        return named.toString();
    }

    /** Why code went wrong, as this system's reason or in what the code said, none of it where it did not. */
    private static void why(
            JdbcClient.StatementSpec ending, CodeError.@Nullable Fault fault, @Nullable ForeignProse said) {
        CodeError.ReadBy readBy = fault == null ? null : fault.readBy();
        CodeError.UndeclaredMember member = fault == null ? null : fault.member();
        ending.param("reason", fault == null ? null : StoreLabels.label(fault.reason()))
                .param("path", fault == null || fault.path().isEmpty() ? null : new Pointer(fault.path()).published())
                .param("member", member == null ? null : member.name())
                .param("memberCut", member != null && member.cut())
                .param("readByStep", readBy instanceof CodeError.StepReads reads ? reads.step() : null)
                .param(
                        "readByOutput",
                        readBy instanceof CodeError.OutputReads reads ? new Pointer(reads.field()).published() : null)
                .param("detail", said == null ? null : said.text())
                .param("cut", said != null && said.truncated());
    }

    /**
     * Every try of code under the tree still open, ended by the system as nothing came back, each a try spent; none
     * is changed where none is open. A try of a code step a person answers is a person's, and is left.
     */
    List<ProductionId> codeLost(LockedTree tree) {
        held(tree);
        return database
                .sql(CODE_LOST)
                .param("system", SYSTEM)
                .param("lost", StoreLabels.label(TryLostReason.NOTHING_CAME_BACK))
                .param("root", tree.root().value())
                .query(UUID.class)
                .list()
                .stream()
                .map(ProductionId::new)
                .toList();
    }

    /**
     * A person's answer to a try of a code step, as the running release declares what it gives back: the try ended
     * by them with why they said it, then each value, standing as the release says it stands.
     */
    void codeAnswered(
            LockedTree tree,
            RunSnapshot run,
            ProductionId aTry,
            UserId caller,
            String why,
            List<CodeOutcome.Given> values) {
        held(tree, run);
        changedOne(
                database.sql(ANSWERED)
                        .param("try", aTry.value())
                        .param("caller", caller.value())
                        .param("why", why)
                        .update(),
                "the end of try " + aTry.value());
        codeValues(aTry, values);
    }

    void searchAnswered(
            LockedTree tree,
            RunSnapshot run,
            ProductionId aTry,
            UserId caller,
            String why,
            Map<FieldName, FieldStanding> standings,
            JsonValue.JsonObject values) {
        held(tree, run);
        changedOne(
                database.sql(ANSWERED)
                        .param("try", aTry.value())
                        .param("caller", caller.value())
                        .param("why", why)
                        .update(),
                "the end of try " + aTry.value());
        for (JsonValue.JsonMember value : values.members()) {
            FieldName field = new FieldName(value.name());
            searchValue(tree, aTry, field, requireNonNull(standings.get(field)), value.value());
        }
    }

    private void codeValues(ProductionId aTry, List<CodeOutcome.Given> values) {
        for (CodeOutcome.Given given : values) {
            changedOne(
                    database.sql(CODE_VALUE)
                            .param("try", aTry.value())
                            .param("field", given.name().value())
                            .param("standing", StoreLabels.label(given.standing()))
                            .param("floor", given.floor())
                            .param(
                                    "value",
                                    given.value() instanceof JsonValue.JsonNull
                                            ? null
                                            : CanonicalJson.write(given.value()))
                            .update(),
                    "a value of try " + aTry.value());
        }
    }

    /** One review deciding every value it names, as the caller's act. */
    void reviewed(LockedTree tree, RunSnapshot run, ProductionId aTry, UserId caller, List<DecidedRecord> decisions) {
        held(tree, run);
        UUID review = database.sql(REVIEW)
                .param("try", aTry.value())
                .param("caller", caller.value())
                .query(UUID.class)
                .optional()
                .orElseThrow(
                        () -> new IllegalStateException("Try " + aTry.value() + " was ended outside its tree's lock"));
        decided(review, aTry, decisions);
    }

    /**
     * The review of {@code aTry} by the model its step names, as {@code call} to it ended: deciding every value
     * {@code decisions} names, or lost where {@code lost} is one, and why it did not fit exactly where it did not.
     */
    void reviewedByModel(
            LockedTree tree,
            ProductionId aTry,
            ModelCallId call,
            ModelCallOutcome outcome,
            boolean answerAltered,
            @Nullable TryLostReason lost,
            @Nullable DidNotFitReason misfit,
            List<DecidedRecord> decisions) {
        held(tree);
        if ((lost == null) == decisions.isEmpty()) {
            throw new IllegalArgumentException("A model's review decides every value it names, or is lost");
        }
        UUID review = database.sql(REVIEWED_BY_MODEL)
                .param("call", call.value())
                .param("outcome", StoreLabels.label(outcome))
                .param("altered", answerAltered)
                .param("lost", lost == null ? null : StoreLabels.label(lost))
                .param("misfit", misfit == null ? null : StoreLabels.label(misfit))
                .param("system", SYSTEM)
                .param("try", aTry.value())
                .param("root", tree.root().value())
                .query(UUID.class)
                .optional()
                .orElseThrow(
                        () -> new IllegalStateException("Try " + aTry.value() + " was ended outside its tree's lock"));
        decided(review, aTry, decisions);
    }

    private void decided(UUID review, ProductionId aTry, List<DecidedRecord> decisions) {
        for (DecidedRecord decided : decisions) {
            changedOne(
                    database.sql(DECISION)
                            .param("review", review)
                            .param("value", decided.value().value())
                            .param("try", aTry.value())
                            .param("outcome", StoreLabels.label(decided.outcome()))
                            .param("why", decided.why())
                            .update(),
                    "a decision on try " + aTry.value());
        }
    }

    List<String> fileOutputsAffected(LockedTree tree, RunId run, ProductionId aTry, List<String> fields) {
        held(tree);
        if (run.equals(tree.root()) || fields.isEmpty()) {
            return List.of();
        }
        return database.sql(AFFECTED_FILE_OUTPUTS)
                .param("run", run.value())
                .param("root", tree.root().value())
                .param("group", tree.group().value())
                .param("try", aTry.value())
                .param("fields", fields.toArray(String[]::new))
                .query((row, number) -> requireNonNull(row.getString("name")))
                .list();
    }

    private static void held(LockedTree tree, RunSnapshot run) {
        held(tree);
        if (!tree.root().equals(run.root()) || !tree.group().equals(run.group())) {
            throw new IllegalStateException("Run " + run.run().value() + " is not of the tree held");
        }
    }

    private static void held(LockedTree tree) {
        requireNonNull(tree, "EngineWrites tree must not be null");
        tree.requireHeld();
    }

    private static void changedOne(int changed, String what) {
        if (changed != 1) {
            throw new IllegalStateException(
                    "Wrote " + what + " as " + changed + " rows, not one: it was changed outside its tree's lock");
        }
    }

    private static boolean ended(int changed) {
        if (changed > 1) {
            throw new IllegalStateException("Ended " + changed + " calls as one");
        }
        return changed == 1;
    }

    /**
     * One value a person gave.
     *
     * @param field the stored key of the first-level field it is the value of
     * @param value none as {@link JsonValue.JsonNull}
     */
    record AnsweredRecord(UUID field, @DoNotLog JsonValue value) {

        AnsweredRecord {
            requireNonNull(field, "EngineWrites.AnsweredRecord field must not be null");
            requireNonNull(value, "EngineWrites.AnsweredRecord value must not be null");
        }
    }

    /** @param why the words it was refused with, exactly where it was refused */
    record DecidedRecord(
            ProductionValueId value,
            ReviewOutcome outcome,
            @DoNotLog @Nullable String why) {

        DecidedRecord {
            requireNonNull(value, "EngineWrites.DecidedRecord value must not be null");
            requireNonNull(outcome, "EngineWrites.DecidedRecord outcome must not be null");
        }
    }

    /**
     * What an attempt sent, as it was stored.
     *
     * @param attempt the attempt holding it, which an attempt sending it again names
     * @param envelopeVersion the version of what it was told besides, as the call that sent it was written
     */
    record SentRecord(
            RunStepSendAttemptId attempt,
            @DoNotLog String payload,
            int envelopeVersion,
            @DoNotLog @Nullable String searchQuestion) {

        SentRecord(RunStepSendAttemptId attempt, String payload, int envelopeVersion) {
            this(attempt, payload, envelopeVersion, null);
        }

        SentRecord {
            requireNonNull(attempt, "EngineWrites.SentRecord attempt must not be null");
            requireNonNull(payload, "EngineWrites.SentRecord payload must not be null");
        }
    }

    private record CallStanding(boolean unended, boolean stopped) {}
}
