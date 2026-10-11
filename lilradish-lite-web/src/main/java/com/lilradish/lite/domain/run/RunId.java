package com.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

/** A run has its own identity; the workflow keeps the same identity across its runs. */
public record RunId(UUID value) {

    public RunId {
        requireNonNull(value, "RunId must not be null");
    }
}
