package org.lilradish.lite.app.run

import static org.lilradish.lite.app.run.fixture.TicketWorkflow.ANN
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.ANN_USER
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.APPROVED
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.CAT
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.CAT_USER
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.CONFIRM
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.CONFIRM_QUESTION
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.CONFIRM_VERSION
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.DAN_USER
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.GROUP
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.NOTE
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.RUN
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARISE
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARISE_VERSION
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARY
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.VERSION
import static org.lilradish.lite.testutil.library.LibraryStore.FIRST_STEWARD
import static org.lilradish.lite.testutil.library.LibraryStore.groupId
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.io.ByteArrayInputStream
import java.time.OffsetDateTime
import org.libprunus.core.error.ApiErrorException
import org.lilradish.lite.app.codestep.CodeRuns
import org.lilradish.lite.app.files.FileFillings
import org.lilradish.lite.app.filling.FillFieldAnswer
import org.lilradish.lite.app.group.GroupReach
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.pool.PersonAnswer
import org.lilradish.lite.app.run.fixture.CodeWorkflow
import org.lilradish.lite.app.run.fixture.StepRows
import org.lilradish.lite.app.run.fixture.TicketWorkflow
import org.lilradish.lite.app.search.SearchWays
import org.lilradish.lite.domain.declaration.Field
import org.lilradish.lite.domain.declaration.FieldKind
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.declaration.FieldShape
import org.lilradish.lite.domain.declaration.HowMany
import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.file.MediaTypeName
import org.lilradish.lite.domain.file.StoredFile
import org.lilradish.lite.domain.filling.FillField
import org.lilradish.lite.domain.filling.FillPath
import org.lilradish.lite.domain.identity.GroupPermission
import org.lilradish.lite.domain.identity.GroupRole
import org.lilradish.lite.domain.run.ProductionId
import org.lilradish.lite.domain.run.ReviewOutcome
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.domain.run.RunStepHoldReason
import org.lilradish.lite.domain.run.StepInputs
import org.lilradish.lite.domain.run.WorkflowStepId
import org.lilradish.lite.domain.wire.CanonicalJson
import org.lilradish.lite.domain.wire.JsonValue
import org.lilradish.lite.domain.wire.StoreLabels
import org.lilradish.lite.testutil.CountingDataSource
import org.lilradish.lite.testutil.codestep.CodeStepsHeld
import org.lilradish.lite.testutil.codestep.SpecCodeStep
import org.lilradish.lite.testutil.inference.ScriptedModelCalls
import org.lilradish.lite.testutil.library.DeployedModels
import org.lilradish.lite.testutil.library.LibraryStore
import org.lilradish.lite.testutil.run.RunRows
import org.lilradish.lite.web.ActAdmission
import org.lilradish.lite.web.CallerAdmission
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import spock.mock.MockMakers
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.JsonNodeFactory

class RunStepsIntegrationSpec extends Specification {

    static final JsonMapper JSON = JsonMapper.builder().build()

    static final JsonNodeFactory NODES = JsonNodeFactory.instance

    static final PersonAnswer CAT_ANSWER = new PersonAnswer("000e03", "Cat")

    static final PersonAnswer ANN_ANSWER = new PersonAnswer("000e01", "Ann")

    static final PersonAnswer STEWARD_ANSWER = new PersonAnswer("000001", null)

    static final String ANNS_RUN = "00000008-0000-4000-8000-000000000e02"

    static final int READ_IN = 27

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    RunTree tree

    EngineExecutor executor

    RunEngine engine

    StepActs acts

    RunSteps steps

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, "run_steps_" + (++databasesMade))
        def session = store.session
        tree = new RunTree(session)
        def snapshots = new RunSnapshots(session, CodeStepsHeld.NONE, new SearchWays([]))
        executor = EngineExecutors.of(store.database)
        def writes = new EngineWrites(session)
        def ceilings = new CeilingReach(session, DeployedModels.HELD, writes)
        def heldFiles = EngineWiring.files(store)
        engine = new RunEngine(session, store.transactions(), tree, snapshots, writes, executor,
                new EngineCalls(EngineWiring.withoutTools(), new ScriptedModelCalls(), store.transactions(), tree, writes, ceilings, snapshots, heldFiles),
                new EngineCodes(new CodeRuns(CodeStepsHeld.NONE), store.transactions(), tree, snapshots, writes, executor, heldFiles),
                DeployedModels.HELD, ceilings, new GroupRoles(session), Deployments.DEEP_ENOUGH, heldFiles)
        acts = new StepActs(session, store.transactions(), new GroupRoles(session), tree, snapshots, writes, engine,
                heldFiles, EngineWiring.fileDeployment())
        steps = new RunSteps(session, new GroupRoles(session), snapshots, store.transactionManager(), Deployments.DEEP_ENOUGH, new org.lilradish.lite.app.currency.GroupCurrencies(session, new org.springframework.transaction.support.TransactionTemplate(store.transactionManager()), new GroupRoles(session), org.lilradish.lite.testutil.library.DeployedModels.HELD))
        TicketWorkflow.seed(store)
        store.transactions().executeWithoutResult {
            TicketWorkflow.run(store)
            engine.planStarted(tree.lock(groupId(GROUP), runId()).orElseThrow())
        }
    }

    def cleanup() {
        executor.destroy()
    }

    private static RunId runId() {
        new RunId(UUID.fromString(RUN))
    }

    private static WorkflowStepId stepId(String spelled) {
        new WorkflowStepId(UUID.fromString(spelled))
    }

    private void answeredByCat() {
        StepRows.answered(store, SUMMARISE, CAT, "Read it twice.", [(SUMMARY): '"A printer fire."'])
    }

    private void reviewed(ReviewOutcome outcome) {
        acts.review(groupId(GROUP), runId(), stepId(SUMMARISE), 1, ANN_USER,
                [summary: new StepActs.ReviewedRecord(outcome, outcome == ReviewOutcome.REFUSED ? "Too short." : null)])
    }

    private List<String> declaredNames(StepAnswers.DeclaredAnswer declared) {
        declared.takes()*.name() + declared.gives()*.name()
    }

    def "reads every step in order: the first asked of whoever answers it, the next not reached"() {
        when:
        def read = steps.steps(groupId(GROUP), runId(), CAT_USER)

        then: "the run as the same read has it, on its first step, with nothing yet given back"
        read.run().state() == "running"
        read.run().at() == new RunsController.AtAnswer(UUID.fromString(SUMMARISE), "summarise")
        read.run().acts() == ["stop", "rename", "change_ceiling"]
        read.run().progress() == new StepAnswers.ProgressAnswer(0, 2)
        read.gaveBack() == new StepAnswers.GaveBackAnswer("values", [])
        read.rereadAfterSeconds() == RunSteps.REREAD_AFTER_SECONDS

        and: "each version named declared once: the run's own, and each question a step pins"
        read.declarations().keySet() == [VERSION, SUMMARISE_VERSION, CONFIRM_VERSION] as Set
        declaredNames(read.declarations()[VERSION]) == ["ticket", "result"]
        declaredNames(read.declarations()[CONFIRM_VERSION]) == ["summary", "approved", "note"]

        and: "summarise waiting on an answer Cat may give, its try the first of two"
        with(read.steps()[0]) {
            order() == 1
            name() == "summarise"
            runs() == new StepAnswers.RunsAnswer("question", UUID.fromString(TicketWorkflow.SUMMARISE_QUESTION),
                    "Summarise", 1, UUID.fromString(SUMMARISE_VERSION), null, null, null)
            producer() == new StepAnswers.WhoAnswer("person", null, null, null)
            reviewer() == new StepAnswers.WhoAnswer("person", null, null, null)
            state() == "waiting"
            where().kind() == "owed_try"
            where().open()
            !where().beyond()
            where().waitsOn() == "answer_step"
            where().stopped() == null
            takesFrom() == [new StepAnswers.TakesFromAnswer("text",
                    new StepAnswers.FromAnswer("run_input", "ticket", null, null, null, null, null, null, null), null)]
            tries() == new StepAnswers.TriesAnswer(1, 2, false)
            cost() == new StepAnswers.CostAnswer(false, null, null, null, null, null)
            gaveBack() == null
            next() == new StepAnswers.NextAnswer(1, false)
            acts() == ["answer"]
            withheld() == []
            wentIn() == null
        }

        and: "confirm not reached, reading from summarise by the version it pins, reviewed by nobody as all it gives stands"
        with(read.steps()[1]) {
            state() == "not_started"
            where() == null
            producer() == new StepAnswers.WhoAnswer("person", null, null, null)
            reviewer() == null
            takesFrom() == [new StepAnswers.TakesFromAnswer("summary",
                    new StepAnswers.FromAnswer("step", "summary", UUID.fromString(SUMMARISE), "summarise",
                            UUID.fromString(SUMMARISE_VERSION), null, null, null, null), null)]
            tries() == new StepAnswers.TriesAnswer(0, 1, false)
            next() == null
            acts() == []
            withheld() == []
        }
    }

    def "a value waiting on this reader's review is drawn with what went in, and offered to review"() {
        given:
        answeredByCat()

        when:
        def read = steps.steps(groupId(GROUP), runId(), ANN_USER)

        then:
        with(read.steps()[0]) {
            state() == "waiting"
            where().kind() == "waiting_on_review"
            where().number() == 1
            where().values() == [new StepAnswers.WaitingValueAnswer("summary", "review_at_gate")]
            gaveBack() == [new StepAnswers.ShownValueAnswer("summary", NODES.stringNode("A printer fire."), null,
                    "waiting_on_review", null)]
            acts() == ["review"]
            wentIn() == [new StepAnswers.WentInAnswer("text",
                    new StepAnswers.FromAnswer("run_input", "ticket", null, null, null, null, UUID.fromString(RUN), 1, null),
                    NODES.stringNode("The printer is on fire."), null, null, null, null)]
            wentInFrom() == "try"
        }

        and: "no answer asked of it any longer, nor any act withheld"
        read.steps()[0].next() == null
        read.steps()[0].withheld() == []
    }

    def "a reader who may not review sees the value waiting, not what went in, and is told why they may not"() {
        given:
        answeredByCat()

        when:
        def read = steps.steps(groupId(GROUP), runId(), CAT_USER)

        then:
        read.steps()[0].acts() == []
        read.steps()[0].withheld() == [new StepAnswers.WithheldAnswer("review", "ACT_NOT_PERMITTED", null, null)]
        read.steps()[0].wentIn() == null
    }

    def "whoever produced the values waiting is offered no review of them, and told it is their own"() {
        given:
        StepRows.answered(store, SUMMARISE, ANN, "Ann's own.", [(SUMMARY): '"A printer fire."'])

        when:
        def read = steps.steps(groupId(GROUP), runId(), ANN_USER)

        then:
        read.steps()[0].acts() == []
        read.steps()[0].withheld() == [new StepAnswers.WithheldAnswer("review", "REVIEW_OWN_PRODUCTION", null, null)]
        read.steps()[0].wentIn() == null
    }

    def "a stopped run offers no act on any step, says why, and is not read again by itself"() {
        given:
        answeredByCat()
        RunRows.stopped(store, RUN, CAT)

        when:
        def read = steps.steps(groupId(GROUP), runId(), ANN_USER)

        then:
        read.run().state() == "stopped"
        read.run().at() == null
        read.rereadAfterSeconds() == null
        read.steps()[0].acts() == []
        read.steps()[0].withheld() == [new StepAnswers.WithheldAnswer("review", "RUN_STOPPED", null, null)]
    }

    def "a run done gives back what stands, is on no step, and offers no stop"() {
        given:
        answeredByCat()
        reviewed(ReviewOutcome.ASSURED)
        StepRows.answered(store, CONFIRM, CAT, "Checked.", [(APPROVED): 'true', (NOTE): null])

        when:
        def read = steps.steps(groupId(GROUP), runId(), CAT_USER)

        then:
        read.run().state() == "done"
        read.run().at() == null
        read.run().progress() == new StepAnswers.ProgressAnswer(2, 2)
        read.gaveBack() == new StepAnswers.GaveBackAnswer("values",
                [new StepAnswers.GivenValueAnswer("result", NODES.stringNode("A printer fire."), null)])
        read.steps()*.state() == ["done", "done"]

        and: "a value of yes or no written as its text, none written as null"
        read.steps()[1].gaveBack() == [
                new StepAnswers.ShownValueAnswer("approved", NODES.stringNode("true"), null, "stands", null),
                new StepAnswers.ShownValueAnswer("note", NODES.nullNode(), null, "stands", null)]

        and: "nothing asked of anybody, and the run not read again by itself"
        !read.run().acts().contains("stop")
        read.steps().every { it.acts().isEmpty() && it.withheld().isEmpty() }
        read.rereadAfterSeconds() == null
    }

    def "a run the reader may not read is refused as none, and somebody holding nothing as no group"() {
        given:
        StepRows.run(store, ANNS_RUN, GROUP, 2, TicketWorkflow.WORKFLOW, VERSION, ANN, TicketWorkflow.STARTED_WITH)

        when:
        steps.steps(groupId(GROUP), new RunId(UUID.fromString(run)), reader)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == refusal

        where:
        run      | reader   || refusal
        ANNS_RUN | CAT_USER || RefusalCode.RUN_NOT_IN_VIEW
        RUN      | DAN_USER || RefusalCode.GROUP_NOT_IN_VIEW
    }

    def "a step's page holds every try oldest first, how each ended and why, and what the newest took"() {
        given:
        answeredByCat()
        reviewed(ReviewOutcome.REFUSED)
        acts.askAgain(groupId(GROUP), runId(), stepId(SUMMARISE), 2, CAT_USER)
        StepRows.answered(store, SUMMARISE, CAT, "Longer now.", [(SUMMARY): '"A printer is on fire in the hall."'])

        when:
        def read = steps.step(groupId(GROUP), runId(), stepId(SUMMARISE), ANN_USER)

        then: "the first try: produced and refused, with the words it was refused with"
        with(read.triesMade()[0]) {
            number() == 1
            !beyond()
            askedBy() == null
            producedBy() == new StepAnswers.WhoAnswer("person", CAT_ANSWER, null, null)
            why() == "Read it twice."
            values() == [new StepAnswers.TriedValueAnswer("summary", NODES.stringNode("A printer fire."), null, "refused",
                    null, new StepAnswers.DecisionAnswer("refused", "Too short.", null), null)]
            review() == new StepAnswers.ReviewAnswer(true, new StepAnswers.WhoAnswer("person", ANN_ANSWER, null, null), null,
                    null, null, null, null)
            ended() == "refused_on_review"
            wentWrong() == null
        }

        and: "the second: asked again by Cat, answered, and waiting on a review nobody has given"
        with(read.triesMade()[1]) {
            number() == 2
            askedBy() == CAT_ANSWER
            values()*.now() == ["waiting_on_review"]
            review() == new StepAnswers.ReviewAnswer(true, null, null, null, null, null, null)
            ended() == "waiting"
        }
        read.triesMade().size() == 2

        and: "what came out is the newest try's, standing nowhere yet"
        read.cameOut() == [new StepAnswers.CameOutAnswer("summary", null, "waiting_on_review", null, null, null)]

        and: "what went in is what the newest try took"
        read.wentInFrom() == "try"
        read.wentIn()*.value() == [NODES.stringNode("The printer is on fire.")]

        and: "a review offered, no answer asked for"
        read.step().acts() == ["review"]
        read.answering() == null
        read.run().state() == "running"

        and: "read again as the run's steps are, the run running"
        read.rereadAfterSeconds() == RunSteps.REREAD_AFTER_SECONDS
    }

    def "a question step reads only its pinned version's drawing, once, without adding it to the steps list"() {
        given:
        def newer = UUID.randomUUID().toString()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("update question_versions set drawing = ? where entry_version_id = ?::uuid")
                .params("# Pinned {{text}}", SUMMARISE_VERSION).update()
        store.seeded(newer, TicketWorkflow.SUMMARISE_QUESTION, 2)
        store.content(newer, "question", "A newer instruction.")
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("update question_versions set drawing = ? where entry_version_id = ?::uuid")
                .params("# Newer {{text}}", newer).update()
        def statements = []
        def counting = new CountingDataSource(store.database, statements)
        def session = JdbcClient.create(counting)
        def counted = new RunSteps(session, new GroupRoles(session), new RunSnapshots(session, CodeStepsHeld.NONE, new SearchWays([])),
                store.transactionManager(counting), Deployments.DEEP_ENOUGH, new org.lilradish.lite.app.currency.GroupCurrencies(session, new org.springframework.transaction.support.TransactionTemplate(store.transactionManager(counting)), new GroupRoles(session), org.lilradish.lite.testutil.library.DeployedModels.HELD))

        when:
        def read = counted.step(groupId(GROUP), runId(), stepId(SUMMARISE), ANN_USER)
        def detailQueries = statements.findAll { it.contains("select drawing from question_versions") }
        statements.clear()
        def listed = counted.steps(groupId(GROUP), runId(), ANN_USER)

        then:
        read.drawing() == "# Pinned {{text}}"
        read.step().runs().versionId() == UUID.fromString(SUMMARISE_VERSION)
        detailQueries.size() == 1
        detailQueries[0].contains("entry_version_id = ?")
        listed.steps()[0].runs().versionId() == UUID.fromString(SUMMARISE_VERSION)
        !statements.any { it.contains("select drawing from question_versions") }
    }

    def "a question without a drawing still reads its pinned version but returns no drawing"() {
        given:
        def statements = []
        def counting = new CountingDataSource(store.database, statements)
        def session = JdbcClient.create(counting)
        def counted = new RunSteps(session, new GroupRoles(session), new RunSnapshots(session, CodeStepsHeld.NONE, new SearchWays([])),
                store.transactionManager(counting), Deployments.DEEP_ENOUGH, new org.lilradish.lite.app.currency.GroupCurrencies(session, new org.springframework.transaction.support.TransactionTemplate(store.transactionManager(counting)), new GroupRoles(session), org.lilradish.lite.testutil.library.DeployedModels.HELD))

        when:
        def read = counted.step(groupId(GROUP), runId(), stepId(SUMMARISE), ANN_USER)

        then:
        read.drawing() == null
        read.step().runs().kind() == "question"
        statements.count { it.contains("select drawing from question_versions") } == 1
    }

    def "a code step's page has no drawing and makes no question-version lookup"() {
        given:
        CodeWorkflow.seed(store, 1, false, "person")
        CodeWorkflow.run(store)
        store.transactions().executeWithoutResult {
            engine.planStarted(tree.lock(groupId(CodeWorkflow.GROUP), new RunId(UUID.fromString(CodeWorkflow.RUN)))
                    .orElseThrow())
        }
        def statements = []
        def counting = new CountingDataSource(store.database, statements)
        def session = JdbcClient.create(counting)
        def counted = new RunSteps(session, new GroupRoles(session), new RunSnapshots(session, CodeStepsHeld.NONE, new SearchWays([])),
                store.transactionManager(counting), Deployments.DEEP_ENOUGH, new org.lilradish.lite.app.currency.GroupCurrencies(session, new org.springframework.transaction.support.TransactionTemplate(store.transactionManager(counting)), new GroupRoles(session), org.lilradish.lite.testutil.library.DeployedModels.HELD))

        when:
        def read = counted.step(groupId(CodeWorkflow.GROUP), new RunId(UUID.fromString(CodeWorkflow.RUN)),
                stepId(CodeWorkflow.SEND), CodeWorkflow.ANN_USER)

        then:
        read.step().runs().kind() == "code_step"
        read.drawing() == null
        !statements.any { it.contains("select drawing from question_versions") }
    }

    def "a step not reached says nothing went in and nothing came out, and made no try"() {
        given:
        // DB-SPECIFIC: PostgreSQL ::jsonb/::uuid casts.
        store.session.sql("""
                update bindings set source_step_id = null, source_path = null,
                                    constant = '"Demo Sample constant"'::jsonb
                 where entry_version_id = ?::uuid and workflow_step_id = ?::uuid and target_path = 'summary'
                """).params(VERSION, CONFIRM).update()

        when:
        def read = steps.step(groupId(GROUP), runId(), stepId(CONFIRM), ANN_USER)

        then:
        read.wentIn() == null
        read.wentInFrom() == null
        read.cameOut() == null
        read.triesMade() == []
        read.step().state() == "not_started"
    }

    def "a step held back before its try shows what it would take, marked as not yet sent, and the hold first"() {
        given:
        store.stopped(CONFIRM_QUESTION, FIRST_STEWARD)
        answeredByCat()
        reviewed(ReviewOutcome.ASSURED)

        when:
        def read = steps.step(groupId(GROUP), runId(), stepId(CONFIRM), CAT_USER)

        then:
        read.step().state() == "held_back"
        read.step().where().kind() == "held_back"
        read.step().where().reason() == "entry_stopped"
        read.wentInFrom() == "not_yet_sent"
        read.wentIn() == [new StepAnswers.WentInAnswer("summary",
                new StepAnswers.FromAnswer("step", "summary", UUID.fromString(SUMMARISE), "summarise",
                        UUID.fromString(SUMMARISE_VERSION), null, UUID.fromString(RUN), 1, null),
                NODES.stringNode("A printer fire."), null, null, null, null)]

        and: "it says what it runs was stopped, by whom and when, and waits on whoever started the run"
        read.step().where().stopped() == new StepAnswers.StoppedAnswer("entry", STEWARD_ANSWER, stoppedAt(CONFIRM_QUESTION))
        read.step().where().waitsOn() == "starter"

        and: "nothing produced, no try made, and nothing to answer: the try it holds is one the run makes itself"
        read.cameOut() == null
        read.triesMade() == []
        read.step().next() == null
        read.step().acts() == []
        read.step().withheld() == []
    }

    def "a hold left behind by a stop let go with nothing going on after it reads as the try owed, and answering it is taken"() {
        given:
        store.stopped(CONFIRM_QUESTION, FIRST_STEWARD)
        answeredByCat()
        reviewed(ReviewOutcome.ASSURED)
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
        store.session.sql("update entry_stops set let_go_at = now(), let_go_by = ?::uuid").params(FIRST_STEWARD).update()

        when:
        def owed = steps.steps(groupId(GROUP), runId(), CAT_USER).steps()[1]

        then:
        owed.state() == "waiting"
        owed.where().kind() == "owed_try"
        owed.where().stopped() == null
        owed.where().waitsOn() == "answer_step"
        owed.where().open()
        owed.next() == new StepAnswers.NextAnswer(1, false)
        owed.acts() == ["answer"]
        owed.withheld() == []

        and: "reading it released nothing"
        store.count("select count(*) from run_step_holds where released_at is null") == 1

        when:
        acts.answer(groupId(GROUP), runId(), stepId(CONFIRM), 1, CAT_USER,
                new JsonValue.JsonObject([new JsonValue.JsonMember("approved", new JsonValue.JsonString("true")),
                                          new JsonValue.JsonMember("note", new JsonValue.JsonString(""))]),
                "Checked.")
        def answered = steps.steps(groupId(GROUP), runId(), CAT_USER).steps()[1]

        then:
        answered.state() == "done"
        answered.next() == null
        store.count("select count(*) from run_step_holds where released_at is null") == 0
        store.count("select count(*) from productions where ended_at is null") == 0
    }

    def "a try owed while the workflow itself is stopped is held back saying so, on whoever started the run, and goes back to whoever may answer once let go"() {
        given:
        answeredByCat()
        reviewed(ReviewOutcome.REFUSED)
        store.stopped(TicketWorkflow.WORKFLOW, FIRST_STEWARD)

        when:
        def held = steps.steps(groupId(GROUP), runId(), CAT_USER).steps()[0]

        then:
        held.state() == "held_back"
        held.where().stopped() == new StepAnswers.StoppedAnswer("workflow", STEWARD_ANSWER,
                stoppedAt(TicketWorkflow.WORKFLOW))
        held.where().waitsOn() == "starter"
        held.next() == new StepAnswers.NextAnswer(2, false)
        held.acts() == []
        held.withheld() == [new StepAnswers.WithheldAnswer("answer", "ENTRY_STOPPED", null, null),
                            new StepAnswers.WithheldAnswer("ask_again", "ENTRY_STOPPED", null, null)]

        when:
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
        store.session.sql("update entry_stops set let_go_at = now(), let_go_by = ?::uuid").params(FIRST_STEWARD).update()
        def letGo = steps.steps(groupId(GROUP), runId(), CAT_USER).steps()[0]

        then:
        letGo.state() == "waiting"
        letGo.where().kind() == "owed_try"
        letGo.where().stopped() == null
        letGo.where().waitsOn() == "answer_step"
        letGo.acts() == ["answer", "ask_again"]
        letGo.withheld() == []
    }

    private String stoppedAt(String entry) {
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("select created_at from entry_stops where entry_id = ?::uuid and let_go_at is null")
                .params(entry).query(OffsetDateTime).single().toInstant().toString()
    }

    def "answering here is put to whoever may answer: the try it fills, the instruction, and what it gives back"() {
        when:
        def read = steps.step(groupId(GROUP), runId(), stepId(SUMMARISE), CAT_USER)

        then:
        read.answering() == new StepAnswers.AnsweringAnswer(1, false, "Say what the ticket is about.",
                [new FillFieldAnswer("summary", null, null, "text", 1000, null, true, null, null)], null, null, null, null)
    }

    def "answering after a refusal shows the refused production and the words it was refused with"() {
        given:
        answeredByCat()
        reviewed(ReviewOutcome.REFUSED)

        when:
        def read = steps.step(groupId(GROUP), runId(), stepId(SUMMARISE), CAT_USER)

        then:
        read.answering().number() == 2
        read.answering().lastRefused() == new StepAnswers.LastRefusedAnswer(1, [
                new StepAnswers.TriedValueAnswer("summary", NODES.stringNode("A printer fire."), null, "refused", null,
                        new StepAnswers.DecisionAnswer("refused", "Too short.", null), null)])
        read.step().acts() == ["answer", "ask_again"]
    }

    def "answering a try asked again after a refusal still shows the refused production, the newest that gave anything back"() {
        given:
        answeredByCat()
        reviewed(ReviewOutcome.REFUSED)
        acts.askAgain(groupId(GROUP), runId(), stepId(SUMMARISE), 2, CAT_USER)

        when:
        def read = steps.step(groupId(GROUP), runId(), stepId(SUMMARISE), CAT_USER)

        then:
        read.answering().number() == 2
        read.answering().lastRefused() == new StepAnswers.LastRefusedAnswer(1, [
                new StepAnswers.TriedValueAnswer("summary", NODES.stringNode("A printer fire."), null, "refused", null,
                        new StepAnswers.DecisionAnswer("refused", "Too short.", null), null)])
        read.step().acts() == ["answer"]
    }

    def "answering the first try asked shows no refused production, there being none"() {
        when:
        def read = steps.step(groupId(GROUP), runId(), stepId(SUMMARISE), CAT_USER)

        then:
        read.answering().number() == 1
        read.answering().lastRefused() == null
    }

    def "a root's nested started value has one input origin and adds no tree-detail queries"() {
        given:
        def rootEntry = UUID.randomUUID().toString()
        def rootVersion = UUID.randomUUID().toString()
        def questionEntry = UUID.randomUUID().toString()
        def questionVersion = UUID.randomUUID().toString()
        def rootRun = UUID.randomUUID().toString()
        def questionStep = UUID.randomUUID().toString()
        StepRows.workflow(store, rootEntry, rootVersion, GROUP, "Demo Sample root")
        StepRows.question(store, questionEntry, questionVersion, GROUP, "Demo Sample question")
        def rootCustomer = UUID.randomUUID().toString()
        def questionCustomer = UUID.randomUUID().toString()
        StepRows.field(store, [id: rootCustomer, owner: rootVersion, ownerKind: "workflow",
                               side: "takes", position: 1, name: "customer", kind: "fields"])
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: rootVersion, ownerKind: "workflow",
                               side: "takes", parent: rootCustomer, position: 1, name: "name"])
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: rootVersion, ownerKind: "workflow",
                               side: "takes", parent: rootCustomer, position: 2, name: "id"])
        StepRows.field(store, [id: questionCustomer, owner: questionVersion, ownerKind: "question",
                               side: "takes", position: 1, name: "customer", kind: "fields"])
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: questionVersion, ownerKind: "question",
                               side: "takes", parent: questionCustomer, position: 1, name: "name"])
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: questionVersion, ownerKind: "question",
                               side: "takes", parent: questionCustomer, position: 2, name: "id"])
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: questionVersion, ownerKind: "question",
                               side: "gives", position: 1, name: "answer", mustBe: true, standing: "always"])
        StepRows.step(store, questionStep, rootVersion, 1, "ask", questionVersion, "model", 1)
        StepRows.binding(store, UUID.randomUUID().toString(), rootVersion, questionStep,
                "customer", null, "customer")
        StepRows.run(store, rootRun, GROUP, 121, rootEntry, rootVersion, CAT,
                '{"customer":{"name":"Demo Sample name","id":"Demo Sample id"}}')
        def countedStatements = []
        def counting = new CountingDataSource(store.database, countedStatements)
        def countedSession = JdbcClient.create(counting)
        def counted = new RunSteps(countedSession, new GroupRoles(countedSession),
                new RunSnapshots(countedSession, CodeStepsHeld.NONE, new SearchWays([])),
                store.transactionManager(counting), Deployments.DEEP_ENOUGH, new org.lilradish.lite.app.currency.GroupCurrencies(countedSession, new org.springframework.transaction.support.TransactionTemplate(store.transactionManager(counting)), new GroupRoles(countedSession), org.lilradish.lite.testutil.library.DeployedModels.HELD))
        def context = new RunSnapshots(store.session, CodeStepsHeld.NONE, new SearchWays([]))
                .inTreeAsRead(groupId(GROUP), new RunId(UUID.fromString(rootRun)))

        when:
        def page = counted.steps(groupId(GROUP), new RunId(UUID.fromString(rootRun)), CAT_USER)
        def queries = countedStatements.size()
        def read = steps.step(groupId(GROUP), new RunId(UUID.fromString(rootRun)), stepId(questionStep), CAT_USER)

        then:
        StepInputs.bound(context, context.run().steps()[0]).get()[0].parts() == []
        queries == READ_IN
        !countedStatements.any { it.contains("entry.name as workflow_name") }
        !countedStatements.any { it.contains("with recursive subtree (child_run_id, run_id)") }
        page.steps()[0].beneath() == null
        read.wentInFrom() == "not_yet_sent"
        read.wentIn()*.input() == ["customer"]
        read.wentIn()[0].from().kind() == "run_input"
        read.wentIn()[0].from().path() == "customer"
        read.wentIn()[0].from().runId() == UUID.fromString(rootRun)
        read.wentIn()[0].from().runNumber() == 121
        read.wentIn()[0].sources() == null
        read.wentIn()[0].value().get("name").asText() == "Demo Sample name"
        read.wentIn()[0].value().get("id").asText() == "Demo Sample id"
    }

    def "a child workflow gives constants, carried input, and ordered composite origins without a made try"() {
        given:
        def rootEntry = UUID.randomUUID().toString()
        def rootVersion = UUID.randomUUID().toString()
        def childEntry = UUID.randomUUID().toString()
        def childVersion = UUID.randomUUID().toString()
        def rootRun = UUID.randomUUID().toString()
        def childRun = UUID.randomUUID().toString()
        def parentStep = UUID.randomUUID().toString()
        def childStep = UUID.randomUUID().toString()
        StepRows.workflow(store, rootEntry, rootVersion, GROUP, "Demo Sample root")
        StepRows.workflow(store, childEntry, childVersion, GROUP, "Demo Sample child")
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: rootVersion, ownerKind: "workflow",
                               side: "takes", position: 1, name: "ticket"])
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: childVersion, ownerKind: "workflow",
                               side: "takes", position: 1, name: "item"])
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: childVersion, ownerKind: "workflow",
                               side: "gives", position: 1, name: "constant"])
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: childVersion, ownerKind: "workflow",
                               side: "gives", position: 2, name: "carried"])
        def packet = UUID.randomUUID().toString()
        StepRows.field(store, [id: packet, owner: childVersion, ownerKind: "workflow",
                               side: "gives", position: 3, name: "packet", kind: "fields"])
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: childVersion, ownerKind: "workflow",
                               side: "gives", parent: packet, position: 1, name: "left"])
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: childVersion, ownerKind: "workflow",
                               side: "gives", parent: packet, position: 2, name: "right"])
        StepRows.askingSomebody(store, childVersion, GROUP, childStep)
        StepRows.run(store, rootRun, GROUP, 101, rootEntry, rootVersion, CAT,
                '{"ticket":"Demo Sample ticket"}')
        RunRows.beneath(store, childRun, rootRun, GROUP, 102, childEntry, childVersion,
                parentStep, UUID.randomUUID().toString())
        StepRows.binding(store, UUID.randomUUID().toString(), rootVersion, parentStep, "item", null, "ticket")
        StepRows.binding(store, UUID.randomUUID().toString(), childVersion, null, "constant", null, null,
                '"Demo Sample constant"')
        StepRows.binding(store, UUID.randomUUID().toString(), childVersion, null, "carried", null, "item")
        StepRows.binding(store, UUID.randomUUID().toString(), childVersion, null, "packet.left", null, "item")
        StepRows.binding(store, UUID.randomUUID().toString(), childVersion, null, "packet.right", null, null,
                '"Demo Sample right"')

        when:
        def read = steps.step(groupId(GROUP), new RunId(UUID.fromString(rootRun)), stepId(parentStep), CAT_USER)
        def came = read.cameOut()

        then:
        came*.field() == ["constant", "carried", "packet"]
        came*.now() == ["stands", "stands", "stands"]
        came.every { it.standing().number() == null && it.fromRun() == null }
        came[0].standing().value() == NODES.stringNode("Demo Sample constant")
        came[0].from().kind() == "constant"
        came[0].from().runId() == null
        came[0].sources() == null
        came[1].standing().value() == NODES.stringNode("Demo Sample ticket")
        came[1].from().kind() == "run_input"
        came[1].from().path() == "ticket"
        came[1].from().runId() == UUID.fromString(rootRun)
        came[1].from().runNumber() == 101
        came[1].sources() == null
        came[2].standing().value().get("left").asText() == "Demo Sample ticket"
        came[2].standing().value().get("right").asText() == "Demo Sample right"
        came[2].from() == null
        came[2].sources()*.targetPath() == ["left", "right"]
        came[2].sources()*.from()*.kind() == ["run_input", "constant"]
        came[2].sources()[0].from().runId() == UUID.fromString(rootRun)
        came[2].sources()[0].from().runNumber() == 101
        came[2].sources()[1].from().runId() == null
    }

    def "a child workflow's standing answer names the actual making try and run"() {
        given:
        def rootEntry = UUID.randomUUID().toString()
        def rootVersion = UUID.randomUUID().toString()
        def childEntry = UUID.randomUUID().toString()
        def childVersion = UUID.randomUUID().toString()
        def rootRun = UUID.randomUUID().toString()
        def childRun = UUID.randomUUID().toString()
        def otherChildRun = UUID.randomUUID().toString()
        def parentStep = UUID.randomUUID().toString()
        def otherParentStep = UUID.randomUUID().toString()
        def childStep = UUID.randomUUID().toString()
        StepRows.workflow(store, rootEntry, rootVersion, GROUP, "Demo Sample root")
        StepRows.workflow(store, childEntry, childVersion, GROUP, "Demo Sample child")
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: childVersion, ownerKind: "workflow",
                               side: "gives", position: 1, name: "result"])
        def answer = StepRows.askingSomebody(store, childVersion, GROUP, childStep)
        StepRows.run(store, rootRun, GROUP, 111, rootEntry, rootVersion, CAT, '{}')
        RunRows.beneath(store, childRun, rootRun, GROUP, 112, childEntry, childVersion,
                parentStep, UUID.randomUUID().toString())
        def otherParentRunStep = UUID.randomUUID().toString()
        store.step(otherParentStep, rootVersion, 2, childVersion, "workflow")
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into run_steps (run_step_id, run_id, entry_version_id, workflow_step_id, step_kind,
                                       pinned_version_id, pinned_kind, reviewed_by_model, created_by)
                values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'entry', ?::uuid, 'workflow', false, ?::uuid)
                """).params(otherParentRunStep, rootRun, rootVersion, otherParentStep, childVersion,
                RunRows.WORKFLOW_RUNNER).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into runs (run_id, group_id, number, entry_id, entry_version_id, parent_run_id,
                                  parent_run_step_id, parent_run_step_kind, parent_workflow_step_id, root_run_id,
                                  depth, parent_depth, created_by, created_by_kind)
                select ?::uuid, ?::uuid, 113, ?::uuid, ?::uuid, above.run_id, ?::uuid, 'workflow', ?::uuid,
                       above.root_run_id, above.depth + 1, above.depth, ?::uuid, 'system'
                  from runs above where above.run_id = ?::uuid
                """).params(otherChildRun, GROUP, childEntry, childVersion, otherParentRunStep, otherParentStep,
                RunRows.WORKFLOW_RUNNER, rootRun).update()
        StepRows.binding(store, UUID.randomUUID().toString(), childVersion, null, "result", childStep, "answer")
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        def questionVersion = store.texts("select pinned_version_id::text from workflow_steps where workflow_step_id = ?::uuid",
                childStep)[0]
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, RETURNING.
        def heldStep = store.session.sql("""
                insert into run_steps (run_id, entry_version_id, workflow_step_id, step_kind,
                                       pinned_version_id, pinned_kind, producer, reviewed_by_model, tries,
                                       created_by, created_by_kind)
                values (?::uuid, ?::uuid, ?::uuid, 'entry', ?::uuid, 'question', 'person', false, 1,
                        ?::uuid, 'system') returning run_step_id::text
                """).params(childRun, childVersion, childStep, questionVersion, RunRows.WORKFLOW_RUNNER)
                .query(String).single()
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, now(), RETURNING.
        def production = store.session.sql("""
                insert into productions (run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                                         reviewed_by_model, tries, pinned_version_id, try_number, producer,
                                         created_by, created_by_kind, ended_at, ended_by, ended_by_kind, explanation)
                values (?::uuid, ?::uuid, ?::uuid, 'question', 'person', false, 1, ?::uuid, 1, 'person',
                        ?::uuid, 'system', now(), ?::uuid, 'person', 'Demo Sample')
                returning production_id::text
                """).params(heldStep, childRun, rootRun, questionVersion, RunRows.WORKFLOW_RUNNER, CAT)
                .query(String).single()
        // DB-SPECIFIC: PostgreSQL ::jsonb/::uuid casts.
        store.session.sql("""
                insert into production_values (production_id, run_step_kind, producer, pinned_version_id,
                                               declaration_field_id, field_standing, value)
                values (?::uuid, 'question', 'person', ?::uuid, ?::uuid, 'always', '"Demo Sample answer"'::jsonb)
                """).params(production, questionVersion, answer).update()

        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, RETURNING.
        def otherHeldStep = store.session.sql("""
                insert into run_steps (run_id, entry_version_id, workflow_step_id, step_kind,
                                       pinned_version_id, pinned_kind, producer, reviewed_by_model, tries,
                                       created_by, created_by_kind)
                values (?::uuid, ?::uuid, ?::uuid, 'entry', ?::uuid, 'question', 'person', false, 1,
                        ?::uuid, 'system') returning run_step_id::text
                """).params(otherChildRun, childVersion, childStep, questionVersion, RunRows.WORKFLOW_RUNNER)
                .query(String).single()
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, now(), RETURNING.
        def otherProduction = store.session.sql("""
                insert into productions (run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                                         reviewed_by_model, tries, pinned_version_id, try_number, producer,
                                         created_by, created_by_kind, ended_at, ended_by, ended_by_kind, explanation)
                values (?::uuid, ?::uuid, ?::uuid, 'question', 'person', false, 1, ?::uuid, 1, 'person',
                        ?::uuid, 'system', now(), ?::uuid, 'person', 'Demo Sample')
                returning production_id::text
                """).params(otherHeldStep, otherChildRun, rootRun, questionVersion, RunRows.WORKFLOW_RUNNER, CAT)
                .query(String).single()
        // DB-SPECIFIC: PostgreSQL ::jsonb/::uuid casts.
        store.session.sql("""
                insert into production_values (production_id, run_step_kind, producer, pinned_version_id,
                                               declaration_field_id, field_standing, value)
                values (?::uuid, 'question', 'person', ?::uuid, ?::uuid, 'always', '"Demo Sample later"'::jsonb)
                """).params(otherProduction, questionVersion, answer).update()

        def finalStep = UUID.randomUUID().toString()
        def finalQuestion = UUID.randomUUID().toString()
        def finalVersion = UUID.randomUUID().toString()
        def finalAnswer = UUID.randomUUID().toString()
        StepRows.question(store, finalQuestion, finalVersion, GROUP, "Demo Sample finish")
        StepRows.field(store, [id: finalAnswer, owner: finalVersion, ownerKind: "question", side: "gives",
                               position: 1, name: "answer", mustBe: true, standing: "always"])
        StepRows.step(store, finalStep, childVersion, 2, "finish", finalVersion, "person", 1)
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, RETURNING.
        def finishedStep = store.session.sql("""
                insert into run_steps (run_id, entry_version_id, workflow_step_id, step_kind,
                                       pinned_version_id, pinned_kind, producer, reviewed_by_model, tries,
                                       created_by, created_by_kind)
                values (?::uuid, ?::uuid, ?::uuid, 'entry', ?::uuid, 'question', 'person', false, 1,
                        ?::uuid, 'system') returning run_step_id::text
                """).params(childRun, childVersion, finalStep, finalVersion, RunRows.WORKFLOW_RUNNER)
                .query(String).single()
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, now(), RETURNING.
        def finished = store.session.sql("""
                insert into productions (run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                                         reviewed_by_model, tries, pinned_version_id, try_number, producer,
                                         created_by, created_by_kind, ended_at, ended_by, ended_by_kind, explanation)
                values (?::uuid, ?::uuid, ?::uuid, 'question', 'person', false, 1, ?::uuid, 1, 'person',
                        ?::uuid, 'system', now(), ?::uuid, 'person', 'Demo Sample finished')
                returning production_id::text
                """).params(finishedStep, childRun, rootRun, finalVersion, RunRows.WORKFLOW_RUNNER, CAT)
                .query(String).single()
        // DB-SPECIFIC: PostgreSQL ::jsonb/::uuid casts.
        store.session.sql("""
                insert into production_values (production_id, run_step_kind, producer, pinned_version_id,
                                               declaration_field_id, field_standing, value)
                values (?::uuid, 'question', 'person', ?::uuid, ?::uuid, 'always', '"Demo Sample finished"'::jsonb)
                """).params(finished, finalVersion, finalAnswer).update()

        when:
        def parent = steps.step(groupId(GROUP), new RunId(UUID.fromString(rootRun)), stepId(parentStep), CAT_USER)
        def child = steps.step(groupId(GROUP), new RunId(UUID.fromString(childRun)), stepId(childStep), CAT_USER)
        def otherParent = steps.step(groupId(GROUP), new RunId(UUID.fromString(rootRun)),
                stepId(otherParentStep), CAT_USER)
        def otherChild = steps.step(groupId(GROUP), new RunId(UUID.fromString(otherChildRun)),
                stepId(childStep), CAT_USER)
        def output = parent.cameOut()[0]
        def otherOutput = otherParent.cameOut()[0]
        def rows = steps.steps(groupId(GROUP), new RunId(UUID.fromString(rootRun)), CAT_USER).steps()
        def parentRow = rows.find { it.stepId() == UUID.fromString(parentStep) }
        def otherRow = rows.find { it.stepId() == UUID.fromString(otherParentStep) }

        then:
        output.field() == "result"
        output.now() == "stands"
        output.standing().number() == 1
        output.standing().value() == NODES.stringNode("Demo Sample answer")
        output.standing().withheld() == null
        output.fromRun() == new RunsController.NumberedRunAnswer(UUID.fromString(childRun), 112)
        output.from().kind() == "step"
        output.from().path() == "answer"
        output.from().stepId() == UUID.fromString(childStep)
        output.from().runId() == UUID.fromString(childRun)
        output.from().runNumber() == 112
        output.sources() == null
        parentRow.gaveBack() == [new StepAnswers.ShownValueAnswer("result",
                NODES.stringNode("Demo Sample answer"), null, "stands", null)]
        otherRow.gaveBack() == null
        child.triesMade()*.number() == [1]
        parent.step().beneath().runId() == UUID.fromString(childRun)
        parent.step().beneath().number() == 112
        parent.step().beneath().at() == null
        parent.step().producer() == null
        parent.step().reviewer() == null
        parent.step().tries() == null
        otherOutput.field() == "result"
        otherOutput.now() == "stands"
        otherOutput.standing().number() == 1
        otherOutput.standing().value() == NODES.stringNode("Demo Sample later")
        otherOutput.fromRun() == new RunsController.NumberedRunAnswer(UUID.fromString(otherChildRun), 113)
        otherOutput.from().runId() == UUID.fromString(otherChildRun)
        otherOutput.from().runNumber() == 113
        otherOutput.from().stepId() == UUID.fromString(childStep)
        otherOutput.sources() == null
        otherChild.triesMade()*.number() == [1]
        otherParent.step().beneath().runId() == UUID.fromString(otherChildRun)
        otherParent.step().beneath().number() == 113
    }

    def "a parent read skips an old completed child and keeps the active child in one bounded query"() {
        given:
        def rootEntry = UUID.randomUUID().toString()
        def rootVersion = UUID.randomUUID().toString()
        def childEntry = UUID.randomUUID().toString()
        def childVersion = UUID.randomUUID().toString()
        def questionEntry = UUID.randomUUID().toString()
        def questionVersion = UUID.randomUUID().toString()
        def parentStep = UUID.randomUUID().toString()
        def oldParentStep = UUID.randomUUID().toString()
        def childStep = UUID.randomUUID().toString()
        def rootRun = UUID.randomUUID().toString()
        def childRun = UUID.randomUUID().toString()
        def oldChildRun = UUID.randomUUID().toString()
        def system = RunRows.WORKFLOW_RUNNER
        StepRows.workflow(store, rootEntry, rootVersion, GROUP, "Demo Sample root")
        StepRows.workflow(store, childEntry, childVersion, GROUP, "Demo Sample child")
        StepRows.question(store, questionEntry, questionVersion, GROUP, "Demo Sample question")
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: questionVersion, ownerKind: "question",
                               side: "gives", position: 1, name: "answer", mustBe: true, standing: "always"])
        store.step(oldParentStep, rootVersion, 1, childVersion, "workflow")
        store.step(parentStep, rootVersion, 2, childVersion, "workflow")
        StepRows.step(store, childStep, childVersion, 1, "ask", questionVersion, "model", 2)
        StepRows.run(store, rootRun, GROUP, 41, rootEntry, rootVersion, CAT, '{}')
        // DB-SPECIFIC: PostgreSQL ::uuid casts, RETURNING.
        def parentStored = store.session.sql("""
                insert into run_steps (run_id, entry_version_id, workflow_step_id, step_kind,
                                       pinned_version_id, pinned_kind, reviewed_by_model, created_by)
                values (?::uuid, ?::uuid, ?::uuid, 'entry', ?::uuid, 'workflow', false, ?::uuid)
                returning cast(run_step_id as text)
                """).params(rootRun, rootVersion, parentStep, childVersion, system).query(String).single()
        // DB-SPECIFIC: PostgreSQL ::uuid casts, RETURNING.
        def oldParentStored = store.session.sql("""
                insert into run_steps (run_id, entry_version_id, workflow_step_id, step_kind,
                                       pinned_version_id, pinned_kind, reviewed_by_model, created_by)
                values (?::uuid, ?::uuid, ?::uuid, 'entry', ?::uuid, 'workflow', false, ?::uuid)
                returning cast(run_step_id as text)
                """).params(rootRun, rootVersion, oldParentStep, childVersion, system).query(String).single()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into runs (run_id, group_id, number, entry_id, entry_version_id, parent_run_id,
                                  parent_run_step_id, parent_run_step_kind, parent_workflow_step_id, root_run_id,
                                  depth, parent_depth, created_by, created_by_kind)
                values (?::uuid, ?::uuid, 42, ?::uuid, ?::uuid, ?::uuid, ?::uuid, 'workflow', ?::uuid,
                        ?::uuid, 1, 0, ?::uuid, 'system')
                """).params(childRun, GROUP, childEntry, childVersion, rootRun, parentStored, parentStep,
                rootRun, system).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts, RETURNING.
        def childStored = store.session.sql("""
                insert into run_steps (run_id, entry_version_id, workflow_step_id, step_kind, pinned_version_id,
                                       pinned_kind, producer, reviewed_by_model, tries, created_by, created_by_kind)
                select ?::uuid, step.entry_version_id, step.workflow_step_id, step.kind, step.pinned_version_id,
                       step.pinned_kind, step.producer, step.reviewed_by_model, step.tries, ?::uuid, 'system'
                  from workflow_steps step where step.workflow_step_id = ?::uuid
                returning cast(run_step_id as text)
                """).params(childRun, system, childStep).query(String).single()
        // DB-SPECIFIC: PostgreSQL ::uuid casts, RETURNING.
        def production = store.session.sql("""
                insert into productions (run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                                         reviewed_by_model, tries, pinned_version_id, try_number, producer, created_by)
                values (?::uuid, ?::uuid, ?::uuid, 'question', 'model', false, 2, ?::uuid, 1, 'model', ?::uuid)
                returning cast(production_id as text)
                """).params(childStored, childRun, rootRun, questionVersion, system).query(String).single()
        def attempts = []
        ["Demo Sample busy", "Demo Sample spent"].eachWithIndex { said, index ->
            // DB-SPECIFIC: PostgreSQL ::uuid casts, RETURNING.
            def attempt = store.session.sql("""
                    insert into run_step_send_attempts (run_step_id, run_id, run_step_kind, workflow_step_id,
                                                        purpose, model, mode, production_id, payload,
                                                        created_by, created_by_kind)
                    values (?::uuid, ?::uuid, 'question', ?::uuid, 'produce', 'general', 'ordinary', ?::uuid,
                            '{}', ?::uuid, 'system')
                    returning cast(run_step_send_attempt_id as text)
                    """).params(childStored, childRun, childStep, production, system).query(String).single()
            attempts << attempt
            // DB-SPECIFIC: PostgreSQL ::uuid casts, now(), RETURNING.
            def call = store.session.sql("""
                    insert into model_calls (run_id, root_run_id, purpose, run_step_send_attempt_id, run_step_id,
                                             production_id, model, mode, envelope_version, sent_count, outcome,
                                             ended_at, created_by)
                    values (?::uuid, ?::uuid, 'produce', ?::uuid, ?::uuid, ?::uuid, 'general', 'ordinary',
                            1, 5, 'turned_away', now(), ?::uuid)
                    returning cast(model_call_id as text)
                    """).params(childRun, rootRun, attempt, childStored, production, system).query(String).single()
            // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
            store.session.sql("""
                    insert into model_call_turnaways (model_call_id, said, created_at, resent_at, created_by)
                    values (?::uuid, ?, now() - (? * interval '1 minute'),
                            case when ? then now() - interval '30 seconds' end, ?::uuid)
                    """).params(call, said, 2 - index, index == 0, system).update()
        }
        // DB-SPECIFIC: PostgreSQL ::uuid casts, enum type run_step_hold_reason.
        store.session.sql("""
                insert into run_step_holds (run_step_id, run_id, run_step_kind, calls_a_model, reason,
                                            run_step_send_attempt_id, created_by)
                select step.run_step_id, step.run_id, step.kind, step.calls_a_model,
                       cast(? as run_step_hold_reason), ?::uuid, ?::uuid
                  from run_steps step where step.run_step_id = ?::uuid
                """).params(StoreLabels.label(RunStepHoldReason.TURNED_AWAY), attempts.last(), system,
                childStored).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into runs (run_id, group_id, number, entry_id, entry_version_id, parent_run_id,
                                  parent_run_step_id, parent_run_step_kind, parent_workflow_step_id, root_run_id,
                                  depth, parent_depth, created_by, created_by_kind)
                values (?::uuid, ?::uuid, 43, ?::uuid, ?::uuid, ?::uuid, ?::uuid, 'workflow', ?::uuid,
                        ?::uuid, 1, 0, ?::uuid, 'system')
                """).params(oldChildRun, GROUP, childEntry, childVersion, rootRun, oldParentStored, oldParentStep,
                rootRun, system).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts, RETURNING.
        def oldChildStored = store.session.sql("""
                insert into run_steps (run_id, entry_version_id, workflow_step_id, step_kind, pinned_version_id,
                                       pinned_kind, producer, reviewed_by_model, tries, created_by, created_by_kind)
                select ?::uuid, step.entry_version_id, step.workflow_step_id, step.kind, step.pinned_version_id,
                       step.pinned_kind, step.producer, step.reviewed_by_model, step.tries, ?::uuid, 'system'
                  from workflow_steps step where step.workflow_step_id = ?::uuid
                returning cast(run_step_id as text)
                """).params(oldChildRun, system, childStep).query(String).single()
        // DB-SPECIFIC: PostgreSQL ::uuid casts, RETURNING.
        def oldProduction = store.session.sql("""
                insert into productions (run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                                         reviewed_by_model, tries, pinned_version_id, try_number, producer, created_by)
                values (?::uuid, ?::uuid, ?::uuid, 'question', 'model', false, 2, ?::uuid, 1, 'model', ?::uuid)
                returning cast(production_id as text)
                """).params(oldChildStored, oldChildRun, rootRun, questionVersion, system).query(String).single()
        // DB-SPECIFIC: PostgreSQL ::uuid casts, RETURNING.
        def oldAttempt = store.session.sql("""
                insert into run_step_send_attempts (run_step_id, run_id, run_step_kind, workflow_step_id,
                                                    purpose, model, mode, production_id, payload,
                                                    created_by, created_by_kind)
                values (?::uuid, ?::uuid, 'question', ?::uuid, 'produce', 'general', 'ordinary', ?::uuid,
                        '{}', ?::uuid, 'system')
                returning cast(run_step_send_attempt_id as text)
                """).params(oldChildStored, oldChildRun, childStep, oldProduction, system).query(String).single()
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now(), RETURNING.
        def oldCall = store.session.sql("""
                insert into model_calls (run_id, root_run_id, purpose, run_step_send_attempt_id, run_step_id,
                                         production_id, model, mode, envelope_version, sent_count, outcome,
                                         ended_at, created_by)
                values (?::uuid, ?::uuid, 'produce', ?::uuid, ?::uuid, ?::uuid, 'general', 'ordinary',
                        1, 5, 'turned_away', now(), ?::uuid)
                returning cast(model_call_id as text)
                """).params(oldChildRun, rootRun, oldAttempt, oldChildStored, oldProduction, system)
                .query(String).single()
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
        store.session.sql("""
                insert into model_call_turnaways (model_call_id, said, created_at, resent_at, created_by)
                values (?::uuid, 'Demo Sample old', now() - interval '3 minutes', now() - interval '2 minutes', ?::uuid)
                """).params(oldCall, system).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts, RETURNING.
        def finalAttempt = store.session.sql("""
                insert into run_step_send_attempts (run_step_id, run_id, run_step_kind, workflow_step_id,
                                                    purpose, model, mode, production_id, payload,
                                                    created_by, created_by_kind)
                values (?::uuid, ?::uuid, 'question', ?::uuid, 'produce', 'general', 'ordinary', ?::uuid,
                        '{}', ?::uuid, 'system')
                returning cast(run_step_send_attempt_id as text)
                """).params(oldChildStored, oldChildRun, childStep, oldProduction, system).query(String).single()
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now(), RETURNING.
        def finalCall = store.session.sql("""
                insert into model_calls (run_id, root_run_id, purpose, run_step_send_attempt_id, run_step_id,
                                         production_id, model, mode, envelope_version, sent_count, came_back_count,
                                         outcome, answer, ended_at, created_by)
                values (?::uuid, ?::uuid, 'produce', ?::uuid, ?::uuid, ?::uuid, 'general', 'ordinary',
                        1, 5, 1, 'came_back', '{}', now(), ?::uuid)
                returning cast(model_call_id as text)
                """).params(oldChildRun, rootRun, finalAttempt, oldChildStored, oldProduction, system)
                .query(String).single()
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
        store.session.sql("""
                update productions set model_call_id = ?::uuid, model_call_outcome = 'came_back',
                                       ended_at = now(), ended_by = ?::uuid
                 where production_id = ?::uuid
                """).params(finalCall, system, oldProduction).update()
        // DB-SPECIFIC: PostgreSQL ::jsonb/::uuid casts.
        store.session.sql("""
                insert into production_values (production_id, run_step_kind, producer, pinned_version_id,
                                               declaration_field_id, field_standing, value)
                select ?::uuid, 'question', 'model', ?::uuid, field.declaration_field_id, field.standing,
                       '"Demo Sample done"'::jsonb
                  from declaration_fields field
                 where field.entry_version_id = ?::uuid and field.side = 'gives'
                """).params(oldProduction, questionVersion, questionVersion).update()
        store.stopped(questionEntry, FIRST_STEWARD)
        def statements = []
        def counting = new CountingDataSource(store.database, statements)
        def session = JdbcClient.create(counting)
        def counted = new RunSteps(session, new GroupRoles(session), new RunSnapshots(session, CodeStepsHeld.NONE, new SearchWays([])),
                store.transactionManager(counting), Deployments.DEEP_ENOUGH, new org.lilradish.lite.app.currency.GroupCurrencies(session, new org.springframework.transaction.support.TransactionTemplate(store.transactionManager(counting)), new GroupRoles(session), org.lilradish.lite.testutil.library.DeployedModels.HELD))

        when:
        def parent = counted.steps(groupId(GROUP), new RunId(UUID.fromString(rootRun)), CAT_USER)
        def child = steps.steps(groupId(GROUP), new RunId(UUID.fromString(childRun)), CAT_USER)
        def oldChild = steps.steps(groupId(GROUP), new RunId(UUID.fromString(oldChildRun)), CAT_USER)
        def visible = StepReading.visibleRuns(new RunSnapshots(session, CodeStepsHeld.NONE, new SearchWays([]))
                .asRead(groupId(GROUP), new RunId(UUID.fromString(rootRun))))
        def parentQueries = statements.count { it.contains("turnaway.resent_at is not null as sent_again") }
        def childCostQueries = statements.count { it.contains("with recursive subtree (child_run_id, run_id)") }
        def childMemberQueries = statements.count { it.contains("entry.name as workflow_name") }
        statements.clear()
        def productions = StepReading.visibleProductions(new RunSnapshots(session, CodeStepsHeld.NONE, new SearchWays([]))
                .asRead(groupId(GROUP), new RunId(UUID.fromString(rootRun))))
        def mapped = counted.turnedAway(new RunId(UUID.fromString(rootRun)), visible, productions)

        then:
        parent.run().at().stepId() == UUID.fromString(parentStep)
        parent.steps()[1].beneath().runId() == UUID.fromString(childRun)
        parent.steps()[1].beneath().number() == 42
        parent.steps()[1].beneath().runs() == new StepAnswers.RunsAnswer("workflow", UUID.fromString(childEntry),
                "Demo Sample child", 1, UUID.fromString(childVersion), null, null, null)
        parent.steps()[1].beneath().at() == new StepAnswers.BeneathAtAnswer(UUID.fromString(childRun), 42,
                UUID.fromString(childStep), "ask")
        parent.steps()[1].cost() == new StepAnswers.CostAnswer(false, null, null, null, null, null)
        child.steps()[0].beneath() == null
        childCostQueries == 1
        childMemberQueries == 1
        parent.steps()[0].state() == "done"
        parent.steps()[0].where() == null
        parent.steps()[1].state() == "held_back"
        parent.steps()[1].where().reason() == "turned_away"
        parent.steps()[1].where().stopped().by() == STEWARD_ANSWER
        parent.steps()[1].where().turnedAway()*.said() == ["Demo Sample busy", "Demo Sample spent"]
        parent.steps()[1].where().turnedAway()*.sentAgain() == [true, false]
        parent.steps()[1].next() == null
        parent.steps()[1].acts().empty
        child.steps()[0].where().turnedAway() == parent.steps()[1].where().turnedAway()
        oldChild.steps()[0].state() == "done"
        visible*.run() == [new RunId(UUID.fromString(rootRun)), new RunId(UUID.fromString(childRun))]
        productions == [new ProductionId(UUID.fromString(production))]
        mapped.keySet() == [new ProductionId(UUID.fromString(production))] as Set
        !mapped.containsKey(new ProductionId(UUID.fromString(oldProduction)))
        parentQueries == 1
        statements.count { it.contains("turnaway.resent_at is not null as sent_again") } == 1
        statements.find { it.contains("turnaway.resent_at is not null as sent_again") }
                .contains("call.run_id = any(cast(")
        statements.find { it.contains("turnaway.resent_at is not null as sent_again") }
                .contains("call.production_id = any(cast(")
    }

    def "a run's steps are read in as many statements however many steps its version holds"() {
        given:
        def statements = []
        def counting = new CountingDataSource(store.database, statements)
        def session = JdbcClient.create(counting)
        def counted = new RunSteps(session, new GroupRoles(session), new RunSnapshots(session, CodeStepsHeld.NONE, new SearchWays([])),
                store.transactionManager(counting), Deployments.DEEP_ENOUGH, new org.lilradish.lite.app.currency.GroupCurrencies(session, new org.springframework.transaction.support.TransactionTemplate(store.transactionManager(counting)), new GroupRoles(session), org.lilradish.lite.testutil.library.DeployedModels.HELD))

        when:
        counted.steps(groupId(GROUP), runId(), ANN_USER)
        def few = statements.size()
        20.times { added ->
            def asked = UUID.randomUUID().toString()
            StepRows.question(store, UUID.randomUUID().toString(), asked, GROUP, "Question " + added)
            StepRows.field(store, [id: UUID.randomUUID().toString(), owner: asked, ownerKind: "question",
                                   side: "gives", position: 1, name: "answer", mustBe: true, standing: "always"])
            StepRows.step(store, UUID.randomUUID().toString(), VERSION, 3 + added, "more_" + added, asked, "person", 1)
        }
        statements.clear()
        counted.steps(groupId(GROUP), runId(), ANN_USER)

        then:
        statements.size() == few
        few == READ_IN
    }

    private UUID heldCodeFile(String run, String group) {
        def field = new FillField(new FieldName("attachment"), null, null, FieldKind.FILE, null, null, true,
                null, [], [new MediaTypeName("text/plain")], 100L)
        def path = new FillPath([new FillPath.Named(new FieldName("attachment"))])
        def marked = new JsonValue.JsonObject([new JsonValue.JsonMember("attachment",
                new JsonValue.JsonObject([new JsonValue.JsonMember("upload", new JsonValue.JsonNumber(0G)),
                        new JsonValue.JsonMember("name", new JsonValue.JsonString("Demo Sample.txt"))]))])
        def batch = EngineWiring.files(store).begin(new RunId(UUID.fromString(run)), groupId(group), field)
        batch.upload(0, 0, "Demo Sample.txt", "text/plain", new ByteArrayInputStream("file bytes".bytes))
        def filled = FileFillings.of(new RunId(UUID.fromString(run)), groupId(group), [field], marked,
                [(path): batch.finish()])
        StoredFile.fromKept(filled.values().members()[0].value()).id()
    }

    def "historical and live code inputs preserve source content permissions when the current release takes a file"() {
        given:
        CodeWorkflow.seed(store, 21)
        CodeWorkflow.run(store)
        def file = store.transactions().execute { status ->
            heldCodeFile(CodeWorkflow.RUN, CodeWorkflow.GROUP)
        }
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        def stored = store.session.sql("""
                select original_name, media_type, byte_size from stored_files where file_id = ?::uuid
                """).params(file).query { result, row ->
            new StoredFile(file, new org.lilradish.lite.domain.file.FileName(result.getString(1)),
                    new MediaTypeName(result.getString(2)), result.getLong(3))
        }.single()
        def source = storedFileSource(stored, codeSource)
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                update bindings set source_step_id = ?::uuid, source_path = 'attachment'
                 where binding_id = ?::uuid
                """).params(source.step, CodeWorkflow.TICKET_IN).update()
        String held = null
        store.transactions().executeWithoutResult {
            held = codeRunStep(CodeWorkflow.RUN)
            if (historical) {
                def production = codeTry(CodeWorkflow.RUN, held, 1, false, source)
                // DB-SPECIFIC: PostgreSQL ::jsonb/::uuid casts.
                store.session.sql("""
                        insert into production_values (production_id, run_step_kind, producer, field_name,
                                                       field_standing, value)
                        values (?::uuid, 'code_step', 'code', 'receipt', 'always', '"Demo Sample result"'::jsonb)
                        """).params(production).update()
            }
        }
        def shape = new FieldShape.File([stored.type()], 100L)
        def current = new SpecCodeStep(CodeWorkflow.CODE_STEP, [SpecCodeStep.given("reply", shape)],
                [CodeWorkflow.RECEIPT], false)
        def declarations = codeSource ? CodeStepsHeld.of(current, source.code) : CodeStepsHeld.of(current)
        def snapshots = new RunSnapshots(store.session, declarations, new SearchWays([]))
        def currentSteps = new RunSteps(store.session, new GroupRoles(store.session), snapshots,
                store.transactionManager(), Deployments.DEEP_ENOUGH, new org.lilradish.lite.app.currency.GroupCurrencies(store.session, new org.springframework.transaction.support.TransactionTemplate(store.transactionManager()), new GroupRoles(store.session), org.lilradish.lite.testutil.library.DeployedModels.HELD))
        def noModels = EnumSet.of(GroupPermission.READ_ALL_RUNS)
        def withModels = EnumSet.of(GroupPermission.READ_ALL_RUNS, GroupPermission.READ_INFERENCE_CONTENT)

        when:
        def read = [noModels, withModels].collect { permitted ->
            store.transactions().execute { status ->
                def context = snapshots.inTreeAsRead(groupId(CodeWorkflow.GROUP), new RunId(UUID.fromString(CodeWorkflow.RUN)))
                def reading = new StepReading(context, 1, true, permitted, null, Optional.empty(),
                        [:], [:], [:], [:], Deployments.DEEP_ENOUGH)
                        .withHeldFiles(currentSteps.heldFiles(context.run().run(), context, stepId(CodeWorkflow.SEND)))
                reading.step(stepId(CodeWorkflow.SEND)).wentIn()[0]
            }
        }

        then:
        read[1].withheld() == null
        read[1].value().get("id").asString() == file.toString()
        if (codeSource) {
            read[0].withheld() == null
            read[0].value() == read[1].value()
        } else {
            read[0].withheld() == true
            read[0].value() == null
        }
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("select count(*) from run_file_holders where run_id = ?::uuid and file_id = ?::uuid")
                .params(CodeWorkflow.RUN, file).query(Long).single() == 1L

        where:
        [codeSource, historical] << [[false, true], [false, true]].combinations()
    }

    def "an old code step reads a persisted composite by each saved source and conceals an unknown one"() {
        given:
        CodeWorkflow.seed(store, 21)
        CodeWorkflow.run(store)
        def childEntry = UUID.randomUUID().toString()
        def childVersion = UUID.randomUUID().toString()
        def childRun = UUID.randomUUID().toString()
        def parentStep = UUID.randomUUID().toString()
        StepRows.workflow(store, childEntry, childVersion, CodeWorkflow.GROUP, "Demo Sample sources")
        RunRows.beneath(store, childRun, CodeWorkflow.RUN, CodeWorkflow.GROUP, 2,
                childEntry, childVersion, parentStep, UUID.randomUUID().toString(), 0)
        def customer = UUID.randomUUID().toString()
        StepRows.field(store, [id: customer, owner: childVersion, ownerKind: "workflow",
                               side: "gives", position: 1, name: "customer", kind: "fields", mustBe: true])
        ["id", "secret"].eachWithIndex { name, index ->
            StepRows.field(store, [id: UUID.randomUUID().toString(), owner: childVersion, ownerKind: "workflow",
                                   side: "gives", parent: customer, position: index + 1, name: name, mustBe: true])
        }
        def sources = [:]
        [[name: "id", producer: "person", text: "123"],
         [name: "secret", producer: secretProducer, text: "Demo Sample private"]].eachWithIndex { made, index ->
            def question = UUID.randomUUID().toString()
            def version = UUID.randomUUID().toString()
            def field = UUID.randomUUID().toString()
            def step = UUID.randomUUID().toString()
            StepRows.question(store, question, version, CodeWorkflow.GROUP, "Demo Sample " + made.name)
            StepRows.field(store, [id: field, owner: version, ownerKind: "question", side: "gives",
                                   position: 1, name: "answer", mustBe: true, standing: "always"])
            StepRows.step(store, step, childVersion, index + 1, "ask_" + made.name, version, made.producer, 1)
            // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, RETURNING.
            def held = store.session.sql("""
                    insert into run_steps (run_id, entry_version_id, workflow_step_id, step_kind,
                                           pinned_version_id, pinned_kind, producer, reviewed_by_model, tries,
                                           created_by, created_by_kind)
                    select ?::uuid, step.entry_version_id, step.workflow_step_id, step.kind,
                           step.pinned_version_id, step.pinned_kind, step.producer, false, step.tries, ?::uuid, 'system'
                      from workflow_steps step where step.workflow_step_id = ?::uuid
                    returning run_step_id::text
                    """).params(childRun, RunRows.WORKFLOW_RUNNER, step).query(String).single()
            // DB-SPECIFIC: PostgreSQL ::step_producer/::subject_kind/::text/::uuid casts, now(), RETURNING.
            def production = store.session.sql("""
                    insert into productions (run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                                             reviewed_by_model, tries, pinned_version_id, try_number, producer,
                                             created_by, created_by_kind, ended_at, ended_by, ended_by_kind, explanation)
                    values (?::uuid, ?::uuid, ?::uuid, 'question', ?::step_producer, false, 1,
                            ?::uuid, 1, ?::step_producer, ?::uuid, 'system',
                            case when ?::step_producer = 'person' then now() end, ?::uuid,
                            ?::subject_kind, ?) returning production_id::text
                    """).params(held, childRun, CodeWorkflow.RUN, made.producer, version, made.producer,
                    RunRows.WORKFLOW_RUNNER, made.producer,
                    made.producer == "model" ? null : CodeWorkflow.CAT,
                    made.producer == "model" ? "system" : "person",
                    made.producer == "model" ? null : "Demo Sample answer").query(String).single()
            if (made.producer == "model") {
                // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, RETURNING.
                def attempt = store.session.sql("""
                        insert into run_step_send_attempts (run_step_id, run_id, run_step_kind, workflow_step_id,
                                                            purpose, model, mode, production_id, payload,
                                                            created_by, created_by_kind)
                        values (?::uuid, ?::uuid, 'question', ?::uuid, 'produce', 'general', 'ordinary',
                                ?::uuid, '{}', ?::uuid, 'system') returning run_step_send_attempt_id::text
                        """).params(held, childRun, step, production, RunRows.WORKFLOW_RUNNER).query(String).single()
                // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, now(), RETURNING.
                def call = store.session.sql("""
                        insert into model_calls (run_id, root_run_id, purpose, run_step_send_attempt_id,
                                                 run_step_id, production_id, model, mode, envelope_version,
                                                 sent_count, came_back_count, outcome, answer, ended_at, created_by)
                        values (?::uuid, ?::uuid, 'produce', ?::uuid, ?::uuid, ?::uuid, 'general', 'ordinary',
                                3, 1, 1, 'came_back', '{}', now(), ?::uuid) returning model_call_id::text
                        """).params(childRun, CodeWorkflow.RUN, attempt, held, production,
                        RunRows.WORKFLOW_RUNNER).query(String).single()
                // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
                store.session.sql("""
                        update productions set model_call_id = ?::uuid, model_call_outcome = 'came_back',
                                               ended_at = now(), ended_by = ?::uuid, ended_by_kind = 'system'
                         where production_id = ?::uuid
                        """).params(call, RunRows.WORKFLOW_RUNNER, production).update()
            }
            // DB-SPECIFIC: PostgreSQL ::jsonb/::step_producer/::text/::uuid casts, RETURNING.
            def value = store.session.sql("""
                    insert into production_values (production_id, run_step_kind, producer, pinned_version_id,
                                                   declaration_field_id, field_standing, value)
                    values (?::uuid, 'question', ?::step_producer, ?::uuid, ?::uuid, 'always', ?::jsonb)
                    returning production_value_id::text
                    """).params(production, made.producer, version, field,
                    '"' + made.text + '"').query(String).single()
            StepRows.binding(store, UUID.randomUUID().toString(), childVersion, null,
                    "customer." + made.name, step, "answer")
            sources[made.name] = [step: step, held: held, version: version, production: production, value: value]
        }
        if (sourceOpen) {
            // DB-SPECIFIC: PostgreSQL ::step_producer/::uuid casts.
            store.session.sql("""
                    insert into productions (run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                                             reviewed_by_model, tries, pinned_version_id, try_number, producer,
                                             created_by, created_by_kind)
                    values (?::uuid, ?::uuid, ?::uuid, 'question', ?::step_producer, false, 1,
                            ?::uuid, 2, 'person', ?::uuid, 'person')
                    """).params(sources.secret.held, childRun, CodeWorkflow.RUN, secretProducer,
                    sources.secret.version, CodeWorkflow.CAT).update()
        }
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                update bindings set source_step_id = ?::uuid, source_path = 'customer'
                 where binding_id = ?::uuid
                """).params(parentStep, CodeWorkflow.TICKET_IN).update()
        if (missingSource) {
            def heldCode = codeRunStep(CodeWorkflow.RUN)
            // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, RETURNING.
            def codeTry = store.session.sql("""
                    insert into productions (run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                                             reviewed_by_model, tries, may_run_again, try_number, producer,
                                             created_by, created_by_kind)
                    values (?::uuid, ?::uuid, ?::uuid, 'code_step', 'code', false, 21, true, 1, 'code',
                            ?::uuid, 'system') returning production_id::text
                    """).params(heldCode, CodeWorkflow.RUN, CodeWorkflow.RUN, RunRows.WORKFLOW_RUNNER)
                    .query(String).single()
            // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
            def parentHeld = store.texts("select parent_run_step_id::text from runs where run_id = ?::uuid", childRun)[0]
            // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, RETURNING.
            def taken = store.session.sql("""
                    insert into production_inputs (production_id, run_id, root_run_id, run_step_id, run_step_kind,
                                                   workflow_step_id, binding_id, binding_reads_a_constant,
                                                   source_workflow_step_id, source_run_step_id, source_run_step_kind)
                    values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'code_step', ?::uuid, ?::uuid, false,
                            ?::uuid, ?::uuid, 'workflow')
                    returning production_input_id::text
                    """).params(codeTry, CodeWorkflow.RUN, CodeWorkflow.RUN, heldCode, CodeWorkflow.SEND,
                    CodeWorkflow.TICKET_IN, parentStep, parentHeld).query(String).single()
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("""
                    insert into production_input_parts (production_input_id, root_run_id, target_path,
                                                        source_production_id, source_production_value_id)
                    values (?::uuid, ?::uuid, 'id', ?::uuid, ?::uuid),
                           (?::uuid, ?::uuid, 'secret', null, null)
                    """).params(taken, CodeWorkflow.RUN, sources.id.production, sources.id.value,
                    taken, CodeWorkflow.RUN).update()
        }
        def run = new RunId(UUID.fromString(CodeWorkflow.RUN))
        def current = new SpecCodeStep(CodeWorkflow.CODE_STEP,
                [SpecCodeStep.given("reply", new FieldShape.Text(2000)),
                 SpecCodeStep.given("attachment", new FieldShape.File([new MediaTypeName("text/plain")], 100L))],
                [CodeWorkflow.RECEIPT], true)
        def fromStorage = new RunSnapshots(store.session,
                fileDeclaration ? CodeStepsHeld.of(current) : CodeStepsHeld.NONE, new SearchWays([]))
        def reading = new RunSteps(store.session, new GroupRoles(store.session), fromStorage,
                store.transactionManager(), Deployments.DEEP_ENOUGH,
                new org.lilradish.lite.app.currency.GroupCurrencies(store.session,
                        new org.springframework.transaction.support.TransactionTemplate(store.transactionManager()),
                        new GroupRoles(store.session), DeployedModels.HELD))

        when:
        def visible = reading.step(groupId(CodeWorkflow.GROUP), run, stepId(CodeWorkflow.SEND), CodeWorkflow.CAT_USER)
        def visibleParent = reading.steps(groupId(CodeWorkflow.GROUP), run, CodeWorkflow.CAT_USER)
                .steps().find { it.stepId() == UUID.fromString(parentStep) }
        def response = missingSource ? MockMvcBuilders.standaloneSetup(new RunStepsController(reading)).build()
                .perform(get("/api/groups/${CodeWorkflow.GROUP}/runs/${CodeWorkflow.RUN}/steps/${CodeWorkflow.SEND}")
                        .requestAttr(CallerAdmission.class.name + ".caller", CodeWorkflow.CAT_USER)
                        .requestAttr(ActAdmission.class.name + ".group", groupId(CodeWorkflow.GROUP)))
                .andReturn() : null
        SpyStatic(GroupReach, MockMakers.mockito)
        def permissions = EnumSet.copyOf(GroupRole.OPERATOR.permissions())
        permissions.remove(GroupPermission.READ_INFERENCE_CONTENT)
        GroupReach.reachedBy(_ as Set) >> permissions
        def hidden = reading.step(groupId(CodeWorkflow.GROUP), run, stepId(CodeWorkflow.SEND), CodeWorkflow.CAT_USER)
        def hiddenParent = reading.steps(groupId(CodeWorkflow.GROUP), run, CodeWorkflow.CAT_USER)
                .steps().find { it.stepId() == UUID.fromString(parentStep) }

        then:
        visible.wentInFrom() == (missingSource ? "try" : "not_yet_sent")
        visible.wentIn()[0].earlierShape() == (missingSource ? null : true)
        visible.wentIn()[0].withheld() == (missingSource ? true : null)
        visible.wentIn()[0].value() == (missingSource ? null :
                NODES.stringNode('{"id":"123","secret":"Demo Sample private"}'))
        hidden.wentIn()[0].earlierShape() == (missingSource ? null : true)
        hidden.wentIn()[0].withheld() == (missingSource ? true : null)
        hidden.wentIn()[0].value() == (missingSource ? null : NODES.stringNode(secretProducer == "model"
                ? '{"id":"123","secret":{"withheld":true}}'
                : '{"id":"123","secret":"Demo Sample private"}'))
        if (missingSource) {
            assert hidden.wentIn()[0].sources() == null
            assert response.response.status == 200
            def sent = JSON.readTree(response.response.contentAsString).get("wentIn").get(0)
            assert sent.get("input").asString() == "reply"
            assert sent.get("from").get("kind").asString() == "step"
            assert sent.get("from").get("path").asString() == "customer"
            assert sent.get("withheld").asBoolean()
            assert !sent.has("earlierShape")
            assert !sent.has("value")
            assert !sent.has("withheldParts")
        } else {
            assert hidden.wentIn()[0].sources().collectEntries { [(it.targetPath()): it.from().stepId()] } ==
                    [id: UUID.fromString(sources.id.step), secret: UUID.fromString(sources.secret.step)]
        }
        if (sourceOpen) {
            assert visibleParent.gaveBack() == null
            assert hiddenParent.gaveBack() == null
        } else {
            assert visibleParent.gaveBack()[0].value() == NODES.objectNode()
                    .put("id", "123").put("secret", "Demo Sample private")
            assert hiddenParent.gaveBack()[0].value() == (secretProducer == "model"
                    ? NODES.objectNode().put("id", "123").set("secret", NODES.objectNode().put("withheld", true))
                    : NODES.objectNode().put("id", "123").put("secret", "Demo Sample private"))
        }
        secretProducer != "model" || !hidden.toString().contains("Demo Sample private")
        secretProducer != "model" || !hiddenParent.toString().contains("Demo Sample private")

        where:
        secretProducer | missingSource | sourceOpen | fileDeclaration
        "model"        | false         | false      | false
        "person"       | false         | false      | false
        "model"        | true          | false      | false
        "model"        | true          | true       | false
        "person"       | true          | true       | false
        "model"        | true          | true       | true
        "person"       | true          | true       | true
    }

    private Map storedFileSource(StoredFile stored, boolean codeSource, int tries = 1) {
        def sourceStep = UUID.randomUUID().toString()
        def question = UUID.randomUUID().toString()
        def version = UUID.randomUUID().toString()
        def field = UUID.randomUUID().toString()
        def sourceCode = new SpecCodeStep("metadata_source", [],
                [SpecCodeStep.standing("attachment", new FieldShape.File([stored.type()], 100L))], tries > 1)
        if (codeSource) {
            // DB-SPECIFIC: PostgreSQL ALTER TYPE enum ADD VALUE.
            store.session.sql("alter type code_step add value if not exists 'metadata_source'").update()
            StepRows.codeStep(store, sourceStep, CodeWorkflow.VERSION, 0, "source", "metadata_source", "code", tries)
        } else {
            StepRows.question(store, question, version, CodeWorkflow.GROUP, "Demo Sample source")
            StepRows.field(store, [id: field, owner: version, ownerKind: "question", side: "gives",
                                  position: 1, name: "attachment", kind: "fields", mustBe: true, standing: "always"])
            ["id", "name", "type", "size"].eachWithIndex { name, position ->
                StepRows.field(store, [id: UUID.randomUUID().toString(), owner: version, ownerKind: "question",
                                      side: "gives", parent: field, position: position + 1, name: name,
                                      kind: name == "size" ? "number" : "text", mustBe: true])
            }
            StepRows.step(store, sourceStep, CodeWorkflow.VERSION, 0, "source", version, "model", 1)
        }
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, RETURNING.
        def held = store.session.sql("""
                insert into run_steps (run_id, entry_version_id, workflow_step_id, step_kind, pinned_version_id,
                                       pinned_kind, producer, reviewed_by_model, tries, created_by, created_by_kind)
                select ?::uuid, step.entry_version_id, step.workflow_step_id, step.kind, step.pinned_version_id,
                       step.pinned_kind, step.producer, step.reviewed_by_model, step.tries, ?::uuid, 'system'
                  from workflow_steps step where step.workflow_step_id = ?::uuid returning run_step_id::text
                """).params(CodeWorkflow.RUN, RunRows.WORKFLOW_RUNNER, sourceStep).query(String).single()
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, RETURNING, enum type run_step_kind, enum type
        // step_producer.
        def production = store.session.sql("""
                insert into productions (run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                                         reviewed_by_model, tries, pinned_version_id, may_run_again, try_number,
                                         producer, created_by, created_by_kind)
                values (?::uuid, ?::uuid, ?::uuid, cast(? as run_step_kind), cast(? as step_producer), false, ?,
                        ?::uuid, ?, 1, cast(? as step_producer), ?::uuid, 'system') returning production_id::text
                """).params(held, CodeWorkflow.RUN, CodeWorkflow.RUN, codeSource ? 'code_step' : 'question',
                codeSource ? 'code' : 'model', tries, codeSource ? null : version, codeSource ? tries > 1 : null,
                codeSource ? 'code' : 'model', RunRows.WORKFLOW_RUNNER).query(String).single()
        if (codeSource) {
            // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
            store.session.sql("update productions set ended_at = now(), ended_by = ?::uuid where production_id = ?::uuid")
                    .params(RunRows.WORKFLOW_RUNNER, production).update()
        } else {
            // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, RETURNING.
            def attempt = store.session.sql("""
                    insert into run_step_send_attempts (run_step_id, run_id, run_step_kind, workflow_step_id,
                                                        purpose, model, mode, production_id, payload, created_by,
                                                        created_by_kind)
                    values (?::uuid, ?::uuid, 'question', ?::uuid, 'produce', 'general', 'ordinary', ?::uuid,
                            '{}', ?::uuid, 'system') returning run_step_send_attempt_id::text
                    """).params(held, CodeWorkflow.RUN, sourceStep, production, RunRows.WORKFLOW_RUNNER)
                    .query(String).single()
            // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, now(), RETURNING.
            def call = store.session.sql("""
                    insert into model_calls (run_id, root_run_id, purpose, run_step_send_attempt_id, run_step_id,
                                             production_id, model, mode, envelope_version, sent_count, came_back_count,
                                             outcome, answer, ended_at, created_by, created_by_kind)
                    values (?::uuid, ?::uuid, 'produce', ?::uuid, ?::uuid, ?::uuid, 'general', 'ordinary', 3, 1, 1,
                            'came_back', '{}', now(), ?::uuid, 'system') returning model_call_id::text
                    """).params(CodeWorkflow.RUN, CodeWorkflow.RUN, attempt, held, production, RunRows.WORKFLOW_RUNNER)
                    .query(String).single()
            // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
            store.session.sql("""
                    update productions set model_call_id = ?::uuid, model_call_outcome = 'came_back',
                                           ended_at = now(), ended_by = ?::uuid where production_id = ?::uuid
                    """).params(call, RunRows.WORKFLOW_RUNNER, production).update()
        }
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, RETURNING, enum type run_step_kind, enum type
        // step_producer, jsonb type.
        def value = store.session.sql("""
                insert into production_values (production_id, run_step_kind, producer, pinned_version_id,
                                               declaration_field_id, field_name, field_standing, value)
                values (?::uuid, cast(? as run_step_kind), cast(? as step_producer), ?::uuid, ?::uuid, ?,
                        'always', cast(? as jsonb)) returning production_value_id::text
                """).params(production, codeSource ? 'code_step' : 'question', codeSource ? 'code' : 'model',
                codeSource ? null : version, codeSource ? null : field, codeSource ? 'attachment' : null,
                CanonicalJson.write(stored.kept())).query(String).single()
        [step: sourceStep, held: held, production: production, value: value, code: sourceCode,
         kind: codeSource ? 'code_step' : 'question']
    }

    private StoredFile storedFile(UUID id) {
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("select original_name, media_type, byte_size from stored_files where file_id = ?::uuid")
                .params(id).query { result, row ->
            new StoredFile(id, new org.lilradish.lite.domain.file.FileName(result.getString(1)),
                    new MediaTypeName(result.getString(2)), result.getLong(3))
        }.single()
    }

    private Map codeChildWithSiblings(int siblings) {
        def entry = UUID.randomUUID().toString()
        def version = UUID.randomUUID().toString()
        def parent = UUID.randomUUID().toString()
        def parentStep = UUID.randomUUID().toString()
        RunRows.workflow(store, entry, version, CodeWorkflow.GROUP, "Demo Sample parent", null, false)
        RunRows.run(store, parent, CodeWorkflow.GROUP, 2, "Demo Sample parent", entry, version, CodeWorkflow.CAT)
        RunRows.beneath(store, CodeWorkflow.RUN, parent, CodeWorkflow.GROUP, 1, CodeWorkflow.WORKFLOW,
                CodeWorkflow.VERSION, parentStep, UUID.randomUUID().toString())
        def file = store.transactions().execute { heldCodeFile(CodeWorkflow.RUN, CodeWorkflow.GROUP) }
        StepRows.binding(store, UUID.randomUUID().toString(), version, parentStep, "ticket", null, null,
                CanonicalJson.write(storedFile(file).kept()))
        def unrelated = []
        siblings.times { index ->
            def sibling = UUID.randomUUID().toString()
            def siblingStep = UUID.randomUUID().toString()
            RunRows.beneath(store, sibling, parent, CodeWorkflow.GROUP, index + 3, CodeWorkflow.WORKFLOW,
                    CodeWorkflow.VERSION, siblingStep, UUID.randomUUID().toString(), index + 2)
            def other = store.transactions().execute { heldCodeFile(sibling, CodeWorkflow.GROUP) }
            unrelated.add(other)
            StepRows.binding(store, UUID.randomUUID().toString(), version, siblingStep, "ticket", null, null,
                    CanonicalJson.write(new JsonValue.JsonObject([new JsonValue.JsonMember("history",
                            new JsonValue.JsonArray([storedFile(other).kept()]))])))
            def held = codeRunStep(sibling)
            // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, now(), RETURNING.
            def production = store.session.sql("""
                    insert into productions (run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                                             reviewed_by_model, tries, may_run_again, try_number, producer,
                                             created_by, created_by_kind, ended_at, ended_by)
                    values (?::uuid, ?::uuid, ?::uuid, 'code_step', 'code', false, 21, true, 1, 'code',
                            ?::uuid, 'system', now(), ?::uuid) returning production_id::text
                    """).params(held, sibling, parent, RunRows.WORKFLOW_RUNNER, RunRows.WORKFLOW_RUNNER)
                    .query(String).single()
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("""
                    insert into production_inputs (production_id, run_id, root_run_id, run_step_id, run_step_kind,
                                                   workflow_step_id, binding_id, binding_reads_a_constant)
                    values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'code_step', ?::uuid, ?::uuid, false)
                    """).params(production, sibling, parent, held, CodeWorkflow.SEND, CodeWorkflow.TICKET_IN).update()
            // DB-SPECIFIC: PostgreSQL ::uuid casts, jsonb type.
            store.session.sql("""
                    insert into production_values (production_id, run_step_kind, producer, field_name, field_standing, value)
                    values (?::uuid, 'code_step', 'code', 'receipt', 'always', cast(? as jsonb))
                    """).params(production, CanonicalJson.write(new JsonValue.JsonObject([
                            new JsonValue.JsonMember("history", new JsonValue.JsonArray([storedFile(other).kept()]))]))).update()
        }
        [file: file, unrelated: unrelated, parentVersion: version, parentStep: parentStep]
    }

    def "requested code file candidates exclude unrelated sibling JSON and skip holders without file declarations"() {
        given:
        CodeWorkflow.seed(store, 21)
        def fixture = codeChildWithSiblings(siblings)
        def shape = files ? new FieldShape.File([new MediaTypeName("text/plain")], 100L) : new FieldShape.Text(2000)
        def current = new SpecCodeStep(CodeWorkflow.CODE_STEP, [SpecCodeStep.given("reply", shape)],
                [CodeWorkflow.RECEIPT], false)
        def statements = []
        def counting = new CountingDataSource(store.database, statements)
        def session = JdbcClient.create(counting)
        def snapshots = new RunSnapshots(session, CodeStepsHeld.of(current), new SearchWays([]))
        def counted = new RunSteps(session, new GroupRoles(session), snapshots,
                store.transactionManager(counting), Deployments.DEEP_ENOUGH, new org.lilradish.lite.app.currency.GroupCurrencies(session, new org.springframework.transaction.support.TransactionTemplate(store.transactionManager(counting)), new GroupRoles(session), org.lilradish.lite.testutil.library.DeployedModels.HELD))
        def run = new RunId(UUID.fromString(CodeWorkflow.RUN))
        def context = snapshots.inTreeAsRead(groupId(CodeWorkflow.GROUP), run)

        when:
        def candidates = counted.fileCandidates(context, stepId(CodeWorkflow.SEND))
        def allCandidates = counted.fileCandidates(context, null)
        statements.clear()
        def page = counted.steps(groupId(CodeWorkflow.GROUP), run, CodeWorkflow.CAT_USER)
        def pageHolders = statements.count { it.contains('from run_file_holders holder') }
        statements.clear()
        def selected = counted.step(groupId(CodeWorkflow.GROUP), run, stepId(CodeWorkflow.SEND), CodeWorkflow.CAT_USER)

        then:
        candidates == (files ? [fixture.file] as Set : [] as Set)
        allCandidates == candidates
        candidates.disjoint(fixture.unrelated)
        page.steps().size() == 2
        pageHolders == (files ? 1 : 0)
        statements.count { it.contains('from run_file_holders holder') } == (files ? 1 : 0)
        selected.wentInFrom() == "not_yet_sent"
        if (files) {
            selected.wentIn()[0].value().get("id").asString() == fixture.file.toString()
        } else {
            selected.wentIn()[0].value() == NODES.stringNode(CanonicalJson.write(
                    StepInputs.bound(context, context.run().steps()[0]).orElseThrow()[0].value()))
        }
        selected.wentIn()[0].earlierShape() == (files ? null : true)

        where:
        [files, siblings] << [[false, true], [1, 8]].combinations()
    }

    def "file candidates follow the saved maker while an untried code input follows the new standing maker"() {
        given:
        CodeWorkflow.seed(store, 21)
        CodeWorkflow.run(store)
        def first = store.transactions().execute { heldCodeFile(CodeWorkflow.RUN, CodeWorkflow.GROUP) }
        def second = store.transactions().execute { heldCodeFile(CodeWorkflow.RUN, CodeWorkflow.GROUP) }
        def source = storedFileSource(storedFile(first), true, 2)
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("update bindings set source_step_id = ?::uuid, source_path = 'attachment' where binding_id = ?::uuid")
                .params(source.step, CodeWorkflow.TICKET_IN).update()
        if (historical) {
            store.transactions().executeWithoutResult {
                def held = codeRunStep(CodeWorkflow.RUN)
                def production = codeTry(CodeWorkflow.RUN, held, 1, false, source)
                // DB-SPECIFIC: PostgreSQL ::jsonb/::uuid casts.
                store.session.sql("""
                        insert into production_values (production_id, run_step_kind, producer, field_name,
                                                       field_standing, value)
                        values (?::uuid, 'code_step', 'code', 'receipt', 'always', '"Demo Sample receipt"'::jsonb)
                        """).params(production).update()
            }
        }
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, now(), RETURNING.
        def latest = store.session.sql("""
                insert into productions (run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                                         reviewed_by_model, tries, may_run_again, try_number, producer,
                                         created_by, created_by_kind, ended_at, ended_by)
                select run_step_id, run_id, root_run_id, run_step_kind, step_producer, reviewed_by_model,
                       2, true, 2, producer, created_by, created_by_kind, now(), ended_by
                  from productions where production_id = ?::uuid returning production_id::text
                """).params(source.production).query(String).single()
        // DB-SPECIFIC: PostgreSQL ::uuid casts, jsonb type.
        store.session.sql("""
                insert into production_values (production_id, run_step_kind, producer, field_name, field_standing, value)
                values (?::uuid, 'code_step', 'code', 'attachment', 'always', cast(? as jsonb))
                """).params(latest, CanonicalJson.write(storedFile(second).kept())).update()
        def current = new SpecCodeStep(CodeWorkflow.CODE_STEP,
                [SpecCodeStep.given("reply", new FieldShape.File([new MediaTypeName("text/plain")], 100L))],
                [CodeWorkflow.RECEIPT], false)
        def snapshots = new RunSnapshots(store.session, CodeStepsHeld.of(current, source.code), new SearchWays([]))
        def currentSteps = new RunSteps(store.session, new GroupRoles(store.session), snapshots,
                store.transactionManager(), Deployments.DEEP_ENOUGH, new org.lilradish.lite.app.currency.GroupCurrencies(store.session, new org.springframework.transaction.support.TransactionTemplate(store.transactionManager()), new GroupRoles(store.session), org.lilradish.lite.testutil.library.DeployedModels.HELD))
        def run = new RunId(UUID.fromString(CodeWorkflow.RUN))
        def context = snapshots.inTreeAsRead(groupId(CodeWorkflow.GROUP), run)

        when:
        def candidates = currentSteps.fileCandidates(context, stepId(CodeWorkflow.SEND))
        def selected = currentSteps.step(groupId(CodeWorkflow.GROUP), run, stepId(CodeWorkflow.SEND), CodeWorkflow.CAT_USER)

        then:
        candidates == [historical ? first : second] as Set
        !candidates.contains(historical ? second : first)
        selected.wentIn()[0].value().get("id").asString() == (historical ? first : second).toString()
        selected.wentIn()[0].earlierShape() == null
        selected.wentInFrom() == (historical ? "try" : "not_yet_sent")

        where:
        historical << [false, true]
    }

    def "composite ancestor inputs collect only current nested file paths and preserve requested holder checks"() {
        given:
        CodeWorkflow.seed(store, 21)
        def fixture = codeChildWithSiblings(1)
        def second = store.transactions().execute { heldCodeFile(CodeWorkflow.RUN, CodeWorkflow.GROUP) }
        def ticket = '0000000c-0000-4000-8000-000000000c01'
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("update declaration_fields set kind = 'fields', text_limit = null where declaration_field_id = ?::uuid")
                .params(ticket).update()
        def attachments = UUID.randomUUID().toString()
        StepRows.field(store, [id: attachments, owner: CodeWorkflow.VERSION, ownerKind: "workflow",
                              side: "takes", parent: ticket, position: 1, name: "attachments", kind: "fields", most: 2])
        ["id", "name", "type", "size"].eachWithIndex { name, position ->
            StepRows.field(store, [id: UUID.randomUUID().toString(), owner: CodeWorkflow.VERSION, ownerKind: "workflow",
                                  side: "takes", parent: attachments, position: position + 1, name: name,
                                  kind: name == "size" ? "number" : "text", mustBe: true])
        }
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: CodeWorkflow.VERSION, ownerKind: "workflow",
                              side: "takes", parent: ticket, position: 2, name: "note", kind: "text"])
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("delete from bindings where workflow_step_id = ?::uuid")
                .params(fixture.parentStep).update()
        def many = new JsonValue.JsonArray([storedFile(fixture.file).kept(), storedFile(second).kept()])
        def historical = many
        StepRows.binding(store, UUID.randomUUID().toString(), fixture.parentVersion, fixture.parentStep,
                "ticket.attachments", null, null, CanonicalJson.write(many))
        StepRows.binding(store, UUID.randomUUID().toString(), fixture.parentVersion, fixture.parentStep,
                "ticket.note", null, null, '"Demo Sample note"')
        def fileField = SpecCodeStep.given("attachments", new FieldShape.File([
                new MediaTypeName("text/plain"), new MediaTypeName("application/pdf")], 100L))
        def files = new Field(fileField.name(), null, null, fileField.shape(), new HowMany.Many(2), fileField.demand())
        def current = new SpecCodeStep(CodeWorkflow.CODE_STEP,
                [SpecCodeStep.given("reply", new FieldShape.Nested([files, SpecCodeStep.given("note", new FieldShape.Text(1000))]))],
                [CodeWorkflow.RECEIPT], false)
        def snapshots = new RunSnapshots(store.session, CodeStepsHeld.of(current), new SearchWays([]))
        def currentSteps = new RunSteps(store.session, new GroupRoles(store.session), snapshots,
                store.transactionManager(), Deployments.DEEP_ENOUGH, new org.lilradish.lite.app.currency.GroupCurrencies(store.session, new org.springframework.transaction.support.TransactionTemplate(store.transactionManager()), new GroupRoles(store.session), org.lilradish.lite.testutil.library.DeployedModels.HELD))
        def run = new RunId(UUID.fromString(CodeWorkflow.RUN))
        def context = snapshots.inTreeAsRead(groupId(CodeWorkflow.GROUP), run)
        def input = StepInputs.bound(context, context.run().steps()[0]).orElseThrow()[0]

        when:
        def candidates = currentSteps.fileCandidates(context, stepId(CodeWorkflow.SEND))
        def positive = currentSteps.step(groupId(CodeWorkflow.GROUP), run, stepId(CodeWorkflow.SEND), CodeWorkflow.CAT_USER)
        if (changed == "holder") {
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("delete from run_file_holders where run_id = ?::uuid and file_id = ?::uuid")
                    .params(CodeWorkflow.RUN, second).update()
        } else {
            def replacement = changed == "size" ? new JsonValue.JsonNumber(11G) : new JsonValue.JsonString(
                    changed == "name" ? "Demo Sample other.txt" : "application/pdf")
            def altered = new JsonValue.JsonObject(storedFile(second).kept().members().collect { member ->
                member.name() == changed ? new JsonValue.JsonMember(changed, replacement) : member
            })
            historical = new JsonValue.JsonArray([storedFile(fixture.file).kept(), altered])
            // DB-SPECIFIC: PostgreSQL ::uuid casts, jsonb type.
            store.session.sql("update bindings set constant = cast(? as jsonb) where workflow_step_id = ?::uuid and target_path = 'ticket.attachments'")
                    .params(CanonicalJson.write(historical),
                            fixture.parentStep).update()
        }
        def negative = currentSteps.step(groupId(CodeWorkflow.GROUP), run, stepId(CodeWorkflow.SEND), CodeWorkflow.CAT_USER)

        then:
        input.parts().size() == 2
        candidates == [fixture.file, second] as Set
        candidates.disjoint(fixture.unrelated)
        positive.wentIn()[0].earlierShape() == null
        positive.wentIn()[0].value().get("attachments").size() == 2
        positive.wentIn()[0].value().get("attachments").get(0).get("id").asString() == fixture.file.toString()
        positive.wentIn()[0].value().get("attachments").get(1).get("id").asString() == second.toString()
        positive.wentIn()[0].value().get("note").asString() == "Demo Sample note"
        negative.wentIn()[0].earlierShape() == true
        negative.wentIn()[0].withheld() == null
        negative.wentIn()[0].value() == NODES.stringNode(CanonicalJson.write(new JsonValue.JsonObject([
                new JsonValue.JsonMember("attachments", historical),
                new JsonValue.JsonMember("note", new JsonValue.JsonString("Demo Sample note"))])))
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        changed != "holder" || store.count("select count(*) from run_file_holders where run_id = ?::uuid and file_id = ?::uuid",
                CodeWorkflow.RUN, second) == 0

        where:
        changed << ["holder", "name", "type", "size"]
    }

    def "old person code output and last refused nested files remain visible beside a newer open try"() {
        given:
        CodeWorkflow.seed(store, 21)
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("delete from bindings where entry_version_id = ?::uuid and (workflow_step_id = ?::uuid or workflow_step_id is null)")
                .params(CodeWorkflow.VERSION, CodeWorkflow.CHECK).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("delete from workflow_steps where workflow_step_id = ?::uuid")
                .params(CodeWorkflow.CHECK).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("delete from declaration_fields where entry_version_id = ?::uuid and side = 'gives'")
                .params(CodeWorkflow.VERSION).update()
        CodeWorkflow.run(store)
        def first = store.transactions().execute { heldCodeFile(CodeWorkflow.RUN, CodeWorkflow.GROUP) }
        def second = store.transactions().execute { heldCodeFile(CodeWorkflow.RUN, CodeWorkflow.GROUP) }
        def ignored = store.transactions().execute { heldCodeFile(CodeWorkflow.RUN, CodeWorkflow.GROUP) }
        def held = codeRunStep(CodeWorkflow.RUN)
        def production = codeTry(CodeWorkflow.RUN, held, 1, false)
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                update productions set producer = 'person', created_by = ?::uuid, created_by_kind = 'person',
                                       ended_by = ?::uuid, ended_by_kind = 'person', explanation = 'Demo Sample reason'
                 where production_id = ?::uuid
                """).params(CodeWorkflow.CAT, CodeWorkflow.CAT, production).update()
        def fileField = SpecCodeStep.given("attachments", new FieldShape.File([new MediaTypeName("text/plain")], 100L))
        def files = new Field(fileField.name(), null, null, fileField.shape(), new HowMany.Many(2), fileField.demand())
        def ordinary = new FieldShape.Nested([
                SpecCodeStep.given("id", new FieldShape.Text(100)),
                SpecCodeStep.given("name", new FieldShape.Text(100)),
                SpecCodeStep.given("type", new FieldShape.Text(100)),
                SpecCodeStep.given("size", new FieldShape.Plain(FieldKind.NUMBER))])
        def receipt = SpecCodeStep.standing("receipt", new FieldShape.Nested([
                files, SpecCodeStep.given("ordinary", ordinary)]))
        def receipts = new Field(receipt.name(), null, null, receipt.shape(), new HowMany.Many(2), receipt.demand())
        def value = new JsonValue.JsonArray([new JsonValue.JsonObject([
                new JsonValue.JsonMember("attachments", new JsonValue.JsonArray([storedFile(first).kept()])),
                new JsonValue.JsonMember("ordinary", storedFile(ignored).kept())]), new JsonValue.JsonObject([
                new JsonValue.JsonMember("attachments", new JsonValue.JsonArray([storedFile(second).kept()])),
                new JsonValue.JsonMember("ordinary", storedFile(ignored).kept())])])
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, RETURNING, jsonb type.
        def kept = store.session.sql("""
                insert into production_values (production_id, run_step_kind, producer, field_name, field_standing, value)
                values (?::uuid, 'code_step', 'person', 'receipt', 'never', cast(? as jsonb))
                returning production_value_id::text
                """).params(production, CanonicalJson.write(value)).query(String).single()
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, RETURNING.
        def review = store.session.sql("""
                insert into reviews (production_id, run_step_id, root_run_id, production_ended_by,
                                     reviewed_by_model, created_by)
                values (?::uuid, ?::uuid, ?::uuid, ?::uuid, false, ?::uuid) returning review_id::text
                """).params(production, held, CodeWorkflow.RUN, CodeWorkflow.CAT, CodeWorkflow.ANN).query(String).single()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into review_decisions (review_id, production_value_id, production_id, for_length,
                                              value_needs_review, outcome, explanation)
                values (?::uuid, ?::uuid, ?::uuid, false, true, 'refused', 'Demo Sample refusal')
                """).params(review, kept, production).update()
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, RETURNING.
        def open = store.session.sql("""
                insert into productions (run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                                         reviewed_by_model, tries, may_run_again, try_number, producer,
                                         created_by, created_by_kind)
                values (?::uuid, ?::uuid, ?::uuid, 'code_step', 'code', false, 21, false, 2, 'person', ?::uuid, 'person')
                returning production_id::text
                """).params(held, CodeWorkflow.RUN, CodeWorkflow.RUN, CodeWorkflow.CAT).query(String).single()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into production_inputs (production_id, run_id, root_run_id, run_step_id, run_step_kind,
                                               workflow_step_id, binding_id, binding_reads_a_constant)
                values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'code_step', ?::uuid, ?::uuid, false)
                """).params(open, CodeWorkflow.RUN, CodeWorkflow.RUN, held, CodeWorkflow.SEND, CodeWorkflow.TICKET_IN).update()
        def current = new SpecCodeStep(CodeWorkflow.CODE_STEP, [CodeWorkflow.REPLY], [receipts], false)
        def snapshots = new RunSnapshots(store.session, CodeStepsHeld.of(current), new SearchWays([]))
        def currentSteps = new RunSteps(store.session, new GroupRoles(store.session), snapshots,
                store.transactionManager(), Deployments.DEEP_ENOUGH, new org.lilradish.lite.app.currency.GroupCurrencies(store.session, new org.springframework.transaction.support.TransactionTemplate(store.transactionManager()), new GroupRoles(store.session), org.lilradish.lite.testutil.library.DeployedModels.HELD))
        def run = new RunId(UUID.fromString(CodeWorkflow.RUN))
        def context = snapshots.inTreeAsRead(groupId(CodeWorkflow.GROUP), run)

        when:
        def candidates = currentSteps.fileCandidates(context, stepId(CodeWorkflow.SEND))
        def selected = currentSteps.step(groupId(CodeWorkflow.GROUP), run, stepId(CodeWorkflow.SEND), CodeWorkflow.CAT_USER)

        then:
        candidates == [first, second] as Set
        !candidates.contains(ignored)
        selected.triesMade().size() == 2
        selected.triesMade()[0].producedBy().kind() == "person"
        selected.triesMade()[0].ended() == "refused_on_review"
        selected.triesMade()[0].values()[0].earlierShape() == null
        selected.triesMade()[0].values()[0].value().get(0).get("attachments").get(0).get("id").asString() == first.toString()
        selected.triesMade()[0].values()[0].value().get(1).get("attachments").get(0).get("id").asString() == second.toString()
        selected.triesMade()[1].values() == []
        selected.answering().lastRefused().number() == 1
        selected.answering().lastRefused().values()[0].value() == selected.triesMade()[0].values()[0].value()
        selected.answering().lastRefused().values()[0].decision().why() == "Demo Sample refusal"
    }

    private String codeRunStep(String run) {
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, RETURNING.
        store.session.sql("""
                insert into run_steps (run_id, entry_version_id, workflow_step_id, step_kind,
                                       pinned_version_id, pinned_kind, producer, reviewed_by_model, tries,
                                       created_by, created_by_kind)
                select ?::uuid, step.entry_version_id, step.workflow_step_id, step.kind,
                       step.pinned_version_id, step.pinned_kind, step.producer, step.reviewed_by_model, step.tries,
                       ?::uuid, 'system'
                  from workflow_steps step where step.workflow_step_id = ?::uuid
                returning run_step_id::text
                """).params(run, RunRows.WORKFLOW_RUNNER, CodeWorkflow.SEND).query(String).single()
    }

    private String codeTry(String run, String heldStep, int number, boolean errored, Map source = [:]) {
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, now(), RETURNING, enum type try_lost_reason, enum type
        // code_error_reason.
        def production = store.session.sql("""
                insert into productions (run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                                         reviewed_by_model, tries, may_run_again, try_number, producer,
                                         created_by, created_by_kind, ended_at, ended_by, lost_reason,
                                         code_error_reason, code_error_path, returned_by_code)
                values (?::uuid, ?::uuid, ?::uuid, 'code_step', 'code', false, 21, true, ?, 'code',
                        ?::uuid, 'system', now(), ?::uuid,
                        cast(? as try_lost_reason), cast(? as code_error_reason), ?, ?)
                returning production_id::text
                """).params(heldStep, run, run, number, RunRows.WORKFLOW_RUNNER, RunRows.WORKFLOW_RUNNER,
                errored ? 'errored' : null, errored ? 'too_long' : null,
                errored ? 'receipt' : null, errored ? '{}' : null).query(String).single()
        // DB-SPECIFIC: PostgreSQL ::uuid casts, enum type run_step_kind.
        store.session.sql("""
                insert into production_inputs (production_id, run_id, root_run_id, run_step_id, run_step_kind,
                                               workflow_step_id, binding_id, binding_reads_a_constant,
                                               source_workflow_step_id, source_run_step_id, source_run_step_kind,
                                               source_production_id, source_production_value_id)
                values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'code_step', ?::uuid, ?::uuid, false,
                        ?::uuid, ?::uuid, cast(? as run_step_kind), ?::uuid, ?::uuid)
                """).params(production, run, run, heldStep, CodeWorkflow.SEND, CodeWorkflow.TICKET_IN,
                source.step, source.held, source.kind, source.production, source.value).update()
        production
    }

    def "kept code files are batch read once for many tries and never leak from another run or a yielded try"() {
        given:
        CodeWorkflow.seed(store, 21)
        def otherRun = UUID.randomUUID().toString()
        String held = null
        String otherHeld = null
        UUID file = null
        UUID otherFile = null
        store.transactions().executeWithoutResult {
            CodeWorkflow.run(store)
            StepRows.run(store, otherRun, CodeWorkflow.GROUP, 2, CodeWorkflow.WORKFLOW,
                    CodeWorkflow.VERSION, CodeWorkflow.CAT, CodeWorkflow.STARTED_WITH)
            held = codeRunStep(CodeWorkflow.RUN)
            otherHeld = codeRunStep(otherRun)
            file = heldCodeFile(CodeWorkflow.RUN, CodeWorkflow.GROUP)
            otherFile = heldCodeFile(otherRun, CodeWorkflow.GROUP)
        }
        def first = codeTry(CodeWorkflow.RUN, held, 1, true)
        def fromOther = codeTry(otherRun, otherHeld, 1, true)
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into errored_code_returned_files (production_id, run_id, file_id, field_path)
                values (?::uuid, ?::uuid, ?::uuid, 'attachment'),
                       (?::uuid, ?::uuid, ?::uuid, 'attachment')
                """).params(first, CodeWorkflow.RUN, file, fromOther, otherRun, otherFile).update()
        def statements = []
        def counting = new CountingDataSource(store.database, statements)
        def session = JdbcClient.create(counting)
        def counted = new RunSteps(session, new GroupRoles(session), new RunSnapshots(session, CodeStepsHeld.NONE, new SearchWays([])),
                store.transactionManager(counting), Deployments.DEEP_ENOUGH, new org.lilradish.lite.app.currency.GroupCurrencies(session, new org.springframework.transaction.support.TransactionTemplate(store.transactionManager(counting)), new GroupRoles(session), org.lilradish.lite.testutil.library.DeployedModels.HELD))

        when:
        def one = counted.steps(groupId(CodeWorkflow.GROUP), new RunId(UUID.fromString(CodeWorkflow.RUN)),
                CodeWorkflow.CAT_USER)
        def oneQueries = statements.count { it.contains('from errored_code_returned_files kept') }
        def few = statements.size()
        def oneStep = steps.step(groupId(CodeWorkflow.GROUP), new RunId(UUID.fromString(CodeWorkflow.RUN)),
                stepId(CodeWorkflow.SEND), CodeWorkflow.CAT_USER)
        statements.clear()
        20.times { added ->
            def asked = UUID.randomUUID().toString()
            StepRows.question(store, UUID.randomUUID().toString(), asked, CodeWorkflow.GROUP,
                    "Demo Sample question " + added)
            StepRows.field(store, [id: UUID.randomUUID().toString(), owner: asked, ownerKind: "question",
                                   side: "gives", position: 1, name: "answer", mustBe: true, standing: "always"])
            StepRows.step(store, UUID.randomUUID().toString(), CodeWorkflow.VERSION, 3 + added,
                    "more_" + added, asked, "person", 1)
        }
        (2..20).each { number ->
            def production = codeTry(CodeWorkflow.RUN, held, number, true)
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("""
                    insert into errored_code_returned_files (production_id, run_id, file_id, field_path)
                    values (?::uuid, ?::uuid, ?::uuid, 'attachment')
                    """).params(production, CodeWorkflow.RUN, file).update()
        }
        def yielded = codeTry(CodeWorkflow.RUN, held, 21, false)
        // DB-SPECIFIC: PostgreSQL ::jsonb/::uuid casts.
        store.session.sql("""
                insert into production_values (production_id, run_step_kind, producer, field_name,
                                               field_standing, value)
                values (?::uuid, 'code_step', 'code', 'receipt', 'always', '"R-21"'::jsonb)
                """).params(yielded).update()
        def many = counted.steps(groupId(CodeWorkflow.GROUP), new RunId(UUID.fromString(CodeWorkflow.RUN)),
                CodeWorkflow.CAT_USER)
        def manyStep = steps.step(groupId(CodeWorkflow.GROUP), new RunId(UUID.fromString(CodeWorkflow.RUN)),
                stepId(CodeWorkflow.SEND), CodeWorkflow.CAT_USER)

        then:
        oneQueries == 1
        statements.count { it.contains('from errored_code_returned_files kept') } == 1
        statements.size() == few
        many.steps().size() == one.steps().size() + 20
        oneStep.triesMade()[0].keptFiles()*.id() == [file]
        manyStep.triesMade().size() == 21
        manyStep.triesMade().take(20).every { it.keptFiles()*.id() == [file] }
        manyStep.triesMade()[20].keptFiles() == null
        !manyStep.triesMade().any { it.keptFiles()?.any { kept -> kept.id() == otherFile } }
    }

    def "a step outside this reader's view never exposes a drawing lookup"() {
        given:
        StepRows.run(store, ANNS_RUN, GROUP, 2, TicketWorkflow.WORKFLOW, VERSION, ANN, TicketWorkflow.STARTED_WITH)
        def statements = []
        def counting = new CountingDataSource(store.database, statements)
        def session = JdbcClient.create(counting)
        def counted = new RunSteps(session, new GroupRoles(session), new RunSnapshots(session, CodeStepsHeld.NONE, new SearchWays([])),
                store.transactionManager(counting), Deployments.DEEP_ENOUGH, new org.lilradish.lite.app.currency.GroupCurrencies(session, new org.springframework.transaction.support.TransactionTemplate(store.transactionManager(counting)), new GroupRoles(session), org.lilradish.lite.testutil.library.DeployedModels.HELD))

        when:
        counted.step(groupId(GROUP), new RunId(UUID.fromString(run)), stepId(step), reader)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == refusal
        !statements.any { it.contains("select drawing from question_versions") }

        where:
        run      | step                                      | reader   || refusal
        ANNS_RUN | SUMMARISE                                 | CAT_USER || RefusalCode.RUN_NOT_IN_VIEW
        RUN      | "00000009-0000-4000-8000-000000000e09" | ANN_USER || RefusalCode.STEP_NOT_IN_VIEW
    }

    def "a step no version of the run holds is refused as none"() {
        when:
        steps.step(groupId(GROUP), runId(), stepId("00000009-0000-4000-8000-000000000e09"), ANN_USER)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.STEP_NOT_IN_VIEW
    }
}
