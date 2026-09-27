package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.inference.ModelCallOutcome;
import org.lilradish.lite.domain.inference.ModelCallPurpose;

/**
 * How one try stands with the model its step names to review it, read from what was sent for that: nothing sent,
 * its call out, its call turned away for the last time, what it would be sent measured too long for it, or, of a
 * code step's try, what it would be sent not built, as a list the release pins is not here, or as the release no
 * longer declares what the try took or what it gave back. The published spelling is written out, for the reason
 * {@link org.lilradish.lite.domain.identity.EstateAct} gives.
 */
public enum ReviewSending {
    UNSENT("unsent"),
    OUT("out"),
    TURNED_AWAY("turned_away"),
    TOO_LONG("too_long"),
    LIST_NOT_HERE("list_not_here"),
    TAKES_NO_LONGER_DECLARED("takes_no_longer_declared"),
    NO_LONGER_DECLARED("no_longer_declared");

    private final String published;

    ReviewSending(String published) {
        this.published = published;
    }

    public String published() {
        return published;
    }

    /** Whether nothing is ever sent for the try, so that a person reviews in the model's place. */
    public boolean toPerson() {
        return this == TOO_LONG
                || this == LIST_NOT_HERE
                || this == TAKES_NO_LONGER_DECLARED
                || this == NO_LONGER_DECLARED;
    }

    /**
     * An attempt nothing was sent for, of which a try has one at most, decides it whatever else was sent, by why
     * nothing was; otherwise the newest attempt's call does. A call that ended otherwise left a review, so nothing
     * of the try waits on one.
     */
    public static ReviewSending of(TryRecord aTry) {
        requireNonNull(aTry, "ReviewSending try must not be null");
        for (AttemptRecord attempt : aTry.attempts()) {
            if (attempt.purpose() == ModelCallPurpose.REVIEW && attempt.tooLong()) {
                return switch (attempt.unbuilt()) {
                    case null -> TOO_LONG;
                    case LIST_NOT_HERE -> LIST_NOT_HERE;
                    case TAKES_NO_LONGER_DECLARED -> TAKES_NO_LONGER_DECLARED;
                    case NO_LONGER_DECLARED -> NO_LONGER_DECLARED;
                };
            }
        }
        CallRecord call = newest(aTry);
        if (call == null) {
            return UNSENT;
        }
        if (call.outcome() == null) {
            return OUT;
        }
        return call.outcome() == ModelCallOutcome.TURNED_AWAY ? TURNED_AWAY : UNSENT;
    }

    /** The call the newest attempt to review {@code aTry} sent; none where no attempt to review it was sent. */
    public static Optional<CallRecord> newestCall(TryRecord aTry) {
        requireNonNull(aTry, "ReviewSending try must not be null");
        return Optional.ofNullable(newest(aTry));
    }

    /* An attempt sent and its call are written together, so one sent with no call was written otherwise. */
    private static @Nullable CallRecord newest(TryRecord aTry) {
        AttemptRecord newest = null;
        for (AttemptRecord attempt : aTry.attempts()) {
            if (attempt.purpose() == ModelCallPurpose.REVIEW && !attempt.tooLong()) {
                newest = attempt;
            }
        }
        if (newest == null) {
            return null;
        }
        for (CallRecord call : aTry.calls()) {
            if (call.attempt().equals(newest.id())) {
                return call;
            }
        }
        throw new IllegalStateException("Try " + aTry.id().value() + " was sent to be reviewed with no call made");
    }
}
