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
import org.lilradish.lite.app.run.fixture.CodeWorkflow
import org.lilradish.lite.app.run.fixture.TicketWorkflow
import org.lilradish.lite.domain.inference.CallOutcome
import org.lilradish.lite.domain.inference.ModelCalls
import org.lilradish.lite.domain.run.ReviewSending
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.domain.run.StepFailure
import org.lilradish.lite.domain.run.StepPosition
import org.lilradish.lite.domain.run.StepPositions
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

/**
 * What a start settles of what a stopped process left out, on a real server running the real baseline. The rows are
 * left by an engine of its own that is then abandoned mid-call or mid-run, as a process killed leaves them: only
 * what it committed. A fresh engine over the same store settles them before it serves, and goes on once it does.
 */
class EngineRestartIntegrationSpec extends Specification {

    static final String SYSTEM = RunRows.WORKFLOW_RUNNER

    static final String FITS = '{"values":{"summary":"A printer fire."},"confidences":{}}'

    static final String ASSURES = '{"decisions":{"summary":{"outcome":"assured"}}}'

    static final Logger RESTART_LOGGER = LoggerFactory.getLogger(EngineRestart) as Logger

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    List<EngineWiring> wirings = []

    /** Whatever an abandoned engine waits on, let go once the feature is over so its threads end. */
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

    /** The ticket workflow with summarise made a model's, as {@link TicketWorkflow#seed} leaves it otherwise. */
    private void modelSeeded() {
        TicketWorkflow.seed(store)
        store.session.sql("""
                update workflow_steps set producer = 'model', producer_model = 'general', producer_mode = 'ordinary'
                 where workflow_step_id = ?::uuid""").params(TicketWorkflow.SUMMARISE).update()
    }

    /** As a start does, on {@code wiring}: the ticket run written and its first step planned, in one transaction. */
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

    /**
     * A process abandoned with its model's call out, paused just after it was sent. Let go only once stopping, as a
     * process killed drives nothing more.
     */
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

    /**
     * A process abandoned with the model the step names reviewing what summarise produced, its call paused just after
     * it was sent. Let go only once stopping, as a process killed drives nothing more.
     */
    private ScriptedModelCalls reviewOut() {
        modelSeeded()
        store.session.sql("""
                update workflow_steps set reviewer_model = 'small', reviewer_mode = 'ordinary'
                 where workflow_step_id = ?::uuid""").params(TicketWorkflow.SUMMARISE).update()
        def pause = new Pause()
        def old = new ScriptedModelCalls().answering(cameBack(FITS)).pausing(pause, [], cameBack(ASSURES))
        def process = wired(old)
        ticketStarted(store, process)
        pause.awaitReached()
        abandoned << {
            process.closing()
            pause.release()
        }
        old
    }

    /** A process abandoned with its code running, held at its gate, let go only once stopping. */
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
        new CallOutcome.CameBack(answer, 321, 12, true, false)
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

    /** Each try of a step: its number, producer, who asked for it, and how it stands. */
    private List<String> triesOf(String step) {
        store.texts("""
                select try.try_number || ' ' || try.producer || ' ' || try.created_by_kind || ' '
                       || case when try.ended_at is null then 'open'
                               else coalesce(try.lost_reason::text, 'yielded') end
                  from productions try join run_steps held on held.run_step_id = try.run_step_id
                 where held.workflow_step_id = ?::uuid order by try.try_number
                """, step)
    }

    /** Each try of code: its number, who ended it, how, whether it may run again, and anything it said or gave. */
    private List<String> codeTries() {
        store.texts("""
                select try.try_number || ' ' || coalesce(try.ended_by::text, '-') || ' '
                       || coalesce(try.lost_reason::text, case when try.ended_at is null then 'open' else 'yielded' end)
                       || ' ' || try.may_run_again || ' ' || coalesce(try.lost_detail, '-') || ' '
                       || coalesce(try.returned_by_code, '-')
                  from productions try where try.producer = 'code' order by try.try_number
                """)
    }

    /** Each call: its try, how it ended, what was sent and came back, whether the model counted, what it answered. */
    private List<String> callRows() {
        store.texts("""
                select try.try_number || ' ' || coalesce(call.outcome::text, 'out') || ' ' || call.sent_count || ' '
                       || coalesce(call.came_back_count::text, '-') || ' ' || call.counted_by_model || ' '
                       || coalesce(call.answer, '-')
                  from model_calls call join productions try on try.production_id = call.production_id
                 order by call.created_at, call.model_call_id
                """)
    }

    private List<String> holds() {
        store.texts("""
                select hold.reason || ' ' || (hold.run_step_send_attempt_id = call.run_step_send_attempt_id) || ' '
                       || coalesce(hold.released_at::text, 'held')
                  from run_step_holds hold
                  left join model_calls call on call.run_step_id = hold.run_step_id
                 order by hold.created_at, hold.run_step_hold_id
                """)
    }

    private List<String> turnaways() {
        store.texts("""
                select coalesce(said, '-') || ' ' || coalesce(resent_at::text, 'not sent again')
                  from model_call_turnaways order by created_at, model_call_turnaway_id
                """)
    }

    /** Every table the baseline made, read off the catalogue, so one a later stage adds is held too. */
    private String runRows() {
        store.contents()
    }

    private StepPosition position(EngineWiring wiring, String group, RunId run, String step) {
        def read = store.transactions().execute { wiring.snapshots.asRead(groupId(group), run) }
        StepPositions.of(read, read.step(new WorkflowStepId(UUID.fromString(step))).orElseThrow())
    }

    /** How a step reads, less when: a position's time is its end's, which no spec can name ahead. */
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
        def call = store.texts("select model_call_id::text from model_calls")[0]
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
        def call = store.texts("select model_call_id::text from model_calls where purpose = 'review'")[0]
        def aTry = store.texts("select production_id::text from productions")[0]
        logged.list*.formattedMessage == ["Call ${call} reviewing try ${aTry} under run ${TicketWorkflow.RUN} was out"
                + " as this system stopped, and ended as nothing came back" as String]
        logged.list.every { !it.formattedMessage.contains("printer") }

        cleanup:
        RESTART_LOGGER.detachAppender(logged)
    }

    /** Written straight in, as the turnaways of a call to produce are: how they came to be is no concern here. */
    def "a review waiting to be sent again as the system stopped ends turned away, holding nothing, its values still waiting"() {
        given:
        reviewOut()
        store.session.sql("""
                insert into model_call_turnaways (model_call_id, said, created_by)
                select call.model_call_id, 'Busy.', ?::uuid from model_calls call where call.purpose = 'review'
                """).params(SYSTEM).update()
        def fresh = wired(new ScriptedModelCalls())
        def logged = listening()

        when:
        fresh.restart.afterSingletonsInstantiated()

        then:
        store.texts("select outcome::text from model_calls where purpose = 'review'") == ["turned_away"]
        store.count("select count(*) from reviews") == 0
        holds() == []
        turnaways() == ["Busy. not sent again"]
        with(position(fresh, TicketWorkflow.GROUP, ticketRun(), TicketWorkflow.SUMMARISE) as StepPosition.AwaitingReview) {
            sending() == ReviewSending.TURNED_AWAY
        }
        triesOf(TicketWorkflow.SUMMARISE) == ["1 model system yielded"]

        and:
        def call = store.texts("select model_call_id::text from model_calls where purpose = 'review'")[0]
        def aTry = store.texts("select production_id::text from productions")[0]
        logged.list*.formattedMessage == ["Call ${call} reviewing try ${aTry} under run ${TicketWorkflow.RUN} waited to"
                + " be sent again as this system stopped, and ended turned away, what it reviews waiting on it still"
                as String]

        cleanup:
        RESTART_LOGGER.detachAppender(logged)
    }

    /** Written straight in: how the turnaways came to be there is no concern of what settles them. */
    def "a call out is ended as its newest turnaway says, and no turnaway is added"() {
        given:
        callOut()
        history.eachWithIndex { boolean resent, int index ->
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
        store.texts("select outcome::text from model_calls") == [outcome]
        triesOf(TicketWorkflow.SUMMARISE) == ["1 model system ${tried}" as String]
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

    /** A code step that fails its first run and is caught running its second, beside a run a person is answering. */
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

    /**
     * One tree's rows of every table carrying a root, read off the catalogue so a table a later stage adds is held
     * too, as one string per table; a table carrying only its run is read by the root's own run.
     */
    private List<String> treeRows(String root) {
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
            store.session.sql("""
                    select coalesce(md5(string_agg(t::text, '|' order by t::text)), 'empty')
                      from ${table} t where t.${column} = ?::uuid""" as String).params(root).query(String).single()
        }
    }

    /** No end is written for a call to help yet, so a tree holding one out fails as it is settled. */
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
        store.texts("select coalesce(outcome::text, 'out') from model_calls") == ["out"]

        and:
        def failed = logged.list.findAll { it.level == Level.ERROR }
        failed*.formattedMessage == ["Run ${TicketWorkflow.RUN} of group ${TicketWorkflow.GROUP} could not be settled"
                + " as this system started, and is left as it reads" as String]
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
        fresh.executor.@pool.threadPoolExecutor.taskCount == 0
    }
}
