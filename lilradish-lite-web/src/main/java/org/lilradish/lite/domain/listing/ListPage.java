package org.lilradish.lite.domain.listing;

import static java.util.Objects.requireNonNull;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One page of a list, and where the next begins if anything is left beyond it. Whether anything follows
 * is learnt by fetching one row beyond the page, never by assuming that a full page has a successor.
 *
 * @param next where the page after this one begins, absent exactly where nothing was fetched beyond it
 */
public record ListPage<R>(List<R> rows, @Nullable ListPosition next) {

    /** Fixed rather than asked: a size a caller could choose is a query cost a caller could choose. */
    public static final int SIZE = 50;

    public ListPage {
        requireNonNull(rows, "ListPage rows must not be null");
    }

    /**
     * The first {@link #SIZE} of what was fetched, which is at most one beyond them, and the position of
     * the last of those where the one beyond was fetched.
     */
    public static <C extends Enum<C> & ListColumn, R extends ListRow<C>> ListPage<R> of(
            List<R> fetched, ListShape<C> shape, ListOrder<C> order) {
        requireNonNull(fetched, "ListPage fetched rows must not be null");
        requireNonNull(shape, "ListPage shape must not be null");
        requireNonNull(order, "ListPage order must not be null");
        if (fetched.size() <= SIZE) {
            return new ListPage<>(List.copyOf(fetched), null);
        }
        List<R> shown = List.copyOf(fetched.subList(0, SIZE));
        R last = shown.getLast();
        Object tiebreakValue = last.valueIn(shape.tiebreak());
        if (tiebreakValue == null) {
            throw new IllegalStateException("ListPage row holds nothing in the column that breaks every tie");
        }
        return new ListPage<>(shown, new ListPosition(last.valueIn(order.column()), tiebreakValue));
    }
}
