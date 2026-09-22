package org.lilradish.lite.domain.workflow;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

/** Version-independent: one entry keeps one id across all its versions. */
public record PromptId(UUID value) {

    public PromptId {
        requireNonNull(value, "PromptId must not be null");
    }
}
