package org.lilradish.lite.domain.inference;

/**
 * How sure a model was of a value it gave back, as a whole percent: the scale a declared floor is written
 * in and the store keeps, so a confidence and a floor compare as they are, with nothing rounded between.
 */
public record Confidence(int percent) {

    static final int HIGHEST = 100;

    public Confidence {
        if (percent < 0 || percent > HIGHEST) {
            throw new IllegalArgumentException(
                    "Confidence is a whole percent from 0 to " + HIGHEST + ", not " + percent);
        }
    }
}
