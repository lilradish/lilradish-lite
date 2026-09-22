package org.lilradish.lite.domain.workflow;

import static java.util.Objects.requireNonNull;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Steps are held in dependency order: a referenced step must appear before the step referencing it.
 * That single constraint is what lets one forward scan reject self-reference, forward reference and
 * cycles within this definition alike, with no topological sort.
 *
 * <p>Only the input half of the I/O signature exists here: declaring {@code outputs} waits on the
 * DSL choosing between an implicit last-step result and an explicit binding table. Two further
 * checks are out of reach at this layer: output-name validity and cross-definition recursion.
 * Both, and any bound on nesting depth, belong to whichever layer resolves a {@code PromptRef}
 * or {@code WorkflowRef}.
 */
public record WorkflowDefinition(Set<FieldName> inputs, List<Step> steps) {

    public WorkflowDefinition {
        requireNonNull(inputs, "WorkflowDefinition inputs must not be null");
        requireNonNull(steps, "WorkflowDefinition steps must not be null");
        inputs = Set.copyOf(inputs);
        steps = List.copyOf(steps);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("WorkflowDefinition must declare at least one step");
        }
        validateStepGraph(inputs, steps);
    }

    private static void validateStepGraph(Set<FieldName> inputs, List<Step> steps) {
        Set<StepId> declaredStepIds = HashSet.newHashSet(steps.size());
        for (Step step : steps) {
            String stepIdValue = step.id().value();
            if (declaredStepIds.contains(step.id())) {
                throw new IllegalArgumentException(
                        "WorkflowDefinition must not declare step " + stepIdValue + " twice");
            }
            for (BindingSource source : step.inputs().values()) {
                switch (source) {
                    case BindingSource.FromWorkflowInput(FieldName inputName) -> {
                        if (!inputs.contains(inputName)) {
                            throw new IllegalArgumentException("WorkflowDefinition step " + stepIdValue
                                    + " binds undeclared workflow input " + inputName.value());
                        }
                    }
                    case BindingSource.FromStepOutput fromStepOutput -> {
                        if (!declaredStepIds.contains(fromStepOutput.stepId())) {
                            throw new IllegalArgumentException("WorkflowDefinition step " + stepIdValue
                                    + " binds output of "
                                    + fromStepOutput.stepId().value()
                                    + ", which is not an earlier step");
                        }
                    }
                }
            }
            declaredStepIds.add(step.id());
        }
    }
}
