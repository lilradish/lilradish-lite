package org.lilradish.lite.domain.inference;

/**
 * Whose call a recorded model call was. A gate's judging call shares {@code (run, step, attempt)}
 * with the step's own — the judge is asked about that attempt — so without this the two are one row
 * shape and every cost figure charges a step for the review of it.
 */
public enum CallPurpose {
    STEP,
    GATE_JUDGE
}
