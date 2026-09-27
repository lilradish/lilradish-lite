package org.lilradish.lite.app.run;

/**
 * Why a step is where it is, as a step's whereabouts are published: running, held back, waiting on a review, owed a
 * try, or failed. The published spelling is written out, for the reason
 * {@link org.lilradish.lite.domain.identity.EstateAct} gives.
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
