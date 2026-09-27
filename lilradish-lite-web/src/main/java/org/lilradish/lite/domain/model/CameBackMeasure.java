package org.lilradish.lite.domain.model;

/**
 * The measure, in characters, of what came back from a model, counted as {@link SentText} counts what
 * is sent: a character is a code point. What came back is measured as it is and never refused here,
 * so an unpaired surrogate counts as one character rather than failing the count.
 */
public final class CameBackMeasure {

    private CameBackMeasure() {}

    public static long characters(String answer) {
        return answer.codePointCount(0, answer.length());
    }
}
