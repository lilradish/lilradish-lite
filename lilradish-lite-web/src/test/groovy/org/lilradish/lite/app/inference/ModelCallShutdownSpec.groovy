package org.lilradish.lite.app.inference

import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.context.support.GenericApplicationContext
import org.springframework.scheduling.concurrent.ExecutorConfigurationSupport
import spock.lang.Specification
import spock.lang.Timeout

/** Every wait here that would run long is one this is there to cut short: a failure times out rather than hangs. */
@Timeout(10)
class ModelCallShutdownSpec extends Specification {

    static final Duration AN_HOUR = Duration.ofHours(1)

    ModelCallShutdown shutdown = new ModelCallShutdown()

    def "stops after the web server and before any executor whose tasks may be waiting on it"() {
        expect:
        shutdown.phase == ModelCallShutdown.PHASE
        ModelCallShutdown.PHASE > ExecutorConfigurationSupport.DEFAULT_PHASE
        ModelCallShutdown.PHASE < WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE
    }

    def "runs from its start until its stop"() {
        when:
        def beforeStart = shutdown.running
        shutdown.start()
        def afterStart = shutdown.running
        shutdown.stop()

        then:
        !beforeStart
        afterStart
        !shutdown.running
    }

    def "a context paused leaves it running, and only closing the context stops it"() {
        given:
        def context = new GenericApplicationContext()
        context.registerBean(ModelCallShutdown, { -> new ModelCallShutdown() } as Supplier)
        context.refresh()
        def registered = context.getBean(ModelCallShutdown)

        when:
        context.pause()
        def runningWhilePaused = registered.running
        context.close()

        then:
        !registered.pauseable
        runningWhilePaused
        !registered.running
    }

    def "says it is stopped from its stop until it is started again"() {
        when:
        shutdown.start()
        def whileRunning = shutdown.stopped()
        shutdown.stop()
        def onceStopped = shutdown.stopped()
        shutdown.start()

        then:
        !whileRunning
        onceStopped
        !shutdown.stopped()
    }

    def "a wait nothing cuts short is waited out"() {
        given:
        shutdown.start()

        expect:
        shutdown.waitedOut(Duration.ofMillis(5))
    }

    def "stopping ends a wait under way at once, as cut short"() {
        given:
        shutdown.start()
        def waited = new AtomicReference<Boolean>()
        def waiting = Thread.start { waited.set(shutdown.waitedOut(AN_HOUR)) }
        while (waiting.state != Thread.State.TIMED_WAITING && waiting.alive) {
            Thread.onSpinWait()
        }

        when:
        shutdown.stop()
        waiting.join()

        then:
        waited.get() == false
    }

    def "once stopped, every later wait returns at once, as cut short"() {
        given:
        shutdown.start()
        shutdown.stop()

        expect:
        !shutdown.waitedOut(AN_HOUR)
        !shutdown.waitedOut(AN_HOUR)
    }

    def "started again after a stop, a wait is waited out again"() {
        given:
        shutdown.start()
        shutdown.stop()

        when:
        shutdown.start()

        then:
        shutdown.running
        shutdown.waitedOut(Duration.ofMillis(5))
    }

    def "an interrupt in a wait is thrown as one, and does not stop anything"() {
        given:
        shutdown.start()
        Thread.currentThread().interrupt()

        when:
        shutdown.waitedOut(AN_HOUR)

        then:
        thrown(InterruptedException)
        shutdown.running
        !shutdown.stopped()
        !Thread.currentThread().interrupted
        shutdown.waitedOut(Duration.ofMillis(5))
    }
}
