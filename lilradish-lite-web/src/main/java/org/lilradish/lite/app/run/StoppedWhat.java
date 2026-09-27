package org.lilradish.lite.app.run;

/**
 * What a stop holding a step back is on: the run's own workflow, which holds whatever the step runs, or only what the
 * step runs. The published spelling is written out, for the reason
 * {@link org.lilradish.lite.domain.identity.EstateAct} gives.
 */
public enum StoppedWhat {
    WORKFLOW("workflow"),
    ENTRY("entry");

    private final String published;

    StoppedWhat(String published) {
        this.published = published;
    }

    public String published() {
        return published;
    }
}
