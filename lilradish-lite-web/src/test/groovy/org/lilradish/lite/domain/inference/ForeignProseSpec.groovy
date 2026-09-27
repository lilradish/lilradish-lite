package org.lilradish.lite.domain.inference

import groovy.transform.CompileStatic
import spock.lang.Specification

class ForeignProseSpec extends Specification {

    static final int MOST_KEPT = 2048

    static final String PLACEHOLDER = "Nothing readable was said about what went wrong."

    /*
     * Every character below is named by its code point rather than typed: typed in, most are invisible,
     * and a row carrying one reads as a duplicate of the row before it.
     */
    static final String NUL = Character.toString(0x0)
    static final String BACKSPACE = Character.toString(0x8)
    static final String VERTICAL_TAB = Character.toString(0xB)
    static final String FORM_FEED = Character.toString(0xC)
    static final String UNIT_SEPARATOR = Character.toString(0x1F)
    static final String DELETE = Character.toString(0x7F)
    static final String NEXT_LINE = Character.toString(0x85)
    static final String LAST_C1 = Character.toString(0x9F)
    static final String LINE_SEPARATOR = Character.toString(0x2028)
    static final String PARAGRAPH_SEPARATOR = Character.toString(0x2029)
    static final String HIGH = Character.toString(0xD83D)
    static final String LOW = Character.toString(0xDE00)
    static final String LEFT_TO_RIGHT_EMBEDDING = Character.toString(0x202A)
    static final String RIGHT_TO_LEFT_EMBEDDING = Character.toString(0x202B)
    static final String POP_DIRECTIONAL_FORMATTING = Character.toString(0x202C)
    static final String LEFT_TO_RIGHT_OVERRIDE = Character.toString(0x202D)
    static final String RIGHT_TO_LEFT_OVERRIDE = Character.toString(0x202E)
    static final String LEFT_TO_RIGHT_ISOLATE = Character.toString(0x2066)
    static final String RIGHT_TO_LEFT_ISOLATE = Character.toString(0x2067)
    static final String FIRST_STRONG_ISOLATE = Character.toString(0x2068)
    static final String POP_DIRECTIONAL_ISOLATE = Character.toString(0x2069)
    static final String FIRST_TAG_BLOCK = Character.toString(0xE0000)
    static final String LANGUAGE_TAG = Character.toString(0xE0001)
    static final String TAG_LATIN_SMALL_A = Character.toString(0xE0061)
    static final String CANCEL_TAG = Character.toString(0xE007F)

    static final String LEFT_TO_RIGHT_MARK = Character.toString(0x200E)
    static final String RIGHT_TO_LEFT_MARK = Character.toString(0x200F)
    static final String ARABIC_LETTER_MARK = Character.toString(0x061C)
    static final String ZERO_WIDTH_JOINER = Character.toString(0x200D)
    static final String ZERO_WIDTH_NON_JOINER = Character.toString(0x200C)
    static final String NO_BREAK_SPACE = Character.toString(0xA0)
    static final String EM_SPACE = Character.toString(0x2003)
    static final String IDEOGRAPHIC_SPACE = Character.toString(0x3000)
    static final String ACUTE = Character.toString(0x0301)
    static final String GRINNING = Character.toString(0x1F600)
    static final String ALEF = Character.toString(0x05D0)
    static final String LAST_BEFORE_TAGS = Character.toString(0xDFFFF)
    static final String FIRST_AFTER_TAGS = Character.toString(0xE0080)

    def "said is kept as it came, and as the very same text, where nothing in it is taken out"() {
        when:
        def prose = ForeignProse.said(said)

        then:
        prose.text().is(said)
        !prose.truncated()

        where:
        said << ["busy, try again later",
                 "line one\n\tline two",
                 "e" + ACUTE + " " + GRINNING,
                 ALEF + RIGHT_TO_LEFT_MARK + " (429)",
                 "a" + LEFT_TO_RIGHT_MARK + "b" + ARABIC_LETTER_MARK + "c",
                 GRINNING + ZERO_WIDTH_JOINER + GRINNING + ZERO_WIDTH_NON_JOINER,
                 "limit" + NO_BREAK_SPACE + "reached",
                 "x" + LAST_BEFORE_TAGS + FIRST_AFTER_TAGS]
    }

    def "nothing readable left is nothing said, whatever was taken out or left blank"() {
        expect:
        ForeignProse.said(said) == null

        where:
        said << [null, "", " ", " \t\n ", "\r\n", NUL + LINE_SEPARATOR, ZERO_WIDTH_JOINER + LEFT_TO_RIGHT_MARK,
                 LANGUAGE_TAG + TAG_LATIN_SMALL_A + CANCEL_TAG, HIGH + " " + LOW, NO_BREAK_SPACE, EM_SPACE,
                 IDEOGRAPHIC_SPACE, " " * MOST_KEPT + "hidden past the cut"]
    }

    def "an error with nothing readable left says so in a fixed sentence, which was not cut"() {
        when:
        def prose = ForeignProse.errorDetail(detail)

        then:
        prose.text() == PLACEHOLDER
        !prose.truncated()

        where:
        detail << ["", " \t\n ", "\r\n", NUL + LINE_SEPARATOR, ZERO_WIDTH_JOINER + RIGHT_TO_LEFT_OVERRIDE,
                   LANGUAGE_TAG + TAG_LATIN_SMALL_A, NO_BREAK_SPACE, EM_SPACE, IDEOGRAPHIC_SPACE,
                   " " * MOST_KEPT + "hidden past the cut"]
    }

    def "an error that says anything readable is kept rather than replaced by the fixed sentence"() {
        when:
        def prose = ForeignProse.errorDetail(" " + ZERO_WIDTH_JOINER + "x\n")

        then:
        prose.text() == " " + ZERO_WIDTH_JOINER + "x\n"
        !prose.truncated()
    }

    /** The cut rows keep the same text, so only whether it was cut tells them apart. */
    def "prose is equal to prose of the same text cut or not alike, whichever factory made it, and hashes alike when equal"() {
        expect:
        left.equals(right) == equal
        right.equals(left) == equal
        (left.hashCode() == right.hashCode()) || !equal

        where:
        left                                               | right                                            || equal
        ForeignProse.said("busy")                          | ForeignProse.said("busy")                        || true
        ForeignProse.said("busy")                          | ForeignProse.errorDetail("busy")                 || true
        ForeignProse.said("a" * (MOST_KEPT + 1))           | ForeignProse.said("a" * (MOST_KEPT + 2))         || true
        ForeignProse.said("busy")                          | ForeignProse.said("idle")                        || false
        ForeignProse.said("a" * (MOST_KEPT + 1))           | ForeignProse.said("a" * MOST_KEPT)               || false
        ForeignProse.said("a" * MOST_KEPT)                 | ForeignProse.said("a" * (MOST_KEPT + 1))         || false
    }

    def "prose is never equal to what is not prose, even its own text"() {
        expect:
        !ForeignProse.said("busy").equals("busy")
        !ForeignProse.said("busy").equals(null)
    }

    def "every character that could hide or rearrange what a reader sees is taken out, and nothing is said to be cut"() {
        when:
        def said = ForeignProse.said("a" + removed + "b")
        def detail = ForeignProse.errorDetail("a" + removed + "b")

        then:
        said.text() == "ab"
        !said.truncated()
        detail.text() == "ab"
        !detail.truncated()

        where:
        removed << [NUL, BACKSPACE, VERTICAL_TAB, FORM_FEED, "\r", UNIT_SEPARATOR, DELETE, NEXT_LINE, LAST_C1,
                    LINE_SEPARATOR, PARAGRAPH_SEPARATOR, HIGH, LOW, LOW + HIGH,
                    LEFT_TO_RIGHT_EMBEDDING, RIGHT_TO_LEFT_EMBEDDING, POP_DIRECTIONAL_FORMATTING,
                    LEFT_TO_RIGHT_OVERRIDE, RIGHT_TO_LEFT_OVERRIDE, LEFT_TO_RIGHT_ISOLATE, RIGHT_TO_LEFT_ISOLATE,
                    FIRST_STRONG_ISOLATE, POP_DIRECTIONAL_ISOLATE,
                    FIRST_TAG_BLOCK, LANGUAGE_TAG, TAG_LATIN_SMALL_A, CANCEL_TAG,
                    NUL + "\r" + RIGHT_TO_LEFT_OVERRIDE + TAG_LATIN_SMALL_A]
    }

    def "a carriage return is taken out wherever it stands"() {
        expect:
        ForeignProse.said(said).text() == kept

        where:
        said                       || kept
        "one\r\ntwo"               || "one\ntwo"
        "one\rtwo"                 || "onetwo"
        "one\r\n\r\ntwo\r\n"       || "one\n\ntwo\n"
        "\r\n" + "rate limited\r" || "\nrate limited"
    }

    /** Hand-kept on purpose, from the Unicode tables rather than the code, so the two cannot drift together. */
    def "over every code point, exactly the controls, separators, lone surrogates, layout controls and tags are taken out"() {
        when:
        def removed = removedCodePoints()

        then:
        removed == expectedRemoved()
    }

    def "at most so many code points are kept, and only a cut says the text was cut"() {
        when:
        def prose = ForeignProse.errorDetail(detail)

        then:
        prose.text() == kept
        prose.truncated() == truncated

        where:
        detail                                 || kept                             | truncated
        "a" * MOST_KEPT                        || "a" * MOST_KEPT                  | false
        "a" * (MOST_KEPT + 1)                  || "a" * MOST_KEPT                  | true
        "a" * (MOST_KEPT - 1) + GRINNING + "b" || "a" * (MOST_KEPT - 1) + GRINNING | true
        "a" * MOST_KEPT + GRINNING             || "a" * MOST_KEPT                  | true
        GRINNING * (MOST_KEPT + 1)             || GRINNING * MOST_KEPT             | true
        "a" * MOST_KEPT + "\r" * 10            || "a" * MOST_KEPT                  | false
        "a" * 1000 + "\r" * 5000 + "b" * 1048  || "a" * 1000 + "b" * 1048          | false
        "\r" * 5000 + "a" * (MOST_KEPT + 1)    || "a" * MOST_KEPT                  | true
        "a" * 1000 + NUL + "b" * 2000          || "a" * 1000 + "b" * 1048          | true
    }

    @CompileStatic
    private static BitSet removedCodePoints() {
        def removed = new BitSet()
        for (int codePoint = 0; codePoint <= Character.MAX_CODE_POINT; codePoint++) {
            if (ForeignProse.said("x" + Character.toString(codePoint)).text() == "x") {
                removed.set(codePoint)
            }
        }
        removed
    }

    private static BitSet expectedRemoved() {
        def expected = new BitSet()
        expected.set(0x00, 0x09)
        expected.set(0x0B, 0x20)
        expected.set(0x7F, 0xA0)
        expected.set(0x2028, 0x202A)
        expected.set(0xD800, 0xE000)
        expected.set(0x202A, 0x202F)
        expected.set(0x2066, 0x206A)
        expected.set(0xE0000, 0xE0080)
        expected
    }
}
