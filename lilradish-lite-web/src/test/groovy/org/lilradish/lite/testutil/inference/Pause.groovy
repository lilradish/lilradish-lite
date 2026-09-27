package org.lilradish.lite.testutil.inference

import groovy.transform.PackageScope
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Where a scripted call stops once its first send is made, until the spec lets it go on: the middle of
 * a call, which is where a race with it is run. Every wait is timed, so a spec that never gets there,
 * or never lets go, fails rather than hangs.
 */
final class Pause {

    private static final long TIMEOUT_SECONDS = 10

    private final CountDownLatch reached = new CountDownLatch(1)

    private final CountDownLatch released = new CountDownLatch(1)

    void awaitReached() {
        if (!reached.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new AssertionError("the call never reached its pause")
        }
    }

    void release() {
        released.countDown()
    }

    /** False where the wait was interrupted, the flag restored for whoever called. */
    @PackageScope
    boolean hold() {
        reached.countDown()
        try {
            if (!released.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("the paused call was never released")
            }
            return true
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt()
            return false
        }
    }
}
