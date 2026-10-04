package org.lilradish.lite.app.run

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import spock.lang.Specification

class EngineTaskBarrierSpec extends Specification {

    EngineExecutor executor

    def cleanup() {
        executor?.destroy()
    }

    def "a barrier sees no submissions before the engine hands any work over"() {
        given:
        executor = EngineExecutors.of(Stub(DataSource))
        def tasks = EngineTaskBarrier.attach(executor)

        when:
        tasks.awaitDrained()

        then:
        tasks.submittedCount() == 0
    }

    def "a barrier waits for a running task and its handoff to the code pool"() {
        given:
        executor = EngineExecutors.of(Stub(DataSource))
        def tasks = EngineTaskBarrier.attach(executor)
        def entered = new CountDownLatch(1)
        def release = new CountDownLatch(1)
        def codeDone = new CountDownLatch(1)
        executor.execute {
            entered.countDown()
            assert release.await(10, TimeUnit.SECONDS)
            executor.executeCode { codeDone.countDown() }
        }
        assert entered.await(2, TimeUnit.SECONDS)
        def waiting = new FutureTask<Boolean>({ tasks.awaitDrained(); true } as Callable<Boolean>)
        def waiter = Thread.ofPlatform().daemon(true).start(waiting)

        expect:
        !waiting.isDone()

        when:
        release.countDown()

        then:
        waiting.get(2, TimeUnit.SECONDS)
        codeDone.count == 0
        tasks.submittedCount() == 2

        cleanup:
        release.countDown()
        waiter.join(2000)
    }

    def "a rejected task is counted as submitted and cancelled before the barrier returns"() {
        given:
        executor = EngineExecutors.of(Stub(DataSource))
        def tasks = EngineTaskBarrier.attach(executor)
        executor.destroy()
        def ran = false

        when:
        executor.execute { ran = true }
        tasks.awaitDrained()

        then:
        tasks.submittedCount() == 1
        !ran
    }

    def "attaching cannot silently replace an existing task decorator"() {
        given:
        executor = EngineExecutors.of(Stub(DataSource))
        EngineTaskBarrier.attach(executor)

        when:
        EngineTaskBarrier.attach(executor)

        then:
        def refused = thrown(AssertionError)
        refused.message.contains('must not replace an engine task decorator')
    }
}
