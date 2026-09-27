package org.lilradish.lite.app.listing;

import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.error.ApiErrorException;
import org.lilradish.lite.domain.failure.RefusalCode;
import org.lilradish.lite.domain.listing.ListColumn;
import org.lilradish.lite.domain.listing.ListCursor;
import org.lilradish.lite.domain.listing.ListFilter;
import org.lilradish.lite.domain.listing.ListOrder;
import org.lilradish.lite.domain.listing.ListPosition;
import org.lilradish.lite.domain.listing.ListQuery;
import org.lilradish.lite.domain.listing.ListShape;
import org.lilradish.lite.domain.people.PersonName;
import org.lilradish.lite.web.QueryParameters;

/**
 * What a reader sent for a list, read as the query it asks and the position it resumes from, or refused
 * with the code saying which part could not be read. Each code is answered with one fixed sentence,
 * whatever arrived, so nothing sent is handed back.
 *
 * <p>Static, so that neither a filter nor a cursor is ever an argument a woven method logs.
 */
public final class ListParameters {

    private static final String SORT = "sort";

    private static final String FILTER = "filter";

    private static final String CURSOR = "cursor";

    /** The parameters a list takes, to be read off the request as {@link QueryParameters} reads them. */
    public static final Set<String> TAKEN = Set.of(SORT, FILTER, CURSOR);

    public static final String PARAMETER_REFUSED = "This list does not take that parameter.";

    private static final String SORT_REFUSED = "This list is sorted by one sortable column at a time.";

    private static final String FILTER_REFUSED =
            "That filter is too long, or holds a character that cannot be searched for.";

    private static final String CURSOR_REFUSED = "That position does not belong to this query of the list.";

    private ListParameters() {}

    /** The order is read in full before the filter is read at all, so the first to fail is what refuses. */
    public static <C extends Enum<C> & ListColumn> ListQuery<C> query(
            ListShape<C> shape, String scope, Map<String, String[]> sent) {
        ListOrder<C> order = order(shape, sent);
        return new ListQuery<>(scope, order, filter(sent));
    }

    /**
     * For a list whose filter matches names: spaced as every name held is, before a cursor is bound to
     * it, so whatever a keyboard types for a space finds the name it shows.
     */
    public static <C extends Enum<C> & ListColumn> ListQuery<C> spacedAsNames(ListQuery<C> query) {
        ListFilter filter = query.filter();
        if (filter == null) {
            return query;
        }
        String spaced = PersonName.spacedAsNamesAre(filter.text());
        return spaced.equals(filter.text())
                ? query
                : new ListQuery<>(query.scope(), query.order(), new ListFilter(spaced));
    }

    /** Absent where no cursor was sent, which is a reading from the start. */
    public static <C extends Enum<C> & ListColumn> @Nullable ListPosition after(
            ListShape<C> shape, ListQuery<C> query, Map<String, String[]> sent) {
        String cursor = QueryParameters.soleValue(sent, CURSOR, RefusalCode.LIST_CURSOR_UNUSABLE, CURSOR_REFUSED);
        if (cursor == null) {
            return null;
        }
        try {
            return ListCursor.resume(shape, query, cursor);
        } catch (IllegalArgumentException refused) {
            throw new ApiErrorException(RefusalCode.LIST_CURSOR_UNUSABLE, CURSOR_REFUSED, refused);
        }
    }

    private static <C extends Enum<C> & ListColumn> ListOrder<C> order(ListShape<C> shape, Map<String, String[]> sent) {
        String requested = QueryParameters.soleValue(sent, SORT, RefusalCode.LIST_SORT_UNUSABLE, SORT_REFUSED);
        try {
            return shape.order(requested);
        } catch (IllegalArgumentException refused) {
            throw new ApiErrorException(RefusalCode.LIST_SORT_UNUSABLE, SORT_REFUSED, refused);
        }
    }

    private static @Nullable ListFilter filter(Map<String, String[]> sent) {
        String typed = QueryParameters.soleValue(sent, FILTER, RefusalCode.LIST_FILTER_UNUSABLE, FILTER_REFUSED);
        try {
            return ListFilter.parse(typed);
        } catch (IllegalArgumentException refused) {
            throw new ApiErrorException(RefusalCode.LIST_FILTER_UNUSABLE, FILTER_REFUSED, refused);
        }
    }
}
