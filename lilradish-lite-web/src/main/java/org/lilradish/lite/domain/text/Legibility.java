package org.lilradish.lite.domain.text;

import java.util.Locale;

/**
 * Whether a reader can see a value for what it is. Checks that are called separately rather than one
 * that does everything, because the values needing them do not need the same set: one is bounded by
 * the column it is stored in, another is whatever its origin produced and is bounded by nothing.
 *
 * <p>What each refused category buys is why it is refused. A control character or a line separator
 * adds a line of the caller's own to whatever the value is interpolated into. A format character — a
 * zero-width space, a soft hyphen, a bidirectional override — leaves two unequal values rendering
 * identically, and an override reverses the order of everything printed after it. An unpaired
 * surrogate encodes to the same UTF-8 bytes as another does, so two values become one the moment
 * either is written down.
 *
 * <p>That is a list of categories and not a class of forgery closed. Ways to write one value so that
 * it reaches a reader as another survive: a letter or a mark that renders as nothing is refused only by
 * {@link #requireNothingInvisible}, and only where Unicode calls it ignorable; two canonically equivalent
 * spellings are unequal strings that render identically, and a homoglyph is an ordinary letter.
 *
 * <p>Spacing is judged in the one category the list leaves alone, a value somebody typed being
 * entitled to a space between its words. U+0020 is the only space separator admitted, and only
 * between words: at either end, or doubled, a space decides nothing a reader can see and everything a
 * unique index can.
 *
 * <p>Everything here refuses and nothing rewrites. A constructor cannot tell a name somebody is
 * typing from a row being read back out of the store, so rewriting in one merges: two rows distinct
 * where they are kept come back as one value, and nothing afterwards can say that it happened. A
 * refusal blocks instead, which is visible and which whoever wrote the value can put right.
 *
 * <p>Judged by Unicode category, as {@link CharacterStanding} has it, rather than by {@link String#isBlank},
 * which counts neither U+00A0 nor U+2007 as whitespace and so reports a value made entirely of either as
 * holding something.
 *
 * <p>Each check that refuses is told the caller's type name, so what it throws reads as the caller's
 * own rule rather than as this class's. The offending code point is reported by position rather than
 * echoed back, for the reason the value is checked at all: a message carrying it verbatim is that
 * same escape one layer out. {@link Locale#ROOT} is what makes that position deterministic — under a
 * default locale of its own, {@code %d} renders in that locale's digits.
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
            if (!(prose ? standing.blankInProse() : standing.blankInALine())) {
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
