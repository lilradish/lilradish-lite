package org.lilradish.lite.domain.run;

/**
 * What a running step has out: its code running, a call to a model, or a next try the system makes by itself.
 * The published spelling is written out, for the reason {@link org.lilradish.lite.domain.identity.EstateAct} gives.
 */
public enum RunningOn {
    CODE("code"),
    CALL("call"),
    NEXT_TRY("next_try");

    private final String published;

    RunningOn(String published) {
        this.published = published;
    }

    public String published() {
        return published;
    }
}
