package org.lilradish.lite.app.run;

import org.lilradish.lite.domain.run.TryLostReason;

/**
 * How a try ended, as a step's page publishes it: still open, lost as its {@link TryLostReason} says, or by how
 * the values it gave back stand. The published spelling is written out, for the reason
 * {@link org.lilradish.lite.domain.identity.EstateAct} gives.
 */
public enum TryEnded {
    OPEN("open"),
    STANDS("stands"),
    REFUSED_ON_REVIEW("refused_on_review"),
    REFUSED_FOR_LENGTH("refused_for_length"),
    WAITING("waiting"),
    DID_NOT_FIT("did_not_fit"),
    ERRORED("errored"),
    NOTHING_CAME_BACK("nothing_came_back");

    private final String published;

    TryEnded(String published) {
        this.published = published;
    }

    public String published() {
        return published;
    }

    static TryEnded of(TryLostReason lost) {
        return switch (lost) {
            case DID_NOT_FIT -> DID_NOT_FIT;
            case ERRORED -> ERRORED;
            case NOTHING_CAME_BACK -> NOTHING_CAME_BACK;
        };
    }
}
