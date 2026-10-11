package com.lilradish.lite.app.run

import static com.lilradish.lite.app.run.fixture.CodeWorkflow.receipt
import static com.lilradish.lite.app.run.fixture.CodeWorkflow.scripted
import static com.lilradish.lite.testutil.library.LibraryStore.FIRST_STEWARD
import static com.lilradish.lite.testutil.library.LibraryStore.groupId

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.lilradish.lite.app.codestep.CodeSteps
import com.lilradish.lite.app.run.fixture.CodeWorkflow
import com.lilradish.lite.app.run.fixture.StepRows
import com.lilradish.lite.app.run.fixture.TicketWorkflow
import com.lilradish.lite.app.run.fixture.TreeWorkflows
import com.lilradish.lite.domain.inference.CallOutcome
import com.lilradish.lite.domain.inference.ModelCalls
import com.lilradish.lite.domain.run.RunId
import com.lilradish.lite.domain.run.RunningOn
import com.lilradish.lite.domain.run.StepPosition
import com.lilradish.lite.domain.run.StepPositions
import com.lilradish.lite.domain.run.WorkflowStepId
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.SnapshottingAppender
import com.lilradish.lite.testutil.codestep.CodeStepsHeld
import com.lilradish.lite.testutil.inference.ScriptedModelCalls
import com.lilradish.lite.testutil.library.LibraryStore
import com.lilradish.lite.testutil.run.RunRows
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class EngineResumptionIntegrationSpec extends Specification {

    static final String SYSTEM = RunRows.WORKFLOW_RUNNER

    static final String FITS = '{"values":{"summary":"A printer fire."},"confidences":{}}'

    @Shared
    @AutoCleanup
    TestPostgres server = TestPostgres.worker()

    @Shared
    int databasesMade = 0

    LibraryStore store

    List<EngineWiring> wirings = []

    def setupSpec() {
        LibraryStore.prepareBaseline(server)
    }

    def setup() {
        store = LibraryStore.forCase(server, "engine_resumption_" + (++databasesMade),
                specificationContext.currentFeature.testTags*.value.contains("postgres-schema-changing"))
    }

    def cleanup() {
        wirings.each { it.executor.destroy() }
    }

    private EngineWiring wired(ModelCalls modelCalls, CodeSteps release = CodeStepsHeld.NONE) {
        def wiring = new EngineWiring(store, modelCalls, release)
        wirings << wiring
        wiring
    }

    private EngineWiring stopping(ModelCalls modelCalls, CodeSteps release = CodeStepsHeld.NONE) {
        def wiring = wired(modelCalls, release)
        wiring.closing()
        wiring
    }

    private void modelSeeded() {
        TicketWorkflow.seed(store)
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
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
        new CallOutcome.CameBack(answer, 321, 12, true, false, null, null)
    }

    private static void until(String what, Closure<Boolean> reached) {
        def deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!reached()) {
            assert System.nanoTime() < deadline: "never ${what}"
            Thread.sleep(10)
        }
    }

    private List<String> triesOf(String step) {
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts; || converts non-string operands to text.
        store.texts("""
                select try.try_number || ' ' || try.producer || ' ' || try.created_by_kind || ' '
                       || case when try.ended_at is null then 'open'
                               else coalesce(try.lost_reason::text, 'yielded') end
                  from productions try join run_steps held on held.run_step_id = try.run_step_id
                 where held.workflow_step_id = ?::uuid order by try.try_number
                """, step)
    }

    private List<String> holds() {
        // DB-SPECIFIC: PostgreSQL ::text casts; || converts non-string operands to text.
        store.texts("""
                select reason || ' ' || created_by || ' ' || coalesce(released_by::text, '-')
                  from run_step_holds order by created_at, run_step_hold_id
                """)
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

    def "the step after one whose values stood, never asked, is asked once runs go on"() {
        given:
        TicketWorkflow.seed(store)
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
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
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
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
        def before = store.contents()
        def fresh = wired(new ScriptedModelCalls(), CodeStepsHeld.of(scripted(false, { receipt() })))

        when:
        fresh.started()
        fresh.drained()

        then:
        store.contents() == before
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
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
        def before = store.contents()
        def again = scripted(false, { receipt() })
        def fresh = wired(new ScriptedModelCalls(), CodeStepsHeld.of(again))

        when:
        fresh.started()
        fresh.drained()

        then:
        store.contents() == before
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
        // DB-SPECIFIC: PostgreSQL ::text casts.
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

    // Lifecycle stopping must also end the walk when closing has not begun.
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

    @spock.lang.Tag("postgres-schema-changing")
    def "a batch of runs that cannot be read ends the walk, logging the last run reached and the failure's kind alone"() {
        given:
        TicketWorkflow.seed(store)
        TicketWorkflow.run(store)
        // DB-SPECIFIC: PostgreSQL ALTER TABLE RENAME TO.
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

    def "a run whose first step runs a workflow, with nothing beneath it yet, has that run started once runs go on, once"() {
        given:
        TreeWorkflows.seed(store)
        TreeWorkflows.run(store, TreeWorkflows.ESCALATE, TreeWorkflows.ESCALATE_VERSION, '{"ticket":"A fire."}')
        def fresh = wired(new ScriptedModelCalls())

        when:
        fresh.started()
        fresh.drained()

        then:
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        store.texts("select entry_version_id::text from runs where parent_run_id = ?::uuid", TreeWorkflows.ROOT) ==
                [TreeWorkflows.NOTE_VERSION]
        triesOf(TreeWorkflows.WRITE_NOTE) == ["1 person system open"]

        and:
        triesOf(TreeWorkflows.CHECK) == []
    }

    // Hand over the next child only after commit; a stopping engine must not hand it over.
    def "a run beneath left unstarted by an engine that was stopping is started once runs go on"() {
        given:
        TreeWorkflows.seed(store)
        def planner = deep(3)
        planner.closing()
        store.transactions().executeWithoutResult {
            TreeWorkflows.run(store, TreeWorkflows.LOOP, TreeWorkflows.LOOP_VERSION, '{}')
            planner.engine.planStarted(planner.tree.lock(groupId(TreeWorkflows.GROUP),
                    new RunId(UUID.fromString(TreeWorkflows.ROOT))).orElseThrow())
        }
        // Drain first so any unexpected handover would finish before the assertion.
        planner.drained()
        def leftByPlanner = runsOf(TreeWorkflows.ROOT)
        def fresh = deep(3)

        when:
        fresh.started()
        fresh.drained()

        then:
        leftByPlanner == ["1 0", "2 1"]
        runsOf(TreeWorkflows.ROOT) == ["1 0", "2 1", "3 2"]
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select reason::text from run_step_failures") == ["too_deep"]
    }

    def "a model's try in a run beneath, left unsent by an engine that was stopping, is sent once runs go on and the run above goes on"() {
        given:
        TreeWorkflows.seed(store)
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                update workflow_steps set producer = 'model', producer_model = 'general', producer_mode = 'ordinary'
                 where workflow_step_id = ?::uuid""").params(TreeWorkflows.WRITE_NOTE).update()
        def planner = stopping(new ScriptedModelCalls())
        store.transactions().executeWithoutResult {
            TreeWorkflows.run(store, TreeWorkflows.ESCALATE, TreeWorkflows.ESCALATE_VERSION, '{"ticket":"A fire."}')
            planner.engine.planStarted(planner.tree.lock(groupId(TreeWorkflows.GROUP),
                    new RunId(UUID.fromString(TreeWorkflows.ROOT))).orElseThrow())
        }
        // DB-SPECIFIC: PostgreSQL ::text casts; || converts non-string operands to text.
        def leftByPlanner = store.texts("""
                select coalesce(try.try_number::text, 'unnumbered') || ' ' || try.producer || ' '
                       || (try.ended_at is null) || ' ' || (try.run_id = try.root_run_id)
                  from productions try
                """)
        def model = new ScriptedModelCalls().answering(cameBack('{"values":{"note":"A note."},"confidences":{}}'))
        def fresh = wired(model)

        when:
        fresh.started()
        until("the run above asked its next step") { triesOf(TreeWorkflows.CHECK).size() == 1 }
        fresh.drained()

        then:
        leftByPlanner == ["unnumbered model true false"]
        triesOf(TreeWorkflows.WRITE_NOTE) == ["1 model system yielded"]
        triesOf(TreeWorkflows.CHECK) == ["1 person system open"]

        and:
        model.requests.size() == 1
        store.count("select count(*) from model_calls") == 1
    }

    private EngineWiring deep(int maximumDepth) {
        def wiring = TreeWorkflows.wired(store, maximumDepth)
        wirings << wiring
        wiring
    }

    private List<String> runsOf(String root) {
        // DB-SPECIFIC: PostgreSQL ::uuid casts; || converts non-string operands to text.
        store.texts("select number || ' ' || depth from runs where root_run_id = ?::uuid order by number", root)
    }
}
