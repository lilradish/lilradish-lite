package org.lilradish.lite.domain.declaration

import spock.lang.Specification

class FieldLabelSpec extends Specification {

    /** Nothing is told apart by how a label is spaced, so spacing is carried as typed. */
    def "a label is carried exactly as given, spacing and all, up to its bound"() {
        expect:
        new FieldLabel(value).value() == value

        where:
        value << ["Came in by", " Leading", "Two  spaces", "Mi" + Character.toString(0x200C) + "tra", "l".repeat(128),
                  Character.toString(0x1F600).repeat(128)]
    }

    def "every rule this label delegates refuses under its own name"() {
        when:
        new FieldLabel(value)

        then:
        def refused = thrown(expectedException)
        refused.message == expectedMessage

        where:
        value                                    || expectedException        | expectedMessage
        null                                     || NullPointerException     | "FieldLabel must not be null"
        ""                                       || IllegalArgumentException | "FieldLabel must not be empty"
        "One" + Character.toString(0x0A) + "two" || IllegalArgumentException | "FieldLabel must not contain a control, line or paragraph separator, or surrogate character, but the one at index 3 is U+000A"
        " " + Character.toString(0x200B)         || IllegalArgumentException | "FieldLabel must hold something other than space, format and tag characters"
        Character.toString(0x00A0)               || IllegalArgumentException | "FieldLabel must hold something other than space, format and tag characters"
        "l".repeat(129)                          || IllegalArgumentException | "FieldLabel must be at most 128 characters, but this one is 129"
    }
}
