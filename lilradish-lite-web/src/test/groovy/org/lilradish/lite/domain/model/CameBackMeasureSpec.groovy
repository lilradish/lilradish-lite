package org.lilradish.lite.domain.model

import spock.lang.Specification

class CameBackMeasureSpec extends Specification {

    def "counts code points, a pair as one and an unpaired surrogate as one, never refusing what came back"() {
        expect:
        CameBackMeasure.characters(answer) == characters

        where:
        answer                     || characters
        ""                         || 0
        "total"                    || 5
        "😀 ok"          || 4
        "\uD800"                   || 1
        "\uDC00"                   || 1
        "a\uD800b"                 || 3
        "\uDE00\uD83D"             || 2
        "😀\uD83D"       || 2
    }

    def "counts a well-formed text as the sent measure counts it"() {
        expect:
        CameBackMeasure.characters(text) == SentText.measure(text, "").characters()

        where:
        text << ["", "plain", "😀😁", "tab\tand\nline"]
    }
}
