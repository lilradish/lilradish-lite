package org.lilradish.lite.domain.identity

import spock.lang.Specification

class GroupNameSpec extends Specification {

    /**
     * What a deployment calls its groups is the deployment's, so no shape is imposed: a name with a
     * space in it, one written in a script no ASCII rule would admit, and one that is a single word
     * are all names a group is known by.
     */
    def "the name a group is known by is carried exactly as given"() {
        expect:
        new GroupName(value).value() == value

        where:
        value << ["Payroll", "Risk and Controls", "会計チーム", "ops-eu-west"]
    }

    /**
     * Case is carried as it was typed, never folded: whether two names differing only by case may
     * both be held is the store's to judge, and it judges them one name.
     */
    def "a name keeps the case it was typed in, one differing only by case being another value"() {
        given:
        def asWritten = new GroupName("Payroll")

        expect:
        asWritten.value() == "Payroll"
        asWritten != new GroupName("payroll")

        and: "while two spelled the same way are one"
        asWritten == new GroupName("Payroll")
    }

    /** Some scripts need a joiner or a non-joiner between letters, so a format character is kept wherever it sits. */
    def "a name holding a format character beside something that shows is carried exactly as given"() {
        expect:
        new GroupName(value).value() == value

        where:
        value << ["Mi" + Character.toString(0x200C) + "tra", "Pay" + Character.toString(0x200B) + "roll",
                  Character.toString(0x202E) + "Triage", Character.toString(0x200D) + " Triage"]
    }

    /**
     * One row per rule this value delegates, each carrying this type's own name. What each rule
     * refuses is exhausted in LegibilitySpec; what is held here is that all six are asked, in an
     * order where what cannot be one line is reported before the spacing a reader can see.
     */
    def "every rule this name delegates refuses under its own name"() {
        when:
        new GroupName(value)

        then:
        def refused = thrown(expectedException)
        refused.message == expectedMessage

        where:
        value                                          || expectedException        | expectedMessage
        null                                           || NullPointerException     | "GroupName must not be null"
        ""                                             || IllegalArgumentException | "GroupName must not be empty"
        "Pay" + Character.toString(0x0A) + "roll"      || IllegalArgumentException | "GroupName must not contain a control, line or paragraph separator, or surrogate character, but the one at index 3 is U+000A"
        "Pay" + Character.toString(0x2028) + "roll"    || IllegalArgumentException | "GroupName must not contain a control, line or paragraph separator, or surrogate character, but the one at index 3 is U+2028"
        "Risk" + Character.toString(0x3000) + "Controls" || IllegalArgumentException | "GroupName must not contain a space other than U+0020, but the one at index 4 is U+3000"
        " Payroll"                                     || IllegalArgumentException | "GroupName must not begin with a space"
        "Payroll "                                     || IllegalArgumentException | "GroupName must not end with a space"
        "Risk  Controls"                               || IllegalArgumentException | "GroupName must not contain two spaces in a row, but a pair begins at index 4"
        Character.toString(0x200B)                     || IllegalArgumentException | "GroupName must hold something other than space, format and tag characters"
        Character.toString(0x200C) + " " + Character.toString(0x200D) || IllegalArgumentException | "GroupName must hold something other than space, format and tag characters"
        "g".repeat(129)                                || IllegalArgumentException | "GroupName must be at most 128 characters, but this one is 129"
    }

    /**
     * A name is what a person picks a group out by, and a full-width space is what an input method's
     * space bar produces. Refused rather than tidied: this constructor also runs when a row is read
     * back, so tidying here would bring two stored names back as one and hide that it had.
     */
    def "a name spaced by a separator no reader can see is refused while the spelling it renders as stands"() {
        given:
        def honest = "Risk Controls"
        def typed = "Risk" + Character.toString(0x3000) + "Controls"

        when:
        new GroupName(typed)

        then:
        thrown(IllegalArgumentException)

        and: "over two strings that were never equal"
        typed != honest

        when:
        def admitted = new GroupName(honest)

        then:
        admitted.value() == honest
    }

    /**
     * A name that is nothing but spacing is no name, and it is refused by the rule about spaces
     * rather than by the one about emptiness: the value holds something, and what it holds is a
     * space at its first position.
     */
    def "a name that is nothing but spacing is refused rather than stored"() {
        when:
        new GroupName(value)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == message

        where:
        value                      || message
        "   "                      || "GroupName must not begin with a space"
        Character.toString(0x00A0) || "GroupName must not contain a space other than U+0020, but the one at index 0 is U+00A0"
    }

    def "a name exactly at the column's own bound stands, so what is refused is the excess and not the length"() {
        given:
        def longest = "g".repeat(128)

        when:
        def admitted = new GroupName(longest)

        then:
        admitted.value() == longest
    }

    /**
     * Code points rather than UTF-16 units, which is what the column's own bound counts. 128
     * characters outside the basic plane are 256 units, so counting units would refuse a name the
     * column takes.
     */
    def "the bound counts what the column counts, and not what the string is stored as"() {
        given:
        def value = Character.toString(0x1F600).repeat(128)

        when:
        def admitted = new GroupName(value)

        then:
        admitted.value() == value

        when:
        new GroupName(Character.toString(0x1F600).repeat(129))

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "GroupName must be at most 128 characters, but this one is 129"
    }
}
