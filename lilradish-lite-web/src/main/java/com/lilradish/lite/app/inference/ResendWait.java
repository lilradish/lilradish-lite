package com.lilradish.lite.app.inference;

import java.time.Duration;

/**
 * An interface so the resend may only wait and
 * ask, and never stop what the application stops.
 */
interface ResendWait {

    /** Whether the application is already stopping, so no wait would be waited out. */
    boolean stopped();

    /** False where the wait was cut short because the application is stopping. */
    boolean waitedOut(Duration wait) throws InterruptedException;
}
