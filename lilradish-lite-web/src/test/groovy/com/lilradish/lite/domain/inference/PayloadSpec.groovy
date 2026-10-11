package com.lilradish.lite.domain.inference

import static com.lilradish.lite.testutil.inference.Askings.GIVES
import static com.lilradish.lite.testutil.inference.Askings.held
import static com.lilradish.lite.testutil.inference.Askings.plain
import static com.lilradish.lite.testutil.inference.Askings.text

import com.lilradish.lite.domain.declaration.AskedField
import com.lilradish.lite.domain.declaration.Asking
import com.lilradish.lite.domain.declaration.FieldKind
import com.lilradish.lite.domain.declaration.FieldName
import com.lilradish.lite.domain.declaration.Instruction
import com.lilradish.lite.domain.file.FileName
import com.lilradish.lite.domain.file.StoredFile
import com.lilradish.lite.domain.wire.CanonicalJson
import com.lilradish.lite.domain.wire.JsonValue
import com.lilradish.lite.testutil.inference.Json
import spock.lang.Specification

class PayloadSpec extends Specification {

    static final Asking ASKING = new Asking(new Instruction("Say what it is."),
            [text("complaint", 10), plain("received", FieldKind.MOMENT, 3)], GIVES)

    /** Handed in the reverse of their declared order, which is not the order they are written in. */
    static final Map<FieldName, JsonValue> TAKEN = named([received: null, complaint: "It broke."])

    static final Map GIVEN_AS_WRITTEN = [details: null, urgent: null, at: null, due: null, amount: null,
                                         tags: ["Billing", "Delivery"], summary: "hello"]

    static final Map<FieldName, JsonValue> GIVEN = named(GIVEN_AS_WRITTEN)

    static final String TAKES_WRITTEN = '"takes":{"complaint":"It broke.","received":null}'

    static final String GIVEN_WRITTEN = '{"summary":"hello","tags":["Billing","Delivery"],"amount":null,"due":null,"at":null,' +
            '"urgent":null,"details":null}'

    static final Payload.Refused REFUSED = new Payload.Refused(GIVEN,
            [(new FieldName("tags")): "Not the category.", (new FieldName("summary")): 'Say "why".'])

    def "a question review preserves the saved inputs and web markers through the same payload authority"() {
        given:
        def deciding = [new FieldName('summary')]
        def marked = [new FieldName('complaint')]
        def sent = Payload.toProduce(ASKING, TAKEN, null, marked)

        expect:
        Payload.toReviewKept(ASKING, sent, REFUSED, GIVEN, deciding).orElseThrow() ==
                Payload.toReview(ASKING, TAKEN, REFUSED, GIVEN, deciding, marked)
        Payload.toReviewKept(ASKING, Payload.toProduce(ASKING, TAKEN, null), null, GIVEN, deciding).orElseThrow() ==
                Payload.toReview(ASKING, TAKEN, null, GIVEN, deciding, [])
    }

    def "a question review leaves an unusable saved input portion to a person"() {
        given:
        def sent = Json.of(saved)

        expect:
        Payload.toReviewKept(ASKING, sent, null, GIVEN, [new FieldName('summary')]).empty

        where:
        saved << [
                [takes: [complaint: 'It broke.', received: null]],
                [instruction: 'A different instruction.', takes: [complaint: 'It broke.', received: null]],
                [instruction: 'Say what it is.', takes: 'Not inputs'],
                [instruction: 'Say what it is.', takes: [complaint: true, received: null]],
                [instruction: 'Say what it is.', takes: [complaint: 'It broke.', received: null, extra: null]],
                [instruction: 'Say what it is.', takes: [complaint: 'It broke.', received: null], found_on_the_web: 'complaint'],
                [instruction: 'Say what it is.', takes: [complaint: 'It broke.', received: null], found_on_the_web: [true]],
                [instruction: 'Say what it is.', takes: [complaint: 'It broke.', received: null], found_on_the_web: ['complaint', 'complaint']],
                [instruction: 'Say what it is.', takes: [complaint: 'It broke.', received: null], found_on_the_web: ['received', 'complaint']]
        ]
    }

    def "a model payload loudly refuses a file at any depth"() {
        given:
        def asking = new Asking(new Instruction("Read it."), takes, GIVES)

        when:
        Payload.toProduce(asking, named(values), null)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "A file is not sent to a model"

        where:
        takes                  | values
        [new AskedField(new FieldName("attachment"), FieldKind.FILE, null, null, null, [], false, false)] | [attachment: null]
        [held("details", [new AskedField(new FieldName("attachment"), FieldKind.FILE, null, null, null, [], false, false)])] |
                [details: [attachment: null]]
    }

    def "a model payload still sends non-file fields in their declared order"() {
        expect:
        CanonicalJson.write(Payload.toProduce(ASKING, TAKEN, null)).contains(TAKES_WRITTEN)
        !CanonicalJson.write(Payload.toProduce(ASKING, TAKEN, null)).contains('"attachment"')
    }

    def "a search refuses a file source at any depth even when it has no value"() {
        given:
        def file = new AskedField(new FieldName("document"), FieldKind.FILE, null, many, null, [], false, false)
        def field = nested ? held("details", [file], outerMany) : file
        def stored = new StoredFile(UUID.fromString("00000000-0000-4000-8000-000000000001"),
                new FileName("Demo Sample.pdf"), 12).kept()
        def value = switch (valueKind) {
            case "one" -> stored
            case "many" -> new JsonValue.JsonArray([stored])
            case "nested" -> new JsonValue.JsonObject([new JsonValue.JsonMember("document", stored)])
            case "nestedMany" -> new JsonValue.JsonArray([
                    new JsonValue.JsonObject([new JsonValue.JsonMember("document", stored)])])
            case "null" -> new JsonValue.JsonNull()
            case "empty" -> new JsonValue.JsonArray([])
            case "absent" -> null
        }

        when:
        Payload.toProduce(new Asking(new Instruction('Read the supplied document.'), [field], GIVES),
                value == null ? [:] : [(field.name()): value], null)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "A file is not sent to a model"

        where:
        nested | many | outerMany | valueKind
        false  | null | null      | "one"
        false  | 2    | null      | "many"
        true   | null | null      | "nested"
        true   | null | 2         | "nestedMany"
        false  | null | null      | "null"
        false  | 2    | null      | "empty"
        true   | null | null      | "absent"
    }

    def "a refusal renders without its prior values or refusal words"() {
        expect:
        REFUSED.toString() == 'Payload$Refused()'
        !REFUSED.toString().contains("hello")
        !REFUSED.toString().contains("Not the category.")
    }

    def "a searching instruction stays separate from the declared values"() {
        expect:
        def sent = Payload.toProduce(ASKING, TAKEN, null)
        sent.members().find { it.name() == 'instruction' }.value() == Json.of('Say what it is.')
        sent.members().find { it.name() == 'takes' }.value() == Json.of([complaint: 'It broke.', received: null])
        !sent.members()*.name().contains('question')
    }

    def "a searching instruction and inputs remain separate with optional null and empty values intact"() {
        given:
        def asking = new Asking(new Instruction('Find sources near the supplied date.'),
                [text('words', 20), plain('missing', FieldKind.DATE), text('empty_many', 3, 2),
                 plain('date', FieldKind.DATE)], GIVES)
        def taken = named([date: '2024-03-31', empty_many: [], missing: null, words: 'quoted "word"'])

        when:
        def sent = Payload.toProduce(asking, taken, null)
        def written = CanonicalJson.write(sent)

        then:
        sent.members()[1].value() == Json.of('Find sources near the supplied date.')
        sent.members()[2].value() == Json.of([words: 'quoted "word"', missing: null, empty_many: [], date: '2024-03-31'])
        written == '{"envelope_version":' + Envelope.VERSION +
                ',"instruction":"Find sources near the supplied date.","takes":{"words":"quoted \\"word\\"","missing":null,"empty_many":[],"date":"2024-03-31"}}'
        sent.members()*.name() == ['envelope_version', 'instruction', 'takes']
    }

    def "a searching instruction preserves whitespace without absorbing its input values"() {
        given:
        def instruction = ' Fictional "quoted"\n\tquestion '
        def asking = new Asking(new Instruction(instruction), [], GIVES)

        expect:
        Payload.toProduce(asking, [:], REFUSED).members()[1].value() == Json.of(instruction)
        Payload.toProduce(asking, [:], null).members()*.name() == ['envelope_version', 'instruction', 'takes']
        Payload.toProduce(asking, [:], null).members()[2].value() == Json.of([:])
    }

    def "a search review omits the whole record when no search entry was kept"() {
        given:
        def deciding = [new FieldName('summary')]
        def answeredHere = new JsonValue.JsonObject([])
        def unsaid = Json.of([searches: [], cut: false, not_as_it_came: false, left_out: 0])
        def noneKept = Json.of([searches: [], cut: true, not_as_it_came: true, left_out: 3])

        when:
        def fromPerson = CanonicalJson.write(Payload.toReviewSearch(new Asking(new Instruction('Find it'), [], GIVES), [:], null, GIVEN, deciding,
                answeredHere, []))
        def fromUnsaid = CanonicalJson.write(Payload.toReviewSearch(new Asking(new Instruction('Find it'), [], GIVES), [:], null, GIVEN, deciding,
                unsaid, []))
        def fromOnlyLeftOut = CanonicalJson.write(Payload.toReviewSearch(new Asking(new Instruction('Find it'), [], GIVES), [:], null, GIVEN, deciding,
                noneKept, []))

        then:
        fromPerson == fromUnsaid
        fromUnsaid == fromOnlyLeftOut
        fromPerson.startsWith('{"envelope_version":' + Envelope.VERSION + ',"instruction":"Find it","takes":{},"answer":')
        !fromPerson.contains('search_record')
        !fromPerson.contains('refused')
    }

    def "a search review orders kept searches and whole-record facts without exposing who searched or its fee"() {
        given:
        def record = Json.of([left_out: 2, not_as_it_came: true, cut: true,
                searches: [[found: '', searched_for: 'Quoted "words"'], [found: 'A finding.']]])

        when:
        def payload = Payload.toReviewSearch(new Asking(new Instruction('Find it'), [], GIVES), [:], REFUSED, GIVEN,
                [new FieldName('tags'), new FieldName('summary')], record, [])
        def written = CanonicalJson.write(payload)

        then:
        payload.members()*.name() == ['envelope_version', 'instruction', 'takes', 'refused', 'search_record', 'answer', 'deciding']
        payload.members()[4].value() == Json.of([searches: [[searched_for: 'Quoted "words"', found: ''],
                                                          [found: 'A finding.']], cut: true,
                                                   not_as_it_came: true, left_out: 2])
        written.endsWith('"deciding":["summary","tags"]}')
        !written.contains('uses')
        !written.contains('price')
        !written.contains('fee')
    }

    def "a search review refuses record members unrelated to searched and found evidence"() {
        when:
        Payload.toReviewSearch(new Asking(new Instruction('Find it'), [], GIVES), [:], null, GIVEN, [new FieldName('summary')],
                Json.of([searches: [[found: 'A finding.']], cut: false, not_as_it_came: false,
                         left_out: 0, fee: '1']), [])

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == 'Payload search record has an unknown member'
    }

    def "a search review refuses malformed record value kinds without exposing their contents"() {
        when:
        Payload.toReviewSearch(new Asking(new Instruction('Find it'), [], GIVES), [:], null, GIVEN, [new FieldName('summary')], Json.of(record), [])

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == reason
        !refused.message.contains('secret value')

        where:
        record                                                                          || reason
        [searches: true]                                                                || 'Payload search record has a wrong value kind'
        [searches: [[found: 'secret value']], cut: 'secret value',
         not_as_it_came: false, left_out: 0]                                             || 'Payload search record has a wrong value kind'
        [searches: [[found: 'secret value']], cut: false,
         not_as_it_came: 1, left_out: 0]                                                 || 'Payload search record has a wrong value kind'
        [searches: [[found: 'secret value']], cut: false,
         not_as_it_came: false, left_out: 'secret value']                                || 'Payload search record has a wrong value kind'
        [searches: ['secret value'], cut: false, not_as_it_came: false, left_out: 0]     || 'Payload search entry must be an object'
        [searches: [[searched_for: 1]], cut: false, not_as_it_came: false, left_out: 0] || 'Payload search entry has a wrong value kind'
        [searches: [[found: 1]], cut: false, not_as_it_came: false, left_out: 0]        || 'Payload search entry has a wrong value kind'
    }

    def "search inputs keep numbers numeric and dates or moments as JSON strings"() {
        given:
        def asking = new Asking(new Instruction('Find evidence for the supplied value.'), [plain('source', kind)], GIVES)

        when:
        def sent = Payload.toProduce(asking, named([source: value]), null)

        then:
        CanonicalJson.write(sent.members()[2].value()) == '{"source":' + inserted + '}'
        sent.members()[1].value() == Json.of('Find evidence for the supplied value.')

        where:
        kind             | value                         || inserted
        FieldKind.NUMBER | new BigDecimal('-12.5')       || '-12.5'
        FieldKind.DATE   | '2024-03-31'                  || '"2024-03-31"'
        FieldKind.MOMENT | '2024-03-31T09:30:00.5+02:00' || '"2024-03-31T09:30:00.5+02:00"'
    }

    def "a searching payload refuses a missing input key"() {
        given:
        def asking = new Asking(new Instruction('Find evidence.'), [text('required', 10, null, false, true)], GIVES)

        when:
        Payload.toProduce(asking, [:], null)

        then:
        thrown(IllegalArgumentException)
    }

    def "a searching payload writes explicit empty input shapes used by measurement and optional values"() {
        given:
        def field = text('required', 10, most, false, true)
        def asking = new Asking(new Instruction('Find evidence.'), [field], GIVES)

        when:
        def sent = Payload.toProduce(asking, [(field.name()): value], null)

        then:
        CanonicalJson.write(sent.members()[2].value()) == written

        where:
        most | value                    || written
        null | new JsonValue.JsonNull()  || '{"required":null}'
        2    | Json.of([])               || '{"required":[]}'
    }

    def "a searching payload preserves a many-valued input as an array and appends a refusal last"() {
        given:
        def asking = new Asking(new Instruction('Find evidence for these numbers.'),
                [plain('numbers', FieldKind.NUMBER, 2)], GIVES)

        when:
        def written = CanonicalJson.write(Payload.toProduce(asking, named([numbers: [1, -2]]), REFUSED))

        then:
        written.startsWith('{"envelope_version":' + Envelope.VERSION +
                ',"instruction":"Find evidence for these numbers.","takes":{"numbers":[1,-2]},"refused":')
        written.contains('"tags":"Not the category."')
        !written.contains('"question"')
    }

    def "what producing is sent opens with the envelope's version, then the instruction and what it takes, in declared order"() {
        expect:
        CanonicalJson.write(Payload.toProduce(ASKING, TAKEN, null, [])) ==
                '{"envelope_version":' + Envelope.VERSION + ',"instruction":"Say what it is.",' + TAKES_WRITTEN + '}'
    }

    def "what producing is sent where the step tells the next asking holds what was refused, and why each was"() {
        expect:
        CanonicalJson.write(Payload.toProduce(ASKING, TAKEN, REFUSED, [])) ==
                '{"envelope_version":' + Envelope.VERSION + ',"instruction":"Say what it is.",' + TAKES_WRITTEN + ',' +
                '"refused":{"values":' + GIVEN_WRITTEN + ',"words":{"summary":"Say \\"why\\".","tags":"Not the category."}}}'
    }

    def "web source names are written after inputs in declared order for producing and both kinds of review"() {
        given:
        def sources = [new FieldName("complaint"), new FieldName("received")]
        def deciding = [new FieldName("summary")]

        when:
        def produced = Payload.toProduce(ASKING, TAKEN, null, sources)
        def reviewed = Payload.toReview(ASKING, TAKEN, null, GIVEN, deciding, sources)
        def uninstructed = Payload.toReviewUninstructed(ASKING.takes(), ASKING.gives(), TAKEN, null,
                GIVEN, deciding, sources)

        then:
        [produced, reviewed, uninstructed].every {
            it.members()*.name()[it.members()*.name().indexOf('takes') + 1] == 'found_on_the_web'
        }
        [produced, reviewed, uninstructed].every {
            it.members().find { member -> member.name() == 'found_on_the_web' }.value() ==
                    Json.of(['complaint', 'received'])
        }
        !CanonicalJson.write(Payload.toProduce(ASKING, TAKEN, null, [])).contains('found_on_the_web')
    }

    def "a web source marker stays between inputs and a refused answer for every payload that can be reviewed"() {
        given:
        def sources = [new FieldName("complaint"), new FieldName("received")]
        def deciding = [new FieldName("summary")]
        def refusedWritten = '{"values":' + GIVEN_WRITTEN +
                ',"words":{"summary":"Say \\"why\\".","tags":"Not the category."}}'

        when:
        def produced = Payload.toProduce(ASKING, TAKEN, REFUSED, sources)
        def reviewed = Payload.toReview(ASKING, TAKEN, REFUSED, GIVEN, deciding, sources)
        def uninstructed = Payload.toReviewUninstructed(ASKING.takes(), ASKING.gives(), TAKEN, REFUSED,
                GIVEN, deciding, sources)
        def unmarked = Payload.toProduce(ASKING, TAKEN, REFUSED, [])

        then:
        produced.members()*.name() == ['envelope_version', 'instruction', 'takes', 'found_on_the_web', 'refused']
        reviewed.members()*.name() ==
                ['envelope_version', 'instruction', 'takes', 'found_on_the_web', 'refused', 'answer', 'deciding']
        uninstructed.members()*.name() == ['envelope_version', 'takes', 'found_on_the_web', 'refused', 'answer', 'deciding']
        [produced, reviewed, uninstructed].every { payload ->
            payload.members().find { it.name() == 'found_on_the_web' }.value() == Json.of(['complaint', 'received']) &&
                    CanonicalJson.write(payload.members().find { it.name() == 'refused' }.value()) == refusedWritten &&
                    !CanonicalJson.write(payload.members().find { it.name() == 'refused' }.value()).contains('found_on_the_web')
        }
        unmarked.members()*.name() == ['envelope_version', 'instruction', 'takes', 'refused']
        !CanonicalJson.write(unmarked).contains('found_on_the_web')
    }

    def "web source names must each name a top-level input once and follow declaration order"() {
        when:
        Payload.toProduce(ASKING, TAKEN, null, names.collect { new FieldName(it) })

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == 'Payload web sources must name top-level inputs once in declared order'

        where:
        names << [['received', 'complaint'], ['complaint', 'complaint'], ['unknown'], ['details']]
    }

    def "values handed for a half that are not one for each field it declares are refused"() {
        when:
        Payload.toProduce(ASKING, named(taken), null, [])

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
        Payload.toProduce(ASKING, TAKEN, new Payload.Refused(GIVEN, [(new FieldName("complaint")): "Wrong."]), [])

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "Payload holds words refusing a field nothing gives back"
    }

    def "what reviewing is sent holds what producing was, the answer, and the values to decide in declared order"() {
        expect:
        CanonicalJson.write(Payload.toReview(ASKING, TAKEN, refused, GIVEN,
                [new FieldName("tags"), new FieldName("summary")], [])) ==
                '{"envelope_version":' + Envelope.VERSION + ',"instruction":"Say what it is.",' + TAKES_WRITTEN + told +
                ',"answer":' + GIVEN_WRITTEN + ',"deciding":["summary","tags"]}'

        where:
        refused || told
        null    || ''
        REFUSED || ',"refused":{"values":' + GIVEN_WRITTEN + ',"words":{"summary":"Say \\"why\\".","tags":"Not the category."}}'
    }

    def "a review deciding nothing, a value twice, or what is not given back is refused"() {
        when:
        Payload.toReview(ASKING, TAKEN, null, GIVEN, deciding.collect { new FieldName(it) }, [])

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "Payload decides values given back, each once, and one at least"

        where:
        deciding << [[], ["summary", "summary"], ["complaint"], ["summary", "complaint"]]
    }

    def "a review of an answer that is not one value for each field given back is refused"() {
        when:
        Payload.toReview(ASKING, TAKEN, null, named([summary: "hello"]), [new FieldName("summary")], [])

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "Payload holds a value, or none, for each field declared and no other"
    }

    def "a value each field's kind writes, at every depth, is sent as it was handed"() {
        given:
        def handed = GIVEN_AS_WRITTEN + [amount: 1.5, due: "2024-03-31", at: "2024-03-31T09:30:00+02:00", urgent: true,
                                         details: [product: "abc", codes: ["ab", "c"]]]

        when:
        def sent = Payload.toReview(ASKING, TAKEN, null, named(handed), [new FieldName("summary")], [])

        then:
        def answer = sent.members().find { it.name() == "answer" }.value() as JsonValue.JsonObject
        answer.members().collectEntries { [(it.name()): it.value()] } ==
                handed.collectEntries { [(it.key): Json.of(it.value)] }
    }

    def "a value its field's kind does not write, at any depth, is refused by the field it was handed for"() {
        when:
        Payload.toReview(ASKING, TAKEN, null, named(GIVEN_AS_WRITTEN + handed), [new FieldName("summary")], [])

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
        Payload.toReview(asking, taken, null, GIVEN, deciding, [])

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
        def sent = Payload.toReviewUninstructed(ASKING.takes(), ASKING.gives(), TAKEN, refused, GIVEN, deciding, [])

        then:
        CanonicalJson.write(sent) == '{"envelope_version":' + Envelope.VERSION + ',' + TAKES_WRITTEN + told + ',"answer":' + GIVEN_WRITTEN +
                ',"deciding":["summary","tags"]}'
        sent.members()*.name() ==
                Payload.toReview(ASKING, TAKEN, refused, GIVEN, deciding, []).members()*.name() - ["instruction"]

        where:
        refused || told
        null    || ''
        REFUSED || ',"refused":{"values":' + GIVEN_WRITTEN + ',"words":{"summary":"Say \\"why\\".","tags":"Not the category."}}'
    }

    def "what reviewing a production no instruction asked for is sent names what it takes, gives and decides, or is refused by name"() {
        when:
        Payload.toReviewUninstructed(takes, gives, TAKEN, null, GIVEN, deciding, [])

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
                deciding.collect { new FieldName(it) }, [])

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
        GIVEN_AS_WRITTEN + [amount: 1.5, details: [product: "abc", codes: ["ab", "cd"]]]  || true
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
            Payload.toReview(ASKING, TAKEN, null, named(answer), [new FieldName("summary")], [])
            true
        } catch (IllegalArgumentException ignored) {
            false
        }
    }
}
