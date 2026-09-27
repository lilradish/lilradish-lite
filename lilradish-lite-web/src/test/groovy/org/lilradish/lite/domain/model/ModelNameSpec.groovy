package org.lilradish.lite.domain.model

import spock.lang.Specification

class ModelNameSpec extends Specification {

    def "accepts every value of the one shape, from a single letter to the longest, and keeps it as written"() {
        when:
        def name = new ModelName(value)

        then:
        name.value() == value

        where:
        value << [
            "a",
            "z",
            "sample_model",
            "model_v2",
            "a0",
            "a9",
            "a_",
            "a__b",
            "abcdefghijklmnopqrstuvwxyz_0123456789",
            "a" * 63,
            "z" + "_" * 61 + "9",
        ]
    }

    /**
     * A neighbour of every admitted range sits one code unit outside it, at the head and inside, so a
     * bound written one wider admits one of these rows. Code points are built rather than typed, so the
     * row shows which one it is.
     */
    def "refuses every value that could not be a model's name, saying which rule it breaks"() {
        when:
        new ModelName(value)

        then:
        def error = thrown(expectedException)
        error.message == expectedMessage

        where:
        value                                  || expectedException        | expectedMessage
        null                                   || NullPointerException     | "ModelName must not be null"
        ""                                     || IllegalArgumentException | "ModelName must not be empty"
        "a" * 64                               || IllegalArgumentException | "ModelName must not exceed 63 characters"
        "A" + "a" * 63                         || IllegalArgumentException | "ModelName must not exceed 63 characters"
        "Sample_model"                         || IllegalArgumentException | "ModelName must start with a lowercase letter"
        "1model"                               || IllegalArgumentException | "ModelName must start with a lowercase letter"
        "_model"                               || IllegalArgumentException | "ModelName must start with a lowercase letter"
        " model"                               || IllegalArgumentException | "ModelName must start with a lowercase letter"
        Character.toString(0x60) + "model"     || IllegalArgumentException | "ModelName must start with a lowercase letter"
        Character.toString(0x7B) + "model"     || IllegalArgumentException | "ModelName must start with a lowercase letter"
        Character.toString(0xA0)               || IllegalArgumentException | "ModelName must start with a lowercase letter"
        Character.toString(0xFF41) + "b"       || IllegalArgumentException | "ModelName must start with a lowercase letter"
        "sample_Model"                         || IllegalArgumentException | "ModelName must contain only lowercase letters, digits and _"
        "sample_modeL"                         || IllegalArgumentException | "ModelName must contain only lowercase letters, digits and _"
        "sample_model "                        || IllegalArgumentException | "ModelName must contain only lowercase letters, digits and _"
        "sample-model"                         || IllegalArgumentException | "ModelName must contain only lowercase letters, digits and _"
        "sample.model"                         || IllegalArgumentException | "ModelName must contain only lowercase letters, digits and _"
        "a" + Character.toString(0x0A) + "b"   || IllegalArgumentException | "ModelName must contain only lowercase letters, digits and _"
        "a" + Character.toString(0x2F) + "b"   || IllegalArgumentException | "ModelName must contain only lowercase letters, digits and _"
        "a" + Character.toString(0x3A) + "b"   || IllegalArgumentException | "ModelName must contain only lowercase letters, digits and _"
        "a" + Character.toString(0x40) + "b"   || IllegalArgumentException | "ModelName must contain only lowercase letters, digits and _"
        "a" + Character.toString(0x5B) + "b"   || IllegalArgumentException | "ModelName must contain only lowercase letters, digits and _"
        "a" + Character.toString(0x5E) + "b"   || IllegalArgumentException | "ModelName must contain only lowercase letters, digits and _"
        "a" + Character.toString(0x60) + "b"   || IllegalArgumentException | "ModelName must contain only lowercase letters, digits and _"
        "a" + Character.toString(0x7B) + "b"   || IllegalArgumentException | "ModelName must contain only lowercase letters, digits and _"
        "a" + Character.toString(0x200B) + "b" || IllegalArgumentException | "ModelName must contain only lowercase letters, digits and _"
        "a" + Character.toString(0x430)        || IllegalArgumentException | "ModelName must contain only lowercase letters, digits and _"
        "a" + Character.toString(0x660)        || IllegalArgumentException | "ModelName must contain only lowercase letters, digits and _"
        "a" + Character.toString(0xFF10)       || IllegalArgumentException | "ModelName must contain only lowercase letters, digits and _"
        "a" + Character.toString(0x1D41A)      || IllegalArgumentException | "ModelName must contain only lowercase letters, digits and _"
    }
}
