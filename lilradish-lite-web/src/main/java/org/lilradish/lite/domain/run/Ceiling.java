package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import java.util.regex.Pattern;

/**
 * The most a run may spend, counted as what was sent and what came back added together. A run with none holds
 * no ceiling rather than one of nothing.
 */
public record Ceiling(long value) {

    /** The largest whole number a reader's own numbers hold exactly, so none is shown or typed rounded. */
    public static final long LARGEST = 9_007_199_254_740_991L;

    private static final Pattern TYPED = Pattern.compile("[1-9][0-9]{0,15}");

    public Ceiling {
        if (value < 1 || value > LARGEST) {
            throw new IllegalArgumentException("Ceiling must be from one to " + LARGEST + ", but this one is " + value);
        }
    }

    /** Typed as digits alone, leading with none. */
    public static Ceiling typed(String typed) {
        requireNonNull(typed, "Ceiling typed must not be null");
        if (!TYPED.matcher(typed).matches()) {
            throw new IllegalArgumentException("Ceiling must be typed as a whole number in digits, leading with none");
        }
        return new Ceiling(Long.parseLong(typed));
    }
}
