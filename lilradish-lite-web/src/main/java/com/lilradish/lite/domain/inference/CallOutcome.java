package com.lilradish.lite.domain.inference;

import com.lilradish.lite.domain.model.CameBackMeasure;
import com.lilradish.lite.domain.model.DeployedModel;
import com.lilradish.lite.domain.model.SentText;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.log.annotation.DoNotLog;

/**
 * How one call to a model ended. A call that is never heard from again is not one of these: nothing is
 * left to return it.
 */
public sealed interface CallOutcome {

    /**
     * The model returned a completion, even if it declined, with every count in the model's own units.
     * Provider counts are used only as a valid pair; otherwise both are measured before this outcome is built.
     * The answer has not been checked against what the store can hold, and may not be storable.
     *
     * @param answer as it came back, and possibly empty
     * @param countedByModel false where both counts are this system's measure rather than the provider's
     * @param cutOff the model stopped at the most it may give back
     * @param declinedWords what it said in declining, where it did; separate from its answer
     */
    record CameBack(
            @DoNotLog String answer,
            long sentCount,
            long cameBackCount,
            boolean countedByModel,
            boolean cutOff,
            @Nullable DidNotFitReason didNotFitReason,
            @DoNotLog @Nullable String declinedWords)
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
            if (didNotFitReason != null && !didNotFitReason.mayEndCallBeforeUnwrapping()) {
                throw new IllegalArgumentException("CameBack has no such provider outcome");
            }
            if (declinedWords != null && didNotFitReason != DidNotFitReason.MODEL_DECLINED) {
                throw new IllegalArgumentException("CameBack has decline words only with model decline");
            }
        }

        public static CameBack measuredHere(
                DeployedModel model,
                SentText sent,
                String answer,
                boolean cutOff,
                @Nullable DidNotFitReason didNotFitReason,
                @Nullable String declinedWords,
                long generatedToolCallCharacters) {
            if (generatedToolCallCharacters < 0) {
                throw new IllegalArgumentException("Generated tool-call characters must not be negative");
            }
            long cameBackCharacters = Math.addExact(
                    CameBackMeasure.characters(answer),
                    declinedWords == null ? 0 : CameBackMeasure.characters(declinedWords));
            return new CameBack(
                    answer,
                    model.unitsOf(sent.characters()),
                    model.unitsOf(Math.addExact(cameBackCharacters, generatedToolCallCharacters)),
                    false,
                    cutOff,
                    didNotFitReason,
                    declinedWords);
        }
    }

    /**
     * The call went wrong once taken up, or was not answered in time.
     *
     * @param detail what went wrong, as the other side put it
     */
    record Errored(
            @DoNotLog String detail, @DoNotLog @Nullable UnreadableResponseRecord unreadableResponse)
            implements CallOutcome {

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
