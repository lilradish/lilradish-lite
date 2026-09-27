package org.lilradish.lite.app.inference;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.lilradish.lite.app.inference.EndpointJson.stringIn;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.http.client.ClientHttpResponse;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * What a response that is not an answer said, read no further than a bound: the error the endpoint
 * put in its body where it is one readable in full, else the body as text. No {@code toString} is
 * written, so what was said is never rendered by accident.
 */
final class ErrorBody {

    // Far past what is kept of what was said, so an error written at length is still judged by its code.
    private static final int MOST_READ = 64 * 1024;

    private static final String INSUFFICIENT_QUOTA = "insufficient_quota";

    // An unknown code is busy rather than spent up: resent, not lost.
    private static final Set<String> SPENT_UP_CODES = Set.of(
            INSUFFICIENT_QUOTA,
            "usage_limit_exceeded",
            "credit_balance_exhausted",
            "organization_spend_limit_exceeded",
            "project_spend_limit_exceeded",
            "organization_usage_limit_exceeded");

    private final @Nullable JsonNode error;

    private final String text;

    private ErrorBody(@Nullable JsonNode error, String text) {
        this.error = error;
        this.text = text;
    }

    static ErrorBody read(ClientHttpResponse response) throws IOException {
        byte[] read;
        try (InputStream body = response.getBody()) {
            read = body.readNBytes(MOST_READ + 1);
        }
        if (read.length > MOST_READ) {
            return new ErrorBody(null, new String(read, 0, unsplitEnd(read, MOST_READ), UTF_8));
        }
        JsonNode error = null;
        try {
            JsonNode document = EndpointJson.READER.readTree(read);
            JsonNode candidate = document.get("error");
            error = candidate != null && candidate.isObject() ? candidate : null;
        } catch (JacksonException unreadable) {
            // Not an error the endpoint put in its own shape; the text below stands for it.
        }
        return new ErrorBody(error, new String(read, UTF_8));
    }

    /** The error's message where it gave one, else the text, else nothing. */
    @Nullable
    String said() {
        String message = error == null ? null : stringIn(error, "message");
        if (message != null && !message.isEmpty()) {
            return message;
        }
        return text.isEmpty() ? null : text;
    }

    /** Judged from the error's own words alone, and only where they are strings as the spec types them. */
    boolean spentUp() {
        if (error == null) {
            return false;
        }
        String code = stringIn(error, "code");
        return (code != null && SPENT_UP_CODES.contains(code)) || INSUFFICIENT_QUOTA.equals(stringIn(error, "type"));
    }

    /** Backed off past the continuation bytes a UTF-8 sequence has at most, so the cut decodes no half character. */
    private static int unsplitEnd(byte[] read, int end) {
        int cut = end;
        while (cut > end - 3 && (read[cut] & 0xC0) == 0x80) {
            cut--;
        }
        return cut;
    }
}
