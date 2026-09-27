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
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.run.fixture.StepRows
import org.lilradish.lite.app.run.fixture.TicketWorkflow
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

/**
 * A model's try written down as sent, on a real server running the real baseline, the model scripted: the plan
 * asks the try and writes nothing of its sending, and the engine's thread writes the attempt and the call just
 * before it sends, only while the try is still the run's next act, the run not stopped and nothing new going out.
 * Until then the try is open and unsent, has spent nothing, and is sent again by the run going on by itself.
 */
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

    /** Every pool made, so each is shut however a feature ends. */
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
        snapshots = new RunSnapshots(store.session, CodeStepsHeld.NONE)
        writes = new EngineWrites(store.session)
        ceilings = new CeilingReach(store.session, DeployedModels.HELD, writes)
        transactions = new CountingTransactions(store.transactions())
        executor = EngineExecutors.of(store.database)
        pools << executor
        engine = engineOn(executor)
        TicketWorkflow.seed(store)
        store.session.sql("""
                update workflow_steps set producer = 'model', producer_model = 'general', producer_mode = 'ordinary'
                 where workflow_step_id = ?::uuid""").params(SUMMARISE).update()
    }

    def cleanup() {
        pools*.destroy()
    }

    private RunEngine engineOn(EngineExecutor pool) {
        new RunEngine(store.session, transactions, tree, snapshots, writes, pool,
                new EngineCalls(model, transactions, tree, writes, ceilings),
                new EngineCodes(new CodeRuns(CodeStepsHeld.NONE), transactions, tree, snapshots, writes, pool),
                DeployedModels.HELD, ceilings, new GroupRoles(store.session))
    }

    private StepActs acts() {
        new StepActs(store.session, store.transactions(), new GroupRoles(store.session), tree, snapshots, writes, engine)
    }

    private static RunId runId() {
        new RunId(UUID.fromString(RUN))
    }

    private static CallOutcome.CameBack cameBack(String answer) {
        new CallOutcome.CameBack(answer, 321, 12, true, false)
    }

    /** As a start does: the run written, its tree locked, the first step planned, in one transaction. */
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

    /** How many threads the engine's pool runs, read off the pool itself as EngineExecutorSpec reads its field. */
    private int engineThreads() {
        executor.@pool.corePoolSize
    }

    /** Every engine thread kept busy until the latch returned is let go, so whatever is handed over meanwhile waits. */
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

    /* A thread takes the next task only once its last has run, so all meeting here ran everything handed before. */
    private void drained() {
        def meeting = new CyclicBarrier(engineThreads() + 1)
        engineThreads().times { executor.execute { meeting.await(10, TimeUnit.SECONDS) } }
        meeting.await(10, TimeUnit.SECONDS)
    }

    /** Each try of summarise as its number, producer, who asked for it, and whether it is open. */
    private List<String> tries() {
        store.texts("""
                select try.try_number || ' ' || try.producer || ' ' || try.created_by || ' '
                       || case when try.ended_at is null then 'open' else 'ended' end
                  from productions try join run_steps held on held.run_step_id = try.run_step_id
                 where held.workflow_step_id = ?::uuid order by try.try_number
                """, SUMMARISE)
    }

    /** How many attempts and how many calls try {@code number} of summarise has, as "attempts calls". */
    private String sentOf(int number) {
        store.texts("""
                select (select count(*) from run_step_send_attempts attempt
                         where attempt.production_id = try.production_id) || ' '
                       || (select count(*) from model_calls call where call.production_id = try.production_id)
                  from productions try where try.try_number = ?
                """, number)[0]
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

        then: "asked, open and unsent, spending nothing, and read as its try being made"
        tries() == ["1 model ${SYSTEM} open" as String]
        sentOf(1) == "0 0"
        summarise() == new StepPosition.Running(RunningOn.NEXT_TRY)
        RunBudget.spentAsRead(store.session, runId(), true) == RunBudget.Spend.NOTHING

        and: "nothing sent"
        model.scripted.requests == []

        when: "the engine's thread takes it"
        letGo.countDown()
        untilEnded(1)

        then: "written down as sent once, and sent once"
        sentOf(1) == "1 1"
        model.scripted.requests.size() == 1
        model.madeOn == [[true, false]]
        tries() == ["1 model ${SYSTEM} ended" as String]
    }

    def "a try the engine's thread takes once stopping has begun is left unsent, and a later drive sends it once"() {
        given:
        model.scripted.answering(cameBack(FITS))
        def letGo = occupied()
        started()
        def destroying = racing.submit { executor.destroy() }
        until("stopping") { executor.stopping() }

        when:
        letGo.countDown()
        destroying.get(10, TimeUnit.SECONDS)

        then: "nothing written, nothing sent, and the try as the plan left it"
        sentOf(1) == "0 0"
        model.scripted.requests == []
        tries() == ["1 model ${SYSTEM} open" as String]
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
        sentOf(1) == "1 1"
        model.scripted.requests.size() == 1
    }

    def "a stop made between the plan and the engine's thread leaves the try unsent, and opening again sends it once"() {
        given:
        model.scripted.answering(cameBack(FITS))
        def letGo = occupied()
        started()

        when:
        RunRows.stopped(store, RUN, CAT)
        letGo.countDown()
        drained()

        then:
        sentOf(1) == "0 0"
        model.scripted.requests == []
        tries() == ["1 model ${SYSTEM} open" as String]

        when:
        new RunChanges(store.session, store.transactions(), new GroupRoles(store.session), tree, snapshots, engine)
                .openAgain(groupId(GROUP), runId(), ANN_USER)
        untilEnded(1)
        drained()

        then:
        sentOf(1) == "1 1"
        model.scripted.requests.size() == 1
    }

    /** The hold is written only by the run going on from the send that found the stop, which a let-go then finds. */
    def "a stop on what the step runs made between the plan and the engine's thread holds the step, and a let-go sends it once"() {
        given:
        model.scripted.answering(cameBack(FITS))
        def letGo = occupied()
        started()

        when:
        store.stopped(TicketWorkflow.SUMMARISE_QUESTION, ANN)
        letGo.countDown()
        drained()

        then: "held on the stop, nothing written of its sending and nothing sent"
        holds() == ["entry_stopped held"]
        sentOf(1) == "0 0"
        model.scripted.requests == []

        when:
        store.session.sql("update entry_stops set let_go_at = now(), let_go_by = ?::uuid where entry_id = ?::uuid")
                .params(ANN, TicketWorkflow.SUMMARISE_QUESTION).update()
        engine.goesOn(entryId(TicketWorkflow.SUMMARISE_QUESTION))
        untilEnded(1)
        drained()

        then:
        holds() == ["entry_stopped released"]
        sentOf(1) == "1 1"
        model.scripted.requests.size() == 1
    }

    /** The start hands it over once and a drive meanwhile again; the one let through second finds it sent. */
    def "a try handed over twice is written down as sent once and sent once"() {
        given:
        model.scripted.answering(cameBack(FITS))
        def letGo = occupied()
        started()
        engine.drive(groupId(GROUP), runId())

        when:
        letGo.countDown()
        untilEnded(1)
        drained()

        then:
        sentOf(1) == "1 1"
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
        untilEnded(1)
        def step = new WorkflowStepId(UUID.fromString(SUMMARISE))
        acts().review(groupId(GROUP), runId(), step, 1, ANN_USER,
                [summary: new StepActs.ReviewedRecord(ReviewOutcome.REFUSED, "Say it is on fire.")])
        def letGo = occupied()

        when:
        acts().askAgain(groupId(GROUP), runId(), step, 2, ANN_USER)

        then: "asked by the person, open and unsent, and read as its try being made"
        tries() == ["1 model ${SYSTEM} ended" as String, "2 model ${ANN} open" as String]
        sentOf(2) == "0 0"
        summarise() == new StepPosition.Running(RunningOn.NEXT_TRY)
        model.scripted.requests.size() == 1

        when:
        letGo.countDown()
        untilEnded(2)
        drained()

        then: "sent once, though the act and the drive after it each handed it over"
        sentOf(2) == "1 1"
        model.scripted.requests.size() == 2
        tries() == ["1 model ${SYSTEM} ended" as String, "2 model ${ANN} ended" as String]
    }

    /** The store's transactions, counting those the engine's own threads run. */
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
