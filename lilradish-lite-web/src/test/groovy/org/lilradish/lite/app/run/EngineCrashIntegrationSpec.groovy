package org.lilradish.lite.app.run

import static org.lilradish.lite.app.run.fixture.CodeWorkflow.receipt
import static org.lilradish.lite.app.run.fixture.CodeWorkflow.scripted
import static org.lilradish.lite.testutil.library.LibraryStore.groupId

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.lilradish.lite.app.run.fixture.CodeWorkflow
import org.lilradish.lite.app.run.fixture.TicketWorkflow
import org.lilradish.lite.domain.inference.CallOutcome
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.testutil.SnapshottingAppender
import org.lilradish.lite.testutil.codestep.CodeStepsHeld
import org.lilradish.lite.testutil.codestep.ScriptedCodeStep
import org.lilradish.lite.testutil.inference.Pause
import org.lilradish.lite.testutil.inference.ScriptedModelCalls
import org.lilradish.lite.testutil.library.LibraryStore
import org.slf4j.LoggerFactory
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * A process killed with a model's call out and code running, on a real server running the real baseline. The killed
 * process is an engine of its own left parked mid-call and mid-run and never shut down: the store sees only what it
 * committed, as after a kill, since no call and no code runs inside a transaction. A second engine over the same store
 * starts, settles and goes on; what the first says when it wakes changes nothing, and says so by its keys alone.
 */
class EngineCrashIntegrationSpec extends Specification {

    static final String SAID = '{"values":{"summary":"invoice 4471 is on fire."},"confidences":{}}'

    static final String FITS = '{"values":{"summary":"A printer fire."},"confidences":{}}'

    static final List<Logger> LATE_LOGGERS = [EngineCalls, EngineCodes, RunEngine, EngineExecutor].collect {
        LoggerFactory.getLogger(it) as Logger
    }

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    /** The process killed: its model paused mid-call, its code held at its gate. */
    EngineWiring killed

    ScriptedModelCalls killedModel

    ScriptedCodeStep killedCode

    Pause callOut

    CountDownLatch codeRunning

    /** The process started after it. */
    EngineWiring started

    ScriptedModelCalls startedModel

    ScriptedCodeStep startedCode

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, "engine_crash_" + (++databasesMade))
        TicketWorkflow.seed(store)
        store.session.sql("""
                update workflow_steps set producer = 'model', producer_model = 'general', producer_mode = 'ordinary'
                 where workflow_step_id = ?::uuid""").params(TicketWorkflow.SUMMARISE).update()
        CodeWorkflow.seed(store, 2)
    }

    def cleanup() {
        killed?.closing()
        callOut?.release()
        codeRunning?.countDown()
        killed?.executor?.destroy()
        started?.executor?.destroy()
    }

    /** The first process started both runs and was killed with both out; the second started and went on. */
    private void killedAndStartedAgain() {
        callOut = new Pause()
        codeRunning = new CountDownLatch(1)
        killedModel = new ScriptedModelCalls().pausing(callOut, [], cameBack(SAID))
        killedCode = scripted(true, { receipt("invoice 4471") }, codeRunning)
        killed = new EngineWiring(store, killedModel, CodeStepsHeld.of(killedCode))
        store.transactions().executeWithoutResult {
            TicketWorkflow.run(store)
            killed.engine.planStarted(killed.tree.lock(groupId(TicketWorkflow.GROUP), ticketRun()).orElseThrow())
        }
        store.transactions().executeWithoutResult {
            CodeWorkflow.run(store)
            killed.engine.planStarted(killed.tree.lock(groupId(CodeWorkflow.GROUP), codeRun()).orElseThrow())
        }
        callOut.awaitReached()
        until("the killed process's code was running") { killedCode.runs.get() == 1 }

        startedModel = new ScriptedModelCalls().answering(cameBack(FITS))
        startedCode = scripted(true, { receipt("R-2") })
        started = new EngineWiring(store, startedModel, CodeStepsHeld.of(startedCode))
        started.started()
        until("both runs went on") {
            store.count("select count(*) from model_calls where outcome = 'came_back'") == 1 &&
                    triesOf(CodeWorkflow.CHECK).size() == 1
        }
        started.drained()
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

    private List<String> triesOf(String step) {
        store.texts("""
                select try.try_number || ' ' || try.producer || ' ' || try.created_by_kind || ' '
                       || case when try.ended_at is null then 'open'
                               else coalesce(try.lost_reason::text, 'yielded') end
                  from productions try join run_steps held on held.run_step_id = try.run_step_id
                 where held.workflow_step_id = ?::uuid order by try.try_number
                """, step)
    }

    private List<String> callRows() {
        store.texts("""
                select try.try_number || ' ' || call.outcome || ' ' || call.sent_count || ' '
                       || coalesce(call.came_back_count::text, '-') || ' ' || coalesce(call.answer, '-')
                  from model_calls call join productions try on try.production_id = call.production_id
                 order by try.try_number
                """)
    }

    /** Every table the baseline made, read off the catalogue, so one a later stage adds is held too. */
    private String runRows() {
        store.contents()
    }

    def "a process started after one killed settles what the killed one had out, and goes on from there"() {
        when:
        killedAndStartedAgain()

        then: "each try the killed process had out spent, and the next made by the process started after it"
        triesOf(TicketWorkflow.SUMMARISE) == ["1 model system nothing_came_back", "2 model system yielded"]
        triesOf(CodeWorkflow.SEND) == ["1 code system nothing_came_back", "2 code system yielded"]

        and: "what was sent counted for both calls, the killed one's as measured and nothing as come back"
        def measured = killedModel.requests[0].model().unitsOf(killedModel.requests[0].sent().characters())
        callRows() == ["1 nothing_came_back ${measured} - -" as String, "2 came_back 321 12 ${FITS}" as String]

        and: "the killed process's call and code never made again"
        killedModel.requests.size() == 1
        startedModel.requests.size() == 1
        killedCode.runs.get() == 1
        startedCode.runs.get() == 1
    }

    def "what the killed process says when it wakes changes nothing, and is said by its keys alone"() {
        given:
        killedAndStartedAgain()
        def before = runRows()
        def logged = new SnapshottingAppender()
        logged.start()
        LATE_LOGGERS.each { it.addAppender(logged) }

        when: "a killed process drives nothing more, so it wakes stopping"
        killed.closing()
        callOut.release()
        codeRunning.countDown()
        killed.drained()

        then:
        runRows() == before

        and:
        def call = store.texts("""
                select call.model_call_id::text from model_calls call where call.outcome = 'nothing_came_back'""")[0]
        def modelTry = store.texts("""
                select production_id::text from productions where producer = 'model' and try_number = 1""")[0]
        def codeTry = store.texts("""
                select production_id::text from productions where producer = 'code' and try_number = 1""")[0]
        logged.list*.formattedMessage.sort() == [
                "Call ${call} of try ${modelTry} of run ${TicketWorkflow.RUN} had ended already; how it ended now is"
                        + " dropped" as String,
                "Try ${codeTry} of run ${CodeWorkflow.RUN} was ended before its code came back; what it came to is"
                        + " dropped" as String].sort()
        logged.list.every { it.level == Level.WARN && it.throwableProxy == null }

        and: "nothing either came back with said, nor any value it gave"
        logged.list.every { !it.formattedMessage.contains("4471") }
        store.count("select count(*) from model_calls where answer like '%4471%'") == 0
        store.count("select count(*) from production_values where value::text like '%4471%'") == 0

        cleanup:
        LATE_LOGGERS.each { it.detachAppender(logged) }
    }
}
