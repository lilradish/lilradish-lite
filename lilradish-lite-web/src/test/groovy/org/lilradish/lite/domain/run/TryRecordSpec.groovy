package org.lilradish.lite.domain.run

import static org.lilradish.lite.domain.run.fixture.Runs.PERSON
import static org.lilradish.lite.domain.run.fixture.Runs.askedAt
import static org.lilradish.lite.domain.run.fixture.Runs.attemptId
import static org.lilradish.lite.domain.run.fixture.Runs.endedAt
import static org.lilradish.lite.domain.run.fixture.Runs.key
import static org.lilradish.lite.domain.run.fixture.Runs.lengthReview
import static org.lilradish.lite.domain.run.fixture.Runs.lost
import static org.lilradish.lite.domain.run.fixture.Runs.lostReview
import static org.lilradish.lite.domain.run.fixture.Runs.minutes
import static org.lilradish.lite.domain.run.fixture.Runs.open
import static org.lilradish.lite.domain.run.fixture.Runs.personReview
import static org.lilradish.lite.domain.run.fixture.Runs.refused
import static org.lilradish.lite.domain.run.fixture.Runs.tryId
import static org.lilradish.lite.domain.run.fixture.Runs.value
import static org.lilradish.lite.domain.run.fixture.Runs.yielded
import static org.lilradish.lite.domain.workflow.StepProducer.MODEL

import org.lilradish.lite.domain.codestep.CodeError
import org.lilradish.lite.domain.codestep.CodeErrorReason
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.inference.ModelCallOutcome
import org.lilradish.lite.domain.inference.ModelCallPurpose
import org.lilradish.lite.domain.workflow.StepProducer
import spock.lang.Specification

class TryRecordSpec extends Specification {

    static final CodeError.Fault TOO_LONG_RECEIPT =
            new CodeError.Fault(CodeErrorReason.TOO_LONG, [new FieldName("receipt")], null, null)

    def "a try is refused numbered below one"() {
        when:
        new TryRecord(tryId(1), number, MODEL, null, askedAt(1), null, null, null, null, null, null, false, null, [], [],
                [], [], [])

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "TryRecord number must be positive: " + number

        where:
        number << [0, -1]
    }

    def "an open try is refused where it names who ended it, how it was lost, or values it gave back"() {
        when:
        new TryRecord(tryId(1), 1, MODEL, null, askedAt(1), null, named == "who ended it" ? PERSON : null, null,
                named == "how it was lost" ? TryLostReason.ERRORED : null, null, null, false, null,
                named == "a value" ? [value(1, "answer", false)] : [], [], [], [], [])

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "TryRecord open ended nowhere and gave nothing back"

        where:
        named << ["who ended it", "how it was lost", "a value"]
    }

    def "a lost try is refused where it gave values back"() {
        when:
        new TryRecord(tryId(1), 1, MODEL, null, askedAt(1), endedAt(1), null, null, TryLostReason.DID_NOT_FIT, null,
                null, false, null, [value(1, "answer", false)], [], [], [], [])

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "TryRecord lost gave nothing back"
    }

    def "a try of code gone wrong says why once: as this system's reason, or in what the code said"() {
        when:
        new TryRecord(tryId(1), 1, StepProducer.CODE, null, askedAt(1), endedAt(1), null, null, TryLostReason.ERRORED,
                fault, detail, false, returned, [], [], [], [], [])

        then:
        noExceptionThrown()

        where:
        fault            | detail      | returned
        TOO_LONG_RECEIPT | null        | '{"receipt":"R-123456789"}'
        TOO_LONG_RECEIPT | null        | null
        null             | "It broke." | null
    }

    def "a try is refused where code went wrong saying why twice or not at all, or where anything else gives code's reason"() {
        when:
        new TryRecord(tryId(1), 1, producer, null, askedAt(1), endedAt(1), producer == StepProducer.PERSON ? PERSON : null,
                producer == StepProducer.PERSON ? "Read it." : null, lost, fault, detail, false, null, [], [], [], [],
                [])

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "TryRecord gives this system's reason exactly where code went wrong without saying why itself"

        where:
        producer            | lost                            | fault            | detail
        StepProducer.CODE   | TryLostReason.ERRORED           | TOO_LONG_RECEIPT | "It broke."
        StepProducer.CODE   | TryLostReason.ERRORED           | null             | null
        StepProducer.CODE   | TryLostReason.NOTHING_CAME_BACK | TOO_LONG_RECEIPT | null
        StepProducer.CODE   | null                            | TOO_LONG_RECEIPT | null
        MODEL               | TryLostReason.ERRORED           | TOO_LONG_RECEIPT | null
        StepProducer.PERSON | null                            | TOO_LONG_RECEIPT | null
    }

    def "a try is open until it ends, and yielded exactly where it ended without being lost"() {
        given:
        def aTry = ended == "open" ? open(1, MODEL) : (ended == "lost" ? lost(1, MODEL)
                : yielded(1, MODEL, ended == "with nothing given back" ? [] : [value(1, "answer", false)]))

        expect:
        aTry.open() == isOpen
        aTry.yielded() == isYielded

        where:
        ended                     || isOpen | isYielded
        "open"                    || true   | false
        "lost"                    || false  | false
        "with nothing given back" || false  | true
        "with a value given back" || false  | true
    }

    def "a try is unsent exactly where a model's is open with no attempt to produce it"() {
        expect:
        sent(producer, ended, attempts, []).unsent() == unsent

        where:
        producer            | ended | attempts                                                                    || unsent
        MODEL               | false | []                                                                          || true
        MODEL               | false | [attempt(1, ModelCallPurpose.PRODUCE)]                                      || false
        MODEL               | true  | []                                                                          || false
        MODEL               | true  | [attempt(1, ModelCallPurpose.REVIEW)]                                       || false
        StepProducer.PERSON | false | []                                                                          || false
        StepProducer.CODE   | false | []                                                                          || false
    }

    def "the review deciding values waiting on a try is its one not for length, a review that went wrong among them"() {
        given:
        def answer = value(1, "answer", true)
        def forLength = lengthReview(minutes(40), [refused(answer)])
        def byPerson = personReview(minutes(30), [refused(answer)])
        def wentWrong = lostReview(minutes(30))
        def reviews = [
                "none"                            : [],
                "only one for length"             : [forLength],
                "a person's, after one for length": [forLength, byPerson],
                "the model's that went wrong"     : [wentWrong],
        ][held]
        def expected = [byPerson: byPerson, wentWrong: wentWrong][deciding]

        expect:
        yielded(1, MODEL, [answer], reviews).onReview() == Optional.ofNullable(expected)

        where:
        held                               || deciding
        "none"                             || "none"
        "only one for length"              || "none"
        "a person's, after one for length" || "byPerson"
        "the model's that went wrong"      || "wentWrong"
    }

    def "a try holds attempts to produce it where a model does, to review it once it gave something back, and their calls"() {
        when:
        def aTry = sent(producer, ended, attempts, calls)

        then:
        noExceptionThrown()
        aTry.calls()*.attempt() == sentBy.collect { attemptId(it) }

        where:
        producer            | ended | attempts                                                                | calls                                                          || sentBy
        MODEL               | true  | [attempt(1, ModelCallPurpose.PRODUCE), attempt(2, ModelCallPurpose.REVIEW)] | [call(1, 2, null), call(2, 1, ModelCallOutcome.CAME_BACK)] || [2, 1]
        MODEL               | false | [attempt(1, ModelCallPurpose.PRODUCE)]                                  | [call(1, 1, null)]                                             || [1]
        MODEL               | false | []                                                                      | []                                                             || []
        StepProducer.PERSON | true  | [attempt(2, ModelCallPurpose.REVIEW)]                                   | [call(1, 2, ModelCallOutcome.ERRORED)]                         || [2]
        StepProducer.CODE   | true  | [attempt(2, ModelCallPurpose.REVIEW)]                                   | []                                                             || []
    }

    def "a try is refused sent to be produced by a model where a model does not produce it"() {
        when:
        sent(producer, true, [attempt(1, ModelCallPurpose.PRODUCE)], [])

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "TryRecord is sent to be produced only where a model produces it"

        where:
        producer << [StepProducer.PERSON, StepProducer.CODE]
    }

    def "a try is refused sent to be reviewed before it gave something back"() {
        when:
        new TryRecord(tryId(1), 1, MODEL, null, askedAt(1), ended ? endedAt(1) : null, null, null,
                ended ? TryLostReason.ERRORED : null, null, null, false, null, [], [], [],
                [attempt(1, ModelCallPurpose.PRODUCE), attempt(2, ModelCallPurpose.REVIEW)], [])

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "TryRecord is sent to be reviewed only once it gave something back"

        where:
        ended << [false, true]
    }

    def "a try is refused holding a call no attempt of it sent"() {
        when:
        sent(MODEL, true, attempts, [call(1, 2, ModelCallOutcome.ERRORED)])

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "TryRecord holds a call no attempt of it sent"

        where:
        attempts << [[], [attempt(1, ModelCallPurpose.PRODUCE)]]
    }

    private static AttemptRecord attempt(int number, ModelCallPurpose purpose) {
        new AttemptRecord(attemptId(number), purpose, false, null)
    }

    private static CallRecord call(int number, int attempt, ModelCallOutcome outcome) {
        new CallRecord(new ModelCallId(key(1100 + number)), attemptId(attempt), outcome, false)
    }

    /** Open, or yielded with nothing given back. */
    private static TryRecord sent(StepProducer producer, boolean ended, List<AttemptRecord> attempts,
                                  List<CallRecord> calls) {
        def byPerson = ended && producer == StepProducer.PERSON
        new TryRecord(tryId(1), 1, producer, null, askedAt(1), ended ? endedAt(1) : null, byPerson ? PERSON : null,
                byPerson ? "Read it." : null, null, null, null, false, null, [], [], [], attempts, calls)
    }
}
