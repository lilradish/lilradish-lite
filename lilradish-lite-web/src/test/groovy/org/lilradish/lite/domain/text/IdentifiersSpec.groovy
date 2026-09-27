package org.lilradish.lite.domain.text

import spock.lang.Specification

class IdentifiersSpec extends Specification {

    def "hands back the very string it was given, unnormalised"() {
        given:
        def value = "ingest_v2"

        when:
        def result = Identifiers.requireText(value, "StepId")

        then:
        result.is(value)
    }

    def "carries the asking type into the rejection message rather than a fixed label"() {
        when:
        Identifiers.requireText("a\nb", identifierType)

        then:
        def error = thrown(IllegalArgumentException)
        error.message == identifierType + " must contain only lowercase letters, digits and _"

        where:
        identifierType << ["StepId", "Code step", "FieldName"]
    }

    /**
     * A neighbour of every admitted range sits one code unit outside it, at the head and inside, so a
     * bound written one wider admits one of these rows.
     */
    def "refuses every value that could not be a safe identifier"() {
        when:
        Identifiers.requireText(value, "StepId")

        then:
        def error = thrown(expectedException)
        error.message == expectedMessage

        where:
        value             || expectedException        | expectedMessage
        null              || NullPointerException     | "StepId must not be null"
        ""                || IllegalArgumentException | "StepId must not be empty"
        "a" * 64          || IllegalArgumentException | "StepId must not exceed 63 characters"
        "A" + "a" * 63    || IllegalArgumentException | "StepId must not exceed 63 characters"
        "Extract"         || IllegalArgumentException | "StepId must start with a lowercase letter"
        "extrAct"         || IllegalArgumentException | "StepId must contain only lowercase letters, digits and _"
        "extracT"         || IllegalArgumentException | "StepId must contain only lowercase letters, digits and _"
        "1extract"        || IllegalArgumentException | "StepId must start with a lowercase letter"
        "_extract"        || IllegalArgumentException | "StepId must start with a lowercase letter"
        "\u0060extract"   || IllegalArgumentException | "StepId must start with a lowercase letter"
        "\u007Bextract"   || IllegalArgumentException | "StepId must start with a lowercase letter"
        " extract"        || IllegalArgumentException | "StepId must start with a lowercase letter"
        "\u00A0"          || IllegalArgumentException | "StepId must start with a lowercase letter"
        "\uFF41b"         || IllegalArgumentException | "StepId must start with a lowercase letter"
        "extract "        || IllegalArgumentException | "StepId must contain only lowercase letters, digits and _"
        "extract-v2"      || IllegalArgumentException | "StepId must contain only lowercase letters, digits and _"
        "org.lilradish"   || IllegalArgumentException | "StepId must contain only lowercase letters, digits and _"
        "team/summarise"  || IllegalArgumentException | "StepId must contain only lowercase letters, digits and _"
        "prompt:v1"       || IllegalArgumentException | "StepId must contain only lowercase letters, digits and _"
        "extr\u200Bact"   || IllegalArgumentException | "StepId must contain only lowercase letters, digits and _"
        "p\u0430ypal"     || IllegalArgumentException | "StepId must contain only lowercase letters, digits and _"
        "extract\nfake"   || IllegalArgumentException | "StepId must contain only lowercase letters, digits and _"
        "a\u0060b"        || IllegalArgumentException | "StepId must contain only lowercase letters, digits and _"
        "a\u007Bb"        || IllegalArgumentException | "StepId must contain only lowercase letters, digits and _"
        "a\u002Fb"        || IllegalArgumentException | "StepId must contain only lowercase letters, digits and _"
        "a\u003Ab"        || IllegalArgumentException | "StepId must contain only lowercase letters, digits and _"
        "a\u005Eb"        || IllegalArgumentException | "StepId must contain only lowercase letters, digits and _"
        "a\u0040b"        || IllegalArgumentException | "StepId must contain only lowercase letters, digits and _"
        "a\u005Bb"        || IllegalArgumentException | "StepId must contain only lowercase letters, digits and _"
        "a\uD835\uDC1A"   || IllegalArgumentException | "StepId must contain only lowercase letters, digits and _"
        "a\u0660"         || IllegalArgumentException | "StepId must contain only lowercase letters, digits and _"
        "a\uFF10"         || IllegalArgumentException | "StepId must contain only lowercase letters, digits and _"
    }

    def "accepts every value of the one shape, from a single letter to the longest"() {
        when:
        def result = Identifiers.requireText(value, "FieldName")

        then:
        result.is(value)

        where:
        value << [
            "a",
            "z",
            "extract",
            "summarise_v2",
            "a0",
            "a9",
            "a_",
            "a__b",
            "abcdefghijklmnopqrstuvwxyz_0123456789",
            "a" * 63,
            "z" + "_" * 61 + "9",
        ]
    }
}
