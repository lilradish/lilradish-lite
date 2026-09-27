package org.lilradish.lite.app.run

import static org.lilradish.lite.testutil.library.LibraryStore.groupId

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.libprunus.core.error.ApiErrorException
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.run.fixture.CodeWorkflow
import org.lilradish.lite.domain.declaration.Demand
import org.lilradish.lite.domain.declaration.Field
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.declaration.FieldShape
import org.lilradish.lite.domain.declaration.FieldStanding
import org.lilradish.lite.domain.declaration.HowMany
import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.inference.CallOutcome
import org.lilradish.lite.domain.inference.ModelCallPurpose
import org.lilradish.lite.domain.inference.TurnAway
import org.lilradish.lite.domain.run.ReviewSending
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.domain.run.StepPosition
import org.lilradish.lite.domain.run.StepPositions
import org.lilradish.lite.domain.run.WaitsOn
import org.lilradish.lite.domain.run.WorkflowStepId
import org.lilradish.lite.domain.wire.JsonValue
import org.lilradish.lite.testutil.SnapshottingAppender
import org.lilradish.lite.testutil.codestep.CodeStepsHeld
import org.lilradish.lite.testutil.codestep.ScriptedCodeStep
import org.lilradish.lite.testutil.inference.ScriptedModelCalls
import org.lilradish.lite.testutil.library.LibraryStore
import org.slf4j.LoggerFactory
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper

/**
 * What a code step gives back, reviewed by the model its step names, on a real server running the real baseline:
 * sent as the running release declares the code step, with no instruction and nothing naming what produced it. A
 * second wiring over the same store stands in for a release deployed after the try was made.
 */
class CodeStepReviewIntegrationSpec extends Specification {

    static final String ASSURES = '{"decisions":{"receipt":{"outcome":"assured"}}}'

    /** A receipt that stands only once reviewed, as the release declares it here. */
    static final Field RECEIPT = new Field(new FieldName("receipt"), null, null, new FieldShape.Text(64),
            new HowMany.One(), new Demand.Stands(true, FieldStanding.NEVER, null))

    /** What a later release gives back in the receipt's place, so the receipt given is no longer what it declares. */
    static final Field REFERENCE = new Field(new FieldName("reference"), null, null, new FieldShape.Text(64),
            new HowMany.One(), new Demand.Stands(true, FieldStanding.NEVER, null))

    /** A reference a later release gives back beside the receipt, which need not be given. */
    static final Field REFERENCE_MAYBE = new Field(new FieldName("reference"), null, null, new FieldShape.Text(64),
            new HowMany.One(), new Demand.Stands(false, FieldStanding.NEVER, null))

    /** What a later release takes in the reply's place, so the version binds into nothing it takes. */
    static final Field MESSAGE = new Field(new FieldName("message"), null, null, new FieldShape.Text(2000),
            new HowMany.One(), new Demand.Given(true))

    static final String REFUSES = '{"decisions":{"receipt":{"outcome":"refused","words":"Name the ticket."}}}'

    /** A reference list the group has no version of. */
    static final String ABSENT_LIST = "00000007-0000-4000-8000-000000000cff"

    /** The receipt as a later release declares it: a term of a list that is not here. */
    static final Field RECEIPT_OF_A_LIST_NOT_HERE = new Field(new FieldName("receipt"), null, null,
            new FieldShape.Term(LibraryStore.versionId(ABSENT_LIST)), new HowMany.One(),
            new Demand.Stands(true, FieldStanding.NEVER, null))

    static final String GRADES = "00000007-0000-4000-8000-000000000c09"

    /**
     * A grade given back beside the receipt, a term of the group's grades, which are here. Beside it, not in its
     * place: the workflow reads the receipt as text, which a term does not fit, and code giving otherwise is not run.
     */
    static final Field GRADE = new Field(new FieldName("grade"), null, null,
            new FieldShape.Term(LibraryStore.versionId(GRADES)), new HowMany.One(),
            new Demand.Stands(true, FieldStanding.NEVER, null))

    static final String ASSURES_BOTH =
            '{"decisions":{"receipt":{"outcome":"assured"},"grade":{"outcome":"assured"}}}'

    static final List<Logger> ENGINE_LOGGERS = [RunEngine, EngineExecutor, EngineThread, CodeThread, EngineCodes,
                                                EngineCalls].collect {
        LoggerFactory.getLogger(it) as Logger
    }

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    ScriptedModelCalls model

    EngineWiring wiring

    /** Every further process a feature wires, each shut down after it. */
    List<EngineWiring> wired = []

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, "code_step_review_" + (++databasesMade))
        model = new ScriptedModelCalls()
        CodeWorkflow.seed(store)
        store.session.sql("""
                update workflow_steps set reviewer_model = 'small', reviewer_mode = 'ordinary'
                 where workflow_step_id = ?::uuid""").params(CodeWorkflow.SEND).update()
        def code = new ScriptedCodeStep(CodeWorkflow.CODE_STEP, [CodeWorkflow.REPLY], [RECEIPT], true,
                { CodeWorkflow.receipt() })
        wiring = new EngineWiring(store, model, CodeStepsHeld.of(code))
    }

    def cleanup() {
        wiring.executor.destroy()
        wired*.closing()
        wired*.executor*.destroy()
    }

    private static RunId runId() {
        new RunId(UUID.fromString(CodeWorkflow.RUN))
    }

    private void started(EngineWiring by = wiring) {
        store.transactions().executeWithoutResult {
            CodeWorkflow.run(store)
            by.engine.planStarted(by.tree.lock(groupId(CodeWorkflow.GROUP), runId()).orElseThrow())
        }
    }

    private void until(String what, Closure<Boolean> reached) {
        def deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!reached()) {
            assert System.nanoTime() < deadline: "never reached: ${what}"
            Thread.sleep(10)
        }
    }

    def "what a code step gave back is sent to the model its step names to review, with no instruction and nothing naming what produced it"() {
        given:
        model.answering(new CallOutcome.CameBack(ASSURES, 200, 10, true, false))

        when:
        started()
        until("the review ended") { store.count("select count(*) from model_calls where outcome is not null") == 1 }
        wiring.drained()

        then:
        model.requests*.purpose() == [ModelCallPurpose.REVIEW]
        def sent = JsonMapper.builder().build().readValue(model.requests[0].sent().user(), Map)
        sent.keySet() == ["envelope_version", "takes", "answer", "deciding"] as Set
        sent.takes == [reply: "The printer is on fire."]
        sent.answer == [receipt: "R-1"]
        sent.deciding == ["receipt"]

        and:
        [model.requests[0].sent().user(), model.requests[0].sent().system()].every {
            !it.contains(CodeWorkflow.CODE_STEP) && !it.contains('"instruction"') && !it.contains('"send"')
        }

        and:
        store.texts("""
                select review.created_by_kind || ' ' || decision.outcome
                  from reviews review join review_decisions decision on decision.review_id = review.review_id""") ==
                ["system assured"]
        store.count("""
                select count(*) from productions try join run_steps step on step.run_step_id = try.run_step_id
                 where step.workflow_step_id = ?::uuid""", CodeWorkflow.CHECK) == 1
    }

    /**
     * The release deployed since the try was made pins a list the group does not hold, no longer takes what the
     * version binds into it, or no longer declares what it gave back: the model is not called and nothing fails, the
     * values waiting on a person, saying why.
     */
    def "what a code step gave back that the running release can no longer put to its reviewer is left to a person, saying why, nothing sent and nothing failed"() {
        given:
        madeUnderOneRelease()
        def logged = listening()
        def next = wiredFor(release(gives, "R-1", null, takes))

        when:
        next.engine.goOn(groupId(CodeWorkflow.GROUP), runId())
        next.drained()

        then:
        reviewAttempts() == ["small ordinary true system ${published}" as String]
        store.count("select count(*) from run_step_send_attempts where payload is not null") == 0
        with(sendPosition(next) as StepPosition.AwaitingReview) {
            sending() == expected
            values()*.on() == [WaitsOn.REVIEW_AT_GATE]
        }

        and: "read as why, a person offered the review and nobody Try sending"
        def row = sendRow(next)
        row.where().review() == published
        row.where().values() == [new StepAnswers.WaitingValueAnswer("receipt", "review_at_gate")]
        row.acts() == ["review"]
        row.withheld() == []

        and:
        model.requests == []
        store.count("select count(*) from model_calls") == 0
        store.count("select count(*) from run_step_failures") == 0
        store.count("select count(*) from reviews") == 0
        store.texts("select coalesce(lost_reason::text, 'yielded') from productions where producer = 'code'") ==
                ["yielded"]
        logged.list.findAll { it.level == Level.ERROR } == []

        cleanup:
        unlistened(logged)

        where:
        takes                | gives                        || expected                               | published
        [CodeWorkflow.REPLY] | [RECEIPT_OF_A_LIST_NOT_HERE] || ReviewSending.LIST_NOT_HERE            | "list_not_here"
        [MESSAGE]            | [RECEIPT]                    || ReviewSending.TAKES_NO_LONGER_DECLARED | "takes_no_longer_declared"
        [CodeWorkflow.REPLY] | [REFERENCE]                  || ReviewSending.NO_LONGER_DECLARED       | "no_longer_declared"
    }

    /**
     * A person answering after the model refused code's try was shown that refusal, so its review would tell it; a
     * release that no longer declares what the refused try gave back leaves nothing to tell it by.
     */
    def "a person's answer reviewed after a refusal the running release no longer declares is left to a person, nothing thrown"() {
        given:
        store.session.sql("update workflow_steps set tries = 2 where workflow_step_id = ?::uuid")
                .params(CodeWorkflow.SEND).update()
        def answered = new JsonValue.JsonObject([
                new JsonValue.JsonMember("receipt", new JsonValue.JsonString("R-2")),
                new JsonValue.JsonMember("reference", new JsonValue.JsonString("T-9"))])
        model.answering(new CallOutcome.CameBack(REFUSES, 200, 10, true, false))
        def first = wiredFor(release([RECEIPT], "R-1", null, [CodeWorkflow.REPLY], false))
        started(first)
        until("the refusal landed") { store.count("select count(*) from review_decisions") == 1 }
        first.drained()
        def logged = listening()
        def next = wiredFor(release([RECEIPT, REFERENCE_MAYBE], "R-1", null, [CodeWorkflow.REPLY], false))

        when:
        next.acts.answer(groupId(CodeWorkflow.GROUP), runId(), new WorkflowStepId(UUID.fromString(CodeWorkflow.SEND)),
                2, CodeWorkflow.ANN_USER, answered, "Checked the ticket.")
        next.drained()

        then:
        reviewAttempts() == ["small ordinary false system -", "small ordinary true system no_longer_declared"]
        (sendPosition(next) as StepPosition.AwaitingReview).sending() == ReviewSending.NO_LONGER_DECLARED
        model.requests*.purpose() == [ModelCallPurpose.REVIEW]

        and:
        store.count("select count(*) from run_step_failures") == 0
        logged.list.findAll { it.level == Level.ERROR } == []

        cleanup:
        unlistened(logged)
    }

    /** The engine sends nothing of a code step no release holds, so pressing is refused, not taken and dropped. */
    def "Try sending on a review turned away is refused where the running release no longer holds the code step, nothing written"() {
        given:
        model.answering(new CallOutcome.TurnedAway(new TurnAway("Busy.", false)))
        started()
        until("the review was turned away") {
            store.count("select count(*) from model_calls where outcome is not null") == 1
        }
        wiring.drained()
        def without = new EngineWiring(store, model, CodeStepsHeld.NONE)
        wired << without
        def before = store.contents()

        when:
        without.acts.trySending(groupId(CodeWorkflow.GROUP), runId(),
                new WorkflowStepId(UUID.fromString(CodeWorkflow.SEND)), CodeWorkflow.CAT_USER)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.TRY_SENDING_NOT_OFFERED
        store.contents() == before
        model.requests.size() == 1
    }

    /** Nothing about a release deployed since holds the review back where it still declares what the try gave back. */
    def "what a code step gave back that the running release still declares, every list it pins here, is sent to its reviewer as ever"() {
        given:
        grades()
        madeUnderOneRelease([RECEIPT, GRADE])
        model.answering(new CallOutcome.CameBack(ASSURES_BOTH, 200, 10, true, false))
        def logged = listening()
        def next = wiredFor(release([RECEIPT, GRADE]))

        when:
        next.engine.goOn(groupId(CodeWorkflow.GROUP), runId())
        until("the review ended") { store.count("select count(*) from model_calls where outcome is not null") == 1 }
        next.drained()

        then:
        reviewAttempts() == ["small ordinary false system -"]
        model.requests*.purpose() == [ModelCallPurpose.REVIEW]
        JsonMapper.builder().build().readValue(model.requests[0].sent().user(), Map).answer ==
                [receipt: "R-1", grade: "gold"]
        store.texts("""
                select review.created_by_kind || ' ' || decision.outcome
                  from reviews review join review_decisions decision on decision.review_id = review.review_id""") ==
                ["system assured", "system assured"]

        and:
        store.count("select count(*) from run_step_failures") == 0
        logged.list.findAll { it.level == Level.ERROR } == []

        cleanup:
        unlistened(logged)
    }

    /**
     * Send's try made under a first release whose process begins stopping as the code runs, so that what it gave
     * back is left unsent to the reviewer, as a process stopped for a deploy leaves it.
     */
    private void madeUnderOneRelease(List<Field> gives = [RECEIPT], String said = "R-1") {
        def gate = new CountDownLatch(1)
        def code = release(gives, said, gate)
        def first = wiredFor(code)
        started(first)
        until("the code is running") { code.runs.get() == 1 }
        first.closing()
        gate.countDown()
        first.drained()
        assert store.texts("select coalesce(lost_reason::text, 'yielded') from productions where producer = 'code'") ==
                ["yielded"]
        assert reviewAttempts() == []
    }

    /**
     * The code step as a release declares it, taking {@code takes}, giving back {@code gives}, saying {@code said}, and
     * run again only where {@code mayRunAgain}; a grade of gold beside the receipt where it declares one.
     */
    private static ScriptedCodeStep release(List<Field> gives, String said = "R-1", CountDownLatch gate = null,
                                            List<Field> takes = [CodeWorkflow.REPLY], boolean mayRunAgain = true) {
        new ScriptedCodeStep(CodeWorkflow.CODE_STEP, takes, gives, mayRunAgain, {
            def members = [new JsonValue.JsonMember("receipt", new JsonValue.JsonString(said))]
            if (GRADE in gives) {
                members << new JsonValue.JsonMember("grade", new JsonValue.JsonString("gold"))
            }
            new JsonValue.JsonObject(members)
        }, gate)
    }

    private EngineWiring wiredFor(ScriptedCodeStep code) {
        def next = new EngineWiring(store, model, CodeStepsHeld.of(code))
        wired << next
        next
    }

    /** Each attempt to review as its model and mode, whether nothing was sent, who wrote it, and why it went unbuilt. */
    private List<String> reviewAttempts() {
        store.texts("""
                select attempt.model || ' ' || attempt.mode || ' ' || attempt.too_long || ' ' || attempt.created_by_kind
                       || ' ' || coalesce(attempt.unbuilt_reason::text, '-')
                  from run_step_send_attempts attempt
                 where attempt.purpose = 'review' order by attempt.created_at, attempt.run_step_send_attempt_id
                """)
    }

    private StepPosition sendPosition(EngineWiring by) {
        def read = store.transactions().execute { by.snapshots.asRead(groupId(CodeWorkflow.GROUP), runId()) }
        StepPositions.of(read, read.step(new WorkflowStepId(UUID.fromString(CodeWorkflow.SEND))).orElseThrow())
    }

    /** Send as Ann, who oversees the group and reviews at its gate, reads it under the release {@code by} holds. */
    private StepAnswers.StepRowAnswer sendRow(EngineWiring by) {
        new RunSteps(store.session, new GroupRoles(store.session), by.snapshots, store.transactionManager())
                .step(groupId(CodeWorkflow.GROUP), runId(), new WorkflowStepId(UUID.fromString(CodeWorkflow.SEND)),
                        CodeWorkflow.ANN_USER)
                .step()
    }

    /** A reference list of the group's in service, offering the grades gold and silver. */
    private void grades() {
        def entry = GRADES.replace("00000007-", "00000006-")
        store.entry(entry, CodeWorkflow.GROUP, "reference_list", "Grades")
        store.seeded(GRADES, entry, 1)
        store.content(GRADES, "reference_list")
        ["gold", "silver"].eachWithIndex { term, index ->
            store.session.sql("""
                    insert into reference_list_terms (entry_version_id, position, term, meaning, created_by)
                    values (?::uuid, ?, ?, ?, ?::uuid)
                    """).params(GRADES, index + 1, term, "Graded " + term + ".", LibraryStore.SEEDER).update()
        }
    }

    /** Whatever the engine, its pools, its threads, its code and its calls log from now on. */
    private static SnapshottingAppender listening() {
        def logged = new SnapshottingAppender()
        logged.start()
        ENGINE_LOGGERS.each { it.addAppender(logged) }
        logged
    }

    private static void unlistened(SnapshottingAppender logged) {
        ENGINE_LOGGERS.each { it.detachAppender(logged) }
    }
}
