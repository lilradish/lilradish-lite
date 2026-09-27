package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.log.annotation.DoNotLog;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.lilradish.lite.domain.wire.JsonValue.JsonObject;

/**
 * One run as one moment of the store holds it: what its version says of it beside what its rows say.
 *
 * @param stopped whether a stop is in force on its tree
 * @param workflowStopped the stop in force on the workflow it runs; none where it is not stopped
 * @param startedWith what it was started with; none beneath another run
 * @param steps one per step of its version, in the order they run
 */
public record RunSnapshot(
        RunId run,
        RunId root,
        GroupId group,
        EntryVersionId version,
        boolean stopped,
        @Nullable StopRecord workflowStopped,
        @DoNotLog @Nullable JsonObject startedWith,
        @DoNotLog RunnableWorkflow workflow,
        List<StepSnapshot> steps) {

    public RunSnapshot {
        requireNonNull(run, "RunSnapshot run must not be null");
        requireNonNull(root, "RunSnapshot root must not be null");
        requireNonNull(group, "RunSnapshot group must not be null");
        requireNonNull(version, "RunSnapshot version must not be null");
        requireNonNull(workflow, "RunSnapshot workflow must not be null");
        steps = List.copyOf(requireNonNull(steps, "RunSnapshot steps must not be null"));
        if (steps.size() != workflow.steps().size()) {
            throw new IllegalArgumentException("RunSnapshot holds a snapshot of other than every step");
        }
        for (int index = 0; index < steps.size(); index++) {
            if (!steps.get(index)
                    .planned()
                    .id()
                    .equals(workflow.steps().get(index).id())) {
                throw new IllegalArgumentException("RunSnapshot holds its steps out of their version's order");
            }
        }
    }

    public Optional<StepSnapshot> step(WorkflowStepId id) {
        requireNonNull(id, "RunSnapshot id must not be null");
        return steps.stream().filter(step -> step.planned().id().equals(id)).findFirst();
    }

    /**
     * The stop holding a new try of {@code step}, none where nothing does: the workflow's where it is stopped,
     * since that holds whatever the step runs, and otherwise the one on what the step runs.
     */
    public @Nullable StopRecord stoppedFor(StepSnapshot step) {
        requireNonNull(step, "RunSnapshot step must not be null");
        return workflowStopped != null ? workflowStopped : step.pinStopped();
    }
}
