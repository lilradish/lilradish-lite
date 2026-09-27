package org.lilradish.lite.app.run;

/**
 * Where an input of a step comes from, as a step's page publishes it: what the run was started with, what an
 * earlier step gave back, or a value written into the workflow. The published spelling is written out, for the
 * reason {@link org.lilradish.lite.domain.identity.EstateAct} gives.
 */
public enum FromKind {
    RUN_INPUT("run_input"),
    STEP("step"),
    CONSTANT("constant");

    private final String published;

    FromKind(String published) {
        this.published = published;
    }

    public String published() {
        return published;
    }
}
