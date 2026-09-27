package org.lilradish.lite.app.listing;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;
import org.libprunus.core.error.ApiErrorException;
import org.lilradish.lite.domain.failure.RefusalCode;
import org.lilradish.lite.domain.people.PeopleSearch;
import org.lilradish.lite.web.QueryParameters;

/**
 * What a reader sent to search for somebody, read as {@link QueryParameters} reads it: a search sent
 * twice is refused, and so is a parameter a search does not take. It offers nobody until something is
 * typed, so a search sent without one is refused too.
 *
 * <p>Static, so that nothing typed is ever an argument a woven method logs.
 */
public final class SearchParameters {

    /** Fixed rather than asked: a size a caller could choose is a query cost a caller could choose. */
    public static final int MOST_FOUND = 20;

    private static final String SEARCH = "search";

    private static final Set<String> TAKEN = Set.of(SEARCH);

    private static final String PARAMETER_REFUSED = "This search does not take that parameter.";

    private static final String SEARCH_REFUSED = "A search takes part of a name or a whole user number, on one line.";

    private SearchParameters() {}

    public static PeopleSearch searchSent(HttpServletRequest request) {
        Map<String, String[]> sent = QueryParameters.sent(request, TAKEN, PARAMETER_REFUSED);
        String typed = QueryParameters.soleValue(sent, SEARCH, RefusalCode.PEOPLE_SEARCH_UNUSABLE, SEARCH_REFUSED);
        if (typed == null) {
            throw new ApiErrorException(RefusalCode.PEOPLE_SEARCH_UNUSABLE, SEARCH_REFUSED);
        }
        try {
            return new PeopleSearch(typed);
        } catch (IllegalArgumentException refused) {
            throw new ApiErrorException(RefusalCode.PEOPLE_SEARCH_UNUSABLE, SEARCH_REFUSED, refused);
        }
    }
}
