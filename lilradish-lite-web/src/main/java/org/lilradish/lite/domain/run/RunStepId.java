package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

/** A step as one run holds it, written the first time anything is written about that step there. */
public record RunStepId(UUID value) {

    public RunStepId {
        requireNonNull(value, "RunStepId must not be null");
    }
}
