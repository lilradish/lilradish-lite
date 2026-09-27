package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.lilradish.lite.domain.declaration.Declaration;
import org.lilradish.lite.domain.declaration.OfferedTerms;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.lilradish.lite.domain.workflow.Binding;

/**
 * A workflow version in service as a run of it runs: what it takes and gives back, what fills each value it
 * gives back, and its steps in the order they run.
 *
 * @param lists what each list a field of either of its own halves pins offers
 */
public record RunnableWorkflow(
        Declaration takes,
        Declaration gives,
        Map<EntryVersionId, OfferedTerms> lists,
        List<Binding> outputs,
        List<PlannedStep> steps) {

    public RunnableWorkflow {
        requireNonNull(takes, "RunnableWorkflow takes must not be null");
        requireNonNull(gives, "RunnableWorkflow gives must not be null");
        lists = Map.copyOf(requireNonNull(lists, "RunnableWorkflow lists must not be null"));
        outputs = List.copyOf(requireNonNull(outputs, "RunnableWorkflow outputs must not be null"));
        steps = List.copyOf(requireNonNull(steps, "RunnableWorkflow steps must not be null"));
        for (int index = 0; index < steps.size(); index++) {
            if (steps.get(index).order() != index + 1) {
                throw new IllegalArgumentException("RunnableWorkflow holds a step out of its order");
            }
        }
    }

    public Optional<PlannedStep> step(WorkflowStepId id) {
        requireNonNull(id, "RunnableWorkflow id must not be null");
        return steps.stream().filter(step -> step.id().equals(id)).findFirst();
    }
}
