package org.lilradish.lite.domain.inference;

/**
 * How a model call ended, as it is recorded against that call. Seven of these are accounting alone;
 * {@link #RATE_LIMITED} is the one a policy reads, because a quota must not spend the step's retry
 * budget.
 */
public enum CallOutcome {
    OK,

    /** The model answered, but not in the shape the step declared. */
    SCHEMA_MISMATCH,

    /** Records that the failure was counted against the endpoint's health. */
    BREAKER_OPEN,

    /** Records that a stream stopped early, which is counted against the endpoint the same way. */
    INCOMPLETE_STREAM,

    /** The endpoint is well and asking for a pause: neither its health nor the retry budget moves. */
    RATE_LIMITED,

    /** This system's refusal — the call was never made, so nothing counts against the endpoint. */
    REFUSED_EGRESS,

    /** A ceiling this system holds was already spent, so the call was never made either. */
    BUDGET_EXHAUSTED,

    /** The request did not stand: no model could have answered it, so none was asked. */
    ROUTING_ERROR
}
