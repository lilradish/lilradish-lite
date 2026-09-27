package org.lilradish.lite.domain.declaration;

import static java.util.Objects.requireNonNull;

import org.lilradish.lite.domain.text.Legibility;

/**
 * What people read a field as. A field with none is read by its name, and never sent to a model either
 * way. Spacing is left as typed: nothing is told apart by it.
 */
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
