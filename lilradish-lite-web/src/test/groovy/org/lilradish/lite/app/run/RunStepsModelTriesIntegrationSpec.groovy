package org.lilradish.lite.app.run

import static org.lilradish.lite.app.run.fixture.TicketWorkflow.ANN_USER
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.CAT
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.GROUP
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.RUN
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARISE
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.VERSION
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.WORKFLOW
import static org.lilradish.lite.testutil.library.LibraryStore.groupId

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.util.concurrent.TimeUnit
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.run.fixture.StepRows
import org.lilradish.lite.app.run.fixture.TicketWorkflow
import org.lilradish.lite.domain.inference.CallOutcome
import org.lilradish.lite.domain.inference.TurnAway
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.domain.run.WorkflowStepId
import org.lilradish.lite.testutil.CountingDataSource
import org.lilradish.lite.testutil.codestep.CodeStepsHeld
import org.lilradish.lite.testutil.inference.ScriptedModelCalls
import org.lilradish.lite.testutil.library.LibraryStore
import org.lilradish.lite.testutil.run.RunRows
import org.springframework.jdbc.core.simple.JdbcClient
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * A step a model produces as its readers read it, on a real server running the real baseline, the model scripted:
 * why a try did not fit, what its call said went wrong, each time a call to produce was turned away, who counted what
 * it spent, and why the step is held or failed, each read from where the store keeps it. Read by an overseer, who may
 * read what a model said as every role may; what is withheld from a reader who may not is StepReadingSpec's.
 */
class RunStepsModelTriesIntegrationSpec extends Specification {

    static final String SYSTEM = RunRows.WORKFLOW_RUNNER

    static final String FITS = '{"values":{"summary":"A printer fire."},"confidences":{}}'

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    ScriptedModelCalls model

    EngineWiring wiring

    RunSteps steps

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, "run_steps_model_tries_" + (++databasesMade))
        model = new ScriptedModelCalls()
        wiring = new EngineWiring(store, model, CodeStepsHeld.NONE)
        steps = new RunSteps(store.session, new GroupRoles(store.session), wiring.snapshots, store.transactionManager())
        TicketWorkflow.seed(store)
        producedByModel("ordinary")
    }

    def cleanup() {
        wiring.executor.destroy()
    }

    private void producedByModel(String mode) {
        store.session.sql("""
                update workflow_steps set producer = 'model', producer_model = 'general', producer_mode = ?
                 where workflow_step_id = ?::uuid""").params(mode, SUMMARISE).update()
    }

    private static RunId runId() {
        new RunId(UUID.fromString(RUN))
    }

    private static WorkflowStepId summarise() {
        new WorkflowStepId(UUID.fromString(SUMMARISE))
    }

    /** As a start does: the run written, its tree locked, the first step planned, in one transaction. */
    private void started() {
        store.transactions().executeWithoutResult {
            StepRows.run(store, RUN, GROUP, 1, WORKFLOW, VERSION, CAT, TicketWorkflow.STARTED_WITH)
            wiring.engine.planStarted(wiring.tree.lock(groupId(GROUP), runId()).orElseThrow())
        }
    }

    private void until(String what, Closure<Boolean> reached) {
        def deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!reached()) {
            assert System.nanoTime() < deadline: "never reached: ${what}"
            Thread.sleep(10)
        }
    }

    private void untilTriesEnded(int tries) {
        until("${tries} tries ended") { store.count("select count(*) from productions where ended_at is not null") == tries }
    }

    private static CallOutcome.CameBack cameBack(String answer, boolean countedByModel, boolean cutOff = false) {
        new CallOutcome.CameBack(answer, 321, 12, countedByModel, cutOff)
    }

    /** The model reviewing try 1 of summarise, the call turned away saying {@code said}; nothing here sends one. */
    private void reviewTurnedAway(String said) {
        def attempt = store.session.sql("""
                insert into run_step_send_attempts (run_step_id, run_id, run_step_kind, workflow_step_id, purpose,
                                                    model, mode, production_id, payload, created_by, created_by_kind)
                select try.run_step_id, try.run_id, 'question', ?::uuid, 'review', 'general', 'ordinary',
                       try.production_id, '{}', ?::uuid, 'system'
                  from productions try
                 where try.run_id = ?::uuid and try.try_number = 1
                returning cast(run_step_send_attempt_id as text)
                """).params(SUMMARISE, SYSTEM, RUN).query(String).single()
        def call = store.session.sql("""
                insert into model_calls (run_id, root_run_id, purpose, run_step_send_attempt_id, run_step_id,
                                         production_id, model, mode, envelope_version, sent_count, outcome, ended_at,
                                         created_by)
                select attempt.run_id, attempt.run_id, 'review', attempt.run_step_send_attempt_id, attempt.run_step_id,
                       attempt.production_id, 'general', 'ordinary', 1, 5, 'turned_away', now(), ?::uuid
                  from run_step_send_attempts attempt
                 where attempt.run_step_send_attempt_id = ?::uuid
                returning cast(model_call_id as text)
                """).params(SYSTEM, attempt).query(String).single()
        store.session.sql("insert into model_call_turnaways (model_call_id, said, created_by) values (?::uuid, ?, ?::uuid)")
                .params(call, said, SYSTEM).update()
    }

    def "a try whose answer did not fit says which way, and one whose call went wrong says what it said, cut to its bound"() {
        given:
        model.answering(cameBack(FITS, true, true))
        model.answering(new CallOutcome.Errored("Upstream failed: " + "x" * 3000))
        started()
        untilTriesEnded(2)

        when:
        def page = steps.step(groupId(GROUP), runId(), summarise(), ANN_USER)

        then:
        page.triesMade()*.ended() == ["did_not_fit", "errored"]
        page.triesMade()*.didNotFit() == ["cut_off", null]

        and: "what the call said, as kept: its bound, and that it was cut there"
        page.triesMade()[0].wentWrong() == null
        with(page.triesMade()[1].wentWrong()) {
            detail().startsWith("Upstream failed: ")
            detail().length() == 2048
            cut()
            withheld() == null
        }

        and: "neither turned away, and the step failed with its tries spent rather than for a model"
        page.triesMade()*.turnedAway() == [null, null]
        page.step().where().reason() == "tries_spent"
        page.step().where().model() == null
    }

    def "a step held because its model turned it away says whether what may be spent is used up, and each time it was turned away"() {
        given:
        model.answering(resentAfter, new CallOutcome.TurnedAway(last))
        started()
        until("the step held") { store.count("select count(*) from run_step_holds where released_at is null") == 1 }

        when:
        def row = steps.steps(groupId(GROUP), runId(), ANN_USER).steps()[0]
        def page = steps.step(groupId(GROUP), runId(), summarise(), ANN_USER)

        then:
        row.state() == "held_back"
        row.where().reason() == "turned_away"
        row.where().spentUp() == spentUp
        row.where().turnedAway()*.said() == said
        row.where().turnedAway()*.sentAgain() == sentAgain
        row.where().turnedAway()*.cut() == said.collect { it == null ? null : false }
        row.where().turnedAway().every { it.withheld() == null }

        and: "the same on the try the call was about, which is still open and spent nothing"
        page.triesMade()*.turnedAway() == [row.where().turnedAway()]
        page.triesMade()*.ended() == ["open"]
        page.triesMade()*.didNotFit() == [null]
        page.triesMade()*.wentWrong() == [null]

        where:
        resentAfter                    | last                             || spentUp | said                | sentAgain
        []                             | new TurnAway("Too busy.", false) || null    | ["Too busy."]       | [false]
        [new TurnAway("Busy.", false)] | new TurnAway("Spent.", true)     || true    | ["Busy.", "Spent."] | [true, false]
        []                             | new TurnAway(null, false)        || null    | [null]              | [false]
    }

    /** A review turned away is not the model declining to produce, so it is told apart rather than listed among them. */
    def "only calls to produce a try are listed as turned away on it, a review of it turned away is not"() {
        given:
        store.session.sql("""
                update workflow_steps set reviewer_model = 'general', reviewer_mode = 'ordinary'
                 where workflow_step_id = ?::uuid""").params(SUMMARISE).update()
        model.answering([new TurnAway("Busy.", false)], cameBack(FITS, true))
        started()
        untilTriesEnded(1)
        reviewTurnedAway("Review busy.")

        when:
        def page = steps.step(groupId(GROUP), runId(), summarise(), ANN_USER)

        then:
        page.triesMade().size() == 1
        page.triesMade()[0].turnedAway()*.said() == ["Busy."]
        page.triesMade()[0].turnedAway()*.sentAgain() == [true]

        and: "the review's turnaway is kept, just not listed"
        store.count("select count(*) from model_call_turnaways") == 2
    }

    /** A call the model counted is its count; one it did not, or one that never came back, is what this system measured. */
    def "each try, the step and the run say whether what they spent is what this system measured rather than what the model counted"() {
        given:
        model.answering(ending)
        model.answering(cameBack(FITS, true))
        started()
        untilTriesEnded(1)

        when:
        def page = steps.step(groupId(GROUP), runId(), summarise(), ANN_USER)

        then:
        page.triesMade()[0].cost().measuredHere() == measuredHere
        page.triesMade()[0].cost().cameBackUnknown() == cameBackUnknown

        and: "the step's and the run's totals say so where any of theirs is"
        page.step().cost().measuredHere() == measuredHere
        RunBudget.spentAsRead(store.session, runId(), true).measuredHere() == (measuredHere != null)

        where:
        ending                                || measuredHere | cameBackUnknown
        cameBack(FITS, true)                  || null         | false
        cameBack(FITS, false)                 || true         | false
        new CallOutcome.Errored("Gone.")      || true         | true
    }

    /** Read again every few seconds while a run runs, so a lost try or a turnaway more is never a statement more. */
    def "a model's run is read in as many statements however many of its tries were lost or turned away"() {
        given:
        model.answering([new TurnAway("Busy.", false)], new CallOutcome.Errored("Gone."))
        model.answering([new TurnAway("Busy again.", false)], second)
        started()
        untilTriesEnded(2)
        def statements = []
        def counting = new CountingDataSource(store.database, statements)
        def session = JdbcClient.create(counting)
        def counted = new RunSteps(session, new GroupRoles(session), new RunSnapshots(session, CodeStepsHeld.NONE),
                store.transactionManager(counting))

        when:
        counted.steps(groupId(GROUP), runId(), ANN_USER)

        then: "one statement more for why tries were lost, and one for the turnaways, over what any run's steps take"
        statements.size() == RunStepsIntegrationSpec.READ_IN + 2

        where:
        second << [cameBack(FITS, true), cameBack("A printer fire.", true)]
    }

    def "a step failed for a model not held says which model and in which mode, to be produced"() {
        given:
        producedByModel("deep")
        started()
        until("the try failed") { store.count("select count(*) from run_step_failures") == 1 }

        when:
        def whereabouts = steps.steps(groupId(GROUP), runId(), ANN_USER).steps()[0].where()

        then:
        whereabouts.kind() == "failed"
        whereabouts.reason() == "model_not_deployed"
        whereabouts.model() == "general"
        whereabouts.mode() == "deep"
        whereabouts.reviewing() == null

        and: "nothing was sent, so nothing was turned away"
        whereabouts.turnedAway() == null
        model.requests == []
    }
}
