package org.lilradish.lite.domain.workflow;

import static java.util.Objects.requireNonNull;

import java.util.UUID;
import org.lilradish.lite.domain.wire.JsonValue;

/**
 * Where a bound value comes from: what the workflow takes, what a step of the same version gives back, or a
 * constant written into the version. There is no fourth way in.
 */
public sealed interface BindingSource
        permits BindingSource.WorkflowInput, BindingSource.StepOutput, BindingSource.Written {

    record WorkflowInput(Pointer pointer) implements BindingSource {

        public WorkflowInput {
            requireNonNull(pointer, "BindingSource.WorkflowInput pointer must not be null");
        }
    }

    /** @param step the stored key of a step of the same version */
    record StepOutput(UUID step, Pointer pointer) implements BindingSource {

        public StepOutput {
            requireNonNull(step, "BindingSource.StepOutput step must not be null");
            requireNonNull(pointer, "BindingSource.StepOutput pointer must not be null");
        }
    }

    /** @param constant as JSON writes it, which {@link ConstantFit} holds to what it fills */
    record Written(JsonValue constant) implements BindingSource {

        public Written {
            requireNonNull(constant, "BindingSource.Written constant must not be null");
        }
    }
}
