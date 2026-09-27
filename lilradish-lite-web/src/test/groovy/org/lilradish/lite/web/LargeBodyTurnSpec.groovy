package org.lilradish.lite.web

import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

class LargeBodyTurnSpec extends Specification {

    static final Duration SHORT_WAIT = Duration.ofMillis(50)

    static final Duration LONG_WAIT = Duration.ofSeconds(30)

    static final int ONE_WAITING = 1

    def cleanup() {
        Thread.interrupted()
    }

    /** A waiter blocked on the turn, counted among those waiting, and what its take answered once it ends. */
    private static CompletableFuture<Boolean> waitingOn(LargeBodyTurn turn, List<Thread> started) {
        def waited = new CompletableFuture<Boolean>()
        started << Thread.start { waited.complete(turn.take()) }
        new PollingConditions(timeout: 5).eventually {
            assert turn.turn.hasQueuedThreads()
        }
        waited
    }

    def "a free turn is taken at once"() {
        given:
        def turn = new LargeBodyTurn(LONG_WAIT, ONE_WAITING)

        when:
        def taken = turn.take()

        then:
        taken

        and: "held from then on, so nobody else holds it too, and nobody left counted as waiting"
        turn.turn.availablePermits() == 0
        turn.waiting.get() == 0
    }

    def "a turn another holds is refused once the whole wait has run out, and not before"() {
        given:
        def turn = new LargeBodyTurn(SHORT_WAIT, ONE_WAITING)
        turn.take()

        when:
        long started = System.nanoTime()
        def taken = turn.take()
        long waited = System.nanoTime() - started

        then:
        !taken
        waited >= SHORT_WAIT.toNanos()

        and: "the turn still the first holder's, nothing interrupted, and the wait no longer counted"
        turn.turn.availablePermits() == 0
        !Thread.currentThread().isInterrupted()
        turn.waiting.get() == 0
    }

    def "an interrupted wait takes nothing and keeps the interrupt"() {
        given:
        def turn = new LargeBodyTurn(LONG_WAIT, ONE_WAITING)
        Thread.currentThread().interrupt()

        when:
        def taken = turn.take()

        then:
        !taken
        Thread.interrupted()

        and: "the turn left free for whoever comes next, and the wait no longer counted"
        turn.turn.availablePermits() == 1
        turn.waiting.get() == 0
    }

    def "a take finding as many waiting as may wait is refused at once, the waiter and the holder left as they were"() {
        given:
        def turn = new LargeBodyTurn(LONG_WAIT, ONE_WAITING)
        turn.take()
        List<Thread> started = []
        def waited = waitingOn(turn, started)

        when:
        long asked = System.nanoTime()
        def taken = turn.take()
        long refusedAfter = System.nanoTime() - asked

        then:
        !taken
        refusedAfter < LONG_WAIT.toNanos().intdiv(2)

        and: "still held by the first, the one waiting still waiting, and the refused one not counted"
        turn.turn.availablePermits() == 0
        !waited.isDone()
        turn.waiting.get() == 1

        cleanup:
        turn.give()
        started*.join()
    }

    def "a turn given back lets in one already waiting for it, well before its wait runs out"() {
        given:
        def turn = new LargeBodyTurn(LONG_WAIT, ONE_WAITING)
        turn.take()
        List<Thread> started = []
        def waited = waitingOn(turn, started)

        when:
        turn.give()

        then:
        waited.get(5, TimeUnit.SECONDS)

        and: "held by the one let in, so the turn is not free twice, and nobody left counted as waiting"
        turn.turn.availablePermits() == 0
        turn.waiting.get() == 0

        cleanup:
        started*.join()
    }
}
