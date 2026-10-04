package org.lilradish.lite.domain.inference

import org.lilradish.lite.domain.model.DeployedModel
import org.lilradish.lite.domain.model.ModelName
import org.lilradish.lite.domain.model.SentText
import spock.lang.Specification

class CallOutcomeSpec extends Specification {

    /** A call that is never heard from again is found on starting, and returned by no call at all. */
    def "a call ends one of exactly four ways"() {
        expect:
        CallOutcome.permittedSubclasses as Set ==
                [CallOutcome.CameBack, CallOutcome.Errored, CallOutcome.TurnedAway, CallOutcome.NotResent] as Set
    }

    def "an answer that came back keeps what it was given, down to an empty answer and nothing come back"() {
        when:
        def cameBack = new CallOutcome.CameBack(answer, sentCount, cameBackCount, countedByModel, cutOff, null, null)

        then:
        cameBack.answer() == answer
        cameBack.sentCount() == sentCount
        cameBack.cameBackCount() == cameBackCount
        cameBack.countedByModel() == countedByModel
        cameBack.cutOff() == cutOff

        where:
        answer           | sentCount      | cameBackCount  | countedByModel | cutOff
        '{"total":"12"}' | 1              | 0              | true           | false
        ""               | 200000         | 8192           | false          | true
        "partial"        | Long.MAX_VALUE | Long.MAX_VALUE | true           | true
    }

    def "an answer that came back refuses counts the model could not have given, and a missing answer"() {
        when:
        new CallOutcome.CameBack(answer, sentCount, cameBackCount, true, false, null, null)

        then:
        def error = thrown(expectedException)
        error.message == expectedMessage

        where:
        answer | sentCount      | cameBackCount  || expectedException        | expectedMessage
        null   | 1              | 0              || NullPointerException     | "CameBack answer must not be null"
        "a"    | 0              | 0              || IllegalArgumentException | "CameBack sent count must be at least one: 0"
        "a"    | Long.MIN_VALUE | 0              || IllegalArgumentException | "CameBack sent count must be at least one: -9223372036854775808"
        "a"    | 1              | -1             || IllegalArgumentException | "CameBack came-back count must not be negative: -1"
    }

    def "decline words belong only to a declined model completion"() {
        when:
        new CallOutcome.CameBack("Demo Sample answer", 1, 1, true, false, reason, "Demo Sample decline")

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "CameBack has decline words only with model decline"

        where:
        reason << [null, DidNotFitReason.NOT_THE_SHAPE]
    }

    def "an error refuses to leave what went wrong unsaid"() {
        when:
        new CallOutcome.Errored(detail, null)

        then:
        def error = thrown(expectedException)
        error.message == expectedMessage

        where:
        detail || expectedException        | expectedMessage
        null   || NullPointerException     | "Errored detail must not be null"
        ""     || IllegalArgumentException | "Errored must say what went wrong"
    }

    def "fallback counts answer, decline and generated tool call together before rounding once"() {
        given:
        def model = new DeployedModel(new ModelName("sample_model"), [], 1000, new BigDecimal("2"), 1000, [])
        def sent = SentText.measure("system", "question")

        when:
        def returned = CallOutcome.CameBack.measuredHere(model, sent, "a", false,
                DidNotFitReason.MODEL_DECLINED, "b", 1L)

        then:
        returned.cameBackCount() == 2L
        !returned.countedByModel()
        returned.answer() == "a"
        returned.declinedWords() == "b"
    }

    def "fallback refuses a negative generated tool-call count"() {
        given:
        def model = new DeployedModel(new ModelName("sample_model"), [], 1000, BigDecimal.ONE, 1000, [])

        when:
        CallOutcome.CameBack.measuredHere(model, SentText.measure("system", "question"), "answer", false,
                null, null, -1L)

        then:
        def invalid = thrown(IllegalArgumentException)
        invalid.message == "Generated tool-call characters must not be negative"
    }

    /** The one carrier of whether it was used up, since the last turnaway is reported nowhere else. */
    def "a call turned away keeps the turnaway that ended it"() {
        given:
        def last = new TurnAway("spend limit reached", true)

        when:
        def turnedAway = new CallOutcome.TurnedAway(last)

        then:
        turnedAway.last() == last
    }

    def "a call turned away refuses to leave the turnaway that ended it out"() {
        when:
        new CallOutcome.TurnedAway(null)

        then:
        def error = thrown(NullPointerException)
        error.message == "TurnedAway last must not be null"
    }
}
