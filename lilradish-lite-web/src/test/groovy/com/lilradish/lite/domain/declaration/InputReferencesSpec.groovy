package com.lilradish.lite.domain.declaration

import spock.lang.Specification

class InputReferencesSpec extends Specification {

    def "instruction references include every complete unescaped brace pair, in first-mention order"() {
        expect:
        InputReferences.names(text) == names
        InputReferences.unknown(text, [new FieldName("known")]) == unknown

        where:
        text                                      || names                  | unknown
        "Use {{known}}, then {{known}}."          || ["known"]             | []
        "{{a.b}} {{lines[0]}} {{ }} {{}}"          || ["a.b", "lines[0]", " ", ""] | ["a.b", "lines[0]", " ", ""]
        "\\{{unknown}} and {{known}}"             || ["known"]             | []
        "No reference, and an unfinished {{known" || []                    | []
        "{{<b>}} {{\u65e5\u672c\u8a9e}} {{a\nb}}"               || ["<b>", "\u65e5\u672c\u8a9e", "a\nb"] | ["<b>", "\u65e5\u672c\u8a9e", "a\nb"]
        "{{known}}{{other}}"                      || ["known", "other"]    | ["other"]
    }

    def "drawing retains its existing name grammar, including escaped and unfinished text"() {
        expect:
        InputReferences.drawingNames("{{known}} {{a.b}} \\{{other}} {{unfinished") == [new FieldName("known")]
        InputReferences.drawingNames("{{known}} {{known}}") == [new FieldName("known"), new FieldName("known")]
    }

    def "renaming is one pass over real names and keeps every other byte, including escapes"() {
        given:
        def names = [(new FieldName("left")): new FieldName("right"),
                     (new FieldName("right")): new FieldName("left")]

        expect:
        InputReferences.renamed("{{left}} / {{right}} / {{left}} / \\{{left}} / {{left.part}} / {{left", names) ==
                "{{right}} / {{left}} / {{right}} / \\{{left}} / {{left.part}} / {{left"
    }
}
