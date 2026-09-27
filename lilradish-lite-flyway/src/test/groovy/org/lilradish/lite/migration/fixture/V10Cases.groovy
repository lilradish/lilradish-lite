package org.lilradish.lite.migration.fixture

import static org.lilradish.lite.migration.fixture.SchemaKeys.*
import static org.lilradish.lite.migration.fixture.SchemaRows.*

/** V10__run.sql's rules: the rows they are proved beside, and a case attacking each. */
final class V10Cases {

    static final List<String> FIXTURE = [
        "insert into app.workflow_steps (workflow_step_id, entry_version_id, position, name, kind, code_step, producer," +
                " tries, created_by) values ('${SUB_STEP}', '${NEXT_WORKFLOW_VERSION}', 1, 'reply', 'code_step'," +
                " '${CODE_STEP}', 'code', 3, '${STEWARD}')",
        "insert into app.workflow_steps (workflow_step_id, entry_version_id, position, name, kind, pinned_version_id," +
                " pinned_kind, created_by) values ('${ESCALATE}', '${WORKFLOW_VERSION}', 5, 'escalate', 'entry'," +
                " '${NEXT_WORKFLOW_VERSION}', 'workflow', '${STEWARD}')",
        "insert into app.entries (entry_id, group_id, kind, name, created_by)" +
                " values ('${OTHER_WORKFLOW_ENTRY}', '${OTHER_GROUP}', 'workflow', 'Handle a refund', '${SEEDER}')",
        "insert into app.entry_versions (entry_version_id, entry_id, entry_kind, number, created_by, approved_at," +
                " approved_by, approved_by_kind) values ('${OTHER_WORKFLOW_VERSION}', '${OTHER_WORKFLOW_ENTRY}', 'workflow', 1," +
                " '${SEEDER}', now(), '${SEEDER}', 'seeder')",
        "insert into app.entry_versions (entry_version_id, entry_id, entry_kind, number, created_by)" +
                " values ('${OTHER_WORKFLOW_DRAFT}', '${OTHER_WORKFLOW_ENTRY}', 'workflow', 2, '${MEMBER}')",
        "insert into app.workflow_versions (entry_version_id, created_by)" +
                " values ('${OTHER_WORKFLOW_VERSION}', '${SEEDER}'), ('${OTHER_WORKFLOW_DRAFT}', '${MEMBER}')",
        "insert into app.route_cases (route_case_id, entry_version_id, workflow_step_id, term, target_version_id," +
                " created_by) values ('${OTHER_CASE}', '${WORKFLOW_VERSION}', '${ROUTE}', 'Refund'," +
                " '${OTHER_WORKFLOW_VERSION}', '${STEWARD}')",
        "insert into app.runs (run_id, group_id, number, name, entry_id, entry_version_id, root_run_id, depth," +
                " started_with, created_by) values" +
                " ('${RUN}', '${GROUP}', 1, 'Complaint from Ada', '${WORKFLOW_ENTRY}', '${WORKFLOW_VERSION}', '${RUN}', 0," +
                " '{\"complaint\": \"It never came.\"}', '${STEWARD}')," +
                " ('${OTHER_RUN}', '${GROUP}', 2, 'Complaint from Grace', '${WORKFLOW_ENTRY}', '${WORKFLOW_VERSION}'," +
                " '${OTHER_RUN}', 0, '{}', '${MEMBER}')",
        "insert into app.run_steps (run_step_id, run_id, entry_version_id, workflow_step_id, step_kind, pinned_version_id," +
                " pinned_kind, producer, reviewed_by_model, tries, created_by) values" +
                " ('${RUN_STEP}', '${RUN}', '${WORKFLOW_VERSION}', '${STEP}', 'entry', '${VERSION}', 'question', 'model'," +
                " false, 3, '${WORKFLOW_RUNNER}')," +
                " ('${ROUTE_RUN_STEP}', '${RUN}', '${WORKFLOW_VERSION}', '${ROUTE}', 'route', null, null, null, false," +
                " null, '${WORKFLOW_RUNNER}')," +
                " ('${ESCALATE_RUN_STEP}', '${RUN}', '${WORKFLOW_VERSION}', '${ESCALATE}', 'entry', '${NEXT_WORKFLOW_VERSION}'," +
                " 'workflow', null, false, null, '${WORKFLOW_RUNNER}')",
        "insert into app.runs (run_id, group_id, number, entry_id, entry_version_id, parent_run_id, parent_run_step_id," +
                " parent_run_step_kind, parent_workflow_step_id, parent_route_case_id, root_run_id, depth, parent_depth," +
                " created_by, created_by_kind) values ('${SUB_RUN}', '${GROUP}', 3, '${WORKFLOW_ENTRY}'," +
                " '${NEXT_WORKFLOW_VERSION}', '${RUN}', '${ROUTE_RUN_STEP}', 'route', '${ROUTE}', '${ROUTE_CASE}', '${RUN}'," +
                " 1, 0, '${WORKFLOW_RUNNER}', 'system')",
        "insert into app.run_stops (run_stop_id, run_id, root_run_id, created_by)" +
                " values ('${RUN_STOP}', '${RUN}', '${RUN}', '${STEWARD}')",
        "insert into app.run_ceiling_changes (run_ceiling_change_id, run_id, position, from_ceiling, to_ceiling," +
                " awaits_approval, created_by) values ('${CHANGE}', '${RUN}', 1, 1000, 2000, true, '${STEWARD}')",
        "insert into app.run_step_failures (run_step_failure_id, run_step_id, run_id, run_step_kind, calls_a_model," +
                " reason, detail, created_by) values ('${FAILURE}', '${ROUTE_RUN_STEP}', '${RUN}', 'route', false," +
                " 'unclaimed_value', 'Refunds', '${WORKFLOW_RUNNER}')",
    ]

    /** A row every rule admits beside the fixture, which a case or a feature alters in what it is about. */
    static final Map<String, String> A_RUN = [run_id: "'${SPARE_RUN}'", group_id: "'${GROUP}'", number: "4",
                                              name: "'Complaint from Alan'", entry_id: "'${WORKFLOW_ENTRY}'",
                                              entry_version_id: "'${WORKFLOW_VERSION}'", root_run_id: "'${SPARE_RUN}'",
                                              depth: "0", started_with: "'{}'", created_by: "'${STEWARD}'"]
    /** Started by the step of RUN that runs NEXT_WORKFLOW_VERSION, so it runs that version. */
    static final Map<String, String> A_SUB_RUN = [run_id: "'${SPARE_RUN}'", group_id: "'${GROUP}'", number: "4",
                                                  entry_id: "'${WORKFLOW_ENTRY}'",
                                                  entry_version_id: "'${NEXT_WORKFLOW_VERSION}'", parent_run_id: "'${RUN}'",
                                                  parent_run_step_id: "'${ESCALATE_RUN_STEP}'",
                                                  parent_run_step_kind: "'workflow'",
                                                  parent_workflow_step_id: "'${ESCALATE}'", root_run_id: "'${RUN}'",
                                                  depth: "1", parent_depth: "0", created_by: "'${WORKFLOW_RUNNER}'",
                                                  created_by_kind: "'system'"]
    static final Map<String, String> A_RUN_STEP = [run_id: "'${SUB_RUN}'", entry_version_id: "'${NEXT_WORKFLOW_VERSION}'",
                                                   workflow_step_id: "'${SUB_STEP}'", step_kind: "'code_step'",
                                                   producer: "'code'", reviewed_by_model: "false", tries: "3",
                                                   created_by: "'${WORKFLOW_RUNNER}'"]
    /** A_RUN_STEP under a key of its own, run ahead of what a feature then records against it. */
    static final String RUN_THE_CODE_STEP = insertInto("run_steps", A_RUN_STEP + [run_step_id: "'${SPARE_RUN_STEP}'"]) + "; "
    /** Opened again already, so it stands beside the stop in force. */
    static final Map<String, String> A_RUN_STOP = [run_id: "'${RUN}'", root_run_id: "'${RUN}'", created_by: "'${STEWARD}'",
                                                   opened_again_at: "now()", opened_again_by: "'${STEWARD}'"]
    /** A lowering, which holds at once and so awaits nothing, made after CHANGE. */
    static final Map<String, String> A_CHANGE = [run_id: "'${RUN}'", position: "2", from_ceiling: "1000",
                                                 to_ceiling: "500", awaits_approval: "false",
                                                 created_by: "'${STEWARD}'"]
    /** The step running a workflow, reaching an entry somebody stopped. */
    static final Map<String, String> A_HOLD = [run_step_id: "'${ESCALATE_RUN_STEP}'", run_id: "'${RUN}'",
                                               run_step_kind: "'workflow'", calls_a_model: "false",
                                               reason: "'entry_stopped'", created_by: "'${WORKFLOW_RUNNER}'"]
    static final Map<String, String> A_SEND_ATTEMPT = [run_step_id: "'${RUN_STEP}'", run_id: "'${RUN}'",
                                                       run_step_kind: "'question'", workflow_step_id: "'${STEP}'",
                                                       purpose: "'produce'", model: "'general'", mode: "'ordinary'",
                                                       production_id: "'${PRODUCTION}'", payload: "'{}'",
                                                       created_by: "'${STEWARD}'"]
    /** RUN_STEP failing on PRODUCTION, the model it names to produce not being held. */
    static final Map<String, String> A_FAILURE = [run_step_id: "'${RUN_STEP}'", run_id: "'${RUN}'",
                                                  run_step_kind: "'question'", calls_a_model: "true",
                                                  reason: "'model_not_deployed'", production_id: "'${PRODUCTION}'",
                                                  purpose: "'produce'",
                                                  detail: "'general'", created_by: "'${WORKFLOW_RUNNER}'"]

    /** What is recorded of a step once it has been reached, each by the row a feature adds to it. */
    static final Map<String, Map<String, String>> WHAT_HAPPENS_TO_A_STEP = [
        "run_step_holds"        : A_HOLD,
        "run_step_send_attempts": A_SEND_ATTEMPT,
        "run_step_failures"     : A_FAILURE,
    ]

    /** A kind other than the one each of those rows' step runs, with whatever else that row's own rules ask of it. */
    static final Map<String, Map<String, String>> CLAIMING_ANOTHER_KIND = [
        "run_step_holds"        : [run_step_kind: "'question'"],
        "run_step_send_attempts": [run_step_kind: "'code_step'", purpose: "'review'", production_id: "'${PRODUCTION}'"],
        "run_step_failures"     : [run_step_kind: "'code_step'"],
    ]

    /** SPARE_RUN and SPARE_PARENT_RUN, beneath RUN, written in one statement as each other's parent. */
    static final String TWO_RUNS_EACH_THE_OTHERS_PARENT = "with beneath as (" + insertInto("runs", A_SUB_RUN + [
            parent_run_id: "'${SPARE_PARENT_RUN}'", depth: "1", parent_depth: "0"]) + ") " + insertInto("runs", A_SUB_RUN + [
            run_id: "'${SPARE_PARENT_RUN}'", number: "7", parent_run_id: "'${SPARE_RUN}'", depth: "3", parent_depth: "2"])

    static final String OPEN_THE_RUN_AGAIN = "update app.run_stops set opened_again_at = now()," +
            " opened_again_by = '${STEWARD}' where run_stop_id = '${RUN_STOP}'; "
    static final String RELEASE_THE_HOLD = "update app.run_step_holds set released_at = now(), released_by = '${WORKFLOW_RUNNER}'" +
            " where run_step_hold_id = '${HOLD}'; "

    static final List<Map<String, String>> CASES = [
        attack("runs_pk", "runs", insertInto("runs", A_RUN + [run_id: "'${RUN}'", root_run_id: "'${RUN}'"])),
        attack("runs_group_fk", "runs", insertInto("runs", A_RUN + [group_id: "'${ABSENT}'"])),
        attack("runs_number_unique", "runs", insertInto("runs", A_RUN + [number: "1"])),
        attack("runs_number_positive", "runs", insertInto("runs", A_RUN + [number: "0"])),
        attack("runs_version_fk", "runs", insertInto("runs",
                A_RUN + [entry_id: "'${ENTRY}'", entry_version_id: "'${VERSION}'"])),
        attack("runs_version_entry_fk", "runs", insertInto("runs", A_RUN + [entry_id: "'${ENTRY}'"])),
        attack("runs_version_approved_fk", "runs", insertInto("runs", A_RUN + [group_id: "'${OTHER_GROUP}'",
                entry_id: "'${OTHER_WORKFLOW_ENTRY}'", entry_version_id: "'${OTHER_WORKFLOW_DRAFT}'"])),
        attack("runs_version_approved", "runs", insertInto("runs", A_RUN + [version_approved: "false"])),
        attack("runs_top_level_entry_fk", "runs", insertInto("runs",
                A_RUN + [entry_id: "'${OTHER_WORKFLOW_ENTRY}'", entry_version_id: "'${OTHER_WORKFLOW_VERSION}'"])),
        attack("runs_root_fk", "runs", insertInto("runs", A_SUB_RUN + [group_id: "'${OTHER_GROUP}'"])),
        attack("runs_parent_together", "runs", insertInto("runs", A_SUB_RUN + [parent_run_step_kind: "null"])),
        attack("runs_parent_step_unique", "runs", insertInto("runs",
                A_SUB_RUN + [parent_run_step_id: "'${ROUTE_RUN_STEP}'", parent_run_step_kind: "'route'",
                             parent_workflow_step_id: "'${ROUTE}'", parent_route_case_id: "'${ROUTE_CASE}'"])),
        attack("runs_parent_fk", "runs", insertInto("runs", A_SUB_RUN + [root_run_id: "'${OTHER_RUN}'"])),
        attack("runs_depth_zero_exactly_for_top_level", "runs", insertInto("runs", A_RUN + [depth: "1"])),
        attack("runs_depth_one_below_parent", "runs", insertInto("runs", A_SUB_RUN + [depth: "2"])),
        attack("runs_parent_step_fk", "runs", insertInto("runs", A_SUB_RUN + [parent_workflow_step_id: "'${STEP}'"])),
        attack("runs_parent_step_is_workflow_or_route", "runs", insertInto("runs",
                A_SUB_RUN + [parent_run_step_id: "'${RUN_STEP}'", parent_run_step_kind: "'question'",
                             parent_workflow_step_id: "'${STEP}'"])),
        attack("runs_parent_route_case_exactly_for_route", "runs", insertInto("runs",
                A_SUB_RUN + [parent_route_case_id: "'${ROUTE_CASE}'"])),
        attack("runs_parent_route_case_fk", "runs", "update app.runs set parent_route_case_id = '${OTHER_CASE}'" +
                " where run_id = '${SUB_RUN}'"),
        attack("runs_parent_pinned_version_fk", "runs", insertInto("runs",
                A_SUB_RUN + [entry_version_id: "'${WORKFLOW_VERSION}'"])),
        attack("runs_own_root_exactly_for_top_level", "runs", insertInto("runs", A_RUN + [root_run_id: "'${RUN}'"])),
        attack("runs_name_exactly_for_top_level", "runs", insertInto("runs", A_RUN + [name: "null"])),
        attack("runs_name_visible", "runs", insertInto("runs", A_RUN + [name: "''"])),
        attack("runs_name_bounded", "runs", insertInto("runs", A_RUN + [name: "repeat('a', 129)"])),
        attack("runs_started_with_exactly_for_top_level", "runs", insertInto("runs", A_RUN + [started_with: "null"])),
        attack("runs_started_with_is_an_object", "runs", insertInto("runs", A_RUN + [started_with: "'[]'"])),
        attack("runs_started_with_bounded", "runs", insertInto("runs",
                A_RUN + [started_with: "jsonb_build_object('complaint', repeat(chr(1), 8388608))"])),
        attack("runs_author_kind_fk", "runs", insertInto("runs", A_RUN + [created_by: "'${ABSENT}'"])),
        attack("runs_author_is_person_or_system", "runs", insertInto("runs",
                A_SUB_RUN + [created_by: "'${SEEDER}'", created_by_kind: "'seeder'"])),
        attack("runs_author_is_person_exactly_for_top_level", "runs", insertInto("runs",
                A_RUN + [created_by: "'${WORKFLOW_RUNNER}'", created_by_kind: "'system'"])),
        *editorAttacks("runs"),
        attack("runs_updated_only_for_top_level", "runs", "update app.runs set updated_at = now()," +
                " updated_by = '${STEWARD}' where run_id = '${SUB_RUN}'"),

        attack("run_steps_pk", "run_steps", insertInto("run_steps", A_RUN_STEP + [run_step_id: "'${RUN_STEP}'"])),
        attack("run_steps_run_fk", "run_steps", insertInto("run_steps", A_RUN_STEP + [run_id: "'${ABSENT}'"])),
        attack("run_steps_workflow_step_fk", "run_steps", insertInto("run_steps", A_RUN_STEP + [workflow_step_id: "'${STEP}'",
                step_kind: "'entry'", pinned_version_id: "'${VERSION}'", pinned_kind: "'question'", producer: "'model'"])),
        attack("run_steps_step_kind_fk", "run_steps", insertInto("run_steps",
                A_RUN_STEP + [step_kind: "'route'", producer: "null", tries: "null"])),
        attack("run_steps_pinned_fk", "run_steps", insertInto("run_steps", A_RUN_STEP + [run_id: "'${OTHER_RUN}'",
                entry_version_id: "'${WORKFLOW_VERSION}'", workflow_step_id: "'${STEP}'", step_kind: "'entry'",
                pinned_version_id: "'${NEXT_WORKFLOW_VERSION}'", pinned_kind: "'workflow'", producer: "null",
                tries: "null"])),
        attack("run_steps_pinned_together", "run_steps", insertInto("run_steps", A_RUN_STEP + [run_id: "'${OTHER_RUN}'",
                entry_version_id: "'${WORKFLOW_VERSION}'", workflow_step_id: "'${STEP}'", step_kind: "'entry'",
                pinned_version_id: "'${VERSION}'", producer: "null", tries: "null"])),
        attack("run_steps_pinned_exactly_for_entry", "run_steps", insertInto("run_steps",
                A_RUN_STEP + [pinned_version_id: "'${VERSION}'", pinned_kind: "'question'"])),
        attack("run_steps_producer_fk", "run_steps", insertInto("run_steps", A_RUN_STEP + [tries: "2"])),
        attack("run_steps_producer_exactly_for_question_or_code_step", "run_steps", insertInto("run_steps",
                A_RUN_STEP + [producer: "null"])),
        attack("run_steps_producer_together", "run_steps", insertInto("run_steps", A_RUN_STEP + [tries: "null"])),
        attack("run_steps_reviewed_by_model_only_for_a_producer", "run_steps", insertInto("run_steps",
                A_RUN_STEP + [run_id: "'${OTHER_RUN}'", entry_version_id: "'${WORKFLOW_VERSION}'",
                              workflow_step_id: "'${ROUTE}'", step_kind: "'route'", producer: "null", tries: "null",
                              reviewed_by_model: "true"])),
        attack("run_steps_one_per_workflow_step", "run_steps", insertInto("run_steps", A_RUN_STEP + [run_id: "'${RUN}'",
                entry_version_id: "'${WORKFLOW_VERSION}'", workflow_step_id: "'${STEP}'", step_kind: "'entry'",
                pinned_version_id: "'${VERSION}'", pinned_kind: "'question'", producer: "'model'"])),
        attack("run_steps_author_system_fk", "run_steps", insertInto("run_steps", A_RUN_STEP + [created_by: "'${STEWARD}'"])),
        attack("run_steps_author_is_system", "run_steps", insertInto("run_steps",
                A_RUN_STEP + [created_by: "'${STEWARD}'", created_by_kind: "'person'"])),

        attack("run_stops_pk", "run_stops", insertInto("run_stops", A_RUN_STOP + [run_stop_id: "'${RUN_STOP}'"])),
        attack("run_stops_run_fk", "run_stops", insertInto("run_stops",
                A_RUN_STOP + [run_id: "'${ABSENT}'", root_run_id: "'${ABSENT}'"])),
        attack("run_stops_run_is_root", "run_stops", insertInto("run_stops", A_RUN_STOP + [run_id: "'${SUB_RUN}'"])),
        attack("run_stops_ceiling_run_fk", "run_stops", insertInto("run_stops", A_RUN_STOP + [created_by: "'${WORKFLOW_RUNNER}'",
                created_by_kind: "'system'", ceiling_run_id: "'${ABSENT}'"])),
        attack("run_stops_author_kind_fk", "run_stops", insertInto("run_stops", A_RUN_STOP + [created_by: "'${ABSENT}'"])),
        attack("run_stops_author_is_person_or_system", "run_stops", insertInto("run_stops",
                A_RUN_STOP + [created_by: "'${SEEDER}'", created_by_kind: "'seeder'"])),
        attack("run_stops_ceiling_exactly_for_system", "run_stops", insertInto("run_stops",
                A_RUN_STOP + [created_by: "'${WORKFLOW_RUNNER}'", created_by_kind: "'system'"])),
        attack("run_stops_opened_again_person_fk", "run_stops",
                setting("run_stops", "opened_again_at = now(), opened_again_by = '${SEEDER}'")),
        attack("run_stops_opened_again_is_person", "run_stops", setting("run_stops",
                "opened_again_at = now(), opened_again_by = '${WORKFLOW_RUNNER}', opened_again_by_kind = 'system'")),
        attack("run_stops_opened_again_together", "run_stops", setting("run_stops", "opened_again_at = now()")),
        attack("run_stops_opened_again_after_created", "run_stops", setting("run_stops",
                "opened_again_at = created_at - interval '1 day', opened_again_by = '${STEWARD}'")),
        attack("run_stops_one_in_force", "run_stops", insertInto("run_stops",
                A_RUN_STOP + [opened_again_at: "null", opened_again_by: "null"])),

        attack("run_ceiling_changes_pk", "run_ceiling_changes", insertInto("run_ceiling_changes",
                A_CHANGE + [run_ceiling_change_id: "'${CHANGE}'"])),
        attack("run_ceiling_changes_run_fk", "run_ceiling_changes", insertInto("run_ceiling_changes",
                A_CHANGE + [run_id: "'${ABSENT}'"])),
        attack("run_ceiling_changes_position_positive", "run_ceiling_changes", insertInto("run_ceiling_changes",
                A_CHANGE + [position: "0"])),
        attack("run_ceiling_changes_position_unique", "run_ceiling_changes", insertInto("run_ceiling_changes",
                A_CHANGE + [position: "1"])),
        attack("run_ceiling_changes_from_ceiling_positive", "run_ceiling_changes", insertInto("run_ceiling_changes",
                A_CHANGE + [from_ceiling: "0"])),
        attack("run_ceiling_changes_to_ceiling_positive", "run_ceiling_changes", insertInto("run_ceiling_changes",
                A_CHANGE + [to_ceiling: "0"])),
        attack("run_ceiling_changes_from_differs_from_to", "run_ceiling_changes", insertInto("run_ceiling_changes",
                A_CHANGE + [to_ceiling: "1000"])),
        attack("run_ceiling_changes_awaits_approval_only_for_a_raise", "run_ceiling_changes",
                insertInto("run_ceiling_changes", A_CHANGE + [run_id: "'${OTHER_RUN}'", awaits_approval: "true"])),
        attack("run_ceiling_changes_author_person_fk", "run_ceiling_changes", insertInto("run_ceiling_changes",
                A_CHANGE + [created_by: "'${SEEDER}'"])),
        attack("run_ceiling_changes_author_is_person", "run_ceiling_changes", insertInto("run_ceiling_changes",
                A_CHANGE + [created_by: "'${WORKFLOW_RUNNER}'", created_by_kind: "'system'"])),
        attack("run_ceiling_changes_outcome_only_for_awaiting", "run_ceiling_changes", insertInto("run_ceiling_changes",
                A_CHANGE + [outcome: "'withdrawn'", decided_at: "now()", decided_by: "'${STEWARD}'"])),
        attack("run_ceiling_changes_decided_together", "run_ceiling_changes", insertInto("run_ceiling_changes",
                A_CHANGE + [decided_at: "now()"])),
        attack("run_ceiling_changes_decider_person_fk", "run_ceiling_changes", setting("run_ceiling_changes",
                "outcome = 'approved', decided_at = now(), decided_by = '${SEEDER}'")),
        attack("run_ceiling_changes_decider_is_person", "run_ceiling_changes", setting("run_ceiling_changes",
                "outcome = 'approved', decided_at = now(), decided_by = '${WORKFLOW_RUNNER}', decided_by_kind = 'system'")),
        attack("run_ceiling_changes_decided_after_created", "run_ceiling_changes", setting("run_ceiling_changes",
                "outcome = 'approved', decided_at = created_at - interval '1 day', decided_by = '${MEMBER}'")),
        attack("run_ceiling_changes_decider_is_not_requester", "run_ceiling_changes", setting("run_ceiling_changes",
                "outcome = 'approved', decided_at = now(), decided_by = '${STEWARD}'")),
        attack("run_ceiling_changes_one_awaiting", "run_ceiling_changes", insertInto("run_ceiling_changes",
                A_CHANGE + [to_ceiling: "3000", awaits_approval: "true"])),

        attack("run_step_holds_pk", "run_step_holds", insertInto("run_step_holds",
                A_HOLD + [run_step_hold_id: "'${HOLD}'"])),
        attack("run_step_holds_step_fk", "run_step_holds", insertInto("run_step_holds",
                A_HOLD + [run_step_id: "'${ABSENT}'"])),
        attack("run_step_holds_too_long_or_turned_away_only_for_calls_a_model", "run_step_holds",
                insertInto("run_step_holds", A_HOLD + [reason: "'too_long'", run_step_send_attempt_id: "'${SEND_ATTEMPT}'"])),
        attack("run_step_holds_attempt_exactly_for_too_long_or_turned_away", "run_step_holds",
                insertInto("run_step_holds", A_HOLD + [run_step_send_attempt_id: "'${SEND_ATTEMPT}'"])),
        attack("run_step_holds_code_step_not_held_only_for_code_step", "run_step_holds",
                insertInto("run_step_holds", A_HOLD + [reason: "'code_step_not_held'"])),
        attack("run_step_holds_attempt_is_produce", "run_step_holds", insertInto("run_step_holds",
                A_HOLD + [attempt_purpose: "'review'"])),
        attack("run_step_holds_attempt_fk", "run_step_holds", insertInto("run_step_holds",
                A_HOLD + [run_step_id: "'${REVIEWED_RUN_STEP}'", run_step_kind: "'question'", calls_a_model: "true",
                          reason: "'too_long'", run_step_send_attempt_id: "'${ABSENT}'"])),
        attack("run_step_holds_author_system_fk", "run_step_holds", insertInto("run_step_holds",
                A_HOLD + [created_by: "'${STEWARD}'"])),
        attack("run_step_holds_author_is_system", "run_step_holds", insertInto("run_step_holds",
                A_HOLD + [created_by: "'${STEWARD}'", created_by_kind: "'person'"])),
        attack("run_step_holds_releaser_system_fk", "run_step_holds",
                setting("run_step_holds", "released_at = now(), released_by = '${ABSENT}'")),
        attack("run_step_holds_releaser_is_system", "run_step_holds", setting("run_step_holds",
                "released_at = now(), released_by = '${STEWARD}', released_by_kind = 'person'")),
        attack("run_step_holds_released_together", "run_step_holds", setting("run_step_holds", "released_at = now()")),
        attack("run_step_holds_released_after_created", "run_step_holds", setting("run_step_holds",
                "released_at = created_at - interval '1 day', released_by = '${WORKFLOW_RUNNER}'")),
        attack("run_step_holds_one_unreleased", "run_step_holds", insertInto("run_step_holds",
                A_HOLD + [run_step_id: "'${RUN_STEP}'", run_step_kind: "'question'", calls_a_model: "true"])),

        attack("run_step_send_attempts_pk", "run_step_send_attempts", insertInto("run_step_send_attempts",
                A_SEND_ATTEMPT + [run_step_send_attempt_id: "'${SEND_ATTEMPT}'"])),
        attack("run_step_send_attempts_step_fk", "run_step_send_attempts", insertInto("run_step_send_attempts",
                A_SEND_ATTEMPT + [run_step_id: "'${ABSENT}'"])),
        attack("run_step_send_attempts_author_kind_fk", "run_step_send_attempts", insertInto("run_step_send_attempts",
                A_SEND_ATTEMPT + [created_by: "'${ABSENT}'"])),
        attack("run_step_send_attempts_author_is_person_or_system", "run_step_send_attempts",
                insertInto("run_step_send_attempts", A_SEND_ATTEMPT + [created_by: "'${SEEDER}'", created_by_kind: "'seeder'"])),
        attack("run_step_send_attempts_only_for_question_or_code_step", "run_step_send_attempts",
                insertInto("run_step_send_attempts", A_SEND_ATTEMPT + [run_step_id: "'${ROUTE_RUN_STEP}'",
                                                                        run_step_kind: "'route'"])),
        attack("run_step_send_attempts_purpose_is_produce_or_review", "run_step_send_attempts",
                insertInto("run_step_send_attempts", A_SEND_ATTEMPT + [purpose: "'help'"])),
        attack("run_step_send_attempts_producer_model_fk", "run_step_send_attempts",
                insertInto("run_step_send_attempts", A_SEND_ATTEMPT + [mode: "'research'"])),
        attack("run_step_send_attempts_reviewer_model_fk", "run_step_send_attempts", insertInto("run_step_send_attempts",
                A_SEND_ATTEMPT + [purpose: "'review'", production_id: "'${PRODUCTION}'"])),
        attack("run_step_send_attempts_mode_shape", "run_step_send_attempts",
                insertInto("run_step_send_attempts", A_SEND_ATTEMPT + [mode: "'Ordinary'"])),
        attack("run_step_send_attempts_produce_only_for_question", "run_step_send_attempts",
                insertInto("run_step_send_attempts", A_SEND_ATTEMPT + [run_step_kind: "'code_step'"])),
        attack("run_step_send_attempts_production_is_model", "run_step_send_attempts",
                insertInto("run_step_send_attempts", A_SEND_ATTEMPT + [production_producer: "'person'"])),
        attack("run_step_send_attempts_production_yielded", "run_step_send_attempts",
                insertInto("run_step_send_attempts", A_SEND_ATTEMPT + [production_yielded: "false"])),
        attack("run_step_send_attempts_payload_is_json", "run_step_send_attempts", insertInto("run_step_send_attempts",
                A_SEND_ATTEMPT + [payload: "'{' || chr(1) || '}'"])),
        attack("run_step_send_attempts_payload_bounded", "run_step_send_attempts", insertInto("run_step_send_attempts",
                A_SEND_ATTEMPT + [payload: "'\"' || repeat('a', 8388607) || '\"'"])),
        attack("run_step_send_attempts_repeats_fk", "run_step_send_attempts", insertInto("run_step_send_attempts",
                A_SEND_ATTEMPT + [payload: "null", repeats_attempt_id: "'${ABSENT}'"])),
        attack("run_step_send_attempts_repeats_exactly_for_no_payload", "run_step_send_attempts",
                insertInto("run_step_send_attempts", A_SEND_ATTEMPT + [payload: "null"])),
        attack("run_step_send_attempts_repeated_holds_payload", "run_step_send_attempts",
                insertInto("run_step_send_attempts", A_SEND_ATTEMPT + [repeated_holds_payload: "false"])),
        attack("run_step_send_attempts_unbuilt_only_for_review_not_sent", "run_step_send_attempts",
                insertInto("run_step_send_attempts", A_SEND_ATTEMPT + [payload: "null",
                        unbuilt_reason: "'list_not_here'"])),

        attack("run_step_failures_pk", "run_step_failures", insertInto("run_step_failures",
                A_FAILURE + [run_step_failure_id: "'${FAILURE}'"])),
        attack("run_step_failures_step_fk", "run_step_failures", insertInto("run_step_failures",
                A_FAILURE + [run_id: "'${OTHER_RUN}'"])),
        attack("run_step_failures_unclaimed_value_only_for_route", "run_step_failures", insertInto("run_step_failures",
                A_FAILURE + [reason: "'unclaimed_value'", production_id: "null", purpose: "null"])),
        attack("run_step_failures_production_exactly_for_length_or_undeployed", "run_step_failures",
                insertInto("run_step_failures", A_FAILURE + [production_id: "null", purpose: "null"])),
        attack("run_step_failures_purpose_together", "run_step_failures", insertInto("run_step_failures",
                A_FAILURE + [purpose: "null"])),
        attack("run_step_failures_purpose_is_produce_or_review", "run_step_failures", insertInto("run_step_failures",
                A_FAILURE + [purpose: "'help'"])),
        attack("run_step_failures_uncuttable_length_only_for_produce", "run_step_failures",
                insertInto("run_step_failures", A_FAILURE + [run_step_id: literal(REVIEWED_RUN_STEP),
                        reason: "'uncuttable_length'", production_id: literal(REVIEWED_ANSWER), purpose: "'review'"])),
        attack("run_step_failures_production_is_model", "run_step_failures", insertInto("run_step_failures",
                A_FAILURE + [production_producer: "'person'"])),
        attack("run_step_failures_production_reviewed", "run_step_failures", insertInto("run_step_failures",
                A_FAILURE + [production_reviewed: "false"])),
        attack("run_step_failures_production_yielded", "run_step_failures", insertInto("run_step_failures",
                A_FAILURE + [production_yielded: "false"])),
        attack("run_step_failures_length_or_undeployed_only_for_calls_a_model", "run_step_failures",
                insertInto("run_step_failures", A_FAILURE + [run_step_id: "'${ROUTE_RUN_STEP}'", run_step_kind: "'route'",
                                                              calls_a_model: "false"])),
        attack("run_step_failures_author_system_fk", "run_step_failures", insertInto("run_step_failures",
                A_FAILURE + [created_by: "'${STEWARD}'"])),
        attack("run_step_failures_author_is_system", "run_step_failures", insertInto("run_step_failures",
                A_FAILURE + [created_by: "'${STEWARD}'", created_by_kind: "'person'"])),
        attack("run_step_failures_detail_visible", "run_step_failures",
                setting("run_step_failures", "detail = 'Refunds' || chr(133)")),
        attack("run_step_failures_detail_bounded", "run_step_failures",
                setting("run_step_failures", "detail = repeat('a', 2049)")),
    ]

    private V10Cases() {}
}
