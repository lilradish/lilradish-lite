package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

/** One change to a run's ceiling, made or asked for, which is how a raise waiting on approval is decided. */
public record CeilingChangeId(UUID value) {

    public CeilingChangeId {
        requireNonNull(value, "CeilingChangeId must not be null");
    }
}
