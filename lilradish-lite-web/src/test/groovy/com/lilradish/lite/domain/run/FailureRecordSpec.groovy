package com.lilradish.lite.domain.run

import static com.lilradish.lite.domain.run.fixture.Runs.key
import static com.lilradish.lite.domain.run.fixture.Runs.minutes
import static com.lilradish.lite.domain.run.fixture.Runs.tryId

import com.lilradish.lite.domain.inference.ModelCallPurpose
import spock.lang.Specification

class FailureRecordSpec extends Specification {

    static final RunStepFailureId FAILURE = new RunStepFailureId(key(1101))
    static final HoldRecord.SourceRecord MAKER = new HoldRecord.SourceRecord(new RunId(key(1102)), new RunStepId(key(1103)), key(1104))

    def "a source mismatch names the actual maker and declaration pointer on the same unsent model production"() {
        given:
        def source = new FailureRecord.SourceRecord(MAKER, 'receipt.note')

        when:
        def failure = new FailureRecord(FAILURE, reason, tryId(1),
                ModelCallPurpose.PRODUCE, minutes(10), false, null, null, source)

        then:
        failure.source() == source
        failure.reason() == reason
        failure.source().maker() == MAKER
        failure.source().field() == 'receipt.note'
        failure.onTry() == tryId(1)
        failure.purpose() == ModelCallPurpose.PRODUCE
        !failure.ended()
        failure.term() == null
        failure.tool() == null

        where:
        reason << [RunStepFailureReason.SOURCE_NO_LONGER_MATCHES, RunStepFailureReason.SOURCE_LIST_NOT_HERE]
    }

    def "a source is named exactly on a source mismatch production and never on review or without a real try"() {
        given:
        FailureRecord failure = null
        def source = named ? new FailureRecord.SourceRecord(MAKER, 'receipt') : null

        when:
        failure = new FailureRecord(FAILURE, reason, onTry, purpose, minutes(10), false, null, null, source)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == message
        failure == null

        where:
        reason                                       | named | onTry    | purpose                 || message
        RunStepFailureReason.SOURCE_NO_LONGER_MATCHES | false | tryId(1) | ModelCallPurpose.PRODUCE || 'FailureRecord names a source exactly where it no longer matches'
        RunStepFailureReason.MODEL_NOT_DEPLOYED       | true  | tryId(1) | ModelCallPurpose.PRODUCE || 'FailureRecord names a source exactly where it no longer matches'
        RunStepFailureReason.SOURCE_NO_LONGER_MATCHES | true  | tryId(1) | ModelCallPurpose.REVIEW  || 'FailureRecord names a source only on a model production to be sent'
        RunStepFailureReason.SOURCE_NO_LONGER_MATCHES | true  | null     | null                    || 'FailureRecord names a source only on a model production to be sent'
        RunStepFailureReason.SOURCE_LIST_NOT_HERE     | false | tryId(1) | ModelCallPurpose.PRODUCE || 'FailureRecord names a source exactly where it no longer matches'
        RunStepFailureReason.SOURCE_LIST_NOT_HERE     | true  | tryId(1) | ModelCallPurpose.REVIEW  || 'FailureRecord names a source only on a model production to be sent'
        RunStepFailureReason.SOURCE_LIST_NOT_HERE     | true  | null     | null                    || 'FailureRecord names a source only on a model production to be sent'
    }

    def "a source mismatch field uses the existing declaration pointer guard"() {
        given:
        FailureRecord.SourceRecord source = null

        when:
        source = new FailureRecord.SourceRecord(MAKER, field)

        then:
        thrown(IllegalArgumentException)
        source == null

        where:
        field << ['', 'receipt.', '.receipt', 'receipt..note']
    }

    def "a failure on a try is taken saying what it was sending that try for, and one on none saying nothing of it"() {
        when:
        def failure = new FailureRecord(FAILURE, RunStepFailureReason.MODEL_NOT_DEPLOYED, onTry, purpose, minutes(10),
                false, null, null, null)

        then:
        failure.id() == FAILURE
        failure.onTry() == onTry
        failure.purpose() == purpose

        where:
        onTry     | purpose
        tryId(1)  | ModelCallPurpose.PRODUCE
        tryId(1)  | ModelCallPurpose.REVIEW
        null      | null
    }

    def "a search question mismatch is kept without a sending try before and after revalidation"() {
        when:
        def failure = new FailureRecord(FAILURE, RunStepFailureReason.SEARCH_QUESTION_NO_LONGER_MATCHES,
                null, null, minutes(10), ended, null, null, null)

        then:
        failure.id() == FAILURE
        failure.reason() == RunStepFailureReason.SEARCH_QUESTION_NO_LONGER_MATCHES
        failure.at() == minutes(10)
        failure.ended() == ended
        failure.onTry() == null
        failure.purpose() == null
        failure.term() == null
        failure.tool() == null
        failure.source() == null

        where:
        ended << [false, true]
    }

    def "a search question mismatch cannot name a sending try"() {
        given:
        FailureRecord failure = null

        when:
        failure = new FailureRecord(FAILURE, RunStepFailureReason.SEARCH_QUESTION_NO_LONGER_MATCHES,
                tryId(1), purpose, minutes(10), ended, null, null, null)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == 'A search question mismatch spends no try'
        failure == null

        where:
        [purpose, ended] << [[ModelCallPurpose.PRODUCE, ModelCallPurpose.REVIEW], [false, true]].combinations()
    }

    def "no ordinary failure can end without its sending try"() {
        given:
        FailureRecord failure = null

        when:
        failure = new FailureRecord(FAILURE, reason, null, null, minutes(10), true, null, null, null)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == 'Only a search question mismatch can end without a sending try'
        failure == null

        where:
        reason << RunStepFailureReason.values().findAll { it != RunStepFailureReason.SEARCH_QUESTION_NO_LONGER_MATCHES }
    }

    def "a no-try search mismatch cannot retain a sending purpose before or after revalidation"() {
        when:
        new FailureRecord(FAILURE, RunStepFailureReason.SEARCH_QUESTION_NO_LONGER_MATCHES,
                null, purpose, minutes(10), ended, null, null, null)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == 'FailureRecord names what it was sending for exactly where it was on a try'

        where:
        purpose                  | ended
        ModelCallPurpose.PRODUCE | false
        ModelCallPurpose.PRODUCE | true
        ModelCallPurpose.REVIEW  | false
        ModelCallPurpose.REVIEW  | true
    }

    def "a failure with no id is refused, and nothing is built"() {
        given:
        FailureRecord failure = null

        when:
        failure = new FailureRecord(null, RunStepFailureReason.MODEL_NOT_DEPLOYED, tryId(1), ModelCallPurpose.PRODUCE,
                minutes(10), false, null, null, null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "FailureRecord id must not be null"
        failure == null
    }

    def "a failure naming what it was sending for without its try, or its try without it, or sending to help, is refused"() {
        given:
        FailureRecord failure = null

        when:
        failure = new FailureRecord(FAILURE, RunStepFailureReason.MODEL_NOT_DEPLOYED, onTry, purpose, minutes(10), false, null, null, null)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == message
        failure == null

        where:
        onTry    | purpose                  || message
        tryId(1) | null                     || "FailureRecord names what it was sending for exactly where it was on a try"
        null     | ModelCallPurpose.PRODUCE || "FailureRecord names what it was sending for exactly where it was on a try"
        tryId(1) | ModelCallPurpose.HELP    || "FailureRecord was sending a try to be produced or to be reviewed"
    }

    def "a failure for a value no case claimed keeps that value, or none where nothing was given"() {
        when:
        def failure = new FailureRecord(FAILURE, RunStepFailureReason.UNCLAIMED_VALUE, null, null, minutes(10), false,
                term, null, null)

        then:
        failure.term() == term
        failure.reason() == RunStepFailureReason.UNCLAIMED_VALUE

        where:
        term << ["wait", null]
    }

    def "a tool no longer offered is kept with that failure"() {
        when:
        def failure = new FailureRecord(FAILURE, RunStepFailureReason.TOOL_NOT_OFFERED, tryId(1),
                ModelCallPurpose.PRODUCE, minutes(10), false, null, "web_search", null)

        then:
        failure.tool() == "web_search"
        failure.term() == null
        failure.onTry() == tryId(1)
        failure.purpose() == ModelCallPurpose.PRODUCE
    }

    def "a tool is named exactly where the model no longer offers it"() {
        given:
        FailureRecord failure = null

        when:
        failure = new FailureRecord(FAILURE, reason, tryId(1), ModelCallPurpose.PRODUCE,
                minutes(10), false, null, tool, null)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "FailureRecord names a tool exactly where the model no longer offers it"
        failure == null

        where:
        reason                                  | tool
        RunStepFailureReason.TOOL_NOT_OFFERED  | null
        RunStepFailureReason.MODEL_NOT_DEPLOYED | "web_search"
    }

    def "a failure for any other reason naming a term is refused"() {
        when:
        new FailureRecord(FAILURE, reason, null, null, minutes(10), false, "wait", reason == RunStepFailureReason.TOOL_NOT_OFFERED ? "web_search" : null, null)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "FailureRecord names a term only where no case claimed it"

        where:
        reason << RunStepFailureReason.values().findAll { it != RunStepFailureReason.UNCLAIMED_VALUE }
    }
}
