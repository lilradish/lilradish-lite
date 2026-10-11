package com.lilradish.lite.app.run

import static com.lilradish.lite.testutil.library.LibraryStore.groupId

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.lilradish.lite.app.group.GroupRoles
import com.lilradish.lite.app.run.fixture.CodeWorkflow
import com.lilradish.lite.domain.declaration.Demand
import com.lilradish.lite.domain.declaration.Field
import com.lilradish.lite.domain.declaration.FieldName
import com.lilradish.lite.domain.declaration.FieldShape
import com.lilradish.lite.domain.declaration.FieldStanding
import com.lilradish.lite.domain.declaration.HowMany
import com.lilradish.lite.domain.failure.RefusalCode
import com.lilradish.lite.domain.file.FileExtension
import com.lilradish.lite.domain.inference.CallOutcome
import com.lilradish.lite.domain.inference.ModelCallPurpose
import com.lilradish.lite.domain.inference.TurnAway
import com.lilradish.lite.domain.run.ReviewSending
import com.lilradish.lite.domain.run.RunId
import com.lilradish.lite.domain.run.StepPosition
import com.lilradish.lite.domain.run.StepPositions
import com.lilradish.lite.domain.run.WaitsOn
import com.lilradish.lite.domain.run.WorkflowStepId
import com.lilradish.lite.domain.wire.JsonValue
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.SnapshottingAppender
import com.lilradish.lite.testutil.codestep.CodeStepsHeld
import com.lilradish.lite.testutil.codestep.ScriptedCodeStep
import com.lilradish.lite.testutil.inference.ScriptedModelCalls
import com.lilradish.lite.testutil.library.LibraryStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.libprunus.core.error.ApiErrorException
import org.slf4j.LoggerFactory
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper

class CodeStepReviewIntegrationSpec extends Specification {

    static final String ASSURES = '{"decisions":{"receipt":{"outcome":"assured"}}}'

    static final Field RECEIPT = new Field(new FieldName("receipt"), null, null, new FieldShape.Text(64),
            new HowMany.One(), new Demand.Stands(true, FieldStanding.NEVER, null))

    static final Field RECEIPT_WIDER = new Field(new FieldName("receipt"), null, null, new FieldShape.Text(128),
            new HowMany.One(), new Demand.Stands(true, FieldStanding.NEVER, null))

    static final Field REFERENCE = new Field(new FieldName("reference"), null, null, new FieldShape.Text(64),
            new HowMany.One(), new Demand.Stands(true, FieldStanding.NEVER, null))

    static final Field REFERENCE_MAYBE = new Field(new FieldName("reference"), null, null, new FieldShape.Text(64),
            new HowMany.One(), new Demand.Stands(false, FieldStanding.NEVER, null))

    static final Field MESSAGE = new Field(new FieldName("message"), null, null, new FieldShape.Text(2000),
            new HowMany.One(), new Demand.Given(true))

    static final Field FILE_TAKEN = new Field(new FieldName("attachment"), null, null,
            new FieldShape.Nested([new Field(new FieldName("document"), null, null,
                    new FieldShape.File([new FileExtension(".pdf")], 1024L),
                    new HowMany.One(), new Demand.Given(false))]), new HowMany.One(), new Demand.Given(false))

    static final Field FILE_GIVEN = new Field(new FieldName("attachment"), null, null,
            new FieldShape.Nested([new Field(new FieldName("document"), null, null,
                    new FieldShape.File([new FileExtension(".pdf")], 1024L),
                    new HowMany.One(), new Demand.Given(false))]),
            new HowMany.One(), new Demand.Stands(false, FieldStanding.NEVER, null))

    static final String REFUSES = '{"decisions":{"receipt":{"outcome":"refused","words":"Name the ticket."}}}'

    static final String ABSENT_LIST = "00000007-0000-4000-8000-000000000cff"

    static final Field RECEIPT_OF_A_LIST_NOT_HERE = new Field(new FieldName("receipt"), null, null,
            new FieldShape.Term(LibraryStore.versionId(ABSENT_LIST)), new HowMany.One(),
            new Demand.Stands(true, FieldStanding.NEVER, null))

    static final String GRADES = "00000007-0000-4000-8000-000000000c09"

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
    TestPostgres server = TestPostgres.worker()

    @Shared
    int databasesMade = 0

    LibraryStore store

    ScriptedModelCalls model

    EngineWiring wiring

    List<EngineWiring> wired = []

    def setupSpec() {
        LibraryStore.prepareBaseline(server)
    }

    def setup() {
        store = LibraryStore.forCase(server, "code_step_review_" + (++databasesMade))
        model = new ScriptedModelCalls()
        CodeWorkflow.seed(store)
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
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
        model.answering(new CallOutcome.CameBack(ASSURES, 200, 10, true, false, null, null))

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
        // DB-SPECIFIC: PostgreSQL || converts enum operands to text.
        store.texts("""
                select review.created_by_kind || ' ' || decision.outcome
                  from reviews review join review_decisions decision on decision.review_id = review.review_id""") ==
                ["system assured"]
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count("""
                select count(*) from productions try join run_steps step on step.run_step_id = try.run_step_id
                 where step.workflow_step_id = ?::uuid""", CodeWorkflow.CHECK) == 1
    }

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
        row.stateActs() == ["review"]
        row.stateRefusals() == []

        and:
        model.requests == []
        store.count("select count(*) from model_calls") == 0
        store.count("select count(*) from run_step_failures") == 0
        store.count("select count(*) from reviews") == 0
        // DB-SPECIFIC: PostgreSQL ::text casts.
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
        [CodeWorkflow.REPLY, FILE_TAKEN] | [RECEIPT]          || ReviewSending.HOLDS_A_FILE             | "holds_a_file"
        [CodeWorkflow.REPLY] | [RECEIPT, FILE_GIVEN]       || ReviewSending.HOLDS_A_FILE             | "holds_a_file"
    }

    def "a later release widening only a text output still lets the model review"() {
        given:
        madeUnderOneRelease()
        model.answering(new CallOutcome.CameBack(ASSURES, 200, 10, true, false, null, null))
        def next = wiredFor(release([RECEIPT_WIDER]))

        when:
        next.engine.goOn(groupId(CodeWorkflow.GROUP), runId())
        until("the review ended") { store.count("select count(*) from model_calls where outcome is not null") == 1 }
        next.drained()

        then:
        reviewAttempts() == ["small ordinary false system -"]
        model.requests*.purpose() == [ModelCallPurpose.REVIEW]
        store.count("select count(*) from run_step_send_attempts where payload is not null") == 1
        store.count("select count(*) from run_step_failures") == 0
    }

    def "a person's answer reviewed after a refusal the running release no longer declares is left to a person, nothing thrown"() {
        given:
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("update workflow_steps set tries = 2 where workflow_step_id = ?::uuid")
                .params(CodeWorkflow.SEND).update()
        def answered = new JsonValue.JsonObject([
                new JsonValue.JsonMember("receipt", new JsonValue.JsonString("R-2")),
                new JsonValue.JsonMember("reference", new JsonValue.JsonString("T-9"))])
        model.answering(new CallOutcome.CameBack(REFUSES, 200, 10, true, false, null, null))
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

    def "a failed code review stays failed until Try sending hands its yielded value to a person"() {
        given:
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("update workflow_steps set reviewer_model = 'large' where workflow_step_id = ?::uuid")
                .params(CodeWorkflow.SEND).update()
        started()
        until("the review failed") { store.count("select count(*) from run_step_failures") == 1 }
        wiring.drained()
        def without = new EngineWiring(store, model, CodeStepsHeld.NONE)
        wired << without
        def beforeGoOn = store.contents()

        when: "the new release drives the failed run"
        without.engine.goOn(groupId(CodeWorkflow.GROUP), runId())
        without.drained()

        then:
        sendPosition(without) instanceof StepPosition.Failed
        with(sendRow(without)) {
            where().kind() == "failed"
            where().reason() == "model_not_deployed"
            where().reviewing() == true
            stateActs() == ["try_sending"]
        }
        store.contents() == beforeGoOn
        reviewAttempts() == []
        reviewFailures() == ["model_not_deployed review -"]
        model.requests == []

        when: "a person presses Try sending while the code step is absent"
        without.acts.trySending(groupId(CodeWorkflow.GROUP), runId(),
                new WorkflowStepId(UUID.fromString(CodeWorkflow.SEND)), CodeWorkflow.CAT_USER)
        without.drained()

        then:
        reviewAttempts() == ["large ordinary true system no_longer_declared"]
        reviewFailures() == ["model_not_deployed review no_longer_declared"]
        store.count("select count(*) from model_calls") == 0
        model.requests == []
        with(sendPosition(without) as StepPosition.AwaitingReview) {
            sending() == ReviewSending.NO_LONGER_DECLARED
            values()*.on() == [WaitsOn.REVIEW_AT_GATE]
        }
        with(sendRow(without)) {
            where().review() == "no_longer_declared"
            stateActs() == ["review"]
        }
        def afterHandoff = store.contents()

        when: "the code step returns and the run is driven again"
        def restored = wiredFor(release([RECEIPT]))
        restored.engine.goOn(groupId(CodeWorkflow.GROUP), runId())
        restored.drained()
        without.engine.goOn(groupId(CodeWorkflow.GROUP), runId())
        without.drained()

        then:
        store.contents() == afterHandoff
        reviewAttempts() == ["large ordinary true system no_longer_declared"]
        reviewFailures() == ["model_not_deployed review no_longer_declared"]
        (sendPosition(restored) as StepPosition.AwaitingReview).sending() == ReviewSending.NO_LONGER_DECLARED
        sendRow(restored).stateActs() == ["review"]
        model.requests == []

        when: "Try sending is pressed again after the handoff"
        restored.acts.trySending(groupId(CodeWorkflow.GROUP), runId(),
                new WorkflowStepId(UUID.fromString(CodeWorkflow.SEND)), CodeWorkflow.CAT_USER)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.TRY_SENDING_NOT_OFFERED
        store.contents() == afterHandoff
        reviewAttempts() == ["large ordinary true system no_longer_declared"]
        model.requests == []
    }

    def "Try sending a failed review while code is still held records the missing reviewer again"() {
        given:
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("update workflow_steps set reviewer_model = 'large' where workflow_step_id = ?::uuid")
                .params(CodeWorkflow.SEND).update()
        started()
        until("the review failed") { store.count("select count(*) from run_step_failures") == 1 }
        wiring.drained()

        when:
        wiring.acts.trySending(groupId(CodeWorkflow.GROUP), runId(),
                new WorkflowStepId(UUID.fromString(CodeWorkflow.SEND)), CodeWorkflow.CAT_USER)
        wiring.drained()

        then:
        reviewFailures() == ["model_not_deployed review -", "model_not_deployed review -"]
        reviewAttempts() == []
        store.count("select count(*) from model_calls") == 0
        model.requests == []
        sendPosition(wiring) instanceof StepPosition.Failed
        sendRow(wiring).stateActs() == ["try_sending"]
    }

    def "a review turned away becomes a persistent human review when the code step leaves the release"() {
        given:
        model.answering(new CallOutcome.TurnedAway(new TurnAway("Busy.", false)))
        started()
        until("the review was turned away") {
            store.count("select count(*) from model_calls where outcome is not null") == 1
        }
        wiring.drained()
        def without = new EngineWiring(store, model, CodeStepsHeld.NONE)
        wired << without
        def beforePress = store.contents()

        when: "Try sending is pressed before the new release has driven the run"
        without.acts.trySending(groupId(CodeWorkflow.GROUP), runId(),
                new WorkflowStepId(UUID.fromString(CodeWorkflow.SEND)), CodeWorkflow.CAT_USER)

        then:
        def refusedBeforeHandoff = thrown(ApiErrorException)
        refusedBeforeHandoff.errorCode() == RefusalCode.TRY_SENDING_NOT_OFFERED
        store.contents() == beforePress
        model.requests.size() == 1

        when: "the new release drives the run"
        without.engine.goOn(groupId(CodeWorkflow.GROUP), runId())
        without.drained()

        then:
        reviewAttempts() == ["small ordinary false system -", "small ordinary true system no_longer_declared"]
        with(sendPosition(without) as StepPosition.AwaitingReview) {
            sending() == ReviewSending.NO_LONGER_DECLARED
            values()*.on() == [WaitsOn.REVIEW_AT_GATE]
        }
        sendRow(without).stateActs() == ["review"]
        model.requests.size() == 1
        store.count("select count(*) from reviews") == 0
        store.count("select count(*) from run_step_failures") == 0
        def before = store.contents()

        when: "a press is attempted after the handoff"
        without.acts.trySending(groupId(CodeWorkflow.GROUP), runId(),
                new WorkflowStepId(UUID.fromString(CodeWorkflow.SEND)), CodeWorkflow.CAT_USER)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.TRY_SENDING_NOT_OFFERED
        store.contents() == before
        model.requests.size() == 1

        when: "the code step returns in a later release"
        def restored = wiredFor(release([RECEIPT]))
        restored.engine.goOn(groupId(CodeWorkflow.GROUP), runId())
        restored.drained()

        then:
        store.contents() == before
        (sendPosition(restored) as StepPosition.AwaitingReview).sending() == ReviewSending.NO_LONGER_DECLARED
        sendRow(restored).stateActs() == ["review"]
        model.requests.size() == 1
    }

    def "a review not yet sent becomes a persistent human review when the code step leaves the release"() {
        given:
        madeUnderOneRelease()
        def without = new EngineWiring(store, model, CodeStepsHeld.NONE)
        wired << without

        when:
        without.engine.goOn(groupId(CodeWorkflow.GROUP), runId())
        without.drained()

        then:
        reviewAttempts() == ["small ordinary true system no_longer_declared"]
        with(sendPosition(without) as StepPosition.AwaitingReview) {
            sending() == ReviewSending.NO_LONGER_DECLARED
            values()*.on() == [WaitsOn.REVIEW_AT_GATE]
        }
        sendRow(without).stateActs() == ["review"]
        model.requests == []
        store.count("select count(*) from model_calls") == 0
        store.count("select count(*) from reviews") == 0
        store.count("select count(*) from run_step_failures") == 0
    }

    def "what a code step gave back that the running release still declares, every list it pins here, is sent to its reviewer as ever"() {
        given:
        grades()
        madeUnderOneRelease([RECEIPT, GRADE])
        model.answering(new CallOutcome.CameBack(ASSURES_BOTH, 200, 10, true, false, null, null))
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
        // DB-SPECIFIC: PostgreSQL || converts enum operands to text.
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

    // Begin stopping before releasing code so its production is not sent for review.
    private void madeUnderOneRelease(List<Field> gives = [RECEIPT], String said = "R-1") {
        def gate = new CountDownLatch(1)
        def code = release(gives, said, gate)
        def first = wiredFor(code)
        started(first)
        until("the code is running") { code.runs.get() == 1 }
        first.closing()
        gate.countDown()
        first.drained()
        // DB-SPECIFIC: PostgreSQL ::text casts.
        assert store.texts("select coalesce(lost_reason::text, 'yielded') from productions where producer = 'code'") ==
                ["yielded"]
        assert reviewAttempts() == []
    }

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

    private List<String> reviewAttempts() {
        // DB-SPECIFIC: PostgreSQL ::text casts; || converts non-string operands to text.
        store.texts("""
                select attempt.model || ' ' || attempt.mode || ' ' || attempt.too_long || ' ' || attempt.created_by_kind
                       || ' ' || coalesce(attempt.unbuilt_reason::text, '-')
                  from run_step_send_attempts attempt
                 where attempt.purpose = 'review' order by attempt.created_at, attempt.run_step_send_attempt_id
                """)
    }

    private List<String> reviewFailures() {
        // DB-SPECIFIC: PostgreSQL ::text casts; || converts non-string operands to text.
        store.texts("""
                select failure.reason || ' ' || failure.purpose || ' '
                       || coalesce(attempt.unbuilt_reason::text, '-')
                  from run_step_failures failure
                  left join run_step_send_attempts attempt
                    on attempt.answers_failure_id = failure.run_step_failure_id
                 order by failure.created_at, failure.run_step_failure_id
                """)
    }

    private StepPosition sendPosition(EngineWiring by) {
        def read = store.transactions().execute { by.snapshots.asRead(groupId(CodeWorkflow.GROUP), runId()) }
        StepPositions.of(read, read.step(new WorkflowStepId(UUID.fromString(CodeWorkflow.SEND))).orElseThrow())
    }

    private StepAnswers.StepRowAnswer sendRow(EngineWiring by) {
        new RunSteps(store.session, new GroupRoles(store.session), by.snapshots, store.transactionManager(), Deployments.DEEP_ENOUGH, new com.lilradish.lite.app.currency.GroupCurrencies(store.session, new org.springframework.transaction.support.TransactionTemplate(store.transactionManager()), new GroupRoles(store.session), com.lilradish.lite.testutil.library.DeployedModels.HELD), com.lilradish.lite.testutil.library.DeployedModels.HELD)
                .step(groupId(CodeWorkflow.GROUP), runId(), new WorkflowStepId(UUID.fromString(CodeWorkflow.SEND)),
                        CodeWorkflow.ANN_USER)
                .step()
    }

    private void grades() {
        def entry = GRADES.replace("00000007-", "00000006-")
        store.entry(entry, CodeWorkflow.GROUP, "reference_list", "Grades")
        store.seeded(GRADES, entry, 1)
        store.content(GRADES, "reference_list")
        ["gold", "silver"].eachWithIndex { term, index ->
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("""
                    insert into reference_list_terms (entry_version_id, position, term, meaning, created_by)
                    values (?::uuid, ?, ?, ?, ?::uuid)
                    """).params(GRADES, index + 1, term, "Graded " + term + ".", LibraryStore.SEEDER).update()
        }
    }

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
