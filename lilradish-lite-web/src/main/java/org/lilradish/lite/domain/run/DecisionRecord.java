package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import org.jspecify.annotations.Nullable;
import org.libprunus.core.log.annotation.DoNotLog;

/**
 * What one review decided of one value.
 *
 * @param why the words it was refused with, exactly where it was refused
 */
public record DecisionRecord(
        ProductionValueId value,
        ReviewOutcome outcome,
        @DoNotLog @Nullable String why) {

    public DecisionRecord {
        requireNonNull(value, "DecisionRecord value must not be null");
        requireNonNull(outcome, "DecisionRecord outcome must not be null");
        if ((outcome == ReviewOutcome.REFUSED) != (why != null)) {
            throw new IllegalArgumentException("DecisionRecord says why exactly where it refused");
        }
    }
}
