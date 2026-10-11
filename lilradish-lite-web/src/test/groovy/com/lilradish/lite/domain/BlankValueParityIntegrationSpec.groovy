package com.lilradish.lite.domain

import static com.lilradish.lite.testutil.inference.Askings.CATEGORIES

import com.lilradish.lite.domain.declaration.AskedField
import com.lilradish.lite.domain.declaration.FieldKind
import com.lilradish.lite.domain.declaration.FieldName
import com.lilradish.lite.domain.filling.FillField
import com.lilradish.lite.domain.filling.FillProblems
import com.lilradish.lite.domain.filling.FillReason
import com.lilradish.lite.domain.filling.FilledFields
import com.lilradish.lite.domain.filling.Filling
import com.lilradish.lite.domain.inference.DidNotFitReason
import com.lilradish.lite.domain.inference.DoesNotFit
import com.lilradish.lite.domain.inference.ProductionAnswer
import com.lilradish.lite.domain.inference.Unwrapping
import com.lilradish.lite.domain.wire.JsonValue
import com.lilradish.lite.testutil.inference.Json
import spock.lang.Specification

/**
 * What a person fills in and what a model gives back are held to one rule of what is nothing given. Each side is
 * judged on its own elsewhere; this holds the two to the same verdict, and to the differences ruled between them.
 */
class BlankValueParityIntegrationSpec extends Specification {

    static final String FITS = "fits"
    static final String NOTHING_GIVEN = "nothing given"
    static final String REFUSED = "refused"

    static final Map<String, String> SHOWING_NOTHING = [
            "empty"            : "",
            "a space"          : " ",
            "a tab and a line feed": "\t\n",
            "a no-break space" : Character.toString(0xA0),
            "a zero-width space": Character.toString(0x200B),
            "a hangul filler" : Character.toString(0x3164),
            "a halfwidth hangul filler": Character.toString(0xFFA0)]

    /** Each draws nothing, yet a person's text may not hold it. */
    static final Map<String, String> REFUSED_TO_A_PERSON = [
            "a right-to-left override": Character.toString(0x202E),
            "a tag latin a"           : Character.toString(0xE0041)]

    /** Each shows nothing yet is no blank, so a model's text keeps it as it came. */
    static final Map<String, String> CONTROLS = [
            "a start of heading": Character.toString(0x1),
            "a next line"       : Character.toString(0x85),
            "a line separator"  : Character.toString(0x2028)]

    /** For each kind, a value that shows and fits: as a person sends it, then as a model gives it. */
    static final Map<FieldKind, List<Object>> SHOWING = [
            (FieldKind.TEXT)  : ["a", "a"],
            (FieldKind.TERM)  : ["Billing", "Billing"],
            (FieldKind.NUMBER): ["5", 5],
            (FieldKind.DATE)  : ["2024-01-01", "2024-01-01"],
            (FieldKind.MOMENT): ["2024-03-31T09:30:00+02:00", "2024-03-31T09:30:00+02:00"],
            (FieldKind.YES_NO): ["true", true]]

    static final Map ANCHOR = [name: "anchor", kind: FieldKind.TEXT, must: true, most: null]

    static final Map<String, Map> HOLDING_NOTHING = [
            "every member none"         : [a: null, b: null, c: null, e: null],
            "every member blank"        : [a: " ", b: "", c: [d: "\t"], e: []],
            "nothing two deep"          : [a: null, b: null, c: [d: null], e: null],
            "an empty list"             : [a: null, b: null, c: null, e: []],
            "a blank of each other kind": [a: Character.toString(0xA0), b: null, c: [d: Character.toString(0x200B)], e: []]]

    /** Something shows in each, and a, the member that may have to be given, shows nothing. */
    static final Map<String, Map> SHOWING_BESIDE_A_BLANK = [
            "b shows"           : [a: " ", b: "y", c: null, e: null],
            "d shows two deep"  : [a: " ", b: null, c: [d: "z"], e: null],
            "an item of e shows": [a: null, b: null, c: null, e: ["q"]]]

    def "a person and a model reach one verdict: #label"() {
        expect:
        personVerdict(fields, personSent) == modelVerdict(fields, modelSent)
        modelVerdict(fields, modelSent).first() == agreed

        where:
        [label, fields, personSent, modelSent, agreed] << agreeing()
    }

    def "a person and a model differ as ruled: #label"() {
        expect:
        personVerdict(fields, personSent).first() == personGets
        modelVerdict(fields, modelSent).first() == modelGets

        where:
        [label, fields, personSent, modelSent, personGets, modelGets] << differing()
    }

    def "a person and a model both treat blank text for fields as nothing before asking its shape"() {
        given:
        def fields = [ANCHOR, [name: "value", kind: FieldKind.FIELDS, must: mustBeGiven, most: most,
                               fields: [[name: "inside", kind: FieldKind.TEXT, must: false, most: null]]]]
        def sent = [anchor: "a", value: value]

        expect:
        personVerdict(fields, sent).first() == verdict
        modelVerdict(fields, sent).first() == verdict
        if (verdict == FITS) {
            assert personVerdict(fields, sent).last() == modelVerdict(fields, sent).last()
        }

        where:
        most | mustBeGiven | value                                      || verdict
        null | false       | " "                                        || FITS
        null | true        | " "                                        || NOTHING_GIVEN
        2    | false       | " "                                        || FITS
        2    | true        | " "                                        || NOTHING_GIVEN
        2    | false       | [[inside: "a"], " "]                     || NOTHING_GIVEN
        2    | true        | [[inside: "a"], " "]                     || NOTHING_GIVEN
    }

    private static List<List> agreeing() {
        List<List> rows = []
        [SHOWING.keySet().toList(), SHOWING_NOTHING.keySet().toList(), [true, false], [null, 2]].combinations()
                .each { FieldKind kind, String blank, boolean must, Integer most ->
                    rows << row("a ${kind} of ${blank}, ${demand(must)}, ${held(most)}", single(kind, must, most),
                            inList(most, SHOWING[kind][0], SHOWING_NOTHING[blank]),
                            inList(most, SHOWING[kind][1], SHOWING_NOTHING[blank]),
                            must || most != null ? NOTHING_GIVEN : FITS)
                }
        [[FieldKind.TEXT, " a ", FITS], [FieldKind.TERM, " Billing", REFUSED], [FieldKind.NUMBER, " 5 ", REFUSED],
         [FieldKind.DATE, " 2024-01-01 ", REFUSED], [FieldKind.MOMENT, " 2024-03-31T09:30:00+02:00", REFUSED],
         [FieldKind.YES_NO, " true", REFUSED], [FieldKind.YES_NO, "", null], [FieldKind.YES_NO, " ", null]]
                .each { FieldKind kind, String said, String verdict ->
                    [true, false].each { boolean must ->
                        rows << row("a ${kind} of '${said}', ${demand(must)}", single(kind, must, null), said, said,
                                verdict ?: (must ? NOTHING_GIVEN : FITS))
                    }
                }
        [[true, false], [true, false], [null, 2], [true, false]].combinations()
                .each { boolean aMust, boolean holderMust, Integer most, boolean aFirst ->
                    def fields = holder(aMust, holderMust, most, aFirst)
                    def shape = "a ${demand(aMust)} ${aFirst ? 'before' : 'after'} b, ${demand(holderMust)}, ${held(most)}"
                    HOLDING_NOTHING.each { name, value ->
                        rows << objectRow("fields of ${name}, ${shape}", fields, value,
                                most == null && !holderMust ? FITS : NOTHING_GIVEN)
                    }
                    SHOWING_BESIDE_A_BLANK.each { name, value ->
                        rows << objectRow("fields where ${name}, ${shape}", fields, value, aMust ? NOTHING_GIVEN : FITS)
                    }
                    rows << objectRow("fields where a shows beside blanks, ${shape}", fields,
                            [a: "x", b: " ", c: [d: " "], e: []], FITS)
                    rows << objectRow("fields where an item of e is blank, ${shape}", fields,
                            [a: "x", b: null, c: null, e: [" "]], NOTHING_GIVEN)
                }
        rows
    }

    private static List<List> differing() {
        List<List> rows = []
        [SHOWING.keySet().toList(), REFUSED_TO_A_PERSON.keySet().toList(), [true, false], [null, 2]].combinations()
                .each { FieldKind kind, String drawn, boolean must, Integer most ->
                    rows << row("a ${kind} of only ${drawn}, ${demand(must)}, ${held(most)}", single(kind, must, most),
                            inList(most, SHOWING[kind][0], REFUSED_TO_A_PERSON[drawn]),
                            inList(most, SHOWING[kind][1], REFUSED_TO_A_PERSON[drawn]),
                            REFUSED, must || most != null ? NOTHING_GIVEN : FITS)
                }
        [[true, false], [null, 2]].combinations().each { boolean aMust, Integer most ->
            def fields = holder(aMust, false, most, true)
            def value = [a: REFUSED_TO_A_PERSON["a right-to-left override"], b: null, c: null, e: null]
            rows << objectRow("fields whose a holds only a right-to-left override, a ${demand(aMust)}, ${held(most)}",
                    fields, value, REFUSED, most == null ? FITS : NOTHING_GIVEN)
        }
        [CONTROLS.keySet().toList(), [true, false], [null, 2]].combinations()
                .each { String control, boolean must, Integer most ->
                    rows << row("a TEXT of only ${control}, ${demand(must)}, ${held(most)}",
                            single(FieldKind.TEXT, must, most), inList(most, "a", CONTROLS[control]),
                            inList(most, "a", CONTROLS[control]), REFUSED, FITS)
                }
        [[FieldKind.NUMBER, "5"], [FieldKind.YES_NO, "true"]].each { FieldKind kind, String said ->
            [true, false].each { boolean must ->
                rows << row("a ${kind} given as the text '${said}', ${demand(must)}", single(kind, must, null), said,
                        said, FITS, REFUSED)
            }
        }
        rows
    }

    private static List row(String label, Map field, Object personLiteral, Object modelLiteral, String... verdicts) {
        [label, [ANCHOR, field], [anchor: "a", value: sent(personLiteral, field)], [anchor: "a", value: modelLiteral]] +
                (verdicts as List)
    }

    /** As the second of many where many are held, after a fields value in which a shows. */
    private static List objectRow(String label, Map field, Map value, String... verdicts) {
        Object placed = field.most == null ? value : [[a: "k", b: null, c: null, e: null], value]
        row(label, field, placed, placed, verdicts)
    }

    private static Object inList(Integer most, Object before, Object said) {
        most == null ? said : [before, said]
    }

    private static String demand(boolean must) {
        must ? "must be given" : "need not be given"
    }

    private static String held(Integer most) {
        most == null ? "held once" : "one of many"
    }

    private static Map single(FieldKind kind, boolean must, Integer most) {
        [name: "value", kind: kind, must: must, most: most]
    }

    /** Fields of a text a, a text b that need not be given, fields c of a text d, and many texts e. */
    private static Map holder(boolean aMust, boolean holderMust, Integer most, boolean aFirst) {
        def a = [name: "a", kind: FieldKind.TEXT, must: aMust, most: null]
        def b = [name: "b", kind: FieldKind.TEXT, must: false, most: null]
        def c = [name: "c", kind: FieldKind.FIELDS, must: false, most: null,
                 fields: [[name: "d", kind: FieldKind.TEXT, must: false, most: null]]]
        def e = [name: "e", kind: FieldKind.TEXT, must: false, most: 2]
        [name: "value", kind: FieldKind.FIELDS, must: holderMust, most: most, fields: (aFirst ? [a, b] : [b, a]) + [c, e]]
    }

    /** As a person's form sends it: every member there at every level, and none of many as an empty list. */
    private static Object sent(Object literal, Map field) {
        if (field.most != null) {
            return literal == null ? [] : (literal as List).collect { sent(it, field + [most: null]) }
        }
        if (field.kind == FieldKind.FIELDS) {
            Map given = literal == null ? [:] : literal as Map
            return field.fields.collectEntries { Map member -> [(member.name): sent(given[member.name], member)] }
        }
        literal
    }

    private static List personVerdict(List<Map> fields, Map sent) {
        def outcome = Filling.of(fields.collect { personField(it) }, Json.of(sent))
        if (outcome instanceof FilledFields) {
            return [FITS, asStored(outcome.values())]
        }
        if (outcome instanceof FillProblems) {
            return [outcome.problems().first().reason() == FillReason.MISSING ? NOTHING_GIVEN : REFUSED, null]
        }
        ["not the shape", null]
    }

    private static List modelVerdict(List<Map> fields, Map given) {
        def answer = Unwrapping.production(fields.collect { modelField(it) }, Json.of([values: given, confidences: [:]]))
        if (answer instanceof ProductionAnswer.Produced) {
            return [FITS, asStored(new JsonValue.JsonObject(fields.collect { Map field ->
                new JsonValue.JsonMember(field.name, answer.values()[new FieldName(field.name)])
            }))]
        }
        [(answer as DoesNotFit).reason() == DidNotFitReason.NOTHING_GIVEN ? NOTHING_GIVEN : REFUSED, null]
    }

    // Both sides read an empty list as nothing, and a person's form always sends one where a model may send none;
    // the store keeps no member order, and each side orders members its own way. Only those two are set aside.
    private static JsonValue asStored(JsonValue value) {
        if (value instanceof JsonValue.JsonObject) {
            return new JsonValue.JsonObject(value.members().toSorted { it.name() }.collect {
                new JsonValue.JsonMember(it.name(), asStored(it.value()))
            })
        }
        if (value instanceof JsonValue.JsonArray) {
            return value.items().isEmpty() ? new JsonValue.JsonNull() : new JsonValue.JsonArray(value.items().collect { asStored(it) })
        }
        value
    }

    private static FillField personField(Map field) {
        new FillField(new FieldName(field.name), null, null, field.kind, field.kind == FieldKind.TEXT ? 5 : null,
                field.most, field.must, field.kind == FieldKind.TERM ? CATEGORIES : null,
                (field.fields ?: []).collect { personField(it) })
    }

    private static AskedField modelField(Map field) {
        new AskedField(new FieldName(field.name), field.kind, field.kind == FieldKind.TEXT ? 5 : null, field.most,
                field.kind == FieldKind.TERM ? CATEGORIES : null, (field.fields ?: []).collect { modelField(it) },
                field.must, false)
    }
}
