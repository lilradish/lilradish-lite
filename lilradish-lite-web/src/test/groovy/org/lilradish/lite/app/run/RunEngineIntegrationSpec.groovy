package org.lilradish.lite.app.run

import static org.lilradish.lite.app.run.fixture.TicketWorkflow.CAT
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.CONFIRM
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.GROUP
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.RUN
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARISE
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARISE_QUESTION
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.TICKET_IN
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.WORKFLOW
import static org.lilradish.lite.testutil.library.LibraryStore.FIRST_STEWARD
import static org.lilradish.lite.testutil.library.LibraryStore.attempting
import static org.lilradish.lite.testutil.library.LibraryStore.entryId
import static org.lilradish.lite.testutil.library.LibraryStore.groupId

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.lilradish.lite.app.codestep.CodeRuns
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.run.fixture.StepRows
import org.lilradish.lite.app.run.fixture.TicketWorkflow
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.testutil.codestep.CodeStepsHeld
import org.lilradish.lite.testutil.inference.ScriptedModelCalls
import org.lilradish.lite.testutil.library.DeployedModels
import org.lilradish.lite.testutil.library.LibraryStore
import org.lilradish.lite.testutil.run.Blocking
import org.lilradish.lite.testutil.run.RunRows
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * A run going on by itself, on a real server running the real baseline: its first step planned in the
 * transaction that started it, a stop holding the try a step would have made, a let-go releasing it, and a run
 * that wants nothing of itself left as it is.
 */
class RunEngineIntegrationSpec extends Specification {

    static final String SYSTEM = RunRows.WORKFLOW_RUNNER

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    RunTree tree

    EngineExecutor executor

    RunEngine engine

    @AutoCleanup("shutdownNow")
    ExecutorService racing = Executors.newFixedThreadPool(2)

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, "run_engine_" + (++databasesMade))
        def session = store.session
        tree = new RunTree(session)
        executor = EngineExecutors.of(store.database)
        def writes = new EngineWrites(session)
        def snapshots = new RunSnapshots(session, CodeStepsHeld.NONE)
        def ceilings = new CeilingReach(session, DeployedModels.HELD, writes)
        engine = new RunEngine(session, store.transactions(), tree, snapshots, writes, executor,
                new EngineCalls(new ScriptedModelCalls(), store.transactions(), tree, writes, ceilings),
                new EngineCodes(new CodeRuns(CodeStepsHeld.NONE), store.transactions(), tree, snapshots, writes, executor),
                DeployedModels.HELD, ceilings, new GroupRoles(session))
        TicketWorkflow.seed(store)
    }

    def cleanup() {
        executor.destroy()
    }

    private static RunId runId(String spelled) {
        new RunId(UUID.fromString(spelled))
    }

    /** Every row the engine writes about a run's steps, as one string per table. */
    private List<String> stepRows() {
        ["run_steps", "productions", "production_inputs", "production_values", "run_step_holds"]
                .collect { store.digestOf(it) }
    }

    /** Each try of a step, as its number, who produced it, who asked for it and whether it is open. */
    private List<String> triesOf(String step) {
        store.texts("""
                select try.try_number || ' ' || try.producer || ' ' || try.created_by || ' '
                       || case when try.ended_at is null then 'open' else 'ended' end
                  from productions try join run_steps step on step.run_step_id = try.run_step_id
                 where step.workflow_step_id = ?::uuid order by try.try_number
                """, step)
    }

    private List<String> holdsOn(String step) {
        store.texts("""
                select hold.reason || ' ' || hold.created_by || ' ' || coalesce(hold.released_by::text, '-')
                  from run_step_holds hold join run_steps step on step.run_step_id = hold.run_step_id
                 where step.workflow_step_id = ?::uuid order by hold.created_at, hold.run_step_hold_id
                """, step)
    }

    /** As a start does: the run written, its tree locked, the first step planned, in one transaction. */
    private void started() {
        store.transactions().executeWithoutResult {
            TicketWorkflow.run(store)
            engine.planStarted(tree.lock(groupId(GROUP), runId(RUN)).orElseThrow())
        }
    }

    private void untilTried(String step, int tries) {
        def deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (triesOf(step).size() < tries) {
            assert System.nanoTime() < deadline: "step ${step} never reached ${tries} tries"
            Thread.sleep(10)
        }
    }

    def "plans the first step in the transaction that starts the run: a try asked of whoever answers it, taking what the run was started with"() {
        when:
        started()

        then:
        triesOf(SUMMARISE) == ["1 person ${SYSTEM} open" as String]
        store.texts("""
                select taken.binding_id || ' ' || coalesce(taken.source_production_value_id::text, '-')
                  from production_inputs taken
                """) == ["${TICKET_IN} -" as String]

        and: "and nothing past it, nothing held and nothing produced"
        triesOf(CONFIRM) == []
        store.count("select count(*) from run_steps where workflow_step_id = ?::uuid", CONFIRM) == 0
        store.count("select count(*) from run_step_holds") == 0
        store.count("select count(*) from production_values") == 0
    }

    /** Joined to the caller's transaction, a drive would hold the tree until whatever else the caller does ends. */
    def "refuses to drive a run inside a transaction of the caller's, writing nothing"() {
        given:
        store.transactions().executeWithoutResult { TicketWorkflow.run(store) }
        def before = stepRows()

        when:
        store.transactions().executeWithoutResult { engine.drive(groupId(GROUP), runId(RUN)) }

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "Run ${RUN} was driven inside a transaction it would join" as String

        and:
        stepRows() == before
    }

    def "refuses to plan a run outside the transaction that started it, writing nothing"() {
        given:
        TicketWorkflow.run(store)
        def before = stepRows()

        when:
        engine.planStarted(new LockedTree(groupId(GROUP), runId(RUN)))

        then:
        thrown(IllegalStateException)

        and:
        stepRows() == before
    }

    def "a run whose workflow is stopped holds its first step, whatever it runs, rather than asking it"() {
        given:
        store.stopped(WORKFLOW, FIRST_STEWARD)

        when:
        started()

        then:
        holdsOn(SUMMARISE) == ["entry_stopped ${SYSTEM} -" as String]

        and: "and no try is asked"
        triesOf(SUMMARISE) == []
    }

    def "a step running a stopped question is held when it is reached"() {
        given:
        store.stopped(SUMMARISE_QUESTION, FIRST_STEWARD)

        when:
        started()

        then:
        holdsOn(SUMMARISE) == ["entry_stopped ${SYSTEM} -" as String]
        triesOf(SUMMARISE) == []
    }

    def "a stopped question let go goes on by itself: the hold released by the system and the step asked"() {
        given:
        store.stopped(SUMMARISE_QUESTION, FIRST_STEWARD)
        started()
        store.session.sql("update entry_stops set let_go_at = now(), let_go_by = ?::uuid where entry_id = ?::uuid")
                .params(FIRST_STEWARD, SUMMARISE_QUESTION).update()

        when:
        engine.goesOn(entryId(SUMMARISE_QUESTION))
        untilTried(SUMMARISE, 1)

        then:
        holdsOn(SUMMARISE) == ["entry_stopped ${SYSTEM} ${SYSTEM}" as String]
        triesOf(SUMMARISE) == ["1 person ${SYSTEM} open" as String]
    }

    def "a let-go leaving the workflow itself stopped releases nothing and asks nothing"() {
        given:
        store.stopped(WORKFLOW, FIRST_STEWARD)
        store.stopped(SUMMARISE_QUESTION, FIRST_STEWARD)
        started()
        store.session.sql("update entry_stops set let_go_at = now(), let_go_by = ?::uuid where entry_id = ?::uuid")
                .params(FIRST_STEWARD, SUMMARISE_QUESTION).update()
        def before = stepRows()

        when:
        engine.goesOn(entryId(SUMMARISE_QUESTION))
        executor.destroy()

        then:
        stepRows() == before
        holdsOn(SUMMARISE) == ["entry_stopped ${SYSTEM} -" as String]
    }

    def "driving a run that wants nothing of itself writes nothing"() {
        given:
        started()
        def before = stepRows()

        when:
        engine.drive(groupId(GROUP), runId(RUN))

        then:
        stepRows() == before
    }

    def "a stopped run starts no step"() {
        given:
        TicketWorkflow.run(store)
        RunRows.stopped(store, RUN, CAT)
        def before = stepRows()

        when:
        engine.drive(groupId(GROUP), runId(RUN))

        then:
        stepRows() == before
        store.count("select count(*) from run_steps") == 0
    }

    def "a hold whose stop was let go with nothing handed on is released by the next drive, and the step asked"() {
        given:
        store.stopped(SUMMARISE_QUESTION, FIRST_STEWARD)
        started()
        store.session.sql("update entry_stops set let_go_at = now(), let_go_by = ?::uuid where entry_id = ?::uuid")
                .params(FIRST_STEWARD, SUMMARISE_QUESTION).update()

        when:
        engine.drive(groupId(GROUP), runId(RUN))

        then:
        holdsOn(SUMMARISE) == ["entry_stopped ${SYSTEM} ${SYSTEM}" as String]
        triesOf(SUMMARISE) == ["1 person ${SYSTEM} open" as String]
    }

    /** Both wait on the tree's lock the spec holds; the one let through second finds the try the first made. */
    def "two drives of one run racing make one try, the second finding it made"() {
        given:
        TicketWorkflow.run(store)
        def holder = store.holding("select 1 from runs where run_id = '${RUN}' for no key update")

        when:
        def first = attempting(racing) { engine.drive(groupId(GROUP), runId(RUN)) }
        def second = attempting(racing) { engine.drive(groupId(GROUP), runId(RUN)) }
        Blocking.untilBlockedBy(store, holder)
        store.untilWaiting(2)
        holder.rollback()
        holder.close()

        then:
        first.get(10, TimeUnit.SECONDS) == null
        second.get(10, TimeUnit.SECONDS) == null

        and:
        triesOf(SUMMARISE) == ["1 person ${SYSTEM} open" as String]
        store.count("select count(*) from production_inputs") == 1
    }
}
