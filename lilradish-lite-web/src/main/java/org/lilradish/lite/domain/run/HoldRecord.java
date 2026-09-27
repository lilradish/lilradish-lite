package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A hold on a step, not yet released: at most one stands on a step at once.
 *
 * @param attempt the attempt to produce that was too long to send, or whose call was turned away; exactly where
 *     it is held for one of those
 * @param spentUp whether that call was turned away as what may be spent with its model is used up
 */
public record HoldRecord(
        RunStepHoldReason reason, Instant since, @Nullable RunStepSendAttemptId attempt, boolean spentUp) {

    public HoldRecord {
        requireNonNull(reason, "HoldRecord reason must not be null");
        requireNonNull(since, "HoldRecord since must not be null");
        if ((reason == RunStepHoldReason.TOO_LONG || reason == RunStepHoldReason.TURNED_AWAY) != (attempt != null)) {
            throw new IllegalArgumentException("HoldRecord names an attempt exactly where it is held for one");
        }
        if (spentUp && reason != RunStepHoldReason.TURNED_AWAY) {
            throw new IllegalArgumentException("HoldRecord is spent up only where its call was turned away");
        }
    }
}
