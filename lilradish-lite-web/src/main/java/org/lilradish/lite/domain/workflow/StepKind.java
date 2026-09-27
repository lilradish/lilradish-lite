package org.lilradish.lite.domain.workflow;

/**
 * What a step runs, as the store tells it apart: a pinned version of an entry, a published code step, or a
 * route. The store declares the same vocabulary, spelt as these constants and in their order.
 */
public enum StepKind {
    ENTRY,
    CODE_STEP,
    ROUTE
}
