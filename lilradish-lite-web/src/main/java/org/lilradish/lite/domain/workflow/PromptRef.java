package org.lilradish.lite.domain.workflow;

import static java.util.Objects.requireNonNull;

public record PromptRef(PromptId id, VersionSelector version) {

    public PromptRef {
        requireNonNull(id, "PromptRef id must not be null");
        requireNonNull(version, "PromptRef version must not be null");
    }
}
