package org.lilradish.lite.app.run

import static org.lilradish.lite.app.run.fixture.CodeWorkflow.receipt
import static org.lilradish.lite.app.run.fixture.CodeWorkflow.scripted
import static org.lilradish.lite.testutil.library.LibraryStore.FIRST_STEWARD
import static org.lilradish.lite.testutil.library.LibraryStore.groupId

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.util.concurrent.TimeUnit
import org.lilradish.lite.app.codestep.CodeSteps
import org.lilradish.lite.app.run.fixture.CodeWorkflow
import org.lilradish.lite.app.run.fixture.StepRows
import org.lilradish.lite.app.run.fixture.TicketWorkflow
import org.lilradish.lite.domain.inference.CallOutcome
import org.lilradish.lite.domain.inference.ModelCalls
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.domain.run.RunningOn
import org.lilradish.lite.domain.run.StepPosition
import org.lilradish.lite.domain.run.StepPositions
import org.lilradish.lite.domain.run.WorkflowStepId
import org.lilradish.lite.testutil.SnapshottingAppender
import org.lilradish.lite.testutil.codestep.CodeStepsHeld
import org.lilradish.lite.testutil.inference.ScriptedModelCalls
import org.lilradish.lite.testutil.library.LibraryStore
import org.lilradish.lite.testutil.run.RunRows
import org.slf4j.LoggerFactory
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * Every run at the top not stopped goes on by itself once a started system serves, on a real server running the real
 * baseline: whatever a stopping engine left to be made, whatever a stop let go left held, whatever a release now holds
 * again, and a run nothing was ever planned for. A run stopped, waiting on a person, or done is left as it is.
 */
class EngineResumptionIntegrationSpec extends Specification {

    static final String SYSTEM = RunRows.WORKFLOW_RUNNER

    static final String FITS = '{"values":{"summary":"A printer fire."},"confidences":{}}'

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    List<EngineWiring> wirings = []

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, "engine_resumption_" + (++databasesMade))
    }

    def cleanup() {
        wirings.each { it.executor.destroy() }
    }

    private EngineWiring wired(ModelCalls modelCalls, CodeSteps release = CodeStepsHeld.NONE) {
        def wiring = new EngineWiring(store, modelCalls, release)
        wirings << wiring
        wiring
    }

    /** An engine that was stopping as it planned: nothing going out is made, and the process then ended. */
    private EngineWiring stopping(ModelCalls modelCalls, CodeSteps release = CodeStepsHeld.NONE) {
        def wiring = wired(modelCalls, release)
        wiring.closing()
        wiring
    }

    private void modelSeeded() {
        TicketWorkflow.seed(store)
        store.session.sql("""
                update workflow_steps set producer = 'model', producer_model = 'general', producer_mode = 'ordinary'
                 where workflow_step_id = ?::uuid""").params(TicketWorkflow.SUMMARISE).update()
    }

    private void ticketStarted(EngineWiring wiring) {
        store.transactions().executeWithoutResult {
            TicketWorkflow.run(store)
            wiring.engine.planStarted(wiring.tree.lock(groupId(TicketWorkflow.GROUP), ticketRun()).orElseThrow())
        }
    }

    private void codeStarted(EngineWiring wiring) {
        store.transactions().executeWithoutResult {
            CodeWorkflow.run(store)
            wiring.engine.planStarted(wiring.tree.lock(groupId(CodeWorkflow.GROUP), codeRun()).orElseThrow())
        }
    }

    private static RunId ticketRun() {
        new RunId(UUID.fromString(TicketWorkflow.RUN))
    }

    private static RunId codeRun() {
        new RunId(UUID.fromString(CodeWorkflow.RUN))
    }

    private static CallOutcome.CameBack cameBack(String answer) {
        new CallOutcome.CameBack(answer, 321, 12, true, false)
    }

    private static void until(String what, Closure<Boolean> reached) {
        def deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!reached()) {
            assert System.nanoTime() < deadline: "never ${what}"
            Thread.sleep(10)
        }
    }

    private List<String> triesOf(String step) {
        store.texts("""
                select try.try_number || ' ' || try.producer || ' ' || try.created_by_kind || ' '
                       || case when try.ended_at is null then 'open'
                               else coalesce(try.lost_reason::text, 'yielded') end
                  from productions try join run_steps held on held.run_step_id = try.run_step_id
                 where held.workflow_step_id = ?::uuid order by try.try_number
                """, step)
    }

    private List<String> holds() {
        store.texts("""
                select reason || ' ' || created_by || ' ' || coalesce(released_by::text, '-')
                  from run_step_holds order by created_at, run_step_hold_id
                """)
    }

    private List<String> runRows() {
        ["run_steps", "productions", "production_inputs", "production_values", "run_step_holds",
         "run_step_send_attempts", "model_calls", "run_step_failures"].collect { store.digestOf(it) }
    }

    private StepPosition position(EngineWiring wiring, String group, RunId run, String step) {
        def read = store.transactions().execute { wiring.snapshots.asRead(groupId(group), run) }
        StepPositions.of(read, read.step(new WorkflowStepId(UUID.fromString(step))).orElseThrow())
    }

    def "a model's try left unsent by an engine that was stopping is sent once runs go on, once"() {
        given:
        modelSeeded()
        def old = new ScriptedModelCalls()
        def planner = stopping(old)
        ticketStarted(planner)
        def before = position(planner, TicketWorkflow.GROUP, ticketRun(), TicketWorkflow.SUMMARISE)
        def model = new ScriptedModelCalls().answering(cameBack(FITS))
        def fresh = wired(model)

        when:
        fresh.started()
        until("the call ended") { store.count("select count(*) from model_calls where outcome is not null") == 1 }
        fresh.drained()

        then:
        before == new StepPosition.Running(RunningOn.NEXT_TRY)
        triesOf(TicketWorkflow.SUMMARISE) == ["1 model system yielded"]

        and:
        model.requests.size() == 1
        old.requests == []
        store.count("select count(*) from model_calls") == 1
    }

    def "a code step's try left unmade by an engine that was stopping is made once runs go on"() {
        given:
        CodeWorkflow.seed(store)
        def old = scripted(false, { receipt("R-old") })
        codeStarted(stopping(new ScriptedModelCalls(), CodeStepsHeld.of(old)))
        def again = scripted(false, { receipt() })
        def fresh = wired(new ScriptedModelCalls(), CodeStepsHeld.of(again))

        when:
        fresh.started()
        until("check was asked") { triesOf(CodeWorkflow.CHECK).size() == 1 }

        then:
        triesOf(CodeWorkflow.SEND) == ["1 code system yielded"]
        again.runs.get() == 1

        and:
        old.runs.get() == 0
    }

    def "a run nothing was ever planned for has its first step asked once runs go on"() {
        given:
        TicketWorkflow.seed(store)
        TicketWorkflow.run(store)
        def fresh = wired(new ScriptedModelCalls())

        when:
        fresh.started()
        fresh.drained()

        then:
        triesOf(TicketWorkflow.SUMMARISE) == ["1 person system open"]
        triesOf(TicketWorkflow.CONFIRM) == []
    }

    /** The summary made to stand as given, so that the step after it is due once it is answered. */
    def "the step after one whose values stood, never asked, is asked once runs go on"() {
        given:
        TicketWorkflow.seed(store)
        store.session.sql("update declaration_fields set standing = 'always' where declaration_field_id = ?::uuid")
                .params(TicketWorkflow.SUMMARY).update()
        ticketStarted(wired(new ScriptedModelCalls()))
        StepRows.answered(store, TicketWorkflow.SUMMARISE, TicketWorkflow.CAT, "Read it.",
                [(TicketWorkflow.SUMMARY): '"A printer fire."'])
        def fresh = wired(new ScriptedModelCalls())

        when:
        fresh.started()
        fresh.drained()

        then:
        triesOf(TicketWorkflow.CONFIRM) == ["1 person system open"]
        triesOf(TicketWorkflow.SUMMARISE) == ["1 person system yielded"]
    }

    def "a hold whose stop was let go with nothing gone on since is released once runs go on, and its try asked"() {
        given:
        TicketWorkflow.seed(store)
        store.stopped(TicketWorkflow.SUMMARISE_QUESTION, FIRST_STEWARD)
        ticketStarted(wired(new ScriptedModelCalls()))
        store.session.sql("update entry_stops set let_go_at = now(), let_go_by = ?::uuid where entry_id = ?::uuid")
                .params(FIRST_STEWARD, TicketWorkflow.SUMMARISE_QUESTION).update()
        def heldBefore = holds()
        def fresh = wired(new ScriptedModelCalls())

        when:
        fresh.started()
        fresh.drained()

        then:
        heldBefore == ["entry_stopped ${SYSTEM} -" as String]
        holds() == ["entry_stopped ${SYSTEM} ${SYSTEM}" as String]
        triesOf(TicketWorkflow.SUMMARISE) == ["1 person system open"]
    }

    def "a code step held because the release did not hold it goes on once a release holding it serves"() {
        given:
        CodeWorkflow.seed(store)
        codeStarted(wired(new ScriptedModelCalls(), CodeStepsHeld.NONE))
        def heldBefore = holds()
        def released = scripted(false, { receipt() })
        def fresh = wired(new ScriptedModelCalls(), CodeStepsHeld.of(released))

        when:
        fresh.started()
        until("check was asked") { triesOf(CodeWorkflow.CHECK).size() == 1 }

        then:
        heldBefore == ["code_step_not_held ${SYSTEM} -" as String]
        holds() == ["code_step_not_held ${SYSTEM} ${SYSTEM}" as String]
        triesOf(CodeWorkflow.SEND) == ["1 code system yielded"]
        released.runs.get() == 1
    }

    def "a stopped run is left as it is, and a run waiting on a person has nothing written of it"() {
        given:
        TicketWorkflow.seed(store)
        TicketWorkflow.run(store)
        RunRows.stopped(store, TicketWorkflow.RUN, TicketWorkflow.CAT)
        CodeWorkflow.seed(store)
        codeStarted(wired(new ScriptedModelCalls(), CodeStepsHeld.of(scripted(false, { receipt() }))))
        until("check was asked") { triesOf(CodeWorkflow.CHECK).size() == 1 }
        wirings.each { it.drained() }
        def before = runRows()
        def fresh = wired(new ScriptedModelCalls(), CodeStepsHeld.of(scripted(false, { receipt() })))

        when:
        fresh.started()
        fresh.drained()

        then:
        runRows() == before
        store.count("select count(*) from run_steps where run_id = ?::uuid", TicketWorkflow.RUN) == 0
    }

    def "a run done has nothing written of it once runs go on"() {
        given:
        CodeWorkflow.seed(store, 1, true)
        def code = scripted(false, { receipt() })
        codeStarted(wired(new ScriptedModelCalls(), CodeStepsHeld.of(code)))
        until("both steps ended") {
            store.count("select count(*) from productions where ended_at is not null") == 2
        }
        wirings.each { it.drained() }
        def before = runRows()
        def again = scripted(false, { receipt() })
        def fresh = wired(new ScriptedModelCalls(), CodeStepsHeld.of(again))

        when:
        fresh.started()
        fresh.drained()

        then:
        runRows() == before
        again.runs.get() == 0
    }

    def "the walk crosses from one batch of runs to the next, and leaves none out"() {
        given:
        TicketWorkflow.seed(store)
        def runs = (1..201).collect { number ->
            def run = UUID.randomUUID().toString()
            TicketWorkflow.run(store, run, number)
            run
        }
        def fresh = wired(new ScriptedModelCalls())

        when:
        fresh.started()
        until("every run was asked its first step") {
            store.count("select count(*) from productions") == 201
        }
        fresh.drained()

        then:
        store.texts("select run_id::text from productions order by run_id") == runs.toSorted()
        store.count("select count(distinct run_id) from productions") == 201
    }

    def "an engine already stopping as runs are to go on drives none of them"() {
        given:
        TicketWorkflow.seed(store)
        TicketWorkflow.run(store)
        def fresh = wired(new ScriptedModelCalls())
        fresh.closing()

        when:
        fresh.started()
        fresh.drained()

        then:
        store.count("select count(*) from run_steps") == 0
    }

    /** Stopped as a lifecycle, on a refresh cancelled or a context stopped, and not closing: the walk ends there too. */
    def "a walk once stopped as a lifecycle drives none of the runs, though the engine is not stopping"() {
        given:
        TicketWorkflow.seed(store)
        TicketWorkflow.run(store)
        def fresh = wired(new ScriptedModelCalls())
        fresh.restart.afterSingletonsInstantiated()
        fresh.restart.stop()

        when:
        fresh.restart.resume()

        then:
        store.count("select count(*) from run_steps") == 0
        !fresh.executor.stopping()
    }

    /** Renamed away by the spec, which the store never does, so the batch read fails where nothing else is read. */
    def "a batch of runs that cannot be read ends the walk, logging the last run reached and the failure's kind alone"() {
        given:
        TicketWorkflow.seed(store)
        TicketWorkflow.run(store)
        store.session.sql("alter table run_stops rename to run_stops_elsewhere").update()
        def fresh = wired(new ScriptedModelCalls())
        def restartLogger = LoggerFactory.getLogger(EngineRestart) as Logger
        def logged = new SnapshottingAppender()
        logged.start()
        restartLogger.addAppender(logged)

        when:
        fresh.started()
        fresh.drained()

        then:
        logged.list*.formattedMessage.size() == 1
        logged.list[0].formattedMessage ==~ /Runs after run 00000000-0000-0000-0000-000000000000 could not be read to go/ +
                / on by themselves, failing with [\w.]+; they go on as they are next driven/
        logged.list[0].level == Level.ERROR
        logged.list[0].throwableProxy == null

        and:
        store.count("select count(*) from run_steps") == 0

        cleanup:
        restartLogger.detachAppender(logged)
    }
}
