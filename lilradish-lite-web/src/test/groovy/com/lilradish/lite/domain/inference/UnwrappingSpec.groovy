package com.lilradish.lite.domain.inference

import static com.lilradish.lite.testutil.inference.Askings.CATEGORIES
import static com.lilradish.lite.testutil.inference.Askings.GIVES
import static com.lilradish.lite.testutil.inference.Askings.held
import static com.lilradish.lite.testutil.inference.Askings.plain
import static com.lilradish.lite.testutil.inference.Askings.term
import static com.lilradish.lite.testutil.inference.Askings.text

import com.lilradish.lite.domain.declaration.AskedField
import com.lilradish.lite.domain.declaration.FieldKind
import com.lilradish.lite.domain.declaration.FieldName
import com.lilradish.lite.domain.declaration.TextLines
import com.lilradish.lite.domain.wire.JsonValue
import com.lilradish.lite.testutil.inference.Json
import spock.lang.Specification

class UnwrappingSpec extends Specification {

    static final Map VALUES = [summary: "hello", tags: ["Billing"], amount: 12.5, due: "2024-03-31",
                               at: "2024-03-31T09:30:00+02:00", urgent: true, details: [product: "abc", codes: ["ab"]]]

    /** What a code step gives: one that must be given, fields held once and many times, many in many, two deep. */
    static final List<AskedField> STEP_GIVES = [
            text("summary", 5, null, false, true),
            term("tags", CATEGORIES, 2),
            plain("amount", FieldKind.NUMBER),
            held("details", [text("product", 3), text("codes", 2, 2)]),
            held("parts", [text("name", 3, null, false, true), text("note", 3), text("codes", 2, 2),
                           held("maker", [text("city", 3)])], 2),
            held("owner", [text("name", 3, null, false, true), held("address", [text("city", 3)])])]

    static final Map STEP = [summary: "hello", tags: ["Billing"], amount: 12.5, details: [product: "abc", codes: ["ab"]],
                             parts: [[name: "x", note: "n", codes: ["ab"], maker: [city: "rom"]]],
                             owner: [name: "ann", address: [city: "rom"]]]

    static final Map SURE = [summary: 90]

    /** What a search gives: text that must be given, asking no confidence. */
    static final List<AskedField> SEARCHED = [text("title", 5, null, false, true), text("body", 5, null, false, true)]

    static final List<FieldName> DECIDING = [new FieldName("summary"), new FieldName("tags")]

    static final String GRINNING = Character.toString(0x1F600)

    static final String NUL = Character.toString(0x0)

    static final String HIGH = Character.toString(0xD800)

    static final String LOW = Character.toString(0xDC00)

    static final String HIGH_OF_GRINNING = Character.toString(0xD83D)

    /** A no-break space and a line separator: no control, so words may hold them beside what shows. */
    static final String SEPARATORS = Character.toString(0xA0) + Character.toString(0x2028)

    /** Each draws nothing in prose: a space of each kind, a zero-width space, and a tab and a line feed. */
    static final List<String> SHOWING_NOTHING = [" ", Character.toString(0xA0), Character.toString(0x3000),
                                                 Character.toString(0x200B), "\t\n"]

    /** Kinds the model can write as text, excluding fields and files. */
    static final List<FieldKind> WRITTEN_KINDS = (FieldKind.values() as List) - [FieldKind.FIELDS, FieldKind.FILE]

    /** For each kind written as text, a value that shows and fits. */
    static final Map<FieldKind, Object> SHOWING = [(FieldKind.TEXT): "a", (FieldKind.TERM): "Billing",
                                                   (FieldKind.NUMBER): 1, (FieldKind.DATE): "2024-01-01",
                                                   (FieldKind.MOMENT): "2024-03-31T09:30:00+02:00",
                                                   (FieldKind.YES_NO): true]

    /** Each holds nothing, at every depth, whatever order its members come in. */
    static final List<Map> HOLDING_NOTHING = [[product: null, codes: null, maker: null],
                                              [product: " ", codes: [], maker: [city: ""]],
                                              [codes: [], maker: [city: null], product: ""],
                                              [maker: [city: "\t\n"], product: null, codes: null]]

    static final Map HOLDING_SOMETHING = [product: "b", codes: null, maker: null]

    def "production and search reject a line break before folding optional blank text to nothing"() {
        given:
        def one = new AskedField(new FieldName("title"), FieldKind.TEXT, 30, null, null, [], false, false, TextLines.ONE)
        def said = Character.toString(codePoint)

        expect:
        Unwrapping.production([one], answer([title: said], [:])) ==
                new DoesNotFit(DidNotFitReason.LINE_BREAK)
        Unwrapping.production([one], answer([title: "Demo Sample" + said], [:])) ==
                new DoesNotFit(DidNotFitReason.LINE_BREAK)
        Unwrapping.searched([one], object([title: said])) ==
                new DoesNotFit(DidNotFitReason.LINE_BREAK)
        Unwrapping.given([one], object([title: said])).reason() == DidNotFitReason.LINE_BREAK
        Unwrapping.given([one], object([title: said])).path() == [new FieldName("title")]
        Unwrapping.production([one], answer([title: "Demo Sample"], [:])) instanceof ProductionAnswer.Produced

        where:
        codePoint << [0x0D, 0x0A, 0x0B, 0x0C, 0x85, 0x2028, 0x2029]
    }

    def "an answer holding every field given back, and how sure it was where asked, is unwrapped as it came"() {
        when:
        def unwrapped = Unwrapping.production(GIVES, answer(values, confidences))

        then:
        unwrapped instanceof ProductionAnswer.Produced
        def produced = unwrapped as ProductionAnswer.Produced
        produced.values().collectEntries { [(it.key.value()): it.value] } == GIVES.collectEntries {
            [(it.name().value()): Json.of(values[it.name().value()])]
        }
        produced.confidences() == [(new FieldName("summary")): new Confidence(percent)]

        where:
        values                                                                  | confidences     || percent
        VALUES                                                                  | SURE            || 90
        nothing()                                                               | [summary: 0]    || 0
        reversed()                                                              | [summary: 100]  || 100
        changed(tags: [])                                                       | SURE            || 90
        changed(tags: ["Delivery", "Billing"])                                  | SURE            || 90
        changed(summary: GRINNING * 5)                                          | SURE            || 90
        changed(details: [product: null, codes: ["ab"]])                       | SURE            || 90
        changed(details: [codes: [], product: "a"])                            | SURE            || 90
        changed(details: [codes: [], product: "a\tb"])                          | SURE            || 90
        changed(amount: new BigDecimal("-0." + "0" * 36 + "1"))                  | SURE            || 90
        changed(at: "9999-12-31T23:59:59.999999-14:00")                         | SURE            || 90
        changed(at: "2024-03-31T09:30:00+00:00")                                | SURE            || 90
    }

    def "an answer that is not what the envelope asked for does not fit, naming the first thing found wrong"() {
        expect:
        Unwrapping.production(GIVES, Json.of(answered)) == new DoesNotFit(reason)

        where:
        answered                                                              || reason
        "hello"                                                               || DidNotFitReason.NOT_THE_SHAPE
        [VALUES, SURE]                                                        || DidNotFitReason.NOT_THE_SHAPE
        [values: VALUES]                                                      || DidNotFitReason.NOT_THE_SHAPE
        [confidences: SURE]                                                   || DidNotFitReason.NOT_THE_SHAPE
        [values: VALUES, confidences: SURE, note: "sure"]                     || DidNotFitReason.NOT_THE_SHAPE
        [values: VALUES, sure: SURE]                                          || DidNotFitReason.NOT_THE_SHAPE
        [values: [VALUES], confidences: SURE]                                 || DidNotFitReason.NOT_THE_SHAPE
        [values: VALUES, confidences: [90]]                                   || DidNotFitReason.NOT_THE_SHAPE
        [values: VALUES, confidences: null]                                   || DidNotFitReason.NOT_THE_SHAPE
        [values: changed(colour: "red"), confidences: SURE]                   || DidNotFitReason.FIELD_UNKNOWN
        [values: changed(details: [product: "a", codes: [], size: 1]), confidences: SURE] || DidNotFitReason.FIELD_UNKNOWN
        [values: without("amount"), confidences: SURE]                        || DidNotFitReason.FIELD_MISSING
        [values: changed(details: [product: "a"]), confidences: SURE]         || DidNotFitReason.FIELD_MISSING
        [values: changed(summary: 5), confidences: SURE]                      || DidNotFitReason.NOT_ITS_KIND
        [values: changed(summary: ["hello"]), confidences: SURE]              || DidNotFitReason.NOT_ITS_KIND
        [values: changed(tags: "Billing"), confidences: SURE]                 || DidNotFitReason.NOT_ITS_KIND
        [values: changed(tags: [null]), confidences: SURE]                    || DidNotFitReason.NOT_ITS_KIND
        [values: changed(amount: "12.5"), confidences: SURE]                  || DidNotFitReason.NOT_ITS_KIND
        [values: changed(amount: new BigDecimal("1E+3")), confidences: SURE]  || DidNotFitReason.NOT_ITS_KIND
        [values: changed(amount: new BigDecimal("1" * 39)), confidences: SURE] || DidNotFitReason.NOT_ITS_KIND
        [values: changed(due: "2024-3-31"), confidences: SURE]                || DidNotFitReason.NOT_ITS_KIND
        [values: changed(at: "2024-03-31T09:30:00Z"), confidences: SURE]      || DidNotFitReason.NOT_ITS_KIND
        [values: changed(at: "2024-03-31T09:30:00-00:00"), confidences: SURE] || DidNotFitReason.NOT_ITS_KIND
        [values: changed(urgent: "yes"), confidences: SURE]                   || DidNotFitReason.NOT_ITS_KIND
        [values: changed(details: "abc"), confidences: SURE]                  || DidNotFitReason.NOT_ITS_KIND
        [values: changed(details: [product: 1, codes: []]), confidences: SURE] || DidNotFitReason.NOT_ITS_KIND
        [values: changed(summary: "hello!"), confidences: SURE]               || DidNotFitReason.TOO_LONG
        [values: changed(summary: GRINNING * 6), confidences: SURE]           || DidNotFitReason.TOO_LONG
        [values: changed(details: [product: "abcd", codes: []]), confidences: SURE] || DidNotFitReason.TOO_LONG
        [values: changed(details: [product: "a", codes: ["abc"]]), confidences: SURE] || DidNotFitReason.TOO_LONG
        [values: changed(tags: ["Billing", "Delivery", "Billing"]), confidences: SURE] || DidNotFitReason.TOO_MANY
        [values: changed(details: [product: "a", codes: ["a", "b", "c"]]), confidences: SURE] || DidNotFitReason.TOO_MANY
        [values: changed(tags: ["billing"]), confidences: SURE]               || DidNotFitReason.NOT_A_TERM
        [values: changed(tags: ["Other"]), confidences: SURE]                 || DidNotFitReason.NOT_A_TERM
        [values: changed(tags: ["Billing "]), confidences: SURE]              || DidNotFitReason.NOT_A_TERM
        [values: changed(summary: "a" + NUL + "b"), confidences: SURE]        || DidNotFitReason.UNKEEPABLE
        [values: changed(summary: "a" + HIGH + "b"), confidences: SURE]       || DidNotFitReason.UNKEEPABLE
        [values: changed(summary: "ab" + LOW), confidences: SURE]             || DidNotFitReason.UNKEEPABLE
        [values: changed(tags: [NUL]), confidences: SURE]                     || DidNotFitReason.UNKEEPABLE
        [values: changed(details: [product: HIGH_OF_GRINNING, codes: []]), confidences: SURE] || DidNotFitReason.UNKEEPABLE
        [values: VALUES, confidences: [:]]                                    || DidNotFitReason.CONFIDENCE_MISSING
        [values: VALUES, confidences: [summary: 90, amount: 50]]              || DidNotFitReason.CONFIDENCE_UNASKED
        [values: VALUES, confidences: [summary: 90, colour: 50]]              || DidNotFitReason.CONFIDENCE_UNASKED
        [values: VALUES, confidences: [summary: 101]]                         || DidNotFitReason.CONFIDENCE_NOT_A_PERCENT
        [values: VALUES, confidences: [summary: -1]]                          || DidNotFitReason.CONFIDENCE_NOT_A_PERCENT
        [values: VALUES, confidences: [summary: 90.5]]                        || DidNotFitReason.CONFIDENCE_NOT_A_PERCENT
        [values: VALUES, confidences: [summary: 90.0]]                        || DidNotFitReason.CONFIDENCE_NOT_A_PERCENT
        [values: VALUES, confidences: [summary: "90"]]                        || DidNotFitReason.CONFIDENCE_NOT_A_PERCENT
        [values: VALUES, confidences: [summary: null]]                        || DidNotFitReason.CONFIDENCE_NOT_A_PERCENT
    }

    /** Two things wrong at once, so the one named says which is read first. */
    def "an answer wrong twice over does not fit for the first found: values before confidences, unknown before any field"() {
        expect:
        Unwrapping.production(GIVES, Json.of(answered)) == new DoesNotFit(reason)

        where:
        answered                                                                         || reason
        [values: changed(summary: 5), confidences: [:]]                                  || DidNotFitReason.NOT_ITS_KIND
        [values: without("amount") + [colour: "red"], confidences: SURE]                 || DidNotFitReason.FIELD_UNKNOWN
        [values: without("amount") + [summary: 5], confidences: SURE]                    || DidNotFitReason.NOT_ITS_KIND
        [values: without("summary") + [amount: "x"], confidences: SURE]                  || DidNotFitReason.FIELD_MISSING
        [values: changed(summary: "abcde" + NUL), confidences: SURE]                     || DidNotFitReason.UNKEEPABLE
        [values: changed(details: [product: "a", codes: [" " * 3]]), confidences: SURE]  || DidNotFitReason.NOTHING_GIVEN
        [values: changed(tags: ["Other", "Billing", "Billing"]), confidences: SURE]      || DidNotFitReason.TOO_MANY
    }

    def "a value that must be given does not fit arriving as nothing, at any depth, and one that need not fits"() {
        given:
        def gives = [text("summary", 5, 2, false, summaryMust),
                     held("details", [text("product", 3, null, false, true), text("note", 3)], null, detailsMust)]

        when:
        def unwrapped = Unwrapping.production(gives, Json.of([values: values, confidences: [:]]))

        then:
        (unwrapped == new DoesNotFit(DidNotFitReason.NOTHING_GIVEN)) == refused
        (unwrapped instanceof ProductionAnswer.Produced) == !refused

        where:
        summaryMust | detailsMust | values                                                 || refused
        true        | false       | [summary: null, details: null]                         || true
        true        | false       | [summary: [], details: null]                           || true
        true        | false       | [summary: ["a"], details: null]                        || false
        false       | false       | [summary: null, details: null]                         || false
        false       | false       | [summary: [], details: null]                           || false
        false       | true        | [summary: null, details: null]                         || true
        false       | false       | [summary: null, details: [product: null, note: "n"]]   || true
        false       | false       | [summary: null, details: [note: "n", product: null]]   || true
        false       | false       | [summary: null, details: [product: "a", note: null]]   || false
    }

    /** Text of nothing that shows says no more than none; one of many is always one that must be given. */
    def "a text a model gives that shows nothing does not fit as nothing given where it must be given, at any depth, and as one of many whether or not it must be given"() {
        given:
        def gives = demanding(mustBeGiven)

        when:
        def unwrapped = Unwrapping.production(gives, answer(placed(at, blank), SURE))

        then:
        unwrapped == new DoesNotFit(DidNotFitReason.NOTHING_GIVEN)

        and: "the same answer with something that shows in that place fits"
        Unwrapping.production(gives, answer(placed(at, "a"), SURE)) instanceof ProductionAnswer.Produced

        where:
        [blank, at, mustBeGiven] << [SHOWING_NOTHING + [""], ["summary", "product", "codes"], [true, false]]
                .combinations().findAll { combination -> combination[2] || combination[1] == "codes" }
    }

    def "a text a model gives that shows nothing, where it need not be given, fits holding none at any depth, every other value kept as it came"() {
        when:
        def unwrapped = Unwrapping.production(demanding(false), answer(placed(at, blank), SURE))

        then:
        unwrapped instanceof ProductionAnswer.Produced
        def produced = unwrapped as ProductionAnswer.Produced
        produced.values() == placed(at, null).collectEntries { [(new FieldName(it.key as String)): Json.of(it.value)] }
        produced.confidences() == [(new FieldName("summary")): new Confidence(90)]

        where:
        [blank, at] << [SHOWING_NOTHING + [""], ["summary", "product"]].combinations()
    }

    def "a text a model gives that shows nothing in one item of many fields, where it need not be given, is none in that item alone"() {
        when:
        def unwrapped = Unwrapping.production(STEP_GIVES, answer(stepChanged(parts: given), [:]))

        then:
        unwrapped == new ProductionAnswer.Produced(
                stepChanged(parts: read).collectEntries { [(new FieldName(it.key as String)): Json.of(it.value)] }, [:])

        where:
        given                                                                         || read
        [[name: "x", note: " ", codes: [], maker: null], STEP.parts[0]]                || [[name: "x", note: null, codes: [], maker: null], STEP.parts[0]]
        [STEP.parts[0], [name: "y", note: "", codes: null, maker: [city: "rom"]]]      || [STEP.parts[0], [name: "y", note: null, codes: null, maker: [city: "rom"]]]
    }

    /** By ruling, a control and a line or paragraph separator are not text that shows nothing. */
    def "a text a model gives of only a control or a separator is not nothing given, and is kept as it came whether or not it must be given"() {
        when:
        def unwrapped = Unwrapping.production(demanding(mustBeGiven), answer(changed(summary: said), SURE))

        then:
        unwrapped instanceof ProductionAnswer.Produced
        (unwrapped as ProductionAnswer.Produced).values()[new FieldName("summary")] == new JsonValue.JsonString(said)

        where:
        [said, mustBeGiven] << [[Character.toString(0x1), Character.toString(0x85), Character.toString(0x2028),
                                 Character.toString(0x2029)], [true, false]].combinations()
    }

    def "a file value in a model answer is an unknown member"() {
        given:
        def file = [id: "00000000-0000-4000-8000-000000000001", name: "Demo Sample.pdf",
                    type: "application/pdf", size: 12]

        expect:
        Unwrapping.production([asked(FieldKind.FILE, null, true)], answer([value: file], [:])) ==
                new DoesNotFit(DidNotFitReason.FIELD_UNKNOWN)
    }

    def "a value of any kind but fields a model writes as text that shows nothing, where it need not be given, fits as none before its kind is asked"() {
        when:
        def unwrapped = Unwrapping.production([asked(kind, null, false), GIVES[0]],
                answer([value: blank, summary: "hello"], SURE))

        then:
        unwrapped == new ProductionAnswer.Produced(
                [(new FieldName("value")): new JsonValue.JsonNull(), (new FieldName("summary")): Json.of("hello")],
                [(new FieldName("summary")): new Confidence(90)])

        where:
        [kind, blank] << [WRITTEN_KINDS, SHOWING_NOTHING + [""]].combinations()
    }

    /** One of many is always one that must be given, so a list never holds a gap. */
    def "a value of any kind but fields a model writes as text that shows nothing does not fit as nothing given where it must be given, and as one of many whether or not it must be given"() {
        given:
        def gives = [asked(kind, most, mustBeGiven)]

        when:
        def unwrapped = Unwrapping.production(gives, answer([value: placedIn(most, blank, SHOWING[kind])], [:]))

        then:
        unwrapped == new DoesNotFit(DidNotFitReason.NOTHING_GIVEN)

        and: "the same answer with a value that shows in that place fits"
        Unwrapping.production(gives, answer([value: placedIn(most, SHOWING[kind], SHOWING[kind])], [:])) instanceof
                ProductionAnswer.Produced

        where:
        [kind, blank, most, mustBeGiven] << [WRITTEN_KINDS, SHOWING_NOTHING + [""], [null, 2], [true, false]]
                .combinations()
                .findAll { combination -> combination[3] || combination[2] != null }
    }

    def "fields written as blank text by a model are nothing before their shape is judged"() {
        given:
        def gives = [held("details", [text("body", 5)], most, mustBeGiven)]

        when:
        def unwrapped = Unwrapping.production(gives, answer([details: blank], [:]))

        then:
        if (mustBeGiven) {
            assert unwrapped == new DoesNotFit(DidNotFitReason.NOTHING_GIVEN)
        } else {
            assert unwrapped instanceof ProductionAnswer.Produced
            assert (unwrapped as ProductionAnswer.Produced).values()[new FieldName("details")] == new JsonValue.JsonNull()
        }

        where:
        [blank, most, mustBeGiven] << [SHOWING_NOTHING + [""], [null, 2], [true, false]].combinations()
    }

    def "a blank text item of many fields is nothing given, while code keeps its shape rule"() {
        given:
        def gives = [held("details", [text("body", 5)], 2)]

        expect:
        Unwrapping.production(gives, answer([details: [[body: "a"], blank]], [:])) ==
                new DoesNotFit(DidNotFitReason.NOTHING_GIVEN)
        Unwrapping.given(gives, object([details: [[body: "a"], blank]])).reason() ==
                DidNotFitReason.NOT_ITS_KIND

        where:
        blank << ["", " ", Character.toString(0x3164)]
    }

    /** Nothing is trimmed: only a value that shows nothing at all is nothing given. */
    def "a value a model gives that shows something, or is not text where its kind is not, is judged by its kind as it came"() {
        expect:
        Unwrapping.production([asked(kind, null, false)], answer([value: said], [:])) == new DoesNotFit(reason)

        where:
        kind                | said                          || reason
        FieldKind.DATE      | " 2024-01-01 "                || DidNotFitReason.NOT_ITS_KIND
        FieldKind.DATE      | "2024-01-01\n"                || DidNotFitReason.NOT_ITS_KIND
        FieldKind.DATE      | "2024-1-1"                    || DidNotFitReason.NOT_ITS_KIND
        FieldKind.MOMENT    | " 2024-03-31T09:30:00+02:00"  || DidNotFitReason.NOT_ITS_KIND
        FieldKind.NUMBER    | " 12 "                        || DidNotFitReason.NOT_ITS_KIND
        FieldKind.NUMBER    | "12"                          || DidNotFitReason.NOT_ITS_KIND
        FieldKind.YES_NO    | "true"                        || DidNotFitReason.NOT_ITS_KIND
        FieldKind.YES_NO    | 0                             || DidNotFitReason.NOT_ITS_KIND
        FieldKind.TERM      | " Billing"                    || DidNotFitReason.NOT_A_TERM
        FieldKind.TERM      | false                         || DidNotFitReason.NOT_ITS_KIND
        FieldKind.TEXT      | 5                             || DidNotFitReason.NOT_ITS_KIND
        FieldKind.TEXT      | []                            || DidNotFitReason.NOT_ITS_KIND
        FieldKind.FIELDS    | "visible"                     || DidNotFitReason.NOT_ITS_KIND
    }

    def "fields a model gives every one of which is nothing, where they need not be given, fit as none whatever each of them must be, at any depth"() {
        when:
        def unwrapped = Unwrapping.production(nothingHolding(productMust, codesMust, false),
                answer([summary: "hello", details: details], [:]))

        then:
        unwrapped == new ProductionAnswer.Produced(
                [(new FieldName("summary")): Json.of("hello"), (new FieldName("details")): new JsonValue.JsonNull()], [:])

        where:
        [details, productMust, codesMust] << [HOLDING_NOTHING, [true, false], [true, false]].combinations()
    }

    def "fields a model gives every one of which is nothing do not fit as nothing given where they must be given, and as one of many whether or not it must be given"() {
        given:
        def gives = nothingHolding(false, false, detailsMust, most)

        when:
        def unwrapped = Unwrapping.production(gives,
                answer([summary: "hello", details: placedIn(most, details, HOLDING_SOMETHING)], [:]))

        then:
        unwrapped == new DoesNotFit(DidNotFitReason.NOTHING_GIVEN)

        and: "the same answer with something that shows in those fields fits"
        Unwrapping.production(gives, answer(
                [summary: "hello", details: placedIn(most, details + [product: "a"], HOLDING_SOMETHING)], [:])) instanceof
                ProductionAnswer.Produced

        where:
        [details, most, detailsMust] << [HOLDING_NOTHING, [null, 2], [true, false]].combinations()
                .findAll { combination -> combination[2] || combination[1] != null }
    }

    def "fields a model gives that hold something are a value, whether or not they must be given, what in them is nothing kept as none"() {
        when:
        def unwrapped = Unwrapping.production(nothingHolding(false, false, detailsMust),
                answer([summary: "hello", details: details], [:]))

        then:
        unwrapped == new ProductionAnswer.Produced(
                [(new FieldName("summary")): Json.of("hello"), (new FieldName("details")): Json.of(kept)], [:])

        where:
        detailsMust | details                                        || kept
        true        | [product: null, codes: ["ab"], maker: null]    || [product: null, codes: ["ab"], maker: null]
        false       | [product: " ", codes: [], maker: [city: "r"]]  || [product: null, codes: [], maker: [city: "r"]]
        true        | [product: "", codes: ["ab"], maker: [city: ""]] || [product: null, codes: ["ab"], maker: null]
        false       | [product: null, codes: null, maker: [city: "r"]] || [product: null, codes: null, maker: [city: "r"]]
    }

    /** Read in declared order, so whatever is first found wrong is named, a field given nothing as any other. */
    def "fields a model gives that hold something, or hold what does not fit, are no gap: one in them that must be given yet is nothing does not fit as nothing given, before what shows or after it"() {
        expect:
        Unwrapping.production(gives, answer([summary: "hello", details: details], [:])) == new DoesNotFit(reason)

        where:
        gives                                | details                                          || reason
        nothingHolding(true, false, false)   | [product: " ", codes: ["ab"], maker: null]       || DidNotFitReason.NOTHING_GIVEN
        nothingHolding(true, false, false)   | [product: null, codes: null, maker: [city: "r"]] || DidNotFitReason.NOTHING_GIVEN
        nothingHolding(false, true, false)   | [product: "a", codes: [], maker: null]           || DidNotFitReason.NOTHING_GIVEN
        nothingHolding(false, true, false)   | [product: "a", codes: null, maker: null]         || DidNotFitReason.NOTHING_GIVEN
        nothingHolding(true, false, false)   | [product: null, codes: ["abc"], maker: null]     || DidNotFitReason.NOTHING_GIVEN
        nothingHolding(true, false, false)   | [product: null, codes: null, maker: [city: 5]]   || DidNotFitReason.NOTHING_GIVEN
        nothingHolding(false, true, false)   | [product: "abcd", codes: [], maker: null]        || DidNotFitReason.TOO_LONG
        nothingHolding(false, false, false)  | [product: null, codes: null, maker: [city: 5]]   || DidNotFitReason.NOT_ITS_KIND
        nothingHolding(false, false, false)  | [product: null, codes: [" "], maker: null]       || DidNotFitReason.NOTHING_GIVEN
        nothingHolding(true, false, false)   | [product: " ", maker: null]                      || DidNotFitReason.NOTHING_GIVEN
    }

    def "fields a model rebuilt to hold none keep their members in the order they came"() {
        when:
        def unwrapped = Unwrapping.production(nothingHolding(false, false, false),
                answer([summary: "hello", details: given], [:]))

        then:
        (unwrapped as ProductionAnswer.Produced).values()[new FieldName("details")] == Json.of(read)

        where:
        given                                               || read
        [codes: ["ab"], product: " ", maker: null]          || [codes: ["ab"], product: null, maker: null]
        [product: " ", maker: [city: "r"], codes: ["ab"]]   || [product: null, maker: [city: "r"], codes: ["ab"]]
    }

    /** A control is kept as its six-character escape, so its count kept runs six to each one written. */
    def "a value the store would keep longer than it keeps any one value does not fit, once its field's own rules pass, and one at that length fits"() {
        given:
        def gives = [text("letter", 8_388_612)]
        def said = Character.toString(0x1) * 8_388_607 + "a" * letters

        when:
        def unwrapped = Unwrapping.production(gives, Json.of([values: [letter: said], confidences: [:]]))

        then:
        (unwrapped instanceof DoesNotFit ? (unwrapped as DoesNotFit).reason() : null) == reason
        (unwrapped instanceof ProductionAnswer.Produced) == (reason == null)

        where:
        letters || reason
        4       || null
        5       || DidNotFitReason.TOO_LONG_TO_KEEP
        6       || DidNotFitReason.TOO_LONG
    }

    def "the longest text a field holds fits with every character of it escaped, kept as it came"() {
        given:
        def letter = '"' * 8_388_608

        when:
        def unwrapped = Unwrapping.production([text("letter", 8_388_608)],
                Json.of([values: [letter: letter], confidences: [:]]))

        then:
        unwrapped instanceof ProductionAnswer.Produced
        (unwrapped as ProductionAnswer.Produced).values() == [(new FieldName("letter")): new JsonValue.JsonString(letter)]
    }

    def "a model gives only non-file siblings at every depth and never a file confidence"() {
        given:
        def file = new AskedField(new FieldName('attachment'), FieldKind.FILE, null, null, null, [], true, true)
        def gives = [file, text('summary', 5, null, true, true), held('details', [file, text('note', 5)]),
                     held('rows', [file, text('note', 5)], 2)]
        def values = [summary: 'hello', details: [note: 'one'], rows: [[note: 'two']]]

        when:
        def read = Unwrapping.production(gives, Json.of([values: values, confidences: [summary: 90]]))

        then:
        read == new ProductionAnswer.Produced(values.collectEntries { [(new FieldName(it.key)): Json.of(it.value)] },
                [(new FieldName('summary')): new Confidence(90)])
    }

    def "a file member or its confidence in a model answer is unasked even when the declaration holds it"() {
        given:
        def file = new AskedField(new FieldName('attachment'), FieldKind.FILE, null, null, null, [], false, true)
        def gives = [file, text('summary', 5), held('details', [file, text('note', 5)]),
                     held('rows', [file, text('note', 5)], 2)]
        def values = [summary: 'hello', details: [note: 'one'], rows: [[note: 'two']]]
        def confidences = [:]
        switch (place) {
            case 'top': values.attachment = null; break
            case 'nested': values.details.attachment = null; break
            case 'many': values.rows[0].attachment = null; break
            case 'confidence': confidences.attachment = 90; break
        }

        expect:
        Unwrapping.production(gives, Json.of([values: values, confidences: confidences])) == new DoesNotFit(reason)

        where:
        place        || reason
        'top'        || DidNotFitReason.FIELD_UNKNOWN
        'nested'     || DidNotFitReason.FIELD_UNKNOWN
        'many'       || DidNotFitReason.FIELD_UNKNOWN
        'confidence' || DidNotFitReason.CONFIDENCE_UNASKED
    }

    def "a search neither requires nor accepts file members or their declared confidence"() {
        given:
        def file = new AskedField(new FieldName('attachment'), FieldKind.FILE, null, null, null, [], true, true)
        def gives = [file, text('title', 5, null, false, true), held('details', [file, text('note', 5)])]

        expect:
        Unwrapping.searched(gives, Json.of(values)) == expected

        where:
        values                                                    || expected
        [title: 'hello', details: [note: 'one']]                   || new ProductionAnswer.Produced([(new FieldName('title')): Json.of('hello'), (new FieldName('details')): Json.of([note: 'one'])], [:])
        [title: 'hello', details: [note: 'one'], attachment: null] || new DoesNotFit(DidNotFitReason.FIELD_UNKNOWN)
        [title: 'hello', details: [note: 'one', attachment: null]] || new DoesNotFit(DidNotFitReason.FIELD_UNKNOWN)
    }

    def "a search answering every field is read as it came, in any order, with no confidence"() {
        when:
        def unwrapped = Unwrapping.searched(SEARCHED, Json.of(answered))

        then:
        unwrapped == new ProductionAnswer.Produced(
                [(new FieldName("title")): Json.of(title), (new FieldName("body")): Json.of(body)], [:])

        where:
        answered                                || title       | body
        [title: "hello", body: "world"]         || "hello"     | "world"
        [body: "world", title: "hello"]         || "hello"     | "world"
        [title: GRINNING * 5, body: " a\t"]     || GRINNING * 5 | " a\t"
    }

    def "a search's answer that is not the fields asked does not fit, naming the first thing found wrong as a model's values are read"() {
        expect:
        Unwrapping.searched(SEARCHED, Json.of(answered)) == new DoesNotFit(reason)

        where:
        answered                                                  || reason
        null                                                      || DidNotFitReason.NOT_THE_SHAPE
        "hello"                                                   || DidNotFitReason.NOT_THE_SHAPE
        [[title: "a", body: "b"]]                                 || DidNotFitReason.NOT_THE_SHAPE
        [values: [title: "a", body: "b"], confidences: [:]]       || DidNotFitReason.FIELD_UNKNOWN
        [title: "a", body: "b", note: "c"]                        || DidNotFitReason.FIELD_UNKNOWN
        [body: "b", note: "c"]                                    || DidNotFitReason.FIELD_UNKNOWN
        [title: "a"]                                              || DidNotFitReason.FIELD_MISSING
        [title: null, body: "b"]                                  || DidNotFitReason.NOTHING_GIVEN
        [title: " ", body: "b"]                                   || DidNotFitReason.NOTHING_GIVEN
        [title: "", body: "b"]                                    || DidNotFitReason.NOTHING_GIVEN
        [title: 5, body: "b"]                                     || DidNotFitReason.NOT_ITS_KIND
        [title: ["a"], body: "b"]                                 || DidNotFitReason.NOT_ITS_KIND
        [title: "a" + NUL, body: "b"]                             || DidNotFitReason.UNKEEPABLE
        [title: "a" + HIGH, body: "b"]                            || DidNotFitReason.UNKEEPABLE
        [title: "hello!", body: "b"]                              || DidNotFitReason.TOO_LONG
        [title: "hello!", body: null]                             || DidNotFitReason.TOO_LONG
    }

    def "a search's text that shows nothing, where it need not be given, is kept as none beside one that must be given kept as it came"() {
        when:
        def unwrapped = Unwrapping.searched([SEARCHED[0], text("other", 5)], Json.of([title: "hello", other: blank]))

        then:
        unwrapped == new ProductionAnswer.Produced(
                [(new FieldName("title")): Json.of("hello"), (new FieldName("other")): new JsonValue.JsonNull()], [:])

        where:
        blank << SHOWING_NOTHING + [""]
    }

    def "a search read against a field asking how sure its maker was is refused by the field's name"() {
        when:
        Unwrapping.searched([SEARCHED[0], text("body", 5, null, true, true)], Json.of([title: "a", body: "b"]))

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message.contains("body")
        !refused.message.contains("title")
    }

    def "what a code step gave back fits, kept as it came where whole, and otherwise holding its members in the order they came, then none for each left out in declared order, at any depth"() {
        expect:
        Unwrapping.given(STEP_GIVES, object(given)) == fits(read)

        where:
        given                                                         || read
        STEP                                                          || STEP
        stepReversed()                                                || STEP
        stepChanged(owner: [address: [city: "rom"], name: "ann"])     || stepChanged(owner: [address: [city: "rom"], name: "ann"])
        [summary: "hello"]                                            || stepNothing()
        stepNothing()                                                 || stepNothing()
        stepChanged(details: [:], owner: [name: "ann"])               || stepChanged(details: [product: null, codes: null], owner: [name: "ann", address: null])
        stepChanged(details: [codes: ["ab"]], owner: [address: [:], name: "ann"]) || stepChanged(details: [codes: ["ab"], product: null], owner: [address: [city: null], name: "ann"])
        stepChanged(details: [product: null], parts: [])              || stepChanged(details: [product: null, codes: null], parts: [])
        stepChanged(parts: [[name: "x"], [codes: ["ab"], maker: [city: "rom"], name: "y", note: "n"]]) || stepChanged(parts: [[name: "x", note: null, codes: null, maker: null], [codes: ["ab"], maker: [city: "rom"], name: "y", note: "n"]])
        stepChanged(parts: [[codes: [], maker: null, note: "n", name: "x"], [name: "y"]])     || stepChanged(parts: [[codes: [], maker: null, note: "n", name: "x"], [name: "y", note: null, codes: null, maker: null]])
        stepChanged(parts: [[maker: [:], name: "x", note: "n", codes: []]])  || stepChanged(parts: [[maker: [city: null], name: "x", note: "n", codes: []]])
        stepChanged(parts: [[codes: ["ab"], name: "x"]])              || stepChanged(parts: [[codes: ["ab"], name: "x", note: null, maker: null]])
        stepChanged(details: [product: null, codes: null])            || stepChanged(details: [product: null, codes: null])
        stepChanged(details: [codes: []], owner: [name: "ann", address: [city: null]]) || stepChanged(details: [codes: [], product: null], owner: [name: "ann", address: [city: null]])
    }

    def "a text a code step gives back that shows nothing fits as it came"() {
        expect:
        Unwrapping.given(STEP_GIVES, object(stepChanged(summary: blank))) == fits(stepChanged(summary: blank))

        where:
        blank << SHOWING_NOTHING + [""]
    }

    def "a value a code step gives back written as text that shows nothing is read as it came, kept where its kind takes it and not fitting where it does not"() {
        expect:
        Unwrapping.given(STEP_GIVES, object(stepChanged(changes))) == read

        where:
        changes                                || read
        [details: [product: " ", codes: [""]]] || fits(stepChanged(details: [product: " ", codes: [""]]))
        [tags: [" "]]                        || new GivenBack.Misfit(DidNotFitReason.NOT_A_TERM, [new FieldName("tags")], null)
        [amount: " "]                        || new GivenBack.Misfit(DidNotFitReason.NOT_ITS_KIND, [new FieldName("amount")], null)
    }

    def "what a code step gave back that does not fit names the first thing found wrong, the field it is at, and any member nothing declares as it came"() {
        expect:
        Unwrapping.given(STEP_GIVES, object(given)) ==
                new GivenBack.Misfit(reason, path.collect { new FieldName(it) }, undeclared)

        where:
        given                                                             || reason                          | path                          | undeclared
        stepChanged(colour: "red")                                        || DidNotFitReason.FIELD_UNKNOWN   | []                            | "colour"
        stepChanged(("Colour " + NUL): "red")                             || DidNotFitReason.FIELD_UNKNOWN   | []                            | "Colour " + NUL
        stepChanged((" colour "): "red")                                  || DidNotFitReason.FIELD_UNKNOWN   | []                            | " colour "
        stepChanged(parts: [[name: "x", maker: [town: "y"]]])             || DidNotFitReason.FIELD_UNKNOWN   | ["parts"]                     | "town"
        stepChanged(details: [product: "a", size: 1])                     || DidNotFitReason.FIELD_UNKNOWN   | ["details"]                   | "size"
        stepChanged(owner: [name: "a", address: [town: "x"]])             || DidNotFitReason.FIELD_UNKNOWN   | ["owner", "address"]          | "town"
        stepChanged(parts: [[name: "x", size: 1]])                        || DidNotFitReason.FIELD_UNKNOWN   | ["parts"]                     | "size"
        stepChanged(summary: null)                                        || DidNotFitReason.NOTHING_GIVEN   | ["summary"]                   | null
        stepChanged(owner: [name: null])                                  || DidNotFitReason.NOTHING_GIVEN   | ["owner", "name"]             | null
        stepChanged(parts: [[name: null]])                                || DidNotFitReason.NOTHING_GIVEN   | ["parts"]                     | null
        stepChanged(summary: 5)                                           || DidNotFitReason.NOT_ITS_KIND    | ["summary"]                   | null
        stepChanged(owner: [name: "a", address: [city: 1]])               || DidNotFitReason.NOT_ITS_KIND    | ["owner", "address", "city"]  | null
        stepChanged(details: [codes: [null]])                             || DidNotFitReason.NOT_ITS_KIND    | ["details", "codes"]          | null
        stepChanged(parts: [[name: 5]])                                   || DidNotFitReason.NOT_ITS_KIND    | ["parts"]                     | null
        stepChanged(parts: [[name: "x", codes: [null]]])                  || DidNotFitReason.NOT_ITS_KIND    | ["parts"]                     | null
        stepChanged(tags: ["Billing", "Delivery", "Billing"])             || DidNotFitReason.TOO_MANY        | ["tags"]                      | null
        stepChanged(parts: [[name: "x"], [name: "y"], [name: "z"]])       || DidNotFitReason.TOO_MANY        | ["parts"]                     | null
        stepChanged(summary: "hello!")                                    || DidNotFitReason.TOO_LONG        | ["summary"]                   | null
        stepChanged(details: [codes: ["abc"]])                            || DidNotFitReason.TOO_LONG        | ["details", "codes"]          | null
        stepChanged(parts: [[name: "x", codes: ["abc"]]])                 || DidNotFitReason.TOO_LONG        | ["parts"]                     | null
        stepChanged(owner: [name: "a", address: [city: "abcd"]])          || DidNotFitReason.TOO_LONG        | ["owner", "address", "city"]  | null
        stepChanged(tags: ["Other"])                                      || DidNotFitReason.NOT_A_TERM      | ["tags"]                      | null
        stepChanged(summary: "a" + NUL)                                   || DidNotFitReason.UNKEEPABLE      | ["summary"]                   | null
        stepChanged(owner: [name: "a", address: [city: HIGH]])            || DidNotFitReason.UNKEEPABLE      | ["owner", "address", "city"]  | null
        stepChanged(parts: [[name: "a" + NUL]])                           || DidNotFitReason.UNKEEPABLE      | ["parts"]                     | null
    }

    /** Two things wrong at once, so the one named says which is read first. */
    def "what a code step gave back wrong twice over does not fit for the first found: unknown before any field, fields in declared order, items in the order they came"() {
        expect:
        Unwrapping.given(STEP_GIVES, object(given)) ==
                new GivenBack.Misfit(reason, path.collect { new FieldName(it) }, undeclared)

        where:
        given                                                             || reason                          | path                  | undeclared
        stepWithout("summary") + [colour: "red"]                          || DidNotFitReason.FIELD_UNKNOWN   | []                    | "colour"
        stepChanged(owner: [address: [city: "rom"], town: "x"])           || DidNotFitReason.FIELD_UNKNOWN   | ["owner"]             | "town"
        stepChanged(details: [product: 5, size: 1])                       || DidNotFitReason.FIELD_UNKNOWN   | ["details"]           | "size"
        stepChanged(summary: 5, amount: "x")                              || DidNotFitReason.NOT_ITS_KIND    | ["summary"]           | null
        stepChanged(details: [codes: ["abc"], product: 5])                || DidNotFitReason.NOT_ITS_KIND    | ["details", "product"] | null
        stepChanged(parts: [[:], [name: 5]])                              || DidNotFitReason.NOTHING_GIVEN   | ["parts"]             | null
    }

    def "a field that must be given, left out of what a code step gave back, does not fit as one given none, at any depth"() {
        expect:
        Unwrapping.given(STEP_GIVES, object(given)) ==
                new GivenBack.Misfit(DidNotFitReason.NOTHING_GIVEN, path.collect { new FieldName(it) }, null)

        where:
        given                                               || path
        stepWithout("summary")                              || ["summary"]
        stepChanged(owner: [address: [city: "rom"]])        || ["owner", "name"]
        stepChanged(parts: [[note: "n"]])                   || ["parts"]
    }

    /** Measured as kept: a note left out is written in as none, fourteen characters past what came. */
    def "a value a code step gave back that the store would keep longer than any one value, as kept, does not fit at its field, once that field's own rules pass"() {
        given:
        def said = Character.toString(0x1) * controls + "a" * letters

        when:
        def unwrapped = Unwrapping.given([field], object([letter: field.fields().isEmpty() ? said : [body: said]]))

        then:
        unwrapped == (reason == null
                ? fits([letter: [body: said, note: null]])
                : new GivenBack.Misfit(reason, [new FieldName("letter")], null))

        where:
        field                                                        | controls  | letters || reason
        text("letter", 8_388_612)                                    | 8_388_607 | 5       || DidNotFitReason.TOO_LONG_TO_KEEP
        text("letter", 8_388_612)                                    | 8_388_607 | 6       || DidNotFitReason.TOO_LONG
        held("letter", [text("body", 8_388_616), text("note", 3)])   | 8_388_604 | 12      || DidNotFitReason.TOO_LONG_TO_KEEP
        held("letter", [text("body", 8_388_616), text("note", 3)])   | 8_388_602 | 10      || null
    }

    def "an absent member for a field that need not be given is missing to a model, and nothing to a code step"() {
        when:
        def produced = Unwrapping.production(STEP_GIVES, Json.of([values: values, confidences: [:]]))
        def given = Unwrapping.given(STEP_GIVES, object(values))

        then:
        produced == new DoesNotFit(DidNotFitReason.FIELD_MISSING)
        given == fits(read)

        where:
        values                                  || read
        stepWithout("amount")                   || stepChanged(amount: null)
        stepChanged(details: [codes: ["ab"]])   || stepChanged(details: [codes: ["ab"], product: null])
    }

    def "what a code step gave back, read against nothing or read as nothing, is refused by name"() {
        when:
        Unwrapping.given(gives, gaveBack)

        then:
        def refused = thrown(NullPointerException)
        refused.message == message

        where:
        gives      | gaveBack     || message
        null       | object(STEP) || "Unwrapping gives must not be null"
        STEP_GIVES | null         || "Unwrapping gaveBack must not be null"
    }

    def "a review deciding every value it was asked to is unwrapped, each decision as it was given"() {
        when:
        def unwrapped = Unwrapping.review(DECIDING, Json.of([decisions: decisions]))

        then:
        unwrapped == new ReviewAnswer.Reviewed([(new FieldName("summary")): new ReviewAnswer.Assured(),
                                                (new FieldName("tags"))   : new ReviewAnswer.Refused(words)])

        where:
        decisions                                                                        || words
        [summary: [outcome: "assured"], tags: [outcome: "refused", words: "Not asked."]] || "Not asked."
        [tags: [words: "x", outcome: "refused"], summary: [outcome: "assured"]]          || "x"
        [summary: [outcome: "assured"], tags: [outcome: "refused", words: "a\tb\nc"]]    || "a\tb\nc"
        [summary: [outcome: "assured"], tags: [outcome: "refused", words: "w" * 2048]]   || "w" * 2048
        [summary: [outcome: "assured"], tags: [outcome: "refused", words: GRINNING * 2048]] || GRINNING * 2048
        [summary: [outcome: "assured"], tags: [outcome: "refused", words: "a" + SEPARATORS + "b"]] || "a" + SEPARATORS + "b"
        [summary: [outcome: "assured"], tags: [outcome: "refused", words: " a "]]        || " a "
    }

    def "a review that does not decide as the envelope asked does not fit, naming the first thing found wrong"() {
        expect:
        Unwrapping.review(DECIDING, Json.of(answered)) == new DoesNotFit(reason)

        where:
        answered                                                                  || reason
        "assured"                                                                 || DidNotFitReason.NOT_THE_SHAPE
        [decisions: [summary: [outcome: "assured"]], note: "x"]                   || DidNotFitReason.NOT_THE_SHAPE
        [decided: [summary: [outcome: "assured"]]]                                || DidNotFitReason.NOT_THE_SHAPE
        [decisions: [[outcome: "assured"]]]                                       || DidNotFitReason.NOT_THE_SHAPE
        decided([summary: "assured"])                                             || DidNotFitReason.NOT_THE_SHAPE
        decided([summary: [outcome: "maybe"]])                                    || DidNotFitReason.NOT_THE_SHAPE
        decided([summary: [outcome: 1]])                                          || DidNotFitReason.NOT_THE_SHAPE
        decided([summary: [words: "x"]])                                          || DidNotFitReason.NOT_THE_SHAPE
        decided([summary: [outcome: "assured", words: "x"]])                      || DidNotFitReason.NOT_THE_SHAPE
        decided([summary: [outcome: "assured", note: "x"]])                       || DidNotFitReason.NOT_THE_SHAPE
        decided([summary: [outcome: "refused", words: "x", note: "y"]])           || DidNotFitReason.NOT_THE_SHAPE
        decided([summary: [outcome: "refused", words: 5]])                        || DidNotFitReason.NOT_THE_SHAPE
        decided([amount: [outcome: "assured"]])                                   || DidNotFitReason.FIELD_UNKNOWN
        [decisions: [summary: [outcome: "assured"]]]                              || DidNotFitReason.UNDECIDED
        [decisions: [:]]                                                          || DidNotFitReason.UNDECIDED
        decided([summary: [outcome: "refused"]])                                  || DidNotFitReason.WORDS_MISSING
        decided([summary: [outcome: "refused", words: null]])                     || DidNotFitReason.WORDS_MISSING
        decided([summary: [outcome: "refused", words: ""]])                       || DidNotFitReason.WORDS_MISSING
        decided([summary: [outcome: "refused", words: "a" + NUL]])                || DidNotFitReason.WORDS_UNKEEPABLE
        decided([summary: [outcome: "refused", words: "a" + HIGH]])               || DidNotFitReason.WORDS_UNKEEPABLE
        decided([summary: [outcome: "refused", words: "a\r\nb"]])                 || DidNotFitReason.WORDS_UNKEEPABLE
        decided([summary: [outcome: "refused", words: "a" + Character.toString(0x01)]]) || DidNotFitReason.WORDS_UNKEEPABLE
        decided([summary: [outcome: "refused", words: "a" + Character.toString(0x0B)]]) || DidNotFitReason.WORDS_UNKEEPABLE
        decided([summary: [outcome: "refused", words: "a" + Character.toString(0x1F)]]) || DidNotFitReason.WORDS_UNKEEPABLE
        decided([summary: [outcome: "refused", words: "a" + Character.toString(0x7F)]]) || DidNotFitReason.WORDS_UNKEEPABLE
        decided([summary: [outcome: "refused", words: "a" + Character.toString(0x85)]]) || DidNotFitReason.WORDS_UNKEEPABLE
        decided([summary: [outcome: "refused", words: "a" + Character.toString(0x9F)]]) || DidNotFitReason.WORDS_UNKEEPABLE
        decided([summary: [outcome: "refused", words: "w" * 2049]])               || DidNotFitReason.WORDS_TOO_LONG
        decided([summary: [outcome: "refused", words: GRINNING * 2049]])          || DidNotFitReason.WORDS_TOO_LONG
    }

    /** Words are prose, and prose of nothing that shows says no more why than no words at all. */
    def "a refusal whose words show nothing is a refusal with no words, however many of them"() {
        expect:
        Unwrapping.review(DECIDING, Json.of(decided([summary: [outcome: "refused", words: words]]))) ==
                new DoesNotFit(DidNotFitReason.WORDS_MISSING)

        where:
        words << SHOWING_NOTHING + SHOWING_NOTHING.collect { it * 2049 } + [SHOWING_NOTHING.join()]
    }

    /** Two things wrong in one review, so the one named says the decisions are read in the order they came. */
    def "a review wrong twice over does not fit for the first decision found wrong, in the order they came"() {
        expect:
        Unwrapping.review(DECIDING, Json.of([decisions: decisions])) == new DoesNotFit(reason)

        where:
        decisions                                                       || reason
        [summary: [outcome: "maybe"], tags: [outcome: "refused"]]       || DidNotFitReason.NOT_THE_SHAPE
        [tags: [outcome: "refused"], summary: [outcome: "maybe"]]       || DidNotFitReason.WORDS_MISSING
        [amount: [outcome: "assured"], summary: [outcome: "maybe"]]     || DidNotFitReason.FIELD_UNKNOWN
        [summary: [outcome: "maybe"], amount: [outcome: "assured"]]     || DidNotFitReason.NOT_THE_SHAPE
    }

    def "an answer that could not be read, arriving as none, is not the shape, produced, searched or reviewed"() {
        expect:
        Unwrapping."$reading"(asked, null) == new DoesNotFit(DidNotFitReason.NOT_THE_SHAPE)

        where:
        reading      | asked
        "production" | GIVES
        "searched"   | SEARCHED
        "review"     | DECIDING
    }

    def "an answer read against nothing asked is refused by name"() {
        when:
        Unwrapping."$reading"(null, Json.of([:]))

        then:
        def refused = thrown(NullPointerException)
        refused.message == message

        where:
        reading      || message
        "production" || "Unwrapping gives must not be null"
        "searched"   || "Unwrapping gives must not be null"
        "review"     || "Unwrapping deciding must not be null"
    }

    private static JsonValue answer(Map values, Map confidences) {
        Json.of([values: values, confidences: confidences])
    }

    private static Map changed(Map changes) {
        VALUES + changes
    }

    /** What the envelope asks, the summary, the product and the codes each asking as {@code mustBeGiven} says. */
    private static List<AskedField> demanding(boolean mustBeGiven) {
        [text("summary", 5, null, true, mustBeGiven), GIVES[1], GIVES[2], GIVES[3], GIVES[4], GIVES[5],
         held("details", [text("product", 3, null, false, mustBeGiven), text("codes", 2, 2, false, mustBeGiven)])]
    }

    /** Every value as given but the one text named, which is {@code said}: the summary, a product, or a code. */
    private static Map placed(String at, String said) {
        switch (at) {
            case "summary": return changed(summary: said)
            case "product": return changed(details: [product: said, codes: ["ab"]])
            default: return changed(details: [product: "abc", codes: ["ab", said]])
        }
    }

    /** A field named value of the kind given, asking no confidence: text of five, a term of the categories. */
    private static AskedField asked(FieldKind kind, Integer most, boolean mustBeGiven) {
        new AskedField(new FieldName("value"), kind, kind == FieldKind.TEXT ? 5 : null, most,
                kind == FieldKind.TERM ? CATEGORIES : null, [], mustBeGiven, false)
    }

    /** The value itself where one is held, and otherwise as the second of many, after one that fits. */
    private static Object placedIn(Integer most, Object said, Object before) {
        most == null ? said : [before, said]
    }

    /** A summary, then details holding a product, codes and a maker holding a city, as many as {@code most} says. */
    private static List<AskedField> nothingHolding(
            boolean productMust, boolean codesMust, boolean detailsMust, Integer most = null) {
        [text("summary", 5),
         held("details", [text("product", 3, null, false, productMust), text("codes", 2, 2, false, codesMust),
                          held("maker", [text("city", 3)])], most, detailsMust)]
    }

    private static Map without(String name) {
        VALUES.findAll { it.key != name }
    }

    /** Every field there, and none of them holding anything. */
    private static Map nothing() {
        VALUES.collectEntries { [(it.key): null] }
    }

    /** Every value as given, the fields in the reverse of their declared order. */
    private static Map reversed() {
        VALUES.entrySet().toList().reverse().collectEntries { [(it.key): it.value] }
    }

    private static JsonValue.JsonObject object(Map given) {
        Json.of(given) as JsonValue.JsonObject
    }

    private static GivenBack.Fits fits(Map read) {
        new GivenBack.Fits(read.collectEntries { [(new FieldName(it.key as String)): Json.of(it.value)] })
    }

    private static Map stepChanged(Map changes) {
        STEP + changes
    }

    private static Map stepWithout(String name) {
        STEP.findAll { it.key != name }
    }

    /** Every field a code step gives there, and none but the one that must be given holding anything. */
    private static Map stepNothing() {
        STEP.collectEntries { [(it.key): it.key == "summary" ? it.value : null] }
    }

    private static Map stepReversed() {
        STEP.entrySet().toList().reverse().collectEntries { [(it.key): it.value] }
    }

    /** Both values decided, the first as {@code first} says, the second assured. */
    private static Map decided(Map first) {
        [decisions: first + [tags: [outcome: "assured"]]]
    }
}
