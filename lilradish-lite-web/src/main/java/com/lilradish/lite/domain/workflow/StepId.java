package com.lilradish.lite.domain.workflow;

import com.lilradish.lite.domain.text.Identifiers;

/** What later steps call a step by; submitting refuses a version where two steps share one. */
public record StepId(String value) {

    public StepId {
        value = Identifiers.requireText(value, "StepId");
    }
}
