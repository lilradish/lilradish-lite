package org.lilradish.lite.domain.model;

import org.libprunus.core.log.annotation.DoNotLog;

/**
 * The two texts one call sends, measured once, as they are taken: a character is a code point, the
 * unit every stored text is bounded in, so the counts of parts cut apart between characters add up
 * exactly to the count of the whole. Only {@link #measure} makes one, so a count never stands beside
 * a text it was not taken from.
 */
public final class SentText {

    @DoNotLog
    private final String system;

    @DoNotLog
    private final String user;

    private final long characters;

    private SentText(String system, String user, long characters) {
        this.system = system;
        this.user = user;
        this.characters = characters;
    }

    /** An unpaired surrogate is refused: encoded, it becomes a character nobody wrote. */
    public static SentText measure(String system, String user) {
        long characters = codePoints(system, "system") + codePoints(user, "user");
        return new SentText(system, user, characters);
    }

    public String system() {
        return system;
    }

    public String user() {
        return user;
    }

    public long characters() {
        return characters;
    }

    private static long codePoints(String text, String part) {
        if (text == null) {
            throw new NullPointerException("SentText " + part + " must not be null");
        }
        int length = text.length();
        long pairs = 0;
        for (int index = 0; index < length; index++) {
            char character = text.charAt(index);
            if (!Character.isSurrogate(character)) {
                continue;
            }
            if (Character.isHighSurrogate(character)
                    && index + 1 < length
                    && Character.isLowSurrogate(text.charAt(index + 1))) {
                index++;
                pairs++;
            } else {
                throw new IllegalArgumentException("SentText " + part + " holds an unpaired surrogate");
            }
        }
        return length - pairs;
    }
}
