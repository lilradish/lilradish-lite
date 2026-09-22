package org.lilradish.lite.domain.identity;

import static java.util.Objects.requireNonNull;

import org.lilradish.lite.domain.text.Legibility;

/**
 * What a group is called, which is the only thing about it a person ever sees. Work is filed under a
 * {@link GroupId}, so correcting a name unfiles nothing — but two groups a reader cannot tell apart
 * are two groups work is filed into by guess, and the store keeps this unique for that reason.
 *
 * <p>What is tightened is legibility, argued once in {@link Legibility} and not repeated here. A name
 * is refused rather than tidied: this constructor runs both when a person types a name and when a row
 * is rebuilt out of the store, so tidying here would fold two stored rows into one value. Tidying
 * what somebody typed belongs at the entry point that took it.
 *
 * <p>Case is carried as given and never folded, matching a store whose uniqueness is exact. Folding
 * would make one group out of two that a deployment says are two, and that is the direction that
 * cannot be undone: the two can be told apart again only by whoever still remembers there were two.
 */
public record GroupName(String value) {

    /** Level with the column's own bound, and counted the way that check counts. */
    private static final int MAXIMUM_LENGTH = 128;

    public GroupName {
        requireNonNull(value, "GroupName must not be null");
        Legibility.requireVisibleAndWellFormed(value, "GroupName");
        Legibility.requireNotEmpty(value, "GroupName");
        Legibility.requireSpaceDecidesNothing(value, "GroupName");
        Legibility.requireWithinMaximumLength(value, MAXIMUM_LENGTH, "GroupName");
    }
}
