package org.lilradish.lite.app.inference

import org.lilradish.lite.domain.wire.JsonValue
import org.lilradish.lite.testutil.inference.Json
import spock.lang.Specification

class AnswerReaderSpec extends Specification {

    def "an answer that is one JSON document is read as it was written, numbers exactly and members in order"() {
        expect:
        AnswerReader.read(answer) == Json.of(read)

        where:
        answer                                              || read
        '{"b":1,"a":2}'                                     || [b: 1, a: 2]
        ' \n{"a":[true,false,null,"x",{}]}\n '              || [a: [true, false, null, "x", [:]]]
        '{"a":12.50}'                                       || [a: new BigDecimal("12.50")]
        '{"a":-0.000000000000000000000000000000000000001}'  || [a: new BigDecimal("-0.000000000000000000000000000000000000001")]
        '{"a":12345678901234567890.123456789}'              || [a: new BigDecimal("12345678901234567890.123456789")]
        '"just words"'                                      || "just words"
        '[]'                                                || []
        '{"a":"\\ud800","b":"\\u0000"}'                     || [a: "\uD800", b: "\u0000"]
        '{"a":' + "9" * 1000 + '}'                          || [a: new BigDecimal("9" * 1000)]
        '[' * 500 + ']' * 500                               || nested(500)
    }

    /** What the domain is handed must be the document itself: anything read around it, or read two ways, is not. */
    def "an answer that is not one JSON document as written is read as none"() {
        expect:
        AnswerReader.read(answer) == null

        where:
        answer << ['', '   ', '```json\n{"a":1}\n```', '```\n{"a":1}\n```', 'Here it is: {"a":1}',
                   '{"a":1}{"a":2}', '{"a":1} x', '{"a":1,"a":2}', '{"a":{"b":1,"b":1}}',
                   '{"a":1e3}', '{"a":1E-2}', '{"a":1.5e0}', '[2E0]', '{"a":-0}', '[-0.0]', '-0.000',
                   "{'a':1}", '{"a":01}', '{"a":NaN}', '{"a":+1}', '[1,]', '{"a":1,}', '// note\n{}', '{"a":1',
                   'nul', '{"a":"\u0001"}', Character.toString(0xFEFF) + '{}', '[' * 501 + ']' * 501, '{"a":' + "9" * 1001 + '}']
    }

    def "an answer that is nothing at all is refused by name"() {
        when:
        AnswerReader.read(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "AnswerReader answer must not be null"
    }

    /** Arrays held one inside another, the outermost counted as one deep, built without recursing that deep. */
    private static JsonValue nested(int depth) {
        JsonValue held = new JsonValue.JsonArray([])
        (depth - 1).times { held = new JsonValue.JsonArray([held]) }
        held
    }
}
