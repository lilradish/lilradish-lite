package com.lilradish.lite.domain.registry;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.text.Legibility;

/** An entry with no purpose holds no instance rather than an empty one. Spacing is left as typed: nothing is told apart by it. */
public record EntryPurpose(String value) {

    /** Level with the column's own bound, and counted the way that check counts. */
    private static final int MAXIMUM_LENGTH = 512;

    public EntryPurpose {
        requireNonNull(value, "EntryPurpose must not be null");
        Legibility.requireOneWellFormedLine(value, "EntryPurpose");
        Legibility.requireNotEmpty(value, "EntryPurpose");
        Legibility.requireSomethingVisible(value, "EntryPurpose");
        Legibility.requireWithinMaximumLength(value, MAXIMUM_LENGTH, "EntryPurpose");
    }
}
