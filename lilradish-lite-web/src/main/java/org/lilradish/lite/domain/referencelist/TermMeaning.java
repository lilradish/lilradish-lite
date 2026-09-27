package org.lilradish.lite.domain.referencelist;

import static java.util.Objects.requireNonNull;

import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.text.Legibility;
import org.lilradish.lite.domain.text.ProseRefusal;

/**
 * A line saying when a term is the right answer and a near one is not. Spacing is left as typed: nothing is
 * told apart by it.
 */
public record TermMeaning(String value) {

    /** Level with the column's own bound, and counted the way that check counts. */
    private static final int MAXIMUM_LENGTH = 512;

    public TermMeaning {
        requireNonNull(value, "TermMeaning must not be null");
        requireWellFormed(value);
    }

    public static @Nullable ProseRefusal refusalOf(String said) {
        requireNonNull(said, "TermMeaning text judged must not be null");
        return ProseRefusal.of(said, false, TermMeaning::requireWellFormed);
    }

    private static void requireWellFormed(String value) {
        Legibility.requireOneWellFormedLine(value, "TermMeaning");
        Legibility.requireNothingConcealed(value, "TermMeaning");
        Legibility.requireNotEmpty(value, "TermMeaning");
        Legibility.requireSomethingVisible(value, "TermMeaning");
        Legibility.requireWithinMaximumLength(value, MAXIMUM_LENGTH, "TermMeaning");
    }
}
