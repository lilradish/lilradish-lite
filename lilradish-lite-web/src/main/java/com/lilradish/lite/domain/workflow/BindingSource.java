package com.lilradish.lite.domain.workflow;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.wire.JsonValue;
import java.util.UUID;

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
