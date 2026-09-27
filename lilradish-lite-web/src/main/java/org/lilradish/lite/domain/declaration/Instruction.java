package org.lilradish.lite.domain.declaration;

import static java.util.Objects.requireNonNull;

import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.text.Legibility;
import org.lilradish.lite.domain.text.ProseRefusal;

/**
 * What whoever answers a question is told to do; what it takes goes beside it, never inside it. A line ends
 * in a line feed alone, which is what a page's text box hands over; any other ending is refused, not rewritten.
 */
public record Instruction(String value) {

    /** Level with the column's own bound, and counted the way that check counts. */
    private static final int MAXIMUM_LENGTH = 8192;

    public Instruction {
        requireNonNull(value, "Instruction must not be null");
        requireWellFormed(value);
    }

    public static @Nullable ProseRefusal refusalOf(String said) {
        requireNonNull(said, "Instruction text judged must not be null");
        return ProseRefusal.of(said, true, Instruction::requireWellFormed);
    }

    private static void requireWellFormed(String value) {
        Legibility.requireWellFormedProse(value, "Instruction");
        Legibility.requireSomethingVisibleInProse(value, "Instruction");
        Legibility.requireWithinMaximumLength(value, MAXIMUM_LENGTH, "Instruction");
    }
}
