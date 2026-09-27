package org.lilradish.lite.domain.listing;

import static java.util.Objects.requireNonNull;

/**
 * One column and a direction, which is the whole of an order: a table sorts by one column at a time,
 * so a list of columns is refused rather than read as a tie-break the reader never chose.
 *
 * <p>Spelt as a reader asks for it — the column's published name, led by a hyphen for descending.
 */
public record ListOrder<C extends Enum<C> & ListColumn>(C column, boolean descending) {

    static final String DESCENDING = "-";

    public ListOrder {
        requireNonNull(column, "ListOrder column must not be null");
    }

    public String published() {
        return descending ? DESCENDING + column.published() : column.published();
    }
}
