package com.lilradish.lite.domain.text

import spock.lang.Specification

class DefaultIgnorableVisibilitySpec extends Specification {

    def "a filler letter alone does not make a value visible on a line or in prose"() {
        expect:
        CharacterStanding.of(codePoint) == CharacterStanding.SHOWS
        CharacterStanding.ignorable(codePoint)
        !Legibility.holdsSomethingVisible(Character.toString(codePoint))
        !Legibility.holdsSomethingVisibleInProse(Character.toString(codePoint))

        where:
        codePoint << [0x3164, 0xFFA0]
    }

    def "a visible word remains visible with a filler and either joiner"() {
        given:
        String value = "word" + Character.toString(filler) + Character.toString(joiner)

        expect:
        Legibility.holdsSomethingVisible(value)
        Legibility.holdsSomethingVisibleInProse(value)
        !CharacterStanding.ignorable(joiner)

        where:
        filler | joiner
        0x3164 | 0x200C
        0xFFA0 | 0x200D
    }
}
