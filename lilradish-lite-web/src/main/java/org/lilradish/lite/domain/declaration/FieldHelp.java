package org.lilradish.lite.domain.declaration;

import static java.util.Objects.requireNonNull;

import org.lilradish.lite.domain.text.Legibility;

/**
 * One line for whoever fills a field or reads it, never sent to a model. Spacing is left as typed:
 * nothing is told apart by it.
 */
public record FieldHelp(String value) {

    /** Level with the column's own bound, and counted the way that check counts. */
    private static final int MAXIMUM_LENGTH = 512;

    public FieldHelp {
        requireNonNull(value, "FieldHelp must not be null");
        Legibility.requireOneWellFormedLine(value, "FieldHelp");
        Legibility.requireNotEmpty(value, "FieldHelp");
        Legibility.requireSomethingVisible(value, "FieldHelp");
        Legibility.requireWithinMaximumLength(value, MAXIMUM_LENGTH, "FieldHelp");
    }
}
