package com.lilradish.lite.domain.workflow

import spock.lang.Specification

class StepLabelSpec extends Specification {

    def "a display name keeps its spelling, case and characters exactly"() {
        when:
        def label = new StepLabel(value)

        then:
        label.value() == value
        label == new StepLabel(value)

        where:
        value << ["Risk review", "リスク確認", "Révision", "Re\u0301vision", "Mi\u200Ctra", "\u202ETriage"]
    }

    def "a display name refuses a filled value that cannot be a visible name on one line"() {
        when:
        new StepLabel(value)

        then:
        thrown(expectedException)

        where:
        value                    || expectedException
        null                     || NullPointerException
        ""                       || IllegalArgumentException
        " "                      || IllegalArgumentException
        " Review"                || IllegalArgumentException
        "Review "                || IllegalArgumentException
        "Risk  review"           || IllegalArgumentException
        "Risk\u3000review"        || IllegalArgumentException
        "Risk\treview"           || IllegalArgumentException
        "Risk\nreview"           || IllegalArgumentException
        "Risk\rreview"           || IllegalArgumentException
        "Risk\u2028review"        || IllegalArgumentException
        "\u200B"                 || IllegalArgumentException
        "\u200C \u200D"           || IllegalArgumentException
        "a" * 129                || IllegalArgumentException
        Character.toString(0xD800) || IllegalArgumentException
    }

    def "the length bound counts characters rather than UTF-16 units"() {
        given:
        def character = Character.toString(codePoint)
        def atTheBound = character.repeat(128)

        when:
        def label = new StepLabel(atTheBound)

        then:
        label.value() == atTheBound

        when:
        new StepLabel(character.repeat(129))

        then:
        thrown(IllegalArgumentException)

        where:
        codePoint << [0x61, 0x1F600]
    }
}
