package org.lilradish.lite.domain.declaration

import spock.lang.Specification

class InstructionSpec extends Specification {

    /** Prose over lines, indented with tabs where its writer wanted, carried exactly as given. */
    def "an instruction is carried exactly as given, lines and tabs and all, up to its bound"() {
        expect:
        new Instruction(value).value() == value

        where:
        value << ["Summarise the complaint.", "Say which category.\nLeave the order empty where none is given.",
                  "\tIndented.\n\n", "i".repeat(8192), Character.toString(0x1F600).repeat(8192)]
    }

    /** A carriage return is refused rather than rewritten: a page's text box never sends one. */
    def "every rule this instruction delegates refuses under its own name"() {
        when:
        new Instruction(value)

        then:
        def refused = thrown(expectedException)
        refused.message == expectedMessage

        where:
        value                 || expectedException        | expectedMessage
        null                  || NullPointerException     | "Instruction must not be null"
        "One\r\ntwo"          || IllegalArgumentException | "Instruction must not contain a control other than a tab or a line feed, a line or paragraph separator, or surrogate character, but the one at index 3 is U+000D"
        "A" + Character.toString(0x202E) || IllegalArgumentException | "Instruction must not contain a direction control, but the one at index 1 is U+202E"
        "A" + Character.toString(0xE0041) || IllegalArgumentException | "Instruction must not contain a tag character, but the one at index 1 is U+E0041"
        ""                    || IllegalArgumentException | "Instruction must hold something other than space, tab, line feed, format and tag characters"
        "\n \t\n"             || IllegalArgumentException | "Instruction must hold something other than space, tab, line feed, format and tag characters"
        Character.toString(0x00A0) + "\n" + Character.toString(0x3000) || IllegalArgumentException | "Instruction must hold something other than space, tab, line feed, format and tag characters"
        "i".repeat(8193)      || IllegalArgumentException | "Instruction must be at most 8192 characters, but this one is 8193"
    }
}
