package org.lilradish.lite.web;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import org.libprunus.core.error.ApiErrorException;
import org.lilradish.lite.domain.failure.RefusalCode;

/**
 * What a change refuses of a request beyond its address, said in one set of sentences for every change: a
 * parameter, which no change takes, and a body, where the change takes none. Static, for the reason {@link
 * QueryParameters} gives.
 */
public final class ChangeRequests {

    private static final String PARAMETER_REFUSED = "This change takes no parameter.";

    private static final String NO_BODY_TAKEN = "This change takes no body.";

    private ChangeRequests() {}

    public static void requireNoParameter(HttpServletRequest request) {
        QueryParameters.requireNone(request, PARAMETER_REFUSED);
    }

    /** For a change asking nothing but its address: no parameter, then no body. */
    public static void requireNothingSent(HttpServletRequest request) throws IOException {
        requireNoParameter(request);
        try (InputStream body = request.getInputStream()) {
            if (body.read() != -1) {
                throw new ApiErrorException(RefusalCode.BODY_UNUSABLE, NO_BODY_TAKEN);
            }
        }
    }
}
