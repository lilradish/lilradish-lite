package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.inference.ModelCallPurpose;

/**
 * A failure written down on a step.
 *
 * @param onTry the try the step was on when it failed; none where it was on no try, and then it never ends
 * @param purpose what the step was sending that try for, to be produced or to be reviewed; exactly where it was on one
 * @param answered whether an attempt has since answered it, which ends it
 */
public record FailureRecord(
        RunStepFailureId id,
        RunStepFailureReason reason,
        @Nullable ProductionId onTry,
        @Nullable ModelCallPurpose purpose,
        Instant at,
        boolean answered) {

    public FailureRecord {
        requireNonNull(id, "FailureRecord id must not be null");
        requireNonNull(reason, "FailureRecord reason must not be null");
        requireNonNull(at, "FailureRecord at must not be null");
        if ((onTry == null) != (purpose == null)) {
            throw new IllegalArgumentException(
                    "FailureRecord names what it was sending for exactly where it was on a try");
        }
        if (purpose == ModelCallPurpose.HELP) {
            throw new IllegalArgumentException("FailureRecord was sending a try to be produced or to be reviewed");
        }
    }
}
