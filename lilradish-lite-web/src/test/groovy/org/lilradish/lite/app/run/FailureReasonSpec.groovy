package org.lilradish.lite.app.run

import org.lilradish.lite.domain.inference.ModelCallPurpose
import org.lilradish.lite.domain.run.RunStepFailureReason
import org.lilradish.lite.domain.run.StepFailure
import spock.lang.Specification

class FailureReasonSpec extends Specification {

    /** Published as the same reason whatever was being sent, producing or reviewing. */
    def "a failure is published as its tries spent where worked out, and as the reason written down otherwise"() {
        when:
        def reason = FailureReason.of(why)

        then:
        reason == published
        reason.published() == spelt

        where:
        why                                                                                         || published                        | spelt
        new StepFailure.TriesSpent(2, 2)                                                            || FailureReason.TRIES_SPENT        | "tries_spent"
        new StepFailure.Recorded(RunStepFailureReason.UNCUTTABLE_LENGTH, ModelCallPurpose.PRODUCE)  || FailureReason.UNCUTTABLE_LENGTH  | "uncuttable_length"
        new StepFailure.Recorded(RunStepFailureReason.UNCLAIMED_VALUE, null)                        || FailureReason.UNCLAIMED_VALUE    | "unclaimed_value"
        new StepFailure.Recorded(RunStepFailureReason.MODEL_NOT_DEPLOYED, ModelCallPurpose.PRODUCE) || FailureReason.MODEL_NOT_DEPLOYED | "model_not_deployed"
        new StepFailure.Recorded(RunStepFailureReason.MODEL_NOT_DEPLOYED, ModelCallPurpose.REVIEW)  || FailureReason.MODEL_NOT_DEPLOYED | "model_not_deployed"
    }

    /** One spelling per reason: a failure written down is published as the store's reason publishes itself. */
    def "every reason written down is published under the spelling it publishes itself, beside tries spent alone"() {
        expect:
        FailureReason.values()*.published() ==
                ["tries_spent"] + RunStepFailureReason.values()*.published()
    }
}
