package org.lilradish.lite.domain.run;

/**
 * Whom a step waits on: whoever may review at a gate, the model its step names, whoever may answer a step, or
 * whoever started the run. The published spelling is written out, for the reason
 * {@link org.lilradish.lite.domain.identity.EstateAct} gives.
 */
public enum WaitsOn {
    REVIEW_AT_GATE("review_at_gate"),
    MODEL("model"),
    ANSWER_STEP("answer_step"),
    STARTER("starter");

    private final String published;

    WaitsOn(String published) {
        this.published = published;
    }

    public String published() {
        return published;
    }
}
