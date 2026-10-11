package com.lilradish.lite.app.run

import static com.lilradish.lite.app.run.fixture.TicketWorkflow.ANN
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.ANN_USER
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.CAT
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.CAT_USER
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.CONFIRM
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.GROUP
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.RUN
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARISE
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARISE_QUESTION
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARISE_VERSION
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARY
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.VERSION
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.WORKFLOW
import static com.lilradish.lite.testutil.library.LibraryStore.groupId

import ch.qos.logback.classic.Logger
import com.lilradish.lite.app.codestep.CodeRuns
import com.lilradish.lite.app.group.GroupRoles
import com.lilradish.lite.app.run.fixture.StepRows
import com.lilradish.lite.app.run.fixture.TicketWorkflow
import com.lilradish.lite.app.search.SearchWays
import com.lilradish.lite.domain.failure.RefusalCode
import com.lilradish.lite.domain.inference.CallOutcome
import com.lilradish.lite.domain.inference.CallProgress
import com.lilradish.lite.domain.inference.CallRequest
import com.lilradish.lite.domain.inference.DidNotFitReason
import com.lilradish.lite.domain.inference.KeptAnswer
import com.lilradish.lite.domain.inference.ModelCallPurpose
import com.lilradish.lite.domain.inference.ModelCalls
import com.lilradish.lite.domain.inference.TurnAway
import com.lilradish.lite.domain.inference.UnreadableResponseRecord
import com.lilradish.lite.domain.model.DeployedModel
import com.lilradish.lite.domain.model.ModelCatalog
import com.lilradish.lite.domain.model.ModelMode
import com.lilradish.lite.domain.model.ModelName
import com.lilradish.lite.domain.run.Ceiling
import com.lilradish.lite.domain.run.ReviewOutcome
import com.lilradish.lite.domain.run.ReviewSending
import com.lilradish.lite.domain.run.RunId
import com.lilradish.lite.domain.run.RunStepFailureReason
import com.lilradish.lite.domain.run.StepAct
import com.lilradish.lite.domain.run.StepFailure
import com.lilradish.lite.domain.run.StepGround
import com.lilradish.lite.domain.run.StepPosition
import com.lilradish.lite.domain.run.StepPositions
import com.lilradish.lite.domain.run.WaitsOn
import com.lilradish.lite.domain.run.WorkflowStepId
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.SnapshottingAppender
import com.lilradish.lite.testutil.codestep.CodeStepsHeld
import com.lilradish.lite.testutil.inference.Pause
import com.lilradish.lite.testutil.inference.ScriptedModelCalls
import com.lilradish.lite.testutil.library.DeployedModels
import com.lilradish.lite.testutil.library.LibraryStore
import com.lilradish.lite.testutil.run.RunRows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.support.GenericApplicationContext
import org.springframework.transaction.support.TransactionSynchronizationManager
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper

class ModelReviewIntegrationSpec extends Specification {

    // DB-SPECIFIC: SQL fixtures and predicates below input/compare PostgreSQL native enum labels.
    // SQL text || non-string renders enum, UUID, numeric and boolean operands in the review
    // evidence assertions.

    static final String SYSTEM = RunRows.WORKFLOW_RUNNER

    static final String FITS = '{"values":{"summary":"A printer fire."},"confidences":{}}'

    static final String ASSURES = '{"decisions":{"summary":{"outcome":"assured"}}}'

    static final String REFUSES = '{"decisions":{"summary":{"outcome":"refused","words":"Say it is on fire."}}}'

    static final String REFUSES_IN_NO_WORDS = '{"decisions":{"summary":{"outcome":"refused"}}}'

    @Shared
    @AutoCleanup
    TestPostgres server = TestPostgres.worker()

    @Shared
    int databasesMade = 0

    LibraryStore store

    ScriptedModelCalls model

    List<List<Boolean>> madeOn = [].asSynchronized()

    RunTree tree

    RunSnapshots snapshots

    EngineWrites writes

    com.lilradish.lite.app.files.RunFiles heldFiles

    EngineExecutor executor

    EngineTaskBarrier tasks

    EngineCalls calls

    CeilingReach ceilings

    RunEngine engine

    StepActs acts

    def setupSpec() {
        LibraryStore.prepareBaseline(server)
    }

    def setup() {
        store = LibraryStore.forCase(server, "model_review_" + (++databasesMade))
        model = new ScriptedModelCalls()
        tree = new RunTree(store.session)
        snapshots = new RunSnapshots(store.session, CodeStepsHeld.NONE, new SearchWays([]))
        writes = new EngineWrites(store.session)
        heldFiles = EngineWiring.files(store)
        executor = EngineExecutors.of(store.database)
        tasks = EngineTaskBarrier.attach(executor)
        def recording = { CallRequest request, CallProgress progress ->
            madeOn << [Thread.currentThread() instanceof EngineThread,
                       TransactionSynchronizationManager.isActualTransactionActive()]
            model.call(request, progress)
        } as ModelCalls
        ceilings = new CeilingReach(store.session, DeployedModels.HELD, writes)
        calls = new EngineCalls(EngineWiring.withoutTools(), recording, store.transactions(), tree, writes, ceilings, snapshots, heldFiles)
        holding(DeployedModels.HELD)
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
        engine = new RunEngine(store.session, store.transactions(), tree, snapshots, writes, executor, calls,
                new EngineCodes(new CodeRuns(CodeStepsHeld.NONE), store.transactions(), tree, snapshots, writes, executor, heldFiles),
                catalog, ceilings, new GroupRoles(store.session), Deployments.DEEP_ENOUGH, heldFiles)
        acts = new StepActs(store.session, store.transactions(), new GroupRoles(store.session), tree, snapshots, writes,
                engine, heldFiles, EngineWiring.fileDeployment(), catalog)
    }

    private void reviewedBy(String name, String mode) {
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("update workflow_steps set reviewer_model = ?, reviewer_mode = ? where workflow_step_id = ?::uuid")
                .params(name, mode, SUMMARISE).update()
    }

    private void summariseTries(int tries, boolean tells = false) {
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("update workflow_steps set tries = ?, tells_what_happened = ? where workflow_step_id = ?::uuid")
                .params(tries, tells, SUMMARISE).update()
    }

    private static RunId runId() {
        new RunId(UUID.fromString(RUN))
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

    // Drain work already handed over, including any further work it submits.
    private void drained() {
        executor.destroy()
    }

    private List<String> triesOf(String step) {
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        store.texts("""
                select try.try_number || ' ' || try.producer || ' ' || try.created_by || ' '
                       || case when try.ended_at is null then 'open' else coalesce(try.lost_reason::text, 'yielded') end
                  from productions try join run_steps held on held.run_step_id = try.run_step_id
                 where held.workflow_step_id = ?::uuid order by try.try_number
                """, step)
    }

    private List<String> callRows() {
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("""
                select call.purpose || ' ' || coalesce(call.outcome::text, 'out') || ' ' || call.model || ' ' || call.mode
                  from model_calls call order by call.created_at, call.model_call_id
                """)
    }

    private List<String> reviewAttempts() {
        store.texts("""
                select attempt.model || ' ' || attempt.mode || ' ' || attempt.too_long || ' ' || attempt.created_by_kind
                       || ' ' || try.try_number
                  from run_step_send_attempts attempt join productions try on try.production_id = attempt.production_id
                 where attempt.purpose = 'review' order by attempt.created_at, attempt.run_step_send_attempt_id
                """)
    }

    private List<String> reviewRows() {
        // DB-SPECIFIC: PostgreSQL ::text casts.
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
        // DB-SPECIFIC: PostgreSQL ::text casts.
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
        new CallOutcome.CameBack(answer, 321, 12, true, cutOff, null, null)
    }

    def "review keeps the same raw instruction and adds the reference explanation only where it names an input"() {
        given:
        reviewedBy("general", "research")
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        assert store.session.sql("update question_versions set instruction = ? where entry_version_id = ?::uuid")
                .params(instruction, SUMMARISE_VERSION).update() == 1
        model.answering(cameBack(FITS))
        model.answering(cameBack(ASSURES))

        when:
        started()
        untilEnded(2)
        executor.destroy()

        then:
        model.requests.size() == 2
        def sent = model.requests[1].sent()
        def document = JsonMapper.builder().build().readValue(sent.user(), Map)
        document.instruction == instruction
        document.takes == [text: "The printer is on fire."]
        document.answer == [summary: "A printer fire."]
        sent.system().contains('In "instruction", {{name}} stands for the input called name in "takes"') == explained
        sent.user() == store.texts("select payload from run_step_send_attempts where purpose = 'review'")[0]

        where:
        instruction                                  || explained
        "Read {{text}}, keep \\{{literal}} literal."   || true
        "Read the input."                            || false
        "An escaped \\{{text}} and unfinished {{text" || false
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

    def "a production a person answered is sent to the model to review as any is, nothing saying who produced it"() {
        given:
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
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

    def "a person's production answered after the model refused one is reviewed with the refusal shown them, in its words"() {
        given:
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
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
        new CallOutcome.Errored("Timed out.", null) || null
    }

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
        // DB-SPECIFIC: PostgreSQL ::text casts.
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

    def "a model declining review keeps its answer and words separately without a decision"() {
        given:
        def answer = "Demo Sample answer" + (answerAltered ? Character.toString(0) : "")
        def words = "Demo Sample decline" + (wordsAltered ? Character.toString(0) : "")
        model.answering(cameBack(FITS))
        model.answering(new CallOutcome.CameBack(answer, 321, 12, true, false,
                DidNotFitReason.MODEL_DECLINED, words))

        when:
        started()
        untilEnded(2)
        drained()

        then:
        reviewRows() == ["system came_back did_not_fit model_declined ${answerAltered} false" as String]
        decisions() == []
        (summarising() as StepPosition.Failed).why() == new StepFailure.TriesSpent(1, 1)
        store.texts("select answer from model_calls where purpose = 'review'") ==
                [answer.replace(Character.toString(0), "\uFFFD")]
        store.texts("select declined_words from model_calls where purpose = 'review'") ==
                [words.replace(Character.toString(0), "\uFFFD")]
        store.texts("""
                select answer_altered || ' ' || answer_truncated || ' ' || declined_words_altered || ' ' ||
                       declined_words_truncated || ' ' || came_back_count
                  from model_calls where purpose = 'review'""") ==
                ["${answerAltered} false ${wordsAltered} false 12" as String]

        where:
        answerAltered | wordsAltered
        false         | true
        true          | false
    }

    def "a review whose call went wrong is lost as gone wrong, which spends the try, and decides nothing"() {
        given:
        model.answering(cameBack(FITS))
        model.answering(new CallOutcome.Errored("Timed out.", null))

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

    def "an unreadable review response is kept on its review call without a returned count or decision"() {
        given:
        model.answering(cameBack(FITS))
        model.answering(new CallOutcome.Errored(
                'The model service returned a response this system could not read.',
                new UnreadableResponseRecord(KeptAnswer.captured('Demo Sample review raw', true, true), stopped)))

        when:
        started()
        untilEnded(2)
        drained()

        then:
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts('''
                select call.purpose::text || '|' || call.outcome::text || '|' || call.unreadable_response || '|' ||
                       call.unreadable_response_altered || '|' || call.unreadable_response_truncated || '|' ||
                       (call.came_back_count is null) || '|' || (call.sent_count is not null)
                  from model_calls call where call.purpose = 'review'
                ''') == ['review|errored|Demo Sample review raw|true|true|true|true']
        store.texts("select unreadable_response_stopped::text from model_calls where purpose = 'review'") ==
                [stopped.toString()]
        store.count("select count(*) from model_calls where purpose = 'produce' and unreadable_response is not null") == 0
        reviewRows() == ['system errored errored - false false']
        decisions() == []

        where:
        stopped << [false, true]
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
        tasks.awaitDrained()
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
        store.session.sql("update run_stops set opened_again_at = now(), opened_again_by = ?::uuid").params(ANN).update()
        engine.goOn(groupId(GROUP), runId())
        drained()

        then:
        callRows() == ["produce came_back general ordinary", "review turned_away small ordinary"]
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select coalesce(resent_at::text, 'not sent again') from model_call_turnaways") == ["not sent again"]
        model.events.count { it == ScriptedModelCalls.SENT } == 2
        reviewAttempts() == ["small ordinary false system 1"]

        and:
        holds() == []
        reviewRows() == []
        (summarising() as StepPosition.AwaitingReview).sending() == ReviewSending.TURNED_AWAY
    }

    def "stopped by hand, or by its ceiling lowered under what it spent, and opened again during a review turnaway wait, a run preserves its values until Try sending is pressed"() {
        given:
        def gate = new Pause()
        def logged = new SnapshottingAppender()
        logged.start()
        def callsLogger = LoggerFactory.getLogger(EngineCalls) as Logger
        callsLogger.addAppender(logged)
        waitingAfterTurnaway(gate, lateProgress)
        model.answering(cameBack(FITS))
        model.answering([new TurnAway("Busy.", false)], cameBack(ASSURES))
        started()
        gate.awaitReached()
        assert store.count("select count(*) from model_calls where outcome is null") == 1
        assert store.count("select count(*) from model_call_turnaways where resent_at is null") == 1
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def original = store.texts("select model_call_id::text from model_calls where purpose = 'review'")[0]
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def production = store.texts("select production_id::text from productions")[0]
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def value = store.texts("select production_value_id::text from production_values")[0]
        def changes = new RunChanges(store.session, store.transactions(), new GroupRoles(store.session), tree, snapshots, engine)

        when:
        if (atCeiling) {
            changes.changeCeiling(groupId(GROUP), runId(), runId(), new Ceiling(1), ANN_USER)
        } else {
            changes.stop(groupId(GROUP), runId(), ANN_USER)
        }
        def stopsMade = RunRows.stops(store)
        if (atCeiling) {
            changes.changeCeiling(groupId(GROUP), runId(), runId(), null, ANN_USER)
        }

        def endedAtStop = callRows()
        def heldAtStop = holds()
        def reviewsAtStop = reviewRows()
        def decisionsAtStop = decisions()
        changes.openAgain(groupId(GROUP), runId(), ANN_USER)
        gate.release()
        settled()

        then:
        stopsMade == [atCeiling ? "system ${RUN} 0" as String : "person - 0"]
        model.events == [ScriptedModelCalls.SENT, ScriptedModelCalls.SENT, new TurnAway("Busy.", false)]
        endedAtStop == ["produce came_back general ordinary", "review turned_away small ordinary"]
        heldAtStop == []
        reviewsAtStop == []
        decisionsAtStop == []
        model.requests.size() == 2
        logged.list.findAll { it.formattedMessage.contains(original) }.every {
            !it.formattedMessage.contains("writing what happened to it failed") && it.throwableProxy == null
        }
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select model_call_id::text from model_calls where purpose = 'review'") == [original]
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select production_id::text from productions") == [production]
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select production_value_id::text from production_values") == [value]
        reviewAttempts() == ["small ordinary false system 1"]
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select coalesce(resent_at::text, 'not sent again') from model_call_turnaways") == ["not sent again"]
        RunBudget.spentAsRead(store.session, runId(), true).spent() == 333
        with(summarising() as StepPosition.AwaitingReview) {
            sending() == ReviewSending.TURNED_AWAY
            values()*.on() == [WaitsOn.MODEL]
        }
        sendingRefused() == null
        holds() == []
        reviewRows() == []
        decisions() == []
        failures() == []

        when:
        model.answering(cameBack(ASSURES))
        pressed()
        untilEnded(3)
        settled()

        then:
        model.requests.size() == 3
        model.events.count { it == ScriptedModelCalls.SENT } == 3
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select production_id::text from productions where producer = 'model'") == [production]
        triesOf(CONFIRM) == ["1 person ${SYSTEM} open" as String]
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select production_value_id::text from production_values") == [value]
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        store.texts("select outcome::text from model_calls where model_call_id = ?::uuid", original) == ["turned_away"]
        reviewAttempts() == ["small ordinary false system 1", "small ordinary false person 1"]
        reviewRows() == ["system came_back - - false false"]
        decisions() == ["1 summary assured -"]
        holds() == []
        store.count("select count(*) from model_call_turnaways where resent_at is not null") == 0
        RunBudget.spentAsRead(store.session, runId(), true).spent() == 666

        cleanup:
        gate.release()
        callsLogger.detachAppender(logged)

        where:
        atCeiling | lateProgress
        false     | false
        true      | false
        false     | true
    }

    def "stopped by hand, or by its ceiling lowered under what it spent, and opened again while a review is already out, a run records its original decision and cost"() {
        given:
        def gate = new Pause()
        model.answering(cameBack(FITS))
        if (resentAlready) {
            waitingAfterResend(gate)
            model.answering([new TurnAway("Busy.", false)], cameBack(ASSURES))
        } else {
            model.pausing(gate, [], cameBack(ASSURES))
        }
        started()
        gate.awaitReached()
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def original = store.texts("select model_call_id::text from model_calls where purpose = 'review'")[0]
        def changes = new RunChanges(store.session, store.transactions(), new GroupRoles(store.session), tree, snapshots, engine)

        when:
        if (atCeiling) {
            changes.changeCeiling(groupId(GROUP), runId(), runId(), new Ceiling(1), ANN_USER)
        } else {
            changes.stop(groupId(GROUP), runId(), ANN_USER)
        }
        def stopsMade = RunRows.stops(store)
        if (atCeiling) {
            changes.changeCeiling(groupId(GROUP), runId(), runId(), null, ANN_USER)
        }
        changes.openAgain(groupId(GROUP), runId(), ANN_USER)
        gate.release()
        untilEnded(2)
        settled()

        then:
        stopsMade == [atCeiling ? "system ${RUN} 0" as String : "person - 0"]
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select model_call_id::text from model_calls where purpose = 'review'") == [original]
        callRows() == ["produce came_back general ordinary", "review came_back small ordinary"]
        reviewRows() == ["system came_back - - false false"]
        decisions() == ["1 summary assured -"]
        holds() == []
        model.events == (resentAlready
                ? [ScriptedModelCalls.SENT, ScriptedModelCalls.SENT, new TurnAway("Busy.", false), ScriptedModelCalls.SENT]
                : [ScriptedModelCalls.SENT, ScriptedModelCalls.SENT])
        store.count("select count(*) from model_call_turnaways") == (resentAlready ? 1 : 0)
        store.count("select count(*) from model_call_turnaways where resent_at is not null") == (resentAlready ? 1 : 0)
        RunBudget.spentAsRead(store.session, runId(), true).spent() == 666

        cleanup:
        gate.release()

        where:
        atCeiling | resentAlready
        false     | false
        false     | true
        true      | false
        true      | true
    }


    // Record sending on the engine thread; if stopping wins before handover, write nothing.
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
        // DB-SPECIFIC: PostgreSQL ::text casts.
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
        tasks.awaitDrained()
        def attemptsWhileStopped = reviewAttempts()

        when:
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
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
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
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
        // DB-SPECIFIC: PostgreSQL ::text casts.
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

    def "values turned away and too long for the model once pressed are left to a person, the too-long attempt the system's, nothing sent"() {
        given:
        turnedAwayOnce()
        holding(new ModelCatalog([
                DeployedModels.HELD.find(new ModelName("general")).orElseThrow(),
                new DeployedModel(new ModelName("small"), [], 20, 4.0G, 4_000, [], [])]))

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
        // DB-SPECIFIC: PostgreSQL ::text casts.
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

    def "values the model turned away are sent again when pressed while what the step runs is stopped, no stop read beside them"() {
        given:
        turnedAwayOnce()
        store.stopped(SUMMARISE_QUESTION, ANN)
        model.answering(cameBack(ASSURES))

        expect: "offered, and read with no stop beside it, as nothing of it is held by one"
        summariseRow().where().review() == "turned_away"
        summariseRow().where().stopped() == null
        summariseRow().stateActs().contains(StepAct.TRY_SENDING.published())
        summariseRow().stateRefusals().every { it.act() != StepAct.TRY_SENDING.published() }

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
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select created_by::text from run_step_send_attempts where created_by_kind = 'person'") in
                [[CAT], [ANN]]
    }

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

    private void catRemoved() {
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
        store.session.sql("""
                update group_members set removed_at = now(), removed_by = ?::uuid, removal = 'removed_from_group',
                       removed_position = (select coalesce(max(closed.removed_position), 0) + 1
                                             from group_members closed where closed.group_id = group_members.group_id)
                 where group_id = ?::uuid and subject_id = ?::uuid and removed_at is null""").params(ANN, GROUP, CAT)
                .update()
    }

    private void turnedAwayOnce() {
        model.answering(cameBack(FITS))
        model.answering(new CallOutcome.TurnedAway(new TurnAway("Busy.", false)))
        started()
        untilEnded(2)
        settled()
    }

    // Keep the threads running while draining submitted work; stopping first would suppress the next press.
    private void settled() {
        tasks.awaitDrained()
    }

    private void pressed(def presser = CAT_USER) {
        acts.trySending(groupId(GROUP), runId(), new WorkflowStepId(UUID.fromString(SUMMARISE)), presser)
    }

    private RefusalCode sendingRefused() {
        def read = store.transactions().execute { snapshots.asRead(groupId(GROUP), runId()) }
        def step = read.step(new WorkflowStepId(UUID.fromString(SUMMARISE))).orElseThrow()
        StepAct.TRY_SENDING.refusal(StepGround.of(read, step, StepPositions.of(read, step), null))
    }

    private StepAnswers.StepRowAnswer summariseRow() {
        new RunSteps(store.session, new GroupRoles(store.session), snapshots, store.transactionManager(), Deployments.DEEP_ENOUGH, new com.lilradish.lite.app.currency.GroupCurrencies(store.session, new org.springframework.transaction.support.TransactionTemplate(store.transactionManager()), new GroupRoles(store.session), com.lilradish.lite.testutil.library.DeployedModels.HELD), com.lilradish.lite.testutil.library.DeployedModels.HELD)
                .steps(groupId(GROUP), runId(), CAT_USER).steps()[0]
    }

    private void personAnswered() {
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                update workflow_steps set producer = 'person', producer_model = null, producer_mode = null
                 where workflow_step_id = ?::uuid""").params(SUMMARISE).update()
        started()
        StepRows.answered(store, SUMMARISE, CAT, "Read it twice.", [(SUMMARY): '"A printer fire."'])
    }

    // Occupy both engine threads until the gate opens so submitted work cannot run early.
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
                new DeployedModel(new ModelName("small"), [], 20, 4.0G, 4_000, [], [])]))
        model.answering(cameBack(FITS))
    }
    private void waitingAfterTurnaway(Pause gate, boolean lateProgress) {
        def waiting = { CallRequest request, CallProgress progress ->
            def outcome = model.call(request, [aboutToSend: { progress.aboutToSend() },
                                              turnedAway: { TurnAway away ->
                                                  progress.turnedAway(away)
                                                  gate.hold()
                                                  if (lateProgress) {
                                                      progress.turnedAway(away)
                                                  }
                                              },
                                              mayResend: { progress.mayResend() }] as CallProgress)
            lateProgress ? cameBack(request.purpose() == ModelCallPurpose.PRODUCE ? FITS : ASSURES) : outcome
        } as ModelCalls
        calls = new EngineCalls(EngineWiring.withoutTools(), waiting, store.transactions(), tree, writes, ceilings, snapshots, heldFiles)
        holding(DeployedModels.HELD)
    }

    private void waitingAfterResend(Pause gate) {
        def waiting = { CallRequest request, CallProgress progress ->
            model.call(request, [aboutToSend: { progress.aboutToSend() },
                                 turnedAway: { TurnAway away -> progress.turnedAway(away) },
                                 mayResend: {
                                     def allowed = progress.mayResend()
                                     if (allowed) {
                                         gate.hold()
                                     }
                                     allowed
                                 }] as CallProgress)
        } as ModelCalls
        calls = new EngineCalls(EngineWiring.withoutTools(), waiting, store.transactions(), tree, writes, ceilings, snapshots, heldFiles)
        holding(DeployedModels.HELD)
    }

}
