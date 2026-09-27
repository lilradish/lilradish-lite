package org.lilradish.lite.domain.declaration

import spock.lang.Specification

class FieldHelpSpec extends Specification {

    def "help is carried exactly as given, spacing and all, up to its bound"() {
        expect:
        new FieldHelp(value).value() == value

        where:
        value << ["As the customer wrote it.", "Trailing ", "h".repeat(512)]
    }

    def "every rule this help delegates refuses under its own name"() {
        when:
        new FieldHelp(value)

        then:
        def refused = thrown(expectedException)
        refused.message == expectedMessage

        where:
        value                                    || expectedException        | expectedMessage
        null                                     || NullPointerException     | "FieldHelp must not be null"
        ""                                       || IllegalArgumentException | "FieldHelp must not be empty"
        "One" + Character.toString(0x09) + "two" || IllegalArgumentException | "FieldHelp must not contain a control, line or paragraph separator, or surrogate character, but the one at index 3 is U+0009"
        "  "                                     || IllegalArgumentException | "FieldHelp must hold something other than space, format and tag characters"
        Character.toString(0x3000) + " "         || IllegalArgumentException | "FieldHelp must hold something other than space, format and tag characters"
        "h".repeat(513)                          || IllegalArgumentException | "FieldHelp must be at most 512 characters, but this one is 513"
    }
}
