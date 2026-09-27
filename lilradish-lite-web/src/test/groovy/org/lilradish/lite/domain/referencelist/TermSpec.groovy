package org.lilradish.lite.domain.referencelist

import spock.lang.Specification

class TermSpec extends Specification {

    static final String JOINER = Character.toString(0x200D)

    static final String NON_JOINER = Character.toString(0x200C)

    /** A joiner is kept: some words are spelt with one. Counted in code points, as the column counts. */
    def "a term is carried exactly as given, up to its bound"() {
        expect:
        new Term(value).value() == value

        where:
        value << ["Billing", "Product fault", "R" + Character.toString(0xE9) + "clamation", "a" + JOINER + "b", "i".repeat(128),
                  Character.toString(0x1F600).repeat(128)]
    }

    def "every rule a term is held to refuses under its own name"() {
        when:
        new Term(value)

        then:
        def refused = thrown(expectedException)
        refused.message == expectedMessage

        where:
        value                                  || expectedException        | expectedMessage
        null                                   || NullPointerException     | "Term must not be null"
        "Line\nbreak"                          || IllegalArgumentException | "Term must not contain a control, line or paragraph separator, or surrogate character, but the one at index 4 is U+000A"
        "One\r\ntwo"                           || IllegalArgumentException | "Term must not contain a control, line or paragraph separator, or surrogate character, but the one at index 3 is U+000D"
        "Tab\there"                            || IllegalArgumentException | "Term must not contain a control, line or paragraph separator, or surrogate character, but the one at index 3 is U+0009"
        "A" + Character.toString(0x202E)       || IllegalArgumentException | "Term must not contain a direction control, but the one at index 1 is U+202E"
        "A" + Character.toString(0x2066)       || IllegalArgumentException | "Term must not contain a direction control, but the one at index 1 is U+2066"
        "A" + Character.toString(0xE0041)      || IllegalArgumentException | "Term must not contain a tag character, but the one at index 1 is U+E0041"
        Character.toString(0x200B)             || IllegalArgumentException | "Term must not contain a character that shows nothing, but the one at index 0 is U+200B"
        "Bill" + Character.toString(0xFE0F)    || IllegalArgumentException | "Term must not contain a character that shows nothing, but the one at index 4 is U+FE0F"
        ""                                     || IllegalArgumentException | "Term must not be empty"
        " Billing"                             || IllegalArgumentException | "Term must not begin with a space"
        "Billing "                             || IllegalArgumentException | "Term must not end with a space"
        "Product  fault"                       || IllegalArgumentException | "Term must not contain two spaces in a row, but a pair begins at index 7"
        "Product" + Character.toString(0xA0) + "fault" || IllegalArgumentException | "Term must not contain a space other than U+0020, but the one at index 7 is U+00A0"
        NON_JOINER + JOINER                    || IllegalArgumentException | "Term must hold something other than space, format and tag characters"
        "i".repeat(129)                        || IllegalArgumentException | "Term must be at most 128 characters, but this one is 129"
    }

    def "no text is judged where there is none"() {
        when:
        Term.refusalOf(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "Term text judged must not be null"
    }
}
