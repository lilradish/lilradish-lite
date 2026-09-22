package org.lilradish.lite.domain.observability;

import static java.util.Objects.requireNonNull;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The identifier a trace and a run record are joined by. Carried on the thread: a run completes
 * within the session that started it, and a job with no inbound request mints one rather than
 * leaving its spans uncorrelated.
 *
 * <p>Two characters are refused rather than sanitised, and neither is a matter of taste. A control
 * character ends a log line and lets a second one be written in its place. A double quote closes a
 * field in a quoted layout and opens whatever it names next.
 */
public final class Correlation {

    public static final String HEADER = "X-Correlation-Id";

    /** A bound of this class's own: one identifier must not crowd a log line. */
    private static final int MAX_LENGTH = 64;

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private Correlation() {}

    /** Null when nothing has set one — a reader must not mint an identifier as a side effect. */
    public static @Nullable String current() {
        return CURRENT.get();
    }

    public static String currentOrNew() {
        String current = CURRENT.get();
        if (current == null) {
            current = UUID.randomUUID().toString();
            CURRENT.set(current);
        }
        return current;
    }

    /**
     * Refuses rather than sanitises: an inbound value is a caller's to get right, and one that could
     * write a log line of its own has to be rejected and replaced, not quietly trimmed.
     */
    public static void set(String correlationId) {
        requireNonNull(correlationId, "Correlation id must not be null");
        if (correlationId.isBlank() || correlationId.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Correlation id must be 1 to " + MAX_LENGTH + " characters");
        }
        for (int index = 0; index < correlationId.length(); index++) {
            char character = correlationId.charAt(index);
            if (character < ' ' || character == 0x7F) {
                throw new IllegalArgumentException("Correlation id must not carry a control character");
            }
            if (character == '"') {
                throw new IllegalArgumentException("Correlation id must not carry a double quote");
            }
        }
        CURRENT.set(correlationId);
    }

    /** A pooled thread outlives the request it served; an uncleared identifier would follow it. */
    public static void clear() {
        CURRENT.remove();
    }
}
