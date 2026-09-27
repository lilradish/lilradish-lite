package org.lilradish.lite.domain.run

import spock.lang.Specification

class RunNameSpec extends Specification {

    /** Two runs may share a name, so nothing is told apart by how one is spaced. */
    def "a name is carried exactly as given, spacing and all"() {
        expect:
        new RunName(value).value() == value

        where:
        value << ["Complaint from Ada", "Two  spaces", " Leading", "Trailing ", "n" * 128,
                  "Wide" + Character.toString(0x3000) + "space", Character.toString(0x1F600) * 128]
    }

    def "every rule this name delegates refuses under its own name"() {
        when:
        new RunName(value)

        then:
        def refused = thrown(expectedException)
        refused.message == expectedMessage

        where:
        value                                    || expectedException        | expectedMessage
        null                                     || NullPointerException     | "RunName must not be null"
        ""                                       || IllegalArgumentException | "RunName must not be empty"
        "One" + Character.toString(0x0A) + "two" || IllegalArgumentException | "RunName must not contain a control, line or paragraph separator, or surrogate character, but the one at index 3 is U+000A"
        "One" + Character.toString(0x2028)       || IllegalArgumentException | "RunName must not contain a control, line or paragraph separator, or surrogate character, but the one at index 3 is U+2028"
        "   "                                    || IllegalArgumentException | "RunName must hold something other than space, format and tag characters"
        Character.toString(0x3000)               || IllegalArgumentException | "RunName must hold something other than space, format and tag characters"
        "n" * 129                                || IllegalArgumentException | "RunName must be at most 128 characters, but this one is 129"
    }
}
