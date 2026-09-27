package org.lilradish.lite.domain.identity

import spock.lang.Specification

class UserIdSpec extends Specification {

    /**
     * More than one shape is in play at once, because the shape is whatever identity provider a
     * deployer runs. Asserted rather than left implicit because a rule admitting only letters,
     * digits and dot-dash-underscore would refuse two of the three, and that is the tightening
     * somebody who has seen only one deployment would reach for.
     */
    def "every shape a provider may assert is carried exactly as given"() {
        expect:
        new UserId(value).value() == value

        where:
        value << ["overseer@example.com", "CN=Ada Lovelace,OU=Analytics", "004821"]
    }

    /**
     * One row per rule this value delegates, each carrying this type's own name. What each rule
     * refuses is exhausted in LegibilitySpec; what is held here is that all five are asked, in an
     * order where the category a reader cannot see is reported before the spacing they can.
     */
    def "every rule this identifier delegates refuses under its own name"() {
        when:
        new UserId(value)

        then:
        def refused = thrown(expectedException)
        refused.message == expectedMessage

        where:
        value                                                    || expectedException        | expectedMessage
        null                                                     || NullPointerException     | "UserId must not be null"
        ""                                                       || IllegalArgumentException | "UserId must not be empty"
        "overseer" + Character.toString(0x200B) + "@example.test" || IllegalArgumentException | "UserId must not contain a control, format, tag, line or paragraph separator, or surrogate character, but the one at index 8 is U+200B"
        "overseer" + Character.toString(0xE0000) + "@example.test" || IllegalArgumentException | "UserId must not contain a control, format, tag, line or paragraph separator, or surrogate character, but the one at index 8 is U+E0000"
        "00" + Character.toString(0x00A0) + "4821"               || IllegalArgumentException | "UserId must not contain a space other than U+0020, but the one at index 2 is U+00A0"
        " 004821"                                                || IllegalArgumentException | "UserId must not begin with a space"
        "004821 "                                                || IllegalArgumentException | "UserId must not end with a space"
        "00  4821"                                               || IllegalArgumentException | "UserId must not contain two spaces in a row, but a pair begins at index 2"
        "4".repeat(257)                                          || IllegalArgumentException | "UserId must be at most 256 characters, but this one is 257"
    }

    /**
     * The forgery a format character buys, stated as the pair it turns on: the two are different
     * strings that render identically, so a unique index holding both holds one user twice. The
     * honest one is admitted, which is what makes the refusal one of the hiding alone.
     */
    def "an identifier hidden behind a zero-width character is refused while the one it renders as stands"() {
        given:
        def honest = "overseer@example.test"
        def hidden = "overseer" + Character.toString(0x200B) + "@example.test"

        when:
        new UserId(hidden)

        then:
        thrown(IllegalArgumentException)

        and: "over two strings that were never equal, which is the whole of how an index tells them apart"
        hidden != honest

        when:
        def admitted = new UserId(honest)

        then:
        admitted.value() == honest
    }

    /**
     * The same forgery in the category the rule above leaves alone, built in the middle where a
     * guard on the two ends alone never reached it: a payroll number spaced by U+00A0 renders as
     * the honest one and satisfies the unique index beside it.
     */
    def "an identifier an invisible space tells apart from an honest one is refused while that one stands"() {
        given:
        def honest = "00 1234"
        def twin = "00" + Character.toString(0x00A0) + "1234"

        when:
        new UserId(twin)

        then:
        thrown(IllegalArgumentException)

        and: "over two strings that were never equal, which is the whole of how an index tells them apart"
        twin != honest

        when:
        def admitted = new UserId(honest)

        then:
        admitted.value() == honest
    }

    /**
     * And the twin built out of the space the rule keeps. Nobody counts the gap, so the doubled
     * spelling is the honest one to every reader and a second row to the store.
     */
    def "an identifier doubling an honest one's space is refused while that one stands"() {
        given:
        def honest = "00 1234"
        def twin = "00  1234"

        when:
        new UserId(twin)

        then:
        thrown(IllegalArgumentException)

        and: "over two strings that were never equal, which is the whole of how an index tells them apart"
        twin != honest

        when:
        def admitted = new UserId(honest)

        then:
        admitted.value() == honest
    }

    def "an identifier exactly at the maximum stands, so what is refused is the excess and not the length"() {
        given:
        def longest = "4".repeat(256)

        when:
        def admitted = new UserId(longest)

        then:
        admitted.value() == longest
    }

    /**
     * Code points rather than UTF-16 units, which is what the database's own bound counts. 256
     * characters outside the basic plane are 512 units, so counting units would refuse an
     * identifier the column takes.
     */
    def "the maximum counts what the database counts, and not what the string is stored as"() {
        given:
        def value = Character.toString(0x1F600).repeat(256)

        when:
        def admitted = new UserId(value)

        then:
        admitted.value() == value

        when:
        new UserId(Character.toString(0x1F600).repeat(257))

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "UserId must be at most 256 characters, but this one is 257"
    }
}
