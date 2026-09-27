package org.lilradish.lite.domain.people;

import static java.util.Objects.requireNonNull;

import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.text.Legibility;

/**
 * What somebody is called once spaced: U+0020 the only whitespace, never at an end or doubled.
 * Only {@link #fromDirectory} rewrites; the constructor refuses, for the reason {@link Legibility} gives.
 */
public record PersonName(String value) {

    /** Level with the bound on the directory's name and on the pool's copy, counted the way both count. */
    private static final int MAXIMUM_LENGTH = 256;

    public PersonName {
        requireNonNull(value, "PersonName must not be null");
        Legibility.requireOneWellFormedLine(value, "PersonName");
        Legibility.requireNotEmpty(value, "PersonName");
        Legibility.requireSpaceDecidesNothing(value, "PersonName");
        Legibility.requireSomethingVisible(value, "PersonName");
        Legibility.requireWithinMaximumLength(value, MAXIMUM_LENGTH, "PersonName");
    }

    /**
     * Spaced as the type requires; null where nothing visible is held, nothing being invented.
     * A name the type still refuses fails the read.
     */
    public static @Nullable PersonName fromDirectory(String held) {
        requireNonNull(held, "PersonName held in the directory must not be null");
        int end = held.length();
        while (end > 0 && isWhiteSpace(held.charAt(end - 1))) {
            end--;
        }
        int start = 0;
        while (start < end && isWhiteSpace(held.charAt(start))) {
            start++;
        }
        String spaced = spaced(held, start, end);
        return Legibility.holdsSomethingVisible(spaced) ? new PersonName(spaced) : null;
    }

    /**
     * Each run of White_Space one U+0020 and nothing trimmed, for text matched against names spaced
     * by {@link #fromDirectory}.
     */
    public static String spacedAsNamesAre(String typed) {
        requireNonNull(typed, "Text spaced as names are must not be null");
        return spaced(typed, 0, typed.length());
    }

    /** What it was given, and no copy of it, wherever that is spaced already. */
    private static String spaced(String text, int start, int end) {
        StringBuilder rewritten = null;
        int index = start;
        while (index < end) {
            char unit = text.charAt(index);
            if (!isWhiteSpace(unit)) {
                if (rewritten != null) {
                    rewritten.append(unit);
                }
                index++;
                continue;
            }
            int run = index + 1;
            while (run < end && isWhiteSpace(text.charAt(run))) {
                run++;
            }
            if (rewritten == null && (unit != ' ' || run > index + 1)) {
                rewritten = new StringBuilder(end - start).append(text, start, index);
            }
            if (rewritten != null) {
                rewritten.append(' ');
            }
            index = run;
        }
        return rewritten == null ? text.substring(start, end) : rewritten.toString();
    }

    /**
     * Unicode's White_Space, not {@link Character#isWhitespace}, whose set differs both ways;
     * every member lies in the basic plane, so no surrogate half is one.
     */
    private static boolean isWhiteSpace(char unit) {
        return switch (unit) {
            case 0x0009, 0x000A, 0x000B, 0x000C, 0x000D, 0x0020, 0x0085, 0x00A0, 0x1680 -> true;
            case 0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200A -> true;
            case 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true;
            default -> false;
        };
    }
}
