package com.lilradish.lite.app.run

import static com.lilradish.lite.app.run.fixture.TicketWorkflow.ANN
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.ANN_USER
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.CAT
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.GROUP
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.RUN
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARISE
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.VERSION
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.WORKFLOW
import static com.lilradish.lite.testutil.library.LibraryStore.groupId

import com.lilradish.lite.app.codestep.CodeRuns
import com.lilradish.lite.app.group.GroupRoles
import com.lilradish.lite.app.run.fixture.StepRows
import com.lilradish.lite.app.run.fixture.TicketWorkflow
import com.lilradish.lite.app.search.SearchWays
import com.lilradish.lite.domain.inference.CallOutcome
import com.lilradish.lite.domain.inference.TurnAway
import com.lilradish.lite.domain.run.Ceiling
import com.lilradish.lite.domain.run.ReviewOutcome
import com.lilradish.lite.domain.run.RunId
import com.lilradish.lite.domain.run.RunState
import com.lilradish.lite.domain.run.RunningOn
import com.lilradish.lite.domain.run.StepPosition
import com.lilradish.lite.domain.run.StepPositions
import com.lilradish.lite.domain.run.WorkflowStepId
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.codestep.CodeStepsHeld
import com.lilradish.lite.testutil.inference.Pause
import com.lilradish.lite.testutil.inference.ScriptedModelCalls
import com.lilradish.lite.testutil.library.DeployedModels
import com.lilradish.lite.testutil.library.LibraryStore
import com.lilradish.lite.testutil.run.RecordedModelCalls
import com.lilradish.lite.testutil.run.RunRows
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class RunCeilingIntegrationSpec extends Specification {

    static final String SYSTEM = RunRows.WORKFLOW_RUNNER

    static final String FITS = '{"values":{"summary":"A printer fire."},"confidences":{}}'

    static final String CEILING_STOP = "system ${RUN} ${SYSTEM}"

    @Shared
    @AutoCleanup
    TestPostgres server = TestPostgres.worker()

    @Shared
    int databasesMade = 0

    LibraryStore store

    RecordedModelCalls model

    RunTree tree

    RunSnapshots snapshots

    EngineWrites writes

    EngineExecutor executor

    RunEngine engine

    RunChanges changes

    StepActs acts

    def setupSpec() {
        LibraryStore.prepareBaseline(server)
    }

    def setup() {
        store = LibraryStore.forCase(server, "run_ceiling_" + (++databasesMade))
        model = new RecordedModelCalls()
        tree = new RunTree(store.session)
        snapshots = new RunSnapshots(store.session, CodeStepsHeld.NONE, new SearchWays([]))
        writes = new EngineWrites(store.session)
        executor = EngineExecutors.of(store.database)
        def ceilings = new CeilingReach(store.session, DeployedModels.HELD, writes)
        def heldFiles = EngineWiring.files(store)
        engine = new RunEngine(store.session, store.transactions(), tree, snapshots, writes, executor,
                new EngineCalls(EngineWiring.withoutTools(), model, store.transactions(), tree, writes, ceilings, snapshots, heldFiles),
                new EngineCodes(new CodeRuns(CodeStepsHeld.NONE), store.transactions(), tree, snapshots, writes, executor, heldFiles),
                DeployedModels.HELD, ceilings, new GroupRoles(store.session), Deployments.DEEP_ENOUGH, heldFiles)
        def roles = new GroupRoles(store.session)
        changes = new RunChanges(store.session, store.transactions(), roles, tree, snapshots, engine)
        acts = new StepActs(store.session, store.transactions(), roles, tree, snapshots, writes, engine,
                heldFiles, EngineWiring.fileDeployment(), com.lilradish.lite.testutil.library.DeployedModels.HELD)
        TicketWorkflow.seed(store)
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                update workflow_steps set producer = 'model', producer_model = 'general', producer_mode = 'ordinary'
                 where workflow_step_id = ?::uuid""").params(SUMMARISE).update()
    }

    def cleanup() {
        executor.destroy()
    }

    private static RunId runId() {
        new RunId(UUID.fromString(RUN))
    }

    private static CallOutcome.CameBack cameBack(String answer) {
        new CallOutcome.CameBack(answer, 321, 12, true, false, null, null)
    }

    private void ceilingOf(long ceiling) {
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("update workflow_versions set ceiling = ? where entry_version_id = ?::uuid")
                .params(ceiling, VERSION).update()
    }

    // Write the run, lock its tree, and plan its first step in one transaction.
    private void started(long spent = 0) {
        store.transactions().executeWithoutResult {
            StepRows.run(store, RUN, GROUP, 1, WORKFLOW, VERSION, CAT, TicketWorkflow.STARTED_WITH)
            if (spent > 0) {
                RunRows.called(store, RUN, "came_back", 700, spent - 700)
            }
            engine.planStarted(tree.lock(groupId(GROUP), runId()).orElseThrow())
        }
    }

    private void until(String what, Closure<Boolean> reached) {
        def deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!reached()) {
            assert System.nanoTime() < deadline: "never reached: ${what}"
            Thread.sleep(10)
        }
    }

    private void untilStoppedByCeiling(int times) {
        until("${times} stops by the ceiling") {
            store.count("select count(*) from run_stops where created_by_kind = 'system'") == times
        }
        drained()
    }

    // Each worker reaches the barrier only after finishing the tasks already handed to it.
    private void drained() {
        int threads = executor.@pool.corePoolSize
        def meeting = new CyclicBarrier(threads + 1)
        threads.times { executor.execute { meeting.await(10, TimeUnit.SECONDS) } }
        meeting.await(10, TimeUnit.SECONDS)
    }

    private List<String> stops() {
        // DB-SPECIFIC: PostgreSQL ::text casts; || converts non-string operands to text.
        store.texts("""
                select stop.created_by_kind || ' ' || coalesce(stop.ceiling_run_id::text, '-') || ' ' || stop.created_by
                       || ' ' || case when stop.opened_again_at is null then 'in force' else 'opened' end
                  from run_stops stop order by stop.created_at, stop.run_stop_id
                """)
    }

    private List<String> tries() {
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts; || converts non-string operands to text.
        store.texts("""
                select coalesce(try.try_number::text, '-') || ' ' || try.producer || ' ' || try.created_by || ' '
                       || case when try.ended_at is null then 'open' else 'ended' end
                  from productions try join run_steps held on held.run_step_id = try.run_step_id
                 where held.workflow_step_id = ?::uuid order by try.try_number
                """, SUMMARISE)
    }

    private List<String> modelCalls() {
        // DB-SPECIFIC: PostgreSQL ::text casts; || converts non-string operands to text.
        store.texts("""
                select coalesce(call.outcome::text, 'out') || ' ' || call.sent_count || ' '
                       || coalesce(call.came_back_count::text, '-')
                  from model_calls call where call.production_id is not null order by call.created_at
                """)
    }

    private StepPosition summarise() {
        StepPositions.of(snapshots.asRead(groupId(GROUP), runId()))[0]
    }

    private Runs.RunView read() {
        new Runs(store.session, new GroupRoles(store.session), store.transactionManager(), snapshots)
                .run(groupId(GROUP), runId(), ANN_USER)
    }

    def "a ceiling reached as a try is to be written down as sent stops the run by the system, naming its run, and sends nothing"() {
        given:
        ceilingOf(770)

        when:
        started(770)
        untilStoppedByCeiling(1)

        then:
        stops() == ["${CEILING_STOP} in force" as String]
        read().state() == RunState.STOPPED
        read().stop().by() == new Runs.Stopper.ByCeiling([new Runs.NumberedRun(runId(), 1)])

        and: "the try left open and unsent, nothing written of its sending and nothing sent"
        tries() == ["- model ${SYSTEM} open" as String]
        modelCalls() == []
        store.count("select count(*) from run_step_send_attempts") == 0
        summarise() == new StepPosition.Running(RunningOn.NEXT_TRY)
        model.scripted.requests == []
    }

    def "the call that crosses the ceiling is made and counted as any other, and stops nothing"() {
        given:
        ceilingOf(800)
        model.scripted.answering(cameBack(FITS))

        when:
        started(770)
        until("the call ended") { modelCalls() == ["came_back 321 12"] }
        drained()

        then:
        RunBudget.spentAsRead(store.session, runId(), true) == new RunBudget.Spend(1021, 82, false, 1, 2)
        tries() == ["1 model ${SYSTEM} ended" as String]
        model.scripted.requests.size() == 1

        and:
        stops() == []
    }

    def "a run its ceiling stopped is stopped again as it is opened, until the ceiling is raised"() {
        given:
        ceilingOf(770)
        model.scripted.answering(cameBack(FITS))
        started(770)
        untilStoppedByCeiling(1)

        when:
        changes.openAgain(groupId(GROUP), runId(), ANN_USER)
        untilStoppedByCeiling(2)

        then: "stopped again by the system before anything is written or sent"
        stops() == ["${CEILING_STOP} opened" as String, "${CEILING_STOP} in force" as String]
        store.count("select count(*) from run_step_send_attempts") == 0
        model.scripted.requests == []

        when:
        changes.changeCeiling(groupId(GROUP), runId(), runId(), new Ceiling(5000), ANN_USER)
        changes.openAgain(groupId(GROUP), runId(), ANN_USER)
        until("the call ended") { modelCalls() == ["came_back 321 12"] }
        drained()

        then: "sent once, and stopped no more"
        model.scripted.requests.size() == 1
        stops() == ["${CEILING_STOP} opened" as String] * 2
    }

    def "asking a model again under a ceiling reached writes the person's try, then stops the run rather than sending it"() {
        given:
        ceilingOf(1000)
        model.scripted.answering(cameBack(FITS))
        started(770)
        until("the first call ended") { modelCalls() == ["came_back 321 12"] }
        drained()
        def step = new WorkflowStepId(UUID.fromString(SUMMARISE))
        acts.review(groupId(GROUP), runId(), step, 1, ANN_USER,
                [summary: new StepActs.ReviewedRecord(ReviewOutcome.REFUSED, "Say it is on fire.")])

        when:
        acts.askAgain(groupId(GROUP), runId(), step, 2, ANN_USER)
        untilStoppedByCeiling(1)

        then: "the try the person's, open and unsent"
        tries() == ["1 model ${SYSTEM} ended" as String, "- model ${ANN} open" as String]
        summarise() == new StepPosition.Running(RunningOn.NEXT_TRY)
        store.count("select count(*) from run_step_send_attempts") == 1

        and: "stopped once by the ceiling, and nothing sent for it"
        stops() == ["${CEILING_STOP} in force" as String]
        read().stop().by() == new Runs.Stopper.ByCeiling([new Runs.NumberedRun(runId(), 1)])
        modelCalls() == ["came_back 321 12"]
        model.scripted.requests.size() == 1
    }

    def "a call turned away is sent again only while what the tree spent, less that call, stays under the ceiling: #ceiling"() {
        given:
        ceilingOf(ceiling)
        def gate = new Pause()
        model.scripted.pausing(gate, [new TurnAway("Busy.", false)], cameBack(FITS))
        started()
        gate.awaitReached()
        RunRows.called(store, RUN, null, 700, null)

        when:
        gate.release()
        until("the call ended") { modelCalls().size() == 1 && !modelCalls()[0].startsWith("out") }
        drained()

        then:
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select outcome::text from model_calls where production_id is not null") == [ended]
        store.texts("select case when resent_at is null then 'not sent again' else 'sent again' end from model_call_turnaways") ==
                [turnaway]
        model.scripted.events.count { it == ScriptedModelCalls.SENT } == sends

        and: "held and stopped exactly where it was not sent again"
        // DB-SPECIFIC: PostgreSQL ::text casts; || converts non-string operands to text.
        store.texts("select reason || ' ' || coalesce(released_at::text, 'held') from run_step_holds") == holds
        stops() == stopped

        where:
        ceiling || ended         | turnaway         | sends | holds                | stopped
        4700    || "turned_away" | "not sent again" | 1     | ["turned_away held"] | ["${CEILING_STOP} in force" as String]
        4701    || "came_back"   | "sent again"     | 2     | []                   | []
    }
}
