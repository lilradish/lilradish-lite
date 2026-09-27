package org.lilradish.lite.domain.inference

import static org.lilradish.lite.testutil.inference.Askings.CATEGORIES
import static org.lilradish.lite.testutil.inference.Askings.GIVES
import static org.lilradish.lite.testutil.inference.Askings.held
import static org.lilradish.lite.testutil.inference.Askings.plain
import static org.lilradish.lite.testutil.inference.Askings.term
import static org.lilradish.lite.testutil.inference.Askings.text

import org.lilradish.lite.domain.declaration.AskedField
import org.lilradish.lite.domain.declaration.FieldKind
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.wire.JsonValue
import org.lilradish.lite.testutil.inference.Json
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
        changed(summary: "")                                                    | SURE            || 90
        changed(details: [product: null, codes: null])                         | SURE            || 90
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
        [values: changed(tags: ["Other", "Billing", "Billing"]), confidences: SURE]      || DidNotFitReason.TOO_MANY
    }

    def "a value that must be given does not fit arriving as nothing, at any depth, and one that need not fits"() {
        given:
        def gives = [text("summary", 5, 2, false, summaryMust),
                     held("details", [text("product", 3, null, false, true)], null, detailsMust)]

        when:
        def unwrapped = Unwrapping.production(gives, Json.of([values: values, confidences: [:]]))

        then:
        (unwrapped == new DoesNotFit(DidNotFitReason.NOTHING_GIVEN)) == refused
        (unwrapped instanceof ProductionAnswer.Produced) == !refused

        where:
        summaryMust | detailsMust | values                                    || refused
        true        | false       | [summary: null, details: null]            || true
        true        | false       | [summary: [], details: null]              || true
        true        | false       | [summary: ["a"], details: null]           || false
        false       | false       | [summary: null, details: null]            || false
        false       | false       | [summary: [], details: null]              || false
        false       | true        | [summary: null, details: null]            || true
        false       | false       | [summary: null, details: [product: null]] || true
        false       | false       | [summary: null, details: [product: "a"]]  || false
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

    def "what a code step gave back fits, kept as it came where whole, and holding every member in declared order, none where left out, at any depth"() {
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
        stepChanged(details: [codes: ["ab"]], owner: [address: [:], name: "ann"]) || stepChanged(details: [product: null, codes: ["ab"]], owner: [name: "ann", address: [city: null]])
        stepChanged(details: [product: null], parts: [])              || stepChanged(details: [product: null, codes: null], parts: [])
        stepChanged(parts: [[name: "x"], [codes: ["ab"], maker: [city: "rom"], name: "y", note: "n"]]) || stepChanged(parts: [[name: "x", note: null, codes: null, maker: null], [codes: ["ab"], maker: [city: "rom"], name: "y", note: "n"]])
        stepChanged(parts: [[codes: [], maker: null, note: "n", name: "x"], [name: "y"]])     || stepChanged(parts: [[codes: [], maker: null, note: "n", name: "x"], [name: "y", note: null, codes: null, maker: null]])
        stepChanged(parts: [[maker: [:], name: "x", note: "n", codes: []]])  || stepChanged(parts: [[name: "x", note: "n", codes: [], maker: [city: null]]])
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
        stepChanged(details: [codes: ["ab"]])   || stepChanged(details: [product: null, codes: ["ab"]])
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

    def "an answer that could not be read, arriving as none, is not the shape, produced or reviewed"() {
        expect:
        (reviewing ? Unwrapping.review(DECIDING, null) : Unwrapping.production(GIVES, null)) ==
                new DoesNotFit(DidNotFitReason.NOT_THE_SHAPE)

        where:
        reviewing << [false, true]
    }

    def "an answer read against nothing asked is refused by name"() {
        when:
        reviewing ? Unwrapping.review(null, Json.of([:])) : Unwrapping.production(null, Json.of([:]))

        then:
        def refused = thrown(NullPointerException)
        refused.message == message

        where:
        reviewing || message
        false     || "Unwrapping gives must not be null"
        true      || "Unwrapping deciding must not be null"
    }

    private static JsonValue answer(Map values, Map confidences) {
        Json.of([values: values, confidences: confidences])
    }

    private static Map changed(Map changes) {
        VALUES + changes
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
