package org.lilradish.lite.app.run

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.lilradish.lite.app.codestep.CodeRuns
import org.lilradish.lite.app.codestep.CodeSteps
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.domain.inference.ModelCalls
import org.lilradish.lite.domain.model.ModelCatalog
import org.lilradish.lite.testutil.library.DeployedModels
import org.lilradish.lite.testutil.library.LibraryStore
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.support.GenericApplicationContext

/**
 * One process's run engine as a spec wires it by hand over a store: its pools, its engine, what a person's acts go
 * through, and what settles and goes on as the process starts. Two over one store are two processes, one of which a
 * spec may abandon to stand in for one killed. Here beside the engine because only its own package may name it.
 */
final class EngineWiring {

    final RunTree tree

    final RunSnapshots snapshots

    final EngineWrites writes

    final EngineExecutor executor

    final EngineCalls calls

    final RunEngine engine

    final StepActs acts

    final EngineRestart restart

    EngineWiring(LibraryStore store, ModelCalls modelCalls, CodeSteps release,
                 ModelCatalog catalog = DeployedModels.HELD) {
        def session = store.session
        tree = new RunTree(session)
        snapshots = new RunSnapshots(session, release)
        writes = new EngineWrites(session)
        executor = EngineExecutors.of(store.database)
        def ceilings = new CeilingReach(session, catalog, writes)
        calls = new EngineCalls(modelCalls, store.transactions(), tree, writes, ceilings)
        def codes = new EngineCodes(new CodeRuns(release), store.transactions(), tree, snapshots, writes, executor)
        engine = new RunEngine(session, store.transactions(), tree, snapshots, writes, executor, calls, codes, catalog,
                ceilings, new GroupRoles(session))
        acts = new StepActs(session, store.transactions(), new GroupRoles(session), tree, snapshots, writes, engine)
        restart = new EngineRestart(session, store.transactions(), tree, writes, calls, engine, executor)
    }

    /** As the application starting does: what was out settled before it serves, then every run not stopped driven. */
    void started() {
        restart.afterSingletonsInstantiated()
        restart.start()
    }

    /** The application closing, as the context the pools belong to publishes it. */
    void closing() {
        def own = new GenericApplicationContext()
        executor.setApplicationContext(own)
        executor.onApplicationEvent(new ContextClosedEvent(own))
    }

    /**
     * Every task handed to either pool finished, those they handed over in turn too, the pools left running. Both idle
     * between two readings of how many tasks the two were ever handed, the same both times: a task hands over before
     * it finishes, so one that handed across between the readings shows as a count moved.
     */
    void drained() {
        ThreadPoolExecutor code = executor.@codePool.threadPoolExecutor
        ThreadPoolExecutor calls = executor.@pool.threadPoolExecutor
        def deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (true) {
            def handed = code.taskCount + calls.taskCount
            if (idle(code) && idle(calls) && code.taskCount + calls.taskCount == handed) {
                return
            }
            assert System.nanoTime() < deadline: "the engine's pools never went idle"
            Thread.sleep(10)
        }
    }

    /** Both pools kept busy until the gate returned is opened, so whatever is handed over meanwhile queues. */
    CountDownLatch occupied() {
        def gate = new CountDownLatch(1)
        def busy = new CountDownLatch(4)
        2.times {
            executor.execute {
                busy.countDown()
                gate.await(60, TimeUnit.SECONDS)
            }
            executor.executeCode {
                busy.countDown()
                gate.await(60, TimeUnit.SECONDS)
            }
        }
        assert busy.await(10, TimeUnit.SECONDS)
        gate
    }

    private static boolean idle(ThreadPoolExecutor pool) {
        pool.taskCount == pool.completedTaskCount
    }
}
