package org.lilradish.lite.domain.inference

import static org.lilradish.lite.testutil.inference.Askings.GIVES
import static org.lilradish.lite.testutil.inference.Askings.held
import static org.lilradish.lite.testutil.inference.Askings.plain
import static org.lilradish.lite.testutil.inference.Askings.text

import org.lilradish.lite.domain.declaration.AskedField
import org.lilradish.lite.domain.declaration.Asking
import org.lilradish.lite.domain.declaration.FieldKind
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.declaration.Instruction
import org.lilradish.lite.domain.file.FileName
import org.lilradish.lite.domain.file.MediaTypeName
import org.lilradish.lite.domain.file.StoredFile
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

    def "a question review preserves the saved inputs and web markers through the same payload authority"() {
        given:
        def deciding = [new FieldName('summary')]
        def marked = [new FieldName('complaint')]
        def sent = Payload.toProduce(ASKING, TAKEN, null, marked)

        expect:
        Payload.toReviewKept(ASKING, sent, REFUSED, GIVEN, deciding).orElseThrow() ==
                Payload.toReview(ASKING, TAKEN, REFUSED, GIVEN, deciding, marked)
        Payload.toReviewKept(ASKING, Payload.toProduce(ASKING, TAKEN, null), null, GIVEN, deciding).orElseThrow() ==
                Payload.toReview(ASKING, TAKEN, null, GIVEN, deciding)
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
                new FileName("Demo Sample.pdf"), new MediaTypeName("application/pdf"), 12).kept()
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
        Payload.toSearch([new Payload.SearchPart.ValuePart(field, value)], GIVES, null)

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

    def "a search value part renders without its field or prior value"() {
        expect:
        new Payload.SearchPart.ValuePart(text("source", 20), Json.of("Demo Sample")).toString() ==
                'Payload$SearchPart$ValuePart()'
    }

    def "a search literal part renders without its written question text"() {
        expect:
        new Payload.SearchPart.LiteralPart("Demo Sample").toString() ==
                'Payload$SearchPart$LiteralPart()'
    }

    def "a search joins its ordered literal and bound parts, leaving an optional absent part empty"() {
        given:
        def parts = [new Payload.SearchPart.LiteralPart('Find '),
                new Payload.SearchPart.ValuePart(text('words', 20), Json.of('quoted "word"')),
                new Payload.SearchPart.LiteralPart(' near '),
                new Payload.SearchPart.ValuePart(plain('missing', FieldKind.DATE), new JsonValue.JsonNull()),
                new Payload.SearchPart.ValuePart(text('empty_many', 3, 2), Json.of([])),
                new Payload.SearchPart.LiteralPart(' on '),
                new Payload.SearchPart.ValuePart(plain('date', FieldKind.DATE), Json.of('2024-03-31'))]

        when:
        def sent = Payload.toSearch(parts, GIVES, null)
        def written = CanonicalJson.write(sent)

        then:
        sent.members()[1].value() == Json.of('Find quoted "word" near  on "2024-03-31"')
        written.startsWith('{"envelope_version":' + Envelope.VERSION + ',"question":')
        sent.members()*.name() == ['envelope_version', 'question']
    }

    def "a search sends an already assembled question without changing its whitespace or writing it twice"() {
        given:
        def question = ' Demo Sample "quoted"\n\tquestion '

        expect:
        Payload.toSearch(question, GIVES, REFUSED) ==
                Payload.toSearch([new Payload.SearchPart.LiteralPart(question)], GIVES, REFUSED)
        Payload.toSearch(question, GIVES, null).members()[1].value() == Json.of(question)
    }

    def "a search review omits the whole record when no search entry was kept"() {
        given:
        def deciding = [new FieldName('summary')]
        def answeredHere = new JsonValue.JsonObject([])
        def unsaid = Json.of([searches: [], cut: false, not_as_it_came: false, left_out: 0])
        def noneKept = Json.of([searches: [], cut: true, not_as_it_came: true, left_out: 3])

        when:
        def fromPerson = CanonicalJson.write(Payload.toReviewSearch('Find it', GIVES, null, GIVEN, deciding,
                answeredHere))
        def fromUnsaid = CanonicalJson.write(Payload.toReviewSearch('Find it', GIVES, null, GIVEN, deciding,
                unsaid))
        def fromOnlyLeftOut = CanonicalJson.write(Payload.toReviewSearch('Find it', GIVES, null, GIVEN, deciding,
                noneKept))

        then:
        fromPerson == fromUnsaid
        fromUnsaid == fromOnlyLeftOut
        fromPerson.startsWith('{"envelope_version":' + Envelope.VERSION + ',"question":"Find it","answer":')
        !fromPerson.contains('search_record')
        !fromPerson.contains('refused')
    }

    def "a search review orders kept searches and whole-record facts without exposing who searched or its fee"() {
        given:
        def record = Json.of([left_out: 2, not_as_it_came: true, cut: true,
                searches: [[found: '', searched_for: 'Quoted "words"'], [found: 'A finding.']]])

        when:
        def payload = Payload.toReviewSearch('Find it', GIVES, REFUSED, GIVEN,
                [new FieldName('tags'), new FieldName('summary')], record)
        def written = CanonicalJson.write(payload)

        then:
        payload.members()*.name() == ['envelope_version', 'question', 'refused', 'search_record', 'answer', 'deciding']
        payload.members()[3].value() == Json.of([searches: [[searched_for: 'Quoted "words"', found: ''],
                                                          [found: 'A finding.']], cut: true,
                                                   not_as_it_came: true, left_out: 2])
        written.endsWith('"deciding":["summary","tags"]}')
        !written.contains('uses')
        !written.contains('price')
        !written.contains('fee')
    }

    def "a search review refuses record members unrelated to searched and found evidence"() {
        when:
        Payload.toReviewSearch('Find it', GIVES, null, GIVEN, [new FieldName('summary')],
                Json.of([searches: [[found: 'A finding.']], cut: false, not_as_it_came: false,
                         left_out: 0, fee: '1']))

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == 'Payload search record has an unknown member'
    }

    def "a search review refuses malformed record value kinds without exposing their contents"() {
        when:
        Payload.toReviewSearch('Find it', GIVES, null, GIVEN, [new FieldName('summary')], Json.of(record))

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

    def "a search inserts a number plainly and a date or moment as a JSON string"() {
        given:
        def parts = [new Payload.SearchPart.LiteralPart('before '),
                new Payload.SearchPart.ValuePart(plain('source', kind), Json.of(value)),
                new Payload.SearchPart.LiteralPart(' after')]

        when:
        def question = Payload.toSearch(parts, GIVES, null).members()[1].value()

        then:
        question == Json.of('before ' + inserted + ' after')

        where:
        kind             | value                            || inserted
        FieldKind.NUMBER | new BigDecimal('-12.5')          || '-12.5'
        FieldKind.DATE   | '2024-03-31'                     || '"2024-03-31"'
        FieldKind.MOMENT | '2024-03-31T09:30:00.5+02:00'    || '"2024-03-31T09:30:00.5+02:00"'
    }

    def "a search refuses an absent value from a field that must be given"() {
        given:
        def field = text('required', 10, most, false, true)
        def parts = [new Payload.SearchPart.LiteralPart('Find '), new Payload.SearchPart.ValuePart(field, value)]

        when:
        Payload.toSearch(parts, GIVES, null)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == 'Payload search field must be given: required'

        where:
        most | value
        null | null
        null | new JsonValue.JsonNull()
        2    | Json.of([])
    }

    def "a search writes a many-valued source as JSON inside its one question and appends a refusal last"() {
        given:
        def parts = [new Payload.SearchPart.ValuePart(plain('numbers', FieldKind.NUMBER, 2), Json.of([1, -2]))]

        when:
        def written = CanonicalJson.write(Payload.toSearch(parts, GIVES, REFUSED))

        then:
        written.startsWith('{"envelope_version":' + Envelope.VERSION + ',"question":"[1,-2]","refused":')
        written.contains('"tags":"Not the category."')
        !written.contains('"instruction"')
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
            Payload.toReview(ASKING, TAKEN, null, named(answer), [new FieldName("summary")], [])
            true
        } catch (IllegalArgumentException ignored) {
            false
        }
    }
}
