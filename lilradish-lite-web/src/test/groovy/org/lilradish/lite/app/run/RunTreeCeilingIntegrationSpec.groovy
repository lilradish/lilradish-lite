package org.lilradish.lite.app.run

import static org.lilradish.lite.app.run.fixture.TreeWorkflows.GROUP
import static org.lilradish.lite.app.run.fixture.TreeWorkflows.ROOT
import static org.lilradish.lite.testutil.library.LibraryStore.groupId

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.run.fixture.TreeWorkflows
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.inference.CallOutcome
import org.lilradish.lite.domain.inference.TurnAway
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.domain.run.WorkflowStepId
import org.lilradish.lite.testutil.inference.Pause
import org.lilradish.lite.testutil.inference.ScriptedModelCalls
import org.lilradish.lite.testutil.library.LibraryStore
import org.lilradish.lite.testutil.run.RunRows
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class RunTreeCeilingIntegrationSpec extends Specification {

    static final UserId CAT_USER = new UserId("003701")

    static final String SYSTEM = RunRows.WORKFLOW_RUNNER

    static final String ANSWER = '{"values":{"answer":"Yes."},"confidences":{}}'

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    ScriptedModelCalls model

    EngineWiring wiring

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, "run_tree_ceiling_" + (++databasesMade))
        TreeWorkflows.seed(store)
        model = new ScriptedModelCalls()
        wiring = TreeWorkflows.wired(store, 32, model)
    }

    def cleanup() {
        wiring.executor.destroy()
    }

    private void answeredTwice() {
        2.times { model.answering(cameBack()) }
    }

    private static CallOutcome cameBack() {
        new CallOutcome.CameBack(ANSWER, 321, 12, true, false, null, null)
    }

    private void starting(long top, long beneath) {
        TreeWorkflows.spending(store, top, beneath)
        store.transactions().executeWithoutResult {
            TreeWorkflows.run(store, TreeWorkflows.SPENDING, TreeWorkflows.SPENDING_VERSION, '{}')
            wiring.engine.planStarted(wiring.tree.lock(groupId(GROUP), runId(ROOT)).orElseThrow())
        }
    }

    private void started(long top, long beneath) {
        starting(top, beneath)
        wiring.drained()
    }

    private int sends() {
        model.events.count { it == ScriptedModelCalls.SENT }
    }

    private static RunId runId(String run) {
        new RunId(UUID.fromString(run))
    }

    private String runOf(String which) {
        which == "top" ? ROOT : beneath()
    }

    private String beneath() {
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        store.texts("select run_id::text from runs where parent_run_id = ?::uuid", ROOT)[0]
    }

    private List<String> stops() {
        // DB-SPECIFIC: PostgreSQL ::text casts, string_agg().
        store.texts("""
                select stop.created_by_kind || ' ' || stop.created_by || ' ' || coalesce(stop.ceiling_run_id::text, '-')
                       || ' [' || coalesce((select string_agg(further.ceiling_run_id::text, ' ' order by run.depth, run.number)
                                              from run_stop_additional_ceilings further
                                              join runs run on run.run_id = further.ceiling_run_id
                                             where further.run_stop_id = stop.run_stop_id), '') || ']'
                  from run_stops stop order by stop.created_at, stop.run_stop_id
                """)
    }

    private int calls() {
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count("select count(*) from model_calls where root_run_id = ?::uuid", ROOT)
    }

    private Runs.RunView read(String run) {
        new Runs(store.session, new GroupRoles(store.session), store.transactionManager(), wiring.snapshots)
                .run(groupId(GROUP), runId(run), CAT_USER)
    }

    def "a second call from beneath stops the top once for every ceiling it would cross, the top's first, and is never sent: #top, #own"() {
        given:
        answeredTwice()

        when:
        started(top, own)

        then:
        calls() == (named.isEmpty() ? 2 : 1)
        sends() == calls()
        stops() == (named.isEmpty()
                ? []
                : ["system ${SYSTEM} ${runOf(named[0])} [${named.drop(1).collect { runOf(it) }.join(' ')}]" as String])

        where:
        top  | own  || named
        333L | 333L || ["top", "beneath"]
        334L | 333L || ["beneath"]
        333L | 334L || ["top"]
        334L | 334L || []
    }

    def "a tree its ceilings stopped reads every one, top first, on the page at the top and the page beneath"() {
        given:
        answeredTwice()
        started(333L, 333L)
        def expected = new Runs.Stopper.ByCeiling([new Runs.NumberedRun(runId(ROOT), 1),
                                                   new Runs.NumberedRun(runId(beneath()), 2)])

        expect:
        read(ROOT).stop().by() == expected
        read(beneath()).stop().by() == expected
    }

    def "opened again with neither ceiling changed, the tree is stopped again before anything is sent, naming both once more"() {
        given:
        answeredTwice()
        started(333L, 333L)
        def first = stops()
        def changes = new RunChanges(store.session, store.transactions(), new GroupRoles(store.session), wiring.tree,
                wiring.snapshots, wiring.engine)

        when:
        changes.openAgain(groupId(GROUP), runId(ROOT), CAT_USER)
        wiring.drained()

        then:
        def now = stops()
        now.size() == 2
        now[1] == first[0]
        calls() == 1
        store.count("select count(*) from run_stops where opened_again_at is null") == 1
        store.count("select count(*) from run_stop_additional_ceilings") == 2
    }

    def "Try sending pressed beneath is judged against the ceiling that run keeps: #own"() {
        given:
        model.answering(cameBack())
        model.answering(new CallOutcome.TurnedAway(new TurnAway("Busy.", false)))
        model.answering(cameBack())
        started(1_000_000L, own)
        RunRows.called(store, beneath(), "came_back", 1, 0)

        when:
        wiring.acts.trySending(groupId(GROUP), runId(beneath()),
                new WorkflowStepId(UUID.fromString(TreeWorkflows.COSTLY_SECOND)), CAT_USER)
        wiring.drained()

        then:
        sends() == (stopped ? 2 : 3)
        stops() == (stopped ? ["system ${SYSTEM} ${beneath()} []" as String] : [])

        where:
        own  || stopped
        334L || true
        335L || false
    }

    def "a call turned away beneath is sent again only while the ceiling that run keeps is not reached: #own"() {
        given:
        def pause = new Pause()
        model.answering(cameBack())
        model.pausing(pause, [new TurnAway("Busy.", false)], cameBack())
        starting(1_000_000L, own)
        pause.awaitReached()
        RunRows.called(store, beneath(), "came_back", 1, 0)

        when:
        pause.release()
        wiring.drained()

        then:
        sends() == (stopped ? 2 : 3)
        stops() == (stopped ? ["system ${SYSTEM} ${beneath()} []" as String] : [])

        cleanup:
        pause.release()

        where:
        own  || stopped
        334L || true
        335L || false
    }
}
