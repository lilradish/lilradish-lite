package com.lilradish.lite.domain.inference;

/** Whole percent, matching the stored floor's scale so comparison needs no rounding. */
public record Confidence(int percent) {

    static final int HIGHEST = 100;

    public Confidence {
        if (percent < 0 || percent > HIGHEST) {
            throw new IllegalArgumentException(
                    "Confidence is a whole percent from 0 to " + HIGHEST + ", not " + percent);
        }
    }
}
