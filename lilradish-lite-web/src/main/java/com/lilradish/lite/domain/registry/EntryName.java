package com.lilradish.lite.domain.registry;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.text.Legibility;

/**
 * Uses the group-name rule and is refused rather than tidied, for the reasons {@link Legibility} gives.
 * Name equality is the store's to decide.
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
