package org.lilradish.lite.domain.run

import static org.lilradish.lite.domain.run.fixture.Runs.hold
import static org.lilradish.lite.domain.run.fixture.Runs.key
import static org.lilradish.lite.domain.run.fixture.Runs.minutes
import static org.lilradish.lite.domain.run.fixture.Runs.person
import static org.lilradish.lite.domain.run.fixture.Runs.question
import static org.lilradish.lite.domain.run.fixture.Runs.started
import static org.lilradish.lite.domain.run.fixture.Runs.stop
import static org.lilradish.lite.domain.run.fixture.Runs.unstarted
import static org.lilradish.lite.domain.run.fixture.Runs.yielded
import static org.lilradish.lite.domain.workflow.StepProducer.PERSON

import spock.lang.Specification

class StepSnapshotSpec extends Specification {

    static final PlannedStep ASKED = question(1, person(), 3)

    def "a step's tries are refused numbered other than one after another from one"() {
        when:
        started(ASKED, numbers.collect { yielded(it, PERSON, []) })

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "StepSnapshot holds tries numbered other than one after another"

        where:
        numbers << [[2], [1, 3], [2, 1], [1, 1]]
    }

    def "a step the run holds no row of is refused where it holds a hold, a failure or a try"() {
        when:
        new StepSnapshot(ASKED, null, null,
                row == "a hold" ? hold(RunStepHoldReason.TOO_LONG, minutes(10)) : null,
                row == "a failure" ? [new FailureRecord(new RunStepFailureId(key(1101)), RunStepFailureReason.UNCLAIMED_VALUE,
                        null, null, minutes(10), false)] : [],
                row == "a try" ? [yielded(1, PERSON, [])] : [])

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "StepSnapshot holds rows of a step the run holds no row of"

        where:
        row << ["a hold", "a failure", "a try"]
    }

    def "a step's newest try is its last, and it has none while it holds none"() {
        given:
        def tries = (1..<held + 1).collect { yielded(it, PERSON, []) }

        expect:
        started(ASKED, tries).newest() == (held == 0 ? Optional.empty() : Optional.of(tries.last()))

        where:
        held << [0, 1, 3]
    }

    def "a step is on as many tries as it holds, none before its first"() {
        expect:
        started(ASKED, (1..<held + 1).collect { yielded(it, PERSON, []) }).used() == held

        where:
        held << [0, 1, 3]
    }

    def "a step the run holds no row of has no newest try and is on none"() {
        given:
        def step = unstarted(ASKED, stop(minutes(5)))

        expect:
        step.newest().isEmpty()
        step.used() == 0
    }
}
