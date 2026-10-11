package com.lilradish.lite.domain.run

import static com.lilradish.lite.domain.run.RunStepHoldReason.CODE_STEP_NOT_HELD
import static com.lilradish.lite.domain.run.RunStepHoldReason.ENTRY_STOPPED
import static com.lilradish.lite.domain.run.RunStepHoldReason.TOO_LONG
import static com.lilradish.lite.domain.run.RunStepHoldReason.TURNED_AWAY
import static com.lilradish.lite.domain.run.fixture.Runs.attemptId
import static com.lilradish.lite.domain.run.fixture.Runs.minutes

import spock.lang.Specification

class HoldRecordSpec extends Specification {

    def "a hold names the attempt it is held for, and is spent up only where that attempt's call was turned away"() {
        when:
        new HoldRecord(reason, minutes(10), named ? attemptId(1) : null, spentUp, null)

        then:
        noExceptionThrown()

        where:
        reason        | named | spentUp
        ENTRY_STOPPED | false | false
        TOO_LONG      | true  | false
        TURNED_AWAY   | true  | false
        TURNED_AWAY   | true  | true
    }

    def "a hold is refused naming an attempt where it is held for none, or naming none where it is held for one"() {
        when:
        new HoldRecord(reason, minutes(10), named ? attemptId(1) : null, false, null)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "HoldRecord names an attempt exactly where it is held for one"

        where:
        reason        | named
        ENTRY_STOPPED | true
        TOO_LONG      | false
        TURNED_AWAY   | false
    }

    def "a hold is refused as spent up where no call of its was turned away"() {
        when:
        new HoldRecord(reason, minutes(10), reason == ENTRY_STOPPED ? null : attemptId(1), true, null)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "HoldRecord is spent up only where its call was turned away"

        where:
        reason << [ENTRY_STOPPED, TOO_LONG]
    }

    def "an absent code step hold keeps the actual input maker without an attempt"() {
        given:
        def source = new HoldRecord.SourceRecord(new RunId(UUID.randomUUID()), new RunStepId(UUID.randomUUID()), UUID.randomUUID())

        when:
        def hold = new HoldRecord(CODE_STEP_NOT_HELD, minutes(10), null, false, source)

        then:
        hold.source() == source
        hold.attempt() == null
        !hold.spentUp()
        hold.reason() == CODE_STEP_NOT_HELD
    }

    def "a hold for another reason cannot name a source code step"() {
        given:
        def source = new HoldRecord.SourceRecord(new RunId(UUID.randomUUID()), new RunStepId(UUID.randomUUID()), UUID.randomUUID())

        when:
        new HoldRecord(reason, minutes(10), reason in [TOO_LONG, TURNED_AWAY] ? attemptId(1) : null, false, source)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == 'HoldRecord names a source only where its code step is not held'

        where:
        reason << RunStepHoldReason.values().findAll { it != CODE_STEP_NOT_HELD }
    }

    def "an input maker names its run its run step and its workflow step"() {
        given:
        def run = new RunId(UUID.randomUUID())
        def runStep = new RunStepId(UUID.randomUUID())
        def step = UUID.randomUUID()

        when:
        new HoldRecord.SourceRecord(missing == 'run' ? null : run,
                missing == 'runStep' ? null : runStep, missing == 'step' ? null : step)

        then:
        def refused = thrown(NullPointerException)
        refused.message == 'HoldRecord.SourceRecord ' + missing + ' must not be null'

        where:
        missing << ['run', 'runStep', 'step']
    }
}
