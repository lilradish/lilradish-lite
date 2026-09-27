package org.lilradish.lite.app.run

import static org.lilradish.lite.app.run.fixture.TicketWorkflow.ANN
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.ANN_USER
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.CAT
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.CAT_USER
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.CONFIRM
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.GROUP
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.RUN
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARISE
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARISE_QUESTION
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARY
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.VERSION
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.WORKFLOW
import static org.lilradish.lite.testutil.library.LibraryStore.groupId

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.lilradish.lite.app.codestep.CodeRuns
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.run.fixture.StepRows
import org.lilradish.lite.app.run.fixture.TicketWorkflow
import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.inference.CallOutcome
import org.lilradish.lite.domain.inference.CallProgress
import org.lilradish.lite.domain.inference.CallRequest
import org.lilradish.lite.domain.inference.ModelCallPurpose
import org.lilradish.lite.domain.inference.ModelCalls
import org.lilradish.lite.domain.inference.TurnAway
import org.lilradish.lite.domain.model.DeployedModel
import org.lilradish.lite.domain.model.ModelCatalog
import org.lilradish.lite.domain.model.ModelMode
import org.lilradish.lite.domain.model.ModelName
import org.lilradish.lite.domain.run.ReviewOutcome
import org.lilradish.lite.domain.run.ReviewSending
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.domain.run.RunStepFailureReason
import org.lilradish.lite.domain.run.StepAct
import org.lilradish.lite.domain.run.StepFailure
import org.lilradish.lite.domain.run.StepGround
import org.lilradish.lite.domain.run.StepPosition
import org.lilradish.lite.domain.run.StepPositions
import org.lilradish.lite.domain.run.WaitsOn
import org.lilradish.lite.domain.run.WorkflowStepId
import org.lilradish.lite.testutil.codestep.CodeStepsHeld
import org.lilradish.lite.testutil.inference.Pause
import org.lilradish.lite.testutil.inference.ScriptedModelCalls
import org.lilradish.lite.testutil.library.DeployedModels
import org.lilradish.lite.testutil.library.LibraryStore
import org.lilradish.lite.testutil.run.RunRows
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.support.GenericApplicationContext
import org.springframework.transaction.support.TransactionSynchronizationManager
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper

/**
 * A step whose values a model reviews, on a real server running the real baseline, the model scripted: summarise
 * produced by one model and every value it gives back waiting on a review, which the model the step names is sent
 * on the engine's own threads once nothing else is, and each way that call can end landing as a review, a try spent,
 * or values left waiting.
 */
class ModelReviewIntegrationSpec extends Specification {

    static final String SYSTEM = RunRows.WORKFLOW_RUNNER

    static final String FITS = '{"values":{"summary":"A printer fire."},"confidences":{}}'

    static final String ASSURES = '{"decisions":{"summary":{"outcome":"assured"}}}'

    static final String REFUSES = '{"decisions":{"summary":{"outcome":"refused","words":"Say it is on fire."}}}'

    static final String REFUSES_IN_NO_WORDS = '{"decisions":{"summary":{"outcome":"refused"}}}'

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    ScriptedModelCalls model

    /** For each call made, whether it was made on an engine thread, and whether inside a transaction. */
    List<List<Boolean>> madeOn = [].asSynchronized()

    RunTree tree

    RunSnapshots snapshots

    EngineWrites writes

    EngineExecutor executor

    EngineCalls calls

    CeilingReach ceilings

    RunEngine engine

    StepActs acts

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, "model_review_" + (++databasesMade))
        model = new ScriptedModelCalls()
        tree = new RunTree(store.session)
        snapshots = new RunSnapshots(store.session, CodeStepsHeld.NONE)
        writes = new EngineWrites(store.session)
        executor = EngineExecutors.of(store.database)
        def recording = { CallRequest request, CallProgress progress ->
            madeOn << [Thread.currentThread() instanceof EngineThread,
                       TransactionSynchronizationManager.isActualTransactionActive()]
            model.call(request, progress)
        } as ModelCalls
        ceilings = new CeilingReach(store.session, DeployedModels.HELD, writes)
        calls = new EngineCalls(recording, store.transactions(), tree, writes, ceilings)
        holding(DeployedModels.HELD)
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
        engine = new RunEngine(store.session, store.transactions(), tree, snapshots, writes, executor, calls,
                new EngineCodes(new CodeRuns(CodeStepsHeld.NONE), store.transactions(), tree, snapshots, writes, executor),
                catalog, ceilings, new GroupRoles(store.session))
        acts = new StepActs(store.session, store.transactions(), new GroupRoles(store.session), tree, snapshots, writes,
                engine)
    }

    private void reviewedBy(String name, String mode) {
        store.session.sql("update workflow_steps set reviewer_model = ?, reviewer_mode = ? where workflow_step_id = ?::uuid")
                .params(name, mode, SUMMARISE).update()
    }

    private void summariseTries(int tries, boolean tells = false) {
        store.session.sql("update workflow_steps set tries = ?, tells_what_happened = ? where workflow_step_id = ?::uuid")
                .params(tries, tells, SUMMARISE).update()
    }

    private static RunId runId() {
        new RunId(UUID.fromString(RUN))
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

    /** Everything handed to the engine's threads done, those it handed over in turn too. */
    private void drained() {
        executor.destroy()
    }

    /** Each try of a step as its number, producer, who asked for it, and how it stands. */
    private List<String> triesOf(String step) {
        store.texts("""
                select try.try_number || ' ' || try.producer || ' ' || try.created_by || ' '
                       || case when try.ended_at is null then 'open' else coalesce(try.lost_reason::text, 'yielded') end
                  from productions try join run_steps held on held.run_step_id = try.run_step_id
                 where held.workflow_step_id = ?::uuid order by try.try_number
                """, step)
    }

    /** Each call as what it was for, how it ended, and the model and mode it was made to, in the order made. */
    private List<String> callRows() {
        store.texts("""
                select call.purpose || ' ' || coalesce(call.outcome::text, 'out') || ' ' || call.model || ' ' || call.mode
                  from model_calls call order by call.created_at, call.model_call_id
                """)
    }

    /** Each attempt to review as the model and mode it names, whether too long, who wrote it, and the try's number. */
    private List<String> reviewAttempts() {
        store.texts("""
                select attempt.model || ' ' || attempt.mode || ' ' || attempt.too_long || ' ' || attempt.created_by_kind
                       || ' ' || try.try_number
                  from run_step_send_attempts attempt join productions try on try.production_id = attempt.production_id
                 where attempt.purpose = 'review' order by attempt.created_at, attempt.run_step_send_attempt_id
                """)
    }

    /**
     * Each review as who wrote it, how its call ended, how it was lost and why it did not fit, whether what came back
     * was kept altered, and whether it names an attempt too long.
     */
    private List<String> reviewRows() {
        store.texts("""
                select review.created_by_kind || ' ' || coalesce(review.model_call_outcome::text, '-') || ' '
                       || coalesce(review.lost_reason::text, '-') || ' ' || coalesce(review.did_not_fit_reason::text, '-')
                       || ' ' || review.call_answer_altered || ' ' || (review.too_long_attempt_id is not null)
                  from reviews review order by review.created_at, review.review_id
                """)
    }

    private List<String> decisions() {
        store.texts("""
                select try.try_number || ' ' || field.name || ' ' || decision.outcome || ' '
                       || coalesce(decision.explanation, '-')
                  from review_decisions decision
                  join production_values held on held.production_value_id = decision.production_value_id
                  join productions try on try.production_id = held.production_id
                  join declaration_fields field on field.declaration_field_id = held.declaration_field_id
                 order by try.try_number, field.position
                """)
    }

    private List<String> holds() {
        store.texts("select reason::text from run_step_holds order by created_at")
    }

    private List<String> failures() {
        store.texts("select reason || ' ' || purpose || ': ' || detail from run_step_failures order by created_at")
    }

    private StepPosition summarising() {
        def read = store.transactions().execute { snapshots.asRead(groupId(GROUP), runId()) }
        StepPositions.of(read, read.step(new WorkflowStepId(UUID.fromString(SUMMARISE))).orElseThrow())
    }

    private WaitsOn summariseWaitsOn() {
        def read = store.transactions().execute { snapshots.asRead(groupId(GROUP), runId()) }
        StepPositions.waitsOn(read, summarising())
    }

    private static CallOutcome.CameBack cameBack(String answer, boolean cutOff = false) {
        new CallOutcome.CameBack(answer, 321, 12, true, cutOff)
    }

    def "values waiting on the model the step names are written down as sent to it on the engine's thread, what went in and what came out, to the model and mode it names for reviewing"() {
        given:
        reviewedBy("general", "research")
        def gate = new Pause()
        model.answering(cameBack(FITS))
        model.pausing(gate, [], cameBack(ASSURES))

        when:
        started()
        gate.awaitReached()

        then: "the attempt and its call written before the call was made, as the system's, of the try reviewed"
        reviewAttempts() == ["general research false system 1"]
        callRows() == ["produce came_back general ordinary", "review out general research"]
        madeOn == [[true, false], [true, false]]

        and: "to the reviewing model and mode, not the producing ones, sending what the attempt holds"
        model.requests[1].purpose() == ModelCallPurpose.REVIEW
        model.requests[1].model().name().value() == "general"
        model.requests[1].mode() == new ModelMode("research")
        model.requests[0].mode() == null
        model.requests[1].sent().user() ==
                store.texts("select payload from run_step_send_attempts where purpose = 'review'")[0]

        and: "what went in and every value that came out, the waiting one to decide, and nothing more"
        def sent = JsonMapper.builder().build().readValue(model.requests[1].sent().user(), Map)
        sent.takes == [text: "The printer is on fire."]
        sent.answer == [summary: "A printer fire."]
        sent.deciding == ["summary"]
        !sent.containsKey("refused")
        !model.requests[1].sent().user().contains("confidences")

        and: "nothing decided, held or failed while it is out, the values waiting on the model"
        reviewRows() == []
        holds() == []
        failures() == []
        with(summarising() as StepPosition.AwaitingReview) {
            sending() == ReviewSending.OUT
            values()*.on() == [WaitsOn.MODEL]
        }

        cleanup:
        gate.release()
    }

    /** What went into a person's production is what answering it showed them; here nothing was refused before it. */
    def "a production a person answered is sent to the model to review as any is, nothing saying who produced it"() {
        given:
        store.session.sql("""
                update workflow_steps set producer = 'person', producer_model = null, producer_mode = null
                 where workflow_step_id = ?::uuid""").params(SUMMARISE).update()
        model.answering(cameBack(ASSURES))
        started()
        StepRows.answered(store, SUMMARISE, CAT, "Read it twice.", [(SUMMARY): '"A printer fire."'])

        when:
        engine.goOn(groupId(GROUP), runId())
        untilEnded(1)

        then:
        callRows() == ["review came_back small ordinary"]
        def user = model.requests[0].sent().user()
        JsonMapper.builder().build().readValue(user, Map).answer == [summary: "A printer fire."]
        !user.contains(CAT)
        !user.contains("000e03")
        !user.contains("Read it twice.")

        and:
        reviewRows() == ["system came_back - - false false"]
        decisions() == ["1 summary assured -"]
    }

    def "a review assuring every value lets it stand, one decision each, and the run goes on by itself"() {
        given:
        model.answering(cameBack(FITS))
        model.answering(cameBack(ASSURES))

        when:
        started()
        untilEnded(2)
        drained()

        then:
        reviewRows() == ["system came_back - - false false"]
        decisions() == ["1 summary assured -"]
        summarising() == new StepPosition.Done()

        and:
        triesOf(SUMMARISE) == ["1 model ${SYSTEM} yielded" as String]
        triesOf(CONFIRM) == ["1 person ${SYSTEM} open" as String]
        holds() == []
        failures() == []
        model.requests.size() == 2
    }

    def "a value the model refused in words, with tries left, has its next try made by itself, the model told the refusal only where the step tells"() {
        given:
        summariseTries(2, tells)
        model.answering(cameBack(FITS))
        model.answering(cameBack(REFUSES))
        model.answering(cameBack('{"values":{"summary":"A printer on fire."},"confidences":{}}'))
        model.answering(cameBack(ASSURES))

        when:
        started()
        untilEnded(4)
        drained()

        then:
        decisions() == ["1 summary refused Say it is on fire.", "2 summary assured -"]
        triesOf(SUMMARISE) == ["1 model ${SYSTEM} yielded" as String, "2 model ${SYSTEM} yielded" as String]
        callRows() == ["produce came_back general ordinary", "review came_back small ordinary",
                       "produce came_back general ordinary", "review came_back small ordinary"]

        and: "the second asking, and the review of what it gave back, told what was refused exactly where the step tells"
        model.requests[2].sent().user().contains('"refused"') == tells
        model.requests[2].sent().user().contains("Say it is on fire.") == tells
        model.requests[3].sent().user().contains('"refused"') == tells
        model.requests[3].sent().user().contains("Say it is on fire.") == tells

        and:
        store.count("select count(*) from productions where created_by_kind = 'person'") == 0
        holds() == []

        where:
        tells << [true, false]
    }

    /**
     * A person answering after a refusal was shown the refused production, whatever the step tells its model, so the
     * review of their answer carries it; one refused in no words, as by a review gone wrong, is no refusal and is shown
     * nobody.
     */
    def "a person's production answered after the model refused one is reviewed with the refusal shown them, in its words"() {
        given:
        store.session.sql("""
                update workflow_steps set producer = 'person', producer_model = null, producer_mode = null
                 where workflow_step_id = ?::uuid""").params(SUMMARISE).update()
        summariseTries(2)
        model.answering(first)
        model.answering(cameBack(ASSURES))
        started()
        StepRows.answered(store, SUMMARISE, CAT, "Read it twice.", [(SUMMARY): '"A printer fire."'])
        engine.goOn(groupId(GROUP), runId())
        untilEnded(1)
        until("try 2 asked of the person") { triesOf(SUMMARISE).size() == 2 }

        when:
        StepRows.answered(store, SUMMARISE, CAT, "Said it burns.", [(SUMMARY): '"A printer on fire."'])
        engine.goOn(groupId(GROUP), runId())
        untilEnded(2)
        drained()

        then:
        def sent = JsonMapper.builder().build().readValue(model.requests[1].sent().user(), Map)
        sent.refused == refused
        sent.answer == [summary: "A printer on fire."]
        !model.requests[1].sent().user().contains("Read it twice.")

        where:
        first                                  || refused
        cameBack(REFUSES)                      || [values: [summary: "A printer fire."], words: [summary: "Say it is on fire."]]
        new CallOutcome.Errored("Timed out.")  || null
    }

    /** A refusal saying nothing of why, or anything else that does not fit, decides nothing. */
    def "a review whose answer does not fit is lost saying why, which spends the try, and decides nothing"() {
        given:
        model.answering(cameBack(FITS))
        model.answering(outcome)

        when:
        started()
        untilEnded(2)
        drained()

        then:
        reviewRows() == ["system came_back did_not_fit ${reason} ${altered} false" as String]
        store.texts("select answer_altered::text from model_calls where purpose = 'review'") == [altered as String]
        decisions() == []

        and:
        (summarising() as StepPosition.Failed).why() == new StepFailure.TriesSpent(1, 1)
        triesOf(SUMMARISE) == ["1 model ${SYSTEM} yielded" as String]
        triesOf(CONFIRM) == []
        model.requests.size() == 2

        where:
        outcome                             || reason                | altered
        cameBack(REFUSES_IN_NO_WORDS)       || "words_missing"       | false
        cameBack('{"decisions":{}}')        || "undecided"           | false
        cameBack("Assured.")                || "not_the_shape"       | false
        cameBack(ASSURES, true)             || "cut_off"             | false
        cameBack(ASSURES + " " * 8_388_608) || "not_kept_as_it_came" | true
    }

    def "a review whose call went wrong is lost as gone wrong, which spends the try, and decides nothing"() {
        given:
        model.answering(cameBack(FITS))
        model.answering(new CallOutcome.Errored("Timed out."))

        when:
        started()
        untilEnded(2)
        drained()

        then:
        reviewRows() == ["system errored errored - false false"]
        store.texts("select error_detail from model_calls where purpose = 'review'") == ["Timed out."]
        decisions() == []
        (summarising() as StepPosition.Failed).why() == new StepFailure.TriesSpent(1, 1)

        and:
        holds() == []
        triesOf(CONFIRM) == []
    }

    def "a review turned away for the last time holds nothing and spends nothing, the values waiting on whoever started the run"() {
        given:
        model.answering(cameBack(FITS))
        model.answering(new CallOutcome.TurnedAway(new TurnAway("Busy.", spentUp)))

        when:
        started()
        untilEnded(2)
        drained()

        then:
        callRows() == ["produce came_back general ordinary", "review turned_away small ordinary"]
        store.texts("select said || ' ' || spent_up from model_call_turnaways") == ["Busy. ${spentUp}" as String]
        with(summarising() as StepPosition.AwaitingReview) {
            sending() == ReviewSending.TURNED_AWAY
            values()*.on() == [WaitsOn.MODEL]
        }
        summariseWaitsOn() == WaitsOn.STARTER

        and:
        holds() == []
        reviewRows() == []
        triesOf(SUMMARISE) == ["1 model ${SYSTEM} yielded" as String]
        model.requests.size() == 2

        where:
        spentUp << [false, true]
    }

    /** Opened again, it still waits: a turnaway is tried again only by somebody pressing for it. */
    def "a review turned away while its run is stopped is not sent again, holding nothing, nor once the run is opened again"() {
        given:
        def gate = new Pause()
        model.answering(cameBack(FITS))
        model.pausing(gate, [new TurnAway("Busy.", false)], cameBack(ASSURES))
        started()
        gate.awaitReached()

        when:
        RunRows.stopped(store, RUN, CAT)
        gate.release()
        untilEnded(2)
        until("the end was gone on from") { executor.@pool.threadPoolExecutor.activeCount == 0 }
        store.session.sql("update run_stops set opened_again_at = now(), opened_again_by = ?::uuid").params(ANN).update()
        engine.goOn(groupId(GROUP), runId())
        drained()

        then:
        callRows() == ["produce came_back general ordinary", "review turned_away small ordinary"]
        store.texts("select coalesce(resent_at::text, 'not sent again') from model_call_turnaways") == ["not sent again"]
        model.events.count { it == ScriptedModelCalls.SENT } == 2
        reviewAttempts() == ["small ordinary false system 1"]

        and:
        holds() == []
        reviewRows() == []
        (summarising() as StepPosition.AwaitingReview).sending() == ReviewSending.TURNED_AWAY
    }

    /** Written down as sent only on the engine's thread, so a hand-over that meets stopping there leaves nothing. */
    def "a review handed over and taken up only once this system is stopping writes nothing and sends nothing"() {
        given:
        personAnswered()
        def gate = occupied()
        engine.goOn(groupId(GROUP), runId())
        def own = new GenericApplicationContext()
        executor.setApplicationContext(own)
        executor.onApplicationEvent(new ContextClosedEvent(own))

        when:
        gate.countDown()
        drained()

        then:
        reviewAttempts() == []
        callRows() == []
        model.requests == []
        (summarising() as StepPosition.AwaitingReview).sending() == ReviewSending.UNSENT
    }

    def "a review handed over twice is written down as sent once and sent once"() {
        given:
        personAnswered()
        model.answering(cameBack(ASSURES))
        def gate = occupied()
        engine.goOn(groupId(GROUP), runId())
        engine.goOn(groupId(GROUP), runId())

        when:
        gate.countDown()
        untilEnded(1)
        settled()

        then:
        reviewAttempts() == ["small ordinary false system 1"]
        callRows() == ["review came_back small ordinary"]
        model.requests.size() == 1
        reviewRows() == ["system came_back - - false false"]
    }

    def "a model named to review that the deployment does not hold fails the step on the try it was to review, and nothing is sent"() {
        given:
        reviewedBy("large", "ordinary")
        model.answering(cameBack(FITS))

        when:
        started()
        untilEnded(1)
        until("the review failed") { store.count("select count(*) from run_step_failures") == 1 }
        drained()

        then:
        failures() == ["model_not_deployed review: Model large is not held here."]
        store.count("""
                select count(*) from run_step_failures failure join productions try on try.production_id = failure.production_id
                 where try.try_number = 1""") == 1
        (summarising() as StepPosition.Failed).why() ==
                new StepFailure.Recorded(RunStepFailureReason.MODEL_NOT_DEPLOYED, ModelCallPurpose.REVIEW)

        and:
        reviewAttempts() == []
        reviewRows() == []
        model.requests.size() == 1
    }

    /** One the model takes a few units of, so its envelope alone is past them; producing goes to another. */
    def "a review too long for its model is not sent, and its values wait on a person, nothing held"() {
        given:
        tooLongToReview()

        when:
        started()
        untilEnded(1)
        until("the review measured") { !reviewAttempts().isEmpty() }
        drained()

        then:
        reviewAttempts() == ["small ordinary true system 1"]
        store.count("select count(*) from run_step_send_attempts where purpose = 'review' and payload is not null") == 1
        with(summarising() as StepPosition.AwaitingReview) {
            sending() == ReviewSending.TOO_LONG
            values()*.on() == [WaitsOn.REVIEW_AT_GATE]
        }

        and:
        callRows() == ["produce came_back general ordinary"]
        model.requests.size() == 1
        holds() == []
        reviewRows() == []
    }

    def "a person reviews in the model's place what was too long for it, naming what was too long"() {
        given:
        tooLongToReview()
        started()
        untilEnded(1)
        until("the review measured") { !reviewAttempts().isEmpty() }

        when:
        acts.review(groupId(GROUP), runId(), new WorkflowStepId(UUID.fromString(SUMMARISE)), 1, ANN_USER,
                [summary: new StepActs.ReviewedRecord(ReviewOutcome.ASSURED, null)])
        drained()

        then:
        reviewRows() == ["person - - - false true"]
        store.texts("select created_by::text from reviews") == [ANN]
        decisions() == ["1 summary assured -"]
        summarising() == new StepPosition.Done()

        and:
        triesOf(CONFIRM) == ["1 person ${SYSTEM} open" as String]
        model.requests.size() == 1
    }

    def "a review is not sent while its run is stopped, and is sent once it is opened again"() {
        given:
        def gate = new Pause()
        model.pausing(gate, [], cameBack(FITS))
        model.answering(cameBack(ASSURES))
        started()
        gate.awaitReached()
        RunRows.stopped(store, RUN, CAT)
        gate.release()
        untilEnded(1)
        until("the end was gone on from") { executor.@pool.threadPoolExecutor.activeCount == 0 }
        def attemptsWhileStopped = reviewAttempts()

        when:
        store.session.sql("update run_stops set opened_again_at = now(), opened_again_by = ?::uuid").params(ANN).update()
        engine.goOn(groupId(GROUP), runId())
        untilEnded(2)

        then: "nothing sent while stopped"
        attemptsWhileStopped == []

        and: "sent once it is open, and decided"
        reviewAttempts() == ["small ordinary false system 1"]
        decisions() == ["1 summary assured -"]
        model.requests*.purpose() == [ModelCallPurpose.PRODUCE, ModelCallPurpose.REVIEW]
    }

    def "a review whose run's ceiling is reached is not sent, and the run is stopped by its ceiling"() {
        given:
        store.session.sql("update workflow_versions set ceiling = 300 where entry_version_id = ?::uuid").params(VERSION)
                .update()
        model.answering(cameBack(FITS))

        when:
        started()
        untilEnded(1)
        until("the run stopped") { store.count("select count(*) from run_stops") == 1 }
        drained()

        then:
        store.texts("""
                select stop.created_by_kind || ' ' || (stop.ceiling_run_id = stop.run_id) from run_stops stop""") ==
                ["system true"]

        and:
        reviewAttempts() == []
        model.requests.size() == 1
        (summarising() as StepPosition.AwaitingReview).sending() == ReviewSending.UNSENT
        summariseWaitsOn() == null
    }

    def "a review planned once this system is stopping is left unsent, nothing of it written"() {
        given:
        def gate = new Pause()
        model.pausing(gate, [], cameBack(FITS))
        started()
        gate.awaitReached()
        def own = new GenericApplicationContext()
        executor.setApplicationContext(own)
        executor.onApplicationEvent(new ContextClosedEvent(own))

        when:
        gate.release()
        untilEnded(1)
        drained()

        then:
        reviewAttempts() == []
        model.requests.size() == 1
        with(summarising() as StepPosition.AwaitingReview) {
            sending() == ReviewSending.UNSENT
            values()*.on() == [WaitsOn.MODEL]
        }
    }

    /** Pressed by whoever may start a run: the review is sent again as the presser's attempt, built afresh. */
    def "values the model turned away are sent to it again once Try sending is pressed, as the presser's attempt, and decided"() {
        given:
        turnedAwayOnce()
        model.answering(cameBack(ASSURES))

        when:
        pressed()
        untilEnded(3)
        drained()

        then:
        reviewAttempts() == ["small ordinary false system 1", "small ordinary false person 1"]
        store.texts("select created_by::text from run_step_send_attempts where created_by_kind = 'person'") == [CAT]
        callRows() == ["produce came_back general ordinary", "review turned_away small ordinary",
                       "review came_back small ordinary"]
        model.requests[2].sent().user() == model.requests[1].sent().user()
        decisions() == ["1 summary assured -"]
        summarising() == new StepPosition.Done()

        and:
        store.count("select count(*) from run_step_send_attempts where answers_failure_id is not null") == 0
        failures() == []
        holds() == []
        triesOf(SUMMARISE) == ["1 model ${SYSTEM} yielded" as String]
    }

    /** Measured, never said: what is too long is the system's attempt whoever pressed, and a person reviews instead. */
    def "values turned away and too long for the model once pressed are left to a person, the too-long attempt the system's, nothing sent"() {
        given:
        turnedAwayOnce()
        holding(new ModelCatalog([
                DeployedModels.HELD.find(new ModelName("general")).orElseThrow(),
                new DeployedModel(new ModelName("small"), [], 20, 4.0G, 4_000, [])]))

        when:
        pressed()
        until("the review measured again") { reviewAttempts().size() == 2 }
        drained()

        then:
        reviewAttempts() == ["small ordinary false system 1", "small ordinary true system 1"]
        with(summarising() as StepPosition.AwaitingReview) {
            sending() == ReviewSending.TOO_LONG
            values()*.on() == [WaitsOn.REVIEW_AT_GATE]
        }
        sendingRefused() == RefusalCode.TRY_SENDING_NOT_OFFERED

        and:
        callRows() == ["produce came_back general ordinary", "review turned_away small ordinary"]
        model.requests.size() == 2
        failures() == []
        reviewRows() == []
    }

    def "a review failed on its model not held is sent once a deploy holds it and Try sending is pressed, the attempt answering that failure"() {
        given:
        holding(new ModelCatalog([DeployedModels.HELD.find(new ModelName("general")).orElseThrow()]))
        model.answering(cameBack(FITS))
        started()
        untilEnded(1)
        until("the review failed") { store.count("select count(*) from run_step_failures") == 1 }
        settled()
        holding(DeployedModels.HELD)
        model.answering(cameBack(ASSURES))

        when:
        pressed()
        untilEnded(2)
        drained()

        then:
        failures() == ["model_not_deployed review: Model small is not held here."]
        store.texts("""
                select failure.reason::text || ' ' || failure.purpose::text from run_step_send_attempts attempt
                  join run_step_failures failure on failure.run_step_failure_id = attempt.answers_failure_id""") ==
                ["model_not_deployed review"]
        reviewAttempts() == ["small ordinary false person 1"]
        decisions() == ["1 summary assured -"]
        summarising() == new StepPosition.Done()

        and:
        model.requests*.purpose() == [ModelCallPurpose.PRODUCE, ModelCallPurpose.REVIEW]
        holds() == []
    }

    /** Reviewing what was produced is not running what the step runs, so a stop on that holds no review back. */
    def "values the model turned away are sent again when pressed while what the step runs is stopped, no stop read beside them"() {
        given:
        turnedAwayOnce()
        store.stopped(SUMMARISE_QUESTION, ANN)
        model.answering(cameBack(ASSURES))

        expect: "offered, and read with no stop beside it, as nothing of it is held by one"
        summariseRow().where().review() == "turned_away"
        summariseRow().where().stopped() == null
        summariseRow().acts().contains(StepAct.TRY_SENDING.published())
        summariseRow().withheld().every { it.act() != StepAct.TRY_SENDING.published() }

        when:
        pressed()
        untilEnded(3)
        drained()

        then:
        reviewAttempts() == ["small ordinary false system 1", "small ordinary false person 1"]
        decisions() == ["1 summary assured -"]
        model.requests*.purpose() == [ModelCallPurpose.PRODUCE, ModelCallPurpose.REVIEW, ModelCallPurpose.REVIEW]
        failures() == []
        holds() == []
    }

    /** Each press names the attempt turned away, so the one taken up after the first was sent finds another. */
    def "two presses on one turnaway taken before the engine's thread comes to either make one attempt and one call"() {
        given:
        turnedAwayOnce()
        model.answering(cameBack(ASSURES))
        def gate = occupied()
        pressed(CAT_USER)
        pressed(ANN_USER)

        when:
        gate.countDown()
        untilEnded(3)
        settled()

        then:
        reviewAttempts() == ["small ordinary false system 1", "small ordinary false person 1"]
        model.requests.size() == 3
        decisions() == ["1 summary assured -"]
        failures() == []

        and: "made once, by whichever press the engine's thread came to first"
        store.texts("select created_by::text from run_step_send_attempts where created_by_kind = 'person'") in
                [[CAT], [ANN]]
    }

    /** The second press named a turnaway; once the first wrote a failure the values no longer stand turned away. */
    def "two presses on one turnaway, the first finding the reviewer not held, write one failure and the second nothing"() {
        given:
        turnedAwayOnce()
        holding(new ModelCatalog([DeployedModels.HELD.find(new ModelName("general")).orElseThrow()]))
        def gate = occupied()
        pressed(CAT_USER)
        pressed(ANN_USER)

        when:
        gate.countDown()
        settled()

        then:
        failures() == ["model_not_deployed review: Model small is not held here."]
        reviewAttempts() == ["small ordinary false system 1"]
        store.count("select count(*) from run_step_send_attempts where answers_failure_id is not null") == 0
        model.requests.size() == 2
        (summarising() as StepPosition.Failed).why() ==
                new StepFailure.Recorded(RunStepFailureReason.MODEL_NOT_DEPLOYED, ModelCallPurpose.REVIEW)
    }

    /** As on a hold: the engine's thread asks again whether the presser may, and one who no longer may pressed nothing. */
    def "a press on a review turned away whose presser is removed from the group before the engine's thread takes it up writes and sends nothing, and it is still offered"() {
        given:
        turnedAwayOnce()
        model.answering(cameBack(ASSURES))
        def gate = occupied()
        pressed(CAT_USER)
        catRemoved()
        def before = store.contents()

        when:
        gate.countDown()
        settled()

        then:
        store.contents() == before
        reviewAttempts() == ["small ordinary false system 1"]
        model.requests.size() == 2
        (summarising() as StepPosition.AwaitingReview).sending() == ReviewSending.TURNED_AWAY
        sendingRefused() == null
    }

    /** Cat no longer a member of the group, as a steward removing her does. */
    private void catRemoved() {
        store.session.sql("""
                update group_members set removed_at = now(), removed_by = ?::uuid, removal = 'removed_from_group'
                 where group_id = ?::uuid and subject_id = ?::uuid and removed_at is null""").params(ANN, GROUP, CAT)
                .update()
    }

    /** Summarise produced by the model and its review turned away for the last time, the engine's threads idle. */
    private void turnedAwayOnce() {
        model.answering(cameBack(FITS))
        model.answering(new CallOutcome.TurnedAway(new TurnAway("Busy.", false)))
        started()
        untilEnded(2)
        settled()
    }

    /**
     * Everything handed to the engine's threads done, the threads left running for what is pressed next. Not
     * {@link #drained}: that stops the engine first, and a press it then takes up sends nothing for stopping.
     */
    private void settled() {
        def pool = executor.@pool.threadPoolExecutor
        until("the engine's threads idle") { pool.completedTaskCount == pool.taskCount }
    }

    private void pressed(def presser = CAT_USER) {
        acts.trySending(groupId(GROUP), runId(), new WorkflowStepId(UUID.fromString(SUMMARISE)), presser)
    }

    /** What pressing Try sending on summarise now would be refused with, none where it is offered. */
    private RefusalCode sendingRefused() {
        def read = store.transactions().execute { snapshots.asRead(groupId(GROUP), runId()) }
        def step = read.step(new WorkflowStepId(UUID.fromString(SUMMARISE))).orElseThrow()
        StepAct.TRY_SENDING.refusal(StepGround.of(read, step, StepPositions.of(read, step), null))
    }

    /** Summarise as Cat, who started the run and may start another, reads it. */
    private StepAnswers.StepRowAnswer summariseRow() {
        new RunSteps(store.session, new GroupRoles(store.session), snapshots, store.transactionManager())
                .steps(groupId(GROUP), runId(), CAT_USER).steps()[0]
    }

    /** Summarise made a person's, asked and answered, its values waiting on the model and nothing yet sent for them. */
    private void personAnswered() {
        store.session.sql("""
                update workflow_steps set producer = 'person', producer_model = null, producer_mode = null
                 where workflow_step_id = ?::uuid""").params(SUMMARISE).update()
        started()
        StepRows.answered(store, SUMMARISE, CAT, "Read it twice.", [(SUMMARY): '"A printer fire."'])
    }

    /** Both of the engine's threads kept busy until the gate returned is opened, so what is handed over meanwhile waits. */
    private CountDownLatch occupied() {
        def gate = new CountDownLatch(1)
        def busy = new CountDownLatch(2)
        2.times {
            executor.execute {
                busy.countDown()
                gate.await(60, TimeUnit.SECONDS)
            }
        }
        assert busy.await(10, TimeUnit.SECONDS)
        gate
    }

    private void tooLongToReview() {
        holding(new ModelCatalog([
                DeployedModels.HELD.find(new ModelName("general")).orElseThrow(),
                new DeployedModel(new ModelName("small"), [], 20, 4.0G, 4_000, [])]))
        model.answering(cameBack(FITS))
    }
}
