package com.lilradish.lite.app.run

import static com.lilradish.lite.app.run.fixture.TicketWorkflow.ANN_USER
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.CAT
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.CONFIRM
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.GROUP
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.RUN
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARISE
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
import com.lilradish.lite.domain.declaration.AskedField
import com.lilradish.lite.domain.declaration.FieldKind
import com.lilradish.lite.domain.declaration.FieldName
import com.lilradish.lite.domain.inference.CallOutcome
import com.lilradish.lite.domain.inference.CallProgress
import com.lilradish.lite.domain.inference.CallRequest
import com.lilradish.lite.domain.inference.DidNotFitReason
import com.lilradish.lite.domain.inference.Envelope
import com.lilradish.lite.domain.inference.KeptAnswer
import com.lilradish.lite.domain.inference.ModelCallPurpose
import com.lilradish.lite.domain.inference.ModelCalls
import com.lilradish.lite.domain.inference.TurnAway
import com.lilradish.lite.domain.inference.UnreadableResponseRecord
import com.lilradish.lite.domain.model.DeployedModel
import com.lilradish.lite.domain.model.ModelCatalog
import com.lilradish.lite.domain.model.ModelName
import com.lilradish.lite.domain.model.SentText
import com.lilradish.lite.domain.run.Ceiling
import com.lilradish.lite.domain.run.ModelCallId
import com.lilradish.lite.domain.run.ProductionId
import com.lilradish.lite.domain.run.ReviewOutcome
import com.lilradish.lite.domain.run.RunId
import com.lilradish.lite.domain.run.RunStepSendAttemptId
import com.lilradish.lite.domain.run.WorkflowStepId
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.SnapshottingAppender
import com.lilradish.lite.testutil.codestep.CodeStepsHeld
import com.lilradish.lite.testutil.inference.Pause
import com.lilradish.lite.testutil.inference.ScriptedModelCalls
import com.lilradish.lite.testutil.library.DeployedModels
import com.lilradish.lite.testutil.library.LibraryStore
import com.lilradish.lite.testutil.run.EngineCodeTasks
import com.lilradish.lite.testutil.run.RunRows
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.springframework.transaction.support.TransactionSynchronizationManager
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper

class ModelProductionIntegrationSpec extends Specification {

    // DB-SPECIFIC: SQL fixtures and predicates below input/compare PostgreSQL native enum labels.
    // SQL text || non-string renders enum, UUID, numeric and boolean operands in the evidence
    // assertions.

    static final String SYSTEM = RunRows.WORKFLOW_RUNNER

    static final String FITS = '{"values":{"summary":"A printer fire."},"confidences":{}}'

    static final String UNKEEPABLE = '{"values":{"summary":"A \uD800fire."},"confidences":{}}'

    static final String CUT_SHORT = '{"values":{"summ'

    static final Logger CALLS_LOGGER = LoggerFactory.getLogger(EngineCalls) as Logger

    static final Logger ENGINE_LOGGER = LoggerFactory.getLogger(RunEngine) as Logger

    @Shared
    @AutoCleanup
    TestPostgres server = TestPostgres.worker()

    @Shared
    int databasesMade = 0

    LibraryStore store

    ScriptedModelCalls model

    List<List<Boolean>> madeOn = [].asSynchronized()

    RunTree tree

    EngineWrites writes

    RunSnapshots snapshots

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
        store = LibraryStore.forCase(server, "model_production_" + (++databasesMade),
                specificationContext.currentFeature.testTags*.value.contains("postgres-schema-changing"))
        model = new ScriptedModelCalls()
        tree = new RunTree(store.session)
        writes = new EngineWrites(store.session)
        snapshots = new RunSnapshots(store.session, CodeStepsHeld.NONE, new SearchWays([]))
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
        TicketWorkflow.seed(store)
        producedByModel("general", "ordinary")
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

    private void producedByModel(String name, String mode, boolean tells = false) {
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                update workflow_steps set producer = 'model', producer_model = ?, producer_mode = ?,
                                          tells_what_happened = ?
                 where workflow_step_id = ?::uuid""").params(name, mode, tells, SUMMARISE).update()
    }

    private static RunId runId() {
        new RunId(UUID.fromString(RUN))
    }

    // Write the run, lock its tree, and plan the first step in one transaction.
    private void started(String startedWith = TicketWorkflow.STARTED_WITH) {
        store.transactions().executeWithoutResult {
            StepRows.run(store, RUN, GROUP, 1, WORKFLOW, VERSION, CAT, startedWith)
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

    private List<String> triesOf(String step) {
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        store.texts("""
                select coalesce(try.try_number::text, '-') || ' ' || try.producer || ' ' || try.created_by || ' '
                       || case when try.ended_at is null then 'open'
                               else coalesce(try.lost_reason::text || ' ' || coalesce(try.did_not_fit_reason::text, '-'),
                                             'yielded') end
                  from productions try join run_steps held on held.run_step_id = try.run_step_id
                 where held.workflow_step_id = ?::uuid order by try.try_number
                """, step)
    }

    private List<String> callRows() {
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("""
                select coalesce(try.try_number::text, '-') || ' ' || coalesce(call.outcome::text, 'out') || ' ' || call.sent_count || ' '
                       || coalesce(call.came_back_count::text, '-') || ' ' || call.counted_by_model || ' '
                       || call.answer_altered || ' ' || call.mode || ' ' || call.envelope_version
                  from model_calls call join productions try on try.production_id = call.production_id
                 order by try.try_number
                """)
    }

    private List<String> holds() {
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("""
                select hold.reason || ' ' || (hold.run_step_send_attempt_id = attempt.run_step_send_attempt_id)
                       || ' ' || coalesce(hold.released_at::text, 'held')
                  from run_step_holds hold
                  left join run_step_send_attempts attempt on attempt.run_step_id = hold.run_step_id
                """)
    }

    private List<String> failures() {
        store.texts("select reason || ': ' || detail from run_step_failures order by created_at")
    }

    private List<String> values() {
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("""
                select value::text || ' ' || coalesce(confidence::text, '-') || ' ' || needs_review
                  from production_values order by production_value_id
                """)
    }

    private long measured(int index) {
        def request = model.requests[index]
        request.model().unitsOf(request.sent().characters())
    }

    private static CallOutcome.CameBack cameBack(String answer, boolean cutOff = false) {
        new CallOutcome.CameBack(answer, 321, 12, true, cutOff, null, null)
    }

    private static PendingCall pendingOf(String sentText) {
        def general = DeployedModels.HELD.find(new ModelName("general")).orElseThrow()
        new PendingCall(groupId(GROUP), runId(), runId(), new ProductionId(UUID.randomUUID()),
                new RunStepSendAttemptId(UUID.randomUUID()), new ModelCallId(UUID.randomUUID()),
                new CallRequest(general, null, ModelCallPurpose.PRODUCE, SentText.measure("the envelope", sentText)),
                new PendingCall.Producing(
                        [new AskedField(new FieldName("summary"), FieldKind.TEXT, 1000, null, null, [], true, false)],
                        [UUID.fromString(SUMMARY)]))
    }

    def "a model's one-line text with a break spends a try and keeps the answer without values"() {
        given:
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("update declaration_fields set text_lines = 'one' where declaration_field_id = ?::uuid")
                .params(SUMMARY).update()
        def answer = '{"values":{"summary":"Demo Sample\\n"},"confidences":{}}'
        model.answering(cameBack(answer))
        model.answering(new CallOutcome.Errored("Demo Sample", null))

        when:
        started()
        untilEnded(2)
        executor.destroy()

        then:
        triesOf(SUMMARISE) == ["1 model ${SYSTEM} did_not_fit line_break" as String,
                               "2 model ${SYSTEM} errored -" as String]
        store.texts("select answer from model_calls where answer is not null") == [answer]
        values() == []
        triesOf(CONFIRM) == []
    }

    def "a model's step reached is asked of its first try by the system, and its call written down as sent on the engine's thread before anything is sent"() {
        given:
        def gate = new Pause()
        model.pausing(gate, [], cameBack(FITS))

        when:
        started()
        gate.awaitReached()

        then: "the try, its attempt holding what is sent, and the call, all written before the call was made"
        triesOf(SUMMARISE) == ["1 model ${SYSTEM} open" as String]
        // DB-SPECIFIC: PostgreSQL ::jsonb casts, JSON/JSONB ->/->>.
        store.texts("""
                select attempt.purpose || ' ' || attempt.model || ' ' || attempt.mode || ' ' || attempt.too_long || ' '
                       || attempt.created_by_kind || ' ' || (attempt.payload::jsonb -> 'takes' ->> 'text')
                  from run_step_send_attempts attempt""") == ["produce general ordinary false system The printer is on fire."]
        callRows() == ["1 out ${measured(0)} - false false ordinary ${Envelope.VERSION}" as String]

        and: "made once, on an engine thread and outside any transaction"
        madeOn == [[true, false]]

        and: "what is sent is what the attempt holds, to the model and mode the step names"
        model.requests.size() == 1
        model.requests[0].sent().user() == store.texts("select payload from run_step_send_attempts")[0]
        model.requests[0].model().name().value() == "general"
        model.requests[0].mode() == null
        model.requests[0].purpose() == ModelCallPurpose.PRODUCE

        and: "nothing held, failed or produced while it is out"
        holds() == []
        failures() == []
        values() == []

        cleanup:
        gate.release()
    }

    def "a start rolled back hands nothing over, so no call is made, and nothing it planned stays"() {
        given:
        model.answering(cameBack(FITS))

        when:
        store.transactions().executeWithoutResult { status ->
            StepRows.run(store, RUN, GROUP, 1, WORKFLOW, VERSION, CAT, TicketWorkflow.STARTED_WITH)
            engine.planStarted(tree.lock(groupId(GROUP), runId()).orElseThrow())
            status.setRollbackOnly()
        }
        executor.destroy()

        then:
        model.requests == []
        madeOn == []

        and:
        store.count("select count(*) from model_calls") == 0
        store.count("select count(*) from productions") == 0
    }

    def "a model or mode the deployment does not hold fails the try, naming it, and nothing is sent"() {
        given:
        producedByModel(name, mode)

        when: "written on the engine's thread, as its call would have been"
        started()
        until("the try failed") { store.count("select count(*) from run_step_failures") == 1 }

        then:
        failures() == ["model_not_deployed: ${said}" as String]
        triesOf(SUMMARISE) == ["- model ${SYSTEM} open" as String]

        and:
        store.count("select count(*) from run_step_send_attempts") == 0
        store.count("select count(*) from model_calls") == 0
        holds() == []
        model.requests == []

        where:
        name     | mode       || said
        "absent" | "ordinary" || "Model absent is not held here."
        "absent" | "research" || "Model absent in mode research is not held here."
        "general"| "deep"     || "Model general in mode deep is not held here."
    }

    def "a try too long to send is held on its attempt, which keeps what would have been sent, and nothing is sent"() {
        given:
        holding(new ModelCatalog([new DeployedModel(new ModelName("general"), [], 600, 4.0G, 4000, [], [])]))
        def ticket = "x" * 3900

        when: "written on the engine's thread, as its call would have been"
        started('{"ticket": "' + ticket + '"}')
        until("the step held") { store.count("select count(*) from run_step_holds") == 1 }

        then:
        holds() == ["too_long true held"]
        // DB-SPECIFIC: PostgreSQL ::jsonb casts, JSON/JSONB ->/->>.
        store.texts("""
                select attempt.too_long || ' ' || (attempt.payload::jsonb -> 'takes' ->> 'text' = ?)
                  from run_step_send_attempts attempt""", ticket) == ["true true"]

        and:
        store.count("select count(*) from model_calls") == 0
        failures() == []
        triesOf(SUMMARISE) == ["- model ${SYSTEM} open" as String]
        model.requests == []
    }

    def "a try whose part that cannot be cut is itself too long fails the step rather than holding it"() {
        given:
        holding(new ModelCatalog([new DeployedModel(new ModelName("general"), [], 50, 4.0G, 4000, [], [])]))

        when: "written on the engine's thread, as its call would have been"
        started()
        until("the try failed") { store.count("select count(*) from run_step_failures") == 1 }

        then:
        failures().size() == 1
        failures()[0].startsWith("uncuttable_length: With nothing it takes, what would be sent is still ")
        failures()[0].endsWith(" units, more than the 50 model general takes.")

        and: "no attempt kept, since nothing would read it, and nothing held or sent"
        store.count("select count(*) from run_step_send_attempts") == 0
        holds() == []
        store.count("select count(*) from model_calls") == 0
        model.requests == []
    }

    def "a call is refused off the engine's threads, and sends nothing"() {
        when:
        calls.callAndEnd(pendingOf("the ticket"))

        then:
        def refused = thrown(IllegalStateException)
        refused.message.endsWith(" was to be made off the run engine")

        and:
        model.requests == []
    }

    def "a call is refused inside a transaction, even on the engine's threads, and sends nothing"() {
        given:
        def outcome = new CompletableFuture<Throwable>()

        when:
        executor.execute {
            try {
                store.transactions().executeWithoutResult {
                    calls.callAndEnd(pendingOf("the ticket"))
                }
                outcome.complete(null)
            } catch (Throwable thrown) {
                outcome.complete(thrown)
            }
        }

        then:
        def refused = outcome.get(10, TimeUnit.SECONDS)
        refused instanceof IllegalStateException
        refused.message.endsWith(" was to be made inside a transaction it would hold open")

        and:
        model.requests == []
    }

    // Calls need a separate pool because code may occupy its thread indefinitely.
    def "a call is refused on the engine's code threads, and sends nothing"() {
        given:
        def outcome = new CompletableFuture<Throwable>()

        when:
        EngineCodeTasks.run(executor) {
            try {
                calls.callAndEnd(pendingOf("the ticket"))
                outcome.complete(null)
            } catch (Throwable thrown) {
                outcome.complete(thrown)
            }
        }

        then:
        def refused = outcome.get(10, TimeUnit.SECONDS)
        refused instanceof IllegalStateException
        refused.message.endsWith(" was to be made off the run engine")

        and:
        model.requests == []
        madeOn == []
    }

    def "production sends the approved instruction byte for byte and inputs beside it, explaining real references only"() {
        given:
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        assert store.session.sql("update question_versions set instruction = ? where entry_version_id = ?::uuid")
                .params(instruction, SUMMARISE_VERSION).update() == 1
        model.answering(cameBack(FITS))

        when:
        started()
        untilEnded(1)
        executor.destroy()

        then:
        model.requests.size() == 1
        def sent = model.requests[0].sent()
        def document = JsonMapper.builder().build().readValue(sent.user(), Map)
        document.instruction == instruction
        document.takes == [text: "The printer is on fire."]
        !document.instruction.contains("The printer is on fire.")
        sent.system().contains('In "instruction", {{name}} stands for the input called name in "takes"') == explained
        sent.user() == store.texts("select payload from run_step_send_attempts")[0]
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select envelope_version::text from model_calls") == ["${Envelope.VERSION}" as String]

        where:
        instruction                                        || explained
        "Read {{text}} twice, then \\{{literal}}.\n"          || true
        "Read it without a reference."                      || false
        "Show \\{{text}} literally, unfinished {{text"       || false
    }

    def "an answer that fits lands as the try's values, and the run goes on as they stand"() {
        given:
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("update declaration_fields set standing = 'above_confidence', standing_threshold = 80 where declaration_field_id = ?::uuid")
                .params(SUMMARY).update()
        model.answering(cameBack("""{"values":{"summary":"A printer fire."},"confidences":{"summary":${sure}}}"""))

        when: "drained: what follows the end is planned after it, in the same task"
        started()
        untilEnded(1)
        executor.destroy()

        then:
        triesOf(SUMMARISE) == ["1 model ${SYSTEM} yielded" as String]
        callRows() == ["1 came_back 321 12 true false ordinary ${Envelope.VERSION}" as String]
        values() == ["\"A printer fire.\" ${sure} ${waits}" as String]
        store.texts("select answer from model_calls") == ["""{"values":{"summary":"A printer fire."},"confidences":{"summary":${sure}}}""" as String]

        and: "confirm asked exactly where the summary stands"
        triesOf(CONFIRM) == asked.collect { "1 person ${SYSTEM} open" as String }
        holds() == []
        failures() == []

        where:
        sure || waits | asked
        90   || false | [1]
        50   || true  | []
    }

    def "an answer that does not fit spends the try, keeping why and what came back, and the next try is sent"() {
        given:
        model.answering(outcome)
        model.answering(new CallOutcome.Errored("Gone.", null))

        when:
        started()
        untilEnded(2)

        then:
        triesOf(SUMMARISE) == ["1 model ${SYSTEM} did_not_fit ${reason}" as String,
                               "2 model ${SYSTEM} errored -" as String]
        store.texts("""
                select call.answer_altered || ' ' || call.answer_truncated || ' ' ||
                       try.call_answer_altered || ' ' || length(call.answer)
                  from model_calls call join productions try on try.model_call_id = call.model_call_id
                 where try.try_number = 1""") == ["${altered} ${truncated} ${altered} ${kept}" as String]

        and: "nothing given back, and the step failed with its tries spent"
        values() == []
        triesOf(CONFIRM) == []
        model.requests.size() == 2

        where:
        outcome                          || reason                | altered | truncated | kept
        cameBack(FITS, true)             || "cut_off"             | false   | false     | FITS.length()
        cameBack(CUT_SHORT, true)        || "cut_off"             | false   | false     | CUT_SHORT.length()
        cameBack(UNKEEPABLE, true)       || "cut_off"             | true    | false     | UNKEEPABLE.length()
        cameBack("A printer fire.")      || "not_the_shape"       | false   | false     | "A printer fire.".length()
        cameBack(UNKEEPABLE)             || "unkeepable"          | true    | false     | UNKEEPABLE.length()
        cameBack(FITS + " " * 8_388_608) || "not_kept_as_it_came" | true    | true      | 8_388_608
    }

    def "a model decline keeps answer and decline words independently with their own replacement and cut flags"() {
        given:
        def answer = "Demo Sample answer" + Character.toString(0)
        def words = "Demo Sample decline" + Character.toString(0) + "x" * 8_388_608
        model.answering(new CallOutcome.CameBack(answer, 321, 12, true, false,
                DidNotFitReason.MODEL_DECLINED, words))
        model.answering(new CallOutcome.Errored("Gone.", null))

        when:
        started()
        untilEnded(2)

        then:
        triesOf(SUMMARISE) == ["1 model ${SYSTEM} did_not_fit model_declined" as String,
                               "2 model ${SYSTEM} errored -" as String]
        values() == []
        store.texts("select answer from model_calls where outcome = 'came_back'") == ["Demo Sample answer\uFFFD"]
        // DB-SPECIFIC: PostgreSQL left().
        store.texts("select left(declined_words, ?) from model_calls where outcome = 'came_back'",
                "Demo Sample decline\uFFFD".length()) == ["Demo Sample decline\uFFFD"]

        and: "the two saved texts have separate flags and the model's reported usage is unchanged"
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("""
                select answer_altered || ' ' || answer_truncated || ' ' || declined_words_altered || ' '
                       || declined_words_truncated || ' ' || length(declined_words) || ' ' || came_back_count::text
                  from model_calls where outcome = 'came_back'""") == ["true false true true 8388608 12"]
        store.texts("""
                select (declined_words is null) || ' ' || declined_words_altered || ' ' || declined_words_truncated
                  from model_calls where outcome = 'errored'""") == ["true false false"]
    }

    def "a model decline preserves an empty answer and cuts an overlong answer without decline words"() {
        given:
        model.answering(new CallOutcome.CameBack("", 321, 12, true, false,
                DidNotFitReason.MODEL_DECLINED, "Demo Sample decline"))
        model.answering(new CallOutcome.CameBack("Demo Sample answer" + "x" * 8_388_608, 321, 12, true, false,
                DidNotFitReason.MODEL_DECLINED, null))

        when:
        started()
        untilEnded(2)

        then:
        triesOf(SUMMARISE) == ["1 model ${SYSTEM} did_not_fit model_declined" as String,
                               "2 model ${SYSTEM} did_not_fit model_declined" as String]
        values() == []
        // DB-SPECIFIC: PostgreSQL left().
        store.texts("""
                select left(call.answer, ?) || '|' || length(call.answer) || '|' ||
                       coalesce(call.declined_words, '<absent>') || '|' ||
                       call.answer_altered || '|' || call.answer_truncated || '|' ||
                       call.declined_words_altered || '|' || call.declined_words_truncated
                  from model_calls call join productions try on try.model_call_id = call.model_call_id
                 order by try.try_number""", "Demo Sample answer".length()) ==
                ["|0|Demo Sample decline|false|false|false|false",
                 "Demo Sample answer|8388608|<absent>|true|true|false|false"]
    }

    def "an empty model decline still spends the try and gives no value"() {
        given:
        model.answering(new CallOutcome.CameBack("", 321, 12, true, false,
                DidNotFitReason.MODEL_DECLINED, null))
        model.answering(new CallOutcome.Errored("Gone.", null))

        when:
        started()
        untilEnded(2)

        then:
        triesOf(SUMMARISE) == ["1 model ${SYSTEM} did_not_fit model_declined" as String,
                               "2 model ${SYSTEM} errored -" as String]
        values() == []
        store.texts("""
                select answer || '|' || (declined_words is null) || '|' || answer_altered || '|' ||
                       answer_truncated || '|' || declined_words_altered || '|' || declined_words_truncated ||
                       '|' || came_back_count
                  from model_calls where outcome = 'came_back'""") == ["|true|false|false|false|false|12"]
    }

    def "a call that went wrong spends the try, keeping what was said cleaned and cut, and the next try is sent"() {
        given:
        model.answering(new CallOutcome.Errored("\u0007Upstream failed: " + "x" * 3000, null))
        model.answering(cameBack(FITS))

        when:
        started()
        untilEnded(2)

        then:
        triesOf(SUMMARISE) == ["1 model ${SYSTEM} errored -" as String, "2 model ${SYSTEM} yielded" as String]
        // DB-SPECIFIC: PostgreSQL left().
        store.texts("""
                select length(error_detail) || ' ' || error_detail_truncated || ' ' || left(error_detail, 16)
                  from model_calls where outcome = 'errored'""") == ["2048 true Upstream failed:"]
        store.count("select count(*) from model_calls where outcome = 'errored' and came_back_count is null and answer is null") == 1
        store.count("select count(*) from model_calls where outcome = 'errored' and unreadable_response is null and not unreadable_response_altered and not unreadable_response_truncated and not unreadable_response_stopped") == 1

        and:
        values() == ['"A printer fire." - true']
        holds() == []
    }

    def "an unreadable service response is kept atomically with its errored producing call and no returned count"() {
        given:
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql('update workflow_steps set tries = 1 where workflow_step_id = ?::uuid')
                .param(SUMMARISE).update()
        model.answering(new CallOutcome.Errored(
                'The model service returned a response this system could not read.',
                new UnreadableResponseRecord(kept, stopped)))

        when:
        started()
        untilEnded(1)

        then:
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts('''
                select coalesce(unreadable_response, '<absent>') || '|' || unreadable_response_altered || '|' ||
                       unreadable_response_truncated || '|' || (came_back_count is null) || '|' ||
                       (sent_count is not null) || '|' || outcome::text
                  from model_calls
                ''') == [expected]
        store.texts('select unreadable_response_stopped::text from model_calls') == [stopped.toString()]
        triesOf(SUMMARISE) == ["1 model ${SYSTEM} errored -" as String]
        store.count('select count(*) from production_values') == 0

        where:
        kept                                                   | stopped || expected
        KeptAnswer.of('Demo Sample raw')                        | false   || 'Demo Sample raw|false|false|true|true|errored'
        KeptAnswer.of('Demo Sample raw')                        | true    || 'Demo Sample raw|false|false|true|true|errored'
        KeptAnswer.captured('Demo Sample replaced', true, false) | false   || 'Demo Sample replaced|true|false|true|true|errored'
        KeptAnswer.captured('Demo Sample replaced', true, false) | true    || 'Demo Sample replaced|true|false|true|true|errored'
        KeptAnswer.captured('Demo Sample partial', false, true)  | false   || 'Demo Sample partial|true|true|true|true|errored'
        KeptAnswer.captured('Demo Sample partial', false, true)  | true    || 'Demo Sample partial|true|true|true|true|errored'
        KeptAnswer.of('')                                       | false   || '|false|false|true|true|errored'
        KeptAnswer.of('')                                       | true    || '|false|false|true|true|errored'
    }

    def "a call turned away and sent again after each turnaway, each on record as sent again, lands as it came back"() {
        given:
        model.answering([new TurnAway("Busy,\u0000 later.", false), new TurnAway(null, false)], cameBack(FITS))

        when:
        started()
        untilEnded(1)

        then:
        store.texts("""
                select coalesce(said, '-') || ' ' || said_truncated || ' ' || spent_up || ' ' || (resent_at >= created_at)
                  from model_call_turnaways order by created_at, model_call_turnaway_id""") ==
                ["Busy, later. false false true", "- false false true"]
        model.events.count { it == ScriptedModelCalls.SENT } == 3

        and:
        callRows() == ["1 came_back 321 12 true false ordinary ${Envelope.VERSION}" as String]
        triesOf(SUMMARISE) == ["1 model ${SYSTEM} yielded" as String]
        holds() == []
    }

    def "a call turned away for the last time holds the step on its attempt, spending no try"() {
        given:
        model.answering(new CallOutcome.TurnedAway(new TurnAway(said, spentUp)))

        when:
        started()
        untilEnded(1)

        then:
        callRows() == ["- turned_away ${measured(0)} - false false ordinary ${Envelope.VERSION}" as String]
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("""
                select coalesce(said, '-') || ' ' || spent_up || ' ' || coalesce(model_call_outcome::text, '-') || ' '
                       || coalesce(resent_at::text, 'not sent again')
                  from model_call_turnaways""") == [row]
        holds() == ["turned_away true held"]

        and: "the try still open, and nothing given back"
        triesOf(SUMMARISE) == ["- model ${SYSTEM} open" as String]
        values() == []

        where:
        said         | spentUp || row
        "Too busy."  | false   || "Too busy. false - not sent again"
        null         | false   || "- false - not sent again"
        "Spent."     | true    || "Spent. true turned_away not sent again"
    }

    def "a stop in force once a turned-away call has waited ends it not sent again, holding the step"() {
        given:
        def gate = new Pause()
        model.pausing(gate, [new TurnAway("Busy.", false)], cameBack(FITS))
        started()
        gate.awaitReached()

        when:
        RunRows.stopped(store, RUN, CAT)
        gate.release()
        untilEnded(1)

        then:
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select outcome::text from model_calls") == ["turned_away"]
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select coalesce(resent_at::text, 'not sent again') from model_call_turnaways") == ["not sent again"]
        holds() == ["turned_away true held"]

        and:
        model.events.count { it == ScriptedModelCalls.SENT } == 1
        triesOf(SUMMARISE) == ["- model ${SYSTEM} open" as String]
        values() == []
    }

    def "stopped by hand, or by its ceiling lowered under what it spent, and opened again during a turnaway wait, a run keeps the old call ended until Try sending is pressed"() {
        given:
        def gate = new Pause()
        def logged = new SnapshottingAppender()
        logged.start()
        def callsLogger = LoggerFactory.getLogger(EngineCalls) as Logger
        callsLogger.addAppender(logged)
        waitingAfterTurnaway(gate, lateProgress)
        model.answering([new TurnAway("Busy.", false)], cameBack(FITS))
        started()
        gate.awaitReached()
        assert store.count("select count(*) from model_calls where outcome is null") == 1
        assert store.count("select count(*) from model_call_turnaways where resent_at is null") == 1
        if (atCeiling) {
            RunRows.called(store, RUN, "came_back", 10, 10)
        }
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def original = store.texts("select model_call_id::text from model_calls where purpose = 'produce'")[0]
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def production = store.texts("select production_id::text from productions")[0]
        def changes = new RunChanges(store.session, store.transactions(), new GroupRoles(store.session), tree,
                new RunSnapshots(store.session, CodeStepsHeld.NONE, new SearchWays([])), engine)

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

        // DB-SPECIFIC: PostgreSQL ::text casts.
        def endedAtStop = store.texts("select outcome::text from model_calls where purpose = 'produce'")
        def heldAtStop = holds()
        changes.openAgain(groupId(GROUP), runId(), ANN_USER)
        gate.release()
        tasks.awaitDrained()

        then:
        stopsMade == [atCeiling ? "system ${RUN} 0" as String : "person - 0"]
        model.events == [ScriptedModelCalls.SENT, new TurnAway("Busy.", false)]
        endedAtStop == ["turned_away"]
        heldAtStop == ["turned_away true held"]
        model.requests.size() == 1
        logged.list.findAll { it.formattedMessage.contains(original) }.every {
            !it.formattedMessage.contains("writing what happened to it failed") && it.throwableProxy == null
        }
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select model_call_id::text from model_calls where purpose = 'produce'") == [original]
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select production_id::text from productions where ended_at is null") == [production]
        store.count("select count(*) from run_step_send_attempts") == 1
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select coalesce(resent_at::text, 'not sent again') from model_call_turnaways") == ["not sent again"]
        RunBudget.spentAsRead(store.session, runId(), true) ==
                (atCeiling ? new RunBudget.Spend(10, 10, false, 1, 1) : RunBudget.Spend.NOTHING)
        values() == []
        store.count("select count(*) from reviews") == 0
        failures() == []
        holds() == ["turned_away true held"]

        when:
        model.answering(cameBack(FITS))
        acts.trySending(groupId(GROUP), runId(), new WorkflowStepId(UUID.fromString(SUMMARISE)), ANN_USER)
        untilEnded(atCeiling ? 3 : 2)
        tasks.awaitDrained()

        then:
        model.requests.size() == 2
        model.events.count { it == ScriptedModelCalls.SENT } == 2
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select production_id::text from productions where producer = 'model'") == [production]
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        store.texts("select outcome::text from model_calls where model_call_id = ?::uuid", original) == ["turned_away"]
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count("select count(*) from model_calls where purpose = 'produce' and model_call_id <> ?::uuid and outcome = 'came_back'", original) == 1
        store.count("select count(*) from run_step_send_attempts") == 2
        store.count("select count(*) from run_step_holds where released_at is null") == 0
        store.count("select count(*) from model_call_turnaways where resent_at is not null") == 0
        RunBudget.spentAsRead(store.session, runId(), true).spent() == (atCeiling ? 353 : 333)

        cleanup:
        gate.release()
        callsLogger.detachAppender(logged)

        where:
        atCeiling | lateProgress
        false     | false
        true      | false
        false     | true
    }

    def "stopped by hand, or by its ceiling lowered under what it spent, and opened again while a call is already out, a run keeps its completion and cost"() {
        given:
        def gate = new Pause()
        if (resentAlready) {
            waitingAfterResend(gate)
            model.answering([new TurnAway("Busy.", false)], cameBack(FITS))
        } else {
            model.pausing(gate, [], cameBack(FITS))
        }
        started()
        gate.awaitReached()
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def original = store.texts("select model_call_id::text from model_calls")[0]
        def changes = new RunChanges(store.session, store.transactions(), new GroupRoles(store.session), tree,
                new RunSnapshots(store.session, CodeStepsHeld.NONE, new SearchWays([])), engine)

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
        untilEnded(1)
        tasks.awaitDrained()

        then:
        stopsMade == [atCeiling ? "system ${RUN} 0" as String : "person - 0"]
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select model_call_id::text from model_calls") == [original]
        callRows() == ["1 came_back 321 12 true false ordinary ${Envelope.VERSION}" as String]
        values() == ['"A printer fire." - true']
        holds() == []
        model.events == (resentAlready
                ? [ScriptedModelCalls.SENT, new TurnAway("Busy.", false), ScriptedModelCalls.SENT]
                : [ScriptedModelCalls.SENT])
        store.count("select count(*) from model_call_turnaways") == (resentAlready ? 1 : 0)
        store.count("select count(*) from model_call_turnaways where resent_at is not null") == (resentAlready ? 1 : 0)
        RunBudget.spentAsRead(store.session, runId(), true).spent() == 333

        cleanup:
        gate.release()

        where:
        atCeiling | resentAlready
        false     | false
        false     | true
        true      | false
        true      | true
    }


    @spock.lang.Tag("postgres-schema-changing")
    def "a call whose own writing of a turnaway fails is ended as not sent again, holding the step, logged by its keys alone"() {
        given:
        store.session.sql("alter table model_call_turnaways add constraint spec_refuses_turnaways check (said is null)")
                .update()
        model.answering([new TurnAway("Busy.", false)], cameBack(FITS))
        def logged = new SnapshottingAppender()
        logged.start()
        CALLS_LOGGER.addAppender(logged)

        when:
        started()
        untilEnded(1)

        then:
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select outcome::text from model_calls") == ["turned_away"]
        holds() == ["turned_away true held"]
        store.count("select count(*) from model_call_turnaways") == 0
        triesOf(SUMMARISE) == ["- model ${SYSTEM} open" as String]

        and: "never sent again, and nothing of what was said logged"
        model.events.count { it == ScriptedModelCalls.SENT } == 1
        logged.list*.formattedMessage.any { it.contains(" is not sent again: writing what happened to it failed with ") }
        logged.list.every { !it.formattedMessage.contains("Busy") && it.throwableProxy == null }

        cleanup:
        CALLS_LOGGER.detachAppender(logged)
    }

    @spock.lang.Tag("postgres-schema-changing")
    def "a call whose own recording of a resend fails is ended as not sent again, holding the step, logged by its keys alone"() {
        given:
        store.session.sql("alter table model_call_turnaways add constraint spec_refuses_resends check (resent_at is null)")
                .update()
        model.answering([new TurnAway("Busy.", false)], cameBack(FITS))
        def logged = new SnapshottingAppender()
        logged.start()
        CALLS_LOGGER.addAppender(logged)
        ENGINE_LOGGER.addAppender(logged)

        when:
        started()
        untilEnded(1)

        then:
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select outcome::text from model_calls") == ["turned_away"]
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select coalesce(said, '-') || ' ' || coalesce(resent_at::text, 'not sent again') from model_call_turnaways") ==
                ["Busy. not sent again"]
        holds() == ["turned_away true held"]
        triesOf(SUMMARISE) == ["- model ${SYSTEM} open" as String]

        and: "sent once only, and nothing the failure said logged"
        model.requests.size() == 1
        model.events.count { it == ScriptedModelCalls.SENT } == 1
        logged.list*.formattedMessage.any { it.contains(" is not sent again: writing what happened to it failed with ") }
        logged.list.every {
            it.throwableProxy == null && !it.formattedMessage.contains("spec_refuses_resends") &&
                    !it.formattedMessage.contains("Busy")
        }

        cleanup:
        CALLS_LOGGER.detachAppender(logged)
        ENGINE_LOGGER.detachAppender(logged)
    }

    def "a call that fails on this side otherwise spends the try as gone wrong, naming only what kind of failure it was"() {
        given:
        def failing = { CallRequest request, progress -> throw new IllegalStateException("sent: The printer is on fire.") } as ModelCalls
        calls = new EngineCalls(EngineWiring.withoutTools(), failing, store.transactions(), tree, writes, ceilings, snapshots, heldFiles)
        holding(DeployedModels.HELD)
        def logged = new SnapshottingAppender()
        logged.start()
        CALLS_LOGGER.addAppender(logged)

        when:
        started()
        untilEnded(2)

        then:
        triesOf(SUMMARISE) == ["1 model ${SYSTEM} errored -" as String, "2 model ${SYSTEM} errored -" as String]
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select outcome::text || ': ' || error_detail from model_calls") ==
                ["errored: The call failed on this side with java.lang.IllegalStateException."] * 2

        and: "nothing held back or turned away, and nothing of what it said kept or logged"
        holds() == []
        store.count("select count(*) from model_call_turnaways") == 0
        logged.list*.formattedMessage.count { it.endsWith(" failed on this side with java.lang.IllegalStateException") } == 2
        logged.list.every { !it.formattedMessage.contains("printer") && it.throwableProxy == null }

        cleanup:
        CALLS_LOGGER.detachAppender(logged)
    }

    def "planning what follows failing after an answer came back leaves the answer, the call's end and the try's standing"() {
        given:
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("update declaration_fields set standing = 'above_confidence', standing_threshold = 80 where declaration_field_id = ?::uuid")
                .params(SUMMARY).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("update bindings set target_path = 'nowhere' where binding_id = ?::uuid")
                .params(TicketWorkflow.SUMMARY_IN).update()
        model.answering(cameBack('{"values":{"summary":"A printer fire."},"confidences":{"summary":90}}'))
        def logged = new SnapshottingAppender()
        logged.start()
        ENGINE_LOGGER.addAppender(logged)

        when:
        started()
        until("the plan after the end failed") { !logged.list.isEmpty() }

        then:
        logged.list*.formattedMessage.toSet() == ["Run ${RUN} of group ${GROUP} could not go on by itself" as String] as Set

        and: "the end stands as it was written"
        callRows() == ["1 came_back 321 12 true false ordinary ${Envelope.VERSION}" as String]
        triesOf(SUMMARISE) == ["1 model ${SYSTEM} yielded" as String]
        values() == ['"A printer fire." 90 false']
        store.texts("select answer from model_calls") ==
                ['{"values":{"summary":"A printer fire."},"confidences":{"summary":90}}']

        and: "nothing of what could not be planned"
        triesOf(CONFIRM) == []

        cleanup:
        ENGINE_LOGGER.detachAppender(logged)
    }

    def "a call ended already when it comes back writes nothing of how it came back, logged by its keys alone"() {
        given:
        def gate = new Pause()
        model.pausing(gate, [], cameBack(FITS))
        def logged = new SnapshottingAppender()
        logged.start()
        CALLS_LOGGER.addAppender(logged)
        started()
        gate.awaitReached()
        // DB-SPECIFIC: PostgreSQL now().
        store.session.sql("update model_calls set outcome = 'nothing_came_back', ended_at = now()").update()

        when:
        gate.release()
        until("the late end logged") { !logged.list.isEmpty() }

        then:
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select outcome::text || ' ' || coalesce(answer, '-') from model_calls") == ["nothing_came_back -"]
        triesOf(SUMMARISE) == ["1 model ${SYSTEM} open" as String]
        values() == []
        holds() == []

        and:
        // DB-SPECIFIC: PostgreSQL ::text casts.
        logged.list*.formattedMessage == ["Call ${store.texts('select model_call_id::text from model_calls')[0]} of try "
                + "${store.texts('select production_id::text from productions')[0]} of run ${RUN} had ended already; "
                + "how it ended now is dropped" as String]

        cleanup:
        CALLS_LOGGER.detachAppender(logged)
    }

    def "a model's production a person refused is asked again of the model, told what was refused only where the step tells"() {
        given:
        producedByModel("general", "ordinary", tells)
        model.answering(cameBack(FITS))
        model.answering(cameBack('{"values":{"summary":"A printer on fire."},"confidences":{}}'))
        started()
        untilEnded(1)
        acts.review(groupId(GROUP), runId(), new WorkflowStepId(UUID.fromString(SUMMARISE)), 1, ANN_USER,
                [summary: new StepActs.ReviewedRecord(ReviewOutcome.REFUSED, "Say it is on fire.")])

        when:
        acts.askAgain(groupId(GROUP), runId(), new WorkflowStepId(UUID.fromString(SUMMARISE)), 2, ANN_USER)
        untilEnded(2)

        then: "the second try asked by the person and produced by the model"
        triesOf(SUMMARISE) == ["1 model ${SYSTEM} yielded" as String,
                               "2 model ${TicketWorkflow.ANN} yielded" as String]

        and: "its try written by the person's act precedes the attempt and the call that sends it"
        store.texts("""
                select (attempt.created_at <= call.created_at) || ' ' || (try.created_at <= attempt.created_at)
                  from productions try
                  join run_step_send_attempts attempt on attempt.production_id = try.production_id
                  join model_calls call on call.run_step_send_attempt_id = attempt.run_step_send_attempt_id
                 where try.try_number = 2""") == ["true true"]
        store.count("""
                select count(*) from model_calls call join productions try on try.production_id = call.production_id
                 where try.try_number = 2""") == 1

        and: "the second call made on an engine thread too, though a request asked for it"
        madeOn == [[true, false], [true, false]]

        and:
        model.requests.size() == 2
        model.requests[1].sent().user().contains('"refused"') == tells
        model.requests[1].sent().user().contains("Say it is on fire.") == tells
        !model.requests[0].sent().user().contains('"refused"')

        where:
        tells << [true, false]
    }

    def "a model's step held on a stop before it was asked is asked and sent once the stop is let go"() {
        given:
        store.stopped(TicketWorkflow.SUMMARISE_QUESTION, TicketWorkflow.ANN)
        model.answering(cameBack(FITS))
        started()
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def heldBefore = store.texts("select reason || ' ' || coalesce(released_at::text, 'held') from run_step_holds")
        def triedBefore = triesOf(SUMMARISE)
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
        store.session.sql("update entry_stops set let_go_at = now(), let_go_by = ?::uuid")
                .params(TicketWorkflow.ANN).update()

        when:
        engine.goesOn(LibraryStore.entryId(TicketWorkflow.SUMMARISE_QUESTION))
        untilEnded(1)

        then: "held and nothing asked while stopped"
        heldBefore == ["entry_stopped held"]
        triedBefore == []

        and: "released, asked by the system, sent once and yielded"
        store.texts("select reason || ' ' || (released_at is not null) from run_step_holds") == ["entry_stopped true"]
        triesOf(SUMMARISE) == ["1 model ${SYSTEM} yielded" as String]
        model.requests.size() == 1
        madeOn == [[true, false]]
    }

    def "a model's step reached by the run going on after a person's act is sent, a value given as none kept as none"() {
        given:
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                update workflow_steps set producer = 'person', producer_model = null, producer_mode = null
                 where workflow_step_id = ?::uuid""").params(SUMMARISE).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                update workflow_steps set producer = 'model', producer_model = 'general', producer_mode = 'ordinary'
                 where workflow_step_id = ?::uuid""").params(CONFIRM).update()
        model.answering(cameBack('{"values":{"approved":true,"note":null},"confidences":{}}'))
        started()
        StepRows.answered(store, SUMMARISE, CAT, "Read it twice.", [(SUMMARY): '"A printer fire."'])

        when:
        acts.review(groupId(GROUP), runId(), new WorkflowStepId(UUID.fromString(SUMMARISE)), 1, ANN_USER,
                [summary: new StepActs.ReviewedRecord(ReviewOutcome.ASSURED, null)])
        untilEnded(1)

        then:
        triesOf(CONFIRM) == ["1 model ${SYSTEM} yielded" as String]
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("""
                select field.name || ' ' || coalesce(held.value::text, 'none')
                  from production_values held join declaration_fields field
                    on field.declaration_field_id = held.declaration_field_id
                 where held.production_id = (select call.production_id from model_calls call)
                 order by field.position""") == ["approved true", "note none"]

        and:
        model.requests.size() == 1
        madeOn == [[true, false]]
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
            lateProgress ? cameBack(FITS) : outcome
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
