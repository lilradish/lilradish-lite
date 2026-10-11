package com.lilradish.lite.domain.inference

import static com.lilradish.lite.testutil.inference.Askings.CATEGORIES
import static com.lilradish.lite.testutil.inference.Askings.GIVES
import static com.lilradish.lite.testutil.inference.Askings.WORST_CHARACTER
import static com.lilradish.lite.testutil.inference.Askings.codePoints
import static com.lilradish.lite.testutil.inference.Askings.held
import static com.lilradish.lite.testutil.inference.Askings.longest
import static com.lilradish.lite.testutil.inference.Askings.offered
import static com.lilradish.lite.testutil.inference.Askings.plain
import static com.lilradish.lite.testutil.inference.Askings.term
import static com.lilradish.lite.testutil.inference.Askings.text

import com.lilradish.lite.domain.declaration.AskedField
import com.lilradish.lite.domain.declaration.Asking
import com.lilradish.lite.domain.declaration.FieldKind
import com.lilradish.lite.domain.declaration.FieldName
import com.lilradish.lite.domain.declaration.Instruction
import com.lilradish.lite.domain.declaration.OfferedTerms
import com.lilradish.lite.domain.referencelist.ListNote
import com.lilradish.lite.domain.wire.CanonicalJson
import com.lilradish.lite.domain.wire.JsonValue
import com.lilradish.lite.testutil.inference.Json
import spock.lang.Specification

/**
 * The measure is held to the longest payload each asking could actually send, built and written, never to a
 * second working-out of the same sum: what it counts has to be what would be kept.
 */
class SendMeasureSpec extends Specification {

    static final String GRINNING = Character.toString(0x1F600)

    static final Instruction INSTRUCTION = new Instruction('Say "what" it is.\nThen why, ' + Character.toString(0xE9) +
            ' ' + Character.toString(0x4E2D) + ' ' + GRINNING)

    /** Code points past the first plane, each one character however many units it is held in. */
    static final List<String> PAST_THE_FIRST_PLANE = [0x10000, 0x1F600, 0xE0001, 0x10FFFF].collect { Character.toString(it) }

    def "the largest production and review include the same conditional explanation as an actual sending"() {
        given:
        def asking = new Asking(new Instruction(instruction), [text("source", 12)], GIVES)

        expect:
        SendMeasure.mostSent(asking, true) == writtenAtMost(asking, true, false)
        SendMeasure.mostSentToReview(asking) == writtenAtMost(asking, true, true)

        where:
        instruction << ["Read {{source}}.", "Read the input.", "Show \\{{source}}; unfinished {{source"]
    }

    def "a search and its review count the conditional explanation beside their actual typed payload"() {
        given:
        def asking = new Asking(new Instruction(instruction), [text('source', 12)], GIVES)
        def taken = asking.takes().collectEntries { [(it.name()): longest(it)] }
        def answer = GIVES.collectEntries { [(it.name()): longest(it)] }
        def refused = new Payload.Refused(answer, GIVES.collectEntries { [(it.name()): '"' * 2048] })
        def produced = Payload.toProduce(asking, taken, refused, asking.takes()*.name())
        def reviewed = Payload.toReviewSearch(asking, taken, refused, answer, GIVES*.name(), null,
                asking.takes()*.name())

        expect:
        SendMeasure.mostSentToSearch(asking, true) == codePoints(CanonicalJson.write(produced)) +
                codePoints(Envelope.toSearch(GIVES, asking.instruction()))
        SendMeasure.mostSentToReviewSearch(asking) == codePoints(CanonicalJson.write(reviewed)) +
                codePoints(Envelope.toReviewSearch(GIVES, asking.instruction()))

        where:
        instruction << ['Read {{source}}.', 'Read the input.', 'Show \\{{source}}; unfinished {{source']
    }

    def "the most one asking could send is the longest payload it could send, written, beside its envelope"() {
        expect:
        SendMeasure.mostSent(asking, told) == writtenAtMost(asking, told, false)

        where:
        [asking, told] << [askings(), [true, false]].combinations()
    }

    def "a file changes no measured send while nested non-file siblings and refusal words still count"() {
        given:
        def file = new AskedField(new FieldName("attachment"), FieldKind.FILE, null, null, null, [], false, false)
        def ordinary = held("details", [text("note", 3)])
        def withFile = held("details", [file, text("note", 3)])
        def without = new Asking(INSTRUCTION, [ordinary], [ordinary])
        def mixed = new Asking(INSTRUCTION, [file, withFile], [file, withFile])

        expect:
        SendMeasure.mostSent(mixed, true) == SendMeasure.mostSent(without, true)
        SendMeasure.mostSent(mixed, false) == SendMeasure.mostSent(without, false)
        SendMeasure.mostSentToReview(mixed) == SendMeasure.mostSentToReview(without)
        SendMeasure.mostSentToReview(mixed.takes(), mixed.gives()) ==
                SendMeasure.mostSentToReview(without.takes(), without.gives())
        SendMeasure.mostSentToSearch(new Asking(INSTRUCTION, [], mixed.gives()), true) ==
                SendMeasure.mostSentToSearch(new Asking(INSTRUCTION, [], without.gives()), true)
        SendMeasure.mostSentToReviewSearch(new Asking(INSTRUCTION, [], mixed.gives())) ==
                SendMeasure.mostSentToReviewSearch(new Asking(INSTRUCTION, [], without.gives()))
        SendMeasure.mostSentToReview(mixed) - codePoints(Envelope.toReview(mixed.gives(), mixed.instruction())) >
                SendMeasure.mostSent(mixed, false) - codePoints(Envelope.toProduce(mixed.gives(), mixed.instruction()))
        SendMeasure.mostSent(mixed, false) > SendMeasure.mostSent(new Asking(INSTRUCTION, [], []), false)
    }

    def "a search measure leaves file inputs to the separate model payload refusal at any depth"() {
        given:
        def file = new AskedField(new FieldName("document"), FieldKind.FILE, null, many, null, [], false, false)
        def field = nested ? held("details", [file], outerMany) : file
        def asking = new Asking(INSTRUCTION, [field], GIVES)

        expect:
        SendMeasure.mostSentToSearch(asking, false) >=
                SendMeasure.mostSentToSearch(new Asking(INSTRUCTION, [], GIVES), false)

        when:
        Payload.toProduce(asking, [(field.name()): empty ? new JsonValue.JsonArray([]) : new JsonValue.JsonNull()], null)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "A file is not sent to a model"

        where:
        nested | many | outerMany | empty
        false  | null | null      | false
        false  | 2    | null      | true
        true   | null | null      | false
        true   | null | 2         | true
    }

    def "longer escaped term meanings increase the measure by the exact schema text without changing the payload"() {
        given:
        def shortAsking = new Asking(INSTRUCTION, [], [term('choice', new OfferedTerms([offered('Choice', 'A meaning.')], null))])
        def longAsking = new Asking(INSTRUCTION, [], [term('choice', new OfferedTerms([offered('Choice', 'Meaning "quoted" ' + GRINNING * 20)], null))])
        def shortEnvelope = codePoints(Envelope.toProduce(shortAsking.gives(), shortAsking.instruction()))
        def longEnvelope = codePoints(Envelope.toProduce(longAsking.gives(), longAsking.instruction()))

        when:
        def shortMeasure = SendMeasure.mostSent(shortAsking, true)
        def longMeasure = SendMeasure.mostSent(longAsking, true)

        then:
        longMeasure > shortMeasure
        longMeasure - shortMeasure == longEnvelope - shortEnvelope
        longMeasure == writtenAtMost(longAsking, true, false)
        shortMeasure - shortEnvelope == longMeasure - longEnvelope
    }

    /** A person answering in a model's place is shown what was refused, so a review is measured as though told. */
    def "the most a review could send is what its production was sent as though told, with every value to decide"() {
        expect:
        SendMeasure.mostSentToReview(asking) == writtenAtMost(asking, true, true)
        SendMeasure.mostSentToReview(asking) > writtenAtMost(asking, false, true)

        where:
        asking << askings()
    }

    def "the most a review of a production no instruction asked for could send is that review written at its longest, told"() {
        expect:
        SendMeasure.mostSentToReview(asking.takes(), asking.gives()) == writtenUninstructedAtMost(asking)

        where:
        asking << askings()
    }

    /** The same fields asked with an instruction send that instruction, and what introduces it, besides. */
    def "a review of a production no instruction asked for could send less than one of the same fields that one asked for"() {
        expect:
        SendMeasure.mostSentToReview(asking.takes(), asking.gives()) < SendMeasure.mostSentToReview(asking)

        where:
        asking << askings()
    }

    def "a measure of a review of a production no instruction asked for names what it takes and gives, or is refused by name"() {
        when:
        SendMeasure.mostSentToReview(takes, gives)

        then:
        def refused = thrown(NullPointerException)
        refused.message == message

        where:
        takes                | gives                || message
        null                 | askings()[0].gives() || "SendMeasure takes must not be null"
        askings()[0].takes() | null                 || "SendMeasure gives must not be null"
    }

    def "a review payload adds the answer even when the production schema is longer than its review instructions"() {
        expect:
        SendMeasure.mostSentToReview(asking) - codePoints(Envelope.toReview(asking.gives(), asking.instruction())) >
                SendMeasure.mostSent(asking, told) - codePoints(Envelope.toProduce(asking.gives(), asking.instruction()))

        where:
        [asking, told] << [askings(), [true, false]].combinations()
    }

    def "the longest search message equals its instruction and typed input payload with every kind of value"() {
        given:
        def chosen = term("choice", new OfferedTerms([offered('"\\' * 3, 'Escaped.'), offered('plain', 'Plain.')], null))
        def asking = new Asking(INSTRUCTION, GIVES + chosen, GIVES)
        def taken = asking.takes().collectEntries { [(it.name()): longest(it)] }
        def refused = new Payload.Refused(GIVES.collectEntries { [(it.name()): longest(it)] },
                GIVES.collectEntries { [(it.name()): '"' * 2048] })

        when:
        def actual = codePoints(CanonicalJson.write(Payload.toProduce(asking, taken, refused, asking.takes()*.name()))) +
                codePoints(Envelope.toSearch(GIVES, asking.instruction()))
        def measured = SendMeasure.mostSentToSearch(asking, true)

        then:
        measured == actual
        measured > SendMeasure.mostSentToSearch(asking, false)
    }

    def "a search counts its instruction and typed number date or moment inputs at their limits"() {
        given:
        def asking = new Asking(INSTRUCTION, [field], GIVES)

        when:
        def actual = codePoints(CanonicalJson.write(Payload.toProduce(asking,
                [(field.name()): longest(field)], null, [field.name()]))) + codePoints(Envelope.toSearch(GIVES, asking.instruction()))

        then:
        SendMeasure.mostSentToSearch(asking, false) == actual

        where:
        field << [plain('number', FieldKind.NUMBER), plain('date', FieldKind.DATE),
                  plain('moment', FieldKind.MOMENT), plain('numbers', FieldKind.NUMBER, 2),
                  plain('dates', FieldKind.DATE, 2), plain('moments', FieldKind.MOMENT, 2)]
    }

    def "the longest search review pairs with the instruction and stored inputs without a kept search record"() {
        given:
        def gives = GIVES.take(4)
        def field = text('source', 3)
        def asking = new Asking(INSTRUCTION, [field], gives)
        def taken = [(field.name()): longest(field)]
        def answer = gives.collectEntries { [(it.name()): longest(it)] }
        def refused = new Payload.Refused(answer, gives.collectEntries { [(it.name()): '"' * 2048] })
        def emptyRecord = new JsonValue.JsonObject([])

        when:
        def payload = Payload.toReviewSearch(asking, taken, refused, answer, gives*.name(), emptyRecord, [field.name()])
        def actual = codePoints(CanonicalJson.write(payload)) + codePoints(Envelope.toReviewSearch(gives, asking.instruction()))
        def measured = SendMeasure.mostSentToReviewSearch(asking)

        then:
        measured == actual
        payload.members()*.name() == ['envelope_version', 'instruction', 'takes', 'found_on_the_web', 'refused', 'answer', 'deciding']
        measured > SendMeasure.mostSentToSearch(asking, true)
    }

    def "the longest date or moment remains a typed string input beside the searching instruction"() {
        given:
        def field = plain("source", kind)
        def value = kind.longestWritten() as JsonValue.JsonString
        def asking = new Asking(INSTRUCTION, [field], GIVES)

        when:
        def payload = Payload.toProduce(asking, [(field.name()): value], null, [field.name()])
        def actual = codePoints(CanonicalJson.write(payload)) + codePoints(Envelope.toSearch(GIVES, asking.instruction()))

        then:
        payload.members()[1].value() == Json.of(INSTRUCTION.value())
        payload.members()[2].value() == Json.of([source: value])
        SendMeasure.mostSentToSearch(asking, false) == actual

        where:
        kind << [FieldKind.DATE, FieldKind.MOMENT]
    }

    def "an empty term list retains its input name and empty array in the longest search message"() {
        given:
        def field = term("unavailable", new OfferedTerms([], null), 3)
        def asking = new Asking(INSTRUCTION, [field], GIVES)

        when:
        def sent = Payload.toProduce(asking, [(field.name()): Json.of([])], null, [field.name()])
        def actual = codePoints(CanonicalJson.write(sent)) + codePoints(Envelope.toSearch(GIVES, asking.instruction()))
        def absent = Payload.toProduce(asking, [(field.name()): Json.of(null)], null, [field.name()])
        def absentLength = codePoints(CanonicalJson.write(absent)) + codePoints(Envelope.toSearch(GIVES, asking.instruction()))
        def measured = SendMeasure.mostSentToSearch(asking, false)

        then:
        sent.members()[2].value() == Json.of([unavailable: []])
        measured == Math.max(actual, absentLength)
        measured > SendMeasure.mostSentToSearch(new Asking(INSTRUCTION, [], GIVES), false)
    }

    def "a many-valued term uses its longest JSON form without an additional string encoding"() {
        given:
        def field = term("choice", new OfferedTerms([offered('""', "Quoted."), offered("abcde", "Plain.")], null), 2)
        def asking = new Asking(INSTRUCTION, [field], GIVES)
        def candidates = ['""', "abcde"].collect { chosen ->
            codePoints(CanonicalJson.write(Payload.toProduce(asking,
                    [(field.name()): Json.of([chosen, chosen])], null, [field.name()]))) +
                    codePoints(Envelope.toSearch(GIVES, asking.instruction()))
        }
        def most = SendMeasure.mostSentToSearch(asking, false)

        expect:
        writtenLength('""') < writtenLength("abcde")
        candidates[0] < candidates[1]
        most == candidates.max()
    }

    def "a repeated nested term list stays bounded across distinct search inputs and equals the actual longest JSON"() {
        given:
        def offeredTerms = new OfferedTerms((0..<256).collect { index ->
            offered(String.format('%03d', index) + '"' * 125, "Fictional choice ${index}.")
        }, null)
        def nested = (0..<255).collect { index -> term("field_${index}", offeredTerms) }
        def fields = (0..<256).collect { index -> held("input_${index}", nested) }
        def asking = new Asking(INSTRUCTION, fields, GIVES)
        def value = longest(fields.first())
        def taken = fields.collectEntries { [(it.name()): value] }
        def refused = new Payload.Refused(GIVES.collectEntries { [(it.name()): longest(it)] },
                GIVES.collectEntries { [(it.name()): '"' * 2048] })

        when:
        long started = System.nanoTime()
        def measured = SendMeasure.mostSentToSearch(asking, true)
        long elapsed = System.nanoTime() - started
        def actual = codePoints(CanonicalJson.write(Payload.toProduce(asking, taken, refused, fields*.name()))) +
                codePoints(Envelope.toSearch(GIVES, asking.instruction()))

        then:
        measured == actual
        elapsed < 10_000_000_000L
        SendMeasure.mostSentToReview(asking) > SendMeasure.mostSentToReview(asking.takes(), asking.gives())
        measured > 8_388_608L
    }

    def "a searching instruction with an unpaired surrogate cannot reach measurement"() {
        when:
        new Instruction(Character.toString(0xD800 as char))

        then:
        thrown(IllegalArgumentException)
    }

    def "a searching JSON input escapes each control once in its typed value"() {
        given:
        def field = text('control', 1)
        def asking = new Asking(INSTRUCTION, [field], GIVES)
        def payload = Payload.toProduce(asking, [(field.name()): Json.of(WORST_CHARACTER)], null, [field.name()])

        expect:
        CanonicalJson.write(payload.members()[2].value()) == '{"control":' + CanonicalJson.write(Json.of(WORST_CHARACTER)) + '}'
        writtenLength(WORST_CHARACTER) == 6
        SendMeasure.mostSentToSearch(asking, false) == codePoints(CanonicalJson.write(payload)) +
                codePoints(Envelope.toSearch(GIVES, asking.instruction()))
    }

    def "a search measure remains at the most a long holds for nested values beyond it"() {
        expect:
        SendMeasure.mostSentToSearch(new Asking(INSTRUCTION, [pastALong()], GIVES), false) ==
                Long.MAX_VALUE
    }

    /** Why the measure may count free text at six characters to one: no character is written as more. */
    def "no character is written longer than a control with no short escape, one past the first plane included"() {
        given:
        def characters = (0..0xFFFF).findAll { !Character.isSurrogate(it as char) }.collect { Character.toString(it) } +
                PAST_THE_FIRST_PLANE

        expect:
        characters.collect { writtenLength(it) }.max() == writtenLength(WORST_CHARACTER)
        writtenLength(WORST_CHARACTER) == 6
    }

    /** Why the measure may count refusal words at two characters to one: among what they hold, a quote is worst. */
    def "no character refusal words may hold is written longer than a quote"() {
        expect:
        heldInWords().collect { writtenLength(it) }.max() == writtenLength('"')
        writtenLength('"') == 2
    }

    def "the controls with no short escape are not among what refusal words may hold, and the short-escaped ones are"() {
        when:
        def held = heldInWords()

        then:
        !held.contains(WORST_CHARACTER)
        held.containsAll(["\t", "\n", '"', "\\"])
    }

    /** Past a long by multiplying, by adding two counts a long holds, or not past it at all, one wide text alone. */
    def "a count past what a long holds is held at the most a long holds rather than wrapped, and one within is not"() {
        given:
        def asking = new Asking(INSTRUCTION, takes, [text("summary", 5)])

        when:
        def most = reviewing ? SendMeasure.mostSentToReview(asking) : SendMeasure.mostSent(asking, told)

        then:
        (most == Long.MAX_VALUE) == held
        most > 5_000_000_000_000_000_000L

        where:
        [reviewing, told, taken] << [[false, true], [true, false], [
                [[pastALong()], true],
                [[wide("first"), wide("second")], true],
                [[wide("first")], false]]].combinations()
        takes = taken[0]
        held = taken[1]
    }

    def "a measure of nothing asked is refused by name"() {
        when:
        reviewing ? SendMeasure.mostSentToReview(null) : SendMeasure.mostSent(null, true)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "SendMeasure asking must not be null"

        where:
        reviewing << [false, true]
    }

    private static List<Asking> askings() {
        def escaping = new OfferedTerms([offered('""', "A pair of " + GRINNING), offered("abc", "Plain.")],
                new ListNote("Choose " + GRINNING + "."))
        [new Asking(INSTRUCTION, [text("complaint", 10), plain("received", FieldKind.MOMENT, 3),
                                  plain("amount", FieldKind.NUMBER), term("category", CATEGORIES)], GIVES),
         new Asking(INSTRUCTION, [], [term("short", new OfferedTerms([offered("a", "x"), offered("b", "y")], null)),
                                      term("offered_none", new OfferedTerms([], null), 4), text("t", 1)]),
         new Asking(INSTRUCTION, [], [held("rows", [text("cell", 3, 2, false, true), plain("on", FieldKind.YES_NO)],
                 4, true)]),
         new Asking(INSTRUCTION, [term("quoted", escaping)], [term("quoted", escaping, 2), text("summary", 1_000)]),
         new Asking(INSTRUCTION, [text("letter", 1_000)], [text("summary", 1_000, null, true, true),
                                                           plain("due", FieldKind.DATE, 2)])]
    }

    /** Fields held many times over, deep enough that the longest they could hold is past what a long holds. */
    private static AskedField pastALong() {
        held("rows", [held("cells", [text("cell", Integer.MAX_VALUE, Integer.MAX_VALUE)], Integer.MAX_VALUE)],
                Integer.MAX_VALUE)
    }

    /** Text held many times, at its longest a little over half of what a long holds, so two of it are past it. */
    private static AskedField wide(String name) {
        text(name, 2_000_000_000, 420_000_000)
    }

    /** Every value at its longest, the words at theirs, built into the payload and written, and counted. */
    private static long writtenAtMost(Asking asking, boolean told, boolean reviewing) {
        def taken = asking.takes().collectEntries { [(it.name()): longest(it)] }
        def given = asking.gives().collectEntries { [(it.name()): longest(it)] }
        def refused = told
                ? new Payload.Refused(given, asking.gives().collectEntries { [(it.name()): '"' * 2048] })
                : null
        def payload = reviewing
                ? Payload.toReview(asking, taken, refused, given, asking.gives()*.name(), asking.takes()*.name())
                : Payload.toProduce(asking, taken, refused, asking.takes()*.name())
        def envelope = reviewing ? Envelope.toReview(asking.gives(), asking.instruction()) : Envelope.toProduce(asking.gives(), asking.instruction())
        codePoints(CanonicalJson.write(payload)) + codePoints(envelope)
    }

    /** As {@link #writtenAtMost} of a review, told, sent with no instruction. */
    private static long writtenUninstructedAtMost(Asking asking) {
        def taken = asking.takes().collectEntries { [(it.name()): longest(it)] }
        def given = asking.gives().collectEntries { [(it.name()): longest(it)] }
        def refused = new Payload.Refused(given, asking.gives().collectEntries { [(it.name()): '"' * 2048] })
        def payload = Payload.toReviewUninstructed(asking.takes(), asking.gives(), taken, refused, given,
                asking.gives()*.name(), asking.takes()*.name())
        codePoints(CanonicalJson.write(payload)) + codePoints(Envelope.toReviewUninstructed(asking.gives()))
    }

    /** Each character refusal words may hold, judged beside a letter so that none is refused for showing nothing. */
    private static List<String> heldInWords() {
        ((0..0xFFFF).findAll { !Character.isSurrogate(it as char) }.collect { Character.toString(it) } +
                PAST_THE_FIRST_PLANE).findAll {
            Unwrapping.review([new FieldName("summary")],
                    Json.of([decisions: [summary: [outcome: "refused", words: "a" + it]]])) instanceof ReviewAnswer.Reviewed
        }
    }

    private static long twiceWrittenLength(String said) {
        codePoints(CanonicalJson.write(new JsonValue.JsonString(
                CanonicalJson.write(new JsonValue.JsonString(said))))) -
                codePoints(CanonicalJson.write(new JsonValue.JsonString(CanonicalJson.write(new JsonValue.JsonString("")))))
    }

    private static long writtenLength(String said) {
        codePoints(CanonicalJson.write(new JsonValue.JsonString(said))) - 2
    }
}
