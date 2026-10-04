package org.lilradish.lite.testutil.run

import static org.lilradish.lite.testutil.library.LibraryStore.SEEDER

import org.lilradish.lite.testutil.library.LibraryStore

final class RunRows {

    static final String WORKFLOW_RUNNER = "00000000-0000-4000-8000-000000000001"

    static final String HELPER = "general"

    private RunRows() {}

    static void workflow(LibraryStore store, String entry, String version, String group, String name, Long ceiling,
                         boolean raiseNeedsApproval, boolean keepsOwnCeiling = false) {
        store.entry(entry, group, "workflow", name)
        store.seeded(version, entry, 1)
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into workflow_versions (entry_version_id, ceiling, raise_needs_approval, keeps_own_ceiling,
                                               may_be_helped, helper_model, helper_mode, created_by)
                values (?::uuid, cast(? as bigint), ?, ?, true, ?, 'ordinary', ?::uuid)
                """).params(version, ceiling, raiseNeedsApproval, keepsOwnCeiling, HELPER, SEEDER).update()
    }

    static void run(LibraryStore store, String run, String group, int number, String name, String entry,
                    String version, String starter) {
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into runs (run_id, group_id, number, name, entry_id, entry_version_id, root_run_id, depth,
                                  started_with, created_by)
                values (?::uuid, ?::uuid, ?, ?, ?::uuid, ?::uuid, ?::uuid, 0, '{}', ?::uuid)
                """).params(run, group, number, name, entry, version, run, starter).update()
    }

    static void beneath(LibraryStore store, String run, String above, String group, int number, String entry,
                        String version, String step, String runStep, int position = 1) {
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        def aboveVersion = store.texts("select entry_version_id::text from runs where run_id = ?::uuid", above)[0]
        store.step(step, aboveVersion, position, version, "workflow")
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into run_steps (run_step_id, run_id, entry_version_id, workflow_step_id, step_kind,
                                       pinned_version_id, pinned_kind, reviewed_by_model, created_by)
                values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'entry', ?::uuid, 'workflow', false, ?::uuid)
                """).params(runStep, above, aboveVersion, step, version, WORKFLOW_RUNNER).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
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
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("insert into run_stops (run_id, root_run_id, created_by) values (?::uuid, ?::uuid, ?::uuid)")
                .params(run, run, by).update()
    }

    static void stoppedByCeiling(LibraryStore store, String run, String reached, List<String> further = []) {
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, RETURNING.
        def stop = store.session.sql("""
                insert into run_stops (run_id, root_run_id, ceiling_run_id, created_by, created_by_kind)
                values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'system')
                returning run_stop_id::text
                """).params(run, run, reached, WORKFLOW_RUNNER).query(String).single()
        further.each {
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("""
                    insert into run_stop_additional_ceilings (run_stop_id, root_run_id, stop_ceiling_run_id, ceiling_run_id)
                    values (?::uuid, ?::uuid, ?::uuid, ?::uuid)
                    """).params(stop, run, reached, it).update()
        }
    }

    static List<String> stops(LibraryStore store) {
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("""
                select stop.created_by_kind || ' ' || coalesce(stop.ceiling_run_id::text, '-') || ' '
                       || (select count(*) from run_stop_additional_ceilings further
                            where further.run_stop_id = stop.run_stop_id)
                  from run_stops stop order by stop.created_at, stop.run_stop_id
                """)
    }

    // Order ceiling changes by position, not by their timestamps.
    static void ceilingChanged(LibraryStore store, String change, String run, Long from, Long to, boolean awaits,
                               String by, int minutesAgo) {
        // DB-SPECIFIC: PostgreSQL now(), make_interval().
        store.session.sql("""
                insert into run_ceiling_changes (run_ceiling_change_id, run_id, position, from_ceiling, to_ceiling,
                                                 awaits_approval, created_at, created_by)
                select cast(? as uuid), cast(? as uuid), coalesce(max(earlier.position), 0) + 1, cast(? as bigint),
                       cast(? as bigint), cast(? as boolean), now() - make_interval(mins => cast(? as integer)),
                       cast(? as uuid)
                  from run_ceiling_changes earlier where earlier.run_id = cast(? as uuid)
                """).params(change, run, from, to, awaits, minutesAgo, by, run).update()
    }

    static void called(LibraryStore store, String run, String outcome, long sent, Long cameBack) {
        // DB-SPECIFIC: PostgreSQL now(), enum type model_call_outcome.
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
