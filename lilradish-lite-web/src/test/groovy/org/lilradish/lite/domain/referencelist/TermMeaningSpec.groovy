package org.lilradish.lite.domain.referencelist

import spock.lang.Specification

class TermMeaningSpec extends Specification {

    /** Spacing is left as typed, a joiner kept, and the bound counted in code points. */
    def "what a term means is carried exactly as given, spacing and all, up to its bound"() {
        expect:
        new TermMeaning(value).value() == value

        where:
        value << ["A charge, a refund or an invoice is what is disputed.", " Spaced  as typed ",
                  "a" + Character.toString(0x200D) + "b", "i".repeat(512), Character.toString(0x1F600).repeat(512)]
    }

    def "every rule what a term means is held to refuses under its own name"() {
        when:
        new TermMeaning(value)

        then:
        def refused = thrown(expectedException)
        refused.message == expectedMessage

        where:
        value                             || expectedException        | expectedMessage
        null                              || NullPointerException     | "TermMeaning must not be null"
        "One\ntwo"                        || IllegalArgumentException | "TermMeaning must not contain a control, line or paragraph separator, or surrogate character, but the one at index 3 is U+000A"
        "One\rtwo"                        || IllegalArgumentException | "TermMeaning must not contain a control, line or paragraph separator, or surrogate character, but the one at index 3 is U+000D"
        "A" + Character.toString(0x202D)  || IllegalArgumentException | "TermMeaning must not contain a direction control, but the one at index 1 is U+202D"
        "A" + Character.toString(0xE0020) || IllegalArgumentException | "TermMeaning must not contain a tag character, but the one at index 1 is U+E0020"
        ""                                || IllegalArgumentException | "TermMeaning must not be empty"
        "   "                             || IllegalArgumentException | "TermMeaning must hold something other than space, format and tag characters"
        "i".repeat(513)                   || IllegalArgumentException | "TermMeaning must be at most 512 characters, but this one is 513"
    }

    def "no text is judged where there is none"() {
        when:
        TermMeaning.refusalOf(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "TermMeaning text judged must not be null"
    }
}
