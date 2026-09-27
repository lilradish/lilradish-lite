package org.lilradish.lite.domain.referencelist;

import static java.util.Objects.requireNonNull;

import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.text.Legibility;
import org.lilradish.lite.domain.text.ProseRefusal;

/**
 * One word an answer may be, as somebody would read it: what a model answers with and a route's case is
 * keyed by. Neither spacing nor a character that shows nothing tells two terms apart, since both read as one.
 */
public record Term(String value) {

    /** The most one list version holds, counted where a term is added. */
    public static final int MOST_IN_A_LIST = 256;

    /** Level with the column's own bound, and counted the way that check counts. */
    public static final int MAXIMUM_LENGTH = 128;

    public Term {
        requireNonNull(value, "Term must not be null");
        requireWellFormed(value);
    }

    /** What shows nothing is named before anything else found wrong, nothing on screen showing it. */
    public static @Nullable ProseRefusal refusalOf(String said) {
        requireNonNull(said, "Term text judged must not be null");
        ProseRefusal refusal = ProseRefusal.of(said, false, Term::requireWellFormed);
        return refusal == ProseRefusal.UNUSABLE && Legibility.holdsInvisible(said) ? ProseRefusal.INVISIBLE : refusal;
    }

    private static void requireWellFormed(String value) {
        Legibility.requireOneWellFormedLine(value, "Term");
        Legibility.requireNothingConcealed(value, "Term");
        Legibility.requireNothingInvisible(value, "Term");
        Legibility.requireNotEmpty(value, "Term");
        Legibility.requireSpaceDecidesNothing(value, "Term");
        Legibility.requireSomethingVisible(value, "Term");
        Legibility.requireWithinMaximumLength(value, MAXIMUM_LENGTH, "Term");
    }
}
