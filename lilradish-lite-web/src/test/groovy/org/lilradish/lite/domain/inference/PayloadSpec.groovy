package org.lilradish.lite.domain.inference

import static org.lilradish.lite.testutil.inference.Askings.GIVES
import static org.lilradish.lite.testutil.inference.Askings.plain
import static org.lilradish.lite.testutil.inference.Askings.text

import org.lilradish.lite.domain.declaration.Asking
import org.lilradish.lite.domain.declaration.FieldKind
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.declaration.Instruction
import org.lilradish.lite.domain.wire.CanonicalJson
import org.lilradish.lite.domain.wire.JsonValue
import org.lilradish.lite.testutil.inference.Json
import spock.lang.Specification

class PayloadSpec extends Specification {

    static final Asking ASKING = new Asking(new Instruction("Say what it is."),
            [text("complaint", 10), plain("received", FieldKind.MOMENT, 3)], GIVES)

    /** Handed in the reverse of their declared order, which is not the order they are written in. */
    static final Map<FieldName, JsonValue> TAKEN = named([received: null, complaint: "It broke."])

    static final Map GIVEN_AS_WRITTEN = [details: null, urgent: null, at: null, due: null, amount: null,
                                         tags: ["Billing"], summary: "hello"]

    static final Map<FieldName, JsonValue> GIVEN = named(GIVEN_AS_WRITTEN)

    static final String TAKES_WRITTEN = '"takes":{"complaint":"It broke.","received":null}'

    static final String GIVEN_WRITTEN = '{"summary":"hello","tags":["Billing"],"amount":null,"due":null,"at":null,' +
            '"urgent":null,"details":null}'

    static final Payload.Refused REFUSED = new Payload.Refused(GIVEN,
            [(new FieldName("tags")): "Not the category.", (new FieldName("summary")): 'Say "why".'])

    def "what producing is sent opens with the envelope's version, then the instruction and what it takes, in declared order"() {
        expect:
        CanonicalJson.write(Payload.toProduce(ASKING, TAKEN, null)) ==
                '{"envelope_version":2,"instruction":"Say what it is.",' + TAKES_WRITTEN + '}'
    }

    def "what producing is sent where the step tells the next asking holds what was refused, and why each was"() {
        expect:
        CanonicalJson.write(Payload.toProduce(ASKING, TAKEN, REFUSED)) ==
                '{"envelope_version":2,"instruction":"Say what it is.",' + TAKES_WRITTEN + ',' +
                '"refused":{"values":' + GIVEN_WRITTEN + ',"words":{"summary":"Say \\"why\\".","tags":"Not the category."}}}'
    }

    def "values handed for a half that are not one for each field it declares are refused"() {
        when:
        Payload.toProduce(ASKING, named(taken), null)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == message

        where:
        taken                                                   || message
        [complaint: "It broke."]                                || "Payload holds a value, or none, for each field declared and no other"
        [complaint: "It broke.", received: null, colour: "red"] || "Payload holds a value, or none, for each field declared and no other"
        [complaint: "It broke.", colour: "red"]                 || "Payload holds nothing for received"
    }

    def "words refusing a field nothing gives back are refused"() {
        when:
        Payload.toProduce(ASKING, TAKEN, new Payload.Refused(GIVEN, [(new FieldName("complaint")): "Wrong."]))

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "Payload holds words refusing a field nothing gives back"
    }

    def "what reviewing is sent holds what producing was, the answer, and the values to decide in declared order"() {
        expect:
        CanonicalJson.write(Payload.toReview(ASKING, TAKEN, refused, GIVEN,
                [new FieldName("tags"), new FieldName("summary")])) ==
                '{"envelope_version":2,"instruction":"Say what it is.",' + TAKES_WRITTEN + told +
                ',"answer":' + GIVEN_WRITTEN + ',"deciding":["summary","tags"]}'

        where:
        refused || told
        null    || ''
        REFUSED || ',"refused":{"values":' + GIVEN_WRITTEN + ',"words":{"summary":"Say \\"why\\".","tags":"Not the category."}}'
    }

    def "a review deciding nothing, a value twice, or what is not given back is refused"() {
        when:
        Payload.toReview(ASKING, TAKEN, null, GIVEN, deciding.collect { new FieldName(it) })

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "Payload decides values given back, each once, and one at least"

        where:
        deciding << [[], ["summary", "summary"], ["complaint"], ["summary", "complaint"]]
    }

    def "a review of an answer that is not one value for each field given back is refused"() {
        when:
        Payload.toReview(ASKING, TAKEN, null, named([summary: "hello"]), [new FieldName("summary")])

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "Payload holds a value, or none, for each field declared and no other"
    }

    def "a value each field's kind writes, at every depth, is sent as it was handed"() {
        given:
        def handed = GIVEN_AS_WRITTEN + [amount: 1.5, due: "2024-03-31", at: "2024-03-31T09:30:00+02:00", urgent: true,
                                         details: [product: "abc", codes: ["ab", "c"]]]

        when:
        def sent = Payload.toReview(ASKING, TAKEN, null, named(handed), [new FieldName("summary")])

        then:
        def answer = sent.members().find { it.name() == "answer" }.value() as JsonValue.JsonObject
        answer.members().collectEntries { [(it.name()): it.value()] } ==
                handed.collectEntries { [(it.key): Json.of(it.value)] }
    }

    def "a value its field's kind does not write, at any depth, is refused by the field it was handed for"() {
        when:
        Payload.toReview(ASKING, TAKEN, null, named(GIVEN_AS_WRITTEN + handed), [new FieldName("summary")])

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "Payload holds for " + field + " what its field does not write"

        where:
        handed                                         || field
        [summary: 5]                                   || "summary"
        [summary: ["hello"]]                           || "summary"
        [tags: "Billing"]                              || "tags"
        [tags: [null]]                                 || "tags"
        [amount: new BigDecimal("1E+999999")]          || "amount"
        [at: "2024-03-31T09:30:00-00:00"]              || "at"
        [details: [product: 1, codes: []]]             || "details"
        [details: [product: "a"]]                      || "details"
        [details: [product: "a", codes: [], size: 1]]  || "details"
        [details: [product: "a", codes: "b"]]          || "details"
    }

    def "what is sent names what it asks, what it takes and what it decides, or is refused by name"() {
        when:
        Payload.toReview(asking, taken, null, GIVEN, deciding)

        then:
        def refused = thrown(NullPointerException)
        refused.message == message

        where:
        asking | taken | deciding                     || message
        ASKING | TAKEN | null                         || "Payload deciding must not be null"
        null   | TAKEN | [new FieldName("summary")]   || "Payload asking must not be null"
        ASKING | null  | [new FieldName("summary")]   || "Payload values must not be null"
    }

    /** Nothing is put in the instruction's place, so nothing sent says what, or which step, produced it. */
    def "what reviewing a production no instruction asked for is sent holds everything any review does but the instruction"() {
        given:
        def deciding = [new FieldName("tags"), new FieldName("summary")]

        when:
        def sent = Payload.toReviewUninstructed(ASKING.takes(), ASKING.gives(), TAKEN, refused, GIVEN, deciding)

        then:
        CanonicalJson.write(sent) == '{"envelope_version":2,' + TAKES_WRITTEN + told + ',"answer":' + GIVEN_WRITTEN +
                ',"deciding":["summary","tags"]}'
        sent.members()*.name() ==
                Payload.toReview(ASKING, TAKEN, refused, GIVEN, deciding).members()*.name() - ["instruction"]

        where:
        refused || told
        null    || ''
        REFUSED || ',"refused":{"values":' + GIVEN_WRITTEN + ',"words":{"summary":"Say \\"why\\".","tags":"Not the category."}}'
    }

    def "what reviewing a production no instruction asked for is sent names what it takes, gives and decides, or is refused by name"() {
        when:
        Payload.toReviewUninstructed(takes, gives, TAKEN, null, GIVEN, deciding)

        then:
        def refused = thrown(NullPointerException)
        refused.message == message

        where:
        takes          | gives          | deciding                   || message
        null           | ASKING.gives() | [new FieldName("summary")] || "Payload takes must not be null"
        ASKING.takes() | null           | [new FieldName("summary")] || "Payload gives must not be null"
        ASKING.takes() | ASKING.gives() | null                       || "Payload deciding must not be null"
    }

    def "a review of a production no instruction asked for deciding nothing, or what is not given back, is refused"() {
        when:
        Payload.toReviewUninstructed(ASKING.takes(), ASKING.gives(), TAKEN, null, GIVEN,
                deciding.collect { new FieldName(it) })

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "Payload decides values given back, each once, and one at least"

        where:
        deciding << [[], ["complaint"]]
    }

    /** Asked before sending, so a half it holds is exactly one sending takes, and one it does not is refused there. */
    def "values hold as a half exactly where sending them as the answer is not refused"() {
        when:
        def held = Payload.holds(ASKING.gives(), named(values))

        then:
        held == holds
        sent(values) == holds

        where:
        values                                                                      || holds
        GIVEN_AS_WRITTEN                                                            || true
        GIVEN_AS_WRITTEN + [amount: 1.5, details: [product: "abc", codes: ["ab"]]]  || true
        GIVEN_AS_WRITTEN + [summary: 5]                                             || false
        GIVEN_AS_WRITTEN + [tags: [null]]                                           || false
        GIVEN_AS_WRITTEN + [details: [product: "a"]]                                || false
        GIVEN_AS_WRITTEN + [colour: "red"]                                          || false
        GIVEN_AS_WRITTEN.findAll { it.key != "urgent" }                             || false
        GIVEN_AS_WRITTEN.findAll { it.key != "urgent" } + [colour: "red"]           || false
    }

    def "a production refused before is told with why, or not at all"() {
        when:
        new Payload.Refused(values, words)

        then:
        def refused = thrown(exception)
        refused.message == message

        where:
        values | words                                   || exception                | message
        GIVEN  | [:]                                     || IllegalArgumentException | "Payload.Refused refused something, and says why"
        null   | [(new FieldName("summary")): "Short."] || NullPointerException     | "Payload.Refused values must not be null"
        GIVEN  | null                                    || NullPointerException     | "Payload.Refused words must not be null"
    }

    private static Map<FieldName, JsonValue> named(Map literal) {
        literal.collectEntries { [(new FieldName(it.key as String)): Json.of(it.value)] }
    }

    /** Whether {@code answer} is sent as the answer under review, deciding the summary, rather than refused. */
    private static boolean sent(Map answer) {
        try {
            Payload.toReview(ASKING, TAKEN, null, named(answer), [new FieldName("summary")])
            true
        } catch (IllegalArgumentException ignored) {
            false
        }
    }
}
