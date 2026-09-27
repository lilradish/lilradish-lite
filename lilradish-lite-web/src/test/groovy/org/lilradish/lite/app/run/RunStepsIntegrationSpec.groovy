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

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.time.OffsetDateTime
import org.libprunus.core.error.ApiErrorException
import org.lilradish.lite.app.codestep.CodeRuns
import org.lilradish.lite.app.filling.FillFieldAnswer
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.pool.PersonAnswer
import org.lilradish.lite.app.run.fixture.StepRows
import org.lilradish.lite.app.run.fixture.TicketWorkflow
import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.run.ReviewOutcome
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.domain.run.WorkflowStepId
import org.lilradish.lite.domain.wire.JsonValue
import org.lilradish.lite.testutil.CountingDataSource
import org.lilradish.lite.testutil.codestep.CodeStepsHeld
import org.lilradish.lite.testutil.inference.ScriptedModelCalls
import org.lilradish.lite.testutil.library.DeployedModels
import org.lilradish.lite.testutil.library.LibraryStore
import org.lilradish.lite.testutil.run.RunRows
import org.springframework.jdbc.core.simple.JdbcClient
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import tools.jackson.databind.node.JsonNodeFactory

/**
 * A run's steps as their readers read them, on a real server running the real baseline: where each is, what
 * went in and came out, every try, and what each reader may do and why not.
 */
class RunStepsIntegrationSpec extends Specification {

    static final JsonNodeFactory NODES = JsonNodeFactory.instance

    static final PersonAnswer CAT_ANSWER = new PersonAnswer("000e03", "Cat")

    static final PersonAnswer ANN_ANSWER = new PersonAnswer("000e01", "Ann")

    static final PersonAnswer STEWARD_ANSWER = new PersonAnswer("000001", null)

    static final String ANNS_RUN = "00000008-0000-4000-8000-000000000e02"

    /** The statements one read of a run's steps makes, its transaction's own included. */
    static final int READ_IN = 26

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
        def snapshots = new RunSnapshots(session, CodeStepsHeld.NONE)
        executor = EngineExecutors.of(store.database)
        def writes = new EngineWrites(session)
        def ceilings = new CeilingReach(session, DeployedModels.HELD, writes)
        engine = new RunEngine(session, store.transactions(), tree, snapshots, writes, executor,
                new EngineCalls(new ScriptedModelCalls(), store.transactions(), tree, writes, ceilings),
                new EngineCodes(new CodeRuns(CodeStepsHeld.NONE), store.transactions(), tree, snapshots, writes, executor),
                DeployedModels.HELD, ceilings, new GroupRoles(session))
        acts = new StepActs(session, store.transactions(), new GroupRoles(session), tree, snapshots, writes, engine)
        steps = new RunSteps(session, new GroupRoles(session), snapshots, store.transactionManager())
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
                    "Summarise", 1, UUID.fromString(SUMMARISE_VERSION), null)
            producer() == new StepAnswers.WhoAnswer("person", null, null, null)
            reviewer() == new StepAnswers.WhoAnswer("person", null, null, null)
            state() == "waiting"
            where().kind() == "owed_try"
            where().open()
            !where().beyond()
            where().waitsOn() == "answer_step"
            where().stopped() == null
            takesFrom() == [new StepAnswers.TakesFromAnswer("text",
                    new StepAnswers.FromAnswer("run_input", "ticket", null, null, null, null))]
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
                            UUID.fromString(SUMMARISE_VERSION), null))]
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
                    new StepAnswers.FromAnswer("run_input", "ticket", null, null, null, null),
                    NODES.stringNode("The printer is on fire."), null, null)]
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
                    null, null)
            ended() == "refused_on_review"
            wentWrong() == null
        }

        and: "the second: asked again by Cat, answered, and waiting on a review nobody has given"
        with(read.triesMade()[1]) {
            number() == 2
            askedBy() == CAT_ANSWER
            values()*.now() == ["waiting_on_review"]
            review() == new StepAnswers.ReviewAnswer(true, null, null, null, null)
            ended() == "waiting"
        }
        read.triesMade().size() == 2

        and: "what came out is the newest try's, standing nowhere yet"
        read.cameOut() == [new StepAnswers.CameOutAnswer("summary", null, "waiting_on_review")]

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

    def "a step not reached says nothing went in and nothing came out, and made no try"() {
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
                        UUID.fromString(SUMMARISE_VERSION), null),
                NODES.stringNode("A printer fire."), null, null)]

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

    /** A let-go whose going on never ran leaves its hold behind; reading must not leave the run waiting on it. */
    def "a hold left behind by a stop let go with nothing going on after it reads as the try owed, and answering it is taken"() {
        given:
        store.stopped(CONFIRM_QUESTION, FIRST_STEWARD)
        answeredByCat()
        reviewed(ReviewOutcome.ASSURED)
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

    /** What the workflow's stop holds is whatever the step runs, so it is the stop the step says it is held by. */
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

    /** When the stop in force on {@code entry} was made, as every moment is written. */
    private String stoppedAt(String entry) {
        store.session.sql("select created_at from entry_stops where entry_id = ?::uuid and let_go_at is null")
                .params(entry).query(OffsetDateTime).single().toInstant().toString()
    }

    def "answering here is put to whoever may answer: the try it fills, the instruction, and what it gives back"() {
        when:
        def read = steps.step(groupId(GROUP), runId(), stepId(SUMMARISE), CAT_USER)

        then:
        read.answering() == new StepAnswers.AnsweringAnswer(1, false, "Say what the ticket is about.",
                [new FillFieldAnswer("summary", null, null, "text", 1000, null, true, null, null)], null)
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

    /** Read again every few seconds while a run runs, so a step more is never a statement more. */
    def "a run's steps are read in as many statements however many steps its version holds"() {
        given:
        def statements = []
        def counting = new CountingDataSource(store.database, statements)
        def session = JdbcClient.create(counting)
        def counted = new RunSteps(session, new GroupRoles(session), new RunSnapshots(session, CodeStepsHeld.NONE),
                store.transactionManager(counting))

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

    def "a step no version of the run holds is refused as none"() {
        when:
        steps.step(groupId(GROUP), runId(), stepId("00000009-0000-4000-8000-000000000e09"), ANN_USER)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.STEP_NOT_IN_VIEW
    }
}
