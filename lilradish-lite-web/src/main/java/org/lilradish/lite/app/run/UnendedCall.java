package org.lilradish.lite.app.run;

import static java.util.Objects.requireNonNull;

import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.inference.ModelCallPurpose;
import org.lilradish.lite.domain.run.ModelCallId;
import org.lilradish.lite.domain.run.ProductionId;
import org.lilradish.lite.domain.run.RunStepSendAttemptId;

/**
 * A call a stopped system left unended, as its tree's lock lets it be read: whether it had been turned away, and
 * whether it was sent again after the newest time it was.
 */
record UnendedCall(
        ModelCallId call,
        ModelCallPurpose purpose,
        @Nullable ProductionId aTry,
        @Nullable RunStepSendAttemptId attempt,
        boolean turnedAwayBefore,
        boolean resent) {

    UnendedCall {
        requireNonNull(call, "UnendedCall call must not be null");
        requireNonNull(purpose, "UnendedCall purpose must not be null");
        if (resent && !turnedAwayBefore) {
            throw new IllegalArgumentException("UnendedCall was sent again after no turnaway");
        }
    }

    /**
     * Whether it may have been taken up, so that what it cost is not known: out as it was sent, or sent again after
     * its newest turnaway. One waiting to be sent again was never taken up since.
     */
    boolean wasOut() {
        return !turnedAwayBefore || resent;
    }
}
