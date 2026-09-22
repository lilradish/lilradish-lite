package org.lilradish.lite.domain.run;

/**
 * How far along one attempt of a step is. A step is several rows — one per attempt — and each
 * carries its own state.
 */
public enum StepStatus {
    PENDING,
    RUNNING,
    AWAITING_APPROVAL,
    COMPLETED,
    FAILED,
    SKIPPED,
    BLOCKED
}
