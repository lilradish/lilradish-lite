package org.lilradish.lite.app.run

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.function.Supplier
import javax.sql.DataSource
import org.lilradish.lite.app.codestep.CodeStepDeployment
import org.lilradish.lite.app.inference.LongestSend
import org.lilradish.lite.testutil.SnapshottingAppender
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.support.GenericApplicationContext
import spock.lang.Specification

/**
 * Where a run goes on after a commit hands it over: off the committing thread, code on threads of its own apart from
 * those a model is called on, and never shut down while a task handed over is still to finish, nor after the store's
 * connections it writes through are gone, nor after the lock that keeps a second process off the store is let go.
 */
class EngineExecutorSpec extends Specification {

    static final Logger ENGINE_LOGGER = LoggerFactory.getLogger(EngineExecutor) as Logger

    static final Logger THREAD_LOGGER = LoggerFactory.getLogger(EngineThread) as Logger

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

    /** What the pools are ordered after by name, standing in for the lock, which says whether it was let go yet. */
    private static DisposableBean lettingGo(List<String> happened) {
        { -> happened << "lock let go" } as DisposableBean
    }

    /** A context holding the pools, the store they take, what they take of code steps, and the one-process lock. */
    private static AnnotationConfigApplicationContext inContext(List<String> happened) {
        def context = new AnnotationConfigApplicationContext()
        context.registerBean("engineExecutor", EngineExecutor, new BeanDefinitionCustomizer[0])
        context.registerBean("store", DataSource, { closingStore(happened) } as Supplier<DataSource>,
                new BeanDefinitionCustomizer[0])
        context.registerBean("codeStepDeployment", CodeStepDeployment,
                { EngineExecutors.A_MINUTE } as Supplier<CodeStepDeployment>, new BeanDefinitionCustomizer[0])
        context.registerBean(OneProcess.BEAN, DisposableBean, { lettingGo(happened) } as Supplier<DisposableBean>,
                new BeanDefinitionCustomizer[0])
        context.refresh()
        context
    }

    /** Only the engine's own threads may call a model, so every thread it runs a task on is one. */
    def "runs a task handed over on an engine thread of its own, named as the engine's, not the one handing it over"() {
        given:
        def executor = EngineExecutors.of(closingStore([]))
        def ran = new CountDownLatch(1)
        Thread ranOn = null

        when:
        executor.execute {
            ranOn = Thread.currentThread()
            ran.countDown()
        }

        then:
        ran.await(10, TimeUnit.SECONDS)
        ranOn instanceof EngineThread
        ranOn.name ==~ /run-engine-\d+/
        !ranOn.daemon

        and:
        ranOn != Thread.currentThread()
        !(Thread.currentThread() instanceof EngineThread)

        cleanup:
        executor.destroy()
    }

    /** Code runs on its own threads, so no model is ever called on a thread code may never give back. */
    def "runs code handed over on a code thread of its own, named as the engine's code threads, and never an engine thread"() {
        given:
        def executor = EngineExecutors.of(closingStore([]))
        def ran = new CountDownLatch(1)
        Thread ranOn = null

        when:
        executor.executeCode {
            ranOn = Thread.currentThread()
            ran.countDown()
        }

        then:
        ran.await(10, TimeUnit.SECONDS)
        ranOn instanceof CodeThread
        ranOn.name ==~ /run-code-\d+/
        !ranOn.daemon

        and:
        !(ranOn instanceof EngineThread)
        ranOn != Thread.currentThread()

        cleanup:
        executor.destroy()
    }

    def "code that never gives back, on every code thread, holds up no task handed over to the engine's threads"() {
        given:
        def executor = EngineExecutors.of(closingStore([]))
        def hung = new CountDownLatch(1)
        def holding = new CountDownLatch(2)
        2.times {
            executor.executeCode {
                holding.countDown()
                hung.await(60, TimeUnit.SECONDS)
            }
        }
        assert holding.await(10, TimeUnit.SECONDS)
        def ran = new CountDownLatch(1)

        when:
        executor.execute { ran.countDown() }

        then:
        ran.await(10, TimeUnit.SECONDS)

        and: "the code still held, and still running"
        hung.count == 1
        executor.@codePool.threadPoolExecutor.activeCount == 2

        cleanup:
        hung.countDown()
        executor.destroy()
    }

    /** The commit that handed a task over has landed; nothing the task does afterwards may be thrown back into it. */
    def "a task that fails is logged, and the next task handed over still runs"() {
        given:
        def executor = EngineExecutors.of(closingStore([]))
        def logged = listening()
        def ran = new CountDownLatch(1)

        when:
        executor.execute { throw new IllegalStateException("Went wrong") }
        executor.execute { ran.countDown() }

        then:
        ran.await(10, TimeUnit.SECONDS)
        executor.destroy()
        logged.list*.formattedMessage == ["A run handed over could not go on by itself"]
        logged.list[0].level == Level.ERROR
        logged.list[0].throwableProxy.message == "Went wrong"

        cleanup:
        executor.destroy()
        ENGINE_LOGGER.detachAppender(logged)
    }

    def "a task handed over once it has shut down is logged and not run, never thrown at whoever handed it over"() {
        given:
        def executor = EngineExecutors.of(closingStore([]))
        def logged = listening()
        def ran = false
        executor.destroy()

        when:
        executor."$handing" { ran = true }

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

    def "shut down, it finishes every task already handed over before it ends"() {
        given:
        def executor = EngineExecutors.of(closingStore([]))
        def started = new CountDownLatch(2)
        def finished = [].asSynchronized()
        ["first", "second"].each { task ->
            executor.execute {
                started.countDown()
                Thread.sleep(200)
                finished << task
            }
        }
        executor.execute { finished << "queued behind them" }
        started.await(10, TimeUnit.SECONDS)

        when:
        executor.destroy()

        then: "both under way and the one still queued, each finished, whichever thread ran it"
        finished.toSorted() == ["first", "queued behind them", "second"]
    }

    def "shut down, it finishes code under way and code queued behind it as well as calls, before it ends"() {
        given:
        def executor = EngineExecutors.of(closingStore([]))
        def started = new CountDownLatch(3)
        def finished = [].asSynchronized()
        2.times { index ->
            executor.executeCode {
                started.countDown()
                Thread.sleep(200)
                finished << "code ${index}".toString()
            }
        }
        executor.executeCode { finished << "code queued" }
        executor.execute {
            started.countDown()
            Thread.sleep(200)
            finished << "call"
        }
        assert started.await(10, TimeUnit.SECONDS)

        when:
        executor.destroy()

        then:
        finished.toSorted() == ["call", "code 0", "code 1", "code queued"]
    }

    /**
     * Registered before the store, it would be destroyed after it were nothing to order the two; what orders
     * them is the store it takes, so a task still draining writes through connections not yet closed.
     */
    def "in a context it is destroyed before the store it takes, a task draining finishing while the store is open"() {
        given:
        def happened = [].asSynchronized()
        def context = inContext(happened)
        def recorded = context.beanFactory.getDependenciesForBean("engineExecutor") as List
        def started = new CountDownLatch(1)
        context.getBean(EngineExecutor).execute {
            started.countDown()
            Thread.sleep(200)
            happened << "task finished"
        }
        started.await(10, TimeUnit.SECONDS)

        when:
        context.close()

        then:
        happened.findAll { it != "lock let go" } == ["task finished", "store closed"]

        and: "which the context held as the one depending on the other"
        recorded.contains("store")
    }

    /** A second process may start the moment the lock goes; what is still running here must be written down first. */
    def "in a context it is destroyed before the one-process lock is let go, code draining finishing while it is held"() {
        given:
        def happened = [].asSynchronized()
        def context = inContext(happened)
        def started = new CountDownLatch(1)
        context.getBean(EngineExecutor).executeCode {
            started.countDown()
            Thread.sleep(200)
            happened << "code finished"
        }
        assert started.await(10, TimeUnit.SECONDS)
        def dependents = context.beanFactory.getDependentBeans(OneProcess.BEAN) as List

        when:
        context.close()

        then:
        happened.findAll { it != "store closed" } == ["code finished", "lock let go"]

        and: "ordered by the lock's name as the context held it before closing, the pools taking nothing of it"
        dependents == ["engineExecutor"]
    }

    /** Published before any lifecycle stops and before the pool drains, so nothing goes out while it drains. */
    def "in a context it says it is stopping once the context closes, and not before"() {
        given:
        def context = inContext([])
        def executor = context.getBean(EngineExecutor)
        def before = executor.stopping()

        when:
        context.close()

        then:
        !before
        executor.stopping()
    }

    def "shut down by hand, it says it is stopping as well"() {
        given:
        def executor = EngineExecutors.of(closingStore([]))
        def before = executor.stopping()

        when:
        executor.destroy()

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

        expect:
        executor.@drain == Duration.ofSeconds(waited)
        EngineExecutor.drain(sends.getBeanProvider(LongestSend), new CodeStepDeployment(Duration.ofSeconds(code))) ==
                Duration.ofSeconds(waited)

        cleanup:
        executor.destroy()

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
        def executor = EngineExecutors.of(closingStore([]))
        def hung = new CountDownLatch(1)
        def holding = new CountDownLatch(1)
        executor.executeCode {
            holding.countDown()
            hung.await(60, TimeUnit.SECONDS)
        }
        assert holding.await(10, TimeUnit.SECONDS)
        executor.@codePool.initiateShutdown()
        executor.@pool.initiateShutdown()

        when:
        def began = System.nanoTime()
        def codeDrained = EngineExecutor.drained(executor.@codePool, began)
        def callsDrained = EngineExecutor.drained(executor.@pool, began)
        def waited = Duration.ofNanos(System.nanoTime() - began)

        then:
        !codeDrained
        callsDrained
        waited < Duration.ofSeconds(1)

        cleanup:
        hung.countDown()
        executor.destroy()
    }

    def "a pool held past a deadline still ahead is waited on until that deadline, and not past it"() {
        given:
        def executor = EngineExecutors.of(closingStore([]))
        def hung = new CountDownLatch(1)
        def holding = new CountDownLatch(1)
        executor.executeCode {
            holding.countDown()
            hung.await(60, TimeUnit.SECONDS)
        }
        assert holding.await(10, TimeUnit.SECONDS)
        executor.@codePool.initiateShutdown()
        def deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300)

        when:
        def drained = EngineExecutor.drained(executor.@codePool, deadline)
        def late = System.nanoTime() - deadline

        then:
        !drained
        late >= 0
        late < TimeUnit.SECONDS.toNanos(1)

        cleanup:
        hung.countDown()
        executor.destroy()
    }

    /** An error is the engine's thread ending; what it says may be what code said, so only its kind is logged. */
    def "a task that ends its thread with an error is logged by its kind alone, and the next task runs on a new thread"() {
        given:
        def executor = EngineExecutors.of(closingStore([]))
        def logged = listening()
        def ended = threadsEnding()
        def ran = new CountDownLatch(1)
        Thread first = null

        when:
        executor.execute {
            first = Thread.currentThread()
            throw new OutOfMemoryError("invoice 4471 was too large")
        }
        def deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while ((first == null || first.alive) && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        executor.execute { ran.countDown() }

        then: "the error ended the thread it ran on, and another is made for what comes next"
        first != null && !first.alive
        ran.await(10, TimeUnit.SECONDS)

        and:
        logged.list*.formattedMessage == ["A run handed over stopped its thread with java.lang.OutOfMemoryError"]
        logged.list[0].level == Level.ERROR
        logged.list[0].throwableProxy == null

        and: "the thread's own end logged by its name and the error's kind, never what the error said"
        ended.list*.formattedMessage == ["Run engine thread ${first.name} ended with java.lang.OutOfMemoryError" as String]
        ended.list[0].level == Level.ERROR
        ended.list[0].throwableProxy == null

        cleanup:
        executor.destroy()
        ENGINE_LOGGER.detachAppender(logged)
        THREAD_LOGGER.detachAppender(ended)
    }

    def "code that ends its thread with an error is logged by its kind alone, and the next code runs on a new code thread"() {
        given:
        def executor = EngineExecutors.of(closingStore([]))
        def codeThreadLogger = LoggerFactory.getLogger(CodeThread) as Logger
        def ended = new SnapshottingAppender()
        ended.start()
        codeThreadLogger.addAppender(ended)
        def ran = new CountDownLatch(1)
        Thread first = null
        Thread next = null

        when:
        executor.executeCode {
            first = Thread.currentThread()
            throw new StackOverflowError("invoice 4471 recursed")
        }
        def deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while ((first == null || first.alive) && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        executor.executeCode {
            next = Thread.currentThread()
            ran.countDown()
        }

        then:
        first != null && !first.alive
        ran.await(10, TimeUnit.SECONDS)
        next instanceof CodeThread

        and:
        ended.list*.formattedMessage == ["Run code thread ${first.name} ended with java.lang.StackOverflowError" as String]
        ended.list[0].throwableProxy == null

        cleanup:
        executor.destroy()
        codeThreadLogger.detachAppender(ended)
    }

    /** Nothing declares one, yet a closure or a sneaky throw can raise it, and it must not end the thread either. */
    def "a task that throws a checked exception is logged as any failure, and ends no thread"() {
        given:
        def executor = EngineExecutors.of(closingStore([]))
        def logged = listening()
        def ended = threadsEnding()
        def ran = new CountDownLatch(1)

        when:
        executor.execute { throw new IOException("Went wrong") }
        executor.execute { ran.countDown() }

        then:
        ran.await(10, TimeUnit.SECONDS)
        executor.destroy()
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
        def executor = EngineExecutors.of(closingStore([]))
        executor.setApplicationContext(own)

        when:
        executor.onApplicationEvent(new ContextClosedEvent(new GenericApplicationContext()))

        then:
        !executor.stopping()

        when:
        executor.onApplicationEvent(new ContextClosedEvent(own))

        then:
        executor.stopping()

        cleanup:
        executor.destroy()
    }

    private static SnapshottingAppender threadsEnding() {
        def logged = new SnapshottingAppender()
        logged.start()
        THREAD_LOGGER.addAppender(logged)
        logged
    }
}
