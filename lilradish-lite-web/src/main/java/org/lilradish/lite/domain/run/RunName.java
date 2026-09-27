package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import org.lilradish.lite.domain.text.Legibility;

/**
 * What the person starting a run called it. Two runs may share one, so spacing is left as typed: nothing is
 * told apart by it.
 */
public record RunName(String value) {

    /** Level with the column's own bound, and counted the way that check counts. */
    private static final int MAXIMUM_LENGTH = 128;

    public static final String UNUSABLE =
            "A run's name is one to " + MAXIMUM_LENGTH + " characters on one line, with something in it that shows.";

    public RunName {
        requireNonNull(value, "RunName must not be null");
        Legibility.requireOneWellFormedLine(value, "RunName");
        Legibility.requireNotEmpty(value, "RunName");
        Legibility.requireSomethingVisible(value, "RunName");
        Legibility.requireWithinMaximumLength(value, MAXIMUM_LENGTH, "RunName");
    }
}
