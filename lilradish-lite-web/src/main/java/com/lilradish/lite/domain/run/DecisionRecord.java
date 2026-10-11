package com.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import org.jspecify.annotations.Nullable;
import org.libprunus.core.log.annotation.DoNotLog;

/**
 * @param why the words it was refused with, exactly where it was refused
 */
public record DecisionRecord(
        ProductionValueId value,
        ReviewOutcome outcome,
        @DoNotLog @Nullable String why,
        @Nullable Long lengthUnits,
        @Nullable Long excessUnits) {

    public DecisionRecord {
        requireNonNull(value, "DecisionRecord value must not be null");
        requireNonNull(outcome, "DecisionRecord outcome must not be null");
        if ((outcome == ReviewOutcome.REFUSED) != (why != null)) {
            throw new IllegalArgumentException("DecisionRecord says why exactly where it refused");
        }
        if ((lengthUnits == null) != (excessUnits == null)) {
            throw new IllegalArgumentException("A length refusal keeps both quantities together");
        }
        if (lengthUnits != null
                && (outcome != ReviewOutcome.REFUSED || lengthUnits < 0 || requireNonNull(excessUnits) <= 0)) {
            throw new IllegalArgumentException("A length refusal keeps a nonnegative contribution and positive excess");
        }
    }
}
