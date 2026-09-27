package org.lilradish.lite.testutil.run

import static org.lilradish.lite.testutil.library.LibraryStore.SEEDER

import org.lilradish.lite.testutil.library.LibraryStore

/**
 * A run's rows written straight into a store, as a run nobody here started would have left them: its workflow's
 * version in service, the run, its stops, its ceiling's changes and the calls it made.
 */
final class RunRows {

    /** Seeded by the baseline as the system actor that runs workflows. */
    static final String WORKFLOW_RUNNER = "00000000-0000-4000-8000-000000000001"

    /** The helper model the versions here may be helped by, which is what a call of theirs names. */
    static final String HELPER = "general"

    private RunRows() {}

    /**
     * A workflow's version in service, declaring its ceiling, whether a raise of it waits on approval, and whether
     * a run of it beneath another keeps that ceiling of its own.
     */
    static void workflow(LibraryStore store, String entry, String version, String group, String name, Long ceiling,
                         boolean raiseNeedsApproval, boolean keepsOwnCeiling = false) {
        store.entry(entry, group, "workflow", name)
        store.seeded(version, entry, 1)
        store.session.sql("""
                insert into workflow_versions (entry_version_id, ceiling, raise_needs_approval, keeps_own_ceiling,
                                               may_be_helped, helper_model, helper_mode, created_by)
                values (?::uuid, cast(? as bigint), ?, ?, true, ?, 'ordinary', ?::uuid)
                """).params(version, ceiling, raiseNeedsApproval, keepsOwnCeiling, HELPER, SEEDER).update()
    }

    /** A run at the top, started by the person given. */
    static void run(LibraryStore store, String run, String group, int number, String name, String entry,
                    String version, String starter) {
        store.session.sql("""
                insert into runs (run_id, group_id, number, name, entry_id, entry_version_id, root_run_id, depth,
                                  started_with, created_by)
                values (?::uuid, ?::uuid, ?, ?, ?::uuid, ?::uuid, ?::uuid, 0, '{}', ?::uuid)
                """).params(run, group, number, name, entry, version, run, starter).update()
    }

    /**
     * A run beneath {@code above}, started by the step of the run above that runs its version, which that
     * version then pins. The step is the one at {@code position} of the version above.
     */
    static void beneath(LibraryStore store, String run, String above, String group, int number, String entry,
                        String version, String step, String runStep) {
        def aboveVersion = store.texts("select entry_version_id::text from runs where run_id = ?::uuid", above)[0]
        store.step(step, aboveVersion, 1, version, "workflow")
        store.session.sql("""
                insert into run_steps (run_step_id, run_id, entry_version_id, workflow_step_id, step_kind,
                                       pinned_version_id, pinned_kind, reviewed_by_model, created_by)
                values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'entry', ?::uuid, 'workflow', false, ?::uuid)
                """).params(runStep, above, aboveVersion, step, version, WORKFLOW_RUNNER).update()
        store.session.sql("""
                insert into runs (run_id, group_id, number, entry_id, entry_version_id, parent_run_id,
                                  parent_run_step_id, parent_run_step_kind, parent_workflow_step_id, root_run_id,
                                  depth, parent_depth, created_by, created_by_kind)
                select ?::uuid, ?::uuid, ?, ?::uuid, ?::uuid, above.run_id, ?::uuid, 'workflow', ?::uuid,
                       above.root_run_id, above.depth + 1, above.depth, ?::uuid, 'system'
                  from runs above where above.run_id = ?::uuid
                """).params(run, group, number, entry, version, runStep, step, WORKFLOW_RUNNER, above).update()
    }

    static void stopped(LibraryStore store, String run, String by) {
        store.session.sql("insert into run_stops (run_id, root_run_id, created_by) values (?::uuid, ?::uuid, ?::uuid)")
                .params(run, run, by).update()
    }

    /** Stopped by the ceiling of {@code reached}, which names nobody. */
    static void stoppedByCeiling(LibraryStore store, String run, String reached) {
        store.session.sql("""
                insert into run_stops (run_id, root_run_id, ceiling_run_id, created_by, created_by_kind)
                values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'system')
                """).params(run, run, reached, WORKFLOW_RUNNER).update()
    }

    /**
     * The run's next change, made {@code minutesAgo}: in force, or a raise asked and waiting where {@code awaits}.
     * The time orders nothing; its place after every change of the run before it does.
     */
    static void ceilingChanged(LibraryStore store, String change, String run, Long from, Long to, boolean awaits,
                               String by, int minutesAgo) {
        store.session.sql("""
                insert into run_ceiling_changes (run_ceiling_change_id, run_id, position, from_ceiling, to_ceiling,
                                                 awaits_approval, created_at, created_by)
                select cast(? as uuid), cast(? as uuid), coalesce(max(earlier.position), 0) + 1, cast(? as bigint),
                       cast(? as bigint), cast(? as boolean), now() - make_interval(mins => cast(? as integer)),
                       cast(? as uuid)
                  from run_ceiling_changes earlier where earlier.run_id = cast(? as uuid)
                """).params(change, run, from, to, awaits, minutesAgo, by, run).update()
    }

    /**
     * A call to the helper of the run at the top, made for {@code run}: still out where {@code outcome} is
     * null, and carrying what came back only where it came back.
     */
    static void called(LibraryStore store, String run, String outcome, long sent, Long cameBack) {
        store.session.sql("""
                insert into model_calls (run_id, root_run_id, purpose, root_version_id, request, model, mode,
                                         envelope_version, sent_count, came_back_count, outcome, answer, error_detail,
                                         ended_at, created_by)
                select made.run_id, made.root_run_id, 'help', root.entry_version_id, '{}', cast(? as text),
                       'ordinary', 1, cast(? as bigint), cast(? as bigint), cast(cast(? as text) as model_call_outcome),
                       case when cast(? as text) = 'came_back' then 'Said.' end,
                       case when cast(? as text) = 'errored' then 'Went wrong.' end,
                       case when cast(? as text) is null then null else now() end,
                       cast(? as uuid)
                  from runs made join runs root on root.run_id = made.root_run_id
                 where made.run_id = cast(? as uuid)
                """).params(HELPER, sent, cameBack, outcome, outcome, outcome, outcome, WORKFLOW_RUNNER, run).update()
    }
}
