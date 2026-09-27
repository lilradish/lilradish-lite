package org.lilradish.lite.domain.registry

import spock.lang.Specification

class EntryNameSpec extends Specification {

    def "the name an entry is known by is carried exactly as given"() {
        expect:
        new EntryName(value).value() == value

        where:
        value << ["Summarise a complaint", "苦情の要約", "triage",
                  "Mi" + Character.toString(0x200C) + "tra", Character.toString(0x202E) + "Triage"]
    }

    /** Whether two names differing only by case are one name is the store's to judge, never folded here. */
    def "a name keeps the case it was typed in, one differing only by case being another value"() {
        expect:
        new EntryName("Triage") != new EntryName("triage")
        new EntryName("Triage") == new EntryName("Triage")
    }

    /**
     * One row per rule this value delegates, each carrying this type's own name. What each rule refuses
     * is exhausted in LegibilitySpec; what is held here is that all six are asked.
     */
    def "every rule this name delegates refuses under its own name"() {
        when:
        new EntryName(value)

        then:
        def refused = thrown(expectedException)
        refused.message == expectedMessage

        where:
        value                                                         || expectedException        | expectedMessage
        null                                                          || NullPointerException     | "EntryName must not be null"
        ""                                                            || IllegalArgumentException | "EntryName must not be empty"
        "Tri" + Character.toString(0x0A) + "age"                      || IllegalArgumentException | "EntryName must not contain a control, line or paragraph separator, or surrogate character, but the one at index 3 is U+000A"
        "Tri" + Character.toString(0x2029) + "age"                    || IllegalArgumentException | "EntryName must not contain a control, line or paragraph separator, or surrogate character, but the one at index 3 is U+2029"
        "Handle" + Character.toString(0x00A0) + "it"                  || IllegalArgumentException | "EntryName must not contain a space other than U+0020, but the one at index 6 is U+00A0"
        " Triage"                                                     || IllegalArgumentException | "EntryName must not begin with a space"
        "Triage "                                                     || IllegalArgumentException | "EntryName must not end with a space"
        "Handle  it"                                                  || IllegalArgumentException | "EntryName must not contain two spaces in a row, but a pair begins at index 6"
        Character.toString(0x200C) + " " + Character.toString(0x200D) || IllegalArgumentException | "EntryName must hold something other than space, format and tag characters"
        "e".repeat(129)                                               || IllegalArgumentException | "EntryName must be at most 128 characters, but this one is 129"
    }

    /** Code points, as the column's own bound counts them, and not the UTF-16 units they are held in. */
    def "a name exactly at the column's bound stands, counted in what the column counts"() {
        expect:
        new EntryName(value).value() == value

        where:
        value << ["e".repeat(128), Character.toString(0x1F600).repeat(128)]
    }
}
