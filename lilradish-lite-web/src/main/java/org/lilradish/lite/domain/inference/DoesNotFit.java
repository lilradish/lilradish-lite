package org.lilradish.lite.domain.inference;

import static java.util.Objects.requireNonNull;

/** An answer that does not fit the envelope it was asked in, and the first thing found wrong with it. */
public record DoesNotFit(DidNotFitReason reason) implements ProductionAnswer, ReviewAnswer {

    public DoesNotFit {
        requireNonNull(reason, "DoesNotFit reason must not be null");
    }
}
