package com.lilradish.lite.app.codestep;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Lazy;

/**
 * Declared maximum code-step duration, used to size orderly shutdown waits; no run is timed out by it.
 */
// Never lazy, even where lazy initialisation is switched on: a bound it cannot hold must stop the start.
@Lazy(false)
@ConfigurationProperties(prefix = "lilradish.code-steps", ignoreUnknownFields = false)
public record CodeStepDeployment(Duration longestRun) {

    private static final String PREFIX = "lilradish.code-steps";

    /* A day: far past any shutdown worth waiting for, and far inside what a wait counted in seconds or millis holds. */
    private static final Duration LONGEST_BOUND = Duration.ofHours(24);

    public CodeStepDeployment {
        if (longestRun == null) {
            throw new NullPointerException(PREFIX + ".longest-run must be set");
        }
        if (!longestRun.isPositive()) {
            throw new IllegalArgumentException(PREFIX + ".longest-run must be positive: " + longestRun);
        }
        if (longestRun.compareTo(LONGEST_BOUND) > 0) {
            throw new IllegalArgumentException(
                    PREFIX + ".longest-run must be at most " + LONGEST_BOUND + ": " + longestRun);
        }
    }
}
