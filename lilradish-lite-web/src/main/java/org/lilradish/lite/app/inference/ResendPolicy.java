package org.lilradish.lite.app.inference;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How a call turned away is sent again: each wait twice the last, from the first up to the longest,
 * never shorter than the endpoint asked and never longer than the longest. Nothing here defaults: the
 * deployment says how many times.
 *
 * @param times how many resends one call may make; none ends a call at its first turnaway
 */
@ConfigurationProperties(prefix = "lilradish.model-calls.resend", ignoreUnknownFields = false)
record ResendPolicy(Integer times, Duration firstWait, Duration longestWait) {

    private static final String PREFIX = "lilradish.model-calls.resend";

    ResendPolicy {
        if (times == null) {
            throw new NullPointerException(PREFIX + ".times must be set");
        }
        if (times < 0) {
            throw new IllegalArgumentException(PREFIX + ".times must not be negative: " + times);
        }
        if (firstWait == null) {
            throw new NullPointerException(PREFIX + ".first-wait must be set");
        }
        if (!firstWait.isPositive()) {
            throw new IllegalArgumentException(PREFIX + ".first-wait must be positive: " + firstWait);
        }
        if (longestWait == null) {
            throw new NullPointerException(PREFIX + ".longest-wait must be set");
        }
        if (longestWait.compareTo(firstWait) < 0) {
            throw new IllegalArgumentException(
                    PREFIX + ".longest-wait must not be shorter than first-wait: " + longestWait + " < " + firstWait);
        }
    }

    /** The wait before resend number {@code resend}, counted from one. */
    Duration waitBefore(int resend, @Nullable Duration asked) {
        if (resend < 1) {
            throw new IllegalArgumentException("ResendPolicy counts resends from one: " + resend);
        }
        Duration doubled = firstWait;
        for (int step = 1; step < resend && doubled.compareTo(longestWait) < 0; step++) {
            // Compared as a difference: doubling a wait past half the largest duration would overflow.
            doubled = doubled.compareTo(longestWait.minus(doubled)) >= 0 ? longestWait : doubled.multipliedBy(2);
        }
        Duration wanted = asked != null && asked.compareTo(doubled) > 0 ? asked : doubled;
        return wanted.compareTo(longestWait) > 0 ? longestWait : wanted;
    }
}
