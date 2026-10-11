package com.lilradish.lite.domain.run;

/** The published spelling is written out, for the reason {@link com.lilradish.lite.domain.identity.EstateAct} gives. */
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
