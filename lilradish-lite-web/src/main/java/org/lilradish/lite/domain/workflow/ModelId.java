package org.lilradish.lite.domain.workflow;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

/** Version-independent: one entry keeps one id across all its versions. */
public record ModelId(UUID value) {

    public ModelId {
        requireNonNull(value, "ModelId must not be null");
    }
}
