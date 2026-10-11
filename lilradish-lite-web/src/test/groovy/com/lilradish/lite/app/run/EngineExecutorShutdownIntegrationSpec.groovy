package com.lilradish.lite.app.run

import com.lilradish.lite.app.codestep.CodeStepDeployment
import com.lilradish.lite.testutil.run.EngineCodeTasks
import java.lang.reflect.Proxy
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.function.Supplier
import javax.sql.DataSource
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

class EngineExecutorShutdownIntegrationSpec extends Specification {

    private final List<EngineExecutor> executors = []

    private final List<AnnotationConfigApplicationContext> contexts = []

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
        contexts.each { context -> completed { context.close() } }
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

    private static DataSource closingStore(List<String> happened) {
        Proxy.newProxyInstance(EngineExecutorShutdownIntegrationSpec.classLoader, [DataSource, DisposableBean] as Class[]) {
            proxy, method, arguments ->
                if (method.name == "destroy") {
                    happened << "store closed"
                }
                null
        } as DataSource
    }

    private static DisposableBean lettingGo(List<String> happened) {
        { -> happened << "lock let go" } as DisposableBean
    }

    private AnnotationConfigApplicationContext inContext(List<String> happened) {
        def context = new AnnotationConfigApplicationContext()
        context.registerBean("engineExecutor", EngineExecutor, new BeanDefinitionCustomizer[0])
        context.registerBean("store", DataSource, { closingStore(happened) } as Supplier<DataSource>,
                new BeanDefinitionCustomizer[0])
        context.registerBean("codeStepDeployment", CodeStepDeployment,
                { EngineExecutors.A_MINUTE } as Supplier<CodeStepDeployment>, new BeanDefinitionCustomizer[0])
        context.registerBean(OneProcess.BEAN, DisposableBean, { lettingGo(happened) } as Supplier<DisposableBean>,
                new BeanDefinitionCustomizer[0])
        contexts << context
        context.refresh()
        executors << context.getBean(EngineExecutor)
        context
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
        def release = new CountDownLatch(1)
        def executor = context.getBean(EngineExecutor)
        executor.execute {
            started.countDown()
            release.await()
            happened << "task finished"
        }
        assert started.await(5, TimeUnit.SECONDS)

        when:
        drainedWhileHeld(executor, release) { context.close() }

        then:
        happened.findAll { it != "lock let go" } == ["task finished", "store closed"]

        and: "which the context held as the one depending on the other"
        recorded.contains("store")

        cleanup:
        release.countDown()
    }

    /** A second process may start the moment the lock goes; what is still running here must be written down first. */
    def "in a context it is destroyed before the one-process lock is let go, code draining finishing while it is held"() {
        given:
        def happened = [].asSynchronized()
        def context = inContext(happened)
        def started = new CountDownLatch(1)
        def release = new CountDownLatch(1)
        def executor = context.getBean(EngineExecutor)
        EngineCodeTasks.run(executor) {
            started.countDown()
            release.await()
            happened << "code finished"
        }
        assert started.await(5, TimeUnit.SECONDS)
        def dependents = context.beanFactory.getDependentBeans(OneProcess.BEAN) as List

        when:
        drainedWhileHeld(executor, release) { context.close() }

        then:
        happened.findAll { it != "store closed" } == ["code finished", "lock let go"]

        and: "ordered by the lock's name as the context held it before closing, the pools taking nothing of it"
        dependents == ["engineExecutor"]

        cleanup:
        release.countDown()
    }

    /** Published before any lifecycle stops and before the pool drains, so nothing goes out while it drains. */
    def "in a context it says it is stopping once the context closes, and not before"() {
        given:
        def context = inContext([])
        def executor = context.getBean(EngineExecutor)
        def before = executor.stopping()

        when:
        completed { context.close() }

        then:
        !before
        executor.stopping()
    }
}
