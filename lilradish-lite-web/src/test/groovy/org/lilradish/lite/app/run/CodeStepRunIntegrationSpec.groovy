package org.lilradish.lite.app.run

import static org.lilradish.lite.app.run.fixture.CodeWorkflow.ANN
import static org.lilradish.lite.app.run.fixture.CodeWorkflow.ANN_USER
import static org.lilradish.lite.app.run.fixture.CodeWorkflow.CAT
import static org.lilradish.lite.app.run.fixture.CodeWorkflow.CHECK
import static org.lilradish.lite.app.run.fixture.CodeWorkflow.GROUP
import static org.lilradish.lite.app.run.fixture.CodeWorkflow.RECEIPT
import static org.lilradish.lite.app.run.fixture.CodeWorkflow.REPLY
import static org.lilradish.lite.app.run.fixture.CodeWorkflow.RUN
import static org.lilradish.lite.app.run.fixture.CodeWorkflow.SEND
import static org.lilradish.lite.app.run.fixture.CodeWorkflow.TICKET_IN
import static org.lilradish.lite.app.run.fixture.CodeWorkflow.receipt
import static org.lilradish.lite.app.run.fixture.CodeWorkflow.scripted
import static org.lilradish.lite.testutil.library.LibraryStore.groupId

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.libprunus.core.error.ApiErrorException
import org.lilradish.lite.app.codestep.CodeRuns
import org.lilradish.lite.app.codestep.CodeSteps
import org.lilradish.lite.app.filling.FillFieldAnswer
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.run.fixture.CodeWorkflow
import org.lilradish.lite.domain.codestep.CodeError
import org.lilradish.lite.domain.codestep.CodeErrorReason
import org.lilradish.lite.domain.codestep.CodeStep
import org.lilradish.lite.domain.declaration.Demand
import org.lilradish.lite.domain.declaration.Field
import org.lilradish.lite.domain.declaration.FieldKind
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.declaration.FieldShape
import org.lilradish.lite.domain.declaration.FieldStanding
import org.lilradish.lite.domain.declaration.HowMany
import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.filling.FillField
import org.lilradish.lite.domain.identity.GroupPermission
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.domain.run.RunStepHoldReason
import org.lilradish.lite.domain.run.RunningOn
import org.lilradish.lite.domain.run.StepFailure
import org.lilradish.lite.domain.run.StepPosition
import org.lilradish.lite.domain.run.StepPositions
import org.lilradish.lite.domain.run.WorkflowStepId
import org.lilradish.lite.domain.wire.JsonValue
import org.lilradish.lite.testutil.SnapshottingAppender
import org.lilradish.lite.testutil.codestep.CodeStepsHeld
import org.lilradish.lite.testutil.codestep.ScriptedCodeStep
import org.lilradish.lite.testutil.codestep.SpecCodeStep
import org.lilradish.lite.testutil.inference.ScriptedModelCalls
import org.lilradish.lite.testutil.library.DeployedModels
import org.lilradish.lite.testutil.library.LibraryStore
import org.lilradish.lite.testutil.run.RunRows
import org.slf4j.LoggerFactory
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.support.GenericApplicationContext
import org.springframework.dao.DataAccessException
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import tools.jackson.databind.node.JsonNodeFactory

/**
 * A run going on through a code step, on a real server running the real baseline: its try written on the engine's
 * own thread just before its code runs and only while it is still the try to make, its code run there outside any
 * transaction, and how it ended written after; what a person may do to it where the code may not run again, or
 * where the release now declares it otherwise.
 */
class CodeStepRunIntegrationSpec extends Specification {

    static final String SYSTEM = RunRows.WORKFLOW_RUNNER

    static final Logger CODES_LOGGER = LoggerFactory.getLogger(EngineCodes) as Logger

    static final JsonNodeFactory NODES = JsonNodeFactory.instance

    static final String GRADES = "00000007-0000-4000-8000-000000000c09"

    static final List<Logger> ENGINE_LOGGERS = [RunEngine, EngineExecutor, EngineThread, CodeThread,
                                                EngineCodes].collect {
        LoggerFactory.getLogger(it) as Logger
    }

    /** A second value the code step may give back, named to be kept by the store after the receipt. */
    static final Field ZETA = SpecCodeStep.standing("zeta", new FieldShape.Text(64))

    /** A grade the code step may give back, a term of the group's grades. */
    static final Field GRADE = new Field(new FieldName("grade"), null, null,
            new FieldShape.Term(LibraryStore.versionId(GRADES)), new HowMany.One(),
            new Demand.Stands(false, FieldStanding.ALWAYS, null))

    /** Check, as what reads what send gives back is answered. */
    static final StepAnswers.ReadByAnswer CHECK_READS = new StepAnswers.ReadByAnswer(UUID.fromString(CHECK), null)

    /** Tags the code step may give back, two at most. */
    static final Field TAGS = new Field(new FieldName("tags"), null, null, new FieldShape.Text(8), new HowMany.Many(2),
            new Demand.Stands(false, FieldStanding.ALWAYS, null))

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    RunTree tree

    RunSnapshots snapshots

    EngineExecutor executor

    RunEngine engine

    StepActs acts

    List<EngineExecutor> pools = []

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, "code_step_run_" + (++databasesMade))
    }

    def cleanup() {
        pools.each { it.destroy() }
    }

    /** The engine as a release holding {@code held} runs it, on a pool of its own. */
    private void holding(CodeStep... held) {
        def release = CodeStepsHeld.of(held)
        wired(release, release)
    }

    /** The engine reading what {@code declaring} declares and running code out of {@code running}, on a pool of its own. */
    private void wired(CodeSteps declaring, CodeSteps running) {
        def session = store.session
        tree = new RunTree(session)
        snapshots = new RunSnapshots(session, declaring)
        def writes = new EngineWrites(session)
        executor = EngineExecutors.of(store.database)
        pools << executor
        def ceilings = new CeilingReach(session, DeployedModels.HELD, writes)
        engine = new RunEngine(session, store.transactions(), tree, snapshots, writes, executor,
                new EngineCalls(new ScriptedModelCalls(), store.transactions(), tree, writes, ceilings),
                new EngineCodes(new CodeRuns(running), store.transactions(), tree, snapshots, writes, executor),
                DeployedModels.HELD, ceilings, new GroupRoles(session))
        acts = new StepActs(session, store.transactions(), new GroupRoles(session), tree, snapshots, writes, engine)
    }

    /** As a start does: the run written, its tree locked, the first step planned, in one transaction. */
    private void started() {
        store.transactions().executeWithoutResult {
            CodeWorkflow.run(store)
            engine.planStarted(tree.lock(groupId(GROUP), runId()).orElseThrow())
        }
    }

    /** Every thread of both pools kept busy until the gate returned is opened, so what is handed over queues. */
    private CountDownLatch occupied() {
        def gate = new CountDownLatch(1)
        def busy = new CountDownLatch(4)
        2.times {
            executor.execute {
                busy.countDown()
                gate.await(60, TimeUnit.SECONDS)
            }
            executor.executeCode {
                busy.countDown()
                gate.await(60, TimeUnit.SECONDS)
            }
        }
        assert busy.await(10, TimeUnit.SECONDS)
        gate
    }

    /** The engine's application closing, as the application context the pool belongs to publishes it. */
    private void closing() {
        def own = new GenericApplicationContext()
        executor.setApplicationContext(own)
        executor.onApplicationEvent(new ContextClosedEvent(own))
    }

    /** Whatever the engine, its pool, its threads and its code log from now on. */
    private static SnapshottingAppender listening() {
        def logged = new SnapshottingAppender()
        logged.start()
        ENGINE_LOGGERS.each { it.addAppender(logged) }
        logged
    }

    private static void unlistened(SnapshottingAppender logged) {
        ENGINE_LOGGERS.each { it.detachAppender(logged) }
    }

    /**
     * Every task handed to either pool finished, those they handed over in turn too, the pools left running and not
     * stopping. Both idle between two readings of how many tasks the two were ever handed, the same both times: a task
     * hands over before it finishes, so one that handed across between the readings shows as a count moved.
     */
    private void drained() {
        def code = executor.@codePool.threadPoolExecutor
        def calls = executor.@pool.threadPoolExecutor
        def idle = { pool -> pool.taskCount == pool.completedTaskCount }
        def handed = { code.taskCount + calls.taskCount }
        until("every task handed over finished") {
            def before = handed()
            idle(code) && idle(calls) && handed() == before
        }
    }

    private static void until(String what, Closure<Boolean> reached) {
        def deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!reached()) {
            assert System.nanoTime() < deadline: "never ${what}"
            Thread.sleep(10)
        }
    }

    private static RunId runId() {
        new RunId(UUID.fromString(RUN))
    }

    private static WorkflowStepId stepId(String step) {
        new WorkflowStepId(UUID.fromString(step))
    }

    /** Each try of a step: its number, producer, who asked for it, how it ended, and whether its code may run again. */
    private List<String> triesOf(String step) {
        store.texts("""
                select try.try_number || ' ' || try.producer || ' ' || try.created_by_kind || ' '
                       || case when try.ended_at is null then 'open'
                               else coalesce(try.lost_reason::text, 'yielded') end
                       || ' ' || coalesce(try.may_run_again::text, '-')
                  from productions try join run_steps held on held.run_step_id = try.run_step_id
                 where held.workflow_step_id = ?::uuid order by try.try_number
                """, step)
    }

    private boolean ended(String step, int tries) {
        def read = triesOf(step)
        read.size() == tries && !read.last().contains(" open ")
    }

    private List<String> values() {
        store.texts("""
                select held.producer || ' ' || held.field_name || ' ' || held.field_standing || ' '
                       || coalesce(held.standing_threshold::text, '-') || ' ' || coalesce(held.value::text, '-') || ' '
                       || coalesce(held.confidence::text, '-') || ' ' || held.needs_review
                  from production_values held join productions try on try.production_id = held.production_id
                  join run_steps step on step.run_step_id = try.run_step_id
                 where step.workflow_step_id = ?::uuid order by try.try_number
                """, SEND)
    }

    /**
     * Why each try of code went wrong: this system's reason, the field and the member it names, or how long what the
     * code said is, whether it was cut, and what it said; then what came back.
     */
    private List<String> wentWrong() {
        store.texts("""
                select coalesce(code_error_reason::text, '-') || ' ' || coalesce(code_error_path, '-') || ' '
                       || coalesce(code_error_member, '-') || ' ' || coalesce(length(lost_detail)::text, '-') || ' '
                       || lost_detail_truncated || ' ' || coalesce(lost_detail, '-') || ' '
                       || coalesce(returned_by_code, '-')
                  from productions where producer = 'code' and lost_reason = 'errored' order by try_number
                """)
    }

    /** What each try of code gone wrong names as reading what no longer matches: a step's key, an output's field. */
    private List<String> readBy() {
        store.texts("""
                select coalesce(code_error_read_by_step::text, code_error_read_by_output, '-')
                  from productions where producer = 'code' and lost_reason = 'errored' order by try_number
                """)
    }

    private List<String> holds() {
        store.texts("""
                select reason || ' ' || created_by || ' ' || coalesce(released_by::text, '-')
                  from run_step_holds order by created_at, run_step_hold_id
                """)
    }

    private StepPosition positionOf(String step) {
        def read = store.transactions().execute { snapshots.asRead(groupId(GROUP), runId()) }
        StepPositions.of(read, read.step(stepId(step)).orElseThrow())
    }

    private static JsonValue.JsonObject filled(Map<String, String> values) {
        new JsonValue.JsonObject(values.collect { name, value ->
            new JsonValue.JsonMember(name, new JsonValue.JsonString(value))
        })
    }

    private static Closure<JsonValue.JsonObject> throwing(String message) {
        { JsonValue.JsonObject takes -> throw new IllegalStateException(message) }
    }

    def "a code step reached is planned without a try, and made on a code thread outside any transaction once that commits"() {
        given:
        CodeWorkflow.seed(store)
        def code = scripted(false, { receipt("R-7") })
        holding(code)
        def gate = occupied()

        when:
        started()

        then: "nothing is written of it where it was planned, and it reads as its try being made"
        triesOf(SEND) == []
        positionOf(SEND) == new StepPosition.Running(RunningOn.NEXT_TRY)
        code.runs.get() == 0

        when:
        gate.countDown()
        until("check was asked") { triesOf(CHECK).size() == 1 }

        then: "its try written by the system, as the release says of running it again, and run once off any transaction"
        triesOf(SEND) == ["1 code system yielded false"]
        code.runs.get() == 1
        code.ran.every { it.thread() ==~ /run-code-\d+/ && !it.inTransaction() }

        and: "taking what the run was started with, and giving back a value standing as the release declares, with no confidence"
        store.texts("""
                select taken.binding_id || ' ' || coalesce(taken.source_production_value_id::text, '-')
                  from production_inputs taken join run_steps step on step.run_step_id = taken.run_step_id
                 where step.workflow_step_id = ?::uuid
                """, SEND) == ["${TICKET_IN} -" as String]
        values() == ['code receipt always - "R-7" - false']

        and: "so the next step is asked of whoever answers it"
        triesOf(CHECK) == ["1 person system open -"]
    }

    /** The store keeps a code step's values by name, in no order of its own; what orders them is the release. */
    def "a code step's values are read in the order its release declares them now, any it no longer declares after"() {
        given:
        CodeWorkflow.seed(store)
        def giving = { JsonValue.JsonObject takes ->
            new JsonValue.JsonObject([new JsonValue.JsonMember("zeta", new JsonValue.JsonString("Z")),
                                      new JsonValue.JsonMember("receipt", new JsonValue.JsonString("R"))])
        }
        holding(new ScriptedCodeStep(CodeWorkflow.CODE_STEP, [REPLY], [ZETA, RECEIPT], false, giving))
        started()
        until("check was asked") { triesOf(CHECK).size() == 1 }

        when:
        holding(new ScriptedCodeStep(CodeWorkflow.CODE_STEP, [REPLY], declared, false, giving))
        def read = store.transactions().execute { snapshots.asRead(groupId(GROUP), runId()) }

        then:
        read.step(stepId(SEND)).orElseThrow().newest().orElseThrow().values()*.field() == fields

        where:
        declared         || fields
        [ZETA, RECEIPT]  || ["zeta", "receipt"]
        [RECEIPT, ZETA]  || ["receipt", "zeta"]
        [ZETA]           || ["zeta", "receipt"]
    }

    def "a code step reads what went in and what came out as its release declares them"() {
        given:
        CodeWorkflow.seed(store)
        holding(scripted(false, { receipt("R-7") }))
        started()
        until("check was asked") { triesOf(CHECK).size() == 1 }

        when:
        def read = reading().step(groupId(GROUP), runId(), stepId(SEND), ANN_USER)

        then:
        read.cameOut() == [new StepAnswers.CameOutAnswer("receipt",
                new StepAnswers.StandingAnswer(1, NODES.stringNode("R-7"), null, null), "stands")]
        read.wentIn() == [new StepAnswers.WentInAnswer("reply", new StepAnswers.FromAnswer("run_input", "ticket", null,
                null, null, null), NODES.stringNode("The printer is on fire."), null, null)]
        read.step().gaveBack() == [new StepAnswers.ShownValueAnswer("receipt", NODES.stringNode("R-7"), null, "stands",
                null)]
    }

    /**
     * A release may change what a code step declares under every run that ran it; what those runs were given is read
     * as kept wherever it no longer reads as declared, and by the name it was kept under, never failing the read.
     */
    def "a code step's past values read as the release declares them now where they still can, and as kept otherwise, marked so"() {
        given:
        CodeWorkflow.seed(store)
        grades()
        holding(scripted(false, { receipt("R-7") }))
        started()
        until("check was asked") { triesOf(CHECK).size() == 1 }
        if (release == null) {
            holding()
        } else {
            holding(new ScriptedCodeStep(CodeWorkflow.CODE_STEP, [REPLY], release, false, { receipt() }))
        }

        when:
        def read = reading().step(groupId(GROUP), runId(), stepId(SEND), ANN_USER)
        def all = reading().steps(groupId(GROUP), runId(), ANN_USER)

        then:
        read.cameOut() == [new StepAnswers.CameOutAnswer("receipt",
                new StepAnswers.StandingAnswer(1, value, null, earlier), "stands")]
        read.triesMade()*.values() == [[new StepAnswers.TriedValueAnswer("receipt", value, null, "stands", null, null,
                earlier)]]
        read.step().gaveBack() == [new StepAnswers.ShownValueAnswer("receipt", value, null, "stands", earlier)]

        and: "the run's steps read alike"
        all.steps()[0].gaveBack() == [new StepAnswers.ShownValueAnswer("receipt", value, null, "stands", earlier)]

        where:
        release                                                                         || value                        | earlier
        [SpecCodeStep.standing("receipt", new FieldShape.Text(8))]                      || NODES.stringNode("R-7")      | null
        [SpecCodeStep.standing("receipt", new FieldShape.Text(2))]                      || NODES.stringNode('"R-7"')    | true
        [SpecCodeStep.standing("receipt", new FieldShape.Term(LibraryStore.versionId(GRADES)))] || NODES.stringNode('"R-7"') | true
        [SpecCodeStep.standing("receipt", new FieldShape.Plain(FieldKind.NUMBER))]      || NODES.stringNode('"R-7"')    | true
        [SpecCodeStep.standing("receipt_no", new FieldShape.Text(64))]                  || NODES.stringNode('"R-7"')    | true
        [SpecCodeStep.standing("receipt", new FieldShape.Term(LibraryStore.versionId(SEND)))] || NODES.stringNode('"R-7"') | true
        null                                                                            || NODES.stringNode('"R-7"')    | true
    }

    /** Whether the form is drawn and whether the answer is taken are one judgement, on what it gives back alone. */
    def "a code step pinning a list not here only in what it takes is answered here, the form drawn and the answer taken"() {
        given:
        CodeWorkflow.seed(store, 2)
        def missing = new Field(new FieldName("tier"), null, null, new FieldShape.Term(LibraryStore.versionId(SEND)),
                new HowMany.One(), new Demand.Given(false))
        holding(new ScriptedCodeStep(CodeWorkflow.CODE_STEP, [REPLY, missing], [RECEIPT], false, { receipt() }))
        started()
        until("send went wrong") { ended(SEND, 1) }

        when:
        def read = reading().step(groupId(GROUP), runId(), stepId(SEND), ANN_USER)

        then:
        read.step().acts().contains("answer")
        read.answering() == new StepAnswers.AnsweringAnswer(2, false, null,
                FillFieldAnswer.of(FillField.of([RECEIPT], [:])), null)

        and: "the try it made at once went wrong for that list, about the field that pins it"
        wentWrong() == ["takes_a_list_not_here tier - - false - -"]

        when:
        acts.answer(groupId(GROUP), runId(), stepId(SEND), 2, ANN_USER, filled([receipt: "R-by-hand"]), "Sent it.")

        then:
        triesOf(SEND) == ["1 code system errored false", "2 person person yielded false"]
    }

    def "a code step pinning a list not here in what it gives back is not answered here, the form not drawn and the answer refused"() {
        given:
        CodeWorkflow.seed(store, 2)
        holding(new ScriptedCodeStep(CodeWorkflow.CODE_STEP, [REPLY],
                [RECEIPT, SpecCodeStep.standing("grade", new FieldShape.Term(LibraryStore.versionId(SEND)))], false,
                { receipt() }))
        started()
        until("send went wrong") { ended(SEND, 1) }

        when:
        def read = reading().step(groupId(GROUP), runId(), stepId(SEND), ANN_USER)

        then:
        !read.step().acts().contains("answer")
        read.answering() == null

        and: "the try it made at once went wrong for that list, about the field that pins it"
        wentWrong() == ["gives_a_list_not_here grade - - false - -"]

        when:
        acts.answer(groupId(GROUP), runId(), stepId(SEND), 2, ANN_USER, filled([receipt: "R", grade: "A"]), "Sent it.")

        then: "refused for the list, which no step or output reading it is to blame for"
        def refused = thrown(GivesOtherwiseRefusal)
        refused.errorCode() == RefusalCode.CODE_STEP_GIVES_OTHERWISE
        refused.fault() == new CodeError.Fault(CodeErrorReason.GIVES_A_LIST_NOT_HERE, [new FieldName("grade")], null, null)
        triesOf(SEND) == ["1 code system errored false"]
    }

    /** Answering it is not drawn, so its row is the one place that says which field keeps it from being answered. */
    def "a code step pinning a list not here in what it gives back names that field where its row withholds answering"() {
        given:
        CodeWorkflow.seed(store, 2)
        holding(new ScriptedCodeStep(CodeWorkflow.CODE_STEP, [REPLY],
                [RECEIPT, SpecCodeStep.standing("grade", new FieldShape.Term(LibraryStore.versionId(SEND)))], false,
                { receipt() }))
        started()
        until("send went wrong") { ended(SEND, 1) }

        when:
        def read = reading().step(groupId(GROUP), runId(), stepId(SEND), ANN_USER)

        then:
        read.step().withheld() == [new StepAnswers.WithheldAnswer("answer", "CODE_STEP_GIVES_OTHERWISE", null, "grade"),
                                   new StepAnswers.WithheldAnswer("ask_again", "ASK_AGAIN_NOT_OFFERED", null, null)]
        !read.step().acts().contains("answer")
        read.answering() == null
    }

    /** A reference list of the group's in service, offering the grades gold and silver and no receipt. */
    private void grades() {
        def entry = GRADES.replace("00000007-", "00000006-")
        store.entry(entry, GROUP, "reference_list", "Grades")
        store.seeded(GRADES, entry, 1)
        store.content(GRADES, "reference_list")
        ["gold", "silver"].eachWithIndex { term, index ->
            store.session.sql("""
                    insert into reference_list_terms (entry_version_id, position, term, meaning, created_by)
                    values (?::uuid, ?, ?, ?, ?::uuid)
                    """).params(GRADES, index + 1, term, "Graded " + term + ".", LibraryStore.SEEDER).update()
        }
    }

    private RunSteps reading() {
        new RunSteps(store.session, new GroupRoles(store.session), snapshots, store.transactionManager())
    }

    /** A code step tells nothing, so answering it puts no instruction, only what the release declares it gives back. */
    def "answering a code step here puts the try it fills and what the release declares it gives back, and no instruction"() {
        given:
        CodeWorkflow.seed(store, 2)
        holding(scripted(false, throwing("Refused.")))
        started()
        until("send went wrong") { ended(SEND, 1) }
        def steps = new RunSteps(store.session, new GroupRoles(store.session), snapshots, store.transactionManager())

        when:
        def read = steps.step(groupId(GROUP), runId(), stepId(SEND), ANN_USER)

        then:
        read.step().acts().contains("answer")
        read.answering() == new StepAnswers.AnsweringAnswer(2, false, null,
                FillFieldAnswer.of(FillField.of([RECEIPT], [:])), null)
    }

    def "code that throws where it may run again spends a try each time, what it said kept cut and marked, until the step has failed"() {
        given:
        CodeWorkflow.seed(store, 3)
        def code = scripted(true, throwing("x" * 3000))
        holding(code)

        when:
        started()
        until("send spent its tries") { positionOf(SEND) instanceof StepPosition.Failed }

        then:
        (positionOf(SEND) as StepPosition.Failed).why() == new StepFailure.TriesSpent(3, 3)
        triesOf(SEND) == ["1 code system errored true", "2 code system errored true", "3 code system errored true"]
        wentWrong() == ["- - - 2048 true ${"x" * 2048} -" as String] * 3
        code.runs.get() == 3

        and:
        values() == []
        triesOf(CHECK) == []
    }

    def "code that may not run again and went wrong is owed a person, and asking again is refused"() {
        given:
        CodeWorkflow.seed(store, 2)
        def code = scripted(false, throwing("The mail server refused it."))
        holding(code)
        started()
        until("send went wrong") { ended(SEND, 1) }

        when:
        acts.askAgain(groupId(GROUP), runId(), stepId(SEND), 2, ANN_USER)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.ASK_AGAIN_NOT_OFFERED

        and:
        with(positionOf(SEND) as StepPosition.Owed) {
            number() == 2
            !open()
            !beyond()
        }
        triesOf(SEND) == ["1 code system errored false"]
        wentWrong() == ["- - - 27 false The mail server refused it. -"]
        code.runs.get() == 1
    }

    def "code that may not run again is never tried a second time by code, which the store refuses as well"() {
        given:
        CodeWorkflow.seed(store, 2)
        holding(scripted(false, throwing("Refused.")))
        started()
        until("send went wrong") { ended(SEND, 1) }

        when:
        store.session.sql("""
                insert into productions (run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                                         reviewed_by_model, tries, try_number, producer, may_run_again, created_by,
                                         created_by_kind)
                select step.run_step_id, step.run_id, ?::uuid, step.kind, step.producer, step.reviewed_by_model,
                       step.tries, 2, 'code', false, ?::uuid, 'system'
                  from run_steps step where step.workflow_step_id = ?::uuid
                """).params(RUN, SYSTEM, SEND).update()

        then:
        def refused = thrown(DataAccessException)
        refused.message.contains("productions_code_again_only_for_may_run_again")

        and:
        triesOf(SEND) == ["1 code system errored false"]
    }

    /** Said by a person, never by the system, and standing as the release declares, as any try of the step would. */
    def "code that may not run again is answered here as a person's try, taking what the release declares it gives back"() {
        given:
        CodeWorkflow.seed(store, 2)
        def code = scripted(false, throwing("Refused."))
        holding(code)
        started()
        until("send went wrong") { ended(SEND, 1) }

        when:
        acts.answer(groupId(GROUP), runId(), stepId(SEND), 2, ANN_USER, filled([receipt: "R-by-hand"]), "Sent it myself.")

        then:
        triesOf(SEND) == ["1 code system errored false", "2 person person yielded false"]
        store.texts("select created_by || ' ' || ended_by || ' ' || explanation from productions where try_number = 2") ==
                ["${ANN} ${ANN} Sent it myself." as String]
        values() == ['person receipt always - "R-by-hand" - false']

        and: "the code never run again, and the next step asked"
        code.runs.get() == 1
        until("check was asked") { triesOf(CHECK).size() == 1 }
    }

    def "a code step a person produces is asked of them as the release declares it, and never run as code"() {
        given:
        CodeWorkflow.seed(store, 1, false, "person")
        def code = scripted(true, { receipt() })
        holding(code)

        when:
        started()
        acts.answer(groupId(GROUP), runId(), stepId(SEND), 1, ANN_USER, filled([receipt: "R-by-hand"]), "Sent it.")

        then:
        triesOf(SEND) == ["1 person system yielded true"]
        values() == ['person receipt always - "R-by-hand" - false']
        code.runs.get() == 0
        until("check was asked") { triesOf(CHECK).size() == 1 }
    }

    /** Nothing the version binds may reach code the release no longer declares so, and each try spent says why. */
    def "a release no longer giving back what a later step reads errs every try at once, its code never run"() {
        given:
        CodeWorkflow.seed(store, 2)
        def code = new ScriptedCodeStep(CodeWorkflow.CODE_STEP, [REPLY],
                [SpecCodeStep.standing("receipt_id", new FieldShape.Text(64))], true, { receipt() })
        holding(code)

        when:
        started()
        until("send spent its tries") { positionOf(SEND) instanceof StepPosition.Failed }

        then:
        triesOf(SEND) == ["1 code system errored true", "2 code system errored true"]
        wentWrong() == ["gives_otherwise receipt - - false - -"] * 2
        readBy() == [CHECK] * 2
        code.runs.get() == 0
        values() == []
    }

    /** No try is spent on it: it is not code gone wrong that the release holds none of. */
    def "a code step this release does not hold is held back, spending nothing, and nobody may answer it"() {
        given:
        CodeWorkflow.seed(store, 2)
        holding()

        when:
        started()

        then:
        holds() == ["code_step_not_held ${SYSTEM} -" as String]
        triesOf(SEND) == []
        positionOf(SEND) instanceof StepPosition.HeldBack
        (positionOf(SEND) as StepPosition.HeldBack).reason() == RunStepHoldReason.CODE_STEP_NOT_HELD

        when:
        acts.answer(groupId(GROUP), runId(), stepId(SEND), 1, ANN_USER, filled([receipt: "R-by-hand"]), "Sent it.")

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.STEP_MOVED_ON
        triesOf(SEND) == []
    }

    def "a code step held back for a release not holding it goes on the next time its run is driven under a release holding it"() {
        given:
        CodeWorkflow.seed(store, 2)
        holding()
        started()
        def code = scripted(false, { receipt() })
        holding(code)

        when:
        engine.drive(groupId(GROUP), runId())
        until("check was asked") { triesOf(CHECK).size() == 1 }

        then:
        holds() == ["code_step_not_held ${SYSTEM} ${SYSTEM}" as String]
        triesOf(SEND) == ["1 code system yielded false"]
        code.runs.get() == 1
    }

    /** What came back is kept as it came back, so why it went wrong can be read beside it. */
    def "code that gives back what does not fit what the release declares went wrong for that reason, about the field, and what it gave is kept"() {
        given:
        CodeWorkflow.seed(store, 1)
        grades()
        holding(new ScriptedCodeStep(CodeWorkflow.CODE_STEP, [REPLY], [RECEIPT, GRADE, TAGS], false, { returned }))

        when:
        started()
        until("send went wrong") { ended(SEND, 1) }

        then:
        triesOf(SEND) == ["1 code system errored false"]
        wentWrong() == ["${why} - false - ${kept}" as String]
        values() == []

        where:
        returned                                                        || why                         | kept
        null                                                            || "gave_nothing - -"          | "-"
        gave([receipt: "R" * 65])                                       || "too_long receipt -"        | '{"receipt":"' + "R" * 65 + '"}'
        gave([:])                                                       || "nothing_given receipt -"   | '{}'
        gave([receipt: null])                                           || "nothing_given receipt -"   | '{"receipt":null}'
        gave([receipt: 5])                                              || "not_its_kind receipt -"    | '{"receipt":5}'
        gave([receipt: "R-1", grade: "bronze"])                         || "not_a_term grade -"        | '{"receipt":"R-1","grade":"bronze"}'
        gave([receipt: "R-1", tags: ["a", "b", "c"]])                   || "too_many tags -"           | '{"receipt":"R-1","tags":["a","b","c"]}'
        gave([receipt: "R-1", extra: true])                             || "not_declared - extra"      | '{"receipt":"R-1","extra":true}'
        gave([receipt: "R" + Character.toString(0xD800)])               || "unkeepable receipt -"      | "-"
    }

    /** Running it failed here and not in the code, so nothing the code said is kept, and nothing it gave back. */
    def "code whose running failed on this side went wrong for that reason, about no field, keeping nothing"() {
        given:
        CodeWorkflow.seed(store, 1)
        def code = scripted(false, { receipt() })
        wired(CodeStepsHeld.of(code), CodeStepsHeld.NONE)

        when:
        started()
        until("send went wrong") { ended(SEND, 1) }

        then:
        triesOf(SEND) == ["1 code system errored false"]
        wentWrong() == ["failed_on_this_side - - - false - -"]
        code.runs.get() == 0
        values() == []
    }

    /**
     * The page words the reason for its reader; what the code said is read as it said it, and never beside one. That
     * what came back was not kept is said outright, since nothing kept is also what code that gave nothing back leaves.
     */
    def "a try of code gone wrong reads why: this system's reason, what it is about and reads it, or what the code said"() {
        given:
        CodeWorkflow.seed(store, 1)
        def code = [
                "too long"       : scripted(false, { gave([receipt: "R" * 65]) }),
                "unkeepable"     : scripted(false, { gave([receipt: "R" + Character.toString(0xD800)]) }),
                "gave nothing"   : scripted(false, { null }),
                "undeclared"     : scripted(false, { gave([receipt: "R-1", extra: 1]) }),
                "gives otherwise": new ScriptedCodeStep(CodeWorkflow.CODE_STEP, [REPLY],
                        [SpecCodeStep.standing("receipt_id", new FieldShape.Text(64))], false, { receipt() }),
                "said nothing"   : scripted(false, throwing(" ")),
                "threw"          : scripted(false, throwing("The mail server refused it.")),
        ][ran]
        holding(code)
        started()
        until("send went wrong") { ended(SEND, 1) }

        when:
        def tried = reading().step(groupId(GROUP), runId(), stepId(SEND), ANN_USER).triesMade()

        then:
        tried*.ended() == ["errored"]
        tried*.erroredFor() == [erroredFor]
        tried*.wentWrong() == [wentWrong]
        tried*.returned() == [returned]

        where:
        ran               || erroredFor                                                                   | wentWrong                                                             | returned
        "too long"        || new StepAnswers.ErroredForAnswer("too_long", "receipt", null, null, null)     | null                                                                  | '{"receipt":"' + "R" * 65 + '"}'
        "unkeepable"      || new StepAnswers.ErroredForAnswer("unkeepable", "receipt", null, null, true)   | null                                                                  | null
        "gave nothing"    || new StepAnswers.ErroredForAnswer("gave_nothing", null, null, null, null)      | null                                                                  | null
        "undeclared"      || new StepAnswers.ErroredForAnswer("not_declared", null, "extra", null, null)   | null                                                                  | '{"receipt":"R-1","extra":1}'
        "gives otherwise" || new StepAnswers.ErroredForAnswer("gives_otherwise", "receipt", null, CHECK_READS, null) | null                                                           | null
        "said nothing"    || new StepAnswers.ErroredForAnswer("said_nothing", null, null, null, null)      | null                                                                  | null
        "threw"           || null                                                                         | new StepAnswers.WentWrongAnswer("The mail server refused it.", false, null) | null
    }

    /** What code gives back, each member as written, in the order written. */
    private static JsonValue.JsonObject gave(Map<String, ?> members) {
        new JsonValue.JsonObject(members.collect { new JsonValue.JsonMember(it.key, json(it.value)) })
    }

    private static JsonValue json(Object written) {
        switch (written) {
            case null: return new JsonValue.JsonNull()
            case String: return new JsonValue.JsonString(written as String)
            case Boolean: return new JsonValue.JsonBoolean(written as boolean)
            case Number: return new JsonValue.JsonNumber(new BigDecimal(written.toString()))
            case List: return new JsonValue.JsonArray((written as List).collect { json(it) })
            default: throw new IllegalArgumentException("No JSON is written as ${written.class.name}")
        }
    }

    /** A stop never takes back a run under way; it only holds what would follow it. */
    def "code under way when its run is stopped finishes and is recorded, nothing follows it, and opening the run again goes on"() {
        given:
        CodeWorkflow.seed(store)
        def gate = new CountDownLatch(1)
        def code = scripted(false, { receipt() }, gate)
        holding(code)
        started()
        until("send's code was running") { code.runs.get() == 1 }

        when:
        RunRows.stopped(store, RUN, CAT)
        gate.countDown()
        drained()

        then:
        triesOf(SEND) == ["1 code system yielded false"]
        values() == ['code receipt always - "R-1" - false']
        triesOf(CHECK) == []

        when:
        store.session.sql("update run_stops set opened_again_at = now(), opened_again_by = ?::uuid").params(CAT).update()
        holding(code)
        engine.drive(groupId(GROUP), runId())

        then:
        triesOf(CHECK) == ["1 person system open -"]
        code.runs.get() == 1
    }

    /** Only the transaction that finds the try open ends it, so an end outlived by another is dropped whole. */
    def "code that comes back to a try ended meanwhile writes nothing, says so by its keys alone, and is never run again"() {
        given:
        CodeWorkflow.seed(store)
        def gate = new CountDownLatch(1)
        def code = scripted(false, { receipt("invoice 4471") }, gate)
        holding(code)
        def logged = new SnapshottingAppender()
        logged.start()
        CODES_LOGGER.addAppender(logged)
        started()
        until("send's code was running") { code.runs.get() == 1 }

        when:
        store.session.sql("""
                update productions set ended_at = now(), ended_by = ?::uuid, ended_by_kind = 'system',
                                       lost_reason = 'nothing_came_back'
                 where producer = 'code'
                """).params(SYSTEM).update()
        gate.countDown()
        drained()

        then:
        triesOf(SEND) == ["1 code system nothing_came_back false"]
        values() == []
        code.runs.get() == 1

        and:
        def aTry = store.texts("select production_id::text from productions where producer = 'code'")[0]
        logged.list*.formattedMessage ==
                ["Try ${aTry} of run ${RUN} was ended before its code came back; what it came to is dropped" as String]
        logged.list[0].level == Level.WARN
        !logged.list[0].formattedMessage.contains("invoice 4471")

        cleanup:
        CODES_LOGGER.detachAppender(logged)
    }

    /**
     * Two drives each plan the same try; only the one that writes it runs the code, and the other finds it made
     * rather than failing on the store's own refusal of a second try one.
     */
    def "two drives racing to make a code step's try write it once, and run its code once, the other quietly"() {
        given:
        CodeWorkflow.seed(store)
        def code = scripted(false, { receipt() })
        holding(code)
        def logged = listening()
        def gate = occupied()
        started()
        engine.drive(groupId(GROUP), runId())

        when:
        gate.countDown()
        drained()

        then:
        triesOf(SEND) == ["1 code system yielded false"]
        code.runs.get() == 1
        triesOf(CHECK) == ["1 person system open -"]

        and:
        logged.list.findAll { it.level == Level.ERROR } == []

        cleanup:
        unlistened(logged)
    }

    /** Stopping begun while it waited for the tree is judged once the tree is held, and writes nothing. */
    def "a code step's try waiting on its tree's lock makes nothing once the engine began stopping meanwhile"() {
        given:
        CodeWorkflow.seed(store)
        def code = scripted(false, { receipt() })
        holding(code)
        def gate = occupied()
        started()
        def holder = store.holding("select 1 from runs where run_id = '${RUN}' for no key update" as String)

        when:
        gate.countDown()
        store.untilWaiting(1)
        closing()
        holder.commit()
        holder.close()
        drained()

        then:
        triesOf(SEND) == []
        code.runs.get() == 0
    }

    /** The try is left open for whatever sweeps what a stopped system left out; what the error said may be code's. */
    def "code that ends its thread with an error leaves its try open, logged by its keys and its kind alone"() {
        given:
        CodeWorkflow.seed(store)
        def code = scripted(false, { throw new OutOfMemoryError("invoice 4471 overflowed") })
        holding(code)
        def logged = listening()

        when:
        started()
        until("the error was logged") { logged.list.any { it.loggerName == EngineCodes.name } }
        drained()

        then:
        triesOf(SEND) == ["1 code system open false"]
        def aTry = store.texts("select production_id::text from productions where producer = 'code'")[0]
        def said = logged.list.find { it.loggerName == EngineCodes.name }
        said.level == Level.ERROR
        said.formattedMessage == "Try ${aTry} (number 1) of step ${SEND} of run ${RUN} under run ${RUN} of group ${GROUP}" +
                " stopped its thread with java.lang.OutOfMemoryError"
        said.throwableProxy == null

        and: "nothing logged says what the error said"
        logged.list.every { !it.formattedMessage.contains("invoice 4471") && it.throwableProxy == null }

        cleanup:
        unlistened(logged)
    }

    def "a code step's try planned but not yet made makes nothing once its run is stopped"() {
        given:
        CodeWorkflow.seed(store)
        def code = scripted(false, { receipt() })
        holding(code)
        def gate = occupied()
        started()

        when:
        RunRows.stopped(store, RUN, CAT)
        gate.countDown()
        drained()

        then:
        triesOf(SEND) == []
        code.runs.get() == 0
    }

    /** A stop on what the step runs drives nothing, so the hand-over goes on itself, and the hold is what it finds. */
    def "a code step's try planned but not yet made when what it runs is stopped is held on that stop, and goes on once it is let go"() {
        given:
        CodeWorkflow.seed(store)
        def code = scripted(false, { receipt() })
        holding(code)
        def gate = occupied()
        started()
        store.stopped(CodeWorkflow.WORKFLOW, LibraryStore.FIRST_STEWARD)

        when:
        gate.countDown()
        drained()

        then:
        triesOf(SEND) == []
        holds() == ["entry_stopped ${SYSTEM} -" as String]
        code.runs.get() == 0

        when:
        store.session.sql("update entry_stops set let_go_at = now(), let_go_by = ?::uuid where entry_id = ?::uuid")
                .params(LibraryStore.FIRST_STEWARD, CodeWorkflow.WORKFLOW).update()
        engine.goesOn(LibraryStore.entryId(CodeWorkflow.WORKFLOW))
        until("check was asked") { triesOf(CHECK).size() == 1 }

        then:
        holds() == ["entry_stopped ${SYSTEM} ${SYSTEM}" as String]
        triesOf(SEND) == ["1 code system yielded false"]
        code.runs.get() == 1
    }

    /** Left as it reads, for whatever starts the system again to go on from. */
    def "a code step's try planned but not yet made makes nothing once the engine is stopping, and reads as still to be made"() {
        given:
        CodeWorkflow.seed(store)
        def code = scripted(false, { receipt() })
        holding(code)
        def gate = occupied()
        started()

        when:
        closing()
        gate.countDown()
        drained()

        then:
        triesOf(SEND) == []
        code.runs.get() == 0
        positionOf(SEND) == new StepPosition.Running(RunningOn.NEXT_TRY)
    }

    def "a code step whose hand-over was lost reads as still to be made, and the next drive makes its first try"() {
        given:
        CodeWorkflow.seed(store)
        def code = scripted(false, { receipt() })
        holding(code)
        executor.@codePool.shutdown()
        started()

        expect:
        triesOf(SEND) == []
        positionOf(SEND) == new StepPosition.Running(RunningOn.NEXT_TRY)

        when:
        holding(code)
        engine.drive(groupId(GROUP), runId())
        until("check was asked") { triesOf(CHECK).size() == 1 }

        then:
        triesOf(SEND) == ["1 code system yielded false"]
        code.runs.get() == 1
    }

    /** What is handed over already still runs and is recorded; what would follow is left to be made. */
    def "shut down while code runs, the pool waits for it and its end, and makes no further code step's try"() {
        given:
        CodeWorkflow.seed(store, 1, true)
        def gate = new CountDownLatch(1)
        def code = scripted(false, { receipt() }, gate)
        holding(code)
        started()
        until("send's code was running") { code.runs.get() == 1 }

        when:
        def shutting = Thread.start { executor.destroy() }
        until("the pool was stopping") { executor.stopping() }
        gate.countDown()
        shutting.join(TimeUnit.SECONDS.toMillis(20))

        then:
        !shutting.alive
        triesOf(SEND) == ["1 code system yielded false"]
        triesOf(CHECK) == []
        positionOf(CHECK) == new StepPosition.Running(RunningOn.NEXT_TRY)
        code.runs.get() == 1
    }

    /** What the release says now decides, never what it said when the earlier try was made. */
    def "a release that lets code run again, deployed after a try went wrong, has the next try made by itself"() {
        given:
        CodeWorkflow.seed(store, 3)
        holding(scripted(false, throwing("Refused.")))
        started()
        until("send went wrong") { ended(SEND, 1) }
        def again = scripted(true, { receipt() })
        holding(again)

        when:
        engine.drive(groupId(GROUP), runId())
        until("check was asked") { triesOf(CHECK).size() == 1 }

        then:
        triesOf(SEND) == ["1 code system errored false", "2 code system yielded true"]
        again.runs.get() == 1
    }

    def "asking code that may run again for a try beyond those declared makes it as that person's, and runs it"() {
        given:
        CodeWorkflow.seed(store, 1)
        def calls = new AtomicInteger()
        def code = scripted(true, {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("Busy.")
            }
            receipt()
        })
        holding(code)
        started()
        until("send spent its tries") { positionOf(SEND) instanceof StepPosition.Failed }

        when:
        acts.askAgain(groupId(GROUP), runId(), stepId(SEND), 2, ANN_USER)
        until("check was asked") { triesOf(CHECK).size() == 1 }

        then:
        triesOf(SEND) == ["1 code system errored true", "2 code person yielded true"]
        store.texts("select created_by::text from productions where try_number = 2 and producer = 'code'") ==
                [ANN]
        code.runs.get() == 2
    }

    /** The press is judged again as the try is made, and a step moved on since makes nothing of it. */
    def "a try of code asked again that the step has moved past before it is made makes nothing"() {
        given:
        CodeWorkflow.seed(store, 1)
        def code = scripted(true, throwing("Busy."))
        holding(code)
        started()
        until("send spent its tries") { positionOf(SEND) instanceof StepPosition.Failed }
        def logged = listening()
        def gate = occupied()

        when:
        acts.askAgain(groupId(GROUP), runId(), stepId(SEND), 2, ANN_USER)
        acts.answer(groupId(GROUP), runId(), stepId(SEND), 2, ANN_USER, filled([receipt: "R-by-hand"]), "Sent it.")
        gate.countDown()
        drained()

        then:
        triesOf(SEND) == ["1 code system errored true", "2 person person yielded true"]
        code.runs.get() == 1

        and: "found moved on rather than failing on the store's refusal of a second try two"
        logged.list.findAll { it.level == Level.ERROR } == []

        cleanup:
        unlistened(logged)
    }

    /** Refused on the same judgement the try made at once went wrong on, so both name the same later step. */
    def "answering here code whose release no longer gives back what a later step reads is refused naming that step, and writes nothing"() {
        given:
        CodeWorkflow.seed(store, 2)
        def code = new ScriptedCodeStep(CodeWorkflow.CODE_STEP, [REPLY],
                [SpecCodeStep.standing("receipt_id", new FieldShape.Text(64))], false, { receipt() })
        holding(code)
        started()
        until("send went wrong") { ended(SEND, 1) }

        when:
        acts.answer(groupId(GROUP), runId(), stepId(SEND), 2, ANN_USER, filled([receipt_id: "R-by-hand"]), "Sent it.")

        then:
        def refused = thrown(GivesOtherwiseRefusal)
        refused.errorCode() == RefusalCode.CODE_STEP_GIVES_OTHERWISE
        refused.fault() == new CodeError.Fault(CodeErrorReason.GIVES_OTHERWISE, [new FieldName("receipt")], null,
                new CodeError.StepReads(UUID.fromString(CHECK)))
        readBy() == [CHECK]

        and:
        triesOf(SEND) == ["1 code system errored false"]
        values() == []
    }

    /** Answering it is not drawn, so its row is the one place that says which later step keeps it from being answered. */
    def "a code step whose release no longer gives back what a later step reads names that step and the field where its row withholds answering"() {
        given:
        CodeWorkflow.seed(store, 2)
        holding(new ScriptedCodeStep(CodeWorkflow.CODE_STEP, [REPLY],
                [SpecCodeStep.standing("receipt_id", new FieldShape.Text(64))], false, { receipt() }))
        started()
        until("send went wrong") { ended(SEND, 1) }

        when:
        def read = reading().step(groupId(GROUP), runId(), stepId(SEND), ANN_USER)

        then:
        read.step().withheld() == [new StepAnswers.WithheldAnswer("answer", "CODE_STEP_GIVES_OTHERWISE", CHECK_READS,
                "receipt"), new StepAnswers.WithheldAnswer("ask_again", "ASK_AGAIN_NOT_OFFERED", null, null)]
        !read.step().acts().contains("answer")
        read.answering() == null
    }

    /**
     * The permission is judged first, so a reader who may not answer is told only that. The reading is handed the
     * permissions, so this holds whatever the roles grant.
     */
    def "a reader who may not answer a code step giving otherwise is told only that they may not, naming nothing"() {
        given:
        CodeWorkflow.seed(store, 2)
        holding(new ScriptedCodeStep(CodeWorkflow.CODE_STEP, [REPLY],
                [SpecCodeStep.standing("receipt_id", new FieldShape.Text(64))], false, { receipt() }))
        started()
        until("send went wrong") { ended(SEND, 1) }
        def run = store.transactions().execute { snapshots.asRead(groupId(GROUP), runId()) }
        def permitted = EnumSet.complementOf(EnumSet.of(GroupPermission.ANSWER_STEP))

        when:
        def row = new StepReading(run, 1, true, permitted, null, Optional.empty(), [:], [:], [:], [:])
                .steps().steps()[0]

        then:
        row.withheld() == [new StepAnswers.WithheldAnswer("answer", "ACT_NOT_PERMITTED", null, null),
                           new StepAnswers.WithheldAnswer("ask_again", "ACT_NOT_PERMITTED", null, null)]
        row.acts() == []
    }

    /** Only what goes into the step changed, which a person answering it has no need of. */
    def "answering here code whose release no longer takes what the step binds is answered as the release gives back now"() {
        given:
        CodeWorkflow.seed(store, 2)
        def code = new ScriptedCodeStep(CodeWorkflow.CODE_STEP,
                [SpecCodeStep.given("memo", new FieldShape.Text(2000))], [RECEIPT], false, { receipt() })
        holding(code)
        started()
        until("send went wrong") { ended(SEND, 1) }

        when:
        acts.answer(groupId(GROUP), runId(), stepId(SEND), 2, ANN_USER, filled([receipt: "R-by-hand"]), "Sent it.")

        then:
        wentWrong() == ["takes_otherwise reply - - false - -"]
        triesOf(SEND) == ["1 code system errored false", "2 person person yielded false"]
        values() == ['person receipt always - "R-by-hand" - false']
        code.runs.get() == 0
    }
}
