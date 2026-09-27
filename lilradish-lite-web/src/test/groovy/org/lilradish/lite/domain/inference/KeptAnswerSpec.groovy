package org.lilradish.lite.domain.inference

import spock.lang.Specification

class KeptAnswerSpec extends Specification {

    // Named by code point, not typed: typed in, most are invisible and a row reads as a duplicate of the last.
    static final String NUL = Character.toString(0x0)
    static final String START_OF_HEADING = Character.toString(0x1)
    static final String HIGH = Character.toString(0xD83D)
    static final String LOW = Character.toString(0xDE00)
    static final String GRINNING = Character.toString(0x1F600)
    static final String REPLACED = Character.toString(0xFFFD)

    static final int MOST = KeptAnswer.MOST_KEPT

    def "what the store can hold is kept as it came, as the very same text, and never said to be altered"() {
        when:
        def kept = KeptAnswer.of(cameBack)

        then:
        kept.text().is(cameBack)
        !kept.altered()

        where:
        cameBack << [
                "",
                '{"values":{"summary":"late"},"confidences":{}}',
                "a" + GRINNING + "b",
                "\t\r\n" + START_OF_HEADING,
                REPLACED]
    }

    /* Millions of characters are built inside the feature and compared there: as a row, or rendered by a failed
     * condition, each would be written into the name of the iteration or the report in full. */
    def "what runs to exactly the most the store keeps is kept as it came, a pair counting as one character"() {
        when:
        def cameBack = piece * MOST
        def kept = KeptAnswer.of(cameBack)
        boolean asItCame = kept.text().is(cameBack)

        then:
        asItCame
        !kept.altered()

        where:
        piece << ["a", GRINNING]
    }

    def "each null character and each half of a pair standing alone is replaced, and the rest kept as it came"() {
        when:
        def kept = KeptAnswer.of(cameBack)

        then:
        kept.text() == expected
        kept.altered()

        where:
        cameBack                       || expected
        NUL                            || REPLACED
        "a" + NUL + "b" + NUL          || "a" + REPLACED + "b" + REPLACED
        "late" + HIGH                  || "late" + REPLACED
        LOW + "late"                   || REPLACED + "late"
        LOW + HIGH                     || REPLACED + REPLACED
        HIGH + HIGH + LOW              || REPLACED + GRINNING
        GRINNING + NUL + GRINNING      || GRINNING + REPLACED + GRINNING
    }

    /** Each side is a head, a piece repeated as many times as the most kept and {@code over} more, and a tail. */
    def "what runs past the most the store keeps is cut there, a pair counting as one character"() {
        when:
        def kept = KeptAnswer.of(head + piece * (MOST + over) + tail)
        boolean cutWhereExpected = kept.text() == keptHead + piece * (MOST + keptOver) + keptTail
        int keptCharacters = kept.text().codePointCount(0, kept.text().length())

        then:
        cutWhereExpected
        keptCharacters == MOST
        kept.altered()

        where:
        head | piece    | over | tail         || keptHead | keptOver | keptTail
        ""   | "a"      | 1    | ""           || ""       | 0        | ""
        ""   | GRINNING | 0    | "a"          || ""       | 0        | ""
        ""   | "a"      | -1   | GRINNING * 2 || ""       | -1       | GRINNING
        NUL  | "a"      | 0    | ""           || REPLACED | -1       | ""
        ""   | "a"      | 0    | NUL          || ""       | 0        | ""
        ""   | "a"      | -1   | HIGH + "a"   || ""       | -1       | REPLACED
    }

    def "nothing to keep is refused by name"() {
        when:
        KeptAnswer.of(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "KeptAnswer came back must not be null"
    }
}
