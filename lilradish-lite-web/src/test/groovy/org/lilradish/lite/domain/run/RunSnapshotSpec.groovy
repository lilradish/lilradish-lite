package org.lilradish.lite.domain.run

import static org.lilradish.lite.domain.run.fixture.Runs.GROUP
import static org.lilradish.lite.domain.run.fixture.Runs.PERSON
import static org.lilradish.lite.domain.run.fixture.Runs.RUN
import static org.lilradish.lite.domain.run.fixture.Runs.VERSION
import static org.lilradish.lite.domain.run.fixture.Runs.key
import static org.lilradish.lite.domain.run.fixture.Runs.minutes
import static org.lilradish.lite.domain.run.fixture.Runs.person
import static org.lilradish.lite.domain.run.fixture.Runs.question
import static org.lilradish.lite.domain.run.fixture.Runs.run
import static org.lilradish.lite.domain.run.fixture.Runs.stepId
import static org.lilradish.lite.domain.run.fixture.Runs.stop
import static org.lilradish.lite.domain.run.fixture.Runs.unstarted
import static org.lilradish.lite.domain.run.fixture.Runs.workflowOf

import spock.lang.Specification

class RunSnapshotSpec extends Specification {

    static final PlannedStep FIRST = question(1, person(), 1)

    static final PlannedStep SECOND = question(2, person(), 1)

    def "a run is refused where it holds other than one step per step of its version"() {
        when:
        snapshot([FIRST, SECOND], held == "one fewer" ? [unstarted(FIRST)]
                : [unstarted(FIRST), unstarted(SECOND), unstarted(SECOND)])

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "RunSnapshot holds a snapshot of other than every step"

        where:
        held << ["one fewer", "one more"]
    }

    def "a run is refused where its steps are out of their version's order"() {
        when:
        snapshot([FIRST, SECOND], [unstarted(SECOND), unstarted(FIRST)])

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "RunSnapshot holds its steps out of their version's order"
    }

    /** A step is its version's by the key it has there, so one read twice is the same step. */
    def "a run holds a step built apart from its version's own, the key being the same"() {
        given:
        def apart = question(1, person(), 1)

        when:
        def read = snapshot([FIRST], [unstarted(apart)])

        then:
        !apart.is(FIRST)
        read.steps()*.planned() == [apart]
    }

    def "a step of a run is found by its version's key, and none is for a key not among them"() {
        given:
        def running = run([unstarted(FIRST), unstarted(SECOND)])

        expect:
        running.step(stepId(2)) == Optional.of(running.steps()[1])
        running.step(new WorkflowStepId(key(999))) == Optional.empty()
    }

    /** The workflow's stop holds whatever the step runs, so it is the one named wherever both are in force. */
    def "a new try is held by the stop on the workflow where there is one, and otherwise by the one on what the step runs"() {
        given:
        def pinnedStop = pinned == null ? null : stop(minutes(pinned))
        def workflowStop = workflow == null ? null : new StopRecord(PERSON, minutes(workflow))
        def step = unstarted(FIRST, pinnedStop)

        when:
        def holding = run([step], false, workflowStop).stoppedFor(step)

        then:
        holding == (held == "the workflow's" ? workflowStop : (held == "the entry's" ? pinnedStop : null))

        where:
        workflow | pinned || held
        null     | null   || "none"
        10       | null   || "the workflow's"
        null     | 20     || "the entry's"
        10       | 20     || "the workflow's"
        30       | 20     || "the workflow's"
    }

    private static RunSnapshot snapshot(List<PlannedStep> planned, List<StepSnapshot> steps) {
        new RunSnapshot(RUN, RUN, GROUP, VERSION, false, null, null, workflowOf(planned), steps)
    }
}
