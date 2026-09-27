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

    def "a review could send more than its production could, told or not"() {
        expect:
        SendMeasure.mostSentToReview(asking) > SendMeasure.mostSent(asking, told)

        where:
        [asking, told] << [askings(), [true, false]].combinations()
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

    /**
     * One of every kind on both halves; a term whose terms are all shorter than none, one offering none, and one
     * whose longest term written is not its longest term typed; no takes at all; fields held many times holding
     * many; fields that must be given at each depth, which the envelope says of them; and text past the first
     * plane, counted as characters rather than as what holds them.
     */
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
                ? Payload.toReview(asking, taken, refused, given, asking.gives()*.name())
                : Payload.toProduce(asking, taken, refused)
        def envelope = reviewing ? Envelope.toReview(asking.gives()) : Envelope.toProduce(asking.gives())
        codePoints(CanonicalJson.write(payload)) + codePoints(envelope)
    }

    /** As {@link #writtenAtMost} of a review, told, sent with no instruction. */
    private static long writtenUninstructedAtMost(Asking asking) {
        def taken = asking.takes().collectEntries { [(it.name()): longest(it)] }
        def given = asking.gives().collectEntries { [(it.name()): longest(it)] }
        def refused = new Payload.Refused(given, asking.gives().collectEntries { [(it.name()): '"' * 2048] })
        def payload = Payload.toReviewUninstructed(asking.takes(), asking.gives(), taken, refused, given,
                asking.gives()*.name())
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

    private static long writtenLength(String said) {
        codePoints(CanonicalJson.write(new JsonValue.JsonString(said))) - 2
    }
}
