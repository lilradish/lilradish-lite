package org.lilradish.lite.domain.inference

import static org.lilradish.lite.testutil.inference.Askings.CATEGORIES
import static org.lilradish.lite.testutil.inference.Askings.GIVES
import static org.lilradish.lite.testutil.inference.Askings.WORST_CHARACTER
import static org.lilradish.lite.testutil.inference.Askings.codePoints
import static org.lilradish.lite.testutil.inference.Askings.held
import static org.lilradish.lite.testutil.inference.Askings.longest
import static org.lilradish.lite.testutil.inference.Askings.offered
import static org.lilradish.lite.testutil.inference.Askings.plain
import static org.lilradish.lite.testutil.inference.Askings.term
import static org.lilradish.lite.testutil.inference.Askings.text

import org.lilradish.lite.domain.declaration.AskedField
import org.lilradish.lite.domain.declaration.Asking
import org.lilradish.lite.domain.declaration.FieldKind
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.declaration.Instruction
import org.lilradish.lite.domain.declaration.OfferedTerms
import org.lilradish.lite.domain.referencelist.ListNote
import org.lilradish.lite.domain.wire.CanonicalJson
import org.lilradish.lite.domain.wire.JsonValue
import org.lilradish.lite.testutil.inference.Json
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
        SendMeasure.mostSentToSearch([], mixed.gives(), true) ==
                SendMeasure.mostSentToSearch([], without.gives(), true)
        SendMeasure.mostSentToReviewSearch([], mixed.gives()) ==
                SendMeasure.mostSentToReviewSearch([], without.gives())
        SendMeasure.mostSentToReview(mixed) - codePoints(Envelope.toReview(mixed.gives())) >
                SendMeasure.mostSent(mixed, false) - codePoints(Envelope.toProduce(mixed.gives()))
        SendMeasure.mostSent(mixed, false) > SendMeasure.mostSent(new Asking(INSTRUCTION, [], []), false)
    }

    def "a search measure refuses a file source at any depth even with an absent or empty value"() {
        given:
        def file = new AskedField(new FieldName("document"), FieldKind.FILE, null, many, null, [], false, false)
        def field = nested ? held("details", [file], outerMany) : file
        def value = empty ? new JsonValue.JsonArray([]) : null
        def part = new Payload.SearchPart.ValuePart(field, value)

        when:
        SendMeasure.mostSentToSearch([part], GIVES, false)

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
        def shortEnvelope = codePoints(Envelope.toProduce(shortAsking.gives()))
        def longEnvelope = codePoints(Envelope.toProduce(longAsking.gives()))

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
        SendMeasure.mostSentToReview(asking) - codePoints(Envelope.toReview(asking.gives())) >
                SendMeasure.mostSent(asking, told) - codePoints(Envelope.toProduce(asking.gives()))

        where:
        [asking, told] << [askings(), [true, false]].combinations()
    }

    def "the longest search message equals its actual fully escaped payload and envelope with every kind of value"() {
        given:
        def chosen = term("choice", new OfferedTerms([offered('"\\' * 3, 'Escaped.'), offered('plain', 'Plain.')], null))
        def parts = [new Payload.SearchPart.LiteralPart('Before "\\\u0001' + GRINNING)] +
                (GIVES + chosen).collect { new Payload.SearchPart.ValuePart(it, longest(it)) } +
                [new Payload.SearchPart.LiteralPart('\nAfter')]
        def refused = new Payload.Refused(GIVES.collectEntries { [(it.name()): longest(it)] },
                GIVES.collectEntries { [(it.name()): '"' * 2048] })

        when:
        def actual = codePoints(CanonicalJson.write(Payload.toSearch(parts, GIVES, refused))) +
                codePoints(Envelope.toSearch(GIVES))
        def measured = SendMeasure.mostSentToSearch(parts, GIVES, true)

        then:
        measured == actual
        measured > SendMeasure.mostSentToSearch(parts, GIVES, false)
    }

    def "a search counts the actual full message for a single or many number date and moment at their limits"() {
        given:
        def parts = [new Payload.SearchPart.LiteralPart('before '),
                new Payload.SearchPart.ValuePart(field, longest(field)),
                new Payload.SearchPart.LiteralPart(' after')]

        when:
        def actual = codePoints(CanonicalJson.write(Payload.toSearch(parts, GIVES, null))) +
                codePoints(Envelope.toSearch(GIVES))

        then:
        SendMeasure.mostSentToSearch(parts, GIVES, false) == actual

        where:
        field << [plain('number', FieldKind.NUMBER), plain('date', FieldKind.DATE),
                  plain('moment', FieldKind.MOMENT), plain('numbers', FieldKind.NUMBER, 2),
                  plain('dates', FieldKind.DATE, 2), plain('moments', FieldKind.MOMENT, 2)]
    }

    def "the longest search review pairs with the written payload without a kept search record"() {
        given:
        def gives = GIVES.take(4)
        def field = text('source', 3)
        def parts = [new Payload.SearchPart.LiteralPart('Find "'),
                new Payload.SearchPart.ValuePart(field, longest(field)),
                new Payload.SearchPart.LiteralPart('"')]
        def question = Payload.toSearch(parts, gives, null).members()[1].value().value()
        def answer = gives.collectEntries { [(it.name()): longest(it)] }
        def refused = new Payload.Refused(answer, gives.collectEntries { [(it.name()): '"' * 2048] })
        def emptyRecord = new JsonValue.JsonObject([])

        when:
        def payload = Payload.toReviewSearch(question, gives, refused, answer, gives*.name(), emptyRecord)
        def actual = codePoints(CanonicalJson.write(payload)) + codePoints(Envelope.toReviewSearch(gives))
        def measured = SendMeasure.mostSentToReviewSearch(parts, gives)

        then:
        measured == actual
        payload.members()*.name() == ['envelope_version', 'question', 'refused', 'answer', 'deciding']
        measured > SendMeasure.mostSentToSearch(parts, gives, true)
    }

    def "the longest date or moment is counted as written inside the actual search question"() {
        given:
        def field = plain("source", kind)
        def value = kind.longestWritten() as JsonValue.JsonString
        def parts = [new Payload.SearchPart.LiteralPart("At "),
                new Payload.SearchPart.ValuePart(field, value)]

        when:
        def payload = Payload.toSearch(parts, GIVES, null)
        def question = payload.members()[1].value().value()
        def actual = codePoints(CanonicalJson.write(payload)) + codePoints(Envelope.toSearch(GIVES))

        then:
        question == 'At "' + value.value() + '"'
        question != "At " + value.value()
        SendMeasure.mostSentToSearch(parts, GIVES, false) == actual

        where:
        kind << [FieldKind.DATE, FieldKind.MOMENT]
    }

    def "an empty top-level term list contributes no question text or array marks to the longest message"() {
        given:
        def field = term("unavailable", new OfferedTerms([], null), 3)
        def parts = [new Payload.SearchPart.LiteralPart("Find "),
                new Payload.SearchPart.ValuePart(field, Json.of([]))]

        when:
        def sent = Payload.toSearch(parts, GIVES, null)
        def actual = codePoints(CanonicalJson.write(sent)) + codePoints(Envelope.toSearch(GIVES))
        def measured = SendMeasure.mostSentToSearch(parts, GIVES, false)

        then:
        sent.members()[1].value() == Json.of("Find ")
        measured == actual
        measured == SendMeasure.mostSentToSearch([new Payload.SearchPart.LiteralPart("Find ")], GIVES, false)
    }

    def "a many-valued term uses its longest double-escaped form, even when another is longer once escaped"() {
        given:
        def field = term("choice", new OfferedTerms([offered('""', "Quoted."), offered("abcde", "Plain.")], null), 2)
        def candidates = ['""', "abcde"].collect { chosen ->
            def parts = [new Payload.SearchPart.ValuePart(field, Json.of([chosen, chosen]))]
            codePoints(CanonicalJson.write(Payload.toSearch(parts, GIVES, null))) +
                    codePoints(Envelope.toSearch(GIVES))
        }
        def most = SendMeasure.mostSentToSearch([new Payload.SearchPart.ValuePart(field, null)], GIVES, false)

        expect:
        writtenLength('""') < writtenLength("abcde")
        candidates[0] > candidates[1]
        most == candidates.max()
    }

    def "a repeated nested term list stays bounded when every search part reads it"() {
        given:
        def offeredTerms = new OfferedTerms((0..<256).collect { index ->
            offered(String.format('%03d', index) + '"' * 125, "Choice ${index}.")
        }, null)
        def source = held('source', (0..<255).collect { index -> term("field_${index}", offeredTerms) })
        def part = new Payload.SearchPart.ValuePart(source, null)
        def empty = SendMeasure.mostSentToSearch([], GIVES, true)
        def one = SendMeasure.mostSentToSearch([part], GIVES, true)

        when:
        long started = System.nanoTime()
        def many = SendMeasure.mostSentToSearch(Collections.nCopies(256, part), GIVES, true)
        long elapsed = System.nanoTime() - started
        def asking = new Asking(INSTRUCTION, [source], [source])
        def questionReview = SendMeasure.mostSentToReview(asking)
        def codeReview = SendMeasure.mostSentToReview([source], [source])

        then:
        many == empty + 256 * (one - empty)
        elapsed < 10_000_000_000L
        questionReview > codeReview
        codeReview > one
    }

    def "a search literal with an unpaired surrogate is refused while counting"() {
        given:
        def part = new Payload.SearchPart.LiteralPart(Character.toString(0xD800 as char))

        when:
        SendMeasure.mostSentToSearch([part], GIVES, false)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == 'CanonicalJson refuses a text holding an unpaired surrogate'
    }

    def "a doubly escaped control with no short escape is the longest written character"() {
        given:
        def characters = (0..0xFFFF).findAll { !Character.isSurrogate(it as char) }.collect { Character.toString(it) } +
                PAST_THE_FIRST_PLANE

        expect:
        characters.collect { twiceWrittenLength(it) }.max() == twiceWrittenLength(WORST_CHARACTER)
        twiceWrittenLength(WORST_CHARACTER) == 7
    }

    def "a search measure remains at the most a long holds for nested values beyond it"() {
        expect:
        SendMeasure.mostSentToSearch([new Payload.SearchPart.ValuePart(pastALong(), null)], GIVES, false) ==
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
        def envelope = reviewing ? Envelope.toReview(asking.gives()) : Envelope.toProduce(asking.gives())
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
