package org.lilradish.lite.app.inference;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.context.SmartLifecycle;

/**
 * The signal that the application is stopping, as a call waiting to be sent again hears it: stopped,
 * every wait under way ends at once and every later one returns at once, so no call is resent. A send
 * in flight is never interrupted, which would spend a try on the stop; it is left to come back.
 *
 * <p>Stopped after the web server stops taking requests and before any executor, whose tasks may be
 * waiting here. Only the adapter may depend on this: a bean that depends on it is stopped before it
 * whatever its phase, so an executor that did would wait on the very waits this is there to end.
 */
public final class ModelCallShutdown implements SmartLifecycle, ResendWait {

    public static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 4096;

    private volatile CountDownLatch stopping = new CountDownLatch(1);

    private volatile boolean running;

    ModelCallShutdown() {}

    @Override
    public void start() {
        if (stopping.getCount() == 0) {
            stopping = new CountDownLatch(1);
        }
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        stopping.countDown();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    // Pausing is not stopping: a paused context is resumed, and the calls it held should still be resent.
    @Override
    public boolean isPauseable() {
        return false;
    }

    @Override
    public boolean stopped() {
        return stopping.getCount() == 0;
    }

    @Override
    public boolean waitedOut(Duration wait) throws InterruptedException {
        return !stopping.await(TimeUnit.NANOSECONDS.convert(wait), TimeUnit.NANOSECONDS);
    }
}
