package org.lilradish.lite.app.inference;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;

/**
 * How long a turnaway asked to be waited out, read in the order the vendor's own client reads it: its
 * millisecond header, then the standard header as seconds, then as a date. The first readable one is
 * taken; an unreadable one is passed over for the next, and a date already past asks for nothing.
 */
final class RetryAfter {

    static final String MILLISECONDS = "retry-after-ms";

    private static final Pattern DECIMAL_MILLISECONDS = Pattern.compile("[0-9]+(?:\\.[0-9]+)?");

    private static final Pattern DELAY_SECONDS = Pattern.compile("[0-9]+");

    private static final BigDecimal LONGEST_IN_NANOSECONDS = BigDecimal.valueOf(Long.MAX_VALUE);

    private RetryAfter() {}

    static @Nullable Duration asked(HttpHeaders headers, Instant now) {
        String milliseconds = headers.getFirst(MILLISECONDS);
        if (milliseconds != null && DECIMAL_MILLISECONDS.matcher(milliseconds).matches()) {
            return nanoseconds(new BigDecimal(milliseconds).movePointRight(6));
        }
        String retryAfter = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (retryAfter == null) {
            return null;
        }
        if (DELAY_SECONDS.matcher(retryAfter).matches()) {
            return nanoseconds(new BigDecimal(retryAfter).movePointRight(9));
        }
        // Read as the client library reads any date: an asctime day of one digit asks for no wait, and a two-digit
        // year reads as 20yy with no fifty-year rule, a wait the longest wait still bounds.
        ZonedDateTime until;
        try {
            until = headers.getFirstZonedDateTime(HttpHeaders.RETRY_AFTER);
        } catch (IllegalArgumentException unreadable) {
            return null;
        }
        return until != null && until.toInstant().isAfter(now) ? Duration.between(now, until.toInstant()) : null;
    }

    // Saturated rather than refused: a wait past what a duration holds is capped at the longest anyway.
    private static Duration nanoseconds(BigDecimal nanoseconds) {
        BigDecimal whole = nanoseconds.setScale(0, RoundingMode.CEILING);
        return Duration.ofNanos(whole.compareTo(LONGEST_IN_NANOSECONDS) > 0 ? Long.MAX_VALUE : whole.longValueExact());
    }
}
