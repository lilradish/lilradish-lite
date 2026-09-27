package org.lilradish.lite.domain.run;

/**
 * Where a run is, exactly one of these: stopped is read from the stop in force, everything else is worked out
 * from its steps and stored nowhere. The published spelling is written out, for the reason
 * {@link org.lilradish.lite.domain.identity.EstateAct} gives.
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
