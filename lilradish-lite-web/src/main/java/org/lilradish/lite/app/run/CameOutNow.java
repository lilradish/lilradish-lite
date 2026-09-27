package org.lilradish.lite.app.run;

import org.lilradish.lite.domain.run.ValueStanding;

/**
 * Where each field a step gives back stands in its newest try: as a value of that try stands, or none where that
 * try gave nothing back for it. The published spelling is written out, for the reason
 * {@link org.lilradish.lite.domain.identity.EstateAct} gives.
 */
public enum CameOutNow {
    STANDS("stands"),
    WAITING_ON_REVIEW("waiting_on_review"),
    REFUSED("refused"),
    REFUSED_FOR_LENGTH("refused_for_length"),
    NONE("none");

    private final String published;

    CameOutNow(String published) {
        this.published = published;
    }

    public String published() {
        return published;
    }

    static CameOutNow of(ValueStanding standing) {
        return switch (standing) {
            case STANDS -> STANDS;
            case WAITING_ON_REVIEW -> WAITING_ON_REVIEW;
            case REFUSED -> REFUSED;
            case REFUSED_FOR_LENGTH -> REFUSED_FOR_LENGTH;
        };
    }
}
