package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

/** One try of one step in one run: written when it is asked for, whoever produces it. */
public record ProductionId(UUID value) {

    public ProductionId {
        requireNonNull(value, "ProductionId must not be null");
    }
}
