package org.lilradish.lite.app.library;

import org.libprunus.core.error.ApiErrorException;
import org.lilradish.lite.domain.failure.RefusalCode;
import tools.jackson.databind.JsonNode;

/**
 * The revision of a draft a page read, which every body writing the draft sends back beside what it writes;
 * anything no revision could be is refused here, before the store is asked.
 */
final class SeenRevision {

    private static final String MEMBER = "revision";

    private SeenRevision() {}

    static int in(JsonNode body, String refusedAs) {
        JsonNode sent = body.path(MEMBER);
        if (!sent.isInt() || sent.intValue() < 1) {
            throw new ApiErrorException(RefusalCode.BODY_UNUSABLE, refusedAs);
        }
        return sent.intValue();
    }
}
