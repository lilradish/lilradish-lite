package com.lilradish.lite.domain.identity;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.text.Legibility;

/**
 * The identity provider owns the format and case; folding here could irreversibly merge distinct people.
 * Internal references use {@link SubjectId}, so no narrower identifier alphabet is needed here.
 *
 * <p>{@link Legibility} still matters: this value uniquely reconnects a returning person and is quoted in act messages,
 * so visually indistinguishable spellings could identify different people without a reader being able to tell.
 */
public record UserId(String value) {

    /** Level with the column's own bound, and counted the way that check counts. */
    private static final int MAXIMUM_LENGTH = 256;

    public UserId {
        requireNonNull(value, "UserId must not be null");
        Legibility.requireVisibleAndWellFormed(value, "UserId");
        Legibility.requireNotEmpty(value, "UserId");
        Legibility.requireSpaceDecidesNothing(value, "UserId");
        Legibility.requireWithinMaximumLength(value, MAXIMUM_LENGTH, "UserId");
    }
}
