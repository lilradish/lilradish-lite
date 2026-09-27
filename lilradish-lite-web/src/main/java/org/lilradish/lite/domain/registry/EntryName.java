package org.lilradish.lite.domain.registry;

import static java.util.Objects.requireNonNull;

import org.lilradish.lite.domain.text.Legibility;

/**
 * What an entry is called, held to the rule a group's name is held to and refused rather than tidied,
 * for the reasons {@link Legibility} gives. Whether two names are one is the store's to say.
 */
public record EntryName(String value) {

    /** Level with the column's own bound, and counted the way that check counts. */
    private static final int MAXIMUM_LENGTH = 128;

    public EntryName {
        requireNonNull(value, "EntryName must not be null");
        Legibility.requireOneWellFormedLine(value, "EntryName");
        Legibility.requireNotEmpty(value, "EntryName");
        Legibility.requireSpaceDecidesNothing(value, "EntryName");
        Legibility.requireSomethingVisible(value, "EntryName");
        Legibility.requireWithinMaximumLength(value, MAXIMUM_LENGTH, "EntryName");
    }
}
