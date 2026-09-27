package org.lilradish.lite.domain.wire

import org.lilradish.lite.domain.wire.JsonValue.JsonArray
import org.lilradish.lite.domain.wire.JsonValue.JsonBoolean
import org.lilradish.lite.domain.wire.JsonValue.JsonMember
import org.lilradish.lite.domain.wire.JsonValue.JsonNull
import org.lilradish.lite.domain.wire.JsonValue.JsonNumber
import org.lilradish.lite.domain.wire.JsonValue.JsonObject
import org.lilradish.lite.domain.wire.JsonValue.JsonString
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper

class CanonicalJsonSpec extends Specification {

    private static final String BACKSLASH = '\\'
    private static final String GRINNING = Character.toString(0x1F600)
    private static final String HIGH = Character.toString(0xD83D)
    private static final String LOW = Character.toString(0xDE00)

    /** A reader of what is stored, standing in for whoever reads it back. */
    private static final JsonMapper READER = JsonMapper.builder().build()

    def "writes every kind of value with nothing between its tokens"() {
        expect:
        CanonicalJson.write(value) == text

        where:
        value                                                                   || text
        new JsonObject([])                                                      || '{}'
        new JsonArray([])                                                       || '[]'
        new JsonString("")                                                      || '""'
        new JsonNumber(new BigDecimal("7"))                                     || '7'
        new JsonBoolean(true)                                                   || 'true'
        new JsonBoolean(false)                                                  || 'false'
        new JsonNull()                                                          || 'null'
        new JsonArray([new JsonNull(), new JsonString("a"), new JsonArray([])]) || '[null,"a",[]]'
    }

    def "writes an object's members in the order they were given, however deeply nested"() {
        given:
        def document = new JsonObject([
            new JsonMember("zeta", new JsonArray([new JsonNumber(1.5), new JsonString("b")])),
            new JsonMember("alpha", new JsonObject([
                new JsonMember("second", new JsonBoolean(true)),
                new JsonMember("first", new JsonNull()),
            ])),
            new JsonMember("mid", new JsonObject([])),
        ])

        expect:
        CanonicalJson.write(document) == '{"zeta":[1.5,"b"],"alpha":{"second":true,"first":null},"mid":{}}'
    }

    def "writes a number as its plain decimal, keeping the scale it was given"() {
        expect:
        CanonicalJson.write(new JsonNumber(new BigDecimal(given))) == text

        where:
        given                              || text
        "0"                                || "0"
        "-0.50"                            || "-0.50"
        "1.230E-5"                         || "0.00001230"
        "1E+3"                             || "1000"
        "123456789012345678901234567890.1" || "123456789012345678901234567890.1"
    }

    def "escapes the quotation mark, the reverse solidus and the controls that have a short escape by that escape"() {
        expect:
        CanonicalJson.write(new JsonString(Character.toString(codePoint))) == '"' + BACKSLASH + escape + '"'

        where:
        codePoint || escape
        0x22      || '"'
        0x5C      || BACKSLASH
        0x08      || 'b'
        0x09      || 't'
        0x0A      || 'n'
        0x0C      || 'f'
        0x0D      || 'r'
    }

    def "escapes every other control below U+0020 as four lowercase hexadecimal digits"() {
        expect:
        CanonicalJson.write(new JsonString(Character.toString(codePoint))) ==
                '"' + BACKSLASH + 'u' + String.format("%04x", codePoint) + '"'

        where:
        codePoint << (0x00..0x1F).findAll { !(it in [0x08, 0x09, 0x0A, 0x0C, 0x0D]) }
    }

    def "writes every other character as it is, one character for one"() {
        given:
        def character = Character.toString(codePoint)

        expect:
        CanonicalJson.write(new JsonString(character)) == '"' + character + '"'

        where:
        codePoint << [0x20, 0x21, 0x2F, 0x7E, 0x7F, 0x80, 0x85, 0x9F, 0xA0, 0xE9, 0x2028, 0x2029, 0x5408, 0xFEFF,
                      0x1F600, 0x10FFFF]
    }

    def "keeps the text around and between escapes whole and in place"() {
        given:
        def text = 'x"y' + Character.toString(0x0A) + Character.toString(0x0A) + 'z' + GRINNING + BACKSLASH

        expect:
        CanonicalJson.write(new JsonString(text)) ==
                '"x' + BACKSLASH + '"y' + BACKSLASH + 'n' + BACKSLASH + 'nz' + GRINNING + BACKSLASH + BACKSLASH + '"'
    }

    def "escapes a member's name as it escapes a string"() {
        given:
        def name = 'a"' + Character.toString(0x09)

        expect:
        CanonicalJson.write(new JsonObject([new JsonMember(name, new JsonNull())])) ==
                '{"a' + BACKSLASH + '"' + BACKSLASH + 't":null}'
    }

    def "writes a text a reader takes back as the very same text"() {
        given:
        def text = "a" + Character.toString(codePoint) + "b"

        when:
        def read = READER.readTree(CanonicalJson.write(new JsonString(text)))

        then:
        read.isString()
        read.asString() == text

        where:
        codePoint << [0x00, 0x08, 0x1F, 0x22, 0x5C, 0x7F, 0x2028, 0x1F600]
    }

    def "refuses a text holding an unpaired surrogate, wherever in the document it stands"() {
        when:
        CanonicalJson.write(value)

        then:
        def error = thrown(IllegalArgumentException)
        error.message == "CanonicalJson refuses a text holding an unpaired surrogate"

        where:
        value << [
            new JsonString(HIGH),
            new JsonString(LOW),
            new JsonString("a" + HIGH),
            new JsonString(LOW + HIGH),
            new JsonString(GRINNING + LOW),
            new JsonArray([new JsonString("a"), new JsonString(HIGH + "a")]),
            new JsonObject([new JsonMember("a" + LOW, new JsonNull())]),
            new JsonObject([new JsonMember("a", new JsonArray([new JsonString(HIGH)]))]),
        ]
    }

    def "refuses to write nothing"() {
        when:
        CanonicalJson.write(null)

        then:
        def error = thrown(NullPointerException)
        error.message == "CanonicalJson value must not be null"
    }

    /** The store writes a space after every comma and colon of its own text, so each counts one more than written. */
    def "counts a value as the store's own text of it runs, a space after each comma and colon"() {
        expect:
        CanonicalJson.storedLength(value) == length

        where:
        value                                                                           || length
        new JsonObject([])                                                              || 2
        new JsonArray([])                                                               || 2
        new JsonString("")                                                              || 2
        new JsonNumber(new BigDecimal("7"))                                             || 1
        new JsonNumber(new BigDecimal("1.50"))                                          || 4
        new JsonNumber(new BigDecimal("1E+2"))                                          || 3
        new JsonBoolean(true)                                                           || 4
        new JsonBoolean(false)                                                          || 5
        new JsonNull()                                                                  || 4
        new JsonArray([new JsonNull(), new JsonString("a"), new JsonArray([])])         || 15
        new JsonObject([new JsonMember("a", new JsonNumber(1G)),
                        new JsonMember("b", new JsonArray([new JsonBoolean(true)]))])   || 21
    }

    /** One character for one, but the quotation mark, the reverse solidus and every control below U+0020. */
    def "counts a text as the store escapes it, a character beyond the first plane as one"() {
        expect:
        CanonicalJson.storedLength(new JsonString(text)) == length

        where:
        text                                                          || length
        "abc"                                                         || 5
        "a b"                                                         || 5
        "a\"b" + BACKSLASH + "c"                                      || 9
        "\n\t\r\b\f"                                                  || 12
        Character.toString(0x01) + Character.toString(0x1F)          || 14
        Character.toString(0x7F)                                      || 3
        GRINNING                                                      || 3
    }

    def "refuses to count nothing"() {
        when:
        CanonicalJson.storedLength(null)

        then:
        def error = thrown(NullPointerException)
        error.message == "CanonicalJson value must not be null"
    }

    def "a character is half of a pair standing alone exactly where the other half does not stand beside it"() {
        expect:
        (0..<text.length()).findAll { CanonicalJson.halfAPairAt(text, it) } == alone

        where:
        text                   || alone
        "a" + GRINNING + "b"   || []
        GRINNING + GRINNING    || []
        HIGH                   || [0]
        LOW                    || [0]
        "a" + HIGH             || [1]
        LOW + HIGH             || [0, 1]
        HIGH + HIGH + LOW      || [0]
        HIGH + LOW + LOW       || [2]
    }
}
