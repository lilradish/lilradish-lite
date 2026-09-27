package org.lilradish.lite.app.run

import static org.lilradish.lite.app.run.fixture.TicketWorkflow.ANN_USER
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.CAT
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.CONFIRM
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.CONFIRM_QUESTION
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.GROUP
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.RUN
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARISE
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARY
import static org.lilradish.lite.testutil.library.LibraryStore.FIRST_STEWARD
import static org.lilradish.lite.testutil.library.LibraryStore.entryId
import static org.lilradish.lite.testutil.library.LibraryStore.groupId

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.time.OffsetDateTime
import org.lilradish.lite.app.codestep.CodeRuns
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.run.fixture.StepRows
import org.lilradish.lite.app.run.fixture.TicketWorkflow
import org.lilradish.lite.domain.run.ReviewOutcome
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.domain.run.WorkflowStepId
import org.lilradish.lite.testutil.codestep.CodeStepsHeld
import org.lilradish.lite.testutil.inference.ScriptedModelCalls
import org.lilradish.lite.testutil.library.DeployedModels
import org.lilradish.lite.testutil.library.LibraryStore
import org.lilradish.lite.testutil.run.RunRows
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * When something last happened to a run's steps, read on a real server running the real baseline as the list of
 * a group's runs reads it: each thing that happens to a step moves it to when that happened, and nothing stores it.
 */
class StepHappeningsIntegrationSpec extends Specification {

    static final String LAST_AT = "select " + StepHappenings.lastAt("run.run_id") + " from runs run where run.run_id = ?::uuid"

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    RunTree tree

    EngineExecutor executor

    RunEngine engine

    StepActs acts

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, "step_happenings_" + (++databasesMade))
        def session = store.session
        tree = new RunTree(session)
        def snapshots = new RunSnapshots(session, CodeStepsHeld.NONE)
        executor = EngineExecutors.of(store.database)
        def writes = new EngineWrites(session)
        def ceilings = new CeilingReach(session, DeployedModels.HELD, writes)
        engine = new RunEngine(session, store.transactions(), tree, snapshots, writes, executor,
                new EngineCalls(new ScriptedModelCalls(), store.transactions(), tree, writes, ceilings),
                new EngineCodes(new CodeRuns(CodeStepsHeld.NONE), store.transactions(), tree, snapshots, writes, executor),
                DeployedModels.HELD, ceilings, new GroupRoles(session))
        acts = new StepActs(session, store.transactions(), new GroupRoles(session), tree, snapshots, writes, engine)
        TicketWorkflow.seed(store)
    }

    def cleanup() {
        executor.destroy()
    }

    private OffsetDateTime lastAt() {
        store.session.sql(LAST_AT).params(RUN).query(OffsetDateTime).optional().orElse(null)
    }

    private OffsetDateTime at(String query, Object... parameters) {
        store.session.sql(query).params(parameters.toList()).query(OffsetDateTime).single()
    }

    private void started() {
        store.transactions().executeWithoutResult {
            TicketWorkflow.run(store)
            engine.planStarted(tree.lock(groupId(GROUP), new RunId(UUID.fromString(RUN))).orElseThrow())
        }
    }

    private void reviewed(ReviewOutcome outcome) {
        acts.review(groupId(GROUP), new RunId(UUID.fromString(RUN)), new WorkflowStepId(UUID.fromString(SUMMARISE)), 1,
                ANN_USER, [summary: new StepActs.ReviewedRecord(outcome, outcome == ReviewOutcome.REFUSED ? "No." : null)])
    }

    def "a run none of whose steps anything has happened to has no such moment"() {
        given:
        TicketWorkflow.run(store)

        expect:
        lastAt() == null
    }

    def "a try asked, and then answered, each moves it to when that happened"() {
        when:
        started()

        then:
        lastAt() == at("select created_at from productions")

        when:
        StepRows.answered(store, SUMMARISE, CAT, "Read it twice.", [(SUMMARY): '"A printer fire."'])

        then:
        lastAt() == at("select ended_at from productions")
        lastAt() > at("select created_at from productions")
    }

    def "a review moves it to when it was made"() {
        given:
        started()
        StepRows.answered(store, SUMMARISE, CAT, "Read it twice.", [(SUMMARY): '"A printer fire."'])

        when:
        reviewed(ReviewOutcome.REFUSED)

        then: "nothing going on by itself after a refusal, which leaves the next try to a person"
        lastAt() == at("select created_at from reviews")
        lastAt() > at("select ended_at from productions")
    }

    def "a hold written moves it to when it was written"() {
        given:
        store.stopped(CONFIRM_QUESTION, FIRST_STEWARD)
        started()
        StepRows.answered(store, SUMMARISE, CAT, "Read it twice.", [(SUMMARY): '"A printer fire."'])

        when:
        reviewed(ReviewOutcome.ASSURED)

        then:
        lastAt() == at("select created_at from run_step_holds")
        lastAt() > at("select created_at from reviews")
    }

    /**
     * Released a minute on, by hand: released by the engine, it asks the step's next try in the same instant, which
     * would move it there as well and leave the release proving nothing.
     */
    def "a hold released moves it to when it was released"() {
        given:
        store.stopped(CONFIRM_QUESTION, FIRST_STEWARD)
        started()
        StepRows.answered(store, SUMMARISE, CAT, "Read it twice.", [(SUMMARY): '"A printer fire."'])
        reviewed(ReviewOutcome.ASSURED)

        when:
        store.session.sql("""
                update run_step_holds set released_at = clock_timestamp() + interval '1 minute', released_by = ?::uuid
                """).params(RunRows.WORKFLOW_RUNNER).update()

        then:
        lastAt() == at("select released_at from run_step_holds")
        lastAt() > at("select created_at from run_step_holds")

        and: "nothing asked of confirm, so nothing else happened with the release"
        store.count("""
                select count(*) from productions try join run_steps step on step.run_step_id = try.run_step_id
                 where step.workflow_step_id = ?::uuid""", CONFIRM) == 0
    }

    def "a failure written moves it to when it was written"() {
        given:
        store.session.sql("update workflow_steps set reviewer_model = 'general', reviewer_mode = 'ordinary' where workflow_step_id = ?::uuid")
                .params(SUMMARISE).update()
        started()
        StepRows.answered(store, SUMMARISE, CAT, "Read it twice.", [(SUMMARY): '"A printer fire."'])

        when:
        store.session.sql("""
                insert into run_step_failures (run_step_id, run_id, run_step_kind, calls_a_model, reason, production_id,
                                               purpose, detail, created_at, created_by)
                select step.run_step_id, step.run_id, step.kind, step.calls_a_model, 'model_not_deployed',
                       try.production_id, 'review', 'Not deployed.', clock_timestamp() + interval '1 minute', ?::uuid
                  from productions try join run_steps step on step.run_step_id = try.run_step_id
                """).params(RunRows.WORKFLOW_RUNNER).update()

        then:
        lastAt() == at("select created_at from run_step_failures")
        lastAt() > at("select ended_at from productions")
    }
}
