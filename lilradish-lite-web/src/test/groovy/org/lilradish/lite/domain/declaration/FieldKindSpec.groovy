package org.lilradish.lite.domain.declaration

import org.lilradish.lite.domain.wire.CanonicalJson
import org.lilradish.lite.domain.wire.JsonValue
import spock.lang.Specification

class FieldKindSpec extends Specification {

    static final String DIGITS_38 = "9" * 38

    /** A year written in digits that are digits, but not the ones a date is written in. */
    static final String ARABIC_INDIC_2024 = [0x662, 0x660, 0x662, 0x664].collect { Character.toString(it) }.join()

    static final List<FieldKind> STRING_WRITERS = [FieldKind.TEXT, FieldKind.TERM]

    static final List<FieldKind> DATE_WRITERS = [FieldKind.TEXT, FieldKind.DATE, FieldKind.TERM]

    static final List<FieldKind> MOMENT_WRITERS = [FieldKind.TEXT, FieldKind.MOMENT, FieldKind.TERM]

    /** Pinned whole and in order: the store's vocabulary is held to this declaration label by label. */
    def "the kinds a field may be are fixed, in the order they are declared"() {
        expect:
        FieldKind.values().toList() == [FieldKind.TEXT, FieldKind.NUMBER, FieldKind.DATE, FieldKind.MOMENT,
                                         FieldKind.YES_NO, FieldKind.TERM, FieldKind.FIELDS]
    }

    /** A page names what it draws by these, so a constant renamed without its spelling staying put breaks it. */
    def "each kind is published under the spelling a reader names it by"() {
        expect:
        FieldKind.values().collectEntries { [(it): it.published()] } == [
                (FieldKind.TEXT)  : "text",
                (FieldKind.NUMBER): "number",
                (FieldKind.DATE)  : "date",
                (FieldKind.MOMENT): "moment",
                (FieldKind.YES_NO): "yes_no",
                (FieldKind.TERM)  : "term",
                (FieldKind.FIELDS): "fields",
        ]
    }

    def "a value written as its kind is written is taken, at every edge the form has"() {
        expect:
        kind.writes(value)

        where:
        kind             | value
        FieldKind.TEXT   | new JsonValue.JsonString("")
        FieldKind.TERM   | new JsonValue.JsonString("Billing")
        FieldKind.NUMBER | number("0")
        FieldKind.NUMBER | number("-0.5")
        FieldKind.NUMBER | number(DIGITS_38)
        FieldKind.NUMBER | number("-9." + "9" * 37)
        FieldKind.NUMBER | number("0." + "0" * 36 + "1")
        FieldKind.DATE   | new JsonValue.JsonString("0001-01-01")
        FieldKind.DATE   | new JsonValue.JsonString("9999-12-31")
        FieldKind.DATE   | new JsonValue.JsonString("2024-02-29")
        FieldKind.MOMENT | moment("2024-03-31T09:30:00+02:00")
        FieldKind.MOMENT | moment("2024-03-31T09:30:00.5-14:00")
        FieldKind.MOMENT | moment("9999-12-31T23:59:59.999999+14:00")
        FieldKind.MOMENT | moment("0001-01-01T00:00:00+00:00")
        FieldKind.MOMENT | moment("0001-01-01T00:00:00-00:01")
        FieldKind.MOMENT | moment("2024-03-31T09:30:00+13:59")
        FieldKind.YES_NO | new JsonValue.JsonBoolean(true)
        FieldKind.YES_NO | new JsonValue.JsonBoolean(false)
        FieldKind.FIELDS | new JsonValue.JsonObject([])
    }

    def "no kind but its own writes a value, bar text and a term, which write every string"() {
        expect:
        FieldKind.values().findAll { it.writes(value) } == writtenBy

        where:
        value                                               || writtenBy
        new JsonValue.JsonString("Billing")                 || STRING_WRITERS
        number("-9." + "9" * 37)                            || [FieldKind.NUMBER]
        new JsonValue.JsonString("2024-02-29")              || DATE_WRITERS
        moment("9999-12-31T23:59:59.999999+14:00")          || MOMENT_WRITERS
        new JsonValue.JsonBoolean(false)                    || [FieldKind.YES_NO]
        new JsonValue.JsonObject([])                        || [FieldKind.FIELDS]
    }

    def "a value not written as its kind is written is not taken"() {
        expect:
        !kind.writes(value)

        where:
        kind             | value
        FieldKind.TEXT   | number("1")
        FieldKind.TERM   | new JsonValue.JsonArray([new JsonValue.JsonString("Billing")])
        FieldKind.NUMBER | number("1" + DIGITS_38)
        FieldKind.NUMBER | number("0." + "0" * 37 + "1")
        FieldKind.NUMBER | number("-9." + DIGITS_38)
        FieldKind.NUMBER | new JsonValue.JsonNumber(new BigDecimal("1E+3"))
        FieldKind.NUMBER | new JsonValue.JsonString("1")
        FieldKind.DATE   | new JsonValue.JsonString("0000-01-01")
        FieldKind.DATE   | new JsonValue.JsonString("2023-02-29")
        FieldKind.DATE   | new JsonValue.JsonString("2024-13-01")
        FieldKind.DATE   | new JsonValue.JsonString("2024-00-10")
        FieldKind.DATE   | new JsonValue.JsonString("2024-01-00")
        FieldKind.DATE   | new JsonValue.JsonString("2024-01-32")
        FieldKind.DATE   | new JsonValue.JsonString("2024-1-01")
        FieldKind.DATE   | new JsonValue.JsonString("2024-01-011")
        FieldKind.DATE   | new JsonValue.JsonString("2024/01/01")
        FieldKind.DATE   | new JsonValue.JsonString("2024/01-01")
        FieldKind.DATE   | new JsonValue.JsonString("2024-01/01")
        FieldKind.DATE   | new JsonValue.JsonString("+2024-01-01")
        FieldKind.DATE   | new JsonValue.JsonString(ARABIC_INDIC_2024 + "-01-01")
        FieldKind.DATE   | new JsonValue.JsonString("2024-03-31T09:30:00+02:00")
        FieldKind.DATE   | number("20240101")
        FieldKind.MOMENT | moment("2024-03-31T09:30:00Z")
        FieldKind.MOMENT | moment("2024-03-31T09:30:00")
        FieldKind.MOMENT | moment("2024-03-31 09:30:00+02:00")
        FieldKind.MOMENT | moment("2024-03-31T24:00:00+00:00")
        FieldKind.MOMENT | moment("2024-03-31T09:60:00+00:00")
        FieldKind.MOMENT | moment("2024-03-31T09:30:60+00:00")
        FieldKind.MOMENT | moment("2024-03-31T0x:30:00+02:00")
        FieldKind.MOMENT | moment("2024-03-31T09:3x:00+02:00")
        FieldKind.MOMENT | moment("2024-03-31T09:30:0x+02:00")
        FieldKind.MOMENT | moment("2024-03-31T09:30+02:00")
        FieldKind.MOMENT | moment("2024-03-31T09-30:00+02:00")
        FieldKind.MOMENT | moment("2024-03-31T09:30-00+02:00")
        FieldKind.MOMENT | moment("2024-03-31T09:30:00.+02:00")
        FieldKind.MOMENT | moment("2024-03-31T09:30:00.1234567+02:00")
        FieldKind.MOMENT | moment("2024-03-31T09:30:00,5+02:00")
        FieldKind.MOMENT | moment("2024-03-31T09:30:00+14:01")
        FieldKind.MOMENT | moment("2024-03-31T09:30:00+15:00")
        FieldKind.MOMENT | moment("2024-03-31T09:30:00+02:60")
        FieldKind.MOMENT | moment("2024-03-31T09:30:00+0x:00")
        FieldKind.MOMENT | moment("2024-03-31T09:30:00+02:0x")
        FieldKind.MOMENT | moment("2024-03-31T09:30:00-00:00")
        FieldKind.MOMENT | moment("2024-03-31T09:30:00.5-00:00")
        FieldKind.MOMENT | moment("2024-03-31T09:30:00+0200")
        FieldKind.MOMENT | moment("2024-03-31T09:30:00*02:00")
        FieldKind.MOMENT | moment("2024-03-31T09:30:00+02-00")
        FieldKind.MOMENT | moment("2024-03-31T09:30:00+02:00 ")
        FieldKind.MOMENT | moment("2024-02-30T09:30:00+02:00")
        FieldKind.MOMENT | moment("0000-03-31T09:30:00+02:00")
        FieldKind.MOMENT | new JsonValue.JsonString("2024-03-31")
        FieldKind.YES_NO | new JsonValue.JsonString("true")
        FieldKind.YES_NO | number("1")
        FieldKind.FIELDS | new JsonValue.JsonString("{}")
        FieldKind.FIELDS | new JsonValue.JsonArray([])
    }

    def "none is no value of any kind"() {
        expect:
        FieldKind.values().every { !it.writes(new JsonValue.JsonNull()) }
    }

    def "a value that is nothing at all is refused by name"() {
        when:
        FieldKind.TEXT.writes(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "FieldKind value must not be null"
    }

    /**
     * The longest each kind declaring no limit is written in, measured as it is kept: taken as its kind, and
     * the one place its form lets it grow grown by one no longer is.
     */
    def "a kind declaring no limit is at its longest as its own form lets it be, and no longer"() {
        when:
        def longest = kind.longestWritten()
        def text = CanonicalJson.write(longest)

        then:
        text == written
        text.codePointCount(0, text.length()) == length
        kind.writes(longest)
        !kind.writes(grown)

        where:
        kind             | grown                                                  || written                                    | length
        FieldKind.NUMBER | number("-9." + DIGITS_38)                              || "-9." + "9" * 37                           | 40
        FieldKind.DATE   | new JsonValue.JsonString("99999-12-31")                || '"9999-12-31"'                             | 12
        FieldKind.MOMENT | moment("9999-12-31T23:59:59.9999999-14:00")            || '"9999-12-31T23:59:59.999999-14:00"'       | 34
        FieldKind.YES_NO | new JsonValue.JsonString("false")                      || "false"                                    | 5
    }

    def "a kind as long as its field makes it has no longest of its own"() {
        when:
        kind.longestWritten()

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "FieldKind " + kind.published() + " is as long as its field makes it"

        where:
        kind << [FieldKind.TEXT, FieldKind.TERM, FieldKind.FIELDS]
    }

    /** A decimal keeps no sign of a zero and no exponent as written, so only the text says either was written. */
    def "a number's text is judged for what the decimal read from it cannot show, and one written plainly passes"() {
        expect:
        FieldKind.numberUnwritten(written) == unwritten

        where:
        written   || unwritten
        "0"       || null
        "0.000"   || null
        "-0.001"  || null
        "-12.50"  || null
        "-0"      || FieldKind.NumberUnwritten.ZERO_WITH_MINUS
        "-0.000"  || FieldKind.NumberUnwritten.ZERO_WITH_MINUS
        "1e5"     || FieldKind.NumberUnwritten.WITH_EXPONENT
        "15E-1"   || FieldKind.NumberUnwritten.WITH_EXPONENT
        "-0e0"    || FieldKind.NumberUnwritten.WITH_EXPONENT
    }

    private static JsonValue number(String written) {
        new JsonValue.JsonNumber(new BigDecimal(written))
    }

    private static JsonValue moment(String written) {
        new JsonValue.JsonString(written)
    }
}
