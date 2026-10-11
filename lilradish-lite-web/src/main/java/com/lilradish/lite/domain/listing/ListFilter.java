package com.lilradish.lite.domain.listing;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.text.Legibility;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.log.annotation.DoNotLog;

/**
 * Literal text, kept exactly as typed and excluded from profile-controlled rendering because it is user input.
 * Refuses rather than tidies, by {@link Legibility}'s comparison-only rule; spacing may be incomplete while typing.
 *
 * <p>The bound limits matching cost, not matchability: folding can make a matching value longer than its stored form.
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
