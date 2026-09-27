package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

/** One value one try gave back, which is what a review decides and what a later input traces to. */
public record ProductionValueId(UUID value) {

    public ProductionValueId {
        requireNonNull(value, "ProductionValueId must not be null");
    }
}
