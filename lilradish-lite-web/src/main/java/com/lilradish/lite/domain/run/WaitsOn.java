package com.lilradish.lite.domain.run;

/** The published spelling is written out, for the reason {@link com.lilradish.lite.domain.identity.EstateAct} gives. */
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
