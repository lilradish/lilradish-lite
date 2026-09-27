package org.lilradish.lite.domain.run

import static org.lilradish.lite.domain.run.fixture.Runs.attemptId
import static org.lilradish.lite.domain.run.fixture.Runs.key
import static org.lilradish.lite.domain.run.fixture.Runs.sentToReview
import static org.lilradish.lite.domain.run.fixture.Runs.tryId
import static org.lilradish.lite.domain.run.fixture.Runs.value
import static org.lilradish.lite.domain.run.fixture.Runs.yielded

import org.lilradish.lite.domain.inference.ModelCallOutcome
import org.lilradish.lite.domain.inference.ModelCallPurpose
import org.lilradish.lite.domain.workflow.StepProducer
import spock.lang.Specification

class ReviewSendingSpec extends Specification {

    static final TryRecord PRODUCED = yielded(1, StepProducer.MODEL, [value(1, "answer", true)])

    def "each way a try can stand with its reviewer is published under its own spelling, in this order"() {
        expect:
        ReviewSending.values()*.published() == ["unsent", "out", "turned_away", "too_long", "list_not_here",
                                                "takes_no_longer_declared", "no_longer_declared"]
    }

    /** Sending again settles none of these, since nothing is ever sent for them. */
    def "values are handed to a person exactly where nothing is ever sent for them, too long or not built"() {
        expect:
        ReviewSending.values().findAll { it.toPerson() } == [ReviewSending.TOO_LONG, ReviewSending.LIST_NOT_HERE,
                                                              ReviewSending.TAKES_NO_LONGER_DECLARED,
                                                              ReviewSending.NO_LONGER_DECLARED]
        ReviewSending.values().findAll { !it.toPerson() } ==
                [ReviewSending.UNSENT, ReviewSending.OUT, ReviewSending.TURNED_AWAY]
    }

    def "a try stands with its reviewer as the newest attempt to review it went"() {
        expect:
        ReviewSending.of(reviewing(PRODUCED, sent)) == expected

        where:
        sent                                                || expected
        []                                                  || ReviewSending.UNSENT
        [[false, null]]                                     || ReviewSending.OUT
        [[false, ModelCallOutcome.TURNED_AWAY]]             || ReviewSending.TURNED_AWAY
        [[true, null]]                                      || ReviewSending.TOO_LONG
        [[false, ModelCallOutcome.TURNED_AWAY], [false, null]] || ReviewSending.OUT
        [[false, null], [false, ModelCallOutcome.TURNED_AWAY]] || ReviewSending.TURNED_AWAY
        [[false, ModelCallOutcome.TURNED_AWAY], [true, null]] || ReviewSending.TOO_LONG
        [[true, null], [false, ModelCallOutcome.TURNED_AWAY]] || ReviewSending.TOO_LONG
    }

    /** Written as one too long is, and read apart from it by why nothing could be built. */
    def "a try whose review could not be built stands as why, whatever was sent to review it before"() {
        expect:
        ReviewSending.of(reviewing(PRODUCED, sent)) == expected

        where:
        sent                                                                            || expected
        [[true, null, ReviewUnbuiltReason.NO_LONGER_DECLARED]]                          || ReviewSending.NO_LONGER_DECLARED
        [[true, null, ReviewUnbuiltReason.LIST_NOT_HERE]]                               || ReviewSending.LIST_NOT_HERE
        [[true, null, ReviewUnbuiltReason.TAKES_NO_LONGER_DECLARED]]                    ||
                ReviewSending.TAKES_NO_LONGER_DECLARED
        [[false, ModelCallOutcome.TURNED_AWAY], [true, null, ReviewUnbuiltReason.LIST_NOT_HERE]] ||
                ReviewSending.LIST_NOT_HERE
        [[false, ModelCallOutcome.TURNED_AWAY], [true, null, ReviewUnbuiltReason.NO_LONGER_DECLARED]] ||
                ReviewSending.NO_LONGER_DECLARED
    }

    /** Such a call left a review, so nothing of the try waits on one, and nothing is read as waiting to be sent. */
    def "a try whose review call came back, went wrong or never came back stands as nothing waiting to be sent"() {
        expect:
        ReviewSending.of(sentToReview(PRODUCED, false, outcome)) == ReviewSending.UNSENT

        where:
        outcome << [ModelCallOutcome.CAME_BACK, ModelCallOutcome.ERRORED, ModelCallOutcome.NOTHING_CAME_BACK]
    }

    def "only an attempt to review decides it, and one to produce is passed over"() {
        given:
        def produced = new TryRecord(tryId(1), 1, StepProducer.MODEL, null, PRODUCED.askedAt(), PRODUCED.endedAt(),
                null, null, null, null, null, false, null, PRODUCED.values(), [], [],
                [new AttemptRecord(attemptId(1), ModelCallPurpose.PRODUCE, false, null)],
                [new CallRecord(new ModelCallId(key(1100)), attemptId(1), ModelCallOutcome.TURNED_AWAY, false)])

        expect:
        ReviewSending.of(produced) == ReviewSending.UNSENT
        ReviewSending.newestCall(produced) == Optional.empty()
    }

    def "the newest call sent to review a try is the one its newest attempt sent, none where none was sent"() {
        given:
        def twice = reviewing(PRODUCED, [[false, ModelCallOutcome.TURNED_AWAY], [false, null]])

        expect:
        ReviewSending.newestCall(twice).map { it.attempt() } == Optional.of(attemptId(61))
        ReviewSending.newestCall(twice).map { it.outcome() } == Optional.empty()
        ReviewSending.newestCall(PRODUCED) == Optional.empty()
        ReviewSending.newestCall(sentToReview(PRODUCED, true)) == Optional.empty()
    }

    /** What a press on a turnaway names stays the turned-away call's, whatever was left unbuilt after it. */
    def "the newest call sent to review a try passes over an attempt that could not be built"() {
        given:
        def unbuilt = reviewing(PRODUCED,
                [[false, ModelCallOutcome.TURNED_AWAY], [true, null, ReviewUnbuiltReason.NO_LONGER_DECLARED]])

        expect:
        ReviewSending.newestCall(unbuilt).map { it.attempt() } == Optional.of(attemptId(60))
        ReviewSending.newestCall(unbuilt).map { it.outcome() } == Optional.of(ModelCallOutcome.TURNED_AWAY)
    }

    def "a try sent to be reviewed with no call made is refused as what the store never holds"() {
        given:
        def uncalled = new TryRecord(tryId(1), 1, StepProducer.MODEL, null, PRODUCED.askedAt(), PRODUCED.endedAt(),
                null, null, null, null, null, false, null, PRODUCED.values(), [], [],
                [new AttemptRecord(attemptId(1), ModelCallPurpose.REVIEW, false, null)], [])

        when:
        ReviewSending.of(uncalled)

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "Try ${tryId(1).value()} was sent to be reviewed with no call made" as String
    }

    /**
     * {@code aTry} with one attempt to review it for each of {@code sent}, in order, each whether it was too long, how
     * its call ended, and why it could not be built where it was not; the attempts are keyed from 60, the calls from
     * 1160.
     */
    private static TryRecord reviewing(TryRecord aTry, List<List<Object>> sent) {
        def attempts = []
        def calls = []
        sent.eachWithIndex { List<Object> each, int index ->
            def attempt = new AttemptRecord(attemptId(60 + index), ModelCallPurpose.REVIEW, each[0] as boolean,
                    each[2] as ReviewUnbuiltReason)
            attempts << attempt
            if (!(each[0] as boolean)) {
                calls << new CallRecord(new ModelCallId(key(1160 + index)), attempt.id(), each[1] as ModelCallOutcome,
                        false)
            }
        }
        new TryRecord(aTry.id(), aTry.number(), aTry.producer(), null, aTry.askedAt(), aTry.endedAt(), null, null,
                null, null, null, false, null, aTry.values(), [], [], attempts, calls)
    }
}
