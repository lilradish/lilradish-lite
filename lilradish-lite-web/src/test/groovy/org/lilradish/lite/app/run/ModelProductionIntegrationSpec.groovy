package org.lilradish.lite.app.run

import static org.lilradish.lite.app.run.fixture.TicketWorkflow.ANN_USER
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.CAT
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.CONFIRM
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.GROUP
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.RUN
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARISE
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARY
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.VERSION
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.WORKFLOW
import static org.lilradish.lite.testutil.library.LibraryStore.groupId

import ch.qos.logback.classic.Logger
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.lilradish.lite.app.codestep.CodeRuns
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.run.fixture.StepRows
import org.lilradish.lite.app.run.fixture.TicketWorkflow
import org.lilradish.lite.domain.declaration.AskedField
import org.lilradish.lite.domain.declaration.FieldKind
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.inference.CallOutcome
import org.lilradish.lite.domain.inference.CallProgress
import org.lilradish.lite.domain.inference.CallRequest
import org.lilradish.lite.domain.inference.ModelCallPurpose
import org.lilradish.lite.domain.inference.ModelCalls
import org.lilradish.lite.domain.inference.TurnAway
import org.lilradish.lite.domain.model.DeployedModel
import org.lilradish.lite.domain.model.ModelCatalog
import org.lilradish.lite.domain.model.ModelName
import org.lilradish.lite.domain.model.SentText
import org.lilradish.lite.domain.run.ModelCallId
import org.lilradish.lite.domain.run.ProductionId
import org.lilradish.lite.domain.run.ReviewOutcome
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.domain.run.RunStepSendAttemptId
import org.lilradish.lite.domain.run.WorkflowStepId
import org.lilradish.lite.testutil.SnapshottingAppender
import org.lilradish.lite.testutil.codestep.CodeStepsHeld
import org.lilradish.lite.testutil.inference.Pause
import org.lilradish.lite.testutil.inference.ScriptedModelCalls
import org.lilradish.lite.testutil.library.DeployedModels
import org.lilradish.lite.testutil.library.LibraryStore
import org.lilradish.lite.testutil.run.RunRows
import org.slf4j.LoggerFactory
import org.springframework.transaction.support.TransactionSynchronizationManager
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * A step a model produces, on a real server running the real baseline, the model scripted: its try asked in the
 * transaction that reached it, its call written down as sent and made on the engine's own threads, and each way
 * the call can end landing as that try's values, a try spent, or the step held or failed.
 */
class ModelProductionIntegrationSpec extends Specification {

    static final String SYSTEM = RunRows.WORKFLOW_RUNNER

    static final String FITS = '{"values":{"summary":"A printer fire."},"confidences":{}}'

    /** Half a pair inside a value: it unwraps as not kept, and is kept with the half replaced. */
    static final String UNKEEPABLE = '{"values":{"summary":"A \uD800fire."},"confidences":{}}'

    /** What a model stopped at its limit gives back: read as it is, it would not be the shape either. */
    static final String CUT_SHORT = '{"values":{"summ'

    static final Logger CALLS_LOGGER = LoggerFactory.getLogger(EngineCalls) as Logger

    static final Logger ENGINE_LOGGER = LoggerFactory.getLogger(RunEngine) as Logger

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
        store = LibraryStore.copied(server, "model_production_" + (++databasesMade))
        model = new ScriptedModelCalls()
        tree = new RunTree(store.session)
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
        TicketWorkflow.seed(store)
        producedByModel("general", "ordinary")
    }

    def cleanup() {
        executor.destroy()
    }

    private void holding(ModelCatalog catalog) {
        def snapshots = new RunSnapshots(store.session, CodeStepsHeld.NONE)
        engine = new RunEngine(store.session, store.transactions(), tree, snapshots, writes, executor, calls,
                new EngineCodes(new CodeRuns(CodeStepsHeld.NONE), store.transactions(), tree, snapshots, writes, executor),
                catalog, ceilings, new GroupRoles(store.session))
        acts = new StepActs(store.session, store.transactions(), new GroupRoles(store.session), tree, snapshots, writes,
                engine)
    }

    private void producedByModel(String name, String mode, boolean tells = false) {
        store.session.sql("""
                update workflow_steps set producer = 'model', producer_model = ?, producer_mode = ?,
                                          tells_what_happened = ?
                 where workflow_step_id = ?::uuid""").params(name, mode, tells, SUMMARISE).update()
    }

    private static RunId runId() {
        new RunId(UUID.fromString(RUN))
    }

    /** As a start does: the run written, its tree locked, the first step planned, in one transaction. */
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

    /** Each try of a step as its number, producer, who asked for it, and how it stands. */
    private List<String> triesOf(String step) {
        store.texts("""
                select try.try_number || ' ' || try.producer || ' ' || try.created_by || ' '
                       || case when try.ended_at is null then 'open'
                               else coalesce(try.lost_reason::text || ' ' || coalesce(try.did_not_fit_reason::text, '-'),
                                             'yielded') end
                  from productions try join run_steps held on held.run_step_id = try.run_step_id
                 where held.workflow_step_id = ?::uuid order by try.try_number
                """, step)
    }

    /** Each call as its try, outcome, counts, whether the model counted, whether its answer was altered. */
    private List<String> callRows() {
        store.texts("""
                select try.try_number || ' ' || coalesce(call.outcome::text, 'out') || ' ' || call.sent_count || ' '
                       || coalesce(call.came_back_count::text, '-') || ' ' || call.counted_by_model || ' '
                       || call.answer_altered || ' ' || call.mode || ' ' || call.envelope_version
                  from model_calls call join productions try on try.production_id = call.production_id
                 order by try.try_number
                """)
    }

    private List<String> holds() {
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
        store.texts("""
                select value::text || ' ' || coalesce(confidence::text, '-') || ' ' || needs_review
                  from production_values order by production_value_id
                """)
    }

    /** The units this system measured the {@code index}th call's sending at. */
    private long measured(int index) {
        def request = model.requests[index]
        request.model().unitsOf(request.sent().characters())
    }

    private static CallOutcome.CameBack cameBack(String answer, boolean cutOff = false) {
        new CallOutcome.CameBack(answer, 321, 12, true, cutOff)
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

    def "a model's step reached is asked of its first try by the system, and its call written down as sent on the engine's thread before anything is sent"() {
        given:
        def gate = new Pause()
        model.pausing(gate, [], cameBack(FITS))

        when:
        started()
        gate.awaitReached()

        then: "the try, its attempt holding what is sent, and the call, all written before the call was made"
        triesOf(SUMMARISE) == ["1 model ${SYSTEM} open" as String]
        store.texts("""
                select attempt.purpose || ' ' || attempt.model || ' ' || attempt.mode || ' ' || attempt.too_long || ' '
                       || attempt.created_by_kind || ' ' || (attempt.payload::jsonb -> 'takes' ->> 'text')
                  from run_step_send_attempts attempt""") == ["produce general ordinary false system The printer is on fire."]
        callRows() == ["1 out ${measured(0)} - false false ordinary 2" as String]

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
        triesOf(SUMMARISE) == ["1 model ${SYSTEM} open" as String]

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

    /** Four characters a unit: what it cannot cut is about 250 units, and a ticket this long adds nearly 1,000. */
    def "a try too long to send is held on its attempt, which keeps what would have been sent, and nothing is sent"() {
        given:
        holding(new ModelCatalog([new DeployedModel(new ModelName("general"), [], 600, 4.0G, 4000, [])]))
        def ticket = "x" * 3900

        when: "written on the engine's thread, as its call would have been"
        started('{"ticket": "' + ticket + '"}')
        until("the step held") { store.count("select count(*) from run_step_holds") == 1 }

        then:
        holds() == ["too_long true held"]
        store.texts("""
                select attempt.too_long || ' ' || (attempt.payload::jsonb -> 'takes' ->> 'text' = ?)
                  from run_step_send_attempts attempt""", ticket) == ["true true"]

        and:
        store.count("select count(*) from model_calls") == 0
        failures() == []
        triesOf(SUMMARISE) == ["1 model ${SYSTEM} open" as String]
        model.requests == []
    }

    def "a try whose part that cannot be cut is itself too long fails the step rather than holding it"() {
        given:
        holding(new ModelCatalog([new DeployedModel(new ModelName("general"), [], 50, 4.0G, 4000, [])]))

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

    /** Code may never give its thread back, so a call made there could wait behind it for good. */
    def "a call is refused on the engine's code threads, and sends nothing"() {
        given:
        def outcome = new CompletableFuture<Throwable>()

        when:
        executor.executeCode {
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

    def "an answer that fits lands as the try's values, and the run goes on as they stand"() {
        given:
        store.session.sql("update declaration_fields set standing = 'above_confidence', standing_threshold = 80 where declaration_field_id = ?::uuid")
                .params(SUMMARY).update()
        model.answering(cameBack("""{"values":{"summary":"A printer fire."},"confidences":{"summary":${sure}}}"""))

        when: "drained: what follows the end is planned after it, in the same task"
        started()
        untilEnded(1)
        executor.destroy()

        then:
        triesOf(SUMMARISE) == ["1 model ${SYSTEM} yielded" as String]
        callRows() == ["1 came_back 321 12 true false ordinary 2"]
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
        model.answering(new CallOutcome.Errored("Gone."))

        when:
        started()
        untilEnded(2)

        then:
        triesOf(SUMMARISE) == ["1 model ${SYSTEM} did_not_fit ${reason}" as String,
                               "2 model ${SYSTEM} errored -" as String]
        store.texts("""
                select call.answer_altered || ' ' || try.call_answer_altered || ' ' || length(call.answer)
                  from model_calls call join productions try on try.model_call_id = call.model_call_id
                 where try.try_number = 1""") == ["${altered} ${altered} ${kept}" as String]

        and: "nothing given back, and the step failed with its tries spent"
        values() == []
        triesOf(CONFIRM) == []
        model.requests.size() == 2

        where:
        outcome                          || reason                | altered | kept
        cameBack(FITS, true)             || "cut_off"             | false   | FITS.length()
        cameBack(CUT_SHORT, true)        || "cut_off"             | false   | CUT_SHORT.length()
        cameBack(UNKEEPABLE, true)       || "cut_off"             | true    | UNKEEPABLE.length()
        cameBack("A printer fire.")      || "not_the_shape"       | false   | "A printer fire.".length()
        cameBack(UNKEEPABLE)             || "unkeepable"          | true    | UNKEEPABLE.length()
        cameBack(FITS + " " * 8_388_608) || "not_kept_as_it_came" | true    | 8_388_608
    }

    def "a call that went wrong spends the try, keeping what was said cleaned and cut, and the next try is sent"() {
        given:
        model.answering(new CallOutcome.Errored("\u0007Upstream failed: " + "x" * 3000))
        model.answering(cameBack(FITS))

        when:
        started()
        untilEnded(2)

        then:
        triesOf(SUMMARISE) == ["1 model ${SYSTEM} errored -" as String, "2 model ${SYSTEM} yielded" as String]
        store.texts("""
                select length(error_detail) || ' ' || error_detail_truncated || ' ' || left(error_detail, 16)
                  from model_calls where outcome = 'errored'""") == ["2048 true Upstream failed:"]
        store.count("select count(*) from model_calls where outcome = 'errored' and came_back_count is null and answer is null") == 1

        and:
        values() == ['"A printer fire." - true']
        holds() == []
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
        callRows() == ["1 came_back 321 12 true false ordinary 2"]
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
        callRows() == ["1 turned_away ${measured(0)} - false false ordinary 2" as String]
        store.texts("""
                select coalesce(said, '-') || ' ' || spent_up || ' ' || coalesce(model_call_outcome::text, '-') || ' '
                       || coalesce(resent_at::text, 'not sent again')
                  from model_call_turnaways""") == [row]
        holds() == ["turned_away true held"]

        and: "the try still open, and nothing given back"
        triesOf(SUMMARISE) == ["1 model ${SYSTEM} open" as String]
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
        store.texts("select outcome::text from model_calls") == ["turned_away"]
        store.texts("select coalesce(resent_at::text, 'not sent again') from model_call_turnaways") == ["not sent again"]
        holds() == ["turned_away true held"]

        and:
        model.events.count { it == ScriptedModelCalls.SENT } == 1
        triesOf(SUMMARISE) == ["1 model ${SYSTEM} open" as String]
        values() == []
    }

    /** Writing the turnaway is made to fail by a check this spec adds, which the store never holds. */
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
        store.texts("select outcome::text from model_calls") == ["turned_away"]
        holds() == ["turned_away true held"]
        store.count("select count(*) from model_call_turnaways") == 0
        triesOf(SUMMARISE) == ["1 model ${SYSTEM} open" as String]

        and: "never sent again, and nothing of what was said logged"
        model.events.count { it == ScriptedModelCalls.SENT } == 1
        logged.list*.formattedMessage.any { it.contains(" is not sent again: writing what happened to it failed with ") }
        logged.list.every { !it.formattedMessage.contains("Busy") && it.throwableProxy == null }

        cleanup:
        CALLS_LOGGER.detachAppender(logged)
    }

    /** Recording the resend is made to fail by a check this spec adds, which the store never holds. */
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
        store.texts("select outcome::text from model_calls") == ["turned_away"]
        store.texts("select coalesce(said, '-') || ' ' || coalesce(resent_at::text, 'not sent again') from model_call_turnaways") ==
                ["Busy. not sent again"]
        holds() == ["turned_away true held"]
        triesOf(SUMMARISE) == ["1 model ${SYSTEM} open" as String]

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
        calls = new EngineCalls(failing, store.transactions(), tree, writes, ceilings)
        holding(DeployedModels.HELD)
        def logged = new SnapshottingAppender()
        logged.start()
        CALLS_LOGGER.addAppender(logged)

        when:
        started()
        untilEnded(2)

        then:
        triesOf(SUMMARISE) == ["1 model ${SYSTEM} errored -" as String, "2 model ${SYSTEM} errored -" as String]
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

    /** Confirm's input is made to point at nothing its question takes, so asking it fails the plan after the end. */
    def "planning what follows failing after an answer came back leaves the answer, the call's end and the try's standing"() {
        given:
        store.session.sql("update declaration_fields set standing = 'above_confidence', standing_threshold = 80 where declaration_field_id = ?::uuid")
                .params(SUMMARY).update()
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
        callRows() == ["1 came_back 321 12 true false ordinary 2"]
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
        store.session.sql("update model_calls set outcome = 'nothing_came_back', ended_at = now()").update()

        when:
        gate.release()
        until("the late end logged") { !logged.list.isEmpty() }

        then:
        store.texts("select outcome::text || ' ' || coalesce(answer, '-') from model_calls") == ["nothing_came_back -"]
        triesOf(SUMMARISE) == ["1 model ${SYSTEM} open" as String]
        values() == []
        holds() == []

        and:
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

        and: "its try written by the person's act, and the attempt sending it and its call together, not before it"
        store.texts("""
                select (attempt.created_at = call.created_at) || ' ' || (try.created_at <= attempt.created_at)
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
        def heldBefore = store.texts("select reason || ' ' || coalesce(released_at::text, 'held') from run_step_holds")
        def triedBefore = triesOf(SUMMARISE)
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

    /** Confirm made a model's, reached by the drive after summarise's value is assured; its note given as none. */
    def "a model's step reached by the run going on after a person's act is sent, a value given as none kept as none"() {
        given:
        store.session.sql("""
                update workflow_steps set producer = 'person', producer_model = null, producer_mode = null
                 where workflow_step_id = ?::uuid""").params(SUMMARISE).update()
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
}
