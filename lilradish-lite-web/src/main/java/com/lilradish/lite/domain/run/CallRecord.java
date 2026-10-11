package com.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.inference.ModelCallOutcome;
import org.jspecify.annotations.Nullable;

/**
 * @param outcome none while it is still out
 * @param spentUp whether the model turned it away for the last time as what may be spent with it is used up
 */
public record CallRecord(
        ModelCallId id,
        RunStepSendAttemptId attempt,
        @Nullable ModelCallOutcome outcome,
        boolean spentUp) {

    public CallRecord {
        requireNonNull(id, "CallRecord id must not be null");
        requireNonNull(attempt, "CallRecord attempt must not be null");
        if (spentUp && outcome != ModelCallOutcome.TURNED_AWAY) {
            throw new IllegalArgumentException("CallRecord is spent up only once it ended turned away");
        }
    }
}
