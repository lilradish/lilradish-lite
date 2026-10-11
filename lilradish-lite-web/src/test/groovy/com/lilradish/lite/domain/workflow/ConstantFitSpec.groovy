package com.lilradish.lite.domain.workflow

import com.lilradish.lite.domain.declaration.Demand
import com.lilradish.lite.domain.declaration.Field
import com.lilradish.lite.domain.declaration.FieldKind
import com.lilradish.lite.domain.declaration.FieldName
import com.lilradish.lite.domain.declaration.FieldShape
import com.lilradish.lite.domain.declaration.FieldStanding
import com.lilradish.lite.domain.declaration.HowMany
import com.lilradish.lite.domain.declaration.OfferedTerms
import com.lilradish.lite.domain.declaration.TextLines
import com.lilradish.lite.domain.registry.EntryVersionId
import com.lilradish.lite.domain.text.ConcealingCharacter
import com.lilradish.lite.domain.wire.JsonValue
import com.lilradish.lite.testutil.OfferedLists
import spock.lang.Specification

class ConstantFitSpec extends Specification {

    static final EntryVersionId LIST = new EntryVersionId(UUID.fromString("00000007-0000-4000-8000-000000000101"))

    static final Map<EntryVersionId, OfferedTerms> TERMS = OfferedLists.offering([(LIST): ["Billing", "Delivery"]])

    static final JsonValue NOTHING = new JsonValue.JsonNull()

    static final String NUL = Character.toString(0)

    def "a constant of text may hold line breaks only where its field takes several"() {
        given:
        def value = new JsonValue.JsonString("Demo Sample" + Character.toString(codePoint))
        def one = field(new FieldShape.Text(30, TextLines.ONE), new HowMany.One(), new Demand.Given(false))
        def several = field(new FieldShape.Text(30), new HowMany.One(), new Demand.Given(false))

        expect:
        !ConstantFit.fits(value, one, [:])
        ConstantFit.fits(value, several, [:])

        where:
        codePoint << [0x0D, 0x0A, 0x0B, 0x0C, 0x85, 0x2028, 0x2029]
    }

    /** Judged before its plain form is ever written out, which for a number this long would never end. */
    def "a constant no store keeps as written is unwritable, wherever in it the reason stands"() {
        expect:
        ConstantFit.unwritable(constant)

        where:
        constant << [
                new JsonValue.JsonNumber(new BigDecimal("1" * 39)),
                new JsonValue.JsonNumber(new BigDecimal("1e38")),
                new JsonValue.JsonNumber(new BigDecimal("0." + "1" * 39)),
                new JsonValue.JsonNumber(new BigDecimal("1e999999999")),
                new JsonValue.JsonNumber(new BigDecimal("1e-999999999")),
                new JsonValue.JsonNumber(new BigDecimal("1e2147483647")),
                new JsonValue.JsonNumber(new BigDecimal("-1e2147483647")),
                new JsonValue.JsonNumber(new BigDecimal("12e2147483647")),
                new JsonValue.JsonNumber(new BigDecimal("1e-2147483647")),
                text("a" + NUL + "b"),
                text(NUL + "a"),
                new JsonValue.JsonObject([member("a" + NUL, text("x"))]),
                new JsonValue.JsonObject([member(NUL + "a", text("x"))]),
                new JsonValue.JsonArray([text("ok"), new JsonValue.JsonObject([
                        member("n", new JsonValue.JsonNumber(new BigDecimal("1" * 39)))])]),
                text("x" * 8193),
                new JsonValue.JsonArray([new JsonValue.JsonNumber(0G)] * 350_000),
                new JsonValue.JsonArray([new JsonValue.JsonNumber(100G)] + [new JsonValue.JsonNumber(0G)] * 349_524),
        ]
    }

    def "a constant at every bound a store keeps is writable"() {
        expect:
        !ConstantFit.unwritable(constant)

        where:
        constant << [
                new JsonValue.JsonNumber(new BigDecimal("1" * 38)),
                new JsonValue.JsonNumber(new BigDecimal("1e37")),
                new JsonValue.JsonNumber(new BigDecimal("0." + "1" * 38)),
                new JsonValue.JsonNumber(new BigDecimal("-" + "9" * 38)),
                text("x" * 8192),
                new JsonValue.JsonObject([member("a", new JsonValue.JsonBoolean(true)), member("b", NOTHING)]),
                new JsonValue.JsonArray([new JsonValue.JsonNumber(0G)] * 300_000),
                new JsonValue.JsonArray([new JsonValue.JsonNumber(10G)] + [new JsonValue.JsonNumber(0G)] * 349_524),
        ]
    }

    def "text is counted a code point at a time, at every depth, member names aside"() {
        expect:
        ConstantFit.textLength(constant) == length

        where:
        constant                                                                               || length
        text("abc")                                                                            || 3
        text("\uD83D\uDE00")                                                                   || 1
        new JsonValue.JsonArray([text("ab"), text("cd")])                                      || 4
        new JsonValue.JsonObject([new JsonValue.JsonMember("a_long_name", text("xy"))])        || 2
        new JsonValue.JsonNumber(12.5G)                                                        || 0
        new JsonValue.JsonBoolean(true)                                                        || 0
        NOTHING                                                                                || 0
    }

    def "the first character a model may not be sent is found wherever it is written, and none where there is none"() {
        expect:
        ConstantFit.concealing(constant) == found

        where:
        constant                                                                                      || found
        text("plain")                                                                                 || null
        text("a\u202Eb")                                                                              || ConcealingCharacter.DIRECTION_CONTROL
        new JsonValue.JsonArray([text("ok"), text("\uDB40\uDC41")])                                   || ConcealingCharacter.TAG
        new JsonValue.JsonObject([new JsonValue.JsonMember("a", text("x\u2066y"))])                   || ConcealingCharacter.DIRECTION_CONTROL
        new JsonValue.JsonArray([text("\uDB40\uDC41"), text("\u202E")])                               || ConcealingCharacter.TAG
        new JsonValue.JsonNumber(1G)                                                                  || null
        NOTHING                                                                                       || null
    }

    /** Nothing fits a field that may be left empty and no other, which is what must be given means. */
    def "nothing fits only where it need not be given"() {
        expect:
        ConstantFit.fits(NOTHING, field(new FieldShape.Text(5), new HowMany.One(), demand), TERMS) == fits

        where:
        demand                                              || fits
        new Demand.Given(false)                             || true
        new Demand.Stands(false, FieldStanding.NEVER, null) || true
        new Demand.Given(true)                              || false
        new Demand.Stands(true, FieldStanding.NEVER, null)  || false
    }

    def "a value of one kind fits a field of that kind, within its limits"() {
        expect:
        ConstantFit.fits(constant, field(shape, new HowMany.One(), new Demand.Given(true)), TERMS) == fits

        where:
        constant                                   | shape                                  || fits
        text("hello")                              | new FieldShape.Text(5)                 || true
        text("hello!")                             | new FieldShape.Text(5)                 || false
        text("anything long at all")               | new FieldShape.Text(null)              || true
        new JsonValue.JsonNumber(3G)               | new FieldShape.Text(5)                 || false
        new JsonValue.JsonNumber(-1.25G)           | new FieldShape.Plain(FieldKind.NUMBER) || true
        text("3")                                  | new FieldShape.Plain(FieldKind.NUMBER) || false
        new JsonValue.JsonBoolean(false)           | new FieldShape.Plain(FieldKind.YES_NO) || true
        text("no")                                 | new FieldShape.Plain(FieldKind.YES_NO) || false
        text("2026-02-28")                         | new FieldShape.Plain(FieldKind.DATE)   || true
        new JsonValue.JsonNumber(20260228G)        | new FieldShape.Plain(FieldKind.DATE)   || false
        text("2026-02-28T10:00:00+01:00")          | new FieldShape.Plain(FieldKind.MOMENT) || true
        new JsonValue.JsonNumber(1G)               | new FieldShape.Plain(FieldKind.MOMENT) || false
        new JsonValue.JsonBoolean(true)            | new FieldShape.Plain(FieldKind.NUMBER) || false
        new JsonValue.JsonNumber(new BigDecimal("1" * 38))       | new FieldShape.Plain(FieldKind.NUMBER) || true
        new JsonValue.JsonNumber(new BigDecimal("1" * 39))       | new FieldShape.Plain(FieldKind.NUMBER) || false
        new JsonValue.JsonNumber(new BigDecimal("0." + "1" * 37)) | new FieldShape.Plain(FieldKind.NUMBER) || true
        new JsonValue.JsonNumber(new BigDecimal("0." + "1" * 38)) | new FieldShape.Plain(FieldKind.NUMBER) || false
        new JsonValue.JsonNumber(new BigDecimal("1E+3"))         | new FieldShape.Plain(FieldKind.NUMBER) || false
        text("0001-01-01")                         | new FieldShape.Plain(FieldKind.DATE)   || true
        text("0000-12-31")                         | new FieldShape.Plain(FieldKind.DATE)   || false
        text("2026-2-28")                          | new FieldShape.Plain(FieldKind.DATE)   || false
        text("2026-02-29")                         | new FieldShape.Plain(FieldKind.DATE)   || false
        text("2026-02-28T10:00:00.123456-14:00")   | new FieldShape.Plain(FieldKind.MOMENT) || true
        text("2026-02-28T10:00:00.1234567+01:00")  | new FieldShape.Plain(FieldKind.MOMENT) || false
        text("2026-02-28T10:00:00Z")               | new FieldShape.Plain(FieldKind.MOMENT) || false
        text("2026-02-28T10:00:00-00:00")          | new FieldShape.Plain(FieldKind.MOMENT) || false
        text("2026-02-28T10:00:00+14:01")          | new FieldShape.Plain(FieldKind.MOMENT) || false
        text("2026-02-28")                         | new FieldShape.Plain(FieldKind.MOMENT) || false
        text("Billing")                            | new FieldShape.Term(LIST)              || true
        text("billing")                            | new FieldShape.Term(LIST)              || false
        text("Refund")                             | new FieldShape.Term(LIST)              || false
        text("Refund")                             | new FieldShape.Term(null)              || true
        text("Billing")                            | new FieldShape.Term(
                new EntryVersionId(UUID.fromString("00000007-0000-4000-8000-000000000102")))         || false
    }

    /** A field holding fields is filled by name, with exactly the fields it holds and each fitting its own. */
    def "an object fits a field holding fields when it holds exactly those, each fitting"() {
        given:
        def held = field(new FieldShape.Nested([
                named("sku", new FieldShape.Text(3), new HowMany.One(), new Demand.Given(true)),
                named("note", new FieldShape.Text(10), new HowMany.One(), new Demand.Given(false))]),
                new HowMany.One(), new Demand.Given(true))

        expect:
        ConstantFit.fits(new JsonValue.JsonObject(members), held, TERMS) == fits

        where:
        members                                                                                    || fits
        [member("sku", text("abc")), member("note", NOTHING)]                                      || true
        [member("note", text("x")), member("sku", text("abc"))]                                    || true
        [member("sku", text("abcd")), member("note", NOTHING)]                                     || false
        [member("sku", NOTHING), member("note", NOTHING)]                                          || false
        [member("sku", text("abc"))]                                                               || false
        [member("sku", text("abc")), member("note", NOTHING), member("extra", NOTHING)]            || false
        [member("sku", text("abc")), member("other", NOTHING)]                                     || false
    }

    def "a field holding fields takes an object and nothing else"() {
        expect:
        !ConstantFit.fits(text("{}"), field(new FieldShape.Nested([]), new HowMany.One(), new Demand.Given(false)), TERMS)
    }

    /** How many is counted as the field says it, each item held to what one of them takes. */
    def "a list fits a field holding many when there are few enough of it, each fitting, and some where some must be"() {
        expect:
        ConstantFit.fits(constant, field(new FieldShape.Text(3), new HowMany.Many(most), new Demand.Given(mustBe)), TERMS) ==
                fits

        where:
        constant                                                  | most | mustBe || fits
        new JsonValue.JsonArray([text("a"), text("b")])           | 2    | true   || true
        new JsonValue.JsonArray([text("a"), text("b")])           | 1    | true   || false
        new JsonValue.JsonArray([text("a"), text("b")])           | null | true   || true
        new JsonValue.JsonArray([text("a"), text("long")])        | 5    | true   || false
        new JsonValue.JsonArray([text("a"), NOTHING])             | 5    | false  || false
        new JsonValue.JsonArray([])                               | 5    | true   || false
        new JsonValue.JsonArray([])                               | 5    | false  || true
        text("a")                                                 | 5    | true   || false
    }

    def "a field holding one takes one, never a list of it"() {
        expect:
        !ConstantFit.fits(new JsonValue.JsonArray([text("a")]),
                field(new FieldShape.Text(3), new HowMany.One(), new Demand.Given(true)), TERMS)
    }

    def "line breaks are refused at one-line fields, including fields held inside lists and objects"() {
        given:
        def one = named("title", new FieldShape.Text(20, TextLines.ONE), new HowMany.One(), new Demand.Given(false))
        def several = named("title", new FieldShape.Text(20), new HowMany.One(), new Demand.Given(false))
        def value = text(Character.toString(codePoint))
        def constant = shape == "one" ? value : shape == "many" ? new JsonValue.JsonArray([text("Demo Sample"), value]) :
                new JsonValue.JsonObject([member("title", value), member("other", new JsonValue.JsonNumber(1G))])
        def howMany = shape == "many" ? new HowMany.Many(3) : new HowMany.One()
        def oneField = shape == "nested" ? field(new FieldShape.Nested([one]), howMany, new Demand.Given(false)) :
                field(one.shape(), howMany, one.demand())
        def severalField = shape == "nested" ? field(new FieldShape.Nested([several]), howMany, new Demand.Given(false)) :
                field(several.shape(), howMany, several.demand())

        expect:
        ConstantFit.breaksTextLines(constant, oneField)
        !ConstantFit.breaksTextLines(constant, severalField)

        where:
        [codePoint, shape] << [[0x0D, 0x0A, 0x0B, 0x0C, 0x85, 0x2028, 0x2029], ["one", "many", "nested"]].combinations()
    }

    def "a known one-line child is checked even when its object holds other mismatches"() {
        given:
        def child = named("title", new FieldShape.Text(20, TextLines.ONE), new HowMany.Many(2), new Demand.Given(false))
        def held = field(new FieldShape.Nested([child]), new HowMany.Many(2), new Demand.Given(false))
        def constant = new JsonValue.JsonArray([new JsonValue.JsonObject([
                member("title", new JsonValue.JsonArray([text("Demo Sample"), text("last\n")])),
                member("undeclared", new JsonValue.JsonBoolean(true))])])

        expect:
        ConstantFit.breaksTextLines(constant, held)
        !ConstantFit.fits(constant, held, [:])
    }

    def "a line-break check leaves other mismatches for judging the draft's content"() {
        given:
        def one = field(new FieldShape.Text(3, TextLines.ONE), new HowMany.One(), new Demand.Given(true))
        def nested = field(new FieldShape.Nested([
                named("title", one.shape(), one.howMany(), one.demand())]), new HowMany.One(), new Demand.Given(true))
        def held = shape == "nested" ? nested : shape == "many" ?
                field(one.shape(), new HowMany.Many(2), one.demand()) : shape == "number" ?
                field(new FieldShape.Plain(FieldKind.NUMBER), new HowMany.One(), one.demand()) : one

        expect:
        !ConstantFit.breaksTextLines(constant, held)
        !ConstantFit.fits(constant, held, [:])

        where:
        shape    | constant
        "one"    | NOTHING
        "one"    | new JsonValue.JsonNumber(1G)
        "one"    | text("longer")
        "one"    | new JsonValue.JsonArray([text("last\n")])
        "many"   | text("last\n")
        "number" | text("last\n")
        "nested" | text("last\n")
        "nested" | new JsonValue.JsonObject([member("unknown", text("last\n"))])
        "nested" | new JsonValue.JsonObject([])
    }

    def "what is held to what is never absent"() {
        when:
        ConstantFit."${asked}"(*arguments)

        then:
        def refused = thrown(NullPointerException)
        refused.message == expectedMessage

        where:
        asked        | arguments                                                                   || expectedMessage
        "unwritable" | [null]                                                                      || "ConstantFit constant must not be null"
        "textLength" | [null]                                                                      || "ConstantFit constant must not be null"
        "concealing" | [null]                                                                      || "ConstantFit constant must not be null"
        "fits"       | [null, field(new FieldShape.Text(1), new HowMany.One(), new Demand.Given(false)), [:]] || "ConstantFit constant must not be null"
        "fits"       | [NOTHING, null, [:]]                                                        || "ConstantFit field must not be null"
        "fits"       | [NOTHING, field(new FieldShape.Text(1), new HowMany.One(), new Demand.Given(false)), null] || "ConstantFit terms must not be null"
        "breaksTextLines" | [null, field(new FieldShape.Text(1), new HowMany.One(), new Demand.Given(false))] || "ConstantFit constant must not be null"
        "breaksTextLines" | [NOTHING, null] || "ConstantFit field must not be null"
    }

    private static JsonValue text(String value) {
        new JsonValue.JsonString(value)
    }

    private static JsonValue.JsonMember member(String name, JsonValue value) {
        new JsonValue.JsonMember(name, value)
    }

    private static Field field(FieldShape shape, HowMany howMany, Demand demand) {
        named("value", shape, howMany, demand)
    }

    private static Field named(String name, FieldShape shape, HowMany howMany, Demand demand) {
        new Field(new FieldName(name), null, null, shape, howMany, demand)
    }
}
