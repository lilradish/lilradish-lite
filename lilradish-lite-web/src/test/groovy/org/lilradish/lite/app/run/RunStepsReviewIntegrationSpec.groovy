package org.lilradish.lite.app.run

import static org.lilradish.lite.app.run.fixture.TicketWorkflow.ANN_USER
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.CAT
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.CAT_USER
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.GROUP
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.RUN
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARISE
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARY
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.VERSION
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.WORKFLOW
import static org.lilradish.lite.testutil.library.LibraryStore.groupId

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.util.concurrent.TimeUnit
import org.lilradish.lite.app.codestep.CodeRuns
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.run.fixture.StepRows
import org.lilradish.lite.app.run.fixture.TicketWorkflow
import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.inference.CallOutcome
import org.lilradish.lite.domain.inference.TurnAway
import org.lilradish.lite.domain.model.DeployedModel
import org.lilradish.lite.domain.model.ModelCatalog
import org.lilradish.lite.domain.model.ModelName
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.domain.run.WorkflowStepId
import org.lilradish.lite.testutil.codestep.CodeStepsHeld
import org.lilradish.lite.testutil.inference.Pause
import org.lilradish.lite.testutil.inference.ScriptedModelCalls
import org.lilradish.lite.testutil.library.DeployedModels
import org.lilradish.lite.testutil.library.LibraryStore
import org.lilradish.lite.testutil.run.RunRows
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * A step whose values the model it names reviews, as its readers read it, on a real server running the real baseline,
 * the model scripted: how those values stand with that model while they wait, and how a review of it was lost.
 */
class RunStepsReviewIntegrationSpec extends Specification {

    static final String FITS = '{"values":{"summary":"A printer fire."},"confidences":{}}'

    static final String ASSURES = '{"decisions":{"summary":{"outcome":"assured"}}}'

    static final String REFUSES = '{"decisions":{"summary":{"outcome":"refused","words":"Say it is on fire."}}}'

    static final StepAnswers.WhoAnswer SMALL = new StepAnswers.WhoAnswer("model", null, "small", null)

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    ScriptedModelCalls model

    RunTree tree

    RunSnapshots snapshots

    EngineWrites writes

    EngineExecutor executor

    CeilingReach ceilings

    RunEngine engine

    RunSteps steps

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, "run_steps_review_" + (++databasesMade))
        model = new ScriptedModelCalls()
        tree = new RunTree(store.session)
        snapshots = new RunSnapshots(store.session, CodeStepsHeld.NONE)
        writes = new EngineWrites(store.session)
        executor = EngineExecutors.of(store.database)
        ceilings = new CeilingReach(store.session, DeployedModels.HELD, writes)
        holding(DeployedModels.HELD)
        steps = new RunSteps(store.session, new GroupRoles(store.session), snapshots, store.transactionManager())
        TicketWorkflow.seed(store, 1)
        store.session.sql("""
                update workflow_steps set producer = 'model', producer_model = 'general', producer_mode = 'ordinary',
                                          reviewer_model = 'small', reviewer_mode = 'ordinary'
                 where workflow_step_id = ?::uuid""").params(SUMMARISE).update()
    }

    def cleanup() {
        executor.destroy()
    }

    private void holding(ModelCatalog catalog) {
        engine = new RunEngine(store.session, store.transactions(), tree, snapshots, writes, executor,
                new EngineCalls(model, store.transactions(), tree, writes, ceilings),
                new EngineCodes(new CodeRuns(CodeStepsHeld.NONE), store.transactions(), tree, snapshots, writes, executor),
                catalog, ceilings, new GroupRoles(store.session))
    }

    private static RunId runId() {
        new RunId(UUID.fromString(RUN))
    }

    private static WorkflowStepId summarise() {
        new WorkflowStepId(UUID.fromString(SUMMARISE))
    }

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

    private StepAnswers.StepRowAnswer summariseRow(def reader = ANN_USER) {
        steps.steps(groupId(GROUP), runId(), reader).steps()[0]
    }

    private static CallOutcome.CameBack cameBack(String answer) {
        new CallOutcome.CameBack(answer, 321, 12, true, false)
    }

    def "values whose review is out read as waiting on the model it is out to, and offer nobody a review"() {
        given:
        def gate = new Pause()
        model.answering(cameBack(FITS))
        model.pausing(gate, [], cameBack(ASSURES))
        started()
        gate.awaitReached()

        when:
        def row = summariseRow()

        then:
        row.state() == "waiting"
        row.reviewer() == SMALL
        with(row.where()) {
            kind() == "waiting_on_review"
            review() == "out"
            spentUp() == null
            waitsOn() == "model"
            values() == [new StepAnswers.WaitingValueAnswer("summary", "model")]
            reason() == null
        }

        and:
        row.acts() == []
        row.withheld() == [new StepAnswers.WithheldAnswer("review", RefusalCode.REVIEW_NOT_A_PERSONS.code(), null, null)]

        cleanup:
        gate.release()
    }

    def "a review the model turned away for the last time reads as waiting on whoever started the run, saying whether what may be spent is used up"() {
        given:
        model.answering(cameBack(FITS))
        model.answering(new CallOutcome.TurnedAway(new TurnAway("Busy.", spent)))
        started()
        untilEnded(2)
        executor.destroy()

        when:
        def row = summariseRow(CAT_USER)

        then:
        row.state() == "waiting"
        with(row.where()) {
            kind() == "waiting_on_review"
            review() == "turned_away"
            spentUp() == said
            waitsOn() == "starter"
            values() == [new StepAnswers.WaitingValueAnswer("summary", "model")]
        }

        where:
        spent || said
        true  || Boolean.TRUE
        false || null
    }

    def "values the model would be sent too much of to review read as waiting on a person in its place, who is offered the review"() {
        given:
        holding(new ModelCatalog([
                DeployedModels.HELD.find(new ModelName("general")).orElseThrow(),
                new DeployedModel(new ModelName("small"), [], 20, 4.0G, 4_000, [])]))
        model.answering(cameBack(FITS))
        started()
        untilEnded(1)
        until("the review measured") {
            store.count("select count(*) from run_step_send_attempts where purpose = 'review'") == 1
        }
        executor.destroy()

        when:
        def row = summariseRow()

        then:
        with(row.where()) {
            kind() == "waiting_on_review"
            review() == "too_long"
            spentUp() == null
            waitsOn() == "review_at_gate"
            values() == [new StepAnswers.WaitingValueAnswer("summary", "review_at_gate")]
        }
        row.reviewer() == SMALL

        and:
        row.acts() == ["review"]
        row.withheld() == []
        row.wentIn()*.input() == ["text"]
    }

    def "values of a stopped run the model was not yet sent read as unsent, waiting on nobody"() {
        given:
        def gate = new Pause()
        model.pausing(gate, [], cameBack(FITS))
        started()
        gate.awaitReached()
        RunRows.stopped(store, RUN, CAT)
        gate.release()
        untilEnded(1)
        executor.destroy()

        when:
        def row = summariseRow()

        then:
        with(row.where()) {
            kind() == "waiting_on_review"
            review() == "unsent"
            waitsOn() == null
        }
        row.acts() == []
        row.withheld() == [new StepAnswers.WithheldAnswer("review", RefusalCode.RUN_STOPPED.code(), null, null)]
    }

    /**
     * What answering here shows is the production refused before that the model reviewing the answer is told of, and
     * the one a model asking again is told: the newest refused in words, a review gone wrong refusing in none.
     */
    def "answering after the model refused shows the newest production refused in words, passing over one refused in none"() {
        given:
        store.session.sql("""
                update workflow_steps set producer = 'person', producer_model = null, producer_mode = null, tries = 3
                 where workflow_step_id = ?::uuid""").params(SUMMARISE).update()
        reviews.each { model.answering(it) }
        started()
        reviews.eachWithIndex { outcome, int index ->
            StepRows.answered(store, SUMMARISE, CAT, "Read it again.", [(SUMMARY): '"A printer fire."'])
            engine.goOn(groupId(GROUP), runId())
            untilEnded(index + 1)
            until("the next try asked") { store.count("select count(*) from productions where ended_at is null") == 1 }
        }
        executor.destroy()

        when:
        def answering = steps.step(groupId(GROUP), runId(), summarise(), CAT_USER).answering()

        then:
        answering.number() == reviews.size() + 1
        answering.lastRefused()?.number() == shown

        where:
        reviews                                                                             || shown
        [cameBack(REFUSES)]                                                                 || 1
        [new CallOutcome.Errored("Timed out.")]                                             || null
        [cameBack(REFUSES), new CallOutcome.Errored("Timed out.")]                          || 1
    }

    def "a model's review that was lost reads as the model's, saying how it was lost and, where it did not fit, which way"() {
        given:
        model.answering(cameBack(FITS))
        model.answering(outcome)
        started()
        untilEnded(2)
        executor.destroy()

        when:
        def read = steps.step(groupId(GROUP), runId(), summarise(), ANN_USER)

        then:
        read.triesMade()*.review() == [new StepAnswers.ReviewAnswer(true, SMALL, true, lost, didNotFit)]
        read.triesMade()*.values().collect { it*.now() } == [["refused"]]

        where:
        outcome                                                    || lost          | didNotFit
        cameBack('{"decisions":{"summary":{"outcome":"refused"}}}') || "did_not_fit" | "words_missing"
        cameBack('{"decisions":{}}')                               || "did_not_fit" | "undecided"
        new CallOutcome.Errored("Timed out.")                      || "errored"     | null
    }

    def "a model's review that decided reads as the model's, and as lost in no way"() {
        given:
        model.answering(cameBack(FITS))
        model.answering(cameBack(ASSURES))
        started()
        untilEnded(2)
        executor.destroy()

        when:
        def read = steps.step(groupId(GROUP), runId(), summarise(), ANN_USER)

        then:
        read.triesMade()*.review() == [new StepAnswers.ReviewAnswer(true, SMALL, null, null, null)]
        read.step().where() == null
    }

    def "values a person reviews say nothing of any model's review"() {
        given:
        store.session.sql("update workflow_steps set reviewer_model = null, reviewer_mode = null where workflow_step_id = ?::uuid")
                .params(SUMMARISE).update()
        model.answering(cameBack(FITS))
        started()
        untilEnded(1)
        executor.destroy()

        when:
        def row = summariseRow()

        then:
        with(row.where()) {
            kind() == "waiting_on_review"
            review() == null
            spentUp() == null
            waitsOn() == "review_at_gate"
        }
    }
}
