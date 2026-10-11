package com.lilradish.lite.app.run

import static com.lilradish.lite.app.run.fixture.TicketWorkflow.ANN_USER
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.CAT
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.CAT_USER
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.GROUP
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.RUN
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARISE
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARY
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.VERSION
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.WORKFLOW
import static com.lilradish.lite.testutil.library.LibraryStore.groupId
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get

import com.lilradish.lite.app.codestep.CodeRuns
import com.lilradish.lite.app.group.GroupRoles
import com.lilradish.lite.app.run.fixture.StepRows
import com.lilradish.lite.app.run.fixture.TicketWorkflow
import com.lilradish.lite.app.search.SearchWays
import com.lilradish.lite.domain.failure.RefusalCode
import com.lilradish.lite.domain.identity.GroupPermission
import com.lilradish.lite.domain.inference.CallOutcome
import com.lilradish.lite.domain.inference.DidNotFitReason
import com.lilradish.lite.domain.inference.KeptAnswer
import com.lilradish.lite.domain.inference.TurnAway
import com.lilradish.lite.domain.inference.UnreadableResponseRecord
import com.lilradish.lite.domain.model.DeployedModel
import com.lilradish.lite.domain.model.ModelCatalog
import com.lilradish.lite.domain.model.ModelName
import com.lilradish.lite.domain.run.RunId
import com.lilradish.lite.domain.run.WorkflowStepId
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.codestep.CodeStepsHeld
import com.lilradish.lite.testutil.inference.Pause
import com.lilradish.lite.testutil.inference.ScriptedModelCalls
import com.lilradish.lite.testutil.library.DeployedModels
import com.lilradish.lite.testutil.library.LibraryStore
import com.lilradish.lite.testutil.run.RunRows
import com.lilradish.lite.web.ActAdmission
import com.lilradish.lite.web.CallerAdmission
import java.util.concurrent.TimeUnit
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper

class RunStepsReviewIntegrationSpec extends Specification {

    static final String FITS = '{"values":{"summary":"A printer fire."},"confidences":{}}'

    static final String ASSURES = '{"decisions":{"summary":{"outcome":"assured"}}}'

    static final String REFUSES = '{"decisions":{"summary":{"outcome":"refused","words":"Say it is on fire."}}}'

    static final StepAnswers.WhoAnswer SMALL = new StepAnswers.WhoAnswer("model", null, "small", null)

    @Shared
    @AutoCleanup
    TestPostgres server = TestPostgres.worker()

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
        LibraryStore.prepareBaseline(server)
    }

    def setup() {
        store = LibraryStore.forCase(server, "run_steps_review_" + (++databasesMade))
        model = new ScriptedModelCalls()
        tree = new RunTree(store.session)
        snapshots = new RunSnapshots(store.session, CodeStepsHeld.NONE, new SearchWays([]))
        writes = new EngineWrites(store.session)
        executor = EngineExecutors.of(store.database)
        ceilings = new CeilingReach(store.session, DeployedModels.HELD, writes)
        holding(DeployedModels.HELD)
        steps = new RunSteps(store.session, new GroupRoles(store.session), snapshots, store.transactionManager(), Deployments.DEEP_ENOUGH, new com.lilradish.lite.app.currency.GroupCurrencies(store.session, new org.springframework.transaction.support.TransactionTemplate(store.transactionManager()), new GroupRoles(store.session), com.lilradish.lite.testutil.library.DeployedModels.HELD), com.lilradish.lite.testutil.library.DeployedModels.HELD)
        TicketWorkflow.seed(store, 1)
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                update workflow_steps set producer = 'model', producer_model = 'general', producer_mode = 'ordinary',
                                          reviewer_model = 'small', reviewer_mode = 'ordinary'
                 where workflow_step_id = ?::uuid""").params(SUMMARISE).update()
    }

    def cleanup() {
        executor.destroy()
    }

    private void holding(ModelCatalog catalog) {
        def heldFiles = EngineWiring.files(store)
        engine = new RunEngine(store.session, store.transactions(), tree, snapshots, writes, executor,
                new EngineCalls(EngineWiring.withoutTools(), model, store.transactions(), tree, writes, ceilings, snapshots, heldFiles),
                new EngineCodes(new CodeRuns(CodeStepsHeld.NONE), store.transactions(), tree, snapshots, writes, executor, heldFiles),
                catalog, ceilings, new GroupRoles(store.session), Deployments.DEEP_ENOUGH, heldFiles)
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
        new CallOutcome.CameBack(answer, 321, 12, true, false, null, null)
    }

    def "a declined review reads the review call's two texts without attributing them to its production"() {
        given:
        model.answering(cameBack(FITS))
        model.answering(new CallOutcome.CameBack("Demo Sample review answer", 321, 12, true, false,
                DidNotFitReason.MODEL_DECLINED, "Demo Sample review refusal"))
        started()
        untilEnded(2)

        when:
        def detail = steps.step(groupId(GROUP), runId(), summarise(), ANN_USER)
        def material = steps.tryDetails(groupId(GROUP), runId(), summarise(), 1, ANN_USER)

        then:
        detail.triesMade().size() == 1
        !detail.triesMade()[0].declinedPresent()
        material.declined() == null
        detail.triesMade()[0].review().didNotFit() == "model_declined"
        detail.triesMade()[0].review().declinedPresent()
        material.review().declined() == new StepAnswers.DeclinedAnswer(
                new StepAnswers.DeclinedTextAnswer("Demo Sample review refusal", false, false),
                new StepAnswers.DeclinedTextAnswer("Demo Sample review answer", false, false), null)

        and: "the same persisted review tells a reader without inference permission no part of either text"
        def snapshot = snapshots.inTreeAsRead(groupId(GROUP), runId())
        def step = snapshot.run().steps().find { it.planned().id() == summarise() }
        def production = step.tries()[0]
        def withoutWords = new StepReading(snapshot, 1, true, [GroupPermission.READ_OWN_RUNS] as Set,
                Optional.empty(), [:], [:], [:], [:], Deployments.DEEP_ENOUGH)
                .withRawFacts([(production.id()): new StepTries.RawFactsRecord(false, false, true, true, false, true)])
        !withoutWords.step(summarise()).triesMade()[0].review().declinedPresent()
        def withheld = new RunTryDetails(store.session).read(runId(), step.planned().id(), production.number(), false, null)
        withheld.declined() == null
        withheld.review() == null
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
        row.stateActs() == []
        row.stateRefusals() == [new StepAnswers.StateRefusalAnswer("review", RefusalCode.REVIEW_NOT_A_PERSONS.code())]

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
                new DeployedModel(new ModelName("small"), [], 20, 4.0G, 4_000, [], [])]))
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
        row.stateActs() == ["review"]
        row.stateRefusals() == []
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
        row.stateActs() == []
        row.stateRefusals() == [new StepAnswers.StateRefusalAnswer("review", RefusalCode.RUN_STOPPED.code())]
    }

    def "answering after the model refused shows the newest production refused in words, passing over one refused in none"() {
        given:
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
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
        [new CallOutcome.Errored("Timed out.", null)]                                       || null
        [cameBack(REFUSES), new CallOutcome.Errored("Timed out.", null)]                    || 1
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
        read.triesMade()*.review() == [new StepAnswers.ReviewAnswer(true, SMALL, true, lost, didNotFit,
                false, false, outcome instanceof CallOutcome.CameBack)]
        read.triesMade()*.values().collect { it*.now() } == [["refused"]]

        where:
        outcome                                                    || lost          | didNotFit
        cameBack('{"decisions":{"summary":{"outcome":"refused"}}}') || "did_not_fit" | "words_missing"
        cameBack('{"decisions":{}}')                               || "did_not_fit" | "undecided"
        new CallOutcome.Errored("Timed out.", null)                || "errored"     | null
    }

    def "an unreadable review response belongs to the review call and only an inference reader sees it"() {
        given:
        model.answering(cameBack(FITS))
        model.answering(new CallOutcome.Errored(
                'The model service returned a response this system could not read.',
                new UnreadableResponseRecord(KeptAnswer.of('Demo Sample review envelope'), stopped)))
        started()
        untilEnded(2)
        executor.destroy()
        def mvc = MockMvcBuilders.standaloneSetup(new RunStepsController(steps)).build()

        when:
        def response = mvc.perform(get("/api/groups/${GROUP}/runs/${RUN}/steps/${SUMMARISE}")
                .requestAttr(CallerAdmission.class.name + '.caller', ANN_USER)
                .requestAttr(ActAdmission.class.name + '.group', groupId(GROUP))).andReturn().response
        def shown = JsonMapper.builder().build().readTree(response.contentAsString).path('triesMade').get(0)
        def materialResponse = mvc.perform(get("/api/groups/${GROUP}/runs/${RUN}/steps/${SUMMARISE}/tries/1")
                .requestAttr(CallerAdmission.class.name + '.caller', ANN_USER)
                .requestAttr(ActAdmission.class.name + '.group', groupId(GROUP))).andReturn().response
        def material = JsonMapper.builder().build().readTree(materialResponse.contentAsString)

        then:
        response.status == 200
        materialResponse.status == 200
        !shown.has('unreadableResponse')
        !shown.path('review').has('unreadableResponse')
        !shown.path('unreadableResponsePresent').asBoolean()
        shown.path('review').path('unreadableResponsePresent').asBoolean()
        !material.has('unreadableResponse')
        material.path('review').path('unreadableResponse').path('text').asText() == 'Demo Sample review envelope'
        !material.path('review').path('unreadableResponse').path('altered').asBoolean()
        !material.path('review').path('unreadableResponse').path('truncated').asBoolean()
        material.path('review').path('unreadableResponse').path('stopped').asBoolean() == stopped
        material.path('review').path('unreadableResponse').propertyNames().toSet() ==
                ['text', 'altered', 'truncated', 'stopped'] as Set
        store.count('''
                select count(*) from model_calls call join reviews review on review.model_call_id = call.model_call_id
                 where call.purpose = 'review' and call.unreadable_response = 'Demo Sample review envelope'
                ''') == 1
        store.count("select count(*) from model_calls where purpose = 'produce' and unreadable_response is not null") == 0

        and: "review evidence is withheld as completely as production evidence"
        def snapshot = snapshots.inTreeAsRead(groupId(GROUP), runId())
        def step = snapshot.run().steps().find { it.planned().id() == summarise() }
        def production = step.tries()[0]
        def withoutWords = new StepReading(snapshot, 1, true, [GroupPermission.READ_OWN_RUNS] as Set,
                Optional.empty(), [:], [:], [:], [:], Deployments.DEEP_ENOUGH)
                .withRawFacts([(production.id()): new StepTries.RawFactsRecord(false, false, true, false, true, false)])
        !withoutWords.step(summarise()).triesMade()[0].unreadableResponsePresent()
        !withoutWords.step(summarise()).triesMade()[0].review().unreadableResponsePresent()
        def withheld = new RunTryDetails(store.session).read(runId(), step.planned().id(), production.number(), false, null)
        withheld.unreadableResponse() == null
        withheld.review() == null

        where:
        stopped << [false, true]
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
        read.triesMade()*.review() == [new StepAnswers.ReviewAnswer(true, SMALL, null, null, null, false, false, true)]
        read.step().where() == null
    }

    def "values a person reviews say nothing of any model's review"() {
        given:
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
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
