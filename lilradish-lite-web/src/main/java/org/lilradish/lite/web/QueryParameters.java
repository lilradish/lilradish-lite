package org.lilradish.lite.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.error.ApiErrorException;
import org.lilradish.lite.domain.failure.RefusalCode;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A query string read off the request rather than bound. Bound to a single value, a parameter sent
 * twice arrives joined by a comma; bound to a list, one sent once is split on the same comma; and a
 * name sent with brackets after it is taken for the name without them. Either way what is read is not
 * what was sent, so a parameter sent twice is refused, and so is one the address does not take.
 *
 * <p>Static, so that nothing sent is ever an argument a woven method logs. Refused on anything but a
 * read: the container's parameters include a form body, and parsing one drains the body.
 */
public final class QueryParameters {

    private QueryParameters() {}

    /** Every name is judged before any value is, so one this does not take is what refuses the request. */
    public static Map<String, String[]> sent(HttpServletRequest request, Set<String> taken, String unknownRefused) {
        String method = request.getMethod();
        if (!HttpMethod.GET.matches(method) && !HttpMethod.HEAD.matches(method)) {
            throw new IllegalStateException("Query parameters are read for a read only");
        }
        Map<String, String[]> sent;
        try {
            sent = request.getParameterMap();
        } catch (IllegalStateException undecodable) {
            // The container's own account of it quotes the bytes it could not decode, so none is kept.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        if (!taken.containsAll(sent.keySet())) {
            throw new ApiErrorException(RefusalCode.PARAMETER_UNKNOWN, unknownRefused);
        }
        return sent;
    }

    /**
     * For an address taking no parameter, a change among them. Read off the query string alone: the
     * container's parameters include a form body, and parsing one would drain a body still to be read.
     */
    public static void requireNone(HttpServletRequest request, String refusedAs) {
        String query = request.getQueryString();
        if (query != null && !query.isEmpty()) {
            throw new ApiErrorException(RefusalCode.PARAMETER_UNKNOWN, refusedAs);
        }
    }

    /** Absent where it was not sent. */
    public static @Nullable String soleValue(
            Map<String, String[]> sent, String name, RefusalCode refusal, String refusedAs) {
        String[] values = sent.get(name);
        if (values == null) {
            return null;
        }
        if (values.length > 1) {
            throw new ApiErrorException(refusal, refusedAs);
        }
        return values[0];
    }
}
