package org.lilradish.lite.domain.run;

/**
 * Why a step failed, where the failure is written down rather than worked out from its tries.
 *
 * <p>The store declares the same vocabulary, spelt as these constants and in their order. The published
 * spelling is written out rather than folded from the constant name, for the reason
 * {@link org.lilradish.lite.domain.identity.EstateAct} gives.
 */
public enum RunStepFailureReason {
    UNCUTTABLE_LENGTH("uncuttable_length"),
    UNCLAIMED_VALUE("unclaimed_value"),
    MODEL_NOT_DEPLOYED("model_not_deployed");

    private final String published;

    RunStepFailureReason(String published) {
        this.published = published;
    }

    public String published() {
        return published;
    }
}
