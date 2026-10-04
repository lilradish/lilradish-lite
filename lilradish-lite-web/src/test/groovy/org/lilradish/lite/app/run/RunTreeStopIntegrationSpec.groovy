package org.lilradish.lite.app.run

import static org.lilradish.lite.app.run.fixture.TreeWorkflows.CAT
import static org.lilradish.lite.app.run.fixture.TreeWorkflows.GROUP
import static org.lilradish.lite.app.run.fixture.TreeWorkflows.ROOT
import static org.lilradish.lite.testutil.library.LibraryStore.entryId
import static org.lilradish.lite.testutil.library.LibraryStore.groupId

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.lilradish.lite.app.run.fixture.StepRows
import org.lilradish.lite.app.run.fixture.TreeWorkflows
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.testutil.codestep.CodeStepsHeld
import org.lilradish.lite.testutil.inference.ScriptedModelCalls
import org.lilradish.lite.testutil.library.LibraryStore
import org.lilradish.lite.testutil.run.RunRows
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class RunTreeStopIntegrationSpec extends Specification {

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    EngineWiring wiring

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, "run_tree_stop_" + (++databasesMade))
        TreeWorkflows.seed(store)
        wiring = new EngineWiring(store, new ScriptedModelCalls(), CodeStepsHeld.NONE)
    }

    def cleanup() {
        wiring.executor.destroy()
    }

    private void started(String entry, String version, String startedWith) {
        store.transactions().executeWithoutResult {
            TreeWorkflows.run(store, entry, version, startedWith)
            wiring.engine.planStarted(wiring.tree.lock(groupId(GROUP), root()).orElseThrow())
        }
    }

    private static RunId root() {
        new RunId(UUID.fromString(ROOT))
    }

    private void letGo(String entry) {
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
        store.session.sql("update entry_stops set let_go_at = now(), let_go_by = ?::uuid where entry_id = ?::uuid")
                .params(CAT, entry).update()
        wiring.engine.goesOn(entryId(entry))
        wiring.drained()
    }

    private List<String> triesOf(String step) {
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.texts("""
                select try.try_number || ' ' || case when try.ended_at is null then 'open' else 'ended' end
                  from productions try join run_steps held on held.run_step_id = try.run_step_id
                 where held.workflow_step_id = ?::uuid order by try.try_number
                """, step)
    }

    private List<String> holds() {
        store.texts("""
                select held.workflow_step_id || ' ' || hold.reason || ' ' || (hold.released_at is null)
                  from run_step_holds hold join run_steps held on held.run_step_id = hold.run_step_id
                 order by hold.created_at, hold.run_step_hold_id
                """)
    }

    private List<String> runsBeneath() {
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        store.texts("select entry_version_id::text from runs where parent_run_id = ?::uuid", ROOT)
    }

    def "a tree stopped at its top starts nothing beneath or above, and opened again goes on from where its runs are"() {
        given:
        started(TreeWorkflows.ESCALATE, TreeWorkflows.ESCALATE_VERSION, TreeWorkflows.NOTED_TICKET)
        RunRows.stopped(store, ROOT, CAT)
        StepRows.answered(store, TreeWorkflows.WRITE_NOTE, CAT, "Noted.", [(TreeWorkflows.NOTE_GIVES): '"A note."'])

        when:
        wiring.engine.goOn(groupId(GROUP), root())
        def whileStopped = triesOf(TreeWorkflows.CHECK)
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
        store.session.sql("""
                update run_stops set opened_again_at = greatest(now(), created_at), opened_again_by = ?::uuid
                 where run_id = ?::uuid
                """).params(CAT, ROOT).update()
        wiring.engine.goOn(groupId(GROUP), root())

        then:
        whileStopped == []
        triesOf(TreeWorkflows.CHECK) == ["1 open"]

        and:
        holds() == []
        runsBeneath() == [TreeWorkflows.NOTE_VERSION]
    }

    def "a workflow step whose workflow is stopped is held with nothing beneath, and starts it once let go"() {
        given:
        store.stopped(TreeWorkflows.NOTE, CAT)
        started(TreeWorkflows.ESCALATE, TreeWorkflows.ESCALATE_VERSION, TreeWorkflows.NOTED_TICKET)
        def whileStopped = runsBeneath()
        def heldWhileStopped = holds()

        when:
        letGo(TreeWorkflows.NOTE)

        then:
        whileStopped == []
        heldWhileStopped == ["${TreeWorkflows.NOTED} entry_stopped true" as String]
        runsBeneath() == [TreeWorkflows.NOTE_VERSION]
        holds() == ["${TreeWorkflows.NOTED} entry_stopped false" as String]
        triesOf(TreeWorkflows.WRITE_NOTE) == ["1 open"]
    }

    def "a route held on the branch its value chose is found once that branch is let go, and takes it"() {
        given:
        store.stopped(TreeWorkflows.GOING, CAT)
        started(TreeWorkflows.ROUTED, TreeWorkflows.ROUTED_VERSION, '{"category":"go"}')
        def whileStopped = runsBeneath()

        when:
        letGo(TreeWorkflows.GOING)

        then:
        whileStopped == []
        runsBeneath() == [TreeWorkflows.GOING_VERSION]
        holds() == ["${TreeWorkflows.ROUTE} entry_stopped false" as String]
        triesOf(TreeWorkflows.GOING_ASK) == ["1 open"]
    }

    def "a route is not held on a stop of a branch its value did not choose"() {
        given:
        store.stopped(TreeWorkflows.FALLING_BACK, CAT)

        when:
        started(TreeWorkflows.ROUTED, TreeWorkflows.ROUTED_VERSION, '{"category":"go"}')

        then:
        runsBeneath() == [TreeWorkflows.GOING_VERSION]

        and:
        holds() == []
    }

    def "a run's own workflow stopped while a run beneath is under way lets that run finish and holds the next step"() {
        given:
        started(TreeWorkflows.ESCALATE, TreeWorkflows.ESCALATE_VERSION, TreeWorkflows.NOTED_TICKET)
        store.stopped(TreeWorkflows.ESCALATE, CAT)

        when:
        StepRows.answered(store, TreeWorkflows.WRITE_NOTE, CAT, "Noted.", [(TreeWorkflows.NOTE_GIVES): '"A note."'])
        wiring.engine.goOn(groupId(GROUP), root())
        def heldWhileStopped = holds()
        def askedWhileStopped = triesOf(TreeWorkflows.CHECK)
        letGo(TreeWorkflows.ESCALATE)

        then:
        heldWhileStopped == ["${TreeWorkflows.CHECK} entry_stopped true" as String]
        askedWhileStopped == []
        triesOf(TreeWorkflows.CHECK) == ["1 open"]

        and:
        triesOf(TreeWorkflows.WRITE_NOTE) == ["1 ended"]
        holds() == ["${TreeWorkflows.CHECK} entry_stopped false" as String]
    }
}
