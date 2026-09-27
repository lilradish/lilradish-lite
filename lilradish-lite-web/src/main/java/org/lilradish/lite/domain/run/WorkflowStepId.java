package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

/** A step of a workflow version by its stored key, the same step in every run of that version. */
public record WorkflowStepId(UUID value) {

    public WorkflowStepId {
        requireNonNull(value, "WorkflowStepId must not be null");
    }
}
