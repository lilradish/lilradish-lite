package org.lilradish.lite.domain.workflow;

import static java.util.Objects.requireNonNull;

public record WorkflowRef(WorkflowId id, VersionSelector version) {

    public WorkflowRef {
        requireNonNull(id, "WorkflowRef id must not be null");
        requireNonNull(version, "WorkflowRef version must not be null");
    }
}
