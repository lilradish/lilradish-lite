package org.lilradish.lite.domain.run;

/**
 * Why a step is held back before anything was produced.
 *
 * <p>The store declares the same vocabulary, spelt as these constants and in their order. The published
 * spelling is written out rather than folded from the constant name, for the reason
 * {@link org.lilradish.lite.domain.identity.EstateAct} gives.
 */
public enum RunStepHoldReason {
    ENTRY_STOPPED("entry_stopped"),
    TOO_LONG("too_long"),
    TURNED_AWAY("turned_away"),
    /** The running release holds no code step of the name the step runs, so neither code nor a person can make it. */
    CODE_STEP_NOT_HELD("code_step_not_held");

    private final String published;

    RunStepHoldReason(String published) {
        this.published = published;
    }

    public String published() {
        return published;
    }
}
