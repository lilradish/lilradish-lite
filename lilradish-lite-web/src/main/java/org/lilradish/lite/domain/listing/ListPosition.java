package org.lilradish.lite.domain.listing;

import static java.util.Objects.requireNonNull;

import org.jspecify.annotations.Nullable;

/**
 * The row a page ended on, as far as the order it was read in can tell one row from the next. Each
 * value is held as its column's {@link ListValueKind} says.
 *
 * <p>Sort values are held and not only the row's identity, so a position still places a reader after
 * its row has gone. A value that changes between two pages can repeat or skip that row, which reading
 * by position rather than by count cannot help.
 *
 * @param sortValue what the row holds in the column the order sorts by, absent where it holds nothing,
 *     which is a place in the order of its own; the tie-breaking value itself where the order is by that
 *     column
 * @param tiebreakValue what the row holds in the column that breaks every tie
 */
public record ListPosition(@Nullable Object sortValue, Object tiebreakValue) {

    public ListPosition {
        requireNonNull(tiebreakValue, "ListPosition tiebreakValue must not be null");
    }
}
