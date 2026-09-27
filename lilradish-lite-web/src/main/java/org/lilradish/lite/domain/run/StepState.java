package org.lilradish.lite.domain.run;

/**
 * Where a step is, worked out from what its run holds and read from no column of its own; exactly one of these.
 * The published spelling is written out, for the reason {@link org.lilradish.lite.domain.identity.EstateAct} gives.
 */
public enum StepState {
    NOT_STARTED("not_started"),
    RUNNING("running"),
    HELD_BACK("held_back"),
    WAITING("waiting"),
    FAILED("failed"),
    DONE("done");

    private final String published;

    StepState(String published) {
        this.published = published;
    }

    public String published() {
        return published;
    }
}
