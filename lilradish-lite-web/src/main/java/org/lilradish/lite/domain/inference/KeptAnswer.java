package org.lilradish.lite.domain.inference;

import static java.util.Objects.requireNonNull;

import org.libprunus.core.log.annotation.DoNotLog;
import org.lilradish.lite.domain.text.CharacterStanding;

/**
 * What is kept of what a model gave back, as evidence: as it came where the store can hold it, and otherwise
 * with each character the store cannot hold replaced and cut at the most it keeps, saying so. Only its
 * factories keep text the store can hold. It records whether the end was cut
 * separately from whether any change was made. What is unwrapped is what came back, never this.
 */
public final class KeptAnswer {

    /** The most characters the store keeps of one answer, each counted as the store counts it: a pair as one. */
    public static final int MOST_KEPT = 8_388_608;

    private static final char REPLACEMENT = 0xFFFD;

    @DoNotLog
    private final String text;

    private final boolean altered;

    private final boolean truncated;

    private KeptAnswer(String text, boolean altered, boolean truncated) {
        this.text = text;
        this.altered = altered;
        this.truncated = truncated;
    }

    public static KeptAnswer of(String cameBack) {
        requireNonNull(cameBack, "KeptAnswer came back must not be null");
        int length = cameBack.length();
        int firstUnkeepable = -1;
        int kept = 0;
        int index = 0;
        while (index < length && kept < MOST_KEPT) {
            int codePoint = cameBack.codePointAt(index);
            if (firstUnkeepable < 0 && unkeepable(codePoint)) {
                firstUnkeepable = index;
            }
            kept++;
            index += Character.charCount(codePoint);
        }
        if (firstUnkeepable < 0) {
            return index < length
                    ? new KeptAnswer(cameBack.substring(0, index), true, true)
                    : new KeptAnswer(cameBack, false, false);
        }
        char[] rebuilt = new char[index];
        cameBack.getChars(0, index, rebuilt, 0);
        // Replaced in place: a character the store cannot hold is one char, and so is what replaces it.
        for (int at = firstUnkeepable; at < index; ) {
            int codePoint = cameBack.codePointAt(at);
            if (unkeepable(codePoint)) {
                rebuilt[at] = REPLACEMENT;
            }
            at += Character.charCount(codePoint);
        }
        return new KeptAnswer(new String(rebuilt), true, index < length);
    }

    /** The bounded prefix decoded from response bytes, retaining capture changes beyond the prefix. */
    public static KeptAnswer captured(String decoded, boolean malformedUtf8, boolean cutAtBound) {
        KeptAnswer kept = of(decoded);
        return new KeptAnswer(kept.text, kept.altered || malformedUtf8 || cutAtBound, kept.truncated || cutAtBound);
    }

    static boolean unkeepable(int codePoint) {
        return codePoint == 0 || CharacterStanding.of(codePoint) == CharacterStanding.SURROGATE;
    }

    public String text() {
        return text;
    }

    /** Whether what is kept is not as it came back: a character replaced, or the rest cut. */
    public boolean altered() {
        return altered;
    }

    /** Whether the store cut off the end, independently of any replacement within what it kept. */
    public boolean truncated() {
        return truncated;
    }
}
