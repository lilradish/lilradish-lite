package org.lilradish.lite.domain.model

import spock.lang.Specification

class SentTextSpec extends Specification {

    private static final String GRINNING = Character.toString(0x1F600)
    private static final String TOTAL = Character.toString(0x5408) + Character.toString(0x8BA1)
    private static final String UNIT_SEPARATOR = Character.toString(0x1F)
    private static final String ACUTE = Character.toString(0x0301)
    private static final String REGIONAL_J = Character.toString(0x1F1EF)
    private static final String REGIONAL_P = Character.toString(0x1F1F5)
    private static final String HIGH = Character.toString(0xD83D)
    private static final String LOW = Character.toString(0xDE00)

    def "keeps both texts as given and counts the code points of the two together"() {
        when:
        def sent = SentText.measure(system, user)

        then:
        sent.system() == system
        sent.user() == user
        sent.characters() == characters

        where:
        system           | user                || characters
        ""               | ""                  || 0
        "answer in JSON" | '{"a":1}'           || 21
        TOTAL            | UNIT_SEPARATOR      || 3
        GRINNING         | GRINNING + GRINNING || 3
    }

    /**
     * Each row cuts where a reader sees one character: a letter from its accent, a flag between its
     * two halves, or beside surrogate pairs, which never split since a lone half is refused.
     */
    def "counts parts cut apart between code points to exactly the count of the whole"() {
        when:
        def whole = SentText.measure(firstSystem + secondSystem, firstUser + secondUser)

        then:
        whole.characters() == characters
        whole.characters() ==
                SentText.measure(firstSystem, firstUser).characters() +
                SentText.measure(secondSystem, secondUser).characters()

        where:
        firstSystem | secondSystem | firstUser | secondUser || characters
        "e"         | ACUTE        | "a"       | "b"        || 4
        REGIONAL_J  | REGIONAL_P   | "x"       | "y"        || 4
        "s"         | "t"          | "e"       | ACUTE      || 4
        GRINNING    | GRINNING     | GRINNING  | "a"        || 4
    }

    def "refuses a text holding an unpaired surrogate, naming which of the two holds it"() {
        when:
        SentText.measure(system, user)

        then:
        def error = thrown(IllegalArgumentException)
        error.message == expectedMessage

        where:
        system           | user             || expectedMessage
        HIGH             | "a"              || "SentText system holds an unpaired surrogate"
        LOW              | "a"              || "SentText system holds an unpaired surrogate"
        "a" + HIGH       | "a"              || "SentText system holds an unpaired surrogate"
        LOW + HIGH       | "a"              || "SentText system holds an unpaired surrogate"
        HIGH + "a" + LOW | "a"              || "SentText system holds an unpaired surrogate"
        "a" + HIGH       | LOW + "a"        || "SentText system holds an unpaired surrogate"
        "a"              | HIGH             || "SentText user holds an unpaired surrogate"
        "a"              | GRINNING + LOW   || "SentText user holds an unpaired surrogate"
        "a"              | HIGH + GRINNING  || "SentText user holds an unpaired surrogate"
    }

    def "refuses a missing text, naming which of the two is missing"() {
        when:
        SentText.measure(system, user)

        then:
        def error = thrown(NullPointerException)
        error.message == expectedMessage

        where:
        system | user || expectedMessage
        null   | "a"  || "SentText system must not be null"
        "a"    | null || "SentText user must not be null"
    }
}
