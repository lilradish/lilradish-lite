package org.lilradish.lite.domain.text;

/**
 * The one judgement of what a code point is to text that a person or a model reads, which every check on
 * such text asks rather than keeping a set of its own. What one line, a line that must read as it compares,
 * and prose each refuse differs, and is spelt out below constant by constant; so is what draws nothing.
 */
public enum CharacterStanding {
    /** U+0009 and U+000A: prose is written with them, and each adds a line to anything one line is put in. */
    TAB_OR_LINE_FEED,
    /** Every control but a tab and a line feed, a carriage return among them. */
    OTHER_CONTROL,
    LINE_OR_PARAGRAPH_SEPARATOR,
    /** Half of a pair standing alone. */
    SURROGATE,
    /** An embedding, an override or an isolate: U+202A to U+202E, and U+2066 to U+2069. */
    DIRECTION_CONTROL,
    /** The tag block, U+E0000 to U+E007F, what is unassigned in it included. */
    TAG,
    /** Every other format character: a joiner and a non-joiner, a zero-width space, a soft hyphen, a mark. */
    FORMAT,
    SPACE,
    /** Every other space separator, U+00A0 and U+3000 among them. */
    OTHER_SPACE,
    /** Everything else, an unassigned code point included. */
    SHOWS;

    // Default_Ignorable_Code_Point from Unicode 16.0's DerivedCoreProperties.txt, the JDK's own, which exposes
    // no predicate for it; first and last of each range, less U+200C and U+200D.
    private static final int[] IGNORABLE = {
        0x00AD, 0x00AD, 0x034F, 0x034F, 0x061C, 0x061C, 0x115F, 0x1160, 0x17B4, 0x17B5, 0x180B, 0x180F,
        0x200B, 0x200B, 0x200E, 0x200F, 0x202A, 0x202E, 0x2060, 0x206F, 0x3164, 0x3164, 0xFE00, 0xFE0F,
        0xFEFF, 0xFEFF, 0xFFA0, 0xFFA0, 0xFFF0, 0xFFF8, 0x1BCA0, 0x1BCA3, 0x1D173, 0x1D17A, 0xE0000, 0xE0FFF
    };

    public static CharacterStanding of(int codePoint) {
        if (codePoint == '\t' || codePoint == '\n') {
            return TAB_OR_LINE_FEED;
        }
        if (codePoint == ' ') {
            return SPACE;
        }
        if ((codePoint >= 0x202A && codePoint <= 0x202E) || (codePoint >= 0x2066 && codePoint <= 0x2069)) {
            return DIRECTION_CONTROL;
        }
        if (codePoint >= 0xE0000 && codePoint <= 0xE007F) {
            return TAG;
        }
        return switch (Character.getType(codePoint)) {
            case Character.CONTROL -> OTHER_CONTROL;
            case Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> LINE_OR_PARAGRAPH_SEPARATOR;
            case Character.SURROGATE -> SURROGATE;
            case Character.FORMAT -> FORMAT;
            case Character.SPACE_SEPARATOR -> OTHER_SPACE;
            default -> SHOWS;
        };
    }

    /** What would put a line, a break or half a character of its own into whatever one line is put in. */
    public boolean refusedInALine() {
        return switch (this) {
            case TAB_OR_LINE_FEED, OTHER_CONTROL, LINE_OR_PARAGRAPH_SEPARATOR, SURROGATE -> true;
            case DIRECTION_CONTROL, TAG, FORMAT, SPACE, OTHER_SPACE, SHOWS -> false;
        };
    }

    /** As one line refuses, and every format character besides: two such values never render alike. */
    public boolean refusedInAVisibleLine() {
        return switch (this) {
            case TAB_OR_LINE_FEED, OTHER_CONTROL, LINE_OR_PARAGRAPH_SEPARATOR, SURROGATE -> true;
            case DIRECTION_CONTROL, TAG, FORMAT -> true;
            case SPACE, OTHER_SPACE, SHOWS -> false;
        };
    }

    /** A tab and a line feed are prose's own; what conceals is refused in it with what breaks it. */
    public boolean refusedInProse() {
        return switch (this) {
            case OTHER_CONTROL, LINE_OR_PARAGRAPH_SEPARATOR, SURROGATE, DIRECTION_CONTROL, TAG -> true;
            case TAB_OR_LINE_FEED, FORMAT, SPACE, OTHER_SPACE, SHOWS -> false;
        };
    }

    /**
     * Draws nothing in a line, so a line of these alone shows nothing. A tab and a line feed count as
     * showing: a line refuses them for what they are rather than as blank.
     */
    public boolean blankInALine() {
        return switch (this) {
            case DIRECTION_CONTROL, TAG, FORMAT, SPACE, OTHER_SPACE -> true;
            // Counted as showing, so a text of nothing else is refused for what it holds rather than as blank.
            case TAB_OR_LINE_FEED, OTHER_CONTROL, LINE_OR_PARAGRAPH_SEPARATOR, SURROGATE, SHOWS -> false;
        };
    }

    /** As in a line, and a tab and a line feed besides, which prose holds and which draw nothing in it. */
    public boolean blankInProse() {
        return this == TAB_OR_LINE_FEED || blankInALine();
    }

    /**
     * Whether Unicode lets it show nothing whatever its standing, a filler letter or a variation selector among
     * them; the joiner and the non-joiner apart, some words being spelt with one.
     */
    public static boolean ignorable(int codePoint) {
        for (int range = 0; range < IGNORABLE.length; range += 2) {
            if (codePoint >= IGNORABLE[range] && codePoint <= IGNORABLE[range + 1]) {
                return true;
            }
        }
        return false;
    }
}
