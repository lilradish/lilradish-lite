package org.lilradish.lite.domain.people

import groovy.transform.CompileStatic
import java.util.regex.Pattern
import spock.lang.Specification

/**
 * White_Space listed as PropList lists it rather than asked of the runtime,
 * so the expectation cannot move with what it checks.
 */
class PersonNameAlgorithmSpec extends Specification {

    static final List<Integer> WHITE_SPACE = [
            0x0009, 0x000A, 0x000B, 0x000C, 0x000D, 0x0020, 0x0085, 0x00A0, 0x1680,
            0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200A,
            0x2028, 0x2029, 0x202F, 0x205F, 0x3000]

    static final String EVERY_WHITE_SPACE = WHITE_SPACE.collect { Character.toString(it) }.join()

    static final Pattern RUNTIME_WHITE_SPACE = Pattern.compile("\\p{IsWhite_Space}")

    static final String NO_BREAK_SPACE = Character.toString(0x00A0)

    static final String IDEOGRAPHIC_SPACE = Character.toString(0x3000)

    static final String LINE_SEPARATOR = Character.toString(0x2028)

    static final String PARAGRAPH_SEPARATOR = Character.toString(0x2029)

    static final String NEXT_LINE = Character.toString(0x0085)

    static final String ZERO_WIDTH_SPACE = Character.toString(0x200B)

    static final String ZERO_WIDTH_NON_JOINER = Character.toString(0x200C)

    static final String ZERO_WIDTH_JOINER = Character.toString(0x200D)

    static final String WORD_JOINER = Character.toString(0x2060)

    static final String ZERO_WIDTH_NO_BREAK_SPACE = Character.toString(0xFEFF)

    static final String MONGOLIAN_VOWEL_SEPARATOR = Character.toString(0x180E)

    static final String SOFT_HYPHEN = Character.toString(0x00AD)

    static final String GRINNING_FACE = Character.toString(0x1F600)

    /** Every kind of White_Space at once: a control, a space separator, a line and a paragraph separator. */
    static final String MIXED_RUNS = "\t" + NO_BREAK_SPACE + "Ada" + IDEOGRAPHIC_SPACE + " " + LINE_SEPARATOR +
            "Lovelace" + NEXT_LINE + PARAGRAPH_SEPARATOR

    def "every White_Space code point is one U+0020 between words, nothing at either end, and alone no name"() {
        given:
        def space = Character.toString(codePoint)

        when:
        def between = PersonName.fromDirectory("Ada" + space + "Lovelace")
        def around = PersonName.fromDirectory(space + "Ada" + space)
        def alone = PersonName.fromDirectory(space)

        then:
        between.value() == "Ada Lovelace"
        around.value() == "Ada"
        alone == null

        where:
        codePoint << WHITE_SPACE
    }

    /**
     * Each renders as nothing or next to nothing and none is White_Space, so each is kept where it
     * stands, and the spaces either side of it stay two, being no run.
     */
    def "what only looks like whitespace is kept where it stands, splitting the spaces either side of it"() {
        given:
        def held = NO_BREAK_SPACE + lookAlike + "Ada" + NO_BREAK_SPACE + lookAlike + IDEOGRAPHIC_SPACE + "Lovelace "

        when:
        def name = PersonName.fromDirectory(held)

        then:
        name.value() == spaced

        where:
        lookAlike                 || spaced
        ZERO_WIDTH_SPACE          || ZERO_WIDTH_SPACE + "Ada " + ZERO_WIDTH_SPACE + " Lovelace"
        ZERO_WIDTH_NON_JOINER     || ZERO_WIDTH_NON_JOINER + "Ada " + ZERO_WIDTH_NON_JOINER + " Lovelace"
        ZERO_WIDTH_JOINER         || ZERO_WIDTH_JOINER + "Ada " + ZERO_WIDTH_JOINER + " Lovelace"
        WORD_JOINER               || WORD_JOINER + "Ada " + WORD_JOINER + " Lovelace"
        ZERO_WIDTH_NO_BREAK_SPACE || ZERO_WIDTH_NO_BREAK_SPACE + "Ada " + ZERO_WIDTH_NO_BREAK_SPACE + " Lovelace"
        MONGOLIAN_VOWEL_SEPARATOR || MONGOLIAN_VOWEL_SEPARATOR + "Ada " + MONGOLIAN_VOWEL_SEPARATOR + " Lovelace"
        SOFT_HYPHEN               || SOFT_HYPHEN + "Ada " + SOFT_HYPHEN + " Lovelace"
    }

    /**
     * Rows mixing runs that need rewriting with runs that do not, in either order, because the name is
     * copied only from the first run needing it and everything before that run has to come along.
     */
    def "a run of whitespace anywhere inside is one U+0020, and at either end is nothing"() {
        when:
        def name = PersonName.fromDirectory(held)

        then:
        name.value() == spaced

        where:
        held                                                  || spaced
        "  Ada  Lovelace  "                                   || "Ada Lovelace"
        MIXED_RUNS                                            || "Ada Lovelace"
        "Ada" + NO_BREAK_SPACE + "Augusta King"               || "Ada Augusta King"
        "Ada Augusta" + NO_BREAK_SPACE + "King"               || "Ada Augusta King"
        "Ada Augusta  King"                                   || "Ada Augusta King"
        "Ada   Augusta" + NO_BREAK_SPACE + " King"            || "Ada Augusta King"
        " A "                                                 || "A"
        GRINNING_FACE + NO_BREAK_SPACE + GRINNING_FACE        || GRINNING_FACE + " " + GRINNING_FACE
        IDEOGRAPHIC_SPACE + GRINNING_FACE + IDEOGRAPHIC_SPACE || GRINNING_FACE
        "Ada Lovelace"                                        || "Ada Lovelace"
        "Ada" + ZERO_WIDTH_JOINER                             || "Ada" + ZERO_WIDTH_JOINER
        ZERO_WIDTH_JOINER + IDEOGRAPHIC_SPACE + "A"           || ZERO_WIDTH_JOINER + " A"
    }

    /** Nothing a reader could see is held, so nothing is invented in its place either. */
    def "a name the directory holds of nothing but whitespace and format characters, however much, is no name"() {
        when:
        def name = PersonName.fromDirectory(held)

        then:
        name == null

        where:
        held << ["", " ", "   ", "\t\n\r", IDEOGRAPHIC_SPACE + " " + NO_BREAK_SPACE, EVERY_WHITE_SPACE,
                 ZERO_WIDTH_SPACE, " " + ZERO_WIDTH_SPACE + " ", ZERO_WIDTH_JOINER, SOFT_HYPHEN,
                 ZERO_WIDTH_SPACE + IDEOGRAPHIC_SPACE + ZERO_WIDTH_JOINER + " " + ZERO_WIDTH_NO_BREAK_SPACE]
    }

    /**
     * Nothing is trimmed: a space at either end is part of what is matched. Rows mix runs needing a
     * rewrite with runs that do not, and end on a run, where nothing past it bounds the scan.
     */
    def "text spaced as names are has each run of White_Space one U+0020, and keeps a run at either end"() {
        when:
        def spaced = PersonName.spacedAsNamesAre(typed)

        then:
        spaced == expected

        where:
        typed                                             || expected
        "ADA" + IDEOGRAPHIC_SPACE                         || "ADA "
        IDEOGRAPHIC_SPACE + "ada"                         || " ada"
        "田中" + IDEOGRAPHIC_SPACE + "一郎"                   || "田中 一郎"
        "  ada  "                                         || " ada "
        " " + IDEOGRAPHIC_SPACE + NO_BREAK_SPACE          || " "
        "\t"                                              || " "
        "ADA" + NO_BREAK_SPACE + "B C"                    || "ADA B C"
        "A B" + NO_BREAK_SPACE + NO_BREAK_SPACE           || "A B "
        "A B  "                                           || "A B "
        ZERO_WIDTH_SPACE + IDEOGRAPHIC_SPACE              || ZERO_WIDTH_SPACE + " "
    }

    /** Every UTF-16 unit asked, since White_Space holds nothing outside the basic plane. */
    def "exactly the White_Space code points are whitespace, and no other unit is"() {
        when:
        def found = whiteSpaceUnits()

        then:
        found == WHITE_SPACE
    }

    /**
     * The constructor refuses by the runtime's categories and the regex engine's White_Space, which the
     * JDK defines by the same categories: an upgrade that moves either now fails here, not in a read.
     */
    def "every code point the runtime counts as a separator or as White_Space is in the switch, and nothing else is"() {
        when:
        def runtime = runtimeWhiteSpace()
        def switched = whiteSpaceUnits()

        then:
        runtime == switched

        and: "over sets holding something, two empty sets being equal too"
        !switched.isEmpty()
    }

    private static List<Integer> whiteSpaceUnits() {
        (0..0xFFFF).findAll { PersonName.isWhiteSpace((char) it) }
    }

    @CompileStatic
    private static List<Integer> runtimeWhiteSpace() {
        List<Integer> found = []
        for (int codePoint = 0; codePoint <= Character.MAX_CODE_POINT; codePoint++) {
            int type = Character.getType(codePoint)
            if (type == Character.SPACE_SEPARATOR || type == Character.LINE_SEPARATOR ||
                    type == Character.PARAGRAPH_SEPARATOR ||
                    RUNTIME_WHITE_SPACE.matcher(Character.toString(codePoint)).matches()) {
                found << codePoint
            }
        }
        found
    }
}
