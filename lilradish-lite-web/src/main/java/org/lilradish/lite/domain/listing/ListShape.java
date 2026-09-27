package org.lilradish.lite.domain.listing;

import static java.util.Objects.requireNonNull;

import org.jspecify.annotations.Nullable;

/**
 * What makes a list the list it is, as far as reading it goes: the name every cursor it mints is bound
 * to, the column that breaks every tie, and the order it opens on.
 *
 * <p>Every tie is broken by that column ascending in either direction, as a stable sort over the order
 * the list opens on would leave it, so a page boundary neither repeats nor skips a row. That holds only
 * of a column no row leaves empty and no two rows share within whatever the list is read within.
 *
 * <p>Two lists named alike share their cursors, so no two may be.
 */
public final class ListShape<C extends Enum<C> & ListColumn> {

    private final String name;

    private final C tiebreak;

    private final ListOrder<C> opening;

    private final C[] columns;

    public ListShape(String name, C tiebreak, ListOrder<C> opening) {
        requireNonNull(name, "ListShape name must not be null");
        requireNonNull(tiebreak, "ListShape tiebreak must not be null");
        requireNonNull(opening, "ListShape opening order must not be null");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("ListShape name must not be empty");
        }
        if (tiebreak.kind().nullable()) {
            throw new IllegalArgumentException("ListShape tiebreak must be a column no row leaves empty");
        }
        this.name = name;
        this.tiebreak = tiebreak;
        this.opening = opening;
        this.columns = tiebreak.getDeclaringClass().getEnumConstants();
    }

    public String name() {
        return name;
    }

    public C tiebreak() {
        return tiebreak;
    }

    /** Nothing asked is the opening order; something asked and not understood is refused, never defaulted. */
    public ListOrder<C> order(@Nullable String requested) {
        if (requested == null) {
            return opening;
        }
        boolean descending = requested.startsWith(ListOrder.DESCENDING);
        String named = descending ? requested.substring(ListOrder.DESCENDING.length()) : requested;
        for (C column : columns) {
            if (column.published().equals(named)) {
                return new ListOrder<>(column, descending);
            }
        }
        throw new IllegalArgumentException("ListOrder names no single column the list is sorted by");
    }
}
