package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.workflow.Binding;
import org.lilradish.lite.domain.workflow.ModelChoice;
import org.lilradish.lite.domain.workflow.Producer;
import org.lilradish.lite.domain.workflow.StepId;

/**
 * One step of a version in service, as every run of that version runs it.
 *
 * @param order where it runs, counted from one
 * @param producer none exactly where what it runs produces nothing of its own: a workflow or a route
 * @param tries none exactly where it has no producer
 * @param reviewer the model reviewing its productions; none where a person does, or where nobody does
 * @param bindings what fills each input it takes, in the order written
 */
public record PlannedStep(
        WorkflowStepId id,
        int order,
        StepId name,
        StepRuns runs,
        @Nullable Producer producer,
        @Nullable Integer tries,
        @Nullable ModelChoice reviewer,
        List<Binding> bindings) {

    public PlannedStep {
        requireNonNull(id, "PlannedStep id must not be null");
        requireNonNull(name, "PlannedStep name must not be null");
        requireNonNull(runs, "PlannedStep runs must not be null");
        bindings = List.copyOf(requireNonNull(bindings, "PlannedStep bindings must not be null"));
        if (order < 1) {
            throw new IllegalArgumentException("PlannedStep order must be positive: " + order);
        }
        boolean produces = runs instanceof StepRuns.Question || runs instanceof StepRuns.Code;
        if (produces != (producer != null) || produces != (tries != null) || (!produces && reviewer != null)) {
            throw new IllegalArgumentException("PlannedStep " + name.value()
                    + " names a producer, tries and a reviewer exactly where what it runs produces");
        }
        if (tries != null && tries < 1) {
            throw new IllegalArgumentException("PlannedStep tries must be positive: " + tries);
        }
    }

    /** The tries it declares; none where what it runs makes none. */
    public int declaredTries() {
        return tries == null ? 0 : tries;
    }
}
