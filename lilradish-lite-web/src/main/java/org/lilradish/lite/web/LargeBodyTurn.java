package org.lilradish.lite.web;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** The one turn a large body is read and handled in, how long another waits for it, and how many wait at once. */
final class LargeBodyTurn {

    private final Semaphore turn = new Semaphore(1);

    private final AtomicInteger waiting = new AtomicInteger();

    private final long longestWaitNanos;

    private final int mostWaiting;

    LargeBodyTurn(Duration longestWait, int mostWaiting) {
        this.longestWaitNanos = longestWait.toNanos();
        this.mostWaiting = mostWaiting;
    }

    /**
     * Whether the turn was taken within the wait. Refused without waiting where as many as may wait already do; an
     * interrupted wait took nothing, and the interrupt is kept.
     */
    boolean take() {
        if (waiting.incrementAndGet() > mostWaiting) {
            waiting.decrementAndGet();
            return false;
        }
        try {
            return turn.tryAcquire(longestWaitNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            waiting.decrementAndGet();
        }
    }

    /** Only after a take that answered true: one without it lets two large bodies in at once. */
    void give() {
        turn.release();
    }
}
