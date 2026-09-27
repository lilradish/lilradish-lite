package org.lilradish.lite.domain.inference;

import org.jspecify.annotations.Nullable;
import org.libprunus.core.log.annotation.DoNotLog;
import org.lilradish.lite.domain.text.CharacterStanding;

/**
 * Text from the model's side, cleaned to be kept and shown, and cut to what a stored text holds. Only
 * the factories make one, so uncleaned text cannot pass for it. What prose refuses is taken out, and
 * what is left is readable where anything in it is not blank.
 */
public final class ForeignProse {

    private static final int MOST_KEPT = 2048;

    private static final String NOTHING_READABLE = "Nothing readable was said about what went wrong.";

    @DoNotLog
    private final String text;

    private final boolean truncated;

    private ForeignProse(String text, boolean truncated) {
        this.text = text;
        this.truncated = truncated;
    }

    /** Absent where nothing readable is left, as it is where nothing was said. */
    public static @Nullable ForeignProse said(@Nullable String said) {
        return said == null ? null : cleaned(said);
    }

    /** Never absent, since an error has to say what went wrong: a fixed sentence stands in for nothing readable. */
    public static ForeignProse errorDetail(String detail) {
        ForeignProse cleaned = cleaned(detail);
        return cleaned == null ? new ForeignProse(NOTHING_READABLE, false) : cleaned;
    }

    public String text() {
        return text;
    }

    /** Only the cut sets this: what was taken out is not a part of the text that was lost. */
    public boolean truncated() {
        return truncated;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof ForeignProse prose && truncated == prose.truncated && text.equals(prose.text);
    }

    @Override
    public int hashCode() {
        return 31 * text.hashCode() + Boolean.hashCode(truncated);
    }

    private static @Nullable ForeignProse cleaned(String raw) {
        int length = raw.length();
        StringBuilder rebuilt = null;
        int kept = 0;
        boolean readable = false;
        int index = 0;
        while (index < length) {
            int codePoint = raw.codePointAt(index);
            CharacterStanding standing = CharacterStanding.of(codePoint);
            if (standing.refusedInProse()) {
                if (rebuilt == null) {
                    rebuilt = new StringBuilder(Math.min(length, 2 * MOST_KEPT)).append(raw, 0, index);
                }
            } else if (kept == MOST_KEPT) {
                break;
            } else {
                kept++;
                readable |= !standing.blankInProse();
                if (rebuilt != null) {
                    rebuilt.appendCodePoint(codePoint);
                }
            }
            index += Character.charCount(codePoint);
        }
        if (!readable) {
            return null;
        }
        boolean cut = index < length;
        if (rebuilt != null) {
            return new ForeignProse(rebuilt.toString(), cut);
        }
        return new ForeignProse(cut ? raw.substring(0, index) : raw, cut);
    }
}
