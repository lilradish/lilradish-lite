package org.lilradish.lite.app.run;

/**
 * What a step's inputs are read from: what its newest try took, or what it would take now where it has sent none.
 * The published spelling is written out, for the reason {@link org.lilradish.lite.domain.identity.EstateAct} gives.
 */
public enum WentInFrom {
    TRY("try"),
    NOT_YET_SENT("not_yet_sent");

    private final String published;

    WentInFrom(String published) {
        this.published = published;
    }

    public String published() {
        return published;
    }
}
