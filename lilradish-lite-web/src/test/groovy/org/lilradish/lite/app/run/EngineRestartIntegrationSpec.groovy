package org.lilradish.lite.app.run

import static org.lilradish.lite.app.run.fixture.CodeWorkflow.receipt
import static org.lilradish.lite.app.run.fixture.CodeWorkflow.scripted
import static org.lilradish.lite.testutil.library.LibraryStore.groupId

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.lilradish.lite.app.codestep.CodeSteps
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.run.fixture.CodeWorkflow
import org.lilradish.lite.app.run.fixture.StepRows
import org.lilradish.lite.app.run.fixture.TicketWorkflow
import org.lilradish.lite.domain.declaration.Demand
import org.lilradish.lite.domain.declaration.Field
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.declaration.FieldShape
import org.lilradish.lite.domain.declaration.FieldStanding
import org.lilradish.lite.domain.declaration.HowMany
import org.lilradish.lite.domain.inference.CallOutcome
import org.lilradish.lite.domain.inference.ModelCallPurpose
import org.lilradish.lite.domain.inference.ModelCalls
import org.lilradish.lite.domain.run.ReviewSending
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.domain.run.StepFailure
import org.lilradish.lite.domain.run.StepPosition
import org.lilradish.lite.domain.run.StepPositions
import org.lilradish.lite.domain.run.ValueStanding
import org.lilradish.lite.domain.run.WorkflowStepId
import org.lilradish.lite.testutil.SnapshottingAppender
import org.lilradish.lite.testutil.codestep.CodeStepsHeld
import org.lilradish.lite.testutil.codestep.ScriptedCodeStep
import org.lilradish.lite.testutil.inference.Pause
import org.lilradish.lite.testutil.inference.ScriptedModelCalls
import org.lilradish.lite.testutil.library.LibraryStore
import org.lilradish.lite.testutil.run.RunRows
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class EngineRestartIntegrationSpec extends Specification {

    static final String SYSTEM = RunRows.WORKFLOW_RUNNER

    static final String FITS = '{"values":{"summary":"A printer fire."},"confidences":{}}'

    static final Field REVIEWED_RECEIPT = new Field(new FieldName("receipt"), null, null, new FieldShape.Text(64),
            new HowMany.One(), new Demand.Stands(true, FieldStanding.NEVER, null))

    static final String ASSURES = '{"decisions":{"summary":{"outcome":"assured"}}}'

    static final Logger RESTART_LOGGER = LoggerFactory.getLogger(EngineRestart) as Logger

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    List<EngineWiring> wirings = []

    // Release abandoned engines' gates during cleanup so their threads can finish.
    List<Closure> abandoned = []

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, "engine_restart_" + (++databasesMade))
    }

    def cleanup() {
        abandoned.each { it() }
        wirings.each { it.executor.destroy() }
    }

    private EngineWiring wired(ModelCalls modelCalls, CodeSteps release = CodeStepsHeld.NONE) {
        def wiring = new EngineWiring(store, modelCalls, release)
        wirings << wiring
        wiring
    }

    private void modelSeeded(int tries = 2) {
        TicketWorkflow.seed(store, tries)
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                update workflow_steps set producer = 'model', producer_model = 'general', producer_mode = 'ordinary'
                 where workflow_step_id = ?::uuid""").params(TicketWorkflow.SUMMARISE).update()
    }

    // Write the run and plan its first step in one transaction.
    private static void ticketStarted(LibraryStore store, EngineWiring wiring) {
        store.transactions().executeWithoutResult {
            TicketWorkflow.run(store)
            wiring.engine.planStarted(wiring.tree.lock(groupId(TicketWorkflow.GROUP), ticketRun()).orElseThrow())
        }
    }

    private static void codeStarted(LibraryStore store, EngineWiring wiring) {
        store.transactions().executeWithoutResult {
            CodeWorkflow.run(store)
            wiring.engine.planStarted(wiring.tree.lock(groupId(CodeWorkflow.GROUP), codeRun()).orElseThrow())
        }
    }

    // Begin stopping before releasing the model call so it cannot drive further work.
    private ScriptedModelCalls callOut() {
        modelSeeded()
        def pause = new Pause()
        def old = new ScriptedModelCalls().pausing(pause, [], cameBack(FITS))
        def process = wired(old)
        ticketStarted(store, process)
        pause.awaitReached()
        abandoned << {
            process.closing()
            pause.release()
        }
        old
    }

    // Begin stopping before releasing the review call so it cannot drive further work.
    private ScriptedModelCalls reviewOut(int tries = 2, boolean mixed = false) {
        modelSeeded(tries)
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                update workflow_steps set reviewer_model = 'small', reviewer_mode = 'ordinary'
                 where workflow_step_id = ?::uuid""").params(TicketWorkflow.SUMMARISE).update()
        if (mixed) {
            StepRows.field(store, [id: "0000000c-0000-4000-8000-000000000e08", owner: TicketWorkflow.SUMMARISE_VERSION,
                                  ownerKind: "question", side: "gives", position: 2, name: "extra", limit: 1000,
                                  mustBe: true, standing: "never"])
            StepRows.field(store, [id: "0000000c-0000-4000-8000-000000000e09", owner: TicketWorkflow.SUMMARISE_VERSION,
                                  ownerKind: "question", side: "gives", position: 3, name: "already", limit: 1000,
                                  mustBe: true, standing: "always"])
        }
        def pause = new Pause()
        def answer = mixed ? '{"values":{"summary":"A printer fire.","extra":"Another fact.","already":"Stands."},"confidences":{}}' : FITS
        def old = new ScriptedModelCalls().answering(cameBack(answer)).pausing(pause, [], cameBack(ASSURES))
        def process = wired(old)
        ticketStarted(store, process)
        pause.awaitReached()
        abandoned << {
            process.closing()
            pause.release()
        }
        old
    }

    private static ScriptedCodeStep reviewedCode(boolean mayRunAgain, String returned) {
        new ScriptedCodeStep(CodeWorkflow.CODE_STEP, [CodeWorkflow.REPLY], [REVIEWED_RECEIPT], mayRunAgain,
                { receipt(returned) })
    }

    private ScriptedModelCalls codeReviewOut(boolean mayRunAgain) {
        CodeWorkflow.seed(store, 3)
        reviewerOn(CodeWorkflow.SEND)
        def code = reviewedCode(mayRunAgain, "R-old")
        def pause = new Pause()
        def old = new ScriptedModelCalls().pausing(pause, [], cameBack('{"decisions":{"receipt":{"outcome":"assured"}}}'))
        def process = wired(old, CodeStepsHeld.of(code))
        codeStarted(store, process)
        pause.awaitReached()
        assert code.runs.get() == 1
        abandoned << {
            process.closing()
            pause.release()
        }
        old
    }

    private void reviewerOn(String step) {
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("update workflow_steps set reviewer_model = 'small', reviewer_mode = 'ordinary' where workflow_step_id = ?::uuid")
                .params(step).update()
    }

    private void reviewTurnedAway() {
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into model_call_turnaways (model_call_id, said, created_by)
                select model_call_id, 'Busy.', ?::uuid from model_calls where purpose = 'review'
                """).params(SYSTEM).update()
    }

    private List<String> turnawayRows() {
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select model_call_turnaway_id::text || ' ' || coalesce(resent_at::text, '-') from model_call_turnaways order by created_at, model_call_turnaway_id")
    }

    private void openTicket() {
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
        store.session.sql("update run_stops set opened_again_at = now(), opened_again_by = ?::uuid")
                .params(TicketWorkflow.CAT).update()
    }

    private StepAnswers.StepAnswer ticketStep(EngineWiring fresh) {
        new RunSteps(store.session, new GroupRoles(store.session), fresh.snapshots, store.transactionManager(), Deployments.DEEP_ENOUGH, new org.lilradish.lite.app.currency.GroupCurrencies(store.session, new org.springframework.transaction.support.TransactionTemplate(store.transactionManager()), new GroupRoles(store.session), org.lilradish.lite.testutil.library.DeployedModels.HELD))
                .step(groupId(TicketWorkflow.GROUP), ticketRun(),
                        new WorkflowStepId(UUID.fromString(TicketWorkflow.SUMMARISE)), TicketWorkflow.ANN_USER)
    }

    private boolean conservedSpend(EngineWiring fresh, String group, RunId run, String step, org.lilradish.lite.domain.identity.UserId reader) {
        def counted = store.session.sql("select coalesce(sum(sent_count + coalesce(came_back_count, 0)), 0) from model_calls where outcome is distinct from 'turned_away'")
                .query(Long).single()
        def tries = RunBudget.spentByTryAsRead(store.session, run)
        def page = new RunSteps(store.session, new GroupRoles(store.session), fresh.snapshots, store.transactionManager(), Deployments.DEEP_ENOUGH, new org.lilradish.lite.app.currency.GroupCurrencies(store.session, new org.springframework.transaction.support.TransactionTemplate(store.transactionManager()), new GroupRoles(store.session), org.lilradish.lite.testutil.library.DeployedModels.HELD))
                .step(groupId(group), run, new WorkflowStepId(UUID.fromString(step)), reader)
        assert RunBudget.spentAsRead(store.session, run, true).spent() == counted
        assert tries.values().sum(0) { it.spent() } == counted
        assert page.step().cost().spent() == counted.toString()
        assert page.triesMade().sum(0) { it.cost().spent()?.toLong() ?: 0 } == counted
        true
    }

    // Begin stopping before releasing the code gate so it cannot drive further work.
    private ScriptedCodeStep codeRunning(int tries, boolean mayRunAgain) {
        CodeWorkflow.seed(store, tries)
        def gate = new CountDownLatch(1)
        def old = scripted(mayRunAgain, { receipt("R-old") }, gate)
        def process = wired(new ScriptedModelCalls(), CodeStepsHeld.of(old))
        codeStarted(store, process)
        until("the old code was running") { old.runs.get() == 1 }
        abandoned << {
            process.closing()
            gate.countDown()
        }
        old
    }

    private static CallOutcome.CameBack cameBack(String answer) {
        new CallOutcome.CameBack(answer, 321, 12, true, false, null, null)
    }

    private static RunId ticketRun() {
        new RunId(UUID.fromString(TicketWorkflow.RUN))
    }

    private static RunId codeRun() {
        new RunId(UUID.fromString(CodeWorkflow.RUN))
    }

    private static void until(String what, Closure<Boolean> reached) {
        def deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!reached()) {
            assert System.nanoTime() < deadline: "never ${what}"
            Thread.sleep(10)
        }
    }

    private List<String> triesOf(String step) {
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        store.texts("""
                select coalesce(try.try_number::text, '-') || ' ' || try.producer || ' ' || try.created_by_kind || ' '
                       || case when try.ended_at is null then 'open'
                               else coalesce(try.lost_reason::text, 'yielded') end
                  from productions try join run_steps held on held.run_step_id = try.run_step_id
                 where held.workflow_step_id = ?::uuid order by try.try_number
                """, step)
    }

    private List<String> codeTries() {
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("""
                select coalesce(try.try_number::text, '-') || ' ' || coalesce(try.ended_by::text, '-') || ' '
                       || coalesce(try.lost_reason::text, case when try.ended_at is null then 'open' else 'yielded' end)
                       || ' ' || try.may_run_again || ' ' || coalesce(try.lost_detail, '-') || ' '
                       || coalesce(try.returned_by_code, '-')
                  from productions try where try.producer = 'code' order by try.try_number
                """)
    }

    private List<String> callRows() {
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("""
                select coalesce(try.try_number::text, '-') || ' ' || coalesce(call.outcome::text, 'out') || ' ' || call.sent_count || ' '
                       || coalesce(call.came_back_count::text, '-') || ' ' || call.counted_by_model || ' '
                       || coalesce(call.answer, '-')
                  from model_calls call join productions try on try.production_id = call.production_id
                 order by call.created_at, call.model_call_id
                """)
    }

    private List<String> holds() {
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("""
                select hold.reason || ' ' || (hold.run_step_send_attempt_id = call.run_step_send_attempt_id) || ' '
                       || coalesce(hold.released_at::text, 'held')
                  from run_step_holds hold
                  left join model_calls call on call.run_step_id = hold.run_step_id
                 order by hold.created_at, hold.run_step_hold_id
                """)
    }

    private List<String> turnaways() {
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("""
                select coalesce(said, '-') || ' ' || coalesce(resent_at::text, 'not sent again')
                  from model_call_turnaways order by created_at, model_call_turnaway_id
                """)
    }

    private String runRows() {
        store.contents()
    }

    private StepPosition position(EngineWiring wiring, String group, RunId run, String step) {
        def read = store.transactions().execute { wiring.snapshots.asRead(groupId(group), run) }
        StepPositions.of(read, read.step(new WorkflowStepId(UUID.fromString(step))).orElseThrow())
    }

    // Exclude position timestamps because they are determined by the eventual end.
    private static String reads(StepPosition position) {
        if (position instanceof StepPosition.Running) {
            return "running ${position.on()}"
        }
        if (position instanceof StepPosition.Owed) {
            return "owed ${position.number()}"
        }
        if (position instanceof StepPosition.Failed && position.why() instanceof StepFailure.TriesSpent) {
            return "failed, ${position.why().used()} of ${position.why().declared()} tries spent"
        }
        if (position instanceof StepPosition.HeldBack) {
            return "held back ${position.reason()}"
        }
        position.class.simpleName
    }

    private static SnapshottingAppender listening() {
        def logged = new SnapshottingAppender()
        logged.start()
        RESTART_LOGGER.addAppender(logged)
        logged
    }

    def "code left running as the system stopped is ended by the system as nothing came back, keeping nothing it might have said"() {
        given:
        codeRunning(3, true)
        def fresh = wired(new ScriptedModelCalls(), CodeStepsHeld.of(scripted(true, { receipt() })))
        def logged = listening()

        when:
        fresh.restart.afterSingletonsInstantiated()

        then:
        codeTries() == ["1 ${SYSTEM} nothing_came_back true - -" as String]
        store.count("select count(*) from production_values") == 0

        and: "nothing goes on before the system serves"
        triesOf(CodeWorkflow.CHECK) == []
        store.count("select count(*) from run_step_holds") == 0

        and: "said by its keys alone"
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def aTry = store.texts("select production_id::text from productions where producer = 'code'")[0]
        logged.list*.formattedMessage == ["Try ${aTry} under run ${CodeWorkflow.RUN} was running code as this system"
                + " stopped, and ended as nothing came back" as String]
        logged.list[0].level == Level.WARN

        cleanup:
        RESTART_LOGGER.detachAppender(logged)
    }

    def "code ended so reads as its release and its tries left say"() {
        given:
        codeRunning(tries, mayRunAgain)
        def fresh = wired(new ScriptedModelCalls(), CodeStepsHeld.of(scripted(mayRunAgain, { receipt() })))

        when:
        fresh.restart.afterSingletonsInstantiated()

        then:
        reads(position(fresh, CodeWorkflow.GROUP, codeRun(), CodeWorkflow.SEND)) == reading

        where:
        tries | mayRunAgain || reading
        3     | true        || "running NEXT_TRY"
        3     | false       || "owed 2"
        1     | true        || "failed, 1 of 1 tries spent"
    }

    def "code that may run again, ended so, is run again once runs go on, and the run it lost never is"() {
        given:
        def old = codeRunning(3, true)
        def again = scripted(true, { receipt("R-2") })
        def fresh = wired(new ScriptedModelCalls(), CodeStepsHeld.of(again))
        fresh.restart.afterSingletonsInstantiated()

        when:
        fresh.restart.start()
        until("check was asked") { triesOf(CodeWorkflow.CHECK).size() == 1 }
        fresh.drained()

        then:
        triesOf(CodeWorkflow.SEND) == ["1 code system nothing_came_back", "2 code system yielded"]
        again.runs.get() == 1
        again.ran.every { it.thread() ==~ /run-code-\d+/ }

        and:
        old.runs.get() == 1
        triesOf(CodeWorkflow.CHECK) == ["1 person system open"]
    }

    def "code that may not run again, or has no try left, is never run again once runs go on"() {
        given:
        codeRunning(tries, mayRunAgain)
        def again = scripted(mayRunAgain, { receipt("R-2") })
        def fresh = wired(new ScriptedModelCalls(), CodeStepsHeld.of(again))
        fresh.restart.afterSingletonsInstantiated()

        when:
        fresh.restart.start()
        fresh.drained()

        then:
        triesOf(CodeWorkflow.SEND) == ["1 code system nothing_came_back"]
        again.runs.get() == 0

        and: "nothing written in its place"
        store.count("select count(*) from run_step_failures") == 0
        store.count("select count(*) from run_step_holds") == 0
        triesOf(CodeWorkflow.CHECK) == []

        where:
        tries | mayRunAgain
        3     | false
        1     | true
    }

    def "a call out with no turnaway is ended as nothing came back, its try spent, what was sent counted as measured"() {
        given:
        def old = callOut()
        def measured = old.requests[0].model().unitsOf(old.requests[0].sent().characters())
        def fresh = wired(new ScriptedModelCalls())
        def logged = listening()

        when:
        fresh.restart.afterSingletonsInstantiated()

        then:
        callRows() == ["1 nothing_came_back ${measured} - false -" as String]
        triesOf(TicketWorkflow.SUMMARISE) == ["1 model system nothing_came_back"]
        store.texts("""
                select try.model_call_outcome || ' ' || (try.model_call_id = call.model_call_id) || ' '
                       || try.ended_by || ' ' || (try.ended_at >= try.created_at)
                  from productions try join model_calls call on call.production_id = try.production_id
                """) == ["nothing_came_back true ${SYSTEM} true" as String]

        and: "no hold, no turnaway, and the next try made by itself"
        holds() == []
        turnaways() == []
        reads(position(fresh, TicketWorkflow.GROUP, ticketRun(), TicketWorkflow.SUMMARISE)) == "running NEXT_TRY"

        and: "said by its keys alone"
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def call = store.texts("select model_call_id::text from model_calls")[0]
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def aTry = store.texts("select production_id::text from productions")[0]
        logged.list*.formattedMessage == ["Call ${call} of try ${aTry} under run ${TicketWorkflow.RUN} was out as this"
                + " system stopped, and ended as nothing came back" as String]
        logged.list.every { !it.formattedMessage.contains("printer") }

        cleanup:
        RESTART_LOGGER.detachAppender(logged)
    }

    def "the next try goes out as a new call once runs go on, and the call lost is never sent again"() {
        given:
        def old = callOut()
        def model = new ScriptedModelCalls().answering(cameBack(FITS))
        def fresh = wired(model)
        fresh.restart.afterSingletonsInstantiated()

        when:
        fresh.restart.start()
        until("the next call ended") {
            store.count("select count(*) from model_calls where outcome = 'came_back'") == 1
        }

        then:
        triesOf(TicketWorkflow.SUMMARISE) == ["1 model system nothing_came_back", "2 model system yielded"]
        store.count("select count(*) from run_step_send_attempts") == 2

        and: "the lost call sent once only, by the process that lost it"
        old.requests.size() == 1
        model.requests.size() == 1
    }

    def "a review out as the system stopped is lost as nothing came back, which spends the try, said by its keys alone"() {
        given:
        def old = reviewOut()
        def measured = old.requests[1].model().unitsOf(old.requests[1].sent().characters())
        def fresh = wired(new ScriptedModelCalls())
        def logged = listening()

        when:
        fresh.restart.afterSingletonsInstantiated()

        then:
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("""
                select call.outcome || ' ' || call.sent_count || ' ' || coalesce(call.came_back_count::text, '-')
                  from model_calls call where call.purpose = 'review'""") == ["nothing_came_back ${measured} -" as String]
        store.texts("""
                select review.created_by_kind || ' ' || review.model_call_outcome || ' ' || review.lost_reason
                  from reviews review""") == ["system nothing_came_back nothing_came_back"]
        store.count("select count(*) from review_decisions") == 0

        and:
        triesOf(TicketWorkflow.SUMMARISE) == ["1 model system yielded"]
        reads(position(fresh, TicketWorkflow.GROUP, ticketRun(), TicketWorkflow.SUMMARISE)) == "running NEXT_TRY"
        holds() == []
        turnaways() == []

        and:
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def call = store.texts("select model_call_id::text from model_calls where purpose = 'review'")[0]
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def aTry = store.texts("select production_id::text from productions")[0]
        logged.list*.formattedMessage == ["Call ${call} reviewing try ${aTry} under run ${TicketWorkflow.RUN} was out"
                + " as this system stopped, and ended as nothing came back" as String]
        logged.list.every { !it.formattedMessage.contains("printer") }

        cleanup:
        RESTART_LOGGER.detachAppender(logged)
    }

    def "a review is settled from its latest turnaway, including equal creation times ordered by identity"() {
        given:
        def old = reviewOut()
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def production = store.texts("select production_id::text from model_calls where purpose = 'review'")[0]
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def call = store.texts("select model_call_id::text from model_calls where purpose = 'review'")[0]
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def values = store.texts("select production_value_id::text from production_values order by production_value_id")
        (0..<history.size()).reverse().each { index ->
            // DB-SPECIFIC: PostgreSQL ::uuid casts, now(), make_interval().
            store.session.sql("""
                    insert into model_call_turnaways (model_call_turnaway_id, model_call_id, said, created_at,
                                                       resent_at, created_by)
                    values (?::uuid, ?::uuid, 'Busy.', timestamp with time zone '2026-01-01 00:00:00+00'
                            + make_interval(secs => ?), case when ? then now() end, ?::uuid)
                    """).params("00000010-0000-4000-8000-000000000a0${index + 1}", call,
                    sameTime ? 0 : index, history[index], SYSTEM).update()
        }
        def before = turnawayRows()
        def fresh = wired(new ScriptedModelCalls())

        when:
        fresh.restart.afterSingletonsInstantiated()

        then:
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        store.texts("select outcome::text from model_calls where model_call_id = ?::uuid", call) == [outcome]
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select production_id::text from model_calls where purpose = 'review'") == [production]
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select production_value_id::text from production_values order by production_value_id") == values
        turnawayRows() == before
        store.count("select count(*) from reviews") == (outcome == "nothing_came_back" ? 1 : 0)
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select production_id::text || ' ' || model_call_id::text from reviews") ==
                (outcome == "nothing_came_back" ? [production + " " + call] : [])
        store.count("select count(*) from review_decisions") == 0
        store.count("select count(*) from run_step_holds") == 0
        store.count("select count(*) from run_step_failures") == 0
        store.count("select count(*) from run_step_send_attempts") == 2
        old.requests.size() == 2
        conservedSpend(fresh, TicketWorkflow.GROUP, ticketRun(), TicketWorkflow.SUMMARISE, TicketWorkflow.ANN_USER)
        RunBudget.spentAsRead(store.session, ticketRun(), true).spent() ==
                333 + (outcome == "nothing_came_back" ? old.requests[1].model().unitsOf(old.requests[1].sent().characters()) : 0)

        where:
        history       | sameTime || outcome
        [false, true] | false    || "nothing_came_back"
        [true, false] | false    || "turned_away"
        [false, true] | true     || "nothing_came_back"
        [true, false] | true     || "turned_away"
    }

    def "a lost model review resumes a new production only while tries remain"() {
        given:
        def old = reviewOut(tries)
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def original = store.texts("select production_id::text from model_calls where purpose = 'review'")[0]
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def originalValues = store.texts("select production_value_id::text from production_values order by production_value_id")
        def model = new ScriptedModelCalls().answering(cameBack(FITS)).answering(cameBack(ASSURES))
        def fresh = wired(model)
        fresh.restart.afterSingletonsInstantiated()

        when:
        fresh.restart.start()
        fresh.drained()

        then:
        old.requests.size() == 2
        model.requests*.purpose() == (tries == 2 ? [ModelCallPurpose.PRODUCE, ModelCallPurpose.REVIEW] : [])
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count("select count(*) from model_calls where production_id = ?::uuid", original) == 2
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        store.texts("select production_value_id::text from production_values where production_id = ?::uuid order by production_value_id",
                original) == originalValues
        store.count("select count(*) from productions where producer = 'model'") == (tries == 2 ? 2 : 1)
        store.count("select count(*) from run_step_send_attempts") == (tries == 2 ? 4 : 2)
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count("select count(*) from reviews where production_id = ?::uuid and lost_reason = 'nothing_came_back'", original) == 1
        if (tries == 2) {
            !model.requests[0].sent().user().contains('"refused"')
            triesOf(TicketWorkflow.SUMMARISE) == ["1 model system yielded", "2 model system yielded"]
        } else {
            reads(position(fresh, TicketWorkflow.GROUP, ticketRun(), TicketWorkflow.SUMMARISE)) ==
                    "failed, 1 of 1 tries spent"
        }
        conservedSpend(fresh, TicketWorkflow.GROUP, ticketRun(), TicketWorkflow.SUMMARISE, TicketWorkflow.ANN_USER)

        where:
        tries << [2, 1]
    }

    def "a lost code review uses the current release's rerun decision rather than its production's"() {
        given:
        def old = codeReviewOut(!mayRunAgain)
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def original = store.texts("select production_id::text from productions")[0]
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def originalRows = store.texts("select production_id::text || ' ' || may_run_again || ' ' || returned_by_code from productions")
        def again = reviewedCode(mayRunAgain, "R-new")
        def model = new ScriptedModelCalls().answering(cameBack('{"decisions":{"receipt":{"outcome":"assured"}}}'))
        def fresh = wired(model, CodeStepsHeld.of(again))

        when:
        fresh.started()
        fresh.drained()

        then:
        again.runs.get() == (mayRunAgain ? 1 : 0)
        old.requests.size() == 1
        model.requests*.purpose() == (mayRunAgain ? [ModelCallPurpose.REVIEW] : [])
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        store.texts("select production_id::text || ' ' || may_run_again || ' ' || returned_by_code from productions where production_id = ?::uuid",
                original) == originalRows
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count("select count(*) from model_calls where production_id = ?::uuid", original) == 1
        triesOf(CodeWorkflow.SEND) == (mayRunAgain ? ["1 code system yielded", "2 code system yielded"] : ["1 code system yielded"])
        if (!mayRunAgain) {
            reads(position(fresh, CodeWorkflow.GROUP, codeRun(), CodeWorkflow.SEND)) == "owed 2"
        }
        conservedSpend(fresh, CodeWorkflow.GROUP, codeRun(), CodeWorkflow.SEND, CodeWorkflow.ANN_USER)

        where:
        mayRunAgain << [true, false]
    }

    def "settling a review preserves an already-standing value and each of its multiple waiting values"() {
        given:
        def old = reviewOut(2, true)
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def production = store.texts("select production_id::text from productions")[0]
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def call = store.texts("select model_call_id::text from model_calls where purpose = 'review'")[0]
        if (!lost) {
            reviewTurnedAway()
        }
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def values = store.texts("select production_value_id::text from production_values order by production_value_id")
        def fresh = wired(new ScriptedModelCalls())

        when:
        fresh.restart.afterSingletonsInstantiated()
        def snapshot = store.transactions().execute { fresh.snapshots.asRead(groupId(TicketWorkflow.GROUP), ticketRun()) }
        def aTry = snapshot.step(new WorkflowStepId(UUID.fromString(TicketWorkflow.SUMMARISE))).orElseThrow().newest().orElseThrow()

        then:
        aTry.id().value().toString() == production
        aTry.values()*.id()*.value()*.toString().sort() == values
        aTry.values().collectEntries { value -> [(value.field()): ValueStanding.of(aTry, value)] } ==
                [already: ValueStanding.STANDS, extra: lost ? ValueStanding.REFUSED : ValueStanding.WAITING_ON_REVIEW,
                 summary: lost ? ValueStanding.REFUSED : ValueStanding.WAITING_ON_REVIEW]
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select production_id::text || ' ' || model_call_id::text from reviews") ==
                (lost ? [production + " " + call] : [])
        store.count("select count(*) from review_decisions") == 0
        store.count("select count(*) from productions") == 1
        store.count("select count(*) from model_calls") == 2
        store.count("select count(*) from run_step_holds") == 0
        old.requests.size() == 2
        conservedSpend(fresh, TicketWorkflow.GROUP, ticketRun(), TicketWorkflow.SUMMARISE, TicketWorkflow.ANN_USER)

        where:
        lost << [true, false]
    }

    def "a settled review turnaway waits for a real press through resume and reopening"() {
        given:
        reviewOut()
        reviewTurnedAway()
        if (stopped) {
            RunRows.stopped(store, TicketWorkflow.RUN, TicketWorkflow.CAT)
        }
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def production = store.texts("select production_id::text from productions")[0]
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def originalAttempt = store.texts("select run_step_send_attempt_id::text from model_calls where purpose = 'review'")[0]
        def model = new ScriptedModelCalls().answering(cameBack(ASSURES))
        def fresh = wired(model)

        when:
        fresh.started()
        fresh.drained()
        fresh.engine.goOn(groupId(TicketWorkflow.GROUP), ticketRun())
        fresh.drained()

        then:
        model.requests == []
        store.count("select count(*) from run_step_send_attempts") == 2
        store.count("select count(*) from reviews") == 0
        ticketStep(fresh).step().acts().contains("try_sending") == !stopped
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select production_id::text from productions") == [production]

        when:
        if (stopped) {
            openTicket()
            fresh.engine.goOn(groupId(TicketWorkflow.GROUP), ticketRun())
            fresh.drained()
        }

        then:
        model.requests == []
        ticketStep(fresh).step().acts().contains("try_sending")

        when:
        fresh.acts.trySending(groupId(TicketWorkflow.GROUP), ticketRun(),
                new WorkflowStepId(UUID.fromString(TicketWorkflow.SUMMARISE)), TicketWorkflow.CAT_USER)
        fresh.drained()

        then:
        model.requests*.purpose() == [ModelCallPurpose.REVIEW]
        model.requests[0].model().name().value() == "small"
        model.requests[0].mode() == null
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select mode::text from run_step_send_attempts where purpose = 'review'") == ["ordinary", "ordinary"]
        store.count("select count(*) from productions where producer = 'model'") == 1
        store.count("select count(*) from model_calls where purpose = 'review'") == 2
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select production_id::text from model_calls where purpose = 'review'") == [production, production]
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count("select count(*) from run_step_send_attempts where purpose = 'review' and run_step_send_attempt_id <> ?::uuid",
                originalAttempt) == 1
        turnaways() == ["Busy. not sent again"]
        conservedSpend(fresh, TicketWorkflow.GROUP, ticketRun(), TicketWorkflow.SUMMARISE, TicketWorkflow.ANN_USER)

        where:
        stopped << [false, true]
    }

    def "a yielded production with no review transaction is first reviewed by fresh resume, or after reopening"() {
        given:
        modelSeeded()
        reviewerOn(TicketWorkflow.SUMMARISE)
        def pause = new Pause()
        def old = new ScriptedModelCalls().pausing(pause, [], cameBack(FITS))
        def process = wired(old)
        ticketStarted(store, process)
        pause.awaitReached()
        process.closing()
        pause.release()
        process.drained()
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def production = store.texts("select production_id::text from productions")[0]
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def values = store.texts("select production_value_id::text from production_values")
        if (stopped) {
            RunRows.stopped(store, TicketWorkflow.RUN, TicketWorkflow.CAT)
        }
        def model = new ScriptedModelCalls().answering(cameBack(ASSURES))
        def fresh = wired(model)

        when:
        fresh.restart.afterSingletonsInstantiated()

        then:
        store.count("select count(*) from run_step_send_attempts where purpose = 'review'") == 0
        store.count("select count(*) from reviews") == 0
        store.count("select count(*) from model_calls where purpose = 'review'") == 0
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select coalesce(lost_reason::text, 'yielded') from productions") == ["yielded"]
        RunBudget.spentAsRead(store.session, ticketRun(), true).spent() == 333

        when:
        fresh.restart.start()
        fresh.drained()

        then:
        model.requests*.purpose() == (stopped ? [] : [ModelCallPurpose.REVIEW])

        when:
        if (stopped) {
            openTicket()
        }
        fresh.engine.goOn(groupId(TicketWorkflow.GROUP), ticketRun())
        fresh.drained()
        fresh.engine.goOn(groupId(TicketWorkflow.GROUP), ticketRun())
        fresh.drained()

        then:
        model.requests*.purpose() == [ModelCallPurpose.REVIEW]
        old.requests*.purpose() == [ModelCallPurpose.PRODUCE]
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select production_id::text from productions where producer = 'model'") == [production]
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select production_value_id::text from production_values") == values
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select production_id::text from model_calls where purpose = 'review'") == [production]
        store.count("select count(*) from run_step_send_attempts where purpose = 'review'") == 1
        store.count("select count(*) from reviews") == 1
        conservedSpend(fresh, TicketWorkflow.GROUP, ticketRun(), TicketWorkflow.SUMMARISE, TicketWorkflow.ANN_USER)

        where:
        stopped << [false, true]
    }

    def "a review settled twice stays settled when the abandoned process eventually returns"() {
        given:
        def old = reviewOut(1)
        def oldProcess = wirings.last()
        if (turnedAway) {
            reviewTurnedAway()
        }
        def fresh = wired(new ScriptedModelCalls())
        fresh.restart.afterSingletonsInstantiated()
        def settled = runRows()
        def spent = RunBudget.spentAsRead(store.session, ticketRun(), true)

        when:
        fresh.restart.afterSingletonsInstantiated()

        then:
        runRows() == settled

        when:
        abandoned.remove(0).call()
        oldProcess.drained()

        then:
        runRows() == settled
        RunBudget.spentAsRead(store.session, ticketRun(), true) == spent
        store.count("select count(*) from reviews") == (turnedAway ? 0 : 1)
        store.count("select count(*) from review_decisions") == 0
        store.count("select count(*) from run_step_holds") == 0
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select outcome::text from model_calls where purpose = 'review'") ==
                [turnedAway ? "turned_away" : "nothing_came_back"]
        old.requests.size() == 2

        where:
        turnedAway << [false, true]
    }

    def "a review waiting to be sent again as the system stopped ends turned away, holding nothing, its values still waiting"() {
        given:
        reviewOut()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into model_call_turnaways (model_call_id, said, created_by)
                select call.model_call_id, 'Busy.', ?::uuid from model_calls call where call.purpose = 'review'
                """).params(SYSTEM).update()
        def fresh = wired(new ScriptedModelCalls())
        def logged = listening()

        when:
        fresh.restart.afterSingletonsInstantiated()

        then:
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select outcome::text from model_calls where purpose = 'review'") == ["turned_away"]
        store.count("select count(*) from reviews") == 0
        holds() == []
        turnaways() == ["Busy. not sent again"]
        with(position(fresh, TicketWorkflow.GROUP, ticketRun(), TicketWorkflow.SUMMARISE) as StepPosition.AwaitingReview) {
            sending() == ReviewSending.TURNED_AWAY
        }
        triesOf(TicketWorkflow.SUMMARISE) == ["1 model system yielded"]

        and:
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def call = store.texts("select model_call_id::text from model_calls where purpose = 'review'")[0]
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def aTry = store.texts("select production_id::text from productions")[0]
        logged.list*.formattedMessage == ["Call ${call} reviewing try ${aTry} under run ${TicketWorkflow.RUN} waited to"
                + " be sent again as this system stopped, and ended turned away, what it reviews waiting on it still"
                as String]

        cleanup:
        RESTART_LOGGER.detachAppender(logged)
    }

    def "a call out is ended as its newest turnaway says, and no turnaway is added"() {
        given:
        callOut()
        history.eachWithIndex { boolean resent, int index ->
            // DB-SPECIFIC: PostgreSQL ::uuid casts, now(), make_interval().
            store.session.sql("""
                    insert into model_call_turnaways (model_call_id, said, created_at, resent_at, created_by)
                    select call.model_call_id, 'Busy.', now() + make_interval(secs => ?),
                           case when ? then now() + make_interval(secs => ?) end, ?::uuid
                      from model_calls call
                    """).params(index, resent, index, SYSTEM).update()
        }
        def before = turnaways()
        def fresh = wired(new ScriptedModelCalls())

        when:
        fresh.restart.afterSingletonsInstantiated()

        then:
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select outcome::text from model_calls") == [outcome]
        triesOf(TicketWorkflow.SUMMARISE) == ["${outcome == 'turned_away' ? '-' : '1'} model system ${tried}" as String]
        holds() == held
        reads(position(fresh, TicketWorkflow.GROUP, ticketRun(), TicketWorkflow.SUMMARISE)) == reading

        and:
        turnaways() == before

        where:
        history       || outcome             | tried               | held                     | reading
        []            || "nothing_came_back" | "nothing_came_back" | []                       | "running NEXT_TRY"
        [true]        || "nothing_came_back" | "nothing_came_back" | []                       | "running NEXT_TRY"
        [true, true]  || "nothing_came_back" | "nothing_came_back" | []                       | "running NEXT_TRY"
        [false]       || "turned_away"       | "open"              | ["turned_away true held"] | "held back TURNED_AWAY"
        [true, false] || "turned_away"       | "open"              | ["turned_away true held"] | "held back TURNED_AWAY"
    }

    def "a stopped tree is settled as any other, nothing goes on in it once runs go on, and opening it again does"() {
        given:
        codeRunning(3, true)
        RunRows.stopped(store, CodeWorkflow.RUN, CodeWorkflow.CAT)
        def again = scripted(true, { receipt("R-2") })
        def fresh = wired(new ScriptedModelCalls(), CodeStepsHeld.of(again))

        when:
        fresh.started()
        fresh.drained()

        then:
        triesOf(CodeWorkflow.SEND) == ["1 code system nothing_came_back"]
        again.runs.get() == 0

        when:
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
        store.session.sql("update run_stops set opened_again_at = now(), opened_again_by = ?::uuid")
                .params(CodeWorkflow.CAT).update()
        fresh.engine.drive(groupId(CodeWorkflow.GROUP), codeRun())
        until("check was asked") { triesOf(CodeWorkflow.CHECK).size() == 1 }

        then:
        triesOf(CodeWorkflow.SEND) == ["1 code system nothing_came_back", "2 code system yielded"]
        again.runs.get() == 1
    }

    def "settling again changes nothing, and writes no second hold"() {
        given:
        callOut()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into model_call_turnaways (model_call_id, said, created_by)
                select call.model_call_id, 'Busy.', ?::uuid from model_calls call
                """).params(SYSTEM).update()
        wired(new ScriptedModelCalls()).restart.afterSingletonsInstantiated()
        def once = runRows()

        when:
        wired(new ScriptedModelCalls()).restart.afterSingletonsInstantiated()

        then:
        runRows() == once
        holds() == ["turned_away true held"]
    }

    def "what is not out is left exactly as it was: a person's open try, an ended call, an ended try of code"() {
        given:
        TicketWorkflow.seed(store)
        ticketStarted(store, wired(new ScriptedModelCalls()))
        RunRows.called(store, TicketWorkflow.RUN, "came_back", 10, 5)
        CodeWorkflow.seed(store, 3)
        def runs = new AtomicInteger()
        def parked = new CountDownLatch(1)
        def release = new CountDownLatch(1)
        def old = new ScriptedCodeStep(CodeWorkflow.CODE_STEP, [CodeWorkflow.REPLY], [CodeWorkflow.RECEIPT], true, {
            if (runs.incrementAndGet() == 1) {
                throw new IllegalStateException("Refused.")
            }
            parked.countDown()
            release.await(60, TimeUnit.SECONDS)
            receipt()
        })
        def process = wired(new ScriptedModelCalls(), CodeStepsHeld.of(old))
        codeStarted(store, process)
        assert parked.await(20, TimeUnit.SECONDS)
        abandoned << {
            process.closing()
            release.countDown()
        }
        def ticketBefore = treeRows(TicketWorkflow.RUN)
        def firstCodeTry = codeTries()[0]

        when:
        wired(new ScriptedModelCalls(), CodeStepsHeld.of(scripted(true, { receipt() }))).restart
                .afterSingletonsInstantiated()

        then:
        codeTries() == [firstCodeTry, "2 ${SYSTEM} nothing_came_back true - -" as String]

        and:
        treeRows(TicketWorkflow.RUN) == ticketBefore
        firstCodeTry.startsWith("1 ${SYSTEM} errored true ")
        triesOf(TicketWorkflow.SUMMARISE) == ["1 person system open"]
    }

    private List<String> treeRows(String root) {
        // DB-SPECIFIC: PostgreSQL bool_or(), current_schema().
        def tables = store.texts("""
                select table_name || ' '
                       || case when bool_or(column_name = 'root_run_id') then 'root_run_id' else 'run_id' end
                  from information_schema.columns
                 where table_schema = current_schema() and column_name in ('root_run_id', 'run_id')
                 group by table_name order by table_name
                """)
        assert tables.containsAll(["model_calls root_run_id", "productions root_run_id", "run_steps run_id"])
        tables.collect { held ->
            def (table, column) = held.split(" ")
            // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, md5(), string_agg().
            store.session.sql("""
                    select coalesce(md5(string_agg(t::text, '|' order by t::text)), 'empty')
                      from ${table} t where t.${column} = ?::uuid""" as String).params(root).query(String).single()
        }
    }

    def "a tree whose settling fails is logged by its keys alone and left as it reads, and every other tree is settled"() {
        given:
        TicketWorkflow.seed(store)
        TicketWorkflow.run(store)
        RunRows.called(store, TicketWorkflow.RUN, null, 10, null)
        codeRunning(3, true)
        def fresh = wired(new ScriptedModelCalls(), CodeStepsHeld.of(scripted(true, { receipt() })))
        def logged = listening()

        when:
        fresh.restart.afterSingletonsInstantiated()

        then:
        codeTries() == ["1 ${SYSTEM} nothing_came_back true - -" as String]
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select coalesce(outcome::text, 'out') from model_calls") == ["out"]

        and:
        def failed = logged.list.findAll { it.level == Level.ERROR }
        failed*.formattedMessage == ["Run ${TicketWorkflow.RUN} of group ${TicketWorkflow.GROUP} could not be settled"
                + " as this system started, and is left as it reads" as String]
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def call = store.texts("select model_call_id::text from model_calls")[0]
        failed[0].throwableProxy.message ==
                "Call ${call} under run ${TicketWorkflow.RUN} was out to help, which nothing here settles yet" as String

        and: "settling went on to the other tree"
        fresh.restart.@settled

        cleanup:
        RESTART_LOGGER.detachAppender(logged)
    }

    def "a store the lead cannot be read from refuses the start, and runs are not let go on by themselves"() {
        given:
        store.session.sql("alter table model_calls rename to model_calls_elsewhere").update()
        def fresh = wired(new ScriptedModelCalls())

        when:
        fresh.restart.afterSingletonsInstantiated()

        then:
        thrown(DataAccessException)

        and:
        !fresh.restart.@settled
        fresh.tasks.submittedCount() == 0
    }
}
