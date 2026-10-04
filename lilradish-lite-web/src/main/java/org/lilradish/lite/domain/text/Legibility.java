package org.lilradish.lite.domain.text;

import java.util.Locale;

/**
 * Whether a reader can see a value for what it is. Checks that are called separately rather than one
 * that does everything, because the values needing them do not need the same set: one is bounded by
 * the column it is stored in, another is whatever its origin produced and is bounded by nothing.
 *
 * <p>Each context calls the checks it needs. Character rules use Unicode categories and, where needed,
 * {@link #requireNothingInvisible} rather than a single blanket rule. Where
 * {@link #requireSpaceDecidesNothing} is required, U+0020 is the only admitted space and must occur between words.
 *
 * <p>These checks refuse unacceptable input; they do not rewrite it. Refusals name the caller's
 * value type. When a check reports an offending code point, it identifies its string position
 * without echoing the character. Numeric positions are formatted with {@link Locale#ROOT}.
 */
public final class Legibility {

    private Legibility() {}

    public static void requireVisibleAndWellFormed(String value, String valueType) {
        requireNone(value, valueType, true, "a control, format, tag, line or paragraph separator, or surrogate");
    }

    /**
     * For text a format character belongs in: only what cannot be one line of text is refused, and
     * whoever shows it isolates it.
     */
    public static void requireOneWellFormedLine(String value, String valueType) {
        requireNone(value, valueType, false, "a control, line or paragraph separator, or surrogate");
    }

    /**
     * For prose sent to a model, which runs over lines: a tab and a line feed are its own, every other
     * control is refused as one line refuses it, and so is every {@link ConcealingCharacter}. The first
     * refused, reading from the start, is the one reported.
     */
    public static void requireWellFormedProse(String value, String valueType) {
        int index = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            CharacterStanding standing = CharacterStanding.of(codePoint);
            if (standing.refusedInProse()) {
                throw new IllegalArgumentException(String.format(
                        Locale.ROOT,
                        "%s must not contain %s, but the one at index %d is U+%04X",
                        valueType,
                        switch (standing) {
                            case DIRECTION_CONTROL, TAG -> concealingSaid(standing);
                            default ->
                                "a control other than a tab or a line feed, a line or paragraph separator,"
                                        + " or surrogate character";
                        },
                        index,
                        codePoint));
            }
            index += Character.charCount(codePoint);
        }
    }

    /** Every {@link ConcealingCharacter} refused, which one line needs asking apart: it admits a format character. */
    public static void requireNothingConcealed(String value, String valueType) {
        int index = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            CharacterStanding standing = CharacterStanding.of(codePoint);
            if (standing == CharacterStanding.DIRECTION_CONTROL || standing == CharacterStanding.TAG) {
                throw new IllegalArgumentException(String.format(
                        Locale.ROOT,
                        "%s must not contain %s, but the one at index %d is U+%04X",
                        valueType,
                        concealingSaid(standing),
                        index,
                        codePoint));
            }
            index += Character.charCount(codePoint);
        }
    }

    private static String concealingSaid(CharacterStanding standing) {
        return standing == CharacterStanding.TAG ? "a tag character" : "a direction control";
    }

    /** Whether it holds a character {@link #requireNothingInvisible} refuses. */
    public static boolean holdsInvisible(String value) {
        return firstInvisible(value) >= 0;
    }

    /** Every Default_Ignorable_Code_Point but the zero-width joiner and non-joiner, which some words are spelt with. */
    public static void requireNothingInvisible(String value, String valueType) {
        int index = firstInvisible(value);
        if (index >= 0) {
            throw new IllegalArgumentException(String.format(
                    Locale.ROOT,
                    "%s must not contain a character that shows nothing, but the one at index %d is U+%04X",
                    valueType,
                    index,
                    value.codePointAt(index)));
        }
    }

    private static int firstInvisible(String value) {
        int index = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            if (CharacterStanding.ignorable(codePoint)) {
                return index;
            }
            index += Character.charCount(codePoint);
        }
        return -1;
    }

    /**
     * Whether anything in it would show on one line: a value of spaces, format and tag characters alone
     * is drawn as nothing, a blank a reader cannot pick out of a list.
     */
    public static boolean holdsSomethingVisible(String value) {
        return shows(value, false);
    }

    public static void requireSomethingVisible(String value, String valueType) {
        if (!holdsSomethingVisible(value)) {
            throw new IllegalArgumentException(
                    valueType + " must hold something other than space, format and tag characters");
        }
    }

    /** As {@link #holdsSomethingVisible}, a tab and a line feed drawing nothing either. */
    public static boolean holdsSomethingVisibleInProse(String value) {
        return shows(value, true);
    }

    public static void requireSomethingVisibleInProse(String value, String valueType) {
        if (!holdsSomethingVisibleInProse(value)) {
            throw new IllegalArgumentException(
                    valueType + " must hold something other than space, tab, line feed, format and tag characters");
        }
    }

    private static boolean shows(String value, boolean prose) {
        int index = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            CharacterStanding standing = CharacterStanding.of(codePoint);
            if (!(prose ? standing.blankInProse() : standing.blankInALine())
                    && !CharacterStanding.ignorable(codePoint)) {
                return true;
            }
            index += Character.charCount(codePoint);
        }
        return false;
    }

    public static void requireNotEmpty(String value, String valueType) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException(valueType + " must not be empty");
        }
    }

    /**
     * Total on the empty value, which holds no space and so satisfies all three rules; what a value
     * must hold at all is {@link #requireNotEmpty}'s question.
     */
    public static void requireSpaceDecidesNothing(String value, String valueType) {
        int index = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            if (CharacterStanding.of(codePoint) == CharacterStanding.OTHER_SPACE) {
                throw new IllegalArgumentException(String.format(
                        Locale.ROOT,
                        "%s must not contain a space other than U+0020, but the one at index %d is U+%04X",
                        valueType,
                        index,
                        codePoint));
            }
            index += Character.charCount(codePoint);
        }
        if (value.startsWith(" ")) {
            throw new IllegalArgumentException(valueType + " must not begin with a space");
        }
        if (value.endsWith(" ")) {
            throw new IllegalArgumentException(valueType + " must not end with a space");
        }
        // Every space still standing is U+0020, the loop above having refused the other sixteen.
        int pair = value.indexOf("  ");
        if (pair >= 0) {
            throw new IllegalArgumentException(String.format(
                    Locale.ROOT,
                    "%s must not contain two spaces in a row, but a pair begins at index %d",
                    valueType,
                    pair));
        }
    }

    /**
     * The position indexes the string rather than counting characters, the two parting company at
     * the first code point outside the basic plane.
     */
    private static void requireNone(String value, String valueType, boolean visible, String refused) {
        int index = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            CharacterStanding standing = CharacterStanding.of(codePoint);
            if (visible ? standing.refusedInAVisibleLine() : standing.refusedInALine()) {
                throw new IllegalArgumentException(String.format(
                        Locale.ROOT,
                        "%s must not contain %s character, but the one at index %d is U+%04X",
                        valueType,
                        refused,
                        index,
                        codePoint));
            }
            index += Character.charCount(codePoint);
        }
    }

    /** Counted in code points, which is what a column's own bound counts. */
    public static boolean withinMaximumLength(String value, int maximumLength) {
        int units = value.length();
        return units <= maximumLength
                || (units <= 2L * maximumLength && value.codePointCount(0, units) <= maximumLength);
    }

    public static void requireWithinMaximumLength(String value, int maximumLength, String valueType) {
        if (!withinMaximumLength(value, maximumLength)) {
            throw new IllegalArgumentException(String.format(
                    Locale.ROOT,
                    "%s must be at most %d characters, but this one is %d",
                    valueType,
                    maximumLength,
                    value.codePointCount(0, value.length())));
        }
    }
}
