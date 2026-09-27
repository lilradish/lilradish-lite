package org.lilradish.lite.app.run;

import org.lilradish.lite.domain.run.RunStepFailureReason;
import org.lilradish.lite.domain.run.StepFailure;

/**
 * Why a step failed, as a step's whereabouts publish it: its tries spent, which is worked out, beside each reason
 * written down, spelt as {@link RunStepFailureReason} publishes it. The worked-out spelling is written out, for the
 * reason {@link org.lilradish.lite.domain.identity.EstateAct} gives.
 */
public enum FailureReason {
    TRIES_SPENT("tries_spent"),
    UNCUTTABLE_LENGTH(RunStepFailureReason.UNCUTTABLE_LENGTH.published()),
    UNCLAIMED_VALUE(RunStepFailureReason.UNCLAIMED_VALUE.published()),
    MODEL_NOT_DEPLOYED(RunStepFailureReason.MODEL_NOT_DEPLOYED.published());

    private final String published;

    FailureReason(String published) {
        this.published = published;
    }

    public String published() {
        return published;
    }

    static FailureReason of(StepFailure why) {
        return switch (why) {
            case StepFailure.TriesSpent ignored -> TRIES_SPENT;
            case StepFailure.Recorded recorded ->
                switch (recorded.reason()) {
                    case UNCUTTABLE_LENGTH -> UNCUTTABLE_LENGTH;
                    case UNCLAIMED_VALUE -> UNCLAIMED_VALUE;
                    case MODEL_NOT_DEPLOYED -> MODEL_NOT_DEPLOYED;
                };
        };
    }
}
