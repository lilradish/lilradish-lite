package com.lilradish.lite.domain.run;

/**
 * STOPPED comes from the stop in force; all other states are derived from steps and stored nowhere.
 * Published spellings are explicit for the reason {@link com.lilradish.lite.domain.identity.EstateAct} gives.
 */
public enum RunState {
    RUNNING("running"),
    STOPPED("stopped"),
    FAILED("failed"),
    DONE("done");

    private final String published;

    RunState(String published) {
        this.published = published;
    }

    public String published() {
        return published;
    }
}
