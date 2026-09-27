package org.lilradish.lite.domain.identity;

import static java.util.Objects.requireNonNull;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The short name people cite a group by, outside this system as well as in it. What is written down out
 * there cannot be rewritten, so a key is never changed once given; and nothing in here points at a group
 * by it, a {@link GroupId} doing that.
 *
 * <p>Held in capitals, which makes one key one spelling however it was typed, and uniqueness exact
 * wherever it is kept. The constructor refuses and only {@link #typed} folds, for the reason
 * {@link GroupName} gives.
 */
public record GroupKey(String value) {

    private static final Pattern HELD = Pattern.compile("[A-Z]{2,16}");

    private static final Pattern TYPED = Pattern.compile("[A-Za-z]{2,16}");

    public GroupKey {
        requireNonNull(value, "GroupKey must not be null");
        if (!HELD.matcher(value).matches()) {
            throw new IllegalArgumentException("GroupKey must be two to sixteen capital English letters");
        }
    }

    /**
     * Capitalised once it is known to be English letters alone: capitalising first would let a letter
     * outside them pass as the capital it folds to.
     */
    public static GroupKey typed(String typed) {
        requireNonNull(typed, "GroupKey typed must not be null");
        if (!TYPED.matcher(typed).matches()) {
            throw new IllegalArgumentException("GroupKey must be typed as two to sixteen English letters");
        }
        return new GroupKey(typed.toUpperCase(Locale.ROOT));
    }
}
