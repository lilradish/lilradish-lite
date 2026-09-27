package org.lilradish.lite.domain.registry;

import static java.util.Objects.requireNonNull;

import org.lilradish.lite.domain.text.Legibility;

/**
 * One line saying what an entry does, for whoever picks it. An entry saying nothing holds none of
 * these rather than an empty one. Spacing is left as typed: nothing is told apart by it.
 */
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
