package org.lilradish.lite.domain.run;

/**
 * The one thing a rerun changes. One, never two: a rerun that moved two things cannot say which of
 * them the better answer came from, and that is exactly what the reviewer asking for it wants to
 * learn.
 */
public enum RerunVariant {

    /**
     * A reviewer who thinks the answer was the model's fault moves to the next entry in the chain
     * the step declared, rather than naming a model of their own.
     */
    NEXT_MODEL,

    /** The reviewer's own words, passed to the call as an argument the next answer can use. */
    REVIEWER_NOTE,

    /** Where a step produces its reasoning only on demand, the rerun is what the demand travels on. */
    REASONING
}
