package com.lilradish.lite.domain.people;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.text.Legibility;

/**
 * Literal text kept as typed: whole user-number matches and partial name matches share this input.
 * Spaces alone are refused because partial matching would list every name containing a space.
 * Other checks follow {@link Legibility}'s comparison-only filter rule.
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
