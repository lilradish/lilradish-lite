package org.lilradish.lite.app.run

import org.lilradish.lite.domain.identity.GroupId
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.inference.ModelCallPurpose
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.domain.run.RunStepFailureId
import org.lilradish.lite.domain.run.RunStepSendAttemptId
import org.lilradish.lite.domain.run.WorkflowStepId
import spock.lang.Specification

class PendingSendSpec extends Specification {

    static final GroupId GROUP = new GroupId(UUID.fromString("00000003-0000-4000-8000-000000000f01"))

    static final RunId RUN = new RunId(UUID.fromString("00000008-0000-4000-8000-000000000f01"))

    static final WorkflowStepId STEP = new WorkflowStepId(UUID.fromString("00000009-0000-4000-8000-000000000f01"))

    static final UserId PRESSER = new UserId("000f01")

    static final RunStepSendAttemptId ATTEMPT_ON =
            new RunStepSendAttemptId(UUID.fromString("00000011-0000-4000-8000-000000000f01"))

    static final RunStepFailureId FAILED_ON =
            new RunStepFailureId(UUID.fromString("00000012-0000-4000-8000-000000000f01"))

    /**
     * A press on values the reviewer turned away names the attempt turned away as a press on a hold names the hold's,
     * and one on a failure the failure, whichever of producing or reviewing it was for.
     */
    def "a send the run makes by itself names nothing pressed on, and a pressed one names exactly the attempt or failure pressed"() {
        when:
        def send = new PendingSend(GROUP, RUN, RUN, STEP, 2, purpose, presser, attemptOn, failedOn)

        then:
        send.group() == GROUP
        send.root() == RUN
        send.run() == RUN
        send.step() == STEP
        send.number() == 2
        send.purpose() == purpose
        send.presser() == presser
        send.attemptOn() == attemptOn
        send.failedOn() == failedOn

        where:
        [purpose, pressed] << [[ModelCallPurpose.PRODUCE, ModelCallPurpose.REVIEW],
                               [[null, null, null], [PRESSER, ATTEMPT_ON, null], [PRESSER, null, FAILED_ON]]].combinations()
        presser = pressed[0]
        attemptOn = pressed[1]
        failedOn = pressed[2]
    }

    def "a send naming what was pressed on where nobody pressed, or not exactly one of an attempt and a failure where somebody did, is refused"() {
        given:
        PendingSend send = null

        when:
        send = new PendingSend(GROUP, RUN, RUN, STEP, 2, purpose, presser, attemptOn, failedOn)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "PendingSend names the one attempt or failure pressed on exactly where it was pressed"
        send == null

        where:
        [purpose, pressed] << [[ModelCallPurpose.PRODUCE, ModelCallPurpose.REVIEW],
                               [[null, ATTEMPT_ON, null], [null, null, FAILED_ON], [null, ATTEMPT_ON, FAILED_ON],
                                [PRESSER, ATTEMPT_ON, FAILED_ON], [PRESSER, null, null]]].combinations()
        presser = pressed[0]
        attemptOn = pressed[1]
        failedOn = pressed[2]
    }

    /** Only the try is ever sent, to be produced or reviewed; a run's step is never sent to its helper. */
    def "a send for help is refused, whoever pressed it, and nothing is made"() {
        given:
        PendingSend send = null

        when:
        send = new PendingSend(GROUP, RUN, RUN, STEP, 2, ModelCallPurpose.HELP, presser, attemptOn, null)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "PendingSend sends a try to be produced or reviewed"
        send == null

        where:
        presser | attemptOn
        null    | null
        PRESSER | ATTEMPT_ON
    }
}
