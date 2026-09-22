package org.lilradish.lite.domain.run;

/**
 * Who passed an attempt. An automated reviewer may block or annotate, but its approval never counts
 * as the human approval a change requires — so the kind is recorded beside the approver rather than
 * inferred from the shape of a name.
 */
public enum ApprovalKind {
    HUMAN,

    MODEL,

    /**
     * A person did not pass what the model produced — they replaced it, so the attempt carrying the
     * value has no model call behind it at all.
     */
    OVERRIDE;

    /** Exhaustive, so a fourth kind cannot be added without deciding which side of this it falls on. */
    public boolean isPersonsDecision() {
        return switch (this) {
            case HUMAN, OVERRIDE -> true;
            case MODEL -> false;
        };
    }
}
