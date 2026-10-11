package com.lilradish.lite.domain.referencelist;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.text.Legibility;
import com.lilradish.lite.domain.text.ProseRefusal;
import org.jspecify.annotations.Nullable;

/** A line ends in LF alone, matching a page's text box; other endings are refused, not rewritten. */
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
