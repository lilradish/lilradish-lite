package org.lilradish.lite.domain.inference;

import static java.util.Objects.requireNonNull;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * How sure a model was of what it produced, in the resolution it is stored at: a fraction of one,
 * to four places — the scale the column is declared at, and the scale every instance is normalised
 * to, so that one confidence has one spelling. Anything finer is refused rather than quietly
 * truncated on the way to the column.
 *
 * <p>Read as a whole percent, which is the unit a declared floor is written in, and rounded down.
 * Not a display preference: a floor is a whole percent, so flooring makes {@code percent()} clear
 * it exactly when the fraction does. Rounding half up would break that equivalence in one
 * direction — 0.8950 would read as 90 and pass a floor of 90 the fraction does not reach.
 */
public record Confidence(BigDecimal fraction) {

    private static final int SCALE = 4;

    public Confidence {
        requireNonNull(fraction, "Confidence fraction must not be null");
        if (fraction.signum() < 0 || fraction.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Confidence fraction must be between zero and one: " + fraction);
        }
        if (fraction.stripTrailingZeros().scale() > SCALE) {
            throw new IllegalArgumentException("Confidence fraction is kept to " + SCALE + " places, not " + fraction);
        }
        // UNNECESSARY never throws after that guard, and says so: no rounding belongs here.
        fraction = fraction.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    public int percent() {
        return fraction.movePointRight(2).setScale(0, RoundingMode.FLOOR).intValueExact();
    }
}
