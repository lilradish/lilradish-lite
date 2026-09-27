package org.lilradish.lite.domain.text

import spock.lang.Specification

class ConcealingCharacterSpec extends Specification {

    def "the kinds of concealing character are two, in the order they are declared"() {
        expect:
        ConcealingCharacter.values().toList() == [ConcealingCharacter.DIRECTION_CONTROL, ConcealingCharacter.TAG]
    }

    /** Each end of every range and the neighbour just outside it, so a bound written one off moves a row. */
    def "a code point is judged by the range it falls in, and one just outside every range is none"() {
        expect:
        ConcealingCharacter.of(codePoint) == found

        where:
        codePoint || found
        0x202A    || ConcealingCharacter.DIRECTION_CONTROL
        0x202E    || ConcealingCharacter.DIRECTION_CONTROL
        0x2066    || ConcealingCharacter.DIRECTION_CONTROL
        0x2069    || ConcealingCharacter.DIRECTION_CONTROL
        0xE0000   || ConcealingCharacter.TAG
        0xE007F   || ConcealingCharacter.TAG
        0x2029    || null
        0x202F    || null
        0x2065    || null
        0x206A    || null
        0xDFFFF   || null
        0xE0080   || null
        0x200C    || null
        0x200D    || null
        0x0041    || null
    }

    def "the first concealing character in a text is found reading from its start, past the basic plane"() {
        expect:
        ConcealingCharacter.firstIn(text) == found

        where:
        text                                                                            || found
        "Say" + Character.toString(0x1F600) + Character.toString(0xE0041) + "why."      || ConcealingCharacter.TAG
        "Say" + Character.toString(0x202E) + Character.toString(0xE0041)                || ConcealingCharacter.DIRECTION_CONTROL
        "Say" + Character.toString(0xE0041) + Character.toString(0x2066)                || ConcealingCharacter.TAG
        "Mi" + Character.toString(0x200C) + "tra"                                       || null
        ""                                                                              || null
    }
}
