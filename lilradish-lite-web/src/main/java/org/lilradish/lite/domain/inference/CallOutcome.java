package org.lilradish.lite.domain.inference;

import org.libprunus.core.log.annotation.DoNotLog;
import org.lilradish.lite.domain.model.CameBackMeasure;
import org.lilradish.lite.domain.model.DeployedModel;
import org.lilradish.lite.domain.model.SentText;

/**
 * How one call to a model ended. A call that is never heard from again is not one of these: nothing is
 * left to return it.
 */
public sealed interface CallOutcome {

    /**
     * The model answered, every count in the model's own units. A sent count below one, a negative
     * came-back count, or only one of the two, is taken as none given, and both are measured here.
     * The answer has not been checked against what the store can hold, and may not be storable.
     *
     * @param answer as it came back, and possibly empty
     * @param countedByModel false where the model said nothing of what it counted, both counts then
     *     being this system's measure
     * @param cutOff the model stopped at the most it may give back
     */
    record CameBack(@DoNotLog String answer, long sentCount, long cameBackCount, boolean countedByModel, boolean cutOff)
            implements CallOutcome {

        public CameBack {
            if (answer == null) {
                throw new NullPointerException("CameBack answer must not be null");
            }
            if (sentCount < 1) {
                throw new IllegalArgumentException("CameBack sent count must be at least one: " + sentCount);
            }
            if (cameBackCount < 0) {
                throw new IllegalArgumentException("CameBack came-back count must not be negative: " + cameBackCount);
            }
        }

        /** Both counts this system's own measure, of what was sent and of what came back. */
        public static CameBack measuredHere(DeployedModel model, SentText sent, String answer, boolean cutOff) {
            return new CameBack(
                    answer,
                    model.unitsOf(sent.characters()),
                    model.unitsOf(CameBackMeasure.characters(answer)),
                    false,
                    cutOff);
        }
    }

    /**
     * The call went wrong once taken up, or was not answered in time.
     *
     * @param detail what went wrong, as the other side put it
     */
    record Errored(@DoNotLog String detail) implements CallOutcome {

        public Errored {
            if (detail == null) {
                throw new NullPointerException("Errored detail must not be null");
            }
            if (detail.isEmpty()) {
                throw new IllegalArgumentException("Errored must say what went wrong");
            }
        }
    }

    /**
     * Never taken up.
     *
     * @param last the turnaway that ended the call
     */
    record TurnedAway(TurnAway last) implements CallOutcome {

        public TurnedAway {
            if (last == null) {
                throw new NullPointerException("TurnedAway last must not be null");
            }
        }
    }

    /** Ended after a turnaway already passed on as progress, with nothing sent after it. */
    record NotResent() implements CallOutcome {}
}
