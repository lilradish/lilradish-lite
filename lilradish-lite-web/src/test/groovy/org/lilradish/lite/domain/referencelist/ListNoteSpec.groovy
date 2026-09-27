package org.lilradish.lite.domain.referencelist

import spock.lang.Specification

class ListNoteSpec extends Specification {

    /** Prose over lines, indented with tabs where its writer wanted, carried exactly as given. */
    def "a note is carried exactly as given, lines and tabs and all, up to its bound"() {
        expect:
        new ListNote(value).value() == value

        where:
        value << ["Choose the nearest.", "Choose the nearest.\n\tAsk where none is near.", "\tIndented.\n\n",
                  "i".repeat(2048), Character.toString(0x1F600).repeat(2048)]
    }

    /** A carriage return is refused rather than rewritten: a page's text box never sends one. */
    def "every rule a note is held to refuses under its own name"() {
        when:
        new ListNote(value)

        then:
        def refused = thrown(expectedException)
        refused.message == expectedMessage

        where:
        value                             || expectedException        | expectedMessage
        null                              || NullPointerException     | "ListNote must not be null"
        "One\r\ntwo"                      || IllegalArgumentException | "ListNote must not contain a control other than a tab or a line feed, a line or paragraph separator, or surrogate character, but the one at index 3 is U+000D"
        "A" + Character.toString(0x2067)  || IllegalArgumentException | "ListNote must not contain a direction control, but the one at index 1 is U+2067"
        "A" + Character.toString(0xE0041) || IllegalArgumentException | "ListNote must not contain a tag character, but the one at index 1 is U+E0041"
        ""                                || IllegalArgumentException | "ListNote must hold something other than space, tab, line feed, format and tag characters"
        "\n \t\n"                         || IllegalArgumentException | "ListNote must hold something other than space, tab, line feed, format and tag characters"
        "i".repeat(2049)                  || IllegalArgumentException | "ListNote must be at most 2048 characters, but this one is 2049"
    }

    def "no text is judged where there is none"() {
        when:
        ListNote.refusalOf(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "ListNote text judged must not be null"
    }
}
