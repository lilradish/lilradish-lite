package org.lilradish.lite.app.run

import static org.lilradish.lite.app.run.fixture.TicketWorkflow.ANN
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.CAT
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.CONFIRM
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.CONFIRM_QUESTION
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.CONFIRM_VERSION
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.GROUP
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.RUN
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARISE
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARISE_VERSION
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARY
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.TICKET_IN
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.VERSION
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.WORKFLOW
import static org.lilradish.lite.testutil.library.LibraryStore.FIRST_STEWARD
import static org.lilradish.lite.testutil.library.LibraryStore.SEEDER
import static org.lilradish.lite.testutil.library.LibraryStore.attempting
import static org.lilradish.lite.testutil.library.LibraryStore.groupId

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.lilradish.lite.app.codestep.CodeRuns
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.run.fixture.CodeWorkflow
import org.lilradish.lite.app.run.fixture.StepRows
import org.lilradish.lite.app.run.fixture.TicketWorkflow
import org.lilradish.lite.domain.codestep.CodeStepDeclaration
import org.lilradish.lite.domain.codestep.ReleasedCodeStep
import org.lilradish.lite.domain.declaration.Declaration
import org.lilradish.lite.domain.declaration.DeclarationSide
import org.lilradish.lite.domain.declaration.Demands
import org.lilradish.lite.domain.declaration.FieldShape
import org.lilradish.lite.domain.declaration.OfferedTerms
import org.lilradish.lite.domain.identity.SubjectId
import org.lilradish.lite.domain.inference.DidNotFitReason
import org.lilradish.lite.domain.inference.ModelCallOutcome
import org.lilradish.lite.domain.inference.ModelCallPurpose
import org.lilradish.lite.domain.referencelist.Term
import org.lilradish.lite.domain.referencelist.TermMeaning
import org.lilradish.lite.domain.registry.EntryVersionId
import org.lilradish.lite.domain.run.AttemptRecord
import org.lilradish.lite.domain.run.CallRecord
import org.lilradish.lite.domain.run.HoldRecord
import org.lilradish.lite.domain.run.ModelCallId
import org.lilradish.lite.domain.run.ReviewRecord
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.domain.run.RunStepHoldReason
import org.lilradish.lite.domain.run.RunStepSendAttemptId
import org.lilradish.lite.domain.run.StepRuns
import org.lilradish.lite.domain.run.StopRecord
import org.lilradish.lite.domain.run.TryLostReason
import org.lilradish.lite.domain.run.WorkflowStepId
import org.lilradish.lite.domain.wire.JsonValue
import org.lilradish.lite.domain.wire.StoreLabels
import org.lilradish.lite.domain.workflow.StepProducer
import org.lilradish.lite.testutil.CountingDataSource
import org.lilradish.lite.testutil.codestep.CodeStepsHeld
import org.lilradish.lite.testutil.codestep.SpecCodeStep
import org.lilradish.lite.testutil.inference.ScriptedModelCalls
import org.lilradish.lite.testutil.library.DeployedModels
import org.lilradish.lite.testutil.library.LibraryStore
import org.lilradish.lite.testutil.run.RunRows
import org.springframework.jdbc.core.simple.JdbcClient
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * One run as the store holds it, read on a real server running the real baseline: what its version says of each
 * step beside what its rows say, and under its tree's lock, each entry it runs held so no stop lands unseen.
 */
class RunSnapshotsIntegrationSpec extends Specification {

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    RunTree tree

    RunSnapshots snapshots

    @AutoCleanup("shutdownNow")
    ExecutorService racing = Executors.newFixedThreadPool(2)

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, "run_snapshots_" + (++databasesMade))
        tree = new RunTree(store.session)
        snapshots = new RunSnapshots(store.session, CodeStepsHeld.NONE)
        TicketWorkflow.seed(store)
        TicketWorkflow.run(store)
    }

    static final String SYSTEM = RunRows.WORKFLOW_RUNNER

    static final String SECOND_RUN = "00000008-0000-4000-8000-000000000e21"

    static final String THIRD_RUN = "00000008-0000-4000-8000-000000000e22"

    private static RunId runId(String spelled = RUN) {
        new RunId(UUID.fromString(spelled))
    }

    private static String runKey(int number) {
        String.format("00000008-0000-4000-8000-%012d", 900 + number)
    }

    private Instant stoppedAt(String entry) {
        store.session.sql("select created_at from entry_stops where entry_id = ?::uuid and let_go_at is null")
                .params(entry).query(OffsetDateTime).single().toInstant()
    }

    /** Summarise asked by the system and answered by Cat, as the engine and the answer would have left it. */
    private void askedAndAnswered() {
        driven()
        StepRows.answered(store, SUMMARISE, CAT, "Read it twice.", [(SUMMARY): '"A printer fire."'])
    }

    /** The run gone on as far as it goes by itself, as the engine takes it, on a pool shut once it has. */
    private void driven() {
        def executor = EngineExecutors.of(store.database)
        def writes = new EngineWrites(store.session)
        def ceilings = new CeilingReach(store.session, DeployedModels.HELD, writes)
        new RunEngine(store.session, store.transactions(), tree, snapshots, writes, executor,
                new EngineCalls(new ScriptedModelCalls(), store.transactions(), tree, writes, ceilings),
                new EngineCodes(new CodeRuns(CodeStepsHeld.NONE), store.transactions(), tree, snapshots, writes, executor),
                DeployedModels.HELD, ceilings, new GroupRoles(store.session))
                .drive(groupId(GROUP), runId())
        executor.destroy()
    }

    /** Summarise produced and reviewed by the model, and its row in the run written; that row's key. */
    private String byModel() {
        store.session.sql("""
                update workflow_steps set producer = 'model', producer_model = 'general', producer_mode = 'ordinary',
                                          reviewer_model = 'general', reviewer_mode = 'ordinary'
                 where workflow_step_id = ?::uuid
                """).params(SUMMARISE).update()
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

    private String modelTry(String runStep, int number) {
        store.session.sql("""
                insert into productions (run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                                         reviewed_by_model, tries, pinned_version_id, try_number, producer, created_by)
                values (?::uuid, ?::uuid, ?::uuid, 'question', 'model', true, 2, ?::uuid, ?, 'model', ?::uuid)
                returning cast(production_id as text)
                """).params(runStep, RUN, RUN, SUMMARISE_VERSION, number, SYSTEM).query(String).single()
    }

    private String attempt(String runStep, String production, String purpose, boolean tooLong) {
        store.session.sql("""
                insert into run_step_send_attempts (run_step_id, run_id, run_step_kind, workflow_step_id, purpose, model,
                                                    mode, production_id, too_long, payload, created_by, created_by_kind)
                values (?::uuid, ?::uuid, 'question', ?::uuid, cast(? as model_call_purpose), 'general', 'ordinary',
                        ?::uuid, ?, '{}', ?::uuid, 'system')
                returning cast(run_step_send_attempt_id as text)
                """).params(runStep, RUN, SUMMARISE, purpose, production, tooLong, SYSTEM).query(String).single()
    }

    /** The call {@code attempt} sent, ended as {@code outcome}, or still out where it is none. */
    private String called(String runStep, String production, String attempt, String purpose, String outcome) {
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

    /** A turnaway of {@code call}, which ended turned away: as what may be spent is used up, where so. */
    private void turnedAway(String call, boolean spentUp) {
        store.session.sql("""
                insert into model_call_turnaways (model_call_id, spent_up, model_call_outcome, created_by)
                values (?::uuid, ?, case when ? then 'turned_away'::model_call_outcome end, ?::uuid)
                """).params(call, spentUp, spentUp, SYSTEM).update()
    }

    private void held(String runStep, RunStepHoldReason reason, String attempt) {
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
        def open = modelTry(runStep, 1)
        def sending = attempt(runStep, open, "produce", true)
        held(runStep, RunStepHoldReason.TOO_LONG, sending)

        when:
        def read = snapshots.asRead(groupId(GROUP), runId()).steps()[0]

        then:
        read.hold() == new HoldRecord(RunStepHoldReason.TOO_LONG, heldSince(), attemptId(sending), false)
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
        held(runStep, RunStepHoldReason.TURNED_AWAY, sending)

        when:
        def read = snapshots.asRead(groupId(GROUP), runId()).steps()[0]

        then:
        read.hold() == new HoldRecord(RunStepHoldReason.TURNED_AWAY, heldSince(), attemptId(sending), spentUp)
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
        store.session.sql("""
                update productions set model_call_id = ?::uuid, model_call_outcome = 'came_back', ended_at = now(),
                                       ended_by = ?::uuid, ended_by_kind = 'system'
                 where production_id = ?::uuid
                """).params(cameBack, SYSTEM, first).update()
        def reviewing = attempt(runStep, first, "review", false)
        def reviewCall = called(runStep, first, reviewing, "review", outcome)
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

    /** One read of a page of runs, whatever the page holds, and not one statement more for each run or version on it. */
    def "reads a group's runs together in as many statements however many runs and versions there are"() {
        given:
        askedAndAnswered()
        (2..6).each { runOfItsOwnVersion(it) }
        def statements = []
        def counted = new RunSnapshots(JdbcClient.create(new CountingDataSource(store.database, statements)),
                CodeStepsHeld.NONE)

        when:
        counted.asRead(groupId(GROUP), [runId()])
        def one = statements.size()
        statements.clear()
        def read = counted.asRead(groupId(GROUP), [runId()] + (2..6).collect { runId(runKey(it)) })

        then:
        statements.size() == one
        one == 21

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

    /** A reference list of {@code group} in service at {@code version}, offering one term. */
    private String offering(String version, String term, String group = GROUP) {
        def entry = version.replace("00000007-", "00000006-")
        store.entry(entry, group, "reference_list", "Kinds of " + term)
        store.seeded(version, entry, 1)
        store.content(version, "reference_list")
        store.session.sql("""
                insert into reference_list_terms (entry_version_id, position, term, meaning, created_by)
                values (?::uuid, 1, ?, ?, ?::uuid)
                """).params(version, term, "Said of " + term + ".", SEEDER).update()
        version
    }

    private void termField(String holder, String kind, String side, int position, String name, String list,
                           String standing) {
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

    /** Cat's run {@code number} of a workflow version of its own, running what the ticket workflow runs. */
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

    /** The release, not the store, declares a code step, so a list it pins that the group lacks is left out, not failed. */
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
        def reading = new RunSnapshots(store.session, held ? CodeStepsHeld.of(code) : CodeStepsHeld.NONE)

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
                CodeStepsHeld.NONE)

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

    /** What keeps a stop from landing between the engine reading an entry running and acting on it. */
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

        when:
        store.transactions().executeWithoutResult {
            snapshots.locked(tree.lock(groupId(GROUP), runId()).orElseThrow(),
                    runId("00000008-0000-4000-8000-000000000e03"))
        }

        then:
        thrown(IllegalStateException)
    }
}
