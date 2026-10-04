package org.lilradish.lite.domain.text

import spock.lang.Specification

class LegibilitySpec extends Specification {


    def "a value the reader cannot see for what it is is refused by position rather than echoed back"() {
        given:
        def value = "handbook" + Character.toString(codePoint) + ".pdf"

        when:
        Legibility.requireVisibleAndWellFormed(value, "UserId")

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message ==
                "UserId must not contain a control, format, tag, line or paragraph separator, or surrogate" +
                " character, but the one at index 8 is U+" + rendered

        and: "in one line holding none of the value, which is the escape an echoed message would be"
        refused.message.readLines().size() == 1
        !refused.message.contains(value)

        where:
        codePoint || rendered
        0x0009    || "0009"
        0x000A    || "000A"
        0x000D    || "000D"
        0x0000    || "0000"
        0x007F    || "007F"
        0x0085    || "0085"
        0x2028    || "2028"
        0x2029    || "2029"
        0x200B    || "200B"
        0x00AD    || "00AD"
        0x202E    || "202E"
        0xFEFF    || "FEFF"
        0xD800    || "D800"
        0xDFFF    || "DFFF"
        0xE0041   || "E0041"
        0xE0000   || "E0000"
        0xE001F   || "E001F"
    }

    def "the asking type names the rule, so the message reads as the caller's own and not as this one's"() {
        when:
        Legibility.requireVisibleAndWellFormed("a" + Character.toString(0x200B) + "b", valueType)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message.startsWith(valueType + " must not contain a control")

        where:
        valueType << ["UserId", "PersonName", "Correlation id", "GroupName"]
    }

    /** Only the specified Unicode categories are refused; other invisible or unassigned characters remain allowed. */
    def "what a reader can see is carried through, this check being no charset"() {
        when:
        Legibility.requireVisibleAndWellFormed(value, "UserId")

        then:
        noExceptionThrown()

        where:
        value << ["handbook/chapter-3.md", "https://example.com/policy", "REF-0000",
                  "CN=Ari Mockvale,OU=DemoGroup", "会計", "😀",
                  "han" + Character.toString(0x3164) + "dbook",
                  "han" + Character.toString(0x034F) + "dbook",
                  "han" + Character.toString(0x2065) + "dbook"]
    }


    def "a space separator is left to the rule about spaces, this check holding no opinion on one"() {
        when:
        Legibility.requireVisibleAndWellFormed(Character.toString(codePoint), "UserId")

        then:
        noExceptionThrown()

        where:
        codePoint << [0x0020, 0x00A0, 0x3000]
    }


    def "a value that is not one well-formed line is refused by position rather than echoed back"() {
        given:
        def value = "Ari" + Character.toString(codePoint) + "Mockvale"

        when:
        Legibility.requireOneWellFormedLine(value, "ListFilter")

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "ListFilter must not contain a control, line or paragraph separator, or surrogate" +
                " character, but the one at index 3 is U+" + rendered

        and:
        refused.message.readLines().size() == 1
        !refused.message.contains(value)

        where:
        codePoint || rendered
        0x0000    || "0000"
        0x0009    || "0009"
        0x000A    || "000A"
        0x0085    || "0085"
        0x2028    || "2028"
        0x2029    || "2029"
        0xD800    || "D800"
        0xDFFF    || "DFFF"
    }

    /** The compared value allows format characters used in names, including bidirectional controls. */
    def "a format character is carried as one line of text, where the stricter rule refuses it"() {
        given:
        def value = "Ari" + Character.toString(codePoint) + "Mockvale"

        when:
        Legibility.requireOneWellFormedLine(value, "ListFilter")

        then:
        noExceptionThrown()

        when:
        Legibility.requireVisibleAndWellFormed(value, "ListFilter")

        then:
        thrown(IllegalArgumentException)

        where:
        codePoint << [0x200C, 0x200D, 0x00AD, 0x202E, 0xFEFF, 0xE0041]
    }


    def "prose refusing what one line refuses, a tab and a line feed apart, is refused by position"() {
        given:
        def value = "Say\twhy.\n" + Character.toString(codePoint)

        when:
        Legibility.requireWellFormedProse(value, "Instruction")

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "Instruction must not contain a control other than a tab or a line feed, a line or" +
                " paragraph separator, or surrogate character, but the one at index 9 is U+" + rendered

        and:
        refused.message.readLines().size() == 1
        !refused.message.contains("Say")

        where:
        codePoint || rendered
        0x000D    || "000D"
        0x0000    || "0000"
        0x000B    || "000B"
        0x007F    || "007F"
        0x0085    || "0085"
        0x2028    || "2028"
        0x2029    || "2029"
        0xD800    || "D800"
    }


    def "prose carries a tab, a line feed, a joiner, a non-joiner and a soft hyphen"() {
        when:
        Legibility.requireWellFormedProse("Say" + Character.toString(codePoint) + "why.", "Instruction")

        then:
        noExceptionThrown()

        where:
        codePoint << [0x0009, 0x000A, 0x200C, 0x200D, 0x00AD, 0x2065, 0x202F, 0xE0080]
    }


    def "prose holding a direction control or a tag character is refused, naming which, by position"() {
        when:
        Legibility.requireWellFormedProse("Say" + Character.toString(codePoint) + "why.", "Instruction")

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "Instruction must not contain a " + found + ", but the one at index 3 is U+" + rendered

        where:
        codePoint || found               | rendered
        0x202A    || "direction control" | "202A"
        0x202E    || "direction control" | "202E"
        0x2066    || "direction control" | "2066"
        0x2069    || "direction control" | "2069"
        0xE0000   || "tag character"     | "E0000"
        0xE0041   || "tag character"     | "E0041"
        0xE007F   || "tag character"     | "E007F"
    }

    def "a direction control or a tag character is refused of one line, naming which, by position"() {
        when:
        Legibility.requireNothingConcealed("Bill" + Character.toString(codePoint) + "ing", "Term")

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "Term must not contain a " + found + ", but the one at index 4 is U+" + rendered

        where:
        codePoint || found               | rendered
        0x202A    || "direction control" | "202A"
        0x2069    || "direction control" | "2069"
        0xE0000   || "tag character"     | "E0000"
        0xE007F   || "tag character"     | "E007F"
    }


    def "a format character that conceals nothing passes the concealing rule"() {
        when:
        Legibility.requireNothingConcealed("Bill" + Character.toString(codePoint) + "ing", "Term")

        then:
        noExceptionThrown()

        where:
        codePoint << [0x200B, 0x00AD, 0x2065, 0x2029, 0xE0080]
    }

    def "a value holds what shows nothing exactly where it holds a default ignorable code point but a joiner"() {
        expect:
        Legibility.holdsInvisible(value) == holds

        where:
        value                                        || holds
        "Billing"                                    || false
        ""                                           || false
        "Mi" + Character.toString(0x200C) + "tra"    || false
        "a" + Character.toString(0x200D) + "b"       || false
        "Bill" + Character.toString(0x200B) + "ing"  || true
        "Billing" + Character.toString(0x00AD)       || true
        "Bill" + Character.toString(0xE0100) + "ing" || true
    }

    def "a value holding what shows nothing is refused, naming the first by position"() {
        when:
        Legibility.requireNothingInvisible(
                "Bill" + Character.toString(codePoint) + "ing" + Character.toString(0x200B), "Term")

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "Term must not contain a character that shows nothing, but the one at index 4 is U+" + rendered

        where:
        codePoint || rendered
        0x00AD    || "00AD"
        0x3164    || "3164"
        0xFE0F    || "FE0F"
        0x1D173   || "1D173"
        0xE0FFF   || "E0FFF"
    }

    def "a value holding only what shows, or a joiner, passes the rule about what shows nothing"() {
        when:
        Legibility.requireNothingInvisible(value, "Term")

        then:
        noExceptionThrown()

        where:
        value << ["Billing", "Mi" + Character.toString(0x200C) + "tra", "a" + Character.toString(0x200D) + "b",
                  Character.toString(0x00A0), Character.toString(0x1F600)]
    }

    def "prose holding more than one refused character is refused for the first of them, whatever each is"() {
        when:
        Legibility.requireWellFormedProse(value, "Instruction")

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message.startsWith("Instruction must not contain a " + found)
        refused.message.endsWith(", but the one at index 3 is U+" + rendered)
        !refused.message.contains(unreported)

        where:
        value                                                        || found               | rendered | unreported
        "Say" + Character.toString(0xE0041) + "\r"                   || "tag character"     | "E0041"  | "000D"
        "Say\r" + Character.toString(0x202E)                         || "control other"     | "000D"   | "202E"
        "Say" + Character.toString(0x2066) + Character.toString(0x0) || "direction control" | "2066"   | "0000"
    }


    def "a value holds something that shows exactly where it holds anything but spaces, format and tag characters"() {
        expect:
        Legibility.holdsSomethingVisible(value) == shows

        where:
        value                                                             || shows
        "Ari"                                                             || true
        Character.toString(0x200C) + "a"                                  || true
        Character.toString(0x0301)                                        || true
        Character.toString(0x1F600)                                       || true
        Character.toString(0x3000) + "a"                                  || true
        "\t\n"                                                            || true
        ""                                                                || false
        " "                                                               || false
        Character.toString(0x3000)                                        || false
        Character.toString(0x00A0) + Character.toString(0x2007)           || false
        Character.toString(0x200B)                                        || false
        Character.toString(0x200C) + " " + Character.toString(0xE0041)    || false
        Character.toString(0xE0000) + Character.toString(0xE001F)         || false
    }

    def "a value holding nothing that shows is refused under the asking type's name"() {
        when:
        Legibility.requireSomethingVisible(value, valueType)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == valueType + " must hold something other than space, format and tag characters"

        where:
        valueType    | value
        "PersonName" | " "
        "GroupName"  | Character.toString(0x200B) + Character.toString(0x200D)
        "FieldHelp"  | Character.toString(0x00A0)
    }

    def "a value holding something that shows passes the rule, whatever else it holds"() {
        when:
        Legibility.requireSomethingVisible("Mi" + Character.toString(0x200C) + "tra", "GroupName")

        then:
        noExceptionThrown()
    }

    def "prose holds something that shows exactly where it holds anything but tabs, line feeds, spaces, format and tag characters"() {
        expect:
        Legibility.holdsSomethingVisibleInProse(value) == shows

        where:
        value                                                             || shows
        "\n\tSay why."                                                    || true
        Character.toString(0x0301)                                        || true
        "\n" + Character.toString(0x3000) + "a"                           || true
        ""                                                                || false
        "\t \n"                                                           || false
        Character.toString(0x200B) + "\n" + Character.toString(0x00A0)    || false
        Character.toString(0xE0041) + "\t"                                || false
    }

    def "prose of tabs, line feeds, spaces and format characters alone is refused as showing nothing"() {
        when:
        Legibility.requireSomethingVisibleInProse(value, "Instruction")

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message ==
                "Instruction must hold something other than space, tab, line feed, format and tag characters"

        where:
        value << ["\n\n", "\t \n", Character.toString(0x200B) + "\n", "", "\n" + Character.toString(0x3000),
                  Character.toString(0x00A0)]
    }

    def "prose holding anything else passes, where the one-line rule counts a tab and a line feed as showing"() {
        when:
        Legibility.requireSomethingVisibleInProse(value, "Instruction")

        then:
        noExceptionThrown()
        Legibility.holdsSomethingVisible("\t\n")

        where:
        value << ["\n\tSay why.", Character.toString(0x0301), "\n" + Character.toString(0x3000) + "Say why."]
    }

    /** An empty value contains no spaces; emptiness therefore needs its own check. */
    def "a value holding nothing at all is refused, whichever caller is asking"() {
        when:
        Legibility.requireNotEmpty("", valueType)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == valueType + " must not be empty"

        where:
        valueType << ["UserId", "PersonName", "Correlation id", "GroupName"]
    }

    /** Identifier spacing must not change how a reader identifies the value. */
    def "a space that is not U+0020 is refused wherever it sits, by position rather than echoed back"() {
        given:
        def value = "clerk" + Character.toString(codePoint) + "0021"

        when:
        Legibility.requireSpaceDecidesNothing(value, "UserId")

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message ==
                "UserId must not contain a space other than U+0020, but the one at index 5 is U+" + rendered

        and: "in one line holding none of the value, which is the escape an echoed message would be"
        refused.message.readLines().size() == 1
        !refused.message.contains(value)

        where:
        codePoint || rendered
        0x00A0    || "00A0"
        0x1680    || "1680"
        0x2000    || "2000"
        0x2001    || "2001"
        0x2002    || "2002"
        0x2003    || "2003"
        0x2004    || "2004"
        0x2005    || "2005"
        0x2006    || "2006"
        0x2007    || "2007"
        0x2008    || "2008"
        0x2009    || "2009"
        0x200A    || "200A"
        0x202F    || "202F"
        0x205F    || "205F"
        0x3000    || "3000"
    }


    def "a single U+0020 between words is carried, which is what makes the refusals ones of the spelling"() {
        when:
        Legibility.requireSpaceDecidesNothing(value, "UserId")

        then:
        noExceptionThrown()

        where:
        value << ["Ari" + Character.toString(0x0020) + "Mockvale", "Risk and Controls", "004821"]
    }


    def "a value U+0020 sits at either end of is refused, and the end holding it is named"() {
        when:
        Legibility.requireSpaceDecidesNothing(value, "UserId")

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == message

        where:
        value      || message
        " 004821"  || "UserId must not begin with a space"
        "004821 "  || "UserId must not end with a space"
        " 004821 " || "UserId must not begin with a space"
        " "        || "UserId must not begin with a space"
        "   "      || "UserId must not begin with a space"
    }


    def "a value carrying two spaces in a row is refused, and the pair is named by position"() {
        when:
        Legibility.requireSpaceDecidesNothing(value, "UserId")

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message ==
                "UserId must not contain two spaces in a row, but a pair begins at index " + index

        where:
        value               || index
        "Risk  Controls"    || 4
        "Risk   Controls"   || 4
        "a  b  c"           || 1
        "Risk and  Controls" || 8
    }


    def "a value with a hidden space and a trailing one is reported by the hidden one"() {
        when:
        Legibility.requireSpaceDecidesNothing("00" + Character.toString(0x00A0) + "4821 ", "UserId")

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "UserId must not contain a space other than U+0020, but the one at index 2 is U+00A0"
    }

    /** The spacing check accepts empty values without reading a nonexistent code point. */
    def "a value holding nothing at all holds no space either, so this rule has nothing to say about it"() {
        when:
        Legibility.requireSpaceDecidesNothing("", "UserId")

        then:
        noExceptionThrown()
    }

    def "a value is within its maximum up to exactly that many code points, a supplementary character counting once"() {
        when:
        def within = Legibility.withinMaximumLength(value, maximumLength)

        then:
        within == expected

        where:
        value                                       | maximumLength || expected
        ""                                          | 0             || true
        "g"                                         | 0             || false
        "ggg"                                       | 3             || true
        "gggg"                                      | 3             || false
        Character.toString(0x1F600).repeat(3)       | 3             || true
        "g" + Character.toString(0x1F600).repeat(2) | 3             || true
        "g".repeat(7)                               | 3             || false
        "g" + Character.toString(0x1F600).repeat(3) | 3             || false
    }

    def "a value longer than the stated maximum is refused, and one exactly at it is not"() {
        when:
        Legibility.requireWithinMaximumLength("g".repeat(129), 128, "GroupName")

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "GroupName must be at most 128 characters, but this one is 129"

        when: "the boundary itself, so what is refused is the excess rather than the length"
        Legibility.requireWithinMaximumLength("g".repeat(128), 128, "GroupName")

        then:
        noExceptionThrown()
    }

    /** Count code points like the database column bound, rather than UTF-16 units. */
    def "the maximum counts what a column counts, and not what the string is stored as"() {
        when:
        Legibility.requireWithinMaximumLength(Character.toString(0x1F600).repeat(128), 128, "GroupName")

        then:
        noExceptionThrown()

        when:
        Legibility.requireWithinMaximumLength(Character.toString(0x1F600).repeat(129), 128, "GroupName")

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "GroupName must be at most 128 characters, but this one is 129"
    }

    def "the bound and the asking type are both the caller's, neither of them fixed here"() {
        when:
        Legibility.requireWithinMaximumLength("a".repeat(257), maximumLength, valueType)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == valueType + " must be at most " + maximumLength + " characters, but this one is 257"

        where:
        valueType        | maximumLength
        "UserId"     | 256
        "Correlation id" | 64
        "GroupName"      | 128
    }
}
