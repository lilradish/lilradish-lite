package com.lilradish.lite.domain.declaration;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.text.Legibility;

/** Labels are never sent to a model. An absent label uses the field name. Spacing is left as typed: nothing is told apart by it. */
public record FieldLabel(String value) {

    /** Level with the column's own bound, and counted the way that check counts. */
    private static final int MAXIMUM_LENGTH = 128;

    public FieldLabel {
        requireNonNull(value, "FieldLabel must not be null");
        Legibility.requireOneWellFormedLine(value, "FieldLabel");
        Legibility.requireNotEmpty(value, "FieldLabel");
        Legibility.requireSomethingVisible(value, "FieldLabel");
        Legibility.requireWithinMaximumLength(value, MAXIMUM_LENGTH, "FieldLabel");
    }
}
