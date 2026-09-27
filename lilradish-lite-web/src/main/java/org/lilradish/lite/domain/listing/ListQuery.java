package org.lilradish.lite.domain.listing;

import static java.util.Objects.requireNonNull;

import org.jspecify.annotations.Nullable;

/**
 * One way of asking for a list: what it is read within, an order, and a filter or none. A cursor is
 * honoured only by the query that minted it.
 *
 * @param scope what the list is read within, spelt as the list spells it, and empty for a list read
 *     whole; it binds cursors, and narrows only a list whose statement is written to read within it
 */
public record ListQuery<C extends Enum<C> & ListColumn>(
        String scope, ListOrder<C> order, @Nullable ListFilter filter) {

    public ListQuery {
        requireNonNull(scope, "ListQuery scope must not be null");
        requireNonNull(order, "ListQuery order must not be null");
    }
}
