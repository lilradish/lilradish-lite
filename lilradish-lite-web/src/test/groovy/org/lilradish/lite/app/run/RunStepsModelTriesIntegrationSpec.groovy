package org.lilradish.lite.app.run

import static org.lilradish.lite.app.run.fixture.TicketWorkflow.ANN_USER
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.CAT
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.GROUP
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.RUN
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARISE
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.VERSION
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.WORKFLOW
import static org.lilradish.lite.testutil.library.LibraryStore.groupId
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.util.concurrent.TimeUnit
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.run.fixture.StepRows
import org.lilradish.lite.app.run.fixture.TicketWorkflow
import org.lilradish.lite.app.search.SearchWays
import org.lilradish.lite.domain.identity.GroupPermission
import org.lilradish.lite.domain.inference.CallOutcome
import org.lilradish.lite.domain.inference.DidNotFitReason
import org.lilradish.lite.domain.inference.KeptAnswer
import org.lilradish.lite.domain.inference.TurnAway
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.domain.run.WorkflowStepId
import org.lilradish.lite.testutil.CountingDataSource
import org.lilradish.lite.testutil.codestep.CodeStepsHeld
import org.lilradish.lite.testutil.inference.ScriptedModelCalls
import org.lilradish.lite.testutil.library.LibraryStore
import org.lilradish.lite.testutil.run.RunRows
import org.lilradish.lite.web.ActAdmission
import org.lilradish.lite.web.CallerAdmission
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper

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
        steps = new RunSteps(store.session, new GroupRoles(store.session), wiring.snapshots, store.transactionManager(), Deployments.DEEP_ENOUGH, new org.lilradish.lite.app.currency.GroupCurrencies(store.session, new org.springframework.transaction.support.TransactionTemplate(store.transactionManager()), new GroupRoles(store.session), org.lilradish.lite.testutil.library.DeployedModels.HELD))
        TicketWorkflow.seed(store)
        producedByModel("ordinary")
    }

    def cleanup() {
        wiring.executor.destroy()
    }

    private void producedByModel(String mode) {
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
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

    // Write the run, lock its tree, and plan the first step in one transaction.
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
        new CallOutcome.CameBack(answer, 321, 12, countedByModel, cutOff, null, null)
    }

    def "a declined production reads its two kept texts on the step page, while the run page reads neither"() {
        given:
        model.answering(new CallOutcome.CameBack("Demo Sample answer", 321, 12, true, false,
                DidNotFitReason.MODEL_DECLINED, "Demo Sample refusal"))
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("update workflow_steps set tries = 1 where workflow_step_id = ?::uuid")
                .params(SUMMARISE).update()
        started()
        untilTriesEnded(1)
        wiring.drained()
        def statements = []
        def counting = new CountingDataSource(store.database, statements)
        def session = JdbcClient.create(counting)
        def counted = new RunSteps(session, new GroupRoles(session),
                new RunSnapshots(session, CodeStepsHeld.NONE, new SearchWays([])), store.transactionManager(counting),
                Deployments.DEEP_ENOUGH, new org.lilradish.lite.app.currency.GroupCurrencies(session,
                new org.springframework.transaction.support.TransactionTemplate(store.transactionManager(counting)),
                new GroupRoles(session), org.lilradish.lite.testutil.library.DeployedModels.HELD))

        when:
        def rows = counted.steps(groupId(GROUP), runId(), ANN_USER)

        then:
        rows.steps()[0].tries().current() == 1
        statements.every { !it.toLowerCase().contains("declined_words") }

        when:
        statements.clear()
        def detail = counted.step(groupId(GROUP), runId(), summarise(), ANN_USER)

        then:
        detail.triesMade()[0].didNotFit() == "model_declined"
        detail.triesMade()[0].declined() == new StepAnswers.DeclinedAnswer(
                new StepAnswers.DeclinedTextAnswer("Demo Sample refusal", false, false),
                new StepAnswers.DeclinedTextAnswer("Demo Sample answer", false, false), null)
        statements.count { it.toLowerCase().contains("declined_words") } == 1

        and: "projecting the same persisted try without inference permission names neither text nor its presence"
        def snapshot = wiring.snapshots.inTreeAsRead(groupId(GROUP), runId())
        def production = snapshot.run().steps().find { it.planned().id() == summarise() }.tries()[0].id()
        def withoutWords = new StepReading(snapshot, 1, true, [GroupPermission.READ_OWN_RUNS] as Set, null,
                Optional.empty(), [:], [:],
                [(production): new StepReading.LostRecord(DidNotFitReason.MODEL_DECLINED, null, false, false)], [:],
                Deployments.DEEP_ENOUGH)
        withoutWords.step(summarise()).triesMade()[0].declined() == new StepAnswers.DeclinedAnswer(null, null, true)
    }

    def "a declined production omits empty text blocks for an allowed reader"() {
        given:
        model.answering(new CallOutcome.CameBack(content, 321, 12, true, false,
                DidNotFitReason.MODEL_DECLINED, words))
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("update workflow_steps set tries = 1 where workflow_step_id = ?::uuid")
                .params(SUMMARISE).update()
        started()
        untilTriesEnded(1)
        wiring.drained()

        when:
        def tried = steps.step(groupId(GROUP), runId(), summarise(), ANN_USER).triesMade()[0]

        then:
        tried.didNotFit() == "model_declined"
        tried.declined() == expected

        where:
        words                 | content              || expected
        null                  | ""                   || null
        "Demo Sample refusal" | ""                   || new StepAnswers.DeclinedAnswer(
                new StepAnswers.DeclinedTextAnswer("Demo Sample refusal", false, false), null, null)
        null                  | "Demo Sample answer" || new StepAnswers.DeclinedAnswer(
                null, new StepAnswers.DeclinedTextAnswer("Demo Sample answer", false, false), null)
    }

    private void reviewTurnedAway(String said) {
        // DB-SPECIFIC: PostgreSQL ::uuid casts, RETURNING.
        def attempt = store.session.sql("""
                insert into run_step_send_attempts (run_step_id, run_id, run_step_kind, workflow_step_id, purpose,
                                                    model, mode, production_id, payload, created_by, created_by_kind)
                select try.run_step_id, try.run_id, 'question', ?::uuid, 'review', 'general', 'ordinary',
                       try.production_id, '{}', ?::uuid, 'system'
                  from productions try
                 where try.run_id = ?::uuid and try.try_number = 1
                returning cast(run_step_send_attempt_id as text)
                """).params(SUMMARISE, SYSTEM, RUN).query(String).single()
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now(), RETURNING.
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
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("insert into model_call_turnaways (model_call_id, said, created_by) values (?::uuid, ?, ?::uuid)")
                .params(call, said, SYSTEM).update()
    }

    def "a try whose answer did not fit says which way, and one whose call went wrong says what it said, cut to its bound"() {
        given:
        model.answering(cameBack(FITS, true, true))
        model.answering(new CallOutcome.Errored("Upstream failed: " + "x" * 3000, null))
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

    def "a single step shows kept unreadable service text only to an inference reader and omits an empty text"() {
        given:
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql('update workflow_steps set tries = 1 where workflow_step_id = ?::uuid')
                .param(SUMMARISE).update()
        model.answering(new CallOutcome.Errored(
                'The model service returned a response this system could not read.', KeptAnswer.of(raw)))
        started()
        untilTriesEnded(1)
        wiring.drained()
        def statements = []
        def counting = new CountingDataSource(store.database, statements)
        def session = JdbcClient.create(counting)
        def counted = new RunSteps(session, new GroupRoles(session),
                new RunSnapshots(session, CodeStepsHeld.NONE, new SearchWays([])), store.transactionManager(counting),
                Deployments.DEEP_ENOUGH, new org.lilradish.lite.app.currency.GroupCurrencies(session,
                new org.springframework.transaction.support.TransactionTemplate(store.transactionManager(counting)),
                new GroupRoles(session), org.lilradish.lite.testutil.library.DeployedModels.HELD))
        def mvc = MockMvcBuilders.standaloneSetup(new RunStepsController(counted)).build()

        when:
        def response = mvc.perform(get("/api/groups/${GROUP}/runs/${RUN}/steps/${SUMMARISE}")
                .requestAttr(CallerAdmission.class.name + '.caller', ANN_USER)
                .requestAttr(ActAdmission.class.name + '.group', groupId(GROUP))).andReturn().response
        def shown = JsonMapper.builder().build().readTree(response.contentAsString).path('triesMade').get(0)

        then:
        response.status == 200
        shown.path('wentWrong').path('detail').asText() ==
                'The model service returned a response this system could not read.'
        shown.has('unreadableResponse') == !raw.isEmpty()
        if (!raw.isEmpty()) {
            assert shown.path('unreadableResponse').path('text').asText() == raw
            assert !shown.path('unreadableResponse').path('altered').asBoolean()
            assert !shown.path('unreadableResponse').path('truncated').asBoolean()
        }
        statements.count { it.contains('call.unreadable_response, call.unreadable_response_altered') } == 1
        store.texts('select unreadable_response from model_calls where outcome = \'errored\'') == [raw]

        where:
        raw << ['Demo Sample unreadable', '']
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

        and: "the unnumbered production spends no try, while the hold keeps every turnaway"
        page.triesMade() == []
        row.tries().current() == 0
        row.where().number() == null
        row.cost().spent() == "0"
        row.cost().measuredHere() == null
        store.count("select count(*) from productions where try_number is null and ended_at is null") == 1
        store.count("select count(*) from model_call_turnaways") == said.size()

        where:
        resentAfter                    | last                             || spentUp | said                | sentAgain
        []                             | new TurnAway("Too busy.", false) || null    | ["Too busy."]       | [false]
        [new TurnAway("Busy.", false)] | new TurnAway("Spent.", true)     || true    | ["Busy.", "Spent."] | [true, false]
        []                             | new TurnAway(null, false)        || null    | [null]              | [false]
    }

    def "only calls to produce a try are listed as turned away on it, a review of it turned away is not"() {
        given:
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
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
        RunBudget.spentAsRead(store.session, runId(), true).measuredHere()?.published() == measuredHere

        where:
        ending                                || measuredHere | cameBackUnknown
        cameBack(FITS, true)                  || null         | false
        cameBack(FITS, false)                 || "wholly"     | false
        new CallOutcome.Errored("Gone.", null) || "wholly"     | true
    }

    def "a model's run is read in as many statements however many of its tries were lost or turned away"() {
        given:
        model.answering([new TurnAway("Busy.", false)], new CallOutcome.Errored("Gone.", null))
        model.answering([new TurnAway("Busy again.", false)], second)
        started()
        untilTriesEnded(2)
        def statements = []
        def counting = new CountingDataSource(store.database, statements)
        def session = JdbcClient.create(counting)
        def counted = new RunSteps(session, new GroupRoles(session), new RunSnapshots(session, CodeStepsHeld.NONE, new SearchWays([])),
                store.transactionManager(counting), Deployments.DEEP_ENOUGH, new org.lilradish.lite.app.currency.GroupCurrencies(session, new org.springframework.transaction.support.TransactionTemplate(store.transactionManager(counting)), new GroupRoles(session), org.lilradish.lite.testutil.library.DeployedModels.HELD))

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
