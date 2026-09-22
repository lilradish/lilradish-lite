package org.lilradish.lite.domain.run;

/**
 * What undoing a step would take. Decided from what the step declared and written onto the attempt
 * row before the work starts, because the interface has to say which of these applies before anyone
 * commits to the step.
 */
public enum Reversal {

    /** Going back does not undo it; an explicit compensating action does. */
    COMPENSATING_ACTION,

    /** Nothing undoes it — the answer where a step declared neither of the other two. */
    IRREVERSIBLE,

    /** There is nothing to undo: a repeat lands on the effect already recorded. */
    IDEMPOTENT_BY_KEY
}
