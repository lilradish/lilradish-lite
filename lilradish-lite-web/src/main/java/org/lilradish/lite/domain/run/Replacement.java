package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import org.libprunus.core.log.annotation.DoNotLog;

/**
 * What a person put in place of what a step produced, and which attempt they put it in place of.
 * Recorded on the attempt carrying the new value, which is the one that needs explaining.
 *
 * <p>The reason is required. A replacement is drawn on later as a lesson, and a lesson whose body
 * is that no reason was given teaches nothing; it is also the only account of why the stored answer
 * is not the one the step produced.
 */
public record Replacement(int fromAttempt, @DoNotLog String reason) {

    public Replacement {
        if (fromAttempt < 1) {
            throw new IllegalArgumentException("Replacement fromAttempt must be positive: " + fromAttempt);
        }
        requireNonNull(reason, "Replacement reason must not be null");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("Replacement reason must not be blank");
        }
    }
}
