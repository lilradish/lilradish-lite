package org.lilradish.lite.app.library

import org.lilradish.lite.domain.wire.JsonValue
import spock.lang.Specification

class ConstantJsonSpec extends Specification {

    static final JsonValue WRITTEN = new JsonValue.JsonObject([
            new JsonValue.JsonMember("zeta", new JsonValue.JsonString("z")),
            new JsonValue.JsonMember("alpha", new JsonValue.JsonArray([
                    new JsonValue.JsonNumber(1.50G), new JsonValue.JsonBoolean(false), new JsonValue.JsonNull()]))])

    /** Members keep the order written, which is the order a stored constant is compared in. */
    def "each value JSON holds is read as the value it writes, members in the order written"() {
        expect:
        ConstantJson.sent('{"zeta":"z","alpha":[1.5,false,null]}') == new JsonValue.JsonObject([
                new JsonValue.JsonMember("zeta", new JsonValue.JsonString("z")),
                new JsonValue.JsonMember("alpha", new JsonValue.JsonArray([
                        new JsonValue.JsonNumber(1.5G), new JsonValue.JsonBoolean(false), new JsonValue.JsonNull()]))])
    }

    def "a single value is read as that value"() {
        expect:
        ConstantJson.sent(text) == value

        where:
        text      || value
        'null'    || new JsonValue.JsonNull()
        '"x"'     || new JsonValue.JsonString("x")
        '12'      || new JsonValue.JsonNumber(12G)
        '-0.50'   || new JsonValue.JsonNumber(-0.50G)
        'true'    || new JsonValue.JsonBoolean(true)
        'false'   || new JsonValue.JsonBoolean(false)
        '[]'      || new JsonValue.JsonArray([])
        '{}'      || new JsonValue.JsonObject([])
    }

    /** Read through a double, the number would be rounded; read as sent, its digits and zeros stay. */
    def "a constant sent is the value its text writes, every number the decimal written"() {
        expect:
        ConstantJson.sent(text) == value

        where:
        text                                                    || value
        ' { "zeta" : "z", "alpha" : [1.50, false, null] } '     || WRITTEN
        "12345678901234567890123456789012345678"                || new JsonValue.JsonNumber(
                new BigDecimal("12345678901234567890123456789012345678"))
    }

    /**
     * A number is written plainly, so one written with an exponent, or as a zero with a minus sign, is refused
     * wherever it stands, whatever it is worth.
     */
    def "a constant sent that is not the text of one JSON value is refused as an argument, saying why"() {
        when:
        ConstantJson.sent(text)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == message
        (refused.cause != null) == unread
        (refused instanceof ConstantJson.NotWrittenPlainly) == notPlain

        where:
        text              || message                                             | unread | notPlain
        "-0"              || "A constant sent writes a zero with a minus sign"   | false  | true
        "-0.000"          || "A constant sent writes a zero with a minus sign"   | false  | true
        '[1, -0.0]'       || "A constant sent writes a zero with a minus sign"   | false  | true
        ""                || "A constant sent holds no JSON document"            | false  | false
        "  "              || "A constant sent holds no JSON document"            | false  | false
        "1 2"             || "A constant sent holds more than one JSON document" | false  | false
        "{"               || "A constant sent is not one JSON document"          | true   | false
        '{"a":1,"a":2}'   || "A constant sent is not one JSON document"          | true   | false
        "1e9999999999"    || "A constant sent writes a number with an exponent"  | false  | true
        "1.0e1"           || "A constant sent writes a number with an exponent"  | false  | true
        "15E-1"           || "A constant sent writes a number with an exponent"  | false  | true
        '[1, 1e-5]'       || "A constant sent writes a number with an exponent"  | false  | true
        '{"a":{"b":2e0}}' || "A constant sent writes a number with an exponent"  | false  | true
    }

    def "a stored constant reads back as it was written"() {
        expect:
        ConstantJson.stored(ConstantJson.written(WRITTEN)) == WRITTEN
    }

    /** Read through a double, it would be rounded. */
    def "a stored number reads back as the decimal it was"() {
        expect:
        ConstantJson.stored('0.1000000000000000055511151231257827') ==
                new JsonValue.JsonNumber(new BigDecimal("0.1000000000000000055511151231257827"))
    }

    def "a stored constant that is not one JSON document is a store gone wrong"() {
        when:
        ConstantJson.stored(text)

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "A stored constant is not one this system writes"
        failed.cause instanceof IllegalArgumentException

        where:
        text << ['{"a":', "", "1 2"]
    }

    def "a constant is written in the one spelling this system writes JSON in"() {
        expect:
        ConstantJson.written(WRITTEN) == '{"zeta":"z","alpha":[1.50,false,null]}'
    }
}
