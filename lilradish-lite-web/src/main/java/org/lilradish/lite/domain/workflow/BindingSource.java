package org.lilradish.lite.domain.workflow;

import static java.util.Objects.requireNonNull;

/** The parameter fed is the key this is stored under, so it is not a component here. */
public sealed interface BindingSource {

    record FromWorkflowInput(FieldName inputName) implements BindingSource {
        public FromWorkflowInput {
            requireNonNull(inputName, "FromWorkflowInput inputName must not be null");
        }
    }

    record FromStepOutput(StepId stepId, FieldName outputName) implements BindingSource {
        public FromStepOutput {
            requireNonNull(stepId, "FromStepOutput stepId must not be null");
            requireNonNull(outputName, "FromStepOutput outputName must not be null");
        }
    }
}
