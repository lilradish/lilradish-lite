package org.lilradish.lite.domain.inference;

/**
 * What the caller is told while a call is under way, so each step is on record before the next one
 * is taken.
 *
 * <p>{@link #aboutToSend} is called before the first send. {@link #turnedAway} is called only for a
 * turnaway that a wait and a resend attempt follow, before the wait, and {@link #mayResend} after it:
 * true once the resend is on record. False, an interrupt in the wait (its flag restored), or the
 * shutdown signal, which cuts every wait short and leaves {@link #mayResend} unasked, ends the call as
 * {@link CallOutcome.NotResent}. The turnaway that ends a call is returned in {@link
 * CallOutcome.TurnedAway} and never passed here; one heard once the application is already stopping
 * ends it so, since no wait could follow. An exception from any of the three propagates as it is, and
 * nothing more is sent.
 */
public interface CallProgress {

    void aboutToSend();

    void turnedAway(TurnAway turnAway);

    boolean mayResend();
}
