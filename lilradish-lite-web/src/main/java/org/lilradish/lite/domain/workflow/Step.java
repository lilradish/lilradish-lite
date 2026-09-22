package org.lilradish.lite.domain.workflow;

import static java.util.Objects.requireNonNull;

import java.util.Map;

public sealed interface Step {

    StepId id();

    StepConstraints constraints();

    Map<ParameterName, BindingSource> inputs();

    record ModelStep(
            StepId id,
            StepConstraints constraints,
            Map<ParameterName, BindingSource> inputs,
            PromptRef prompt,
            ModelSelector model)
            implements Step {
        public ModelStep {
            requireNonNull(id, "ModelStep id must not be null");
            requireNonNull(constraints, "ModelStep constraints must not be null");
            requireNonNull(inputs, "ModelStep inputs must not be null");
            requireNonNull(prompt, "ModelStep prompt must not be null");
            requireNonNull(model, "ModelStep model must not be null");
            inputs = Map.copyOf(inputs);
        }
    }

    record SubWorkflowStep(
            StepId id, StepConstraints constraints, Map<ParameterName, BindingSource> inputs, WorkflowRef workflow)
            implements Step {
        public SubWorkflowStep {
            requireNonNull(id, "SubWorkflowStep id must not be null");
            requireNonNull(constraints, "SubWorkflowStep constraints must not be null");
            requireNonNull(inputs, "SubWorkflowStep inputs must not be null");
            requireNonNull(workflow, "SubWorkflowStep workflow must not be null");
            inputs = Map.copyOf(inputs);
        }
    }
}
