package org.lilradish.lite.domain.referencelist;

import static java.util.Objects.requireNonNull;

import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.text.Legibility;
import org.lilradish.lite.domain.text.ProseRefusal;

/**
 * How to go about choosing among a list's terms at all. A line ends in a line feed alone, which is what a
 * page's text box hands over; any other ending is refused, not rewritten.
 */
public record ListNote(String value) {

    /** Level with the column's own bound, and counted the way that check counts. */
    private static final int MAXIMUM_LENGTH = 2048;

    public ListNote {
        requireNonNull(value, "ListNote must not be null");
        requireWellFormed(value);
    }

    public static @Nullable ProseRefusal refusalOf(String said) {
        requireNonNull(said, "ListNote text judged must not be null");
        return ProseRefusal.of(said, true, ListNote::requireWellFormed);
    }

    private static void requireWellFormed(String value) {
        Legibility.requireWellFormedProse(value, "ListNote");
        Legibility.requireSomethingVisibleInProse(value, "ListNote");
        Legibility.requireWithinMaximumLength(value, MAXIMUM_LENGTH, "ListNote");
    }
}
