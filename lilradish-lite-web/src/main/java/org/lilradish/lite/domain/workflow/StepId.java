package org.lilradish.lite.domain.workflow;

import org.lilradish.lite.domain.text.Identifiers;

/** What later steps call a step by; submitting refuses a version where two steps share one. */
public record StepId(String value) {

    public StepId {
        value = Identifiers.requireText(value, "StepId");
    }
}
