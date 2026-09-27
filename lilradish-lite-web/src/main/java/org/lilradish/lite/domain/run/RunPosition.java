package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

/**
 * Where a run stands as far as what may be done to it turns on.
 *
 * @param done whether every step of it is done, which leaves nothing to stop
 * @param raise the raise waiting on its ceiling that the act in question names, as the reader stands to it
 */
public record RunPosition(boolean atTop, boolean stopped, boolean done, Raise raise) {

    public RunPosition {
        requireNonNull(raise, "RunPosition raise must not be null");
    }

    /** A raise waiting on approval, as the one reading stands to it. */
    public enum Raise {
        NONE_WAITING,
        ASKED_BY_READER,
        ASKED_BY_ANOTHER
    }
}
