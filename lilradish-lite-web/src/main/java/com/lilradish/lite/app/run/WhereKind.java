package com.lilradish.lite.app.run;

/**
 * Published step whereabouts use explicit spellings, for the reason
 * {@link com.lilradish.lite.domain.identity.EstateAct} gives.
 */
public enum WhereKind {
    RUNNING("running"),
    HELD_BACK("held_back"),
    WAITING_ON_REVIEW("waiting_on_review"),
    OWED_TRY("owed_try"),
    FAILED("failed");

    private final String published;

    WhereKind(String published) {
        this.published = published;
    }

    public String published() {
        return published;
    }
}
