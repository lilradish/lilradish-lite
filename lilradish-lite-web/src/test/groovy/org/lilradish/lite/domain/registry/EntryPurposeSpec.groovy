package org.lilradish.lite.domain.registry

import spock.lang.Specification

class EntryPurposeSpec extends Specification {

    /** Nothing is told apart by how a purpose is spaced, so spacing is carried as typed and never refused. */
    def "a purpose is carried exactly as given, spacing and all"() {
        expect:
        new EntryPurpose(value).value() == value

        where:
        value << ["Says what a complaint is about.", "Says what it is about.  Then who.", " Leading", "Trailing ",
                  "Wide" + Character.toString(0x3000) + "space", "p".repeat(512)]
    }

    def "every rule this purpose delegates refuses under its own name"() {
        when:
        new EntryPurpose(value)

        then:
        def refused = thrown(expectedException)
        refused.message == expectedMessage

        where:
        value                                            || expectedException        | expectedMessage
        null                                             || NullPointerException     | "EntryPurpose must not be null"
        ""                                               || IllegalArgumentException | "EntryPurpose must not be empty"
        "One" + Character.toString(0x0A) + "two"         || IllegalArgumentException | "EntryPurpose must not contain a control, line or paragraph separator, or surrogate character, but the one at index 3 is U+000A"
        "One" + Character.toString(0x85) + "two"         || IllegalArgumentException | "EntryPurpose must not contain a control, line or paragraph separator, or surrogate character, but the one at index 3 is U+0085"
        "   "                                            || IllegalArgumentException | "EntryPurpose must hold something other than space, format and tag characters"
        Character.toString(0x2007) + Character.toString(0x00A0) || IllegalArgumentException | "EntryPurpose must hold something other than space, format and tag characters"
        "p".repeat(513)                                  || IllegalArgumentException | "EntryPurpose must be at most 512 characters, but this one is 513"
    }
}
