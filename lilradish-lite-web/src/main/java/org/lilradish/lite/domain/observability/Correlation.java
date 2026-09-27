package org.lilradish.lite.domain.observability;

import static java.util.Objects.requireNonNull;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The identifier a trace and a run record are joined by. Carried on the thread: a run completes
 * within the session that started it, and a job with no inbound request mints one rather than
 * leaving its spans uncorrelated.
 *
 * <p>Token characters only, refused rather than sanitised: anything else lets the value end a log line
 * or pose as whatever is printed beside it.
 */
public final class Correlation {

    public static final String HEADER = "X-Correlation-Id";

    /** A bound of this class's own: one identifier must not crowd a log line. */
    private static final int MAX_LENGTH = 64;

    private static final String TOKEN_SYMBOLS = "!#$%&'*+-.^_`|~";

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

    public static void set(String correlationId) {
        requireNonNull(correlationId, "Correlation id must not be null");
        if (correlationId.isEmpty() || correlationId.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Correlation id must be 1 to " + MAX_LENGTH + " characters");
        }
        for (int index = 0; index < correlationId.length(); index++) {
            if (!isTokenCharacter(correlationId.charAt(index))) {
                throw new IllegalArgumentException("Correlation id must hold token characters only");
            }
        }
        CURRENT.set(correlationId);
    }

    private static boolean isTokenCharacter(char character) {
        return (character >= 'a' && character <= 'z')
                || (character >= 'A' && character <= 'Z')
                || (character >= '0' && character <= '9')
                || TOKEN_SYMBOLS.indexOf(character) >= 0;
    }

    /** A pooled thread outlives the request it served; an uncleared identifier would follow it. */
    public static void clear() {
        CURRENT.remove();
    }
}
