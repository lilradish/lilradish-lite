package org.lilradish.lite.domain.people;

import static java.util.Objects.requireNonNull;

import org.lilradish.lite.domain.text.Legibility;

/**
 * What a reader typed to find somebody, in the directory or in the pool, held exactly as it was typed.
 * It is tried whole against user numbers and in part against names, so no character in it means
 * anything but itself.
 *
 * <p>Something has to have been typed, and spaces alone are not something. Found in part, they would
 * match every name that has a space in it, which lists what is searched rather than searching it.
 *
 * <p>Otherwise refused only as a filter over the pool is, for the reason {@link Legibility} gives and
 * by its rule for text that is compared and never shown.
 */
public record PeopleSearch(String text) {

    private static final int MAXIMUM_LENGTH = 256;

    public PeopleSearch {
        requireNonNull(text, "PeopleSearch must not be null");
        Legibility.requireOneWellFormedLine(text, "PeopleSearch");
        Legibility.requireWithinMaximumLength(text, MAXIMUM_LENGTH, "PeopleSearch");
        // Empty passes the match below vacuously, which is the refusal it is owed.
        if (text.codePoints().allMatch(codePoint -> Character.getType(codePoint) == Character.SPACE_SEPARATOR)) {
            throw new IllegalArgumentException("PeopleSearch must hold something other than space");
        }
    }
}
