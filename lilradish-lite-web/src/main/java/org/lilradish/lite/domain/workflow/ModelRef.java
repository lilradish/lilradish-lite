package org.lilradish.lite.domain.workflow;

import static java.util.Objects.requireNonNull;

public record ModelRef(ModelId id, VersionSelector version) {

    public ModelRef {
        requireNonNull(id, "ModelRef id must not be null");
        requireNonNull(version, "ModelRef version must not be null");
    }
}
