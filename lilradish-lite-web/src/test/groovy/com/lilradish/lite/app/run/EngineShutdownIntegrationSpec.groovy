package com.lilradish.lite.app.run

import static com.lilradish.lite.app.run.fixture.CodeWorkflow.receipt
import static com.lilradish.lite.app.run.fixture.CodeWorkflow.scripted
import static com.lilradish.lite.testutil.library.LibraryStore.groupId

import com.lilradish.lite.app.run.fixture.CodeWorkflow
import com.lilradish.lite.app.run.fixture.TicketWorkflow
import com.lilradish.lite.domain.inference.CallOutcome
import com.lilradish.lite.domain.run.RunId
import com.lilradish.lite.domain.run.RunningOn
import com.lilradish.lite.domain.run.StepPosition
import com.lilradish.lite.domain.run.StepPositions
import com.lilradish.lite.domain.run.WorkflowStepId
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.codestep.CodeStepsHeld
import com.lilradish.lite.testutil.inference.Pause
import com.lilradish.lite.testutil.inference.ScriptedModelCalls
import com.lilradish.lite.testutil.library.LibraryStore
import com.lilradish.lite.testutil.run.RecordedModelCalls
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class EngineShutdownIntegrationSpec extends Specification {

    static final String FITS = '{"values":{"summary":"A printer fire."},"confidences":{}}'

    @Shared
    @AutoCleanup
    TestPostgres server = TestPostgres.worker()

    @Shared
    int databasesMade = 0

    LibraryStore store

    RecordedModelCalls model

    CountDownLatch codeGate

    EngineWiring wiring

    def setupSpec() {
        LibraryStore.prepareBaseline(server)
    }

    def setup() {
        store = LibraryStore.forCase(server, "engine_shutdown_" + (++databasesMade))
        TicketWorkflow.seed(store)
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                update workflow_steps set producer = 'model', producer_model = 'general', producer_mode = 'ordinary'
                 where workflow_step_id = ?::uuid""").params(TicketWorkflow.SUMMARISE).update()
        model = new RecordedModelCalls()
        codeGate = new CountDownLatch(1)
    }

    def cleanup() {
        codeGate.countDown()
        wiring.executor.destroy()
    }

    private void ticketStarted() {
        store.transactions().executeWithoutResult {
            TicketWorkflow.run(store)
            wiring.engine.planStarted(wiring.tree.lock(groupId(TicketWorkflow.GROUP), ticketRun()).orElseThrow())
        }
    }

    private void codeStarted() {
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
                select coalesce(try.try_number::text, '-') || ' ' || try.producer || ' ' || try.created_by_kind || ' '
                       || case when try.ended_at is null then 'open'
                               else coalesce(try.lost_reason::text, 'yielded') end
                  from productions try join run_steps held on held.run_step_id = try.run_step_id
                 where held.workflow_step_id = ?::uuid order by try.try_number
                """, step)
    }

    private StepPosition position(String group, RunId run, String step) {
        def read = store.transactions().execute { wiring.snapshots.asRead(groupId(group), run) }
        StepPositions.of(read, read.step(new WorkflowStepId(UUID.fromString(step))).orElseThrow())
    }

    def "a call out and code under way as the pools shut down are each written down before the shutdown returns"() {
        given:
        CodeWorkflow.seed(store, 1, true)
        def callOut = new Pause()
        model.scripted.pausing(callOut, [], new CallOutcome.CameBack(FITS, 321, 12, true, false, null, null))
        def code = scripted(false, { receipt() }, codeGate)
        wiring = new EngineWiring(store, model, CodeStepsHeld.of(code))
        ticketStarted()
        codeStarted()
        callOut.awaitReached()
        until("the code was running") { code.runs.get() == 1 }

        when:
        def shutting = Thread.start { wiring.executor.destroy() }
        until("the pools were stopping") { wiring.executor.stopping() }
        callOut.release()
        codeGate.countDown()
        shutting.join(TimeUnit.SECONDS.toMillis(20))

        then:
        !shutting.alive
        triesOf(TicketWorkflow.SUMMARISE) == ["1 model system yielded"]
        // DB-SPECIFIC: PostgreSQL ::text casts; || converts non-string operands to text.
        store.texts("select outcome::text || ' ' || came_back_count from model_calls") == ["came_back 12"]
        triesOf(CodeWorkflow.SEND) == ["1 code system yielded"]

        and: "each on its own pool, and what would follow the code not made"
        model.madeOn == [[true, false]]
        code.ran.every { it.thread() ==~ /run-code-\d+/ }
        triesOf(CodeWorkflow.CHECK) == []
        position(CodeWorkflow.GROUP, codeRun(), CodeWorkflow.CHECK) == new StepPosition.Running(RunningOn.NEXT_TRY)
    }

    def "code under way as the pools shut down ends before the shutdown returns, and the person's step after it is asked"() {
        given:
        CodeWorkflow.seed(store)
        def code = scripted(false, { receipt() }, codeGate)
        wiring = new EngineWiring(store, model, CodeStepsHeld.of(code))
        codeStarted()
        until("the code was running") { code.runs.get() == 1 }

        when:
        def shutting = Thread.start { wiring.executor.destroy() }
        until("the pools were stopping") { wiring.executor.stopping() }
        codeGate.countDown()
        shutting.join(TimeUnit.SECONDS.toMillis(20))

        then:
        !shutting.alive
        triesOf(CodeWorkflow.SEND) == ["1 code system yielded"]
        triesOf(CodeWorkflow.CHECK) == ["1 person system open"]

        and: "no second try of the code, and the code run once"
        store.count("select count(*) from productions where producer = 'code'") == 1
        code.runs.get() == 1
    }

    def "a model's try planned once stopping has begun is written but never sent, and reads as still to be sent"() {
        given:
        wiring = new EngineWiring(store, model, CodeStepsHeld.NONE)
        wiring.closing()

        when:
        ticketStarted()
        wiring.executor.destroy()

        then:
        triesOf(TicketWorkflow.SUMMARISE) == ["- model system open"]
        position(TicketWorkflow.GROUP, ticketRun(), TicketWorkflow.SUMMARISE) ==
                new StepPosition.Running(RunningOn.NEXT_TRY)

        and:
        store.count("select count(*) from run_step_send_attempts") == 0
        store.count("select count(*) from model_calls") == 0
        model.scripted.requests == []
    }
}
