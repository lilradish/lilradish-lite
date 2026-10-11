package com.lilradish.lite.domain.inference;

import static java.util.Objects.requireNonNull;

/** The first thing found wrong with an answer. */
public record DoesNotFit(DidNotFitReason reason) implements ProductionAnswer, ReviewAnswer {

    public DoesNotFit {
        requireNonNull(reason, "DoesNotFit reason must not be null");
    }
}
