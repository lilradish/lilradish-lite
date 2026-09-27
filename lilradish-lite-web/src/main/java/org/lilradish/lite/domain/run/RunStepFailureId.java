package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

/** One failure written down on a step, which an attempt made by Try sending answers. */
public record RunStepFailureId(UUID value) {

    public RunStepFailureId {
        requireNonNull(value, "RunStepFailureId must not be null");
    }
}
