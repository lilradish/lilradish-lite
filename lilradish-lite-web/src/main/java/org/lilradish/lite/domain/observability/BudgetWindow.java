package org.lilradish.lite.domain.observability;

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.time.Instant;

/**
 * Bucketed from the epoch rather than rolled back from the moment of the question: a rolling window
 * would answer differently for two callers asking a millisecond apart, and a warning only means the
 * refusal is coming if both were measured over the same window.
 */
public record BudgetWindow(Duration length) {

    public BudgetWindow {
        requireNonNull(length, "BudgetWindow length must not be null");
        if (length.isZero() || length.isNegative()) {
            throw new IllegalArgumentException("BudgetWindow length must be positive: " + length);
        }
        if (length.getNano() != 0) {
            throw new IllegalArgumentException("BudgetWindow length must be whole seconds: " + length);
        }
    }

    public static BudgetWindow ofSeconds(long seconds) {
        return new BudgetWindow(Duration.ofSeconds(seconds));
    }

    /** Floor division, so a window before the epoch buckets the same way as one after it. */
    public Instant startOf(Instant at) {
        requireNonNull(at, "BudgetWindow instant must not be null");
        long seconds = length.toSeconds();
        return Instant.ofEpochSecond(Math.floorDiv(at.getEpochSecond(), seconds) * seconds);
    }

    /**
     * The exclusive end: a call at exactly this instant belongs to the next window. A predicate
     * bounding a window therefore reads {@code >= startOf} and {@code < endOf}, never {@code <=}.
     */
    public Instant endOf(Instant at) {
        return startOf(at).plus(length);
    }
}
