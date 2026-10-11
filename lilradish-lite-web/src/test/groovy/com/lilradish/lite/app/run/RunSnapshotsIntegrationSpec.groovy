package com.lilradish.lite.app.run

import static com.lilradish.lite.app.run.fixture.TicketWorkflow.ANN
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.APPROVED
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.CAT
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.CONFIRM
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.CONFIRM_QUESTION
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.CONFIRM_VERSION
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.GROUP
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.RUN
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARISE
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARISE_VERSION
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARY
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.TICKET_IN
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.VERSION
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.WORKFLOW
import static com.lilradish.lite.testutil.library.LibraryStore.FIRST_STEWARD
import static com.lilradish.lite.testutil.library.LibraryStore.SEEDER
import static com.lilradish.lite.testutil.library.LibraryStore.attempting
import static com.lilradish.lite.testutil.library.LibraryStore.groupId

import com.lilradish.lite.app.codestep.CodeRuns
import com.lilradish.lite.app.group.GroupRoles
import com.lilradish.lite.app.run.fixture.CodeWorkflow
import com.lilradish.lite.app.run.fixture.StepRows
import com.lilradish.lite.app.run.fixture.TicketWorkflow
import com.lilradish.lite.app.search.SearchWays
import com.lilradish.lite.domain.codestep.CodeStepDeclaration
import com.lilradish.lite.domain.codestep.ReleasedCodeStep
import com.lilradish.lite.domain.declaration.Declaration
import com.lilradish.lite.domain.declaration.DeclarationSide
import com.lilradish.lite.domain.declaration.Demands
import com.lilradish.lite.domain.declaration.FieldShape
import com.lilradish.lite.domain.declaration.OfferedTerms
import com.lilradish.lite.domain.identity.SubjectId
import com.lilradish.lite.domain.inference.DidNotFitReason
import com.lilradish.lite.domain.inference.ModelCallOutcome
import com.lilradish.lite.domain.inference.ModelCallPurpose
import com.lilradish.lite.domain.referencelist.Term
import com.lilradish.lite.domain.referencelist.TermMeaning
import com.lilradish.lite.domain.registry.EntryVersionId
import com.lilradish.lite.domain.run.AttemptRecord
import com.lilradish.lite.domain.run.CallRecord
import com.lilradish.lite.domain.run.HoldRecord
import com.lilradish.lite.domain.run.ModelCallId
import com.lilradish.lite.domain.run.ProductionValueId
import com.lilradish.lite.domain.run.ReviewRecord
import com.lilradish.lite.domain.run.RunId
import com.lilradish.lite.domain.run.RunInTree
import com.lilradish.lite.domain.run.RunStepFailureReason
import com.lilradish.lite.domain.run.RunStepHoldReason
import com.lilradish.lite.domain.run.RunStepSendAttemptId
import com.lilradish.lite.domain.run.StepInputs
import com.lilradish.lite.domain.run.StepRuns
import com.lilradish.lite.domain.run.StopRecord
import com.lilradish.lite.domain.run.TryLostReason
import com.lilradish.lite.domain.run.WorkflowStepId
import com.lilradish.lite.domain.wire.JsonValue
import com.lilradish.lite.domain.wire.StoreLabels
import com.lilradish.lite.domain.workflow.StepProducer
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.CountingDataSource
import com.lilradish.lite.testutil.codestep.CodeStepsHeld
import com.lilradish.lite.testutil.codestep.SpecCodeStep
import com.lilradish.lite.testutil.inference.ScriptedModelCalls
import com.lilradish.lite.testutil.library.DeployedModels
import com.lilradish.lite.testutil.library.LibraryStore
import com.lilradish.lite.testutil.run.RunRows
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class RunSnapshotsIntegrationSpec extends Specification {

    // DB-SPECIFIC: the SQL fixtures and predicates below input/compare PostgreSQL native enum labels.

    @Shared
    @AutoCleanup
    TestPostgres server = TestPostgres.worker()

    @Shared
    int databasesMade = 0

    LibraryStore store

    RunTree tree

    RunSnapshots snapshots

    @AutoCleanup("shutdownNow")
    ExecutorService racing = Executors.newFixedThreadPool(2)

    def setupSpec() {
        LibraryStore.prepareBaseline(server)
    }

    def setup() {
        store = LibraryStore.forCase(server, "run_snapshots_" + (++databasesMade),
                specificationContext.currentFeature.testTags*.value.contains("postgres-schema-changing"))
        tree = new RunTree(store.session)
        snapshots = new RunSnapshots(store.session, CodeStepsHeld.NONE, new SearchWays([]))
        TicketWorkflow.seed(store)
        TicketWorkflow.run(store)
    }

    static final String SYSTEM = RunRows.WORKFLOW_RUNNER

    static final String LONGEST_TERM = ((String.valueOf((char) 0xE9) + String.valueOf((char) 0x5B57)) * 63) +
            new String(Character.toChars(0x20000)) + "x"

    static final String SECOND_RUN = "00000008-0000-4000-8000-000000000e21"

    static final String THIRD_RUN = "00000008-0000-4000-8000-000000000e22"

    private static RunId runId(String spelled = RUN) {
        new RunId(UUID.fromString(spelled))
    }

    private static String runKey(int number) {
        String.format("00000008-0000-4000-8000-%012d", 900 + number)
    }

    private Instant stoppedAt(String entry) {
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("select created_at from entry_stops where entry_id = ?::uuid and let_go_at is null")
                .params(entry).query(OffsetDateTime).single().toInstant()
    }

    private void askedAndAnswered() {
        driven()
        StepRows.answered(store, SUMMARISE, CAT, "Read it twice.", [(SUMMARY): '"A printer fire."'])
    }

    private void driven() {
        def executor = EngineExecutors.of(store.database)
        def writes = new EngineWrites(store.session)
        def ceilings = new CeilingReach(store.session, DeployedModels.HELD, writes)
        def heldFiles = EngineWiring.files(store)
        new RunEngine(store.session, store.transactions(), tree, snapshots, writes, executor,
                new EngineCalls(EngineWiring.withoutTools(), new ScriptedModelCalls(), store.transactions(), tree, writes, ceilings, snapshots, heldFiles),
                new EngineCodes(new CodeRuns(CodeStepsHeld.NONE), store.transactions(), tree, snapshots, writes, executor, heldFiles),
                DeployedModels.HELD, ceilings, new GroupRoles(store.session), Deployments.DEEP_ENOUGH, heldFiles)
                .drive(groupId(GROUP), runId())
        executor.destroy()
    }

    private String byModel() {
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                update workflow_steps set producer = 'model', producer_model = 'general', producer_mode = 'ordinary',
                                          reviewer_model = 'general', reviewer_mode = 'ordinary'
                 where workflow_step_id = ?::uuid
                """).params(SUMMARISE).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts, RETURNING.
        store.session.sql("""
                insert into run_steps (run_id, entry_version_id, workflow_step_id, step_kind, pinned_version_id,
                                       pinned_kind, producer, reviewed_by_model, tries, created_by, created_by_kind)
                select ?::uuid, step.entry_version_id, step.workflow_step_id, step.kind, step.pinned_version_id,
                       step.pinned_kind, step.producer, step.reviewed_by_model, step.tries, ?::uuid, 'system'
                  from workflow_steps step
                 where step.workflow_step_id = ?::uuid
                returning cast(run_step_id as text)
                """).params(RUN, SYSTEM, SUMMARISE).query(String).single()
    }

    private String modelTry(String runStep, Integer number) {
        // DB-SPECIFIC: PostgreSQL ::uuid casts, RETURNING.
        store.session.sql("""
                insert into productions (run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                                         reviewed_by_model, tries, pinned_version_id, try_number, producer, created_by)
                values (?::uuid, ?::uuid, ?::uuid, 'question', 'model', true, 2, ?::uuid, ?, 'model', ?::uuid)
                returning cast(production_id as text)
                """).params(runStep, RUN, RUN, SUMMARISE_VERSION, number, SYSTEM).query(String).single()
    }

    private String attempt(String runStep, String production, String purpose, boolean tooLong) {
        // DB-SPECIFIC: PostgreSQL ::uuid casts, RETURNING, enum type model_call_purpose.
        store.session.sql("""
                insert into run_step_send_attempts (run_step_id, run_id, run_step_kind, workflow_step_id, purpose, model,
                                                    mode, production_id, too_long, payload, created_by, created_by_kind)
                values (?::uuid, ?::uuid, 'question', ?::uuid, cast(? as model_call_purpose), 'general', 'ordinary',
                        ?::uuid, ?, '{}', ?::uuid, 'system')
                returning cast(run_step_send_attempt_id as text)
                """).params(runStep, RUN, SUMMARISE, purpose, production, tooLong, SYSTEM).query(String).single()
    }

    private String called(String runStep, String production, String attempt, String purpose, String outcome) {
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now(), RETURNING, enum type model_call_purpose, enum type
        // model_call_outcome.
        store.session.sql("""
                insert into model_calls (run_id, root_run_id, purpose, run_step_send_attempt_id, run_step_id,
                                         production_id, model, mode, envelope_version, sent_count, came_back_count,
                                         outcome, answer, error_detail, ended_at, created_by)
                values (?::uuid, ?::uuid, cast(? as model_call_purpose), ?::uuid, ?::uuid, ?::uuid, 'general',
                        'ordinary', 1, 100, case when cast(? as text) = 'came_back' then 40 end,
                        cast(? as model_call_outcome), case when cast(? as text) = 'came_back' then '{}' end,
                        case when cast(? as text) = 'errored' then 'Went wrong.' end,
                        case when cast(? as text) is null then null else now() end, ?::uuid)
                returning cast(model_call_id as text)
                """).params(RUN, RUN, purpose, attempt, runStep, production, outcome, outcome, outcome, outcome, outcome,
                SYSTEM).query(String).single()
    }

    private void turnedAway(String call, boolean spentUp) {
        // DB-SPECIFIC: PostgreSQL ::model_call_outcome/::uuid casts.
        store.session.sql("""
                insert into model_call_turnaways (model_call_id, spent_up, model_call_outcome, created_by)
                values (?::uuid, ?, case when ? then 'turned_away'::model_call_outcome end, ?::uuid)
                """).params(call, spentUp, spentUp, SYSTEM).update()
    }

    private void held(String runStep, RunStepHoldReason reason, String attempt) {
        // DB-SPECIFIC: PostgreSQL ::uuid casts, enum type run_step_hold_reason.
        store.session.sql("""
                insert into run_step_holds (run_step_id, run_id, run_step_kind, calls_a_model, reason,
                                            run_step_send_attempt_id, created_by)
                select step.run_step_id, step.run_id, step.kind, step.calls_a_model, cast(? as run_step_hold_reason),
                       ?::uuid, ?::uuid
                  from run_steps step where step.run_step_id = ?::uuid
                """).params(StoreLabels.label(reason), attempt, SYSTEM, runStep).update()
    }

    private Instant heldSince() {
        store.session.sql("select created_at from run_step_holds").query(OffsetDateTime).single().toInstant()
    }

    private static RunStepSendAttemptId attemptId(String spelled) {
        new RunStepSendAttemptId(UUID.fromString(spelled))
    }

    private static ModelCallId callId(String spelled) {
        new ModelCallId(UUID.fromString(spelled))
    }

    def "reads a run as its rows hold it: its version's steps in order, and the tries each has made"() {
        given:
        askedAndAnswered()

        when:
        def read = snapshots.asRead(groupId(GROUP), runId())

        then: "the run, its version and what it was started with"
        read.run() == runId()
        read.root() == runId()
        read.version().value() == UUID.fromString(VERSION)
        !read.stopped()
        read.workflowStopped() == null
        read.startedWith() == new JsonValue.JsonObject([
                new JsonValue.JsonMember("ticket", new JsonValue.JsonString("The printer is on fire."))])

        and: "both steps, in order, each running the question it pins"
        read.steps()*.planned()*.id() == [SUMMARISE, CONFIRM].collect { new WorkflowStepId(UUID.fromString(it)) }
        read.steps()*.planned()*.runs()*.getClass() == [StepRuns.Question, StepRuns.Question]

        and: "summarise's one try, asked by the system, answered by Cat with a value that waits on a review"
        def asked = read.steps()[0]
        asked.runStep() != read.steps()[1].runStep()
        asked.tries().size() == 1
        with(asked.tries()[0]) {
            number() == 1
            producer() == StepProducer.PERSON
            askedBy() == null
            endedBy() == new SubjectId(UUID.fromString(CAT))
            explanation() == "Read it twice."
            lost() == null
            values()*.field() == ["summary"]
            values()*.value() == [new JsonValue.JsonString("A printer fire.")]
            values()*.needsReview() == [true]
            reviews() == []
            inputs()*.binding() == [UUID.fromString(TICKET_IN)]
            inputs()*.source() == [null]
        }

        and: "confirm not written at all"
        read.steps()[1].runStep() == null
        read.steps()[1].tries() == []
        read.steps()[1].hold() == null
    }

    def "reads who stopped what the workflow, and what a step runs, and since when, and a hold not released"() {
        given:
        store.stopped(WORKFLOW, FIRST_STEWARD)
        store.stopped(CONFIRM_QUESTION, ANN)
        driven()

        when:
        def read = snapshots.asRead(groupId(GROUP), runId())

        then:
        read.workflowStopped() == new StopRecord(new SubjectId(UUID.fromString(FIRST_STEWARD)), stoppedAt(WORKFLOW))
        read.steps()[0].pinStopped() == null
        read.steps()[1].pinStopped() == new StopRecord(new SubjectId(UUID.fromString(ANN)), stoppedAt(CONFIRM_QUESTION))
        read.steps()[0].hold().reason() == RunStepHoldReason.ENTRY_STOPPED
        read.steps()[0].hold().attempt() == null
        !read.steps()[0].hold().spentUp()

        and: "and the run itself not stopped, a stop on an entry being none on the run"
        !read.stopped()
    }

    def "reads each try's attempts to send it and the calls they sent, one still out, and a call to the helper on none"() {
        given: "summarise's first try produced by the model and reviewed by it, the review going wrong"
        def runStep = byModel()
        def first = modelTry(runStep, 1)
        def producing = attempt(runStep, first, "produce", false)
        def cameBack = called(runStep, first, producing, "produce", "came_back")
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
        store.session.sql("""
                update productions set model_call_id = ?::uuid, model_call_outcome = 'came_back', ended_at = now(),
                                       ended_by = ?::uuid, ended_by_kind = 'system'
                 where production_id = ?::uuid
                """).params(cameBack, SYSTEM, first).update()
        def reviewing = attempt(runStep, first, "review", false)
        def wentWrong = called(runStep, first, reviewing, "review", "errored")

        and: "its second try's call still out, and the run's helper asked something"
        def second = modelTry(runStep, 2)
        def sending = attempt(runStep, second, "produce", false)
        def out = called(runStep, second, sending, "produce", null)
        RunRows.called(store, RUN, "came_back", 10, 5)
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def helped = store.texts("select model_call_id::text from model_calls where purpose = 'help'")

        when:
        def read = snapshots.asRead(groupId(GROUP), runId()).steps()[0]

        then:
        read.tries()*.attempts() == [
                [new AttemptRecord(attemptId(producing), ModelCallPurpose.PRODUCE, false, null),
                 new AttemptRecord(attemptId(reviewing), ModelCallPurpose.REVIEW, false, null)],
                [new AttemptRecord(attemptId(sending), ModelCallPurpose.PRODUCE, false, null)]]
        read.tries()*.calls() == [
                [new CallRecord(callId(cameBack), attemptId(producing), ModelCallOutcome.CAME_BACK, false),
                 new CallRecord(callId(wentWrong), attemptId(reviewing), ModelCallOutcome.ERRORED, false)],
                [new CallRecord(callId(out), attemptId(sending), null, false)]]
        read.hold() == null

        and: "the helper's call on no try"
        helped.size() == 1
        !read.tries()*.calls().flatten()*.id().contains(callId(helped[0]))
    }

    def "reads a hold on an attempt too long to send as naming that attempt, which sent nothing"() {
        given:
        def runStep = byModel()
        def open = modelTry(runStep, null)
        def sending = attempt(runStep, open, "produce", true)
        held(runStep, RunStepHoldReason.TOO_LONG, sending)

        when:
        def read = snapshots.asRead(groupId(GROUP), runId()).steps()[0]

        then:
        read.hold() == new HoldRecord(RunStepHoldReason.TOO_LONG, heldSince(), attemptId(sending), false, null)
        read.tries()[0].attempts() == [new AttemptRecord(attemptId(sending), ModelCallPurpose.PRODUCE, true, null)]
        read.tries()[0].calls() == []
    }

    def "reads a hold on a turned-away call as spent up only where that call was, whatever an earlier call was"() {
        given: "an earlier attempt of the open try turned away as spent up"
        def runStep = byModel()
        def open = modelTry(runStep, 1)
        def earlier = attempt(runStep, open, "produce", false)
        def earlierCall = called(runStep, open, earlier, "produce", "turned_away")
        turnedAway(earlierCall, true)

        and: "the attempt the step is held back for turned away, spent up or not"
        def sending = attempt(runStep, open, "produce", false)
        def call = called(runStep, open, sending, "produce", "turned_away")
        turnedAway(call, spentUp)
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("update productions set try_number = null where production_id = ?::uuid").param(open).update()
        held(runStep, RunStepHoldReason.TURNED_AWAY, sending)

        when:
        def read = snapshots.asRead(groupId(GROUP), runId()).steps()[0]

        then:
        read.hold() == new HoldRecord(RunStepHoldReason.TURNED_AWAY, heldSince(), attemptId(sending), spentUp, null)
        read.tries()[0].calls() == [
                new CallRecord(callId(earlierCall), attemptId(earlier), ModelCallOutcome.TURNED_AWAY, true),
                new CallRecord(callId(call), attemptId(sending), ModelCallOutcome.TURNED_AWAY, spentUp)]

        where:
        spentUp << [true, false]
    }

    def "reads a model's review as it ended, saying why it did not fit exactly where it did not"() {
        given: "summarise's try produced by the model, and the model's review of it ended"
        def runStep = byModel()
        def first = modelTry(runStep, 1)
        def producing = attempt(runStep, first, "produce", false)
        def cameBack = called(runStep, first, producing, "produce", "came_back")
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
        store.session.sql("""
                update productions set model_call_id = ?::uuid, model_call_outcome = 'came_back', ended_at = now(),
                                       ended_by = ?::uuid, ended_by_kind = 'system'
                 where production_id = ?::uuid
                """).params(cameBack, SYSTEM, first).update()
        def reviewing = attempt(runStep, first, "review", false)
        def reviewCall = called(runStep, first, reviewing, "review", outcome)
        // DB-SPECIFIC: PostgreSQL ::uuid casts, enum type model_call_outcome, enum type try_lost_reason, enum
        // type did_not_fit_reason.
        store.session.sql("""
                insert into reviews (production_id, run_step_id, root_run_id, production_ended_by, reviewed_by_model,
                                     model_call_id, model_call_outcome, lost_reason, did_not_fit_reason, created_by,
                                     created_by_kind)
                values (?::uuid, ?::uuid, ?::uuid, ?::uuid, true, ?::uuid, cast(? as model_call_outcome),
                        cast(? as try_lost_reason), cast(? as did_not_fit_reason), ?::uuid, 'system')
                """).params(first, runStep, RUN, SYSTEM, reviewCall, outcome, lost, misfit, SYSTEM).update()
        def reviewedAt = store.session.sql("select created_at from reviews").query(OffsetDateTime).single().toInstant()

        when:
        def read = snapshots.asRead(groupId(GROUP), runId()).steps()[0].tries()[0]

        then:
        read.reviews() == [new ReviewRecord(null, reviewedAt, false,
                StoreLabels.parse(TryLostReason, lost), misfit == null ? null : StoreLabels.parse(DidNotFitReason, misfit),
                [])]

        where:
        outcome             | lost                | misfit
        "came_back"         | "did_not_fit"       | "undecided"
        "came_back"         | "did_not_fit"       | "words_missing"
        "errored"           | "errored"           | null
        "nothing_came_back" | "nothing_came_back" | null
    }

    def "reads an attempt to review that was too long to send as that, beside the call an earlier one sent"() {
        given:
        def runStep = byModel()
        def first = modelTry(runStep, 1)
        def producing = attempt(runStep, first, "produce", false)
        def cameBack = called(runStep, first, producing, "produce", "came_back")
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
        store.session.sql("""
                update productions set model_call_id = ?::uuid, model_call_outcome = 'came_back', ended_at = now(),
                                       ended_by = ?::uuid, ended_by_kind = 'system'
                 where production_id = ?::uuid
                """).params(cameBack, SYSTEM, first).update()
        def reviewing = attempt(runStep, first, "review", false)
        def turned = called(runStep, first, reviewing, "review", "turned_away")
        def tooLong = attempt(runStep, first, "review", true)

        when:
        def read = snapshots.asRead(groupId(GROUP), runId()).steps()[0].tries()[0]

        then:
        read.attempts().findAll { it.purpose() == ModelCallPurpose.REVIEW } == [
                new AttemptRecord(attemptId(reviewing), ModelCallPurpose.REVIEW, false, null),
                new AttemptRecord(attemptId(tooLong), ModelCallPurpose.REVIEW, true, null)]
        read.calls().findAll { it.attempt() == attemptId(reviewing) } ==
                [new CallRecord(callId(turned), attemptId(reviewing), ModelCallOutcome.TURNED_AWAY, false)]
        read.calls().every { it.attempt() != attemptId(tooLong) }
        read.reviews() == []
    }

    def "reads a stop in force on the tree, and none once it is opened again"() {
        given:
        RunRows.stopped(store, RUN, CAT)

        expect:
        snapshots.asRead(groupId(GROUP), runId()).stopped()

        when:
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
        store.session.sql("update run_stops set opened_again_at = now(), opened_again_by = ?::uuid").params(ANN).update()

        then:
        !snapshots.asRead(groupId(GROUP), runId()).stopped()
    }

    def "reads runs of a group together in the order given, each as it reads alone"() {
        given:
        askedAndAnswered()
        TicketWorkflow.run(store, SECOND_RUN, 2)
        TicketWorkflow.run(store, THIRD_RUN, 3)
        RunRows.stopped(store, THIRD_RUN, CAT)
        def asked = [runId(THIRD_RUN), runId(), runId(SECOND_RUN)]

        when:
        def read = snapshots.asRead(groupId(GROUP), asked)

        then:
        read == asked.collect { snapshots.asRead(groupId(GROUP), it) }
        read*.run() == asked

        and: "each with its own rows and nothing of another's"
        read[1].steps()[0].tries().size() == 1
        read[0].steps().every { it.tries().isEmpty() }
        read*.stopped() == [true, false, false]
    }

    def "reads a group's runs together in as many statements however many runs and versions there are"() {
        given:
        askedAndAnswered()
        (2..6).each { runOfItsOwnVersion(it) }
        def statements = []
        def counted = new RunSnapshots(JdbcClient.create(new CountingDataSource(store.database, statements)),
                CodeStepsHeld.NONE, new SearchWays([]))

        when:
        counted.asRead(groupId(GROUP), [runId()])
        def one = statements.size()
        statements.clear()
        def read = counted.asRead(groupId(GROUP), [runId()] + (2..6).collect { runId(runKey(it)) })

        then:
        statements.size() == one
        one == 22

        and: "counted over six versions, each run read of its own"
        read*.version().unique().size() == 6
    }

    def "reads what each list a step's question pins offers, and what each list the workflow itself pins offers"() {
        given:
        def asked = offering("00000007-0000-4000-8000-000000000e31", "Hardware")
        def taken = offering("00000007-0000-4000-8000-000000000e32", "Printer")
        termField(CONFIRM_VERSION, "question", "gives", 3, "category", asked, "always")
        termField(VERSION, "workflow", "takes", 2, "kind", taken, null)

        when:
        def read = snapshots.asRead(groupId(GROUP), runId())

        then: "the question confirm pins holds the list it gives a term of, and nothing the workflow pins"
        (read.steps()[1].planned().runs() as StepRuns.Question).lists() ==
                [(versionId(asked)): offered("Hardware")]
        (read.steps()[0].planned().runs() as StepRuns.Question).lists() == [:]

        and: "the workflow holds the list it takes a term of, and nothing its steps' questions pin"
        read.workflow().lists() == [(versionId(taken)): offered("Printer")]
    }

    private String offering(String version, String term, String group = GROUP) {
        def entry = version.replace("00000007-", "00000006-")
        store.entry(entry, group, "reference_list", "Kinds of " + term)
        store.seeded(version, entry, 1)
        store.content(version, "reference_list")
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into reference_list_terms (entry_version_id, position, term, meaning, created_by)
                values (?::uuid, 1, ?, ?, ?::uuid)
                """).params(version, term, "Said of " + term + ".", SEEDER).update()
        version
    }

    private void termField(String holder, String kind, String side, int position, String name, String list,
                           String standing) {
        // DB-SPECIFIC: PostgreSQL ::declaration_side/::entry_kind/::field_standing/::uuid casts.
        store.session.sql("""
                insert into declaration_fields (entry_version_id, entry_kind, side, position, name, kind,
                                                term_list_version_id, must_be_given, standing, created_by)
                values (?::uuid, ?::entry_kind, ?::declaration_side, ?, ?, 'term', ?::uuid, false,
                        ?::field_standing, ?::uuid)
                """).params(holder, kind, side, position, name, list, standing, SEEDER).update()
    }

    private static OfferedTerms offered(String term) {
        new OfferedTerms([new OfferedTerms.Offered(new Term(term), new TermMeaning("Said of " + term + "."))], null)
    }

    private static EntryVersionId versionId(String spelled) {
        new EntryVersionId(UUID.fromString(spelled))
    }

    private void runOfItsOwnVersion(int number) {
        def entry = String.format("00000006-0000-4000-8000-%012d", 900 + number)
        def version = String.format("00000007-0000-4000-8000-%012d", 900 + number)
        def summarise = UUID.randomUUID().toString()
        def confirm = UUID.randomUUID().toString()
        StepRows.workflow(store, entry, version, GROUP, "Handle a ticket, kept " + number)
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: version, ownerKind: "workflow", side: "takes",
                               position: 1, name: "ticket", limit: 4000, mustBe: true])
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: version, ownerKind: "workflow", side: "gives",
                               position: 1, name: "result", limit: 1000, mustBe: true])
        StepRows.step(store, summarise, version, 1, "summarise", TicketWorkflow.SUMMARISE_VERSION, "person", 2)
        StepRows.step(store, confirm, version, 2, "confirm", CONFIRM_VERSION, "person", 1)
        StepRows.binding(store, UUID.randomUUID().toString(), version, summarise, "text", null, "ticket")
        StepRows.binding(store, UUID.randomUUID().toString(), version, confirm, "summary", summarise, "summary")
        StepRows.binding(store, UUID.randomUUID().toString(), version, null, "result", summarise, "summary")
        StepRows.run(store, runKey(number), GROUP, number, entry, version, CAT, TicketWorkflow.STARTED_WITH)
    }

    private Map<String, String> nestedTree(boolean startChildren = true) {
        def rootEntry = UUID.randomUUID().toString()
        def rootVersion = UUID.randomUUID().toString()
        def childEntry = UUID.randomUUID().toString()
        def childVersion = UUID.randomUUID().toString()
        def leafEntry = UUID.randomUUID().toString()
        def leafVersion = UUID.randomUUID().toString()
        def firstStep = UUID.randomUUID().toString()
        def secondStep = UUID.randomUUID().toString()
        def nestedStep = UUID.randomUUID().toString()
        def root = UUID.randomUUID().toString()
        def first = UUID.randomUUID().toString()
        def second = UUID.randomUUID().toString()
        def firstLeaf = UUID.randomUUID().toString()
        def secondLeaf = UUID.randomUUID().toString()
        StepRows.workflow(store, rootEntry, rootVersion, GROUP, "A nested root")
        StepRows.workflow(store, childEntry, childVersion, GROUP, "A child")
        StepRows.workflow(store, leafEntry, leafVersion, GROUP, "A leaf")
        StepRows.askingSomebody(store, leafVersion, GROUP)
        store.step(firstStep, rootVersion, 1, childVersion, "workflow")
        store.step(secondStep, rootVersion, 2, childVersion, "workflow")
        store.step(nestedStep, childVersion, 1, leafVersion, "workflow")
        StepRows.run(store, root, GROUP, 21, rootEntry, rootVersion, CAT, '{"marker":"Demo Sample private"}')
        if (startChildren) {
            childRun(root, first, 22, firstStep, childEntry, childVersion)
            childRun(root, second, 23, secondStep, childEntry, childVersion)
            childRun(first, firstLeaf, 24, nestedStep, leafEntry, leafVersion)
            childRun(second, secondLeaf, 25, nestedStep, leafEntry, leafVersion)
        }
        [root: root, first: first, second: second, firstLeaf: firstLeaf, secondLeaf: secondLeaf,
         firstStep: firstStep, secondStep: secondStep, nestedStep: nestedStep,
         childVersion: childVersion, leafVersion: leafVersion]
    }

    private void childRun(String above, String run, int number, String step, String entry, String version) {
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        def aboveVersion = store.texts("select entry_version_id::text from runs where run_id = ?::uuid", above)[0]
        // DB-SPECIFIC: PostgreSQL ::uuid casts, RETURNING.
        def storedStep = store.session.sql("""
                insert into run_steps (run_id, entry_version_id, workflow_step_id, step_kind,
                                       pinned_version_id, pinned_kind, reviewed_by_model, created_by)
                values (?::uuid, ?::uuid, ?::uuid, 'entry', ?::uuid, 'workflow', false, ?::uuid)
                returning cast(run_step_id as text)
                """).params(above, aboveVersion, step, version, SYSTEM).query(String).single()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into runs (run_id, group_id, number, entry_id, entry_version_id, parent_run_id,
                                  parent_run_step_id, parent_run_step_kind, parent_workflow_step_id, root_run_id,
                                  depth, parent_depth, created_by, created_by_kind)
                select ?::uuid, ?::uuid, ?, ?::uuid, ?::uuid, parent.run_id, ?::uuid, 'workflow', ?::uuid,
                       parent.root_run_id, parent.depth + 1, parent.depth, ?::uuid, 'system'
                  from runs parent where parent.run_id = ?::uuid
                """).params(run, GROUP, number, entry, version, storedStep, step, SYSTEM, above).update()
    }

    def "a child writer numbers a pinned run under the locked root, and one beneath it a level further down"() {
        given:
        def nested = nestedTree(false)
        def writes = new EngineWrites(store.session)
        def started = store.transactions().execute {
            def locked = tree.lock(groupId(GROUP), runId(nested.root)).orElseThrow()
            def root = snapshots.locked(locked, runId(nested.root))
            writes.beneath(locked, root, root.steps()[0],
                    new EntryVersionId(UUID.fromString(nested.childVersion)), null, null)
        }

        when:
        def leaf = store.transactions().execute {
            def locked = tree.lock(groupId(GROUP), runId(nested.root)).orElseThrow()
            def child = snapshots.locked(locked, started)
            writes.beneath(locked, child, child.steps()[0],
                    new EntryVersionId(UUID.fromString(nested.leafVersion)), null, null)
        }

        then:
        // DB-SPECIFIC: PostgreSQL ::uuid casts; text || non-string renders numeric, enum and UUID operands.
        store.texts("""
                select number || ' ' || depth || ' ' || created_by_kind || ' ' || parent_run_id from runs
                 where root_run_id = ?::uuid and parent_run_id is not null order by number
                """, nested.root) == ["22 1 system ${nested.root}" as String, "23 2 system ${started.value()}" as String]
        leaf != started
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count("select count(*) from runs where root_run_id = ?::uuid", nested.root) == 3
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count("select count(*) from run_steps where run_id = ?::uuid", leaf.value().toString()) == 0
    }

    def "a step already owning a child cannot start another child"() {
        given:
        def nested = nestedTree(false)
        def writes = new EngineWrites(store.session)
        def first = store.transactions().execute {
            def locked = tree.lock(groupId(GROUP), runId(nested.root)).orElseThrow()
            def root = snapshots.locked(locked, runId(nested.root))
            writes.beneath(locked, root, root.steps()[0],
                    new EntryVersionId(UUID.fromString(nested.childVersion)), null, null)
        }

        when:
        store.transactions().executeWithoutResult {
            def locked = tree.lock(groupId(GROUP), runId(nested.root)).orElseThrow()
            def root = snapshots.locked(locked, runId(nested.root))
            writes.beneath(locked, root, root.steps()[0],
                    new EntryVersionId(UUID.fromString(nested.childVersion)), null, null)
        }

        then:
        thrown(DataIntegrityViolationException)
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        store.texts("select run_id::text from runs where parent_run_id = ?::uuid", nested.root) ==
                [first.value().toString()]
    }

    def "reads each requested member with its own descendants and finds its path from the root"() {
        given:
        def nested = nestedTree()

        when:
        def root = snapshots.asRead(groupId(GROUP), runId(nested.root))
        def child = snapshots.asRead(groupId(GROUP), runId(nested.second))
        def context = snapshots.inTreeAsRead(groupId(GROUP), runId(nested.secondLeaf))

        then: "each child attaches by its stored run step, even when the workflow step keys repeat"
        root.steps()*.planned()*.id()*.value() == [nested.firstStep, nested.secondStep].collect(UUID::fromString)
        root.steps()*.beneath()*.run()*.run() == [runId(nested.first), runId(nested.second)]
        root.steps()[0].beneath().run().steps()[0].beneath().run().run() == runId(nested.firstLeaf)
        root.steps()[1].beneath().run().steps()[0].beneath().run().run() == runId(nested.secondLeaf)
        root.steps()*.beneath()*.routeCase() == [null, null]

        and: "a member read begins there, while contextual reading also names both ancestors"
        child.run() == runId(nested.second)
        child.steps()[0].beneath().run().run() == runId(nested.secondLeaf)
        context.run().run() == runId(nested.secondLeaf)
        context.above().run().run() == runId(nested.second)
        context.above().above().run().run() == runId(nested.root)
        context.from().planned().id().value() == UUID.fromString(nested.nestedStep)
        context.above().from().planned().id().value() == UUID.fromString(nested.secondStep)

        and: "logging a context or child link reveals neither the inputs nor the ancestors"
        context.toString() == "RunInTree[run=${nested.secondLeaf}]" as String
        root.steps()[0].beneath().toString() == "Beneath[run=${nested.first}]" as String
        !context.toString().contains("Demo Sample private")
    }

    def "each overview entry prepares a try projection without reading either kept code text"() {
        given:
        askedAndAnswered()
        def nested = nestedTree()
        def statements = []
        def counted = new RunSnapshots(JdbcClient.create(new CountingDataSource(store.database, statements)),
                CodeStepsHeld.NONE, new SearchWays([]))

        when:
        switch (entry) {
            case 'root':
                counted.asRead(groupId(GROUP), runId())
                break
            case 'list':
                counted.asRead(groupId(GROUP), [runId(), runId(nested.root)])
                break
            case 'child':
                counted.asRead(groupId(GROUP), runId(nested.second))
                break
            case 'inTree':
                counted.inTreeAsRead(groupId(GROUP), runId(nested.secondLeaf))
                break
        }
        def projections = statements.findAll { it.contains('select try.production_id') }

        then:
        projections.size() == 1
        projections.every { sql ->
            sql.contains('cast(null as text) as lost_detail') &&
                    sql.contains('cast(null as text) as returned_by_code') &&
                    sql.contains('try.lost_detail is not null as lost_detail_present') &&
                    sql.contains('try.returned_by_code is not null as returned_present')
        }

        where:
        entry << ['root', 'list', 'child', 'inTree']
    }

    def "reading a nested tree adds no statement for each descendant"() {
        given:
        def nested = nestedTree()
        def statements = []
        def counted = new RunSnapshots(JdbcClient.create(new CountingDataSource(store.database, statements)),
                CodeStepsHeld.NONE, new SearchWays([]))

        when:
        counted.asRead(groupId(GROUP), [runId()])
        def one = statements.size()
        statements.clear()
        def read = counted.asRead(groupId(GROUP), [runId(), runId(nested.root), runId(nested.second)])

        then:
        statements.size() == one
        read*.run() == [runId(), runId(nested.root), runId(nested.second)]
        read[1].steps()[1].beneath().run().steps()[0].beneath().run().run() == runId(nested.secondLeaf)
    }

    def "a held tree read locates a leaf beneath its actual parent step"() {
        given:
        def nested = nestedTree()

        when:
        def context = store.transactions().execute {
            snapshots.inTreeLocked(tree.lock(groupId(GROUP), runId(nested.root)).orElseThrow(),
                    runId(nested.secondLeaf))
        }

        then:
        context.run().run() == runId(nested.secondLeaf)
        context.above().run().run() == runId(nested.second)
        context.above().above().run().run() == runId(nested.root)
        context.from().planned().id().value() == UUID.fromString(nested.nestedStep)
        context.above().from().planned().id().value() == UUID.fromString(nested.secondStep)
        context.run().steps().every { it.beneath() == null }
    }

    def "a held tree context refuses another root before reading its members or locking entries"() {
        given:
        def nested = nestedTree()
        def statements = []
        def counted = new RunSnapshots(JdbcClient.create(new CountingDataSource(store.database, statements)),
                CodeStepsHeld.NONE, new SearchWays([]))

        when:
        store.transactions().executeWithoutResult {
            counted.inTreeLocked(tree.lock(groupId(GROUP), runId()).orElseThrow(), runId(nested.secondLeaf))
        }

        then:
        thrown(IllegalStateException)
        statements.size() == 1
        statements[0].contains("run.root_run_id")
    }

    def "finds a child deep in a wide tree without confusing its siblings"() {
        given:
        def rootEntry = UUID.randomUUID().toString()
        def rootVersion = UUID.randomUUID().toString()
        def childEntry = UUID.randomUUID().toString()
        def childVersion = UUID.randomUUID().toString()
        def root = UUID.randomUUID().toString()
        StepRows.workflow(store, rootEntry, rootVersion, GROUP, "A wide root")
        StepRows.workflow(store, childEntry, childVersion, GROUP, "A child")
        def siblings = (1..24).collect { number ->
            def step = UUID.randomUUID().toString()
            def child = UUID.randomUUID().toString()
            store.step(step, rootVersion, number, childVersion, "workflow")
            [step: step, child: child, number: number]
        }
        StepRows.run(store, root, GROUP, 31, rootEntry, rootVersion, CAT, '{}')
        siblings.each { childRun(root, it.child, 31 + it.number, it.step, childEntry, childVersion) }

        when:
        def context = snapshots.inTreeAsRead(groupId(GROUP), runId(siblings[0].child))
        def rootRead = snapshots.asRead(groupId(GROUP), runId(root))

        then:
        context.run().run() == runId(siblings[0].child)
        context.above().run().run() == runId(root)
        context.from().planned().id().value() == UUID.fromString(siblings[0].step)
        rootRead.steps()*.beneath()*.run()*.run() == siblings.collect { runId(it.child) }
    }

    private Map<String, String> compositeChild() {
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("update declaration_fields set standing = 'always' where declaration_field_id = ?::uuid")
                .params(SUMMARY).update()
        driven()
        StepRows.answered(store, SUMMARISE, CAT, "Answered.", [(SUMMARY): '"Earlier"'])
        driven()
        StepRows.answered(store, CONFIRM, CAT, "Confirmed.", [(APPROVED): '"true"'])

        def childEntry = UUID.randomUUID().toString()
        def childVersion = UUID.randomUUID().toString()
        def childQuestion = UUID.randomUUID().toString()
        def childQuestionVersion = UUID.randomUUID().toString()
        def parentStep = UUID.randomUUID().toString()
        def childStep = UUID.randomUUID().toString()
        def customer = UUID.randomUUID().toString()
        def askedCustomer = UUID.randomUUID().toString()
        StepRows.workflow(store, childEntry, childVersion, GROUP, "A composite child")
        StepRows.field(store, [id: customer, owner: childVersion, ownerKind: "workflow", side: "takes",
                               position: 1, name: "customer", kind: "fields", mustBe: true])
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: childVersion, ownerKind: "workflow",
                               side: "takes", parent: customer, position: 1, name: "summary", mustBe: true])
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: childVersion, ownerKind: "workflow",
                               side: "takes", parent: customer, position: 2, name: "approved", kind: "yes_no",
                               mustBe: true])
        StepRows.question(store, childQuestion, childQuestionVersion, GROUP, "Read composite input")
        StepRows.field(store, [id: askedCustomer, owner: childQuestionVersion, ownerKind: "question", side: "takes",
                               position: 1, name: "customer", kind: "fields", mustBe: true])
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: childQuestionVersion, ownerKind: "question",
                               side: "takes", parent: askedCustomer, position: 1, name: "summary", mustBe: true])
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: childQuestionVersion, ownerKind: "question",
                               side: "takes", parent: askedCustomer, position: 2, name: "approved", kind: "yes_no",
                               mustBe: true])
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: childQuestionVersion, ownerKind: "question",
                               side: "gives", position: 1, name: "read", standing: "always", mustBe: true])
        StepRows.step(store, childStep, childVersion, 1, "read_customer", childQuestionVersion, "person", 1)
        StepRows.binding(store, UUID.randomUUID().toString(), childVersion, childStep, "customer", null, "customer")
        store.step(parentStep, VERSION, 3, childVersion, "workflow")
        StepRows.binding(store, UUID.randomUUID().toString(), VERSION, parentStep, "customer.summary", SUMMARISE,
                "summary")
        StepRows.binding(store, UUID.randomUUID().toString(), VERSION, parentStep, "customer.approved", CONFIRM,
                "approved")
        def writes = new EngineWrites(store.session)
        def child = store.transactions().execute {
            def locked = tree.lock(groupId(GROUP), runId()).orElseThrow()
            def root = snapshots.locked(locked, runId())
            def step = root.steps().find { it.planned().id().value().toString() == parentStep }
            writes.beneath(locked, root, step, new EntryVersionId(UUID.fromString(childVersion)), null, null)
        }
        store.transactions().executeWithoutResult {
            def locked = tree.lock(groupId(GROUP), runId()).orElseThrow()
            def at = snapshots.inTreeLocked(locked, child)
            def step = at.run().steps()[0]
            def takes = ((StepRuns.Question) step.planned().runs()).takes()
            def inputs = StepInputs.traced(at, step, takes).orElseThrow()
            def held = writes.runStep(locked, at.run(), step)
            writes.tried(locked, at.run(), held, 1, StepProducer.PERSON, null, null, inputs)
        }
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        def summary = store.texts("""
                select value.production_value_id::text from production_values value
                  join productions made on made.production_id = value.production_id
                  join run_steps step on step.run_step_id = made.run_step_id
                 where step.workflow_step_id = ?::uuid
                """, SUMMARISE)[0]
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        def approved = store.texts("""
                select value.production_value_id::text from production_values value
                  join productions made on made.production_id = value.production_id
                  join run_steps step on step.run_step_id = made.run_step_id
                 where step.workflow_step_id = ?::uuid
                """, CONFIRM)[0]
        [child: child.value().toString(), summary: summary, approved: approved]
    }

    def "a child try persists two maker paths and reconstructs its old composite value after one maker is replaced"() {
        given:
        def composite = compositeChild()
        def at = snapshots.inTreeAsRead(groupId(GROUP), runId(composite.child))
        def original = at.run().steps()[0].tries()[0]

        expect:
        original.inputs()[0].parts().collect { it.targetPath()*.value() } == [["approved"], ["summary"]]
        original.inputs()[0].parts()*.source()*.value()*.toString() == [composite.approved, composite.summary]
        StepInputs.took(at, at.run().steps()[0], original)[0].value() ==
                new JsonValue.JsonObject([new JsonValue.JsonMember("summary", new JsonValue.JsonString("Earlier")),
                                          new JsonValue.JsonMember("approved", new JsonValue.JsonString("true"))])

        when:
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now(), RETURNING.
        def sourceTry = store.session.sql("""
                insert into productions (run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                                         reviewed_by_model, tries, pinned_version_id, try_number, producer,
                                         created_by, created_by_kind, ended_at, ended_by, ended_by_kind, explanation)
                select step.run_step_id, ?::uuid, ?::uuid, 'question', 'person', false, 2, ?::uuid, 2,
                       'person', ?::uuid, 'person', now(), ?::uuid, 'person', 'Replaced.'
                  from run_steps step where step.workflow_step_id = ?::uuid
                returning cast(production_id as text)
                """).params(RUN, RUN, SUMMARISE_VERSION, CAT, CAT, SUMMARISE).query(String).single()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into production_inputs (production_id, run_id, root_run_id, run_step_id, run_step_kind,
                                               workflow_step_id, binding_id, binding_reads_a_constant,
                                               source_workflow_step_id, source_run_step_id, source_run_step_kind,
                                               source_production_id, source_production_value_id)
                select ?::uuid, input.run_id, input.root_run_id, input.run_step_id, input.run_step_kind,
                       input.workflow_step_id, input.binding_id, input.binding_reads_a_constant,
                       input.source_workflow_step_id, input.source_run_step_id, input.source_run_step_kind,
                       input.source_production_id, input.source_production_value_id
                  from production_inputs input join production_values value
                    on value.production_id = input.production_id
                 where value.production_value_id = ?::uuid
                """).params(sourceTry, composite.summary).update()
        // DB-SPECIFIC: PostgreSQL ::jsonb/::uuid casts, RETURNING.
        def laterValue = store.session.sql("""
                insert into production_values (production_id, run_step_kind, producer, pinned_version_id,
                                               declaration_field_id, field_standing, value)
                values (?::uuid, 'question', 'person', ?::uuid, ?::uuid, 'always', '"Later"'::jsonb)
                returning cast(production_value_id as text)
                """).params(sourceTry, SUMMARISE_VERSION, SUMMARY).query(String).single()
        def after = snapshots.inTreeAsRead(groupId(GROUP), runId(composite.child))

        then:
        StepInputs.bound(after, after.run().steps()[0]).get()[0].value() ==
                new JsonValue.JsonObject([new JsonValue.JsonMember("summary", new JsonValue.JsonString("Later")),
                                          new JsonValue.JsonMember("approved", new JsonValue.JsonString("true"))])
        StepInputs.took(after, after.run().steps()[0], after.run().steps()[0].tries()[0])[0].value() ==
                new JsonValue.JsonObject([new JsonValue.JsonMember("summary", new JsonValue.JsonString("Earlier")),
                                          new JsonValue.JsonMember("approved", new JsonValue.JsonString("true"))])

        when:
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
        store.session.sql("""
                update productions set ended_at = now(), ended_by = ?::uuid, ended_by_kind = 'person',
                                       explanation = 'Answered.'
                 where run_id = ?::uuid
                """).params(CAT, composite.child).update()
        def oldUsedBefore = recordedMaker(RUN, new ProductionValueId(UUID.fromString(composite.summary)))
        def laterUsedBefore = recordedMaker(RUN, new ProductionValueId(UUID.fromString(laterValue)))

        then:
        oldUsedBefore
        !laterUsedBefore

        when:
        // DB-SPECIFIC: PostgreSQL ::uuid casts and UPDATE FROM.
        store.session.sql("""
                update production_input_parts part
                   set source_production_id = value.production_id,
                       source_production_value_id = value.production_value_id
                  from production_values value
                 where part.target_path = 'summary' and value.production_value_id = ?::uuid
                """).params(laterValue).update()

        then:
        thrown(DataIntegrityViolationException)

        when:
        def unchanged = snapshots.inTreeAsRead(groupId(GROUP), runId(composite.child))
        def oldUsedAfter = recordedMaker(RUN, new ProductionValueId(UUID.fromString(composite.summary)))
        def laterUsedAfter = recordedMaker(RUN, new ProductionValueId(UUID.fromString(laterValue)))

        then:
        oldUsedAfter
        !laterUsedAfter
        StepInputs.took(unchanged, unchanged.run().steps()[0], unchanged.run().steps()[0].tries()[0])[0].value() ==
                new JsonValue.JsonObject([new JsonValue.JsonMember("summary", new JsonValue.JsonString("Earlier")),
                                          new JsonValue.JsonMember("approved", new JsonValue.JsonString("true"))])
    }

    def "a recorded component cannot be changed to another maker or target path"() {
        given:
        def composite = compositeChild()
        def before = snapshots.inTreeAsRead(groupId(GROUP), runId(composite.child))
        assert StepInputs.took(before, before.run().steps()[0], before.run().steps()[0].tries()[0]).size() == 1

        when:
        if (attack == "maker") {
            // DB-SPECIFIC: PostgreSQL ::uuid casts and UPDATE FROM.
            store.session.sql("""
                    update production_input_parts part
                       set source_production_id = value.production_id,
                           source_production_value_id = value.production_value_id
                      from production_values value
                     where part.target_path = 'summary' and value.production_value_id = ?::uuid
                    """).params(composite.approved).update()
        } else {
            store.session.sql("update production_input_parts set target_path = 'ghost' where target_path = 'summary'")
                    .update()
        }

        then:
        thrown(DataIntegrityViolationException)

        when:
        def after = snapshots.inTreeAsRead(groupId(GROUP), runId(composite.child))

        then:
        StepInputs.took(after, after.run().steps()[0], after.run().steps()[0].tries()[0])[0].value() ==
                new JsonValue.JsonObject([new JsonValue.JsonMember("summary", new JsonValue.JsonString("Earlier")),
                                          new JsonValue.JsonMember("approved", new JsonValue.JsonString("true"))])

        where:
        attack << ["maker", "path"]
    }

    def "a used component remains traced to its original maker after a rejected rewrite"() {
        given:
        def composite = compositeChild()
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
        store.session.sql("""
                update productions set ended_at = now(), ended_by = ?::uuid, ended_by_kind = 'person',
                                       explanation = 'Answered.'
                 where run_id = ?::uuid
                """).params(CAT, composite.child).update()
        def used = { String maker ->
            recordedMaker(RUN, new ProductionValueId(UUID.fromString(maker)))
        }
        assert used(composite.summary)
        assert used(composite.approved)

        when:
        if (attack == "other maker") {
            // DB-SPECIFIC: PostgreSQL ::uuid casts and UPDATE FROM.
            store.session.sql("""
                    update production_input_parts part
                       set source_production_id = value.production_id,
                           source_production_value_id = value.production_value_id
                      from production_values value
                     where part.target_path = 'summary' and value.production_value_id = ?::uuid
                    """).params(composite.approved).update()
        } else if (attack == "no maker") {
            store.session.sql("""
                    update production_input_parts set source_production_id = null,
                                                      source_production_value_id = null
                     where target_path = 'summary'
                    """).update()
        } else if (attack == "missing part") {
            store.session.sql("delete from production_input_parts where target_path = 'summary'").update()
        } else {
            store.session.sql("update production_input_parts set target_path = 'ghost' where target_path = 'summary'")
                    .update()
        }

        then:
        thrown(DataIntegrityViolationException)

        when:
        def oldStillUsed = used(composite.summary)
        def otherStillUsed = used(composite.approved)

        then:
        oldStillUsed
        otherStillUsed
        store.count("select count(*) from production_input_parts where target_path = 'summary'") == 1

        where:
        attack << ["other maker", "no maker", "missing part", "wrong path"]
    }

    private Map<String, String> routeTree(boolean startChild = true, boolean fromProduction = false) {
        def listEntry = UUID.randomUUID().toString()
        def listVersion = UUID.randomUUID().toString()
        def rootEntry = UUID.randomUUID().toString()
        def rootVersion = UUID.randomUUID().toString()
        def fallbackEntry = UUID.randomUUID().toString()
        def fallbackVersion = UUID.randomUUID().toString()
        def chosenEntry = UUID.randomUUID().toString()
        def chosenVersion = UUID.randomUUID().toString()
        def idleEntry = UUID.randomUUID().toString()
        def idleVersion = UUID.randomUUID().toString()
        def route = UUID.randomUUID().toString()
        def producerEntry = UUID.randomUUID().toString()
        def producerVersion = UUID.randomUUID().toString()
        def producerStep = UUID.randomUUID().toString()
        def producerField = UUID.randomUUID().toString()
        def fallbackCase = UUID.randomUUID().toString()
        def chosenCase = UUID.randomUUID().toString()
        def idleCase = UUID.randomUUID().toString()
        def root = UUID.randomUUID().toString()
        def child = UUID.randomUUID().toString()
        store.entry(listEntry, GROUP, "reference_list", "Demo Sample terms")
        store.seeded(listVersion, listEntry, 1)
        store.content(listVersion, "reference_list")
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into reference_list_terms (entry_version_id, position, term, meaning, created_by)
                values (?::uuid, 1, 'go', 'Demo Sample.', ?::uuid)
                """).params(listVersion, SEEDER).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into reference_list_terms (entry_version_id, position, term, meaning, created_by)
                values (?::uuid, 2, 'wait', 'Demo Sample.', ?::uuid)
                """).params(listVersion, SEEDER).update()
        StepRows.workflow(store, rootEntry, rootVersion, GROUP, "A routed root")
        store.termField(UUID.randomUUID().toString(), rootVersion, "workflow", listVersion, "takes")
        if (fromProduction) {
            StepRows.question(store, producerEntry, producerVersion, GROUP, "A category maker")
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("""
                    insert into declaration_fields (declaration_field_id, entry_version_id, entry_kind, side,
                                                    position, name, kind, term_list_version_id, must_be_given,
                                                    standing, created_by)
                    values (?::uuid, ?::uuid, 'question', 'gives', 1, 'category', 'term', ?::uuid, true,
                            'always', ?::uuid)
                    """).params(producerField, producerVersion, listVersion, SEEDER).update()
            StepRows.step(store, producerStep, rootVersion, 1, "make_category", producerVersion, "person", 2)
        }
        StepRows.workflow(store, fallbackEntry, fallbackVersion, GROUP, "A fallback")
        StepRows.workflow(store, chosenEntry, chosenVersion, GROUP, "A chosen target")
        StepRows.workflow(store, idleEntry, idleVersion, GROUP, "An idle target")
        store.routed(route, fallbackCase, rootVersion, fromProduction ? 2 : 1, fallbackVersion)
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into route_cases (route_case_id, entry_version_id, workflow_step_id, term,
                                         target_version_id, created_by)
                values (?::uuid, ?::uuid, ?::uuid, 'go', ?::uuid, ?::uuid)
                """).params(chosenCase, rootVersion, route, chosenVersion, SEEDER).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into route_cases (route_case_id, entry_version_id, workflow_step_id, term,
                                         target_version_id, created_by)
                values (?::uuid, ?::uuid, ?::uuid, 'wait', ?::uuid, ?::uuid)
                """).params(idleCase, rootVersion, route, idleVersion, SEEDER).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into bindings (binding_id, entry_version_id, workflow_step_id, step_kind,
                                      source_step_id, source_path, created_by)
                values (?::uuid, ?::uuid, ?::uuid, 'route', ?::uuid, 'category', ?::uuid)
                """).params(UUID.randomUUID().toString(), rootVersion, route,
                fromProduction ? producerStep : null, SEEDER).update()
        StepRows.run(store, root, GROUP, 41, rootEntry, rootVersion, CAT, '{"category":"go"}')
        def sourceValue = null
        if (fromProduction) {
            // DB-SPECIFIC: PostgreSQL ::uuid casts, RETURNING.
            def sourceStep = store.session.sql("""
                    insert into run_steps (run_id, entry_version_id, workflow_step_id, step_kind,
                                           pinned_version_id, pinned_kind, producer, reviewed_by_model, tries,
                                           created_by, created_by_kind)
                    select ?::uuid, step.entry_version_id, step.workflow_step_id, step.kind,
                           step.pinned_version_id, step.pinned_kind, step.producer, step.reviewed_by_model,
                           step.tries, ?::uuid, 'system'
                      from workflow_steps step where step.workflow_step_id = ?::uuid
                    returning cast(run_step_id as text)
                    """).params(root, SYSTEM, producerStep).query(String).single()
            // DB-SPECIFIC: PostgreSQL ::uuid casts, now(), RETURNING.
            def made = store.session.sql("""
                    insert into productions (run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                                             reviewed_by_model, tries, pinned_version_id, try_number, producer,
                                             created_by, ended_at, ended_by, ended_by_kind, explanation)
                    values (?::uuid, ?::uuid, ?::uuid, 'question', 'person', false, 2, ?::uuid, 1,
                            'person', ?::uuid, now(), ?::uuid, 'person', 'Chosen.')
                    returning cast(production_id as text)
                    """).params(sourceStep, root, root, producerVersion, SYSTEM, CAT).query(String).single()
            // DB-SPECIFIC: PostgreSQL ::jsonb/::uuid casts, RETURNING.
            sourceValue = store.session.sql("""
                    insert into production_values (production_id, run_step_kind, producer, pinned_version_id,
                                                   declaration_field_id, field_standing, value)
                    values (?::uuid, 'question', 'person', ?::uuid, ?::uuid, 'always', '"go"'::jsonb)
                    returning cast(production_value_id as text)
                    """).params(made, producerVersion, producerField).query(String).single()
        }
        // DB-SPECIFIC: PostgreSQL ::uuid casts, RETURNING.
        def runStep = store.session.sql("""
                insert into run_steps (run_id, entry_version_id, workflow_step_id, step_kind,
                                       reviewed_by_model, created_by)
                values (?::uuid, ?::uuid, ?::uuid, 'route', false, ?::uuid)
                returning cast(run_step_id as text)
                """).params(root, rootVersion, route, SYSTEM).query(String).single()
        if (startChild) {
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("""
                    insert into runs (run_id, group_id, number, entry_id, entry_version_id, parent_run_id,
                                      parent_run_step_id, parent_run_step_kind, parent_workflow_step_id,
                                      parent_route_case_id, root_run_id, depth, parent_depth, created_by, created_by_kind)
                    values (?::uuid, ?::uuid, 42, ?::uuid, ?::uuid, ?::uuid, ?::uuid, 'route', ?::uuid,
                            ?::uuid, ?::uuid, 1, 0, ?::uuid, 'system')
                    """).params(child, GROUP, chosenEntry, chosenVersion, root, runStep, route,
                    chosenCase, root, SYSTEM).update()
        }
        store.stopped(fallbackEntry, CAT)
        [root: root, child: child, route: route, chosenCase: chosenCase, fallbackCase: fallbackCase,
         idleCase: idleCase, idleEntry: idleEntry, chosenVersion: chosenVersion, sourceValue: sourceValue,
         producerStep: producerStep, producerVersion: producerVersion, producerField: producerField]
    }

    def "a route writer records its chosen case and binding while an unclaimed route records no child"() {
        given:
        def routed = routeTree(false)
        def writes = new EngineWrites(store.session)

        when:
        def child = store.transactions().execute {
            def locked = tree.lock(groupId(GROUP), runId(routed.root)).orElseThrow()
            def root = snapshots.locked(locked, runId(routed.root))
            def route = root.steps()[0]
            def discriminator = StepInputs.bound(RunInTree.root(root),
                    ((StepRuns.Route) route.planned().runs()).discriminator()).orElseThrow()
            writes.beneath(locked, root, route,
                    new EntryVersionId(UUID.fromString(routed.chosenVersion)),
                    UUID.fromString(routed.chosenCase), discriminator)
        }

        then:
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts; text || non-string renders the subject_kind enum.
        store.texts("""
                select parent_route_case_id::text || ' ' || created_by_kind from runs
                 where run_id = ?::uuid
                """, child.value().toString()) == [routed.chosenCase + " system"]
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        store.texts("""
                select binding_id::text || ' ' || branch_run_id::text from production_inputs
                 where branch_run_id = ?::uuid
                """, child.value().toString()) ==
                // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
                [store.texts("select binding_id::text from bindings where workflow_step_id = ?::uuid", routed.route)[0] +
                        " " + child.value()]
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count("select count(*) from productions where run_id = ?::uuid", child.value().toString()) == 0
    }

    def "a branch uses its maker's value when selected, before the child starts any step"() {
        given:
        def routed = routeTree(false, true)
        def writes = new EngineWrites(store.session)
        def value = new ProductionValueId(UUID.fromString(routed.sourceValue))

        when:
        def before = recordedMaker(routed.root, value)
        def child = store.transactions().execute {
            def locked = tree.lock(groupId(GROUP), runId(routed.root)).orElseThrow()
            def root = snapshots.locked(locked, runId(routed.root))
            def route = root.steps().find { it.planned().id().value().toString() == routed.route }
            def discriminator = StepInputs.bound(RunInTree.root(root),
                    ((StepRuns.Route) route.planned().runs()).discriminator()).orElseThrow()
            writes.beneath(locked, root, route,
                    new EntryVersionId(UUID.fromString(routed.chosenVersion)),
                    UUID.fromString(routed.chosenCase), discriminator)
        }

        then:
        !before
        recordedMaker(routed.root, value)
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        store.texts("""
                select source_production_value_id::text from production_inputs where branch_run_id = ?::uuid
                """, child.value().toString()) == [routed.sourceValue]
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count("select count(*) from run_steps where run_id = ?::uuid", child.value().toString()) == 0
    }

    def "a chosen branch retains its saved discriminator when deletion is rejected"() {
        given:
        def routed = routeTree(false, true)
        def writes = new EngineWrites(store.session)
        def child = store.transactions().execute {
            def locked = tree.lock(groupId(GROUP), runId(routed.root)).orElseThrow()
            def root = snapshots.locked(locked, runId(routed.root))
            def route = root.steps().find { it.planned().id().value().toString() == routed.route }
            def discriminator = StepInputs.bound(RunInTree.root(root),
                    ((StepRuns.Route) route.planned().runs()).discriminator()).orElseThrow()
            writes.beneath(locked, root, route,
                    new EntryVersionId(UUID.fromString(routed.chosenVersion)),
                    UUID.fromString(routed.chosenCase), discriminator)
        }
        assert recordedMaker(routed.root, new ProductionValueId(UUID.fromString(routed.sourceValue)))

        when:
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("delete from production_inputs where branch_run_id = ?::uuid")
                .params(child.value().toString()).update()
        then:
        thrown(DataIntegrityViolationException)

        when:
        def stillUsed = recordedMaker(routed.root, new ProductionValueId(UUID.fromString(routed.sourceValue)))
        def unrelated = recordedMaker(routed.root, new ProductionValueId(UUID.randomUUID()))

        then:
        stillUsed
        !unrelated
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count("select count(*) from runs where run_id = ?::uuid", child.value().toString()) == 1
    }

    def "a chosen branch keeps the earlier maker when the same source step later yields another value"() {
        given:
        def routed = routeTree(false, true)
        def writes = new EngineWrites(store.session)
        def child = store.transactions().execute {
            def locked = tree.lock(groupId(GROUP), runId(routed.root)).orElseThrow()
            def root = snapshots.locked(locked, runId(routed.root))
            def route = root.steps().find { it.planned().id().value().toString() == routed.route }
            def discriminator = StepInputs.bound(RunInTree.root(root),
                    ((StepRuns.Route) route.planned().runs()).discriminator()).orElseThrow()
            writes.beneath(locked, root, route,
                    new EntryVersionId(UUID.fromString(routed.chosenVersion)),
                    UUID.fromString(routed.chosenCase), discriminator)
        }
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        def sourceStep = store.texts("""
                select run_step_id::text from run_steps where workflow_step_id = ?::uuid
                """, routed.producerStep)[0]

        when:
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now(), RETURNING.
        def laterTry = store.session.sql("""
                insert into productions (run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                                         reviewed_by_model, tries, pinned_version_id, try_number, producer,
                                         created_by, ended_at, ended_by, ended_by_kind, explanation)
                values (?::uuid, ?::uuid, ?::uuid, 'question', 'person', false, 2, ?::uuid, 2,
                        'person', ?::uuid, now(), ?::uuid, 'person', 'Later category.')
                returning cast(production_id as text)
                """).params(sourceStep, routed.root, routed.root, routed.producerVersion, SYSTEM, CAT)
                .query(String).single()
        // DB-SPECIFIC: PostgreSQL ::jsonb/::uuid casts, RETURNING.
        def laterValue = store.session.sql("""
                insert into production_values (production_id, run_step_kind, producer, pinned_version_id,
                                               declaration_field_id, field_standing, value)
                values (?::uuid, 'question', 'person', ?::uuid, ?::uuid, 'always', '"wait"'::jsonb)
                returning cast(production_value_id as text)
                """).params(laterTry, routed.producerVersion, routed.producerField).query(String).single()
        def oldUsed = recordedMaker(routed.root, new ProductionValueId(UUID.fromString(routed.sourceValue)))
        def laterUsed = recordedMaker(routed.root, new ProductionValueId(UUID.fromString(laterValue)))

        then:
        oldUsed
        !laterUsed
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        store.texts("select parent_route_case_id::text from runs where run_id = ?::uuid",
                child.value().toString()) == [routed.chosenCase]

        when:
        // DB-SPECIFIC: PostgreSQL ::uuid casts and UPDATE FROM.
        store.session.sql("""
                update production_inputs input
                   set source_production_id = value.production_id,
                       source_production_value_id = value.production_value_id
                  from production_values value
                 where input.branch_run_id = ?::uuid and value.production_value_id = ?::uuid
                """).params(child.value().toString(), laterValue).update()

        then:
        thrown(DataIntegrityViolationException)

        when:
        def oldStillUsed = recordedMaker(routed.root, new ProductionValueId(UUID.fromString(routed.sourceValue)))
        def laterStillUnused = recordedMaker(routed.root, new ProductionValueId(UUID.fromString(laterValue)))

        then:
        oldStillUsed
        !laterStillUnused
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        store.texts("select source_production_value_id::text from production_inputs where branch_run_id = ?::uuid",
                child.value().toString()) == [routed.sourceValue]
    }

    def "an unclaimed route fails its step on the term as it was said, or on nothing, without starting a run or making a try"() {
        given:
        def routed = routeTree(false)
        def writes = new EngineWrites(store.session)

        when:
        store.transactions().executeWithoutResult {
            def locked = tree.lock(groupId(GROUP), runId(routed.root)).orElseThrow()
            def root = snapshots.locked(locked, runId(routed.root))
            writes.unclaimed(locked, root, root.steps()[0], said)
        }
        def read = store.transactions().execute { snapshots.asRead(groupId(GROUP), runId(routed.root)) }

        then:
        // DB-SPECIFIC: PostgreSQL ::uuid casts; text || non-string renders enum and boolean operands.
        store.texts("""
                select reason || ' ' || coalesce(detail, '-') || ' ' || (production_id is null)
                  from run_step_failures where run_id = ?::uuid
                """, routed.root) == [written]
        read.steps()[0].failures()*.term() == [said]
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count("select count(*) from runs where parent_run_id = ?::uuid", routed.root) == 0
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count("select count(*) from productions where run_id = ?::uuid", routed.root) == 0

        where:
        said           || written
        "wait"         || "unclaimed_value wait true"
        null           || "unclaimed_value - true"
    }

    def "a term at its longest, of characters outside one byte and outside the first plane, is written and read back whole"() {
        given:
        def routed = routeTree(false)
        def writes = new EngineWrites(store.session)
        def longest = new Term(LONGEST_TERM).value()
        assert longest.codePointCount(0, longest.length()) == Term.MAXIMUM_LENGTH && longest.length() > Term.MAXIMUM_LENGTH

        when:
        store.transactions().executeWithoutResult {
            def locked = tree.lock(groupId(GROUP), runId(routed.root)).orElseThrow()
            def root = snapshots.locked(locked, runId(routed.root))
            writes.unclaimed(locked, root, root.steps()[0], longest)
        }
        def read = store.transactions().execute { snapshots.asRead(groupId(GROUP), runId(routed.root)) }

        then:
        read.steps()[0].failures()*.term() == [longest]
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        store.texts("select length(detail)::text from run_step_failures where run_id = ?::uuid", routed.root) == ["128"]
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count("select count(*) from runs where parent_run_id = ?::uuid", routed.root) == 0
    }

    def "a step too deep fails saying nothing more, without starting a run or making a try"() {
        given:
        def nested = nestedTree(false)
        def writes = new EngineWrites(store.session)

        when:
        store.transactions().executeWithoutResult {
            def locked = tree.lock(groupId(GROUP), runId(nested.root)).orElseThrow()
            def root = snapshots.locked(locked, runId(nested.root))
            writes.tooDeep(locked, root, root.steps()[0])
        }

        then:
        // DB-SPECIFIC: PostgreSQL ::uuid casts; text || non-string renders enum and boolean operands.
        store.texts("""
                select reason || ' ' || coalesce(detail, '-') || ' ' || (production_id is null) || ' ' || created_by_kind
                  from run_step_failures where run_id = ?::uuid
                """, nested.root) == ["too_deep - true system"]
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count("select count(*) from runs where parent_run_id = ?::uuid", nested.root) == 0
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count("select count(*) from productions where run_id = ?::uuid", nested.root) == 0
    }

    // Keep both failures open so a second write can expose a missing tree lock.
    def "a step already failed on no try is never failed so again, and the write that tried fails"() {
        given:
        def nested = nestedTree(false)
        def writes = new EngineWrites(store.session)
        store.transactions().executeWithoutResult {
            def locked = tree.lock(groupId(GROUP), runId(nested.root)).orElseThrow()
            def root = snapshots.locked(locked, runId(nested.root))
            writes.tooDeep(locked, root, root.steps()[0])
        }

        when:
        store.transactions().executeWithoutResult {
            def locked = tree.lock(groupId(GROUP), runId(nested.root)).orElseThrow()
            def root = snapshots.locked(locked, runId(nested.root))
            writes.tooDeep(locked, root, root.steps()[0])
        }

        then:
        thrown(IllegalStateException)
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        store.texts("select reason::text from run_step_failures where run_id = ?::uuid", nested.root) == ["too_deep"]
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count("select count(*) from runs where parent_run_id = ?::uuid", nested.root) == 0
    }

    def "a route already failed on a value no case claimed is never failed as too deep besides"() {
        given:
        def routed = routeTree(false)
        def writes = new EngineWrites(store.session)
        store.transactions().executeWithoutResult {
            def locked = tree.lock(groupId(GROUP), runId(routed.root)).orElseThrow()
            def root = snapshots.locked(locked, runId(routed.root))
            writes.unclaimed(locked, root, root.steps()[0], "wait")
        }

        when:
        store.transactions().executeWithoutResult {
            def locked = tree.lock(groupId(GROUP), runId(routed.root)).orElseThrow()
            def root = snapshots.locked(locked, runId(routed.root))
            writes.tooDeep(locked, root, root.steps()[0])
        }

        then:
        thrown(IllegalStateException)
        // DB-SPECIFIC: PostgreSQL ::uuid casts; text || non-string renders the failure-reason enum.
        store.texts("select reason || ' ' || detail from run_step_failures where run_id = ?::uuid", routed.root) ==
                ["unclaimed_value wait"]
    }

    def "reads a route child by its chosen case and keeps an unchosen target stop on that case"() {
        given:
        def routed = routeTree()

        when:
        def root = snapshots.asRead(groupId(GROUP), runId(routed.root))
        def route = root.steps()[0]

        then:
        route.planned().runs() instanceof StepRuns.Route
        route.beneath().run().run() == runId(routed.child)
        route.beneath().routeCase() == UUID.fromString(routed.chosenCase)
        route.casesStopped().keySet() == [UUID.fromString(routed.fallbackCase)] as Set
        route.casesStopped()[UUID.fromString(routed.fallbackCase)].by() == new SubjectId(UUID.fromString(CAT))
        !route.casesStopped().containsKey(UUID.fromString(routed.idleCase))
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count("select count(*) from runs where entry_id = ?::uuid", routed.idleEntry) == 0
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count("""
                select count(*) from workflow_steps step
                  join entry_versions pinned on pinned.entry_version_id = step.pinned_version_id
                 where pinned.entry_id = ?::uuid
                """, routed.idleEntry) == 0
        route.pinStopped() == null
        route.hold() == null
    }

    def "a locked route snapshot holds every target entry while it reads the case stops"() {
        given:
        def routed = routeTree()
        def read = new CountDownLatch(1)
        def release = new CountDownLatch(1)
        def holding = attempting(racing) {
            store.transactions().executeWithoutResult {
                snapshots.locked(tree.lock(groupId(GROUP), runId(routed.root)).orElseThrow(), runId(routed.root))
                read.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        read.await(10, TimeUnit.SECONDS)
        def stopping = store.database.connection
        stopping.autoCommit = false

        when:
        def stopped = attempting(racing) {
            stopping.createStatement().withCloseable {
                // DB-SPECIFIC: PostgreSQL FOR NO KEY UPDATE.
                it.execute("select 1 from entries where entry_id = '${routed.idleEntry}' for no key update")
            }
        }
        store.untilWaiting(1)

        then:
        !stopped.done

        when:
        release.countDown()

        then:
        holding.get(10, TimeUnit.SECONDS) == null
        stopped.get(10, TimeUnit.SECONDS) == true

        cleanup:
        stopping?.rollback()
        stopping?.close()
    }

    // The release declares code outputs; a pinned list absent from the group must not fail the read.
    def "reads a code step as the running release declares it, beside each list it pins that the group holds, and none where it holds no such step"() {
        given:
        CodeWorkflow.seed(store)
        CodeWorkflow.run(store)
        def ours = offering("00000007-0000-4000-8000-000000000e33", "Hardware", CodeWorkflow.GROUP)
        def theirs = offering("00000007-0000-4000-8000-000000000e34", "Printer")
        def declared = new CodeStepDeclaration(
                new Declaration(DeclarationSide.TAKES, Demands.ofQuestion(DeclarationSide.TAKES), [CodeWorkflow.REPLY]),
                new Declaration(DeclarationSide.GIVES, Demands.ofQuestion(DeclarationSide.GIVES), [
                        CodeWorkflow.RECEIPT,
                        SpecCodeStep.standing("category", new FieldShape.Term(versionId(ours))),
                        SpecCodeStep.standing("grade", new FieldShape.Term(versionId(theirs)))]),
                true)
        def code = new SpecCodeStep(CodeWorkflow.CODE_STEP, declared.takes().fields(), declared.gives().fields(), true)
        def reading = new RunSnapshots(store.session, held ? CodeStepsHeld.of(code) : CodeStepsHeld.NONE, new SearchWays([]))

        when:
        def read = reading.asRead(groupId(CodeWorkflow.GROUP), runId(CodeWorkflow.RUN))

        then:
        read.steps()[0].planned().runs() == new StepRuns.Code(CodeWorkflow.CODE_STEP,
                held ? new ReleasedCodeStep(declared, [(versionId(ours)): offered("Hardware")]) : null)

        where:
        held << [true, false]
    }

    def "reading no run asks nothing of the store"() {
        given:
        def statements = []
        def counted = new RunSnapshots(JdbcClient.create(new CountingDataSource(store.database, statements)),
                CodeStepsHeld.NONE, new SearchWays([]))

        expect:
        counted.asRead(groupId(GROUP), []) == []
        statements == []
    }

    def "a run among those read that the group does not hold fails the whole read"() {
        when:
        snapshots.asRead(groupId(GROUP), [runId(), runId("00000008-0000-4000-8000-000000000e99")])

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "Group ${GROUP} holds no run 00000008-0000-4000-8000-000000000e99 to read" as String
    }

    // Lock the entry so a stop cannot land between reading it and acting on it.
    def "under the tree's lock each entry the run runs is held, so a stop waits until the transaction ends"() {
        given:
        def read = new CountDownLatch(1)
        def release = new CountDownLatch(1)
        def holding = attempting(racing) {
            store.transactions().executeWithoutResult {
                snapshots.locked(tree.lock(groupId(GROUP), runId()).orElseThrow(), runId())
                read.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        read.await(10, TimeUnit.SECONDS)
        def stopping = store.database.connection
        stopping.autoCommit = false

        when:
        def stopped = attempting(racing) {
            stopping.createStatement().withCloseable {
                // DB-SPECIFIC: PostgreSQL FOR NO KEY UPDATE.
                it.execute("select 1 from entries where entry_id = '${CONFIRM_QUESTION}' for no key update")
            }
        }
        store.untilWaiting(1)

        then: "the stop waits on the read"
        !stopped.done

        when:
        release.countDown()

        then:
        holding.get(10, TimeUnit.SECONDS) == null
        stopped.get(10, TimeUnit.SECONDS) == true

        cleanup:
        stopping?.rollback()
        stopping?.close()
    }

    def "a run is read under a tree's lock only as a run of that tree"() {
        given:
        StepRows.run(store, "00000008-0000-4000-8000-000000000e03", GROUP, 3, TicketWorkflow.WORKFLOW, VERSION, CAT,
                TicketWorkflow.STARTED_WITH)
        def statements = []
        def counted = new RunSnapshots(JdbcClient.create(new CountingDataSource(store.database, statements)),
                CodeStepsHeld.NONE, new SearchWays([]))

        when:
        store.transactions().executeWithoutResult {
            counted.locked(tree.lock(groupId(GROUP), runId()).orElseThrow(),
                    runId("00000008-0000-4000-8000-000000000e03"))
        }

        then:
        thrown(IllegalStateException)
        statements.size() == 1
        statements[0].contains("run.root_run_id")
    }
    // DB-SPECIFIC: SQL in this feature uses PostgreSQL :: casts.
    def "a following transaction reloads two distinct ordinary bindings to the same stored input"() {
        given:
        def questionEntry = UUID.randomUUID().toString()
        def questionVersion = UUID.randomUUID().toString()
        def searchBinding = UUID.randomUUID().toString()
        def searching = UUID.randomUUID().toString()
        def runStep = UUID.randomUUID().toString()
        def production = UUID.randomUUID().toString()
        store.transactions().executeWithoutResult {
            StepRows.question(store, questionEntry, questionVersion, GROUP, "Fictional stored-ticket evidence",
                    "Find evidence about the fictional ticket.")
            StepRows.field(store, [id: UUID.randomUUID().toString(), owner: questionVersion, ownerKind: "question",
                                  side: "takes", position: 0, name: "text", limit: 4000, mustBe: true])
            ['title': 200, 'body': 4000, 'references': 2000, 'other': 1000].eachWithIndex { name, limit, position ->
                StepRows.field(store, [id: UUID.randomUUID().toString(), owner: questionVersion, ownerKind: "question",
                                      side: "gives", position: position, name: name, limit: limit,
                                      mustBe: position < 2, standing: "never"])
            }
            store.session.sql("update question_versions set search_way = 'web' where entry_version_id = ?::uuid")
                    .param(questionVersion).update()
            // DB-SPECIFIC: PostgreSQL ::jsonb/::uuid casts.
            store.session.sql("update runs set started_with = '{\"ticket\":\"Demo Sample stored input\"}'::jsonb where run_id = ?::uuid")
                    .param(RUN).update()
            def boundStep = byModel()
            def boundTry = modelTry(boundStep, null)
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("""
                    insert into production_inputs (production_id, run_id, root_run_id, run_step_id,
                        run_step_kind, workflow_step_id, binding_id, binding_reads_a_constant)
                    values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'question', ?::uuid, ?::uuid, false)
                    """).params(boundTry, RUN, RUN, boundStep, SUMMARISE, TICKET_IN).update()
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("""
                    insert into workflow_steps (workflow_step_id, entry_version_id, position, name, kind,
                        search_way, pinned_version_id, pinned_kind, producer, producer_model, producer_mode, tries, search_title_standing,
                        search_body_standing, search_references_standing, search_other_standing, created_by)
                    values (?::uuid, ?::uuid, 10, 'look_up', 'search', 'web', ?::uuid, 'question', 'model', 'general', 'ordinary', 1,
                        'always', 'always', 'never', 'never', ?::uuid)
                    """).params(searching, VERSION, questionVersion, SEEDER).update()
            StepRows.binding(store, searchBinding, VERSION, searching, "text", null, "ticket")
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("""
                    insert into run_steps (run_step_id, run_id, entry_version_id, workflow_step_id, step_kind,
                        pinned_version_id, pinned_kind, producer, reviewed_by_model, tries, created_by)
                    values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'search', ?::uuid, 'question', 'model', false, 1, ?::uuid)
                    """).params(runStep, RUN, VERSION, searching, questionVersion, SYSTEM).update()
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("""
                    insert into productions (production_id, run_step_id, run_id, root_run_id, run_step_kind,
                        step_producer, reviewed_by_model, tries, pinned_version_id, producer, created_by)
                    values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'search', 'model', false, 1, ?::uuid, 'model', ?::uuid)
                    """).params(production, runStep, RUN, RUN, questionVersion, SYSTEM).update()
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("""
                    insert into production_inputs (production_id, run_id, root_run_id, run_step_id,
                        run_step_kind, workflow_step_id, binding_id, binding_reads_a_constant)
                    values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'search', ?::uuid, ?::uuid, false)
                    """).params(production, RUN, RUN, runStep, searching, searchBinding).update()
        }

        when:
        def context = store.transactions().execute { status ->
            new RunSnapshots(store.session, CodeStepsHeld.NONE, new SearchWays([DeployedModels.WEB]))
                    .inTreeAsRead(groupId(GROUP), runId()) }
        def searchStep = context.run().steps().find { it.planned().id().value().toString() == searching }
        def searchTry = searchStep.tries().first()
        def boundStep = context.run().steps().find { it.planned().id().value().toString() == SUMMARISE }

        then:
        searchTry.inputs().size() == 1
        searchTry.inputs().first().binding() == UUID.fromString(searchBinding)
        searchTry.inputs().first().binding() != UUID.fromString(TICKET_IN)
        searchTry.inputs().first().source() == null
        searchTry.inputs().first().parts().isEmpty()
        StepInputs.took(context, searchStep, searchTry).first().value() == new JsonValue.JsonString('Demo Sample stored input')
        boundStep.tries().first().inputs().first().binding() == UUID.fromString(TICKET_IN)
        boundStep.tries().first().inputs().first().source() == null
        boundStep.tries().first().inputs().first().parts().isEmpty()
        StepInputs.took(context, boundStep, boundStep.tries().first()).first().value() == new JsonValue.JsonString('Demo Sample stored input')
    }


    def "an unstarted child carries the pinned workflow's real output demands in locked and read-only snapshots"() {
        given:
        def entry = UUID.randomUUID().toString()
        def version = UUID.randomUUID().toString()
        def step = UUID.randomUUID().toString()
        def bundle = UUID.randomUUID().toString()
        store.entry(entry, GROUP, "workflow", "Demo Sample child")
        store.seeded(version, entry, 1)
        store.content(version, "workflow")
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: version, ownerKind: "workflow", side: "gives",
                               position: 1, name: "note", mustBe: required])
        StepRows.field(store, [id: bundle, owner: version, ownerKind: "workflow", side: "gives", position: 2,
                               name: "bundle", kind: "fields", mustBe: required])
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: version, ownerKind: "workflow", side: "gives",
                               parent: bundle, position: 1, name: "note", mustBe: true])
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into workflow_steps (workflow_step_id, entry_version_id, position, name, kind,
                                            pinned_version_id, pinned_kind, created_by)
                values (?::uuid, ?::uuid, 10, 'unstarted_child', 'entry', ?::uuid, 'workflow', ?::uuid)
                """).params(step, VERSION, version, SEEDER).update()

        when:
        def read = locked ? store.transactions().execute {
            snapshots.locked(tree.lock(groupId(GROUP), runId()).orElseThrow(), runId())
        } : snapshots.asRead(groupId(GROUP), runId())
        def planned = read.steps().find { it.planned().id().value() == UUID.fromString(step) }
        def workflow = planned.planned().runs() as StepRuns.Workflow
        def fields = workflow.gives().fields()

        then:
        planned.beneath() == null
        workflow.pinned().version() == new EntryVersionId(UUID.fromString(version))
        fields*.name()*.value() == ["note", "bundle"]
        (com.lilradish.lite.domain.workflow.Pointer.parse("note").reach(fields) as
                com.lilradish.lite.domain.workflow.Pointer.Reach.At).given() == required
        (com.lilradish.lite.domain.workflow.Pointer.parse("bundle").reach(fields) as
                com.lilradish.lite.domain.workflow.Pointer.Reach.At).given() == required
        (com.lilradish.lite.domain.workflow.Pointer.parse("bundle.note").reach(fields) as
                com.lilradish.lite.domain.workflow.Pointer.Reach.At).given() == required

        where:
        [locked, required] << [[true, false], [true, false]].combinations()
    }

    def "a later transaction reads a held model production's stable code source after its binding is removed"() {
        given:
        def identity = heldCodeSource()
        def sourceStep = identity.source
        def targetStep = identity.target

        when:
        def read = store.transactions().execute {
            snapshots.asRead(groupId(CodeWorkflow.GROUP), runId(CodeWorkflow.RUN))
        }
        def source = read.steps()[0]
        def held = read.steps()[1]

        then:
        source.planned().runs() == new StepRuns.Code(CodeWorkflow.CODE_STEP, null)
        held.hold().reason() == RunStepHoldReason.CODE_STEP_NOT_HELD
        held.hold().attempt() == null
        !held.hold().spentUp()
        held.hold().source() == new HoldRecord.SourceRecord(runId(CodeWorkflow.RUN),
                new com.lilradish.lite.domain.run.RunStepId(UUID.fromString(sourceStep)),
                UUID.fromString(CodeWorkflow.SEND))
        held.tries().empty
        held.planned().bindings().empty

        when:
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
        store.session.sql("update run_step_holds set released_at = now(), released_by = ?::uuid, released_by_kind = 'system' where run_step_id = ?::uuid")
                .params(SYSTEM, targetStep).update()
        def released = snapshots.asRead(groupId(CodeWorkflow.GROUP), runId(CodeWorkflow.RUN))

        then:
        released.steps()[1].hold() == null
        released.steps()[1].tries().empty
    }

    @spock.lang.Tag("postgres-schema-changing")
    def "a damaged stored source tuple is refused rather than being read as a current code hold"() {
        given:
        def identity = heldCodeSource()
        store.session.sql("alter table run_step_holds drop constraint run_step_holds_source_together").update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("update run_step_holds set source_workflow_step_id = null where run_step_id = ?::uuid")
                .param(identity.target).update()

        when:
        snapshots.asRead(groupId(CodeWorkflow.GROUP), runId(CodeWorkflow.RUN))

        then:
        def failure = thrown(NullPointerException)
        failure.message == "A held source has its planned step"
    }

    def "a stored source failure reloads its maker and original field on an unspent model production with no call"() {
        given:
        def identity = heldCodeSource()
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
        store.session.sql("update run_step_holds set released_at = now(), released_by = ?::uuid, released_by_kind = 'system' where run_step_id = ?::uuid")
                .params(SYSTEM, identity.target).update()
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, RETURNING.
        def production = store.session.sql("""
                insert into productions (run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                                         reviewed_by_model, tries, pinned_version_id, producer, created_by)
                select step.run_step_id, step.run_id, step.run_id, step.kind, step.producer,
                       step.reviewed_by_model, step.tries, step.pinned_version_id, 'model', ?::uuid
                  from run_steps step where step.run_step_id = ?::uuid
                returning production_id::text
                """).params(SYSTEM, identity.target).query(String).single()
        store.transactions().executeWithoutResult {
            // DB-SPECIFIC: PostgreSQL ::run_step_failure_reason/::uuid casts.
            store.session.sql("""
                    insert into run_step_failures (run_step_id, run_id, run_step_kind, calls_a_model, reason,
                        production_id, purpose, root_run_id, source_run_id, source_run_step_id,
                        source_workflow_step_id, source_field, created_by)
                    values (?::uuid, ?::uuid, 'question', true, ?::run_step_failure_reason,
                            ?::uuid, 'produce', ?::uuid, ?::uuid, ?::uuid, ?::uuid, 'receipt', ?::uuid)
                    """).params(identity.target, CodeWorkflow.RUN, reason.published(), production, CodeWorkflow.RUN,
                    CodeWorkflow.RUN, identity.source, CodeWorkflow.SEND, SYSTEM).update()
        }

        when:
        def read = store.transactions().execute {
            snapshots.asRead(groupId(CodeWorkflow.GROUP), runId(CodeWorkflow.RUN))
        }
        def target = read.steps()[1]
        def failure = target.failures().last()

        then:
        failure.reason() == reason
        failure.onTry().value() == UUID.fromString(production)
        failure.purpose() == ModelCallPurpose.PRODUCE
        failure.source() == new com.lilradish.lite.domain.run.FailureRecord.SourceRecord(
                new HoldRecord.SourceRecord(runId(CodeWorkflow.RUN),
                        new com.lilradish.lite.domain.run.RunStepId(UUID.fromString(identity.source)),
                        UUID.fromString(CodeWorkflow.SEND)), "receipt")
        target.hold() == null
        target.tries().size() == 1
        target.tries().first().number() == null
        target.used() == 0
        target.tries().first().calls().empty
        target.tries().first().attempts().empty
        target.planned().bindings().empty
        read.steps()[0].tries().first().values().first().field() == "receipt"

        where:
        reason << [RunStepFailureReason.SOURCE_NO_LONGER_MATCHES, RunStepFailureReason.SOURCE_LIST_NOT_HERE]
    }

    private boolean recordedMaker(String root, ProductionValueId value) {
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                select exists (select 1 from production_inputs input
                                where input.root_run_id = ?::uuid and input.source_production_value_id = ?::uuid)
                    or exists (select 1 from production_input_parts part
                                where part.root_run_id = ?::uuid and part.source_production_value_id = ?::uuid)
                """).params(root, value.value().toString(), root, value.value().toString()).query(Boolean).single()
    }

    private Map<String, String> heldCodeSource() {
        CodeWorkflow.seed(store)
        CodeWorkflow.run(store)
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                update workflow_steps set producer = 'model', producer_model = 'general', producer_mode = 'ordinary'
                 where workflow_step_id = ?::uuid
                """).param(CodeWorkflow.CHECK).update()
        def sourceStep = UUID.randomUUID().toString()
        def targetStep = UUID.randomUUID().toString()
        store.transactions().executeWithoutResult {
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("""
                    insert into run_steps (run_step_id, run_id, entry_version_id, workflow_step_id, step_kind,
                                          pinned_version_id, pinned_kind, producer, reviewed_by_model,
                                          tries, created_by, created_by_kind)
                    select case when step.workflow_step_id = ?::uuid then ?::uuid else ?::uuid end,
                           ?::uuid, step.entry_version_id, step.workflow_step_id, step.kind,
                           step.pinned_version_id, step.pinned_kind, step.producer,
                           step.reviewed_by_model, step.tries, ?::uuid, 'system'
                      from workflow_steps step where step.entry_version_id = ?::uuid
                    """).params(CodeWorkflow.SEND, sourceStep, targetStep, CodeWorkflow.RUN,
                    SYSTEM, CodeWorkflow.VERSION).update()
            // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, now(), RETURNING.
            def sourceProduction = store.session.sql("""
                    insert into productions (run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                                             reviewed_by_model, tries, may_run_again, try_number, producer,
                                             created_by, ended_at, ended_by)
                    select step.run_step_id, step.run_id, step.run_id, step.kind, step.producer,
                           step.reviewed_by_model, step.tries, false, 1, 'code', ?::uuid, now(), ?::uuid
                      from run_steps step where step.run_step_id = ?::uuid
                    returning production_id::text
                    """).params(SYSTEM, SYSTEM, sourceStep).query(String).single()
            // DB-SPECIFIC: PostgreSQL ::jsonb/::uuid casts.
            store.session.sql("""
                    insert into production_values (production_id, run_step_kind, producer, field_name, field_standing, value)
                    values (?::uuid, 'code_step', 'code', 'receipt', 'always', '"Demo Sample receipt"'::jsonb)
                    """).param(sourceProduction).update()
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("""
                    insert into run_step_holds (run_id, run_step_id, run_step_kind, calls_a_model, reason,
                                               root_run_id, source_run_id, source_run_step_id,
                                               source_workflow_step_id, created_by, created_by_kind)
                    values (?::uuid, ?::uuid, 'question', true, 'code_step_not_held',
                            ?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::uuid, 'system')
                    """).params(CodeWorkflow.RUN, targetStep, CodeWorkflow.RUN, CodeWorkflow.RUN,
                    sourceStep, CodeWorkflow.SEND, SYSTEM).update()
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("delete from bindings where binding_id = ?::uuid")
                    .param(CodeWorkflow.RECEIPT_IN).update()
        }

        [source: sourceStep, target: targetStep]
    }

}
