package org.lilradish.lite.app.run

import static org.lilradish.lite.app.run.fixture.TicketWorkflow.ANN_USER
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.BEN_USER
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.CAT
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.CAT_USER
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.CONFIRM
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.GROUP
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.RUN
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARISE
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARY
import static org.lilradish.lite.testutil.library.LibraryStore.attempting
import static org.lilradish.lite.testutil.library.LibraryStore.groupId

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.sql.Connection
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.libprunus.core.error.ApiErrorException
import org.lilradish.lite.app.codestep.CodeRuns
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.run.fixture.StepRows
import org.lilradish.lite.app.run.fixture.TicketWorkflow
import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.run.ReviewOutcome
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.domain.run.WorkflowStepId
import org.lilradish.lite.testutil.codestep.CodeStepsHeld
import org.lilradish.lite.testutil.inference.ScriptedModelCalls
import org.lilradish.lite.testutil.library.DeployedModels
import org.lilradish.lite.testutil.library.LibraryStore
import org.lilradish.lite.testutil.run.Blocking
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * Acts on one step meeting another in flight, on a real server running the real baseline: each waits on the
 * tree's lock the other holds, and decides on what the other left, never on what it read before it waited.
 */
class StepActsRaceIntegrationSpec extends Specification {

    static final String ANN = TicketWorkflow.ANN

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    EngineExecutor executor

    StepActs acts

    @AutoCleanup("shutdownNow")
    ExecutorService racing = Executors.newFixedThreadPool(2)

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, "step_races_" + (++databasesMade))
        def session = store.session
        def tree = new RunTree(session)
        def snapshots = new RunSnapshots(session, CodeStepsHeld.NONE)
        def writes = new EngineWrites(session)
        executor = EngineExecutors.of(store.database)
        def ceilings = new CeilingReach(session, DeployedModels.HELD, writes)
        def engine = new RunEngine(session, store.transactions(), tree, snapshots, writes, executor,
                new EngineCalls(new ScriptedModelCalls(), store.transactions(), tree, writes, ceilings),
                new EngineCodes(new CodeRuns(CodeStepsHeld.NONE), store.transactions(), tree, snapshots, writes, executor),
                DeployedModels.HELD, ceilings, new GroupRoles(session))
        acts = new StepActs(session, store.transactions(), new GroupRoles(session), tree, snapshots, writes, engine)
        TicketWorkflow.seed(store)
        store.transactions().executeWithoutResult {
            TicketWorkflow.run(store)
            engine.planStarted(tree.lock(groupId(GROUP), runId()).orElseThrow())
        }
        StepRows.answered(store, SUMMARISE, CAT, "Read it twice.", [(SUMMARY): '"A printer fire."'])
    }

    def cleanup() {
        executor.destroy()
    }

    private static RunId runId() {
        new RunId(UUID.fromString(RUN))
    }

    private static WorkflowStepId stepId(String spelled) {
        new WorkflowStepId(UUID.fromString(spelled))
    }

    private Future<Object> reviewing(org.lilradish.lite.domain.identity.UserId reviewer, ReviewOutcome outcome) {
        attempting(racing) {
            acts.review(groupId(GROUP), runId(), stepId(SUMMARISE), 1, reviewer,
                    [summary: new StepActs.ReviewedRecord(outcome, outcome == ReviewOutcome.REFUSED ? "No." : null)])
        }
    }

    /** The tree's root held by a transaction of the spec's own, which then makes {@code writes} and stays open. */
    private Connection inFlight(String... writes) {
        store.holding((["select 1 from runs where run_id = '${RUN}' for no key update" as String] + writes.toList())
                as String[])
    }

    def "two reviews of one try racing: the first decides it, and the second finds it moved on"() {
        given:
        def holder = inFlight()

        when:
        def first = reviewing(ANN_USER, ReviewOutcome.ASSURED)
        def second = reviewing(BEN_USER, ReviewOutcome.REFUSED)
        store.untilWaiting(2)
        holder.rollback()
        holder.close()
        def outcomes = [first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)]

        then: "one landed and one was refused, which one being the server's to choose"
        outcomes.count { it == null } == 1
        outcomes.findAll { it != null }.every {
            it instanceof ApiErrorException && it.errorCode() == RefusalCode.STEP_MOVED_ON
        }

        and: "one review written, and no key ever breached to stop the second"
        store.count("select count(*) from reviews") == 1
        store.count("select count(*) from review_decisions") == 1
    }

    def "a review waiting while the run is stopped lands after the stop, and is refused as the run stopped"() {
        given:
        def stopping = inFlight("insert into run_stops (run_id, root_run_id, created_by) values ('${RUN}', '${RUN}', '${CAT}')")

        when:
        def review = reviewing(ANN_USER, ReviewOutcome.ASSURED)
        Blocking.untilBlockedBy(store, stopping)
        stopping.commit()
        stopping.close()

        then:
        def refused = review.get(10, TimeUnit.SECONDS)
        refused instanceof ApiErrorException
        refused.errorCode() == RefusalCode.RUN_STOPPED

        and:
        store.count("select count(*) from reviews") == 0
    }

    def "a reviewer whose roles are taken while the review waits is refused as not permitted, writing nothing"() {
        given:
        def holder = inFlight()

        when:
        def review = reviewing(ANN_USER, ReviewOutcome.ASSURED)
        Blocking.untilBlockedBy(store, holder)
        def taking = store.takingRoles(GROUP, ANN, "operator")
        taking.commit()
        taking.close()
        holder.rollback()
        holder.close()

        then:
        def refused = review.get(10, TimeUnit.SECONDS)
        refused instanceof ApiErrorException
        refused.errorCode() == RefusalCode.ACT_NOT_PERMITTED

        and:
        store.count("select count(*) from reviews") == 0
        store.count("select count(*) from run_steps where workflow_step_id = ?::uuid", CONFIRM) == 0
    }

    def "asking again twice for one try: the first makes it, and the second finds it made"() {
        given:
        acts.review(groupId(GROUP), runId(), stepId(SUMMARISE), 1, ANN_USER,
                [summary: new StepActs.ReviewedRecord(ReviewOutcome.REFUSED, "No.")])
        def holder = inFlight()

        when:
        def first = attempting(racing) { acts.askAgain(groupId(GROUP), runId(), stepId(SUMMARISE), 2, CAT_USER) }
        def second = attempting(racing) { acts.askAgain(groupId(GROUP), runId(), stepId(SUMMARISE), 2, ANN_USER) }
        store.untilWaiting(2)
        holder.rollback()
        holder.close()
        def outcomes = [first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)]

        then:
        outcomes.count { it == null } == 1
        outcomes.findAll { it != null }.every {
            it instanceof ApiErrorException && it.errorCode() == RefusalCode.STEP_MOVED_ON
        }

        and:
        store.count("select count(*) from productions where try_number = 2") == 1
    }
}
