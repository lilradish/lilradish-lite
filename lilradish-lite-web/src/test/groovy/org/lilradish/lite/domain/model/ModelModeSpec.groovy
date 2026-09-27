package org.lilradish.lite.domain.model

import spock.lang.Specification

class ModelModeSpec extends Specification {

    /** The reserved word's neighbours are here so that only the word itself is refused, not what contains it. */
    def "accepts every value of the one shape, from a single letter to the longest, and keeps it as written"() {
        when:
        def mode = new ModelMode(value)

        then:
        mode.value() == value

        where:
        value << [
            "a",
            "z",
            "research",
            "deep_research_v2",
            "a0",
            "a9",
            "a_",
            "a__b",
            "abcdefghijklmnopqrstuvwxyz_0123456789",
            "a" * 63,
            "z" + "_" * 61 + "9",
            "ordinar",
            "ordinary_",
            "ordinary2",
            "extraordinary",
        ]
    }

    /**
     * A neighbour of every admitted range sits one code unit outside it, at the head and inside, so a
     * bound written one wider admits one of these rows. Code points are built rather than typed, so the
     * row shows which one it is.
     */
    def "refuses every value that could not be a mode's name, saying which rule it breaks"() {
        when:
        new ModelMode(value)

        then:
        def error = thrown(expectedException)
        error.message == expectedMessage

        where:
        value                                  || expectedException        | expectedMessage
        null                                   || NullPointerException     | "ModelMode must not be null"
        ""                                     || IllegalArgumentException | "ModelMode must not be empty"
        "a" * 64                               || IllegalArgumentException | "ModelMode must not exceed 63 characters"
        "A" + "a" * 63                         || IllegalArgumentException | "ModelMode must not exceed 63 characters"
        "Research"                             || IllegalArgumentException | "ModelMode must start with a lowercase letter"
        "Ordinary"                             || IllegalArgumentException | "ModelMode must start with a lowercase letter"
        "1research"                            || IllegalArgumentException | "ModelMode must start with a lowercase letter"
        "_research"                            || IllegalArgumentException | "ModelMode must start with a lowercase letter"
        " research"                            || IllegalArgumentException | "ModelMode must start with a lowercase letter"
        Character.toString(0x60) + "research"  || IllegalArgumentException | "ModelMode must start with a lowercase letter"
        Character.toString(0x7B) + "research"  || IllegalArgumentException | "ModelMode must start with a lowercase letter"
        Character.toString(0xA0)               || IllegalArgumentException | "ModelMode must start with a lowercase letter"
        Character.toString(0xFF41) + "b"       || IllegalArgumentException | "ModelMode must start with a lowercase letter"
        "researcH"                             || IllegalArgumentException | "ModelMode must contain only lowercase letters, digits and _"
        "ORDINARY"                             || IllegalArgumentException | "ModelMode must start with a lowercase letter"
        "oRDINARY"                             || IllegalArgumentException | "ModelMode must contain only lowercase letters, digits and _"
        "research "                            || IllegalArgumentException | "ModelMode must contain only lowercase letters, digits and _"
        "deep-research"                        || IllegalArgumentException | "ModelMode must contain only lowercase letters, digits and _"
        "deep.research"                        || IllegalArgumentException | "ModelMode must contain only lowercase letters, digits and _"
        "a" + Character.toString(0x0A) + "b"   || IllegalArgumentException | "ModelMode must contain only lowercase letters, digits and _"
        "a" + Character.toString(0x2F) + "b"   || IllegalArgumentException | "ModelMode must contain only lowercase letters, digits and _"
        "a" + Character.toString(0x3A) + "b"   || IllegalArgumentException | "ModelMode must contain only lowercase letters, digits and _"
        "a" + Character.toString(0x40) + "b"   || IllegalArgumentException | "ModelMode must contain only lowercase letters, digits and _"
        "a" + Character.toString(0x5B) + "b"   || IllegalArgumentException | "ModelMode must contain only lowercase letters, digits and _"
        "a" + Character.toString(0x5E) + "b"   || IllegalArgumentException | "ModelMode must contain only lowercase letters, digits and _"
        "a" + Character.toString(0x60) + "b"   || IllegalArgumentException | "ModelMode must contain only lowercase letters, digits and _"
        "a" + Character.toString(0x7B) + "b"   || IllegalArgumentException | "ModelMode must contain only lowercase letters, digits and _"
        "a" + Character.toString(0x200B) + "b" || IllegalArgumentException | "ModelMode must contain only lowercase letters, digits and _"
        "a" + Character.toString(0x430)        || IllegalArgumentException | "ModelMode must contain only lowercase letters, digits and _"
        "a" + Character.toString(0x660)        || IllegalArgumentException | "ModelMode must contain only lowercase letters, digits and _"
        "a" + Character.toString(0xFF10)       || IllegalArgumentException | "ModelMode must contain only lowercase letters, digits and _"
        "a" + Character.toString(0x1D41A)      || IllegalArgumentException | "ModelMode must contain only lowercase letters, digits and _"
        "ordinary"                             || IllegalArgumentException | "ModelMode must not be ordinary, which names running a model as it is"
    }
}
