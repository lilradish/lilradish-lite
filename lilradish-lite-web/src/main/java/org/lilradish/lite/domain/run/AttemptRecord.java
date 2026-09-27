package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.inference.ModelCallPurpose;

/**
 * One attempt to send a try to a model, to produce it or to review it.
 *
 * @param tooLong whether nothing was sent for it: what it would send was measured longer than the model takes, or,
 *     to review, could not be built
 * @param unbuilt why what it would send to review could not be built; none where it was built
 */
public record AttemptRecord(
        RunStepSendAttemptId id,
        ModelCallPurpose purpose,
        boolean tooLong,
        @Nullable ReviewUnbuiltReason unbuilt) {

    public AttemptRecord {
        requireNonNull(id, "AttemptRecord id must not be null");
        requireNonNull(purpose, "AttemptRecord purpose must not be null");
        if (purpose == ModelCallPurpose.HELP) {
            throw new IllegalArgumentException("AttemptRecord sends a try to produce it or to review it");
        }
        if (unbuilt != null && (purpose != ModelCallPurpose.REVIEW || !tooLong)) {
            throw new IllegalArgumentException("AttemptRecord is left unbuilt only to review, nothing being sent");
        }
    }
}
