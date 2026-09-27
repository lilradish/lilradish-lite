package org.lilradish.lite.domain.text

import java.util.function.Consumer
import spock.lang.Specification

class ProseRefusalSpec extends Specification {

    static final String OVERRIDE = Character.toString(0x202E)

    static final String TAG = Character.toString(0xE0041)

    /** A CRLF is named before what is concealed, and only of text over lines; the rest falls to the value's own rule. */
    def "text is refused for the first reason a page names, in the order it looks"() {
        given:
        Consumer<String> oneLine = { String typed -> Legibility.requireOneWellFormedLine(typed, "Said") }

        expect:
        ProseRefusal.of(said, overLines, oneLine) == refusal

        where:
        said                                 | overLines || refusal
        "Say why."                           | true      || null
        "Say why."                           | false     || null
        "One\r\ntwo" + OVERRIDE              | true      || ProseRefusal.CRLF
        "One\r\ntwo" + OVERRIDE              | false     || ProseRefusal.DIRECTION_CONTROL
        "One\r\ntwo"                         | false     || ProseRefusal.UNUSABLE
        OVERRIDE + " then tagged" + TAG      | false     || ProseRefusal.DIRECTION_CONTROL
        "Tagged" + TAG + " then " + OVERRIDE | false     || ProseRefusal.TAG
        "Bell" + Character.toString(7)       | true      || ProseRefusal.UNUSABLE
    }
}
