package org.lilradish.lite.domain.workflow;

/** Unique within one workflow definition. */
public record StepId(String value) {

    public StepId {
        value = Identifiers.requireText(value, "StepId");
    }
}
