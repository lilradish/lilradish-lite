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
import ch.qos.logback.classic.spi.ILoggingEvent
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.libprunus.core.error.ApiErrorException
import org.lilradish.lite.app.codestep.CodeSteps
import org.lilradish.lite.app.files.FileDeployment
import org.lilradish.lite.app.filling.FillFieldAnswer
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.run.fixture.CodeWorkflow
import org.lilradish.lite.app.run.fixture.StepRows
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
import org.lilradish.lite.domain.declaration.TextLines
import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.file.MediaTypeName
import org.lilradish.lite.domain.filling.FillField
import org.lilradish.lite.domain.filling.FillPath
import org.lilradish.lite.domain.identity.GroupPermission
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.domain.run.RunInTree
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
import org.mockito.Mockito
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.util.unit.DataSize
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import tools.jackson.databind.node.JsonNodeFactory

class CodeStepRunIntegrationSpec extends Specification {

    static final String SYSTEM = RunRows.WORKFLOW_RUNNER

    static final Logger CODES_LOGGER = LoggerFactory.getLogger(EngineCodes) as Logger

    static final JsonNodeFactory NODES = JsonNodeFactory.instance

    static final String GRADES = "00000007-0000-4000-8000-000000000c09"

    static final List<Logger> ENGINE_LOGGERS = [RunEngine, EngineExecutor, EngineThread, CodeThread,
                                                EngineCodes].collect {
        LoggerFactory.getLogger(it) as Logger
    }

    static final Field ZETA = SpecCodeStep.standing("zeta", new FieldShape.Text(64))

    static final Field GRADE = new Field(new FieldName("grade"), null, null,
            new FieldShape.Term(LibraryStore.versionId(GRADES)), new HowMany.One(),
            new Demand.Stands(false, FieldStanding.ALWAYS, null))

    static final StepAnswers.ReadByAnswer CHECK_READS = new StepAnswers.ReadByAnswer(UUID.fromString(CHECK), null)

    static final Field TAGS = new Field(new FieldName("tags"), null, null, new FieldShape.Text(8), new HowMany.Many(2),
            new Demand.Stands(false, FieldStanding.ALWAYS, null))

    static final Field ATTACHMENT = SpecCodeStep.standing("attachment",
            new FieldShape.File([new MediaTypeName("text/plain")], 1024L))

    static final FillPath ATTACHMENT_PATH = new FillPath([new FillPath.Named(new FieldName("attachment"))])

    static final Field COPY = SpecCodeStep.standing("copy",
            new FieldShape.File([new MediaTypeName("text/plain")], 1024L))

    static final FillPath COPY_PATH = new FillPath([new FillPath.Named(new FieldName("copy"))])

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    EngineWiring wiring

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

    private void holding(CodeStep... held) {
        def release = CodeStepsHeld.of(held)
        wired(release, release)
    }

    private void wired(CodeSteps declaring, CodeSteps running,
                       FileDeployment fileLimits = EngineWiring.fileDeployment()) {
        wiring = new EngineWiring(store, new ScriptedModelCalls(), declaring, DeployedModels.HELD, running,
                Deployments.DEEP_ENOUGH, fileLimits)
        tree = wiring.tree
        snapshots = wiring.snapshots
        executor = wiring.executor
        engine = wiring.engine
        acts = wiring.acts
        pools << executor
    }

    // Write the run, lock its tree, and plan the first step in one transaction.
    private void started() {
        store.transactions().executeWithoutResult {
            CodeWorkflow.run(store)
            engine.planStarted(tree.lock(groupId(GROUP), runId()).orElseThrow())
        }
    }

    private void holdingWithFiles(FileDeployment fileLimits, CodeStep... held) {
        def release = CodeStepsHeld.of(held)
        wired(release, release, fileLimits)
    }

    private CountDownLatch occupied() {
        wiring.occupied()
    }

    private void closing() {
        wiring.closing()
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

    private static List<ILoggingEvent> events(SnapshottingAppender logged) {
        // AppenderBase.doAppend holds this monitor while appending to the list.
        synchronized (logged) {
            return new ArrayList<>(logged.list)
        }
    }

    private void drained() {
        wiring.drained()
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

    private List<String> triesOf(String step) {
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
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
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        store.texts("""
                select held.producer || ' ' || held.field_name || ' ' || held.field_standing || ' '
                       || coalesce(held.standing_threshold::text, '-') || ' ' || coalesce(held.value::text, '-') || ' '
                       || coalesce(held.confidence::text, '-') || ' ' || held.needs_review
                  from production_values held join productions try on try.production_id = held.production_id
                  join run_steps step on step.run_step_id = try.run_step_id
                 where step.workflow_step_id = ?::uuid order by try.try_number
                """, SEND)
    }

    private List<String> wentWrong() {
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("""
                select coalesce(code_error_reason::text, '-') || ' ' || coalesce(code_error_path, '-') || ' '
                       || coalesce(code_error_member, '-') || ' ' || coalesce(length(lost_detail)::text, '-') || ' '
                       || lost_detail_truncated || ' ' || coalesce(lost_detail, '-') || ' '
                       || coalesce(returned_by_code, '-')
                  from productions where producer = 'code' and lost_reason = 'errored' order by try_number
                """)
    }

    private List<String> readBy() {
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("""
                select coalesce(code_error_read_by_step::text, code_error_read_by_output, '-')
                  from productions where producer = 'code' and lost_reason = 'errored' order by try_number
                """)
    }

    private List<String> holds() {
        // DB-SPECIFIC: PostgreSQL ::text casts.
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
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        store.texts("""
                select taken.binding_id || ' ' || coalesce(taken.source_production_value_id::text, '-')
                  from production_inputs taken join run_steps step on step.run_step_id = taken.run_step_id
                 where step.workflow_step_id = ?::uuid
                """, SEND) == ["${TICKET_IN} -" as String]
        values() == ['code receipt always - "R-7" - false']

        and: "so the next step is asked of whoever answers it"
        triesOf(CHECK) == ["1 person system open -"]
    }

    // Values are stored by name; the current release supplies their order.
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
                new StepAnswers.StandingAnswer(1, NODES.stringNode("R-7"), null, null), "stands", null, null, null)]
        read.wentIn() == [new StepAnswers.WentInAnswer("reply", new StepAnswers.FromAnswer("run_input", "ticket", null,
                null, null, null, UUID.fromString(RUN), 1, null), NODES.stringNode("The printer is on fire."), null, null,
                null, null)]
        read.step().gaveBack() == [new StepAnswers.ShownValueAnswer("receipt", NODES.stringNode("R-7"), null, "stands",
                null)]
    }

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
                new StepAnswers.StandingAnswer(1, value, null, earlier), "stands", null, null, null)]
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

    // Drawing the form and accepting its answer must use the same judgement.
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
                FillFieldAnswer.of(FillField.of([RECEIPT], [:])), null, null, null, null)

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

    private void grades() {
        def entry = GRADES.replace("00000007-", "00000006-")
        store.entry(entry, GROUP, "reference_list", "Grades")
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

    private RunSteps reading() {
        new RunSteps(store.session, new GroupRoles(store.session), snapshots, store.transactionManager(), Deployments.DEEP_ENOUGH, new org.lilradish.lite.app.currency.GroupCurrencies(store.session, new org.springframework.transaction.support.TransactionTemplate(store.transactionManager()), new GroupRoles(store.session), org.lilradish.lite.testutil.library.DeployedModels.HELD))
    }

    def "answering a code step here puts the try it fills and what the release declares it gives back, and no instruction"() {
        given:
        CodeWorkflow.seed(store, 2)
        holding(scripted(false, throwing("Refused.")))
        started()
        until("send went wrong") { ended(SEND, 1) }
        def steps = new RunSteps(store.session, new GroupRoles(store.session), snapshots, store.transactionManager(), Deployments.DEEP_ENOUGH, new org.lilradish.lite.app.currency.GroupCurrencies(store.session, new org.springframework.transaction.support.TransactionTemplate(store.transactionManager()), new GroupRoles(store.session), org.lilradish.lite.testutil.library.DeployedModels.HELD))

        when:
        def read = steps.step(groupId(GROUP), runId(), stepId(SEND), ANN_USER)

        then:
        read.step().acts().contains("answer")
        read.answering() == new StepAnswers.AnsweringAnswer(2, false, null,
                FillFieldAnswer.of(FillField.of([RECEIPT], [:])), null, null, null, null)
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
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
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

    def "code giving a line break for one-line text spends a try, keeps the original return and reads its field"() {
        given:
        CodeWorkflow.seed(store, 1)
        grades()
        def one = SpecCodeStep.standing("receipt", new FieldShape.Text(64, TextLines.ONE))
        holding(new ScriptedCodeStep(CodeWorkflow.CODE_STEP, [REPLY], [one, GRADE, TAGS], false,
                { gave([receipt: "Demo Sample\n"]) }))

        when:
        started()
        until("send went wrong") { ended(SEND, 1) }
        def tried = reading().step(groupId(GROUP), runId(), stepId(SEND), ANN_USER).triesMade()

        then:
        triesOf(SEND) == ["1 code system errored false"]
        values() == []
        tried*.ended() == ["errored"]
        tried*.erroredFor() == [new StepAnswers.ErroredForAnswer("line_break", "receipt", null, null, null, null)]
        tried*.returned() == ['{"receipt":"Demo Sample\\n"}']
        tried*.wentWrong() == [null]
    }

    def "code whose running failed on this side went wrong for that reason, about no field, keeping nothing"() {
        given:
        CodeWorkflow.seed(store, 1)
        def code = scripted(false, { receipt() })
        def release = CodeStepsHeld.of(code)
        def running = Mockito.spy(release)
        assert running.declaration(CodeWorkflow.CODE_STEP).isPresent()
        Mockito.doThrow(new IllegalStateException("The code runner could not read its held step."))
                .when(running).held(CodeWorkflow.CODE_STEP)
        wired(release, running)

        when:
        started()
        until("send went wrong") { ended(SEND, 1) }

        then:
        triesOf(SEND) == ["1 code system errored false"]
        wentWrong() == ["failed_on_this_side - - - false - -"]
        code.runs.get() == 0
        values() == []
    }

    def "a try of code gone wrong reads why: this system's reason, what it is about and reads it, or what the code said"() {
        given:
        CodeWorkflow.seed(store, 1)
        def code = [
                "too long"       : scripted(false, { gave([receipt: "R" * 65]) }),
                "unkeepable"     : scripted(false, { gave([receipt: "R" + Character.toString(0xD800)]) }),
                "gave nothing"   : scripted(false, { null }),
                "undeclared"     : scripted(false, { gave([receipt: "R-1", extra: 1]) }),
                "undeclared, cut": scripted(false, { gave([receipt: "R-1", ("n" * 65): 1]) }),
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
        ran               || erroredFor                                                                              | wentWrong                                                                   | returned
        "too long"        || new StepAnswers.ErroredForAnswer("too_long", "receipt", null, null, null, null)           | null                                                                        | '{"receipt":"' + "R" * 65 + '"}'
        "unkeepable"      || new StepAnswers.ErroredForAnswer("unkeepable", "receipt", null, null, null, true)         | null                                                                        | null
        "gave nothing"    || new StepAnswers.ErroredForAnswer("gave_nothing", null, null, null, null, null)            | null                                                                        | null
        "undeclared"      || new StepAnswers.ErroredForAnswer("not_declared", null, "extra", null, null, null)         | null                                                                        | '{"receipt":"R-1","extra":1}'
        "undeclared, cut" || new StepAnswers.ErroredForAnswer("not_declared", null, "n" * 64, true, null, null)        | null                                                                        | '{"receipt":"R-1","' + "n" * 65 + '":1}'
        "gives otherwise" || new StepAnswers.ErroredForAnswer("gives_otherwise", "receipt", null, null, CHECK_READS, null) | null                                                                   | null
        "said nothing"    || new StepAnswers.ErroredForAnswer("said_nothing", null, null, null, null, null)            | null                                                                        | null
        "threw"           || null                                                                                    | new StepAnswers.WentWrongAnswer("The mail server refused it.", false, null) | null
    }

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

    // Stopping cannot retract code already running; it only holds subsequent work.
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
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
        store.session.sql("update run_stops set opened_again_at = now(), opened_again_by = ?::uuid").params(CAT).update()
        holding(code)
        engine.drive(groupId(GROUP), runId())

        then:
        triesOf(CHECK) == ["1 person system open -"]
        code.runs.get() == 1
    }

    // Only the transaction that finds the try open may end it.
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
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
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
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def aTry = store.texts("select production_id::text from productions where producer = 'code'")[0]
        def loggedEvents = events(logged)
        loggedEvents*.formattedMessage ==
                ["Try ${aTry} of run ${RUN} was ended before its code came back; what it came to is dropped" as String]
        loggedEvents[0].level == Level.WARN
        !loggedEvents[0].formattedMessage.contains("invoice 4471")

        cleanup:
        CODES_LOGGER.detachAppender(logged)
    }

    // Only the transaction that inserts a try may run its code.
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
        events(logged).findAll { it.level == Level.ERROR } == []

        cleanup:
        unlistened(logged)
    }

    // Recheck stopping after acquiring the tree lock, before writing anything.
    def "a code step's try waiting on its tree's lock makes nothing once the engine began stopping meanwhile"() {
        given:
        CodeWorkflow.seed(store)
        def code = scripted(false, { receipt() })
        holding(code)
        def gate = occupied()
        started()
        // DB-SPECIFIC: PostgreSQL FOR NO KEY UPDATE.
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

    // Leave the try open for recovery and avoid logging details that may contain code output.
    def "code that ends its thread with an error leaves its try open, logged by its keys and its kind alone"() {
        given:
        CodeWorkflow.seed(store)
        def code = scripted(false, { throw new OutOfMemoryError("invoice 4471 overflowed") })
        holding(code)
        def logged = listening()

        when:
        started()
        until("the error was logged") { events(logged).any { it.loggerName == EngineCodes.name } }
        drained()

        then:
        triesOf(SEND) == ["1 code system open false"]
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def aTry = store.texts("select production_id::text from productions where producer = 'code'")[0]
        def loggedEvents = events(logged)
        def said = loggedEvents.find { it.loggerName == EngineCodes.name }
        said.level == Level.ERROR
        said.formattedMessage == "Try ${aTry} (number 1) of step ${SEND} of run ${RUN} under run ${RUN} of group ${GROUP}" +
                " stopped its thread with java.lang.OutOfMemoryError"
        said.throwableProxy == null

        and: "nothing logged says what the error said"
        loggedEvents.every { !it.formattedMessage.contains("invoice 4471") && it.throwableProxy == null }

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
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
        store.session.sql("update entry_stops set let_go_at = now(), let_go_by = ?::uuid where entry_id = ?::uuid")
                .params(LibraryStore.FIRST_STEWARD, CodeWorkflow.WORKFLOW).update()
        engine.goesOn(LibraryStore.entryId(CodeWorkflow.WORKFLOW))
        until("check was asked") { triesOf(CHECK).size() == 1 }

        then:
        holds() == ["entry_stopped ${SYSTEM} ${SYSTEM}" as String]
        triesOf(SEND) == ["1 code system yielded false"]
        code.runs.get() == 1
    }

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

    // Use the current release declaration when deciding whether code may run.
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
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select created_by::text from productions where try_number = 2 and producer = 'code'") ==
                [ANN]
        code.runs.get() == 2
    }

    // Recheck the requested act when making the try; the step may have advanced.
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
        events(logged).findAll { it.level == Level.ERROR } == []

        cleanup:
        unlistened(logged)
    }

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

    // Check permission first so a reader without it learns only that the answer is refused.
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
        def row = new StepReading(RunInTree.root(run), 1, true, permitted, null, Optional.empty(), [:], [:], [:], [:], Deployments.DEEP_ENOUGH)
                .steps().steps()[0]

        then:
        row.withheld() == [new StepAnswers.WithheldAnswer("answer", "ACT_NOT_PERMITTED", null, null),
                           new StepAnswers.WithheldAnswer("ask_again", "ACT_NOT_PERMITTED", null, null)]
        row.acts() == []
    }

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
    def "valid code file survives another returned field failing and is downloadable from its run"() {
        given:
        CodeWorkflow.seed(store)
        def stream = new ByteArrayInputStream("kept bytes".getBytes("UTF-8"))
        def code = new ScriptedCodeStep(CodeWorkflow.CODE_STEP, [REPLY], [RECEIPT, ATTACHMENT], false,
                { new CodeStep.Output(receipt("x" * 65),
                        [(ATTACHMENT_PATH): [new CodeStep.FileOutput("receipt.txt", "text/plain", stream)]]) })
        holding(code)

        when:
        started()
        until("file try ended") { ended(SEND, 1) }
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def ids = store.texts("select file_id::text from errored_code_returned_files")
        def reading = new RunSteps(store.session, new GroupRoles(store.session), snapshots,
                store.transactionManager(), Deployments.DEEP_ENOUGH, new org.lilradish.lite.app.currency.GroupCurrencies(store.session, new org.springframework.transaction.support.TransactionTemplate(store.transactionManager()), new GroupRoles(store.session), org.lilradish.lite.testutil.library.DeployedModels.HELD))
                .step(groupId(GROUP), runId(), stepId(SEND), ANN_USER).triesMade()[0]

        then:
        triesOf(SEND) == ["1 code system errored false"]
        wentWrong()[0].startsWith("too_long receipt ")
        ids.size() == 1
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        store.texts("select file_id::text from run_file_holders where run_id = ?::uuid", RUN) == ids
        reading.keptFiles() == [new StepAnswers.KeptCodeFileAnswer("attachment", UUID.fromString(ids[0]),
                "receipt.txt", "text/plain", 10L)]
        reading.returned().contains('"receipt":"')
        reading.returned().contains(ids[0])
        EngineWiring.files(store).open(runId(), groupId(GROUP), UUID.fromString(ids[0]))
                .content().withCloseable { it.readAllBytes() } == "kept bytes".getBytes("UTF-8")
        !code.ran[0].inTransaction()
    }

    def "two code file fields exactly fill the configured total and keep both files"() {
        given:
        CodeWorkflow.seed(store)
        def limits = new FileDeployment(DataSize.ofBytes(8), 2, DataSize.ofBytes(8))
        def code = new ScriptedCodeStep(CodeWorkflow.CODE_STEP, [REPLY], [RECEIPT, ATTACHMENT, COPY], false,
                { new CodeStep.Output(receipt("R-1"), [
                        (ATTACHMENT_PATH): [new CodeStep.FileOutput("first.txt", "text/plain",
                                new ByteArrayInputStream("four".bytes))],
                        (COPY_PATH): [new CodeStep.FileOutput("second.txt", "text/plain",
                                new ByteArrayInputStream("more".bytes))]]) })
        holdingWithFiles(limits, code)

        when:
        started()
        until("total-bound file try ended") { ended(SEND, 1) }
        def read = reading().step(groupId(GROUP), runId(), stepId(SEND), ANN_USER)
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def firstId = store.texts("select file_id::text from stored_files where original_name = 'first.txt'")[0]
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def secondId = store.texts("select file_id::text from stored_files where original_name = 'second.txt'")[0]
        def first = read.cameOut().find { it.field() == "attachment" }.standing()
        def second = read.triesMade()[0].values().find { it.field() == "copy" }

        then:
        triesOf(SEND) == ["1 code system yielded false"]
        first.value().get("id").asString() == firstId
        first.value().get("name").asString() == "first.txt"
        first.value().get("type").asString() == "text/plain"
        first.value().get("size").asLong() == 4L
        first.earlierShape() == null
        second.value().get("id").asString() == secondId
        second.value().get("name").asString() == "second.txt"
        second.value().get("type").asString() == "text/plain"
        second.value().get("size").asLong() == 4L
        second.earlierShape() == null
        EngineWiring.files(store).open(runId(), groupId(GROUP), UUID.fromString(firstId)).content()
                .withCloseable { it.readAllBytes() } == "four".bytes
        wentWrong() == []
        store.count('select count(*) from stored_files') == 2
        store.count('select count(*) from file_contents') == 2
        store.count('select count(*) from run_file_holders') == 2
        store.count('select count(*) from errored_code_returned_files') == 0
        values().any { it.contains('receipt') && it.contains('R-1') }
    }

    def "a file bound from one code step enters the next code step as downloadable metadata"() {
        given:
        CodeWorkflow.seed(store, 1, true)
        StepRows.binding(store, "0000000d-0000-4000-8000-000000000c04", CodeWorkflow.VERSION,
                CHECK, "attachment", SEND, "attachment")
        def inputFile = new Field(new FieldName("attachment"), null, null,
                new FieldShape.File([new MediaTypeName("text/plain")], 1024L), new HowMany.One(),
                new Demand.Given(false))
        def calls = new AtomicInteger()
        def code = new ScriptedCodeStep(CodeWorkflow.CODE_STEP, [REPLY, inputFile], [RECEIPT, ATTACHMENT], false,
                { input ->
                    int number = calls.incrementAndGet()
                    new CodeStep.Output(receipt("R-${number}"),
                            [(ATTACHMENT_PATH): [new CodeStep.FileOutput("file-${number}.txt", "text/plain",
                                    new ByteArrayInputStream("four".bytes))]])
                })
        holding(code)

        when:
        started()
        until("file-bound next code try ended") { ended(CHECK, 1) }
        def read = reading().step(groupId(GROUP), runId(), stepId(CHECK), ANN_USER)
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def firstId = store.texts("select file_id::text from stored_files where original_name = 'file-1.txt'")[0]
        def bound = read.wentIn().find { it.input() == "attachment" }

        then:
        calls.get() == 2
        triesOf(SEND) == ["1 code system yielded false"]
        triesOf(CHECK) == ["1 code system yielded false"]
        bound.value().get("id").asString() == firstId
        bound.value().get("name").asString() == "file-1.txt"
        bound.value().get("type").asString() == "text/plain"
        bound.value().get("size").asLong() == 4L
        bound.earlierShape() == null
        EngineWiring.files(store).open(runId(), groupId(GROUP), UUID.fromString(firstId)).content()
                .withCloseable { it.readAllBytes() } == "four".bytes
    }

    def "old fields shaped like file metadata are kept as an earlier shape without a run holder"() {
        given:
        CodeWorkflow.seed(store)
        def fakeId = "0000000f-0000-4000-8000-000000000c44"
        def otherRun = "00000008-0000-4000-8000-000000000c44"
        store.transactions().executeWithoutResult {
            StepRows.run(store, otherRun, GROUP, 2, CodeWorkflow.WORKFLOW, CodeWorkflow.VERSION, CAT,
                    CodeWorkflow.STARTED_WITH)
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("insert into stored_files (file_id, group_id, original_name, media_type, byte_size) " +
                    "values (?::uuid, ?::uuid, ?, ?, ?)")
                    .params(fakeId, GROUP, "Demo Sample.txt", "text/plain", 4L).update()
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("insert into file_contents (file_id, content) values (?::uuid, ?)")
                    .params(fakeId, "four".bytes).update()
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("insert into run_file_holders (run_id, file_id, group_id) " +
                    "values (?::uuid, ?::uuid, ?::uuid)").params(otherRun, fakeId, GROUP).update()
        }
        def oldFileLike = new Field(new FieldName("attachment"), null, null,
                new FieldShape.Nested([
                        SpecCodeStep.given("id", new FieldShape.Text(64)),
                        SpecCodeStep.given("name", new FieldShape.Text(255)),
                        SpecCodeStep.given("type", new FieldShape.Text(127)),
                        SpecCodeStep.given("size", new FieldShape.Plain(FieldKind.NUMBER))]),
                new HowMany.One(), new Demand.Stands(true, FieldStanding.ALWAYS, null))
        def metadata = new JsonValue.JsonObject([
                new JsonValue.JsonMember("id", new JsonValue.JsonString(fakeId)),
                new JsonValue.JsonMember("name", new JsonValue.JsonString("Demo Sample.txt")),
                new JsonValue.JsonMember("type", new JsonValue.JsonString("text/plain")),
                new JsonValue.JsonMember("size", json(4))])
        def returned = new JsonValue.JsonObject([
                new JsonValue.JsonMember("receipt", new JsonValue.JsonString("R-1")),
                new JsonValue.JsonMember("attachment", metadata)])
        holding(new ScriptedCodeStep(CodeWorkflow.CODE_STEP, [REPLY], [RECEIPT, oldFileLike], false,
                { returned }))
        started()
        until("old fields code try ended") { ended(SEND, 1) }
        holding(new ScriptedCodeStep(CodeWorkflow.CODE_STEP, [REPLY], [RECEIPT, ATTACHMENT], false,
                { receipt() }))

        when:
        def read = reading().step(groupId(GROUP), runId(), stepId(SEND), ANN_USER)
        def standing = read.cameOut().find { it.field() == "attachment" }.standing()
        def tried = read.triesMade()[0].values().find { it.field() == "attachment" }

        then:
        triesOf(SEND) == ["1 code system yielded false"]
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        store.texts("select run_id::text from run_file_holders where file_id = ?::uuid", fakeId) == [otherRun]
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count("select count(*) from run_file_holders where run_id = ?::uuid", RUN) == 0
        standing.value().asString().contains(fakeId)
        standing.earlierShape() == true
        tried.value().asString() == standing.value().asString()
        tried.earlierShape() == true
    }

    def "a stored code file is kept in its earlier shape when the release changes its type size or kind"() {
        given:
        CodeWorkflow.seed(store)
        holding(new ScriptedCodeStep(CodeWorkflow.CODE_STEP, [REPLY], [RECEIPT, ATTACHMENT], false,
                { new CodeStep.Output(receipt("R-1"),
                        [(ATTACHMENT_PATH): [new CodeStep.FileOutput("Demo Sample.txt", "text/plain",
                                new ByteArrayInputStream("four".bytes))]]) }))
        started()
        until("file try ended") { ended(SEND, 1) }
        def changes = [
                new Field(new FieldName("attachment"), null, null,
                        new FieldShape.File([new MediaTypeName("image/png")], 1024L), new HowMany.One(),
                        new Demand.Stands(true, FieldStanding.ALWAYS, null)),
                new Field(new FieldName("attachment"), null, null,
                        new FieldShape.File([new MediaTypeName("text/plain")], 3L), new HowMany.One(),
                        new Demand.Stands(true, FieldStanding.ALWAYS, null)),
                SpecCodeStep.standing("attachment", new FieldShape.Text(200))]

        when:
        def views = changes.collect { changed ->
            holding(new ScriptedCodeStep(CodeWorkflow.CODE_STEP, [REPLY], [RECEIPT, changed], false,
                    { receipt() }))
            def read = reading().step(groupId(GROUP), runId(), stepId(SEND), ANN_USER)
            [read.cameOut().find { it.field() == "attachment" }.standing(),
             read.triesMade()[0].values().find { it.field() == "attachment" }]
        }

        then:
        views.size() == 3
        views.every { pair -> pair[0].earlierShape() == true && pair[1].earlierShape() == true }
        views.every { pair -> pair[0].value().isString() && pair[1].value().isString() }
        store.count('select count(*) from run_file_holders') == 1
    }

    def "a code try one byte above its total rolls back all files and keeps ordinary return"() {
        given:
        CodeWorkflow.seed(store)
        def limits = new FileDeployment(DataSize.ofBytes(8), 2, DataSize.ofBytes(8))
        def read = new AtomicInteger()
        def closed = new AtomicInteger()
        def second = new ByteArrayInputStream("fives".bytes) {
            @Override
            int read() {
                int value = super.read()
                if (value >= 0) read.incrementAndGet()
                value
            }

            @Override
            int read(byte[] bytes, int offset, int length) {
                int count = super.read(bytes, offset, length)
                if (count > 0) read.addAndGet(count)
                count
            }

            @Override
            void close() {
                closed.incrementAndGet()
                super.close()
            }
        }
        def code = new ScriptedCodeStep(CodeWorkflow.CODE_STEP, [REPLY], [RECEIPT, ATTACHMENT, COPY], false,
                { new CodeStep.Output(receipt("R-1"), [
                        (ATTACHMENT_PATH): [new CodeStep.FileOutput("first.txt", "text/plain",
                                new ByteArrayInputStream("four".bytes))],
                        (COPY_PATH): [new CodeStep.FileOutput("second.txt", "text/plain", second)]]) })
        holdingWithFiles(limits, code)

        when:
        started()
        until("over-total file try ended") { ended(SEND, 1) }

        then:
        triesOf(SEND) == ["1 code system errored false"]
        wentWrong()[0].startsWith("file_total_too_large - ")
        wentWrong()[0].contains('{"receipt":"R-1"}')
        read.get() == 5
        closed.get() == 1
        store.count('select count(*) from stored_files') == 0
        store.count('select count(*) from file_contents') == 0
        store.count('select count(*) from run_file_holders') == 0
        store.count('select count(*) from errored_code_returned_files') == 0
        values() == []
    }

    def "a code file refused for its type leaves no holder or errored association and keeps ordinary return"() {
        given:
        CodeWorkflow.seed(store)
        def code = new ScriptedCodeStep(CodeWorkflow.CODE_STEP, [REPLY], [RECEIPT, ATTACHMENT], false,
                { new CodeStep.Output(receipt("R-1"),
                        [(ATTACHMENT_PATH): [new CodeStep.FileOutput("receipt.png", "image/png",
                                new ByteArrayInputStream("bytes".getBytes("UTF-8")))]]) })
        holding(code)

        when:
        started()
        until("file refusal ended") { ended(SEND, 1) }

        then:
        wentWrong()[0].startsWith("file_type_unusable attachment ")
        wentWrong()[0].contains('{"receipt":"R-1"}')
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select file_id::text from stored_files") == []
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select file_id::text from run_file_holders") == []
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select file_id::text from errored_code_returned_files") == []
    }

    def "a refused code file still reports that refusal when its stream close fails"() {
        given:
        CodeWorkflow.seed(store)
        def closed = new AtomicInteger()
        def stream = new ByteArrayInputStream("bytes".getBytes("UTF-8")) {
            @Override
            void close() throws IOException {
                closed.incrementAndGet()
                throw new IOException("Demo Sample close failed")
            }
        }
        holding(new ScriptedCodeStep(CodeWorkflow.CODE_STEP, [REPLY], [RECEIPT, ATTACHMENT], false,
                { new CodeStep.Output(receipt("R-1"),
                        [(ATTACHMENT_PATH): [new CodeStep.FileOutput("receipt.png", "image/png", stream)]]) }))

        when:
        started()
        until("refused file and close failure try ended") { ended(SEND, 1) }

        then:
        wentWrong()[0].startsWith("file_type_unusable attachment ")
        closed.get() == 1
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select file_id::text from stored_files") == []
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select file_id::text from run_file_holders") == []
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select file_id::text from errored_code_returned_files") == []
    }

    def "code output close failure rolls back its file and ends the try on this side"() {
        given:
        CodeWorkflow.seed(store)
        def closed = new AtomicInteger()
        def stream = new ByteArrayInputStream("bytes".getBytes("UTF-8")) {
            @Override
            void close() throws IOException {
                closed.incrementAndGet()
                throw new IOException("Demo Sample close failed")
            }
        }
        holding(new ScriptedCodeStep(CodeWorkflow.CODE_STEP, [REPLY], [RECEIPT, ATTACHMENT], false,
                { new CodeStep.Output(receipt("R-1"),
                        [(ATTACHMENT_PATH): [new CodeStep.FileOutput("receipt.txt", "text/plain", stream)]]) }))

        when:
        started()
        until("stream close failure try ended") { ended(SEND, 1) }

        then:
        wentWrong()[0].startsWith("failed_on_this_side ")
        closed.get() == 1
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select file_id::text from stored_files") == []
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select file_id::text from run_file_holders") == []
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select file_id::text from errored_code_returned_files") == []
    }

    def "an id returned as raw code JSON never becomes a held or downloadable file"() {
        given:
        CodeWorkflow.seed(store)
        def fake = UUID.randomUUID().toString()
        def returned = new JsonValue.JsonObject([
                new JsonValue.JsonMember("receipt", new JsonValue.JsonString("R-1")),
                new JsonValue.JsonMember("attachment", new JsonValue.JsonObject([
                        new JsonValue.JsonMember("id", new JsonValue.JsonString(fake)),
                        new JsonValue.JsonMember("name", new JsonValue.JsonString("fake.txt")),
                        new JsonValue.JsonMember("type", new JsonValue.JsonString("text/plain")),
                        new JsonValue.JsonMember("size", new JsonValue.JsonNumber(1G))]))])
        holding(new ScriptedCodeStep(CodeWorkflow.CODE_STEP, [REPLY], [RECEIPT, ATTACHMENT], false,
                { returned }))

        when:
        started()
        until("raw id try ended") { ended(SEND, 1) }
        def reading = new RunSteps(store.session, new GroupRoles(store.session), snapshots,
                store.transactionManager(), Deployments.DEEP_ENOUGH, new org.lilradish.lite.app.currency.GroupCurrencies(store.session, new org.springframework.transaction.support.TransactionTemplate(store.transactionManager()), new GroupRoles(store.session), org.lilradish.lite.testutil.library.DeployedModels.HELD))
                .step(groupId(GROUP), runId(), stepId(SEND), ANN_USER).triesMade()[0]

        then:
        wentWrong()[0].startsWith("not_its_kind attachment ")
        reading.keptFiles() == null
        reading.returned().contains(fake)
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select file_id::text from run_file_holders") == []
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select file_id::text from errored_code_returned_files") == []
    }

    def "a required code file missing from a file return errs without writing a holder"() {
        given:
        CodeWorkflow.seed(store)
        holding(new ScriptedCodeStep(CodeWorkflow.CODE_STEP, [REPLY], [RECEIPT, ATTACHMENT], false,
                { new CodeStep.Output(receipt("R-1"), [(ATTACHMENT_PATH): []]) }))

        when:
        started()
        until("missing file try ended") { ended(SEND, 1) }

        then:
        wentWrong()[0].startsWith("nothing_given attachment ")
        wentWrong()[0].contains('{"receipt":"R-1"}')
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select file_id::text from run_file_holders") == []
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select file_id::text from errored_code_returned_files") == []
    }

    def "too many files in one code field are rejected before reading streams and all are closed"() {
        given:
        CodeWorkflow.seed(store)
        def many = new Field(new FieldName("attachment"), null, null,
                new FieldShape.File([new MediaTypeName("text/plain")], 1024L), new HowMany.Many(2),
                new Demand.Stands(true, FieldStanding.ALWAYS, null))
        def closed = new AtomicInteger()
        def files = (0..<3).collect { at ->
            new CodeStep.FileOutput("part-${at}.txt", "text/plain", new ByteArrayInputStream("part".bytes) {
                @Override
                void close() {
                    closed.incrementAndGet()
                    super.close()
                }
            })
        }
        holding(new ScriptedCodeStep(CodeWorkflow.CODE_STEP, [REPLY], [RECEIPT, many], false,
                { new CodeStep.Output(receipt("R-1"), [(ATTACHMENT_PATH): files]) }))

        when:
        started()
        until("too many files try ended") { ended(SEND, 1) }

        then:
        wentWrong()[0].startsWith("too_many attachment ")
        closed.get() == 3
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select file_id::text from stored_files") == []
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select file_id::text from run_file_holders") == []
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select file_id::text from errored_code_returned_files") == []
    }

    def "twelve nested code files kept with an errored try read in numeric position order"() {
        given:
        CodeWorkflow.seed(store)
        def innerFile = new Field(new FieldName("attachment"), null, null,
                new FieldShape.File([new MediaTypeName("text/plain")], 1024L), new HowMany.One(),
                new Demand.Given(true))
        def folder = new Field(new FieldName("folders"), null, null, new FieldShape.Nested([innerFile]),
                new HowMany.Many(12), new Demand.Stands(true, FieldStanding.ALWAYS, null))
        def slots = new JsonValue.JsonArray((0..<12).collect { new JsonValue.JsonObject([]) })
        def raw = new JsonValue.JsonObject([
                new JsonValue.JsonMember("receipt", new JsonValue.JsonString("x" * 65)),
                new JsonValue.JsonMember("folders", slots)])
        def returnedFiles = (0..<12).collectEntries { index ->
            def path = new FillPath([new FillPath.Named(new FieldName("folders")),
                    new FillPath.Place(index), new FillPath.Named(new FieldName("attachment"))])
            [(path): [new CodeStep.FileOutput("item-${index}.txt", "text/plain",
                    new ByteArrayInputStream("item-${index}".getBytes("UTF-8")))]]
        }
        holding(new ScriptedCodeStep(CodeWorkflow.CODE_STEP, [REPLY], [RECEIPT, folder], false,
                { new CodeStep.Output(raw, returnedFiles) }))

        when:
        started()
        until("twelve nested files try ended") { ended(SEND, 1) }
        def reading = new RunSteps(store.session, new GroupRoles(store.session), snapshots,
                store.transactionManager(), Deployments.DEEP_ENOUGH, new org.lilradish.lite.app.currency.GroupCurrencies(store.session, new org.springframework.transaction.support.TransactionTemplate(store.transactionManager()), new GroupRoles(store.session), org.lilradish.lite.testutil.library.DeployedModels.HELD))
                .step(groupId(GROUP), runId(), stepId(SEND), ANN_USER).triesMade()[0]

        then:
        wentWrong()[0].startsWith("too_long receipt ")
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        store.texts("select file_id::text from run_file_holders where run_id = ?::uuid", RUN).size() == 12
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select file_id::text from errored_code_returned_files").size() == 12
        reading.keptFiles()*.path() == (0..<12).collect { "folders[${it}].attachment" }
        reading.keptFiles()*.name() == (0..<12).collect { "item-${it}.txt" }
    }

    def "a required empty nested list errs for its field while a separate valid code file is kept"() {
        given:
        CodeWorkflow.seed(store)
        def inner = new Field(new FieldName("copy"), null, null,
                new FieldShape.File([new MediaTypeName("text/plain")], 1024L), new HowMany.One(),
                new Demand.Given(true))
        def folder = new Field(new FieldName("folders"), null, null, new FieldShape.Nested([inner]),
                new HowMany.Many(2), new Demand.Stands(true, FieldStanding.ALWAYS, null))
        def raw = new JsonValue.JsonObject([
                new JsonValue.JsonMember("receipt", new JsonValue.JsonString("R-1")),
                new JsonValue.JsonMember("folders", new JsonValue.JsonArray([]))])
        holding(new ScriptedCodeStep(CodeWorkflow.CODE_STEP, [REPLY], [RECEIPT, ATTACHMENT, folder], false,
                { new CodeStep.Output(raw, [(ATTACHMENT_PATH): [new CodeStep.FileOutput("valid.txt", "text/plain",
                        new ByteArrayInputStream("bytes".bytes))]]) }))

        when:
        started()
        until("nested missing result ended") { ended(SEND, 1) }

        then:
        wentWrong()[0].startsWith("nothing_given folders ")
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def ids = store.texts("select file_id::text from errored_code_returned_files")
        ids.size() == 1
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        store.texts("select file_id::text from run_file_holders where run_id = ?::uuid", RUN) == ids
        EngineWiring.files(store).open(runId(), groupId(GROUP), UUID.fromString(ids[0])).content()
                .withCloseable { it.readAllBytes() } == "bytes".bytes
    }

    def "a valid nested ordinary value beside an optional file does not reject a separate file"() {
        given:
        CodeWorkflow.seed(store)
        def innerFile = new Field(new FieldName("copy"), null, null,
                new FieldShape.File([new MediaTypeName("text/plain")], 1024L), new HowMany.One(),
                new Demand.Given(false))
        def description = new Field(new FieldName("description"), null, null,
                new FieldShape.Text(32), new HowMany.One(), new Demand.Given(true))
        def folder = new Field(new FieldName("folder"), null, null,
                new FieldShape.Nested([innerFile, description]), new HowMany.One(),
                new Demand.Stands(true, FieldStanding.ALWAYS, null))
        def raw = new JsonValue.JsonObject([
                new JsonValue.JsonMember("receipt", new JsonValue.JsonString("R-1")),
                new JsonValue.JsonMember("folder", new JsonValue.JsonObject([
                        new JsonValue.JsonMember("description", new JsonValue.JsonString("sound"))]))])
        holding(new ScriptedCodeStep(CodeWorkflow.CODE_STEP, [REPLY], [RECEIPT, ATTACHMENT, folder], false,
                { new CodeStep.Output(raw, [(ATTACHMENT_PATH): [new CodeStep.FileOutput("valid.txt", "text/plain",
                        new ByteArrayInputStream("bytes".bytes))]]) }))

        when:
        started()
        until("valid nested result ended") { ended(SEND, 1) }

        then:
        triesOf(SEND) == ["1 code system yielded false"]
        wentWrong() == []
        // DB-SPECIFIC: PostgreSQL JSON/JSONB ->>.
        store.texts("select value ->> 'description' from production_values where field_name = 'folder'") == ["sound"]
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        def ids = store.texts("select file_id::text from run_file_holders where run_id = ?::uuid", RUN)
        ids.size() == 1
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select file_id::text from errored_code_returned_files") == []
        EngineWiring.files(store).open(runId(), groupId(GROUP), UUID.fromString(ids[0])).content()
                .withCloseable { it.readAllBytes() } == "bytes".bytes
    }

    def "an empty nested item with only optional files does not reject a separate file"() {
        given:
        CodeWorkflow.seed(store)
        def copy = new Field(new FieldName("copy"), null, null,
                new FieldShape.File([new MediaTypeName("text/plain")], 1024L), new HowMany.One(),
                new Demand.Given(false))
        def folders = new Field(new FieldName("folders"), null, null, new FieldShape.Nested([copy]),
                new HowMany.Many(2), new Demand.Stands(true, FieldStanding.ALWAYS, null))
        def raw = new JsonValue.JsonObject([
                new JsonValue.JsonMember("receipt", new JsonValue.JsonString("R-1")),
                new JsonValue.JsonMember("folders", new JsonValue.JsonArray([new JsonValue.JsonObject([])]))])
        holding(new ScriptedCodeStep(CodeWorkflow.CODE_STEP, [REPLY], [RECEIPT, ATTACHMENT, folders], false,
                { new CodeStep.Output(raw, [(ATTACHMENT_PATH): [new CodeStep.FileOutput("valid.txt", "text/plain",
                        new ByteArrayInputStream("bytes".bytes))]]) }))

        when:
        started()
        until("empty nested item try ended") { ended(SEND, 1) }
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        def ids = store.texts("select file_id::text from run_file_holders where run_id = ?::uuid", RUN)

        then:
        triesOf(SEND) == ["1 code system yielded false"]
        wentWrong() == []
        ids.size() == 1
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select file_id::text from errored_code_returned_files") == []
        EngineWiring.files(store).open(runId(), groupId(GROUP), UUID.fromString(ids[0])).content()
                .withCloseable { it.readAllBytes() } == "bytes".bytes
    }

    def "nested ordinary text too long does not discard a separate valid file"() {
        given:
        CodeWorkflow.seed(store)
        def copy = new Field(new FieldName("copy"), null, null,
                new FieldShape.File([new MediaTypeName("text/plain")], 1024L), new HowMany.One(),
                new Demand.Given(false))
        def description = new Field(new FieldName("description"), null, null,
                new FieldShape.Text(3), new HowMany.One(), new Demand.Given(true))
        def folder = new Field(new FieldName("folder"), null, null,
                new FieldShape.Nested([copy, description]), new HowMany.One(),
                new Demand.Stands(true, FieldStanding.ALWAYS, null))
        def raw = new JsonValue.JsonObject([
                new JsonValue.JsonMember("receipt", new JsonValue.JsonString("R-1")),
                new JsonValue.JsonMember("folder", new JsonValue.JsonObject([
                        new JsonValue.JsonMember("description", new JsonValue.JsonString("long"))]))])
        holding(new ScriptedCodeStep(CodeWorkflow.CODE_STEP, [REPLY], [RECEIPT, ATTACHMENT, folder], false,
                { new CodeStep.Output(raw, [(ATTACHMENT_PATH): [new CodeStep.FileOutput("valid.txt", "text/plain",
                        new ByteArrayInputStream("bytes".bytes))]]) }))

        when:
        started()
        until("nested text error try ended") { ended(SEND, 1) }
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def ids = store.texts("select file_id::text from errored_code_returned_files")

        then:
        wentWrong()[0].startsWith("too_long folder.description ")
        ids.size() == 1
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        store.texts("select file_id::text from run_file_holders where run_id = ?::uuid", RUN) == ids
        EngineWiring.files(store).open(runId(), groupId(GROUP), UUID.fromString(ids[0])).content()
                .withCloseable { it.readAllBytes() } == "bytes".bytes
    }


    def "too many nested objects do not discard a separate valid code file"() {
        given:
        CodeWorkflow.seed(store)
        def copy = new Field(new FieldName("copy"), null, null,
                new FieldShape.File([new MediaTypeName("text/plain")], 1024L), new HowMany.One(),
                new Demand.Given(false))
        def folders = new Field(new FieldName("folders"), null, null, new FieldShape.Nested([copy]),
                new HowMany.Many(2), new Demand.Stands(true, FieldStanding.ALWAYS, null))
        def raw = new JsonValue.JsonObject([
                new JsonValue.JsonMember("receipt", new JsonValue.JsonString("R-1")),
                new JsonValue.JsonMember("folders", new JsonValue.JsonArray((0..<3).collect {
                    new JsonValue.JsonObject([])
                }))])
        holding(new ScriptedCodeStep(CodeWorkflow.CODE_STEP, [REPLY], [RECEIPT, ATTACHMENT, folders], false,
                { new CodeStep.Output(raw, [(ATTACHMENT_PATH): [new CodeStep.FileOutput("valid.txt", "text/plain",
                        new ByteArrayInputStream("bytes".bytes))]]) }))

        when:
        started()
        until("too many nested objects try ended") { ended(SEND, 1) }
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def ids = store.texts("select file_id::text from errored_code_returned_files")

        then:
        wentWrong()[0].startsWith("too_many folders ")
        ids.size() == 1
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        store.texts("select file_id::text from run_file_holders where run_id = ?::uuid", RUN) == ids
        EngineWiring.files(store).open(runId(), groupId(GROUP), UUID.fromString(ids[0])).content()
                .withCloseable { it.readAllBytes() } == "bytes".bytes
    }

    def "a malformed nested ordinary container does not discard a separate valid code file"() {
        given:
        CodeWorkflow.seed(store)
        def copy = new Field(new FieldName("copy"), null, null,
                new FieldShape.File([new MediaTypeName("text/plain")], 1024L), new HowMany.One(),
                new Demand.Given(true))
        def folders = new Field(new FieldName("folders"), null, null, new FieldShape.Nested([copy]),
                new HowMany.Many(2), new Demand.Stands(true, FieldStanding.ALWAYS, null))
        def raw = new JsonValue.JsonObject([
                new JsonValue.JsonMember("receipt", new JsonValue.JsonString("R-1")),
                new JsonValue.JsonMember("folders", nested)])
        holding(new ScriptedCodeStep(CodeWorkflow.CODE_STEP, [REPLY], [RECEIPT, ATTACHMENT, folders], false,
                { new CodeStep.Output(raw, [(ATTACHMENT_PATH): [new CodeStep.FileOutput("valid.txt", "text/plain",
                        new ByteArrayInputStream("bytes".bytes))]]) }))

        when:
        started()
        until("malformed nested container try ended") { ended(SEND, 1) }
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def ids = store.texts("select file_id::text from errored_code_returned_files")

        then:
        wentWrong()[0].startsWith("not_its_kind folders ")
        ids.size() == 1
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        store.texts("select file_id::text from run_file_holders where run_id = ?::uuid", RUN) == ids
        EngineWiring.files(store).open(runId(), groupId(GROUP), UUID.fromString(ids[0])).content()
                .withCloseable { it.readAllBytes() } == "bytes".bytes

        where:
        nested << [new JsonValue.JsonString("wrong"),
                   new JsonValue.JsonArray([new JsonValue.JsonString("wrong")])]
    }

    def "a malformed outer list item with a nested list keeps a separate valid code file"() {
        given:
        CodeWorkflow.seed(store)
        def copy = new Field(new FieldName("copy"), null, null,
                new FieldShape.File([new MediaTypeName("text/plain")], 1024L), new HowMany.One(),
                new Demand.Given(false))
        def children = new Field(new FieldName("children"), null, null,
                new FieldShape.Nested([copy]), new HowMany.Many(2), new Demand.Given(false))
        def folders = new Field(new FieldName("folders"), null, null,
                new FieldShape.Nested([children]), new HowMany.Many(2),
                new Demand.Stands(true, FieldStanding.ALWAYS, null))
        def raw = new JsonValue.JsonObject([
                new JsonValue.JsonMember("receipt", new JsonValue.JsonString("R-1")),
                new JsonValue.JsonMember("folders", new JsonValue.JsonArray([
                        new JsonValue.JsonString("wrong")]))])
        holding(new ScriptedCodeStep(CodeWorkflow.CODE_STEP, [REPLY], [RECEIPT, ATTACHMENT, folders], false,
                { new CodeStep.Output(raw, [(ATTACHMENT_PATH): [new CodeStep.FileOutput("valid.txt", "text/plain",
                        new ByteArrayInputStream("bytes".bytes))]]) }))

        when:
        started()
        until("malformed deeper nested container try ended") { ended(SEND, 1) }
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def ids = store.texts("select file_id::text from errored_code_returned_files")

        then:
        wentWrong()[0].startsWith("not_its_kind folders ")
        ids.size() == 1
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        store.texts("select file_id::text from run_file_holders where run_id = ?::uuid", RUN) == ids
        EngineWiring.files(store).open(runId(), groupId(GROUP), UUID.fromString(ids[0])).content()
                .withCloseable { it.readAllBytes() } == "bytes".bytes
    }

}
