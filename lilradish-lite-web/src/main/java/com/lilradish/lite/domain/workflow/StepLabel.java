package com.lilradish.lite.domain.workflow;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.text.Legibility;

public record StepLabel(String value) {

    private static final int MAXIMUM_LENGTH = 128;

    public StepLabel {
        requireNonNull(value, "StepLabel must not be null");
        Legibility.requireOneWellFormedLine(value, "StepLabel");
        Legibility.requireNotEmpty(value, "StepLabel");
        Legibility.requireSpaceDecidesNothing(value, "StepLabel");
        Legibility.requireSomethingVisible(value, "StepLabel");
        Legibility.requireWithinMaximumLength(value, MAXIMUM_LENGTH, "StepLabel");
    }
}
