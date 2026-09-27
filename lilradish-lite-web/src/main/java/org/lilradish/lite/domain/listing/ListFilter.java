package org.lilradish.lite.domain.listing;

import static java.util.Objects.requireNonNull;

import org.jspecify.annotations.Nullable;
import org.libprunus.core.log.annotation.DoNotLog;
import org.lilradish.lite.domain.text.Legibility;

/**
 * What a reader typed to narrow a list, held exactly as it was typed. It is matched as text, so no
 * character in it means anything but itself. Kept out of every rendering a profile controls, being
 * whatever somebody typed.
 *
 * <p>Refused rather than tidied, for the reason {@link Legibility} gives, and only by its rule for
 * text that is compared and never shown. Spacing is left alone, a space at either end or two in a row
 * being what somebody holds mid-name while the rows narrow under them.
 *
 * <p>The bound is on what matching costs and says nothing about what can be matched: folding lengthens
 * some text, so a filter longer than every held value can still be found in one.
 */
public record ListFilter(@DoNotLog String text) {

    private static final int MAXIMUM_LENGTH = 256;

    public ListFilter {
        requireNonNull(text, "ListFilter must not be null");
        Legibility.requireOneWellFormedLine(text, "ListFilter");
        Legibility.requireNotEmpty(text, "ListFilter");
        Legibility.requireWithinMaximumLength(text, MAXIMUM_LENGTH, "ListFilter");
    }

    /** Nothing typed narrows nothing, so an empty filter is no filter rather than a refusal. */
    public static @Nullable ListFilter parse(@Nullable String typed) {
        return typed == null || typed.isEmpty() ? null : new ListFilter(typed);
    }
}
