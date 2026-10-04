package org.lilradish.lite.app.run

import static org.lilradish.lite.app.run.fixture.TicketWorkflow.ANN
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.ANN_USER
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.CAT
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.GROUP
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.RUN
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARISE
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.VERSION
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.WORKFLOW
import static org.lilradish.lite.testutil.library.LibraryStore.entryId
import static org.lilradish.lite.testutil.library.LibraryStore.groupId

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.lilradish.lite.app.codestep.CodeRuns
import org.lilradish.lite.app.files.RunFiles
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.run.fixture.StepRows
import org.lilradish.lite.app.run.fixture.TicketWorkflow
import org.lilradish.lite.app.search.SearchWays
import org.lilradish.lite.domain.inference.CallOutcome
import org.lilradish.lite.domain.run.ReviewOutcome
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.domain.run.RunningOn
import org.lilradish.lite.domain.run.StepPosition
import org.lilradish.lite.domain.run.StepPositions
import org.lilradish.lite.domain.run.WorkflowStepId
import org.lilradish.lite.testutil.codestep.CodeStepsHeld
import org.lilradish.lite.testutil.library.DeployedModels
import org.lilradish.lite.testutil.library.LibraryStore
import org.lilradish.lite.testutil.run.RecordedModelCalls
import org.lilradish.lite.testutil.run.RunRows
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class RunEngineSendIntegrationSpec extends Specification {

    static final String SYSTEM = RunRows.WORKFLOW_RUNNER

    static final String FITS = '{"values":{"summary":"A printer fire."},"confidences":{}}'

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    RecordedModelCalls model

    RunTree tree

    RunSnapshots snapshots

    EngineWrites writes

    CeilingReach ceilings

    CountingTransactions transactions

    EngineExecutor executor

    RunEngine engine

    RunFiles heldFiles

    List<EngineExecutor> pools = []

    @AutoCleanup("shutdownNow")
    ExecutorService racing = Executors.newFixedThreadPool(1)

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, "run_engine_send_" + (++databasesMade))
        model = new RecordedModelCalls()
        tree = new RunTree(store.session)
        snapshots = new RunSnapshots(store.session, CodeStepsHeld.NONE, new SearchWays([]))
        writes = new EngineWrites(store.session)
        ceilings = new CeilingReach(store.session, DeployedModels.HELD, writes)
        transactions = new CountingTransactions(store.transactions())
        executor = EngineExecutors.of(store.database)
        pools << executor
        heldFiles = EngineWiring.files(store)
        engine = engineOn(executor)
        TicketWorkflow.seed(store)
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                update workflow_steps set producer = 'model', producer_model = 'general', producer_mode = 'ordinary'
                 where workflow_step_id = ?::uuid""").params(SUMMARISE).update()
    }

    def cleanup() {
        pools*.destroy()
    }

    private RunEngine engineOn(EngineExecutor pool) {
        new RunEngine(store.session, transactions, tree, snapshots, writes, pool,
                new EngineCalls(EngineWiring.withoutTools(), model, transactions, tree, writes, ceilings, snapshots, heldFiles),
                new EngineCodes(new CodeRuns(CodeStepsHeld.NONE), transactions, tree, snapshots, writes, pool, heldFiles),
                DeployedModels.HELD, ceilings, new GroupRoles(store.session), Deployments.DEEP_ENOUGH, heldFiles)
    }

    private StepActs acts() {
        new StepActs(store.session, store.transactions(), new GroupRoles(store.session), tree, snapshots, writes, engine,
                heldFiles, EngineWiring.fileDeployment())
    }

    private static RunId runId() {
        new RunId(UUID.fromString(RUN))
    }

    private static CallOutcome.CameBack cameBack(String answer) {
        new CallOutcome.CameBack(answer, 321, 12, true, false, null, null)
    }

    // Write the run, lock its tree, and plan the first step in one transaction.
    private void started() {
        store.transactions().executeWithoutResult {
            StepRows.run(store, RUN, GROUP, 1, WORKFLOW, VERSION, CAT, TicketWorkflow.STARTED_WITH)
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

    private void untilEnded(int calls) {
        until("${calls} calls ended") { store.count("select count(*) from model_calls where outcome is not null") == calls }
    }

    private int engineThreads() {
        executor.@pool.corePoolSize
    }

    // Occupy every engine thread until the latch opens so submitted work cannot run early.
    private CountDownLatch occupied() {
        def letGo = new CountDownLatch(1)
        def busy = new CountDownLatch(engineThreads())
        engineThreads().times {
            executor.execute {
                busy.countDown()
                assert letGo.await(10, TimeUnit.SECONDS): "the engine's threads were never let go"
            }
        }
        assert busy.await(10, TimeUnit.SECONDS): "the engine's threads were never all taken"
        letGo
    }

    // Each worker reaches the barrier only after finishing the tasks already handed to it.
    private void drained() {
        def meeting = new CyclicBarrier(engineThreads() + 1)
        engineThreads().times { executor.execute { meeting.await(10, TimeUnit.SECONDS) } }
        meeting.await(10, TimeUnit.SECONDS)
    }

    private List<String> tries() {
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        store.texts("""
                select coalesce(try.try_number::text, '-') || ' ' || try.producer || ' ' || try.created_by || ' '
                       || case when try.ended_at is null then 'open' else 'ended' end
                  from productions try join run_steps held on held.run_step_id = try.run_step_id
                 where held.workflow_step_id = ?::uuid order by try.try_number
                """, SUMMARISE)
    }

    private String newestProduction() {
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, LIMIT.
        store.texts("""
                select production_id::text from productions try join run_steps step using (run_step_id)
                 where step.workflow_step_id = ?::uuid order by try.try_number desc nulls first limit 1
                """, SUMMARISE)[0]
    }

    private String sentOf(String production) {
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.texts("""
                select (select count(*) from run_step_send_attempts attempt
                         where attempt.production_id = try.production_id) || ' '
                       || (select count(*) from model_calls call where call.production_id = try.production_id)
                  from productions try where try.production_id = ?::uuid
                """, production)[0]
    }

    private List<String> holds() {
        store.texts("""
                select hold.reason || ' ' || case when hold.released_at is null then 'held' else 'released' end
                  from run_step_holds hold order by hold.created_at, hold.run_step_hold_id
                """)
    }

    private StepPosition summarise() {
        StepPositions.of(snapshots.asRead(groupId(GROUP), runId()))[0]
    }

    def "a model's try reached is asked with the plan, nothing of its sending written until the engine's thread writes it down as sent"() {
        given:
        model.scripted.answering(cameBack(FITS))
        def letGo = occupied()

        when:
        started()
        def production = newestProduction()

        then: "asked, open and unsent, spending nothing, and read as its try being made"
        tries() == ["- model ${SYSTEM} open" as String]
        sentOf(production) == "0 0"
        summarise() == new StepPosition.Running(RunningOn.NEXT_TRY)
        RunBudget.spentAsRead(store.session, runId(), true) == RunBudget.Spend.NOTHING

        and: "nothing sent"
        model.scripted.requests == []

        when: "the engine's thread takes it"
        letGo.countDown()
        untilEnded(1)

        then: "written down as sent once, and sent once"
        sentOf(production) == "1 1"
        model.scripted.requests.size() == 1
        model.madeOn == [[true, false]]
        tries() == ["1 model ${SYSTEM} ended" as String]
    }

    def "a try the engine's thread takes once stopping has begun is left unsent, and a later drive sends it once"() {
        given:
        model.scripted.answering(cameBack(FITS))
        def letGo = occupied()
        started()
        def production = newestProduction()
        def destroying = racing.submit { executor.destroy() }
        until("stopping") { executor.stopping() }

        when:
        letGo.countDown()
        destroying.get(10, TimeUnit.SECONDS)

        then: "nothing written, nothing sent, and the try as the plan left it"
        sentOf(production) == "0 0"
        model.scripted.requests == []
        tries() == ["- model ${SYSTEM} open" as String]
        summarise() == new StepPosition.Running(RunningOn.NEXT_TRY)

        and: "the one transaction on the engine's threads was the one that found it not to be sent, nothing gone on from"
        transactions.handedOver.get() == 1

        when: "the run driven again on an engine not stopping, as after a restart"
        executor = EngineExecutors.of(store.database)
        pools << executor
        engine = engineOn(executor)
        engine.drive(groupId(GROUP), runId())
        untilEnded(1)

        then:
        sentOf(production) == "1 1"
        model.scripted.requests.size() == 1
    }

    def "a stop made between the plan and the engine's thread leaves the try unsent, and opening again sends it once"() {
        given:
        model.scripted.answering(cameBack(FITS))
        def letGo = occupied()
        started()
        def production = newestProduction()

        when:
        RunRows.stopped(store, RUN, CAT)
        letGo.countDown()
        drained()

        then:
        sentOf(production) == "0 0"
        model.scripted.requests == []
        tries() == ["- model ${SYSTEM} open" as String]

        when:
        new RunChanges(store.session, store.transactions(), new GroupRoles(store.session), tree, snapshots, engine)
                .openAgain(groupId(GROUP), runId(), ANN_USER)
        untilEnded(1)
        drained()

        then:
        sentOf(production) == "1 1"
        model.scripted.requests.size() == 1
    }

    def "a stop on what the step runs made between the plan and the engine's thread holds the step, and a let-go sends it once"() {
        given:
        model.scripted.answering(cameBack(FITS))
        def letGo = occupied()
        started()
        def production = newestProduction()

        when:
        store.stopped(TicketWorkflow.SUMMARISE_QUESTION, ANN)
        letGo.countDown()
        drained()

        then: "held on the stop, nothing written of its sending and nothing sent"
        holds() == ["entry_stopped held"]
        sentOf(production) == "0 0"
        model.scripted.requests == []

        when:
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
        store.session.sql("update entry_stops set let_go_at = now(), let_go_by = ?::uuid where entry_id = ?::uuid")
                .params(ANN, TicketWorkflow.SUMMARISE_QUESTION).update()
        engine.goesOn(entryId(TicketWorkflow.SUMMARISE_QUESTION))
        untilEnded(1)
        drained()

        then:
        holds() == ["entry_stopped released"]
        sentOf(production) == "1 1"
        model.scripted.requests.size() == 1
    }

    def "a try handed over twice is written down as sent once and sent once"() {
        given:
        model.scripted.answering(cameBack(FITS))
        def letGo = occupied()
        started()
        def production = newestProduction()
        engine.drive(groupId(GROUP), runId())

        when:
        letGo.countDown()
        untilEnded(1)
        drained()

        then:
        sentOf(production) == "1 1"
        model.scripted.requests.size() == 1
        model.madeOn == [[true, false]]

        and: "no second attempt for the store to refuse"
        store.count("select count(*) from run_step_send_attempts") == 1
    }

    def "a model's try a person asks again is theirs on record before anything is sent, and is sent once the engine's thread takes it"() {
        given:
        model.scripted.answering(cameBack(FITS))
        model.scripted.answering(cameBack('{"values":{"summary":"A printer on fire."},"confidences":{}}'))
        started()
        def production = newestProduction()
        untilEnded(1)
        def step = new WorkflowStepId(UUID.fromString(SUMMARISE))
        acts().review(groupId(GROUP), runId(), step, 1, ANN_USER,
                [summary: new StepActs.ReviewedRecord(ReviewOutcome.REFUSED, "Say it is on fire.")])
        def letGo = occupied()

        when:
        acts().askAgain(groupId(GROUP), runId(), step, 2, ANN_USER)
        production = newestProduction()

        then: "asked by the person, open and unsent, and read as its try being made"
        tries() == ["1 model ${SYSTEM} ended" as String, "- model ${ANN} open" as String]
        sentOf(production) == "0 0"
        summarise() == new StepPosition.Running(RunningOn.NEXT_TRY)
        model.scripted.requests.size() == 1

        when:
        letGo.countDown()
        untilEnded(2)
        drained()

        then: "sent once, though the act and the drive after it each handed it over"
        sentOf(production) == "1 1"
        model.scripted.requests.size() == 2
        tries() == ["1 model ${SYSTEM} ended" as String, "2 model ${ANN} ended" as String]
    }

    static final class CountingTransactions implements TransactionOperations {

        final AtomicInteger handedOver = new AtomicInteger()

        private final TransactionOperations transactions

        CountingTransactions(TransactionOperations transactions) {
            this.transactions = transactions
        }

        @Override
        <T> T execute(TransactionCallback<T> action) {
            if (Thread.currentThread() instanceof EngineThread) {
                handedOver.incrementAndGet()
            }
            transactions.execute(action)
        }
    }
}
