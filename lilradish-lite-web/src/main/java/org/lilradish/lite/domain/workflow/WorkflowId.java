package org.lilradish.lite.domain.workflow;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

/** Version-independent: one entry keeps one id across all its versions. */
public record WorkflowId(UUID value) {

    public WorkflowId {
        requireNonNull(value, "WorkflowId must not be null");
    }
}
