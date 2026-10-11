package com.lilradish.lite.web;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
     * True means the turn was taken. The waiting limit refuses immediately; an interrupted wait
     * takes nothing and preserves the interrupt.
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
