package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

/** One call to a model: written before it is sent and ended once. */
public record ModelCallId(UUID value) {

    public ModelCallId {
        requireNonNull(value, "ModelCallId must not be null");
    }
}
