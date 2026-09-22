package org.lilradish.lite.domain.run;

/**
 * Which check stopped an output. A reviewer told only that it was blocked cannot tell an egress
 * refusal from an exhausted budget, and the two want opposite next steps.
 */
public enum BlockedCheck {
    EGRESS_FILTER,
    TOKEN_BUDGET,
    OUTPUT_SCHEMA,
    CONFIDENCE_FLOOR,

    /** Named rather than guessed: a wrong check name would be worse than admitting to none. */
    UNCLASSIFIED
}
