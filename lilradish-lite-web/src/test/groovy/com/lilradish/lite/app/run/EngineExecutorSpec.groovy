package com.lilradish.lite.app.run

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.lilradish.lite.app.codestep.CodeStepDeployment
import com.lilradish.lite.app.inference.LongestSend
import com.lilradish.lite.domain.identity.GroupId
import com.lilradish.lite.domain.identity.UserId
import com.lilradish.lite.domain.inference.ModelCallPurpose
import com.lilradish.lite.domain.registry.EntryId
import com.lilradish.lite.domain.run.ProductionId
import com.lilradish.lite.domain.run.RunId
import com.lilradish.lite.domain.run.RunStepSendAttemptId
import com.lilradish.lite.domain.run.WorkflowStepId
import com.lilradish.lite.testutil.SnapshottingAppender
import com.lilradish.lite.testutil.run.EngineCodeTasks
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.support.GenericApplicationContext
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

/**
 * Where a run goes on after a commit hands it over: off the committing thread, code on threads of its own apart from
 * those a model is called on, and never shut down while a task handed over is still to finish, nor after the store's
 * connections it writes through are gone, nor after the lock that keeps a second process off the store is let go.
 */
class EngineExecutorSpec extends Specification {

    static final Logger ENGINE_LOGGER = LoggerFactory.getLogger(EngineExecutor) as Logger

    static final Logger THREAD_LOGGER = LoggerFactory.getLogger(EngineThread) as Logger

    private final List<EngineExecutor> executors = []

    def cleanup() {
        executors.each { executor ->
            def pools = [executor.@codePool, executor.@pool].collect { it.@threadPoolExecutor }.findAll { it != null }
            pools*.shutdownNow()
            pools.each { pool ->
                try {
                    pool.awaitTermination(500, TimeUnit.MILLISECONDS)
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    private EngineExecutor executorFor(DataSource store) {
        def executor = EngineExecutors.of(store)
        executors << executor
        executor
    }

    private static void destroyed(EngineExecutor executor) {
        completed { executor.destroy() }
    }

    private static <T> T completed(Closure<T> action) {
        def task = new FutureTask<T>(action as Callable<T>)
        def worker = Thread.ofPlatform().daemon(true).start(task)
        try {
            task.get(1, TimeUnit.SECONDS)
        } finally {
            worker.interrupt()
            try {
                worker.join(500)
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private static <T> T drainedWhileHeld(EngineExecutor executor, CountDownLatch release, Closure<T> close) {
        def closing = new FutureTask<T>(close as Callable<T>)
        def worker = Thread.ofPlatform().daemon(true).start(closing)
        try {
            new PollingConditions(timeout: 5).eventually {
                assert executor.@codePool.threadPoolExecutor.isShutdown()
                assert executor.@pool.threadPoolExecutor.isShutdown()
                assert worker.state == Thread.State.TIMED_WAITING
            }
            assert !closing.done
            release.countDown()
            closing.get(5, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            worker.interrupt()
            worker.join(5000)
            assert !worker.alive
        }
    }

    private static SnapshottingAppender listening() {
        def logged = new SnapshottingAppender()
        logged.start()
        ENGINE_LOGGER.addAppender(logged)
        logged
    }

    /** A store whose connections are never asked for, which says whether it was destroyed yet. */
    private static DataSource closingStore(List<String> happened) {
        Proxy.newProxyInstance(EngineExecutorSpec.classLoader, [DataSource, DisposableBean] as Class[]) {
            proxy, method, arguments ->
                if (method.name == "destroy") {
                    happened << "store closed"
                }
                null
        } as DataSource
    }

    /** Only the engine's own threads may call a model, so every thread it runs a task on is one. */
    def "runs a task handed over on an engine thread of its own, named as the engine's, not the one handing it over"() {
        given:
        def executor = executorFor(closingStore([]))
        def ran = new CountDownLatch(1)
        Thread ranOn = null

        when:
        executor.execute {
            ranOn = Thread.currentThread()
            ran.countDown()
        }

        then:
        ran.await(1, TimeUnit.SECONDS)
        ranOn instanceof EngineThread
        ranOn.name ==~ /run-engine-\d+/
        !ranOn.daemon

        and:
        ranOn != Thread.currentThread()
        !(Thread.currentThread() instanceof EngineThread)
    }

    /** Code runs on its own threads, so no model is ever called on a thread code may never give back. */
    def "runs code handed over on a code thread of its own, named as the engine's code threads, and never an engine thread"() {
        given:
        def executor = executorFor(closingStore([]))
        def ran = new CountDownLatch(1)
        Thread ranOn = null

        when:
        EngineCodeTasks.run(executor) {
            ranOn = Thread.currentThread()
            ran.countDown()
        }

        then:
        ran.await(1, TimeUnit.SECONDS)
        ranOn instanceof CodeThread
        ranOn.name ==~ /run-code-\d+/
        !ranOn.daemon

        and:
        !(ranOn instanceof EngineThread)
        ranOn != Thread.currentThread()
    }

    def "code that never gives back, on every code thread, holds up no task handed over to the engine's threads"() {
        given:
        def executor = executorFor(closingStore([]))
        def hung = new CountDownLatch(1)
        def holding = new CountDownLatch(2)
        def exited = [new CountDownLatch(1), new CountDownLatch(1)]
        2.times { index ->
            EngineCodeTasks.run(executor) {
                try {
                    holding.countDown()
                    hung.await(60, TimeUnit.SECONDS)
                } finally {
                    exited[index].countDown()
                }
            }
        }
        assert holding.await(1, TimeUnit.SECONDS)
        def ran = new CountDownLatch(1)

        when:
        executor.execute { ran.countDown() }

        then:
        ran.await(1, TimeUnit.SECONDS)

        and: "the code still held, and still running"
        hung.count == 1
        exited*.count == [1L, 1L]

        cleanup:
        hung.countDown()
        exited.each { it.await(1, TimeUnit.SECONDS) }
    }

    /** The commit that handed a task over has landed; nothing the task does afterwards may be thrown back into it. */
    def "a task that fails is logged, and the next task handed over still runs"() {
        given:
        def executor = executorFor(closingStore([]))
        def logged = listening()
        def ran = new CountDownLatch(1)

        when:
        executor.execute { throw new IllegalStateException("Went wrong") }
        executor.execute { ran.countDown() }

        then:
        ran.await(1, TimeUnit.SECONDS)
        destroyed(executor)
        logged.list*.formattedMessage == ["A run handed over could not go on by itself"]
        logged.list[0].level == Level.ERROR
        logged.list[0].throwableProxy.message == "Went wrong"

        cleanup:
        ENGINE_LOGGER.detachAppender(logged)
    }

    def "a task handed over once it has shut down is logged and not run, never thrown at whoever handed it over"() {
        given:
        def executor = executorFor(closingStore([]))
        def logged = listening()
        def ran = false
        destroyed(executor)

        when:
        Runnable task = { ran = true }
        if (handing == "executeCode") EngineCodeTasks.run(executor, task)
        else executor."$handing"(task)

        then:
        noExceptionThrown()
        !ran
        logged.list*.formattedMessage == ["A run was handed over once runs had stopped going on by themselves"]
        logged.list[0].level == Level.WARN

        cleanup:
        ENGINE_LOGGER.detachAppender(logged)

        where:
        handing << ["execute", "executeCode"]
    }

    private static PendingSend press(ProductionId production, RunStepSendAttemptId attempt, String presser) {
        def group = new GroupId(UUID.fromString("00000008-0000-4000-8000-000000000001"))
        def root = new RunId(UUID.fromString("00000008-0000-4000-8000-000000000002"))
        def step = new WorkflowStepId(UUID.fromString("00000008-0000-4000-8000-000000000003"))
        new PendingSend(group, root, root, step, production, ModelCallPurpose.PRODUCE,
                new UserId(presser), attempt, null)
    }

    private static Object intentFor(String kind) {
        def group = new GroupId(UUID.randomUUID())
        def root = new RunId(UUID.randomUUID())
        def step = new WorkflowStepId(UUID.randomUUID())
        switch (kind) {
            case "entry": return new EntryId(UUID.randomUUID())
            case "send": return new PendingSend(group, root, root, step, new ProductionId(UUID.randomUUID()),
                    ModelCallPurpose.PRODUCE, null, null, null)
            case "press": return press(new ProductionId(UUID.randomUUID()), new RunStepSendAttemptId(UUID.randomUUID()),
                    "asker")
            case "code": return new PendingCode(group, root, root, step, 1, null)
            default: throw new IllegalArgumentException(kind)
        }
    }

    def "an intent refused after shutdown leaves no queued ticket and never runs its task"() {
        given:
        def executor = executorFor(closingStore([]))
        destroyed(executor)
        def logged = listening()
        def ran = false
        Runnable task = { ran = true }

        when:
        executor."$handing"(intentFor(kind), task)

        then:
        noExceptionThrown()
        !ran
        executor.@queued.isEmpty()
        executor.@pool.threadPoolExecutor.queue.isEmpty()
        executor.@codePool.threadPoolExecutor.queue.isEmpty()
        logged.list*.formattedMessage == ["A run was handed over once runs had stopped going on by themselves"]
        logged.list*.level == [Level.WARN]

        cleanup:
        ENGINE_LOGGER.detachAppender(logged)

        where:
        handing       | kind
        "execute"     | "entry"
        "execute"     | "send"
        "execute"     | "press"
        "executeCode" | "code"
    }

    def "null task or intent is refused before a task runs or a ticket remains"() {
        given:
        def executor = executorFor(closingStore([]))
        def ran = false
        Runnable task = { ran = true }

        when:
        if (missing == "intent") {
            switch (kind) {
                case "entry": executor.execute((EntryId) null, task); break
                case "send": executor.execute((PendingSend) null, task); break
                case "code": executor.executeCode((PendingCode) null, task); break
            }
        } else if (kind == "plain") {
            if (handing == "executeCode") EngineCodeTasks.run(executor, (Runnable) null)
            else executor."$handing"((Runnable) null)
        } else {
            executor."$handing"(intentFor(kind), (Runnable) null)
        }

        then:
        def refused = thrown(NullPointerException)
        refused.message == message
        !ran
        executor.@queued.isEmpty()
        executor.@pool.threadPoolExecutor.queue.isEmpty()
        executor.@codePool.threadPoolExecutor.queue.isEmpty()

        where:
        handing       | kind    | missing  || message
        "execute"     | "entry" | "intent" || "EngineExecutor entry must not be null"
        "execute"     | "send"  | "intent" || "EngineExecutor pending send must not be null"
        "executeCode" | "code"  | "intent" || "EngineExecutor pending code must not be null"
        "execute"     | "plain" | "task"   || "EngineExecutor task must not be null"
        "executeCode" | "plain" | "task"   || "EngineExecutor task must not be null"
        "execute"     | "entry" | "task"   || "EngineExecutor task must not be null"
        "execute"     | "send"  | "task"   || "EngineExecutor task must not be null"
        "execute"     | "press" | "task"   || "EngineExecutor task must not be null"
        "executeCode" | "code"  | "task"   || "EngineExecutor task must not be null"
    }

    def "a null store is refused with its explicit construction guard"() {
        given:
        def sends = new StaticListableBeanFactory()
        EngineExecutor made = null

        when:
        made = new EngineExecutor(null, sends.getBeanProvider(LongestSend), EngineExecutors.A_MINUTE)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "EngineExecutor store must not be null"

        cleanup:
        if (made != null) {
            made.@codePool.threadPoolExecutor.shutdownNow()
            made.@pool.threadPoolExecutor.shutdownNow()
        }
    }

    def "interrupting a drain returns false and preserves the signal while its task stays held"() {
        given:
        def executor = executorFor(closingStore([]))
        def release = new CountDownLatch(1)
        def started = new CountDownLatch(1)
        EngineCodeTasks.run(executor) { started.countDown(); release.await() }
        assert started.await(5, TimeUnit.SECONDS)
        executor.@codePool.initiateShutdown()
        def deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        def outcome = new FutureTask<Map>({
            def drained = EngineExecutor.drained(executor.@codePool, deadline)
            [drained: drained, interrupted: Thread.currentThread().isInterrupted()]
        } as Callable<Map>)
        def worker = Thread.ofPlatform().daemon(true).start(outcome)
        new PollingConditions(timeout: 5).eventually {
            assert worker.state == Thread.State.TIMED_WAITING
        }

        when:
        worker.interrupt()
        def answered = outcome.get(5, TimeUnit.SECONDS)
        worker.join(5000)

        then:
        !worker.alive
        !answered.drained
        answered.interrupted
        release.count == 1
        !executor.@codePool.threadPoolExecutor.isTerminated()

        cleanup:
        release.countDown()
        worker?.interrupt()
        worker?.join(5000)
        assert worker == null || !worker.alive
    }

    def "interrupted shutdown warns once when code or calls have not finished and keeps the signal"() {
        given:
        def executor = executorFor(closingStore([]))
        def release = new CountDownLatch(1)
        def started = new CountDownLatch((code ? 1 : 0) + (call ? 1 : 0))
        if (code) EngineCodeTasks.run(executor) { started.countDown(); release.await() }
        if (call) executor.execute { started.countDown(); release.await() }
        assert started.await(5, TimeUnit.SECONDS)
        def logged = listening()
        def closing = new FutureTask<Boolean>({
            executor.destroy()
            Thread.currentThread().isInterrupted()
        } as Callable<Boolean>)
        def worker = Thread.ofPlatform().daemon(true).start(closing)
        new PollingConditions(timeout: 5).eventually {
            assert executor.@codePool.threadPoolExecutor.isShutdown()
            assert executor.@pool.threadPoolExecutor.isShutdown()
            assert worker.state == Thread.State.TIMED_WAITING
        }

        when:
        worker.interrupt()
        def interrupted = closing.get(5, TimeUnit.SECONDS)
        worker.join(5000)

        then:
        !worker.alive
        interrupted
        release.count == 1
        logged.list*.level == [Level.WARN]
        logged.list*.formattedMessage == [
            "Runs under way were not all written down within ${executor.@drain} of stopping; what is left is settled when this system starts again" as String,
        ]
        if (code) assert !executor.@codePool.threadPoolExecutor.isTerminated()
        if (call) assert !executor.@pool.threadPoolExecutor.isTerminated()

        cleanup:
        release.countDown()
        worker?.interrupt()
        worker?.join(5000)
        assert worker == null || !worker.alive
        ENGINE_LOGGER.detachAppender(logged)

        where:
        code  | call
        true  | false
        false | true
        true  | true
    }

    def "shutdown gives both held pools one real configured deadline and warns once when it expires"() {
        given:
        def sends = new StaticListableBeanFactory()
        def executor = new EngineExecutor(closingStore([]), sends.getBeanProvider(LongestSend),
                new CodeStepDeployment(Duration.ofNanos(1)))
        executors << executor
        def release = new CountDownLatch(1)
        def started = new CountDownLatch(2)
        EngineCodeTasks.run(executor) { started.countDown(); release.await() }
        executor.execute { started.countDown(); release.await() }
        assert started.await(5, TimeUnit.SECONDS)
        def logged = listening()
        def closing = new FutureTask<Boolean>({ executor.destroy(); Thread.currentThread().isInterrupted() } as Callable<Boolean>)
        def began = System.nanoTime()
        def worker = Thread.ofPlatform().daemon(true).start(closing)
        new PollingConditions(timeout: 5).eventually {
            assert executor.@codePool.threadPoolExecutor.isShutdown()
            assert executor.@pool.threadPoolExecutor.isShutdown()
            assert worker.state == Thread.State.TIMED_WAITING
        }

        when:
        def interrupted = closing.get(35, TimeUnit.SECONDS)
        def waited = Duration.ofNanos(System.nanoTime() - began)
        worker.join(5000)

        then:
        !worker.alive
        !interrupted
        executor.@drain == Duration.ofSeconds(30).plusNanos(1)
        waited >= executor.@drain
        waited < executor.@drain.plusSeconds(5)
        release.count == 1
        !executor.@codePool.threadPoolExecutor.isTerminated()
        !executor.@pool.threadPoolExecutor.isTerminated()
        logged.list*.level == [Level.WARN]
        logged.list*.formattedMessage == [
            "Runs under way were not all written down within ${executor.@drain} of stopping; what is left is settled when this system starts again" as String,
        ]

        cleanup:
        release.countDown()
        worker?.interrupt()
        worker?.join(5000)
        assert worker == null || !worker.alive
        ENGINE_LOGGER.detachAppender(logged)
    }

    def "a queued press is submitted once for its first presser despite repeated presses by another"() {
        given:
        def executor = executorFor(closingStore([]))
        def gate = new CountDownLatch(1)
        def started = new CountDownLatch(2)
        2.times { executor.execute { started.countDown(); gate.await(10, TimeUnit.SECONDS) } }
        assert started.await(1, TimeUnit.SECONDS)
        def production = new ProductionId(UUID.randomUUID())
        def attempt = new RunStepSendAttemptId(UUID.randomUUID())
        def ran = [].asSynchronized()

        when:
        executor.execute(press(production, attempt, "first")) { ran << "first" }
        32.times { executor.execute(press(production, attempt, "later")) { ran << "later" } }

        then:
        executor.@pool.threadPoolExecutor.queue.size() == 1
        ran == []

        when:
        gate.countDown()
        destroyed(executor)

        then:
        ran == ["first"]
        executor.@queued.isEmpty()

        cleanup:
        gate.countDown()
    }

    def "different queued press targets each run even while the model threads are occupied"() {
        given:
        def executor = executorFor(closingStore([]))
        def gate = new CountDownLatch(1)
        def started = new CountDownLatch(2)
        2.times { executor.execute { started.countDown(); gate.await(10, TimeUnit.SECONDS) } }
        assert started.await(1, TimeUnit.SECONDS)
        def production = new ProductionId(UUID.randomUUID())
        def first = press(production, new RunStepSendAttemptId(UUID.randomUUID()), "first")
        def second = press(production, new RunStepSendAttemptId(UUID.randomUUID()), "second")
        def ran = [].asSynchronized()

        when:
        executor.execute(first) { ran << "first" }
        executor.execute(second) { ran << "second" }

        then:
        executor.@pool.threadPoolExecutor.queue.size() == 2

        when:
        gate.countDown()
        destroyed(executor)

        then:
        ran.toSorted() == ["first", "second"]
        executor.@queued.isEmpty()

        cleanup:
        gate.countDown()
    }

    def "a press may queue again after its task starts, including after an earlier task fails"() {
        given:
        def executor = executorFor(closingStore([]))
        def blocker = new CountDownLatch(1)
        def blocked = new CountDownLatch(1)
        executor.execute { blocked.countDown(); blocker.await(60, TimeUnit.SECONDS) }
        assert blocked.await(1, TimeUnit.SECONDS)
        def began = new CountDownLatch(1)
        def fail = new CountDownLatch(1)
        def pending = press(new ProductionId(UUID.randomUUID()), new RunStepSendAttemptId(UUID.randomUUID()), "first")
        def ran = [].asSynchronized()
        def secondStarted = new CountDownLatch(1)
        def secondGate = new CountDownLatch(1)
        def thirdRan = new CountDownLatch(1)
        executor.execute(pending) {
            began.countDown()
            fail.await(60, TimeUnit.SECONDS)
            throw new IllegalStateException("Planned failure")
        }
        assert began.await(1, TimeUnit.SECONDS)

        when:
        executor.execute(pending) {
            secondStarted.countDown()
            secondGate.await(60, TimeUnit.SECONDS)
            ran << "second"
        }

        then:
        executor.@pool.threadPoolExecutor.queue.size() == 1

        when:
        fail.countDown()

        then:
        secondStarted.await(2, TimeUnit.SECONDS)

        when:
        executor.execute(pending) {
            ran << "third"
            thirdRan.countDown()
        }

        then:
        executor.@pool.threadPoolExecutor.queue.size() == 1

        when:
        secondGate.countDown()

        then:
        // One thread stays held, so the third runs only on the thread the second frees, after it.
        thirdRan.await(2, TimeUnit.SECONDS)
        ran == ["second", "third"]

        when:
        blocker.countDown()
        destroyed(executor)

        then:
        ran == ["second", "third"]
        executor.@queued.isEmpty()

        cleanup:
        fail.countDown()
        secondGate.countDown()
        blocker.countDown()
    }

    def "automatic sends queued for one root merge while another root still advances"() {
        given:
        def executor = executorFor(closingStore([]))
        def gate = new CountDownLatch(1)
        def started = new CountDownLatch(2)
        2.times { executor.execute { started.countDown(); gate.await(10, TimeUnit.SECONDS) } }
        assert started.await(1, TimeUnit.SECONDS)
        def group = new GroupId(UUID.randomUUID())
        def root = new RunId(UUID.randomUUID())
        def step = new WorkflowStepId(UUID.randomUUID())
        def first = new PendingSend(group, root, root, step, new ProductionId(UUID.randomUUID()),
                ModelCallPurpose.PRODUCE, null, null, null)
        def sameRoot = new PendingSend(group, root, root, step, new ProductionId(UUID.randomUUID()),
                ModelCallPurpose.REVIEW, null, null, null)
        def otherRoot = new RunId(UUID.randomUUID())
        def different = new PendingSend(group, otherRoot, otherRoot, step, new ProductionId(UUID.randomUUID()),
                ModelCallPurpose.PRODUCE, null, null, null)
        def ran = [].asSynchronized()

        when:
        executor.execute(first) { ran << "first" }
        executor.execute(sameRoot) { ran << "same root" }
        executor.execute(different) { ran << "different root" }

        then:
        executor.@pool.threadPoolExecutor.queue.size() == 2

        when:
        gate.countDown()
        destroyed(executor)

        then:
        ran.toSorted() == ["different root", "first"]
        executor.@queued.isEmpty()

        cleanup:
        gate.countDown()
    }

    def "repeated notifications for an entry merge while a different entry still goes on"() {
        given:
        def executor = executorFor(closingStore([]))
        def gate = new CountDownLatch(1)
        def started = new CountDownLatch(2)
        2.times { executor.execute { started.countDown(); gate.await(10, TimeUnit.SECONDS) } }
        assert started.await(1, TimeUnit.SECONDS)
        def entry = new EntryId(UUID.randomUUID())
        def different = new EntryId(UUID.randomUUID())
        def ran = [].asSynchronized()

        when:
        executor.execute(entry) { ran << "first" }
        32.times { executor.execute(entry) { ran << "later" } }
        executor.execute(different) { ran << "different" }

        then:
        executor.@pool.threadPoolExecutor.queue.size() == 2
        ran == []

        when:
        gate.countDown()
        destroyed(executor)

        then:
        ran.toSorted() == ["different", "first"]
        executor.@queued.isEmpty()

        cleanup:
        gate.countDown()
    }

    def "an entry may queue again after its earlier notification starts"() {
        given:
        def executor = executorFor(closingStore([]))
        def blocker = new CountDownLatch(1)
        def blocked = new CountDownLatch(1)
        executor.execute { blocked.countDown(); blocker.await(10, TimeUnit.SECONDS) }
        assert blocked.await(1, TimeUnit.SECONDS)
        def entry = new EntryId(UUID.randomUUID())
        def began = new CountDownLatch(1)
        def firstGate = new CountDownLatch(1)
        def secondRan = new CountDownLatch(1)
        def ran = [].asSynchronized()
        executor.execute(entry) {
            began.countDown()
            firstGate.await(10, TimeUnit.SECONDS)
            ran << "first"
        }
        assert began.await(1, TimeUnit.SECONDS)

        when:
        executor.execute(entry) {
            ran << "second"
            secondRan.countDown()
        }

        then:
        executor.@pool.threadPoolExecutor.queue.size() == 1

        when:
        firstGate.countDown()

        then:
        // One thread stays held, so the second runs only on the thread the first frees, after it.
        secondRan.await(2, TimeUnit.SECONDS)
        ran == ["first", "second"]

        when:
        blocker.countDown()
        destroyed(executor)

        then:
        ran == ["first", "second"]
        executor.@queued.isEmpty()

        cleanup:
        firstGate.countDown()
        blocker.countDown()
    }

    def "finishing an earlier entry notification preserves its later pending ticket"() {
        given:
        def executor = executorFor(closingStore([]))
        def firstBlocker = new CountDownLatch(1)
        def firstBlocked = new CountDownLatch(1)
        def oldGate = new CountDownLatch(1)
        def oldStarted = new CountDownLatch(1)
        def secondBlocker = new CountDownLatch(1)
        def secondBlocked = new CountDownLatch(1)
        def blockersFinished = new CountDownLatch(2)
        def entry = new EntryId(UUID.randomUUID())
        def ran = [].asSynchronized()
        boolean refuses = fails
        executor.execute {
            firstBlocked.countDown()
            try { assert firstBlocker.await(15, TimeUnit.SECONDS) }
            finally { blockersFinished.countDown() }
        }
        assert firstBlocked.await(5, TimeUnit.SECONDS)
        executor.execute(entry) {
            oldStarted.countDown()
            assert oldGate.await(15, TimeUnit.SECONDS)
            ran << "old"
            if (refuses) throw new IllegalStateException("The earlier task failed.")
        }
        assert oldStarted.await(5, TimeUnit.SECONDS)
        executor.execute {
            secondBlocked.countDown()
            try { assert secondBlocker.await(15, TimeUnit.SECONDS) }
            finally { blockersFinished.countDown() }
        }
        executor.execute(entry) { ran << "new" }
        def ticket = executor.@queued.values().first()

        when:
        oldGate.countDown()
        assert secondBlocked.await(5, TimeUnit.SECONDS)
        32.times { executor.execute(entry) { ran << "duplicate" } }

        then:
        executor.@queued.size() == 1
        executor.@queued.values().first().is(ticket)
        executor.@pool.threadPoolExecutor.queue.size() == 1
        ran == ["old"]

        when:
        firstBlocker.countDown()
        secondBlocker.countDown()
        destroyed(executor)

        then:
        blockersFinished.count == 0
        ran == ["old", "new"]
        executor.@queued.isEmpty()
        executor.@pool.threadPoolExecutor.queue.isEmpty()
        executor.@codePool.threadPoolExecutor.queue.isEmpty()

        cleanup:
        oldGate.countDown()
        firstBlocker.countDown()
        secondBlocker.countDown()
        executor.@pool.threadPoolExecutor.shutdownNow()
        assert executor.@pool.threadPoolExecutor.awaitTermination(5, TimeUnit.SECONDS)
        assert blockersFinished.count == 0

        where:
        fails << [false, true]
    }

    def "queued code for one root and step keeps its first asker while another step still runs"() {
        given:
        def executor = executorFor(closingStore([]))
        def gate = new CountDownLatch(1)
        def started = new CountDownLatch(2)
        2.times { EngineCodeTasks.run(executor) { started.countDown(); gate.await(10, TimeUnit.SECONDS) } }
        assert started.await(1, TimeUnit.SECONDS)
        def group = new GroupId(UUID.randomUUID())
        def root = new RunId(UUID.randomUUID())
        def step = new WorkflowStepId(UUID.randomUUID())
        def first = new PendingCode(group, root, root, step, 1, new UserId("first"))
        def later = new PendingCode(group, root, root, step, 1, new UserId("later"))
        def different = new PendingCode(group, root, root, new WorkflowStepId(UUID.randomUUID()), 1, null)
        def ran = [].asSynchronized()

        when:
        executor.executeCode(first) { ran << "first" }
        executor.executeCode(later) { ran << "later" }
        executor.executeCode(different) { ran << "different" }

        then:
        executor.@codePool.threadPoolExecutor.queue.size() == 2

        when:
        gate.countDown()
        destroyed(executor)

        then:
        ran.toSorted() == ["different", "first"]
        executor.@queued.isEmpty()

        cleanup:
        gate.countDown()
    }

    def "shut down, it finishes every task already handed over before it ends"() {
        given:
        def executor = executorFor(closingStore([]))
        def started = new CountDownLatch(2)
        def release = new CountDownLatch(1)
        def finished = [].asSynchronized()
        ["first", "second"].each { task ->
            executor.execute {
                started.countDown()
                release.await()
                finished << task
            }
        }
        executor.execute { finished << "queued behind them" }
        assert started.await(5, TimeUnit.SECONDS)

        when:
        drainedWhileHeld(executor, release) { executor.destroy() }

        then: "both under way and the one still queued, each finished, whichever thread ran it"
        finished.toSorted() == ["first", "queued behind them", "second"]

        cleanup:
        release.countDown()
    }

    def "shut down, it finishes code under way and code queued behind it as well as calls, before it ends"() {
        given:
        def executor = executorFor(closingStore([]))
        def started = new CountDownLatch(3)
        def release = new CountDownLatch(1)
        def finished = [].asSynchronized()
        2.times { index ->
            EngineCodeTasks.run(executor) {
                started.countDown()
                release.await()
                finished << "code ${index}".toString()
            }
        }
        EngineCodeTasks.run(executor) { finished << "code queued" }
        executor.execute {
            started.countDown()
            release.await()
            finished << "call"
        }
        assert started.await(5, TimeUnit.SECONDS)

        when:
        drainedWhileHeld(executor, release) { executor.destroy() }

        then:
        finished.toSorted() == ["call", "code 0", "code 1", "code queued"]

        cleanup:
        release.countDown()
    }

    def "shut down by hand, it says it is stopping as well"() {
        given:
        def executor = executorFor(closingStore([]))
        def before = executor.stopping()

        when:
        destroyed(executor)

        then:
        !before
        executor.stopping()
    }

    /** What it waits on is one send or one code run under way, whichever may run longer, then writing where it ended. */
    def "shut down, it waits for the longest a send or a code run may take, and then as long as an end takes alone"() {
        given:
        def sends = new StaticListableBeanFactory()
        if (send != null) {
            sends.addBean("longestSend", new LongestSend(Duration.ofSeconds(send)))
        }
        def executor = new EngineExecutor(closingStore([]), sends.getBeanProvider(LongestSend),
                new CodeStepDeployment(Duration.ofSeconds(code)))
        executors << executor

        expect:
        executor.@drain == Duration.ofSeconds(waited)
        EngineExecutor.drain(sends.getBeanProvider(LongestSend), new CodeStepDeployment(Duration.ofSeconds(code))) ==
                Duration.ofSeconds(waited)

        where:
        send | code || waited
        null | 60   || 90
        120  | 60   || 150
        30   | 60   || 90
        60   | 60   || 90
    }

    /**
     * Each pool is waited on only up to the one deadline both share, so a pool held past it adds nothing to the wait
     * on the other: one held gives up at once once the deadline has passed, and one done answers so at once.
     */
    def "a pool is waited on only up to the deadline both pools share, whether it has drained or not"() {
        given:
        def executor = executorFor(closingStore([]))
        def hung = new CountDownLatch(1)
        def holding = new CountDownLatch(1)
        EngineCodeTasks.run(executor) {
            holding.countDown()
            hung.await(60, TimeUnit.SECONDS)
        }
        assert holding.await(1, TimeUnit.SECONDS)
        executor.@codePool.initiateShutdown()
        executor.@pool.initiateShutdown()

        when:
        def began = System.nanoTime()
        def codeDrained = completed { EngineExecutor.drained(executor.@codePool, began) }
        def callsDrained = completed { EngineExecutor.drained(executor.@pool, began) }
        def waited = Duration.ofNanos(System.nanoTime() - began)

        then:
        !codeDrained
        callsDrained
        waited < Duration.ofSeconds(1)

        cleanup:
        hung.countDown()
    }

    def "a pool held past a deadline still ahead is waited on until that deadline, and not past it"() {
        given:
        def executor = executorFor(closingStore([]))
        def hung = new CountDownLatch(1)
        def holding = new CountDownLatch(1)
        EngineCodeTasks.run(executor) {
            holding.countDown()
            hung.await(60, TimeUnit.SECONDS)
        }
        assert holding.await(1, TimeUnit.SECONDS)
        executor.@codePool.initiateShutdown()
        def deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300)

        when:
        def drained = completed { EngineExecutor.drained(executor.@codePool, deadline) }
        def late = System.nanoTime() - deadline

        then:
        !drained
        late >= 0
        late < TimeUnit.SECONDS.toNanos(1)

        cleanup:
        hung.countDown()
    }

    /** An error is the engine's thread ending; what it says may be what code said, so only its kind is logged. */
    def "a task that ends its thread with an error is logged by its kind alone, and the next task runs on a new thread"() {
        given:
        def executor = executorFor(closingStore([]))
        def logged = listening()
        def ended = threadsEnding()
        def ran = new CountDownLatch(1)
        Thread first = null

        when:
        executor.execute {
            first = Thread.currentThread()
            throw new OutOfMemoryError("invoice 4471 was too large")
        }
        def deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while ((first == null || first.alive) && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        executor.execute { ran.countDown() }

        then: "the error ended the thread it ran on, and another is made for what comes next"
        first != null && !first.alive
        ran.await(1, TimeUnit.SECONDS)

        and:
        logged.list*.formattedMessage == ["A run handed over stopped its thread with java.lang.OutOfMemoryError"]
        logged.list[0].level == Level.ERROR
        logged.list[0].throwableProxy == null

        and: "the thread's own end logged by its name and the error's kind, never what the error said"
        ended.list*.formattedMessage == ["Run engine thread ${first.name} ended with java.lang.OutOfMemoryError" as String]
        ended.list[0].level == Level.ERROR
        ended.list[0].throwableProxy == null

        cleanup:
        ENGINE_LOGGER.detachAppender(logged)
        THREAD_LOGGER.detachAppender(ended)
    }

    def "code that ends its thread with an error is logged by its kind alone, and the next code runs on a new code thread"() {
        given:
        def executor = executorFor(closingStore([]))
        def codeThreadLogger = LoggerFactory.getLogger(CodeThread) as Logger
        def ended = new SnapshottingAppender()
        ended.start()
        codeThreadLogger.addAppender(ended)
        def ran = new CountDownLatch(1)
        Thread first = null
        Thread next = null

        when:
        EngineCodeTasks.run(executor) {
            first = Thread.currentThread()
            throw new StackOverflowError("invoice 4471 recursed")
        }
        def deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while ((first == null || first.alive) && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        EngineCodeTasks.run(executor) {
            next = Thread.currentThread()
            ran.countDown()
        }

        then:
        first != null && !first.alive
        ran.await(1, TimeUnit.SECONDS)
        next instanceof CodeThread

        and:
        ended.list*.formattedMessage == ["Run code thread ${first.name} ended with java.lang.StackOverflowError" as String]
        ended.list[0].throwableProxy == null

        cleanup:
        codeThreadLogger.detachAppender(ended)
    }

    /** Nothing declares one, yet a closure or a sneaky throw can raise it, and it must not end the thread either. */
    def "a task that throws a checked exception is logged as any failure, and ends no thread"() {
        given:
        def executor = executorFor(closingStore([]))
        def logged = listening()
        def ended = threadsEnding()
        def ran = new CountDownLatch(1)

        when:
        executor.execute { throw new IOException("Went wrong") }
        executor.execute { ran.countDown() }

        then:
        ran.await(1, TimeUnit.SECONDS)
        destroyed(executor)
        logged.list*.formattedMessage == ["A run handed over could not go on by itself"]
        logged.list[0].throwableProxy.className == IOException.name
        ended.list == []

        cleanup:
        ENGINE_LOGGER.detachAppender(logged)
        THREAD_LOGGER.detachAppender(ended)
    }

    /** A context beneath the application publishes its own close to the application's listeners too. */
    def "a context closing that is not its own leaves it not stopping"() {
        given:
        def own = new GenericApplicationContext()
        def executor = executorFor(closingStore([]))
        executor.setApplicationContext(own)

        when:
        executor.onApplicationEvent(new ContextClosedEvent(new GenericApplicationContext()))

        then:
        !executor.stopping()

        when:
        executor.onApplicationEvent(new ContextClosedEvent(own))

        then:
        executor.stopping()
    }

    private static SnapshottingAppender threadsEnding() {
        def logged = new SnapshottingAppender()
        logged.start()
        THREAD_LOGGER.addAppender(logged)
        logged
    }
}
