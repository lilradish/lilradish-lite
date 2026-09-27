package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.inference.ModelCallPurpose;

/** Why a step failed: its tries spent, which is worked out, or a failure written down. */
public sealed interface StepFailure permits StepFailure.TriesSpent, StepFailure.Recorded {

    record TriesSpent(int used, int declared) implements StepFailure {

        public TriesSpent {
            if (declared < 1 || used < declared) {
                throw new IllegalArgumentException(
                        "StepFailure.TriesSpent used " + used + " of " + declared + " is not spent");
            }
        }
    }

    /** @param purpose what the try was being sent to a model for when it failed; none where nothing was being sent */
    record Recorded(RunStepFailureReason reason, @Nullable ModelCallPurpose purpose) implements StepFailure {

        public Recorded {
            requireNonNull(reason, "StepFailure.Recorded reason must not be null");
        }
    }
}
