package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

/** One execution of one workflow — not the workflow it ran, which keeps its id across every run. */
public record RunId(UUID value) {

    public RunId {
        requireNonNull(value, "RunId must not be null");
    }
}
