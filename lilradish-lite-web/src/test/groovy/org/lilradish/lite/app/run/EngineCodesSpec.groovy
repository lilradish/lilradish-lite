package org.lilradish.lite.app.run

import static org.lilradish.lite.domain.run.fixture.Runs.GROUP
import static org.lilradish.lite.domain.run.fixture.Runs.RUN
import static org.lilradish.lite.domain.run.fixture.Runs.STARTED
import static org.lilradish.lite.domain.run.fixture.Runs.codeStep
import static org.lilradish.lite.domain.run.fixture.Runs.lost
import static org.lilradish.lite.domain.run.fixture.Runs.open
import static org.lilradish.lite.domain.run.fixture.Runs.person
import static org.lilradish.lite.domain.run.fixture.Runs.question
import static org.lilradish.lite.domain.run.fixture.Runs.run
import static org.lilradish.lite.domain.run.fixture.Runs.started
import static org.lilradish.lite.domain.run.fixture.Runs.stop
import static org.lilradish.lite.domain.run.fixture.Runs.tidy
import static org.lilradish.lite.domain.run.fixture.Runs.unstarted

import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.workflow.StepProducer
import spock.lang.Specification

/**
 * Whether a code step's try handed over is still the one to make once its tree is held, worked out from the run
 * alone: nothing but that decides whether the engine's thread writes it and runs the code.
 */
class EngineCodesSpec extends Specification {

    static final UserId ASKER = new UserId("000c01")

    def "a try the run makes by itself is still due only where the run's own next act is that very try"() {
        given:
        def running = run([step], stopped, workflowStopped ? stop(STARTED) : null)

        expect:
        EngineCodes.due(running, running.steps()[0], pending(number, null)) == due

        where:
        step                                                                  | number | stopped | workflowStopped || due
        unstarted(codeStep(1, 3, tidy(true)))                                 | 1      | false   | false           || true
        unstarted(codeStep(1, 3, tidy(true)))                                 | 2      | false   | false           || false
        started(codeStep(1, 3, tidy(true)), [lost(1, StepProducer.CODE)])    | 2      | false   | false           || true
        started(codeStep(1, 3, tidy(true)), [lost(1, StepProducer.CODE)])    | 1      | false   | false           || false
        started(codeStep(1, 3, tidy(false)), [lost(1, StepProducer.CODE)])   | 2      | false   | false           || false
        started(codeStep(1, 3, tidy(true)), [open(1, StepProducer.CODE)])    | 1      | false   | false           || false
        unstarted(codeStep(1, 3, tidy(true)))                                 | 1      | true    | false           || false
        unstarted(codeStep(1, 3, tidy(true)))                                 | 1      | false   | true            || false
        unstarted(codeStep(1, 3, null))                                       | 1      | false   | false           || false
    }

    /** Judged as pressing it would be judged now, save who may: that was settled when it was pressed. */
    def "a try a person asked for is still due only where it is still the try they may ask for, and asking is not refused"() {
        given:
        def running = run([step], stopped, workflowStopped ? stop(STARTED) : null)

        expect:
        EngineCodes.due(running, running.steps()[0], pending(number, ASKER)) == due

        where:
        step                                                                  | number | stopped | workflowStopped || due
        started(codeStep(1, 1, tidy(true)), [lost(1, StepProducer.CODE)])    | 2      | false   | false           || true
        started(codeStep(1, 1, tidy(true)), [lost(1, StepProducer.CODE)])    | 3      | false   | false           || false
        started(codeStep(1, 2, tidy(false)), [lost(1, StepProducer.CODE)])   | 2      | false   | false           || false
        started(codeStep(1, 1, tidy(true)), [lost(1, StepProducer.CODE)])    | 2      | true    | false           || false
        started(codeStep(1, 1, tidy(true)), [lost(1, StepProducer.CODE)])    | 2      | false   | true            || false
        started(codeStep(1, 2, tidy(true)), [open(1, StepProducer.CODE)])    | 2      | false   | false           || false
        started(codeStep(1, 1, null), [lost(1, StepProducer.CODE)])          | 2      | false   | false           || false
    }

    def "a step that runs no code of its own is never one a code step's try was handed over for"() {
        given:
        def running = run([step])

        when:
        EngineCodes.due(running, running.steps()[0], pending(1, asker))

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "Step step_1 runs no code of its own"

        where:
        [step, asker] << [[unstarted(question(1, person(), 1)), unstarted(codeStep(1, 1, tidy(true), person()))],
                          [null, ASKER]].combinations()
    }

    private static PendingCode pending(int number, UserId asker) {
        new PendingCode(GROUP, RUN, RUN, codeStep(1, 1).id(), number, asker)
    }
}
