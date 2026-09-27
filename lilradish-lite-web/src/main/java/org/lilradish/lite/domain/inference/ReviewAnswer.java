package org.lilradish.lite.domain.inference;

import static java.util.Objects.requireNonNull;

import java.util.Map;
import org.libprunus.core.log.annotation.DoNotLog;
import org.lilradish.lite.domain.declaration.FieldName;

/** What a model reviewing gave back, unwrapped once as it arrived: a decision on every value, or no fit. */
public sealed interface ReviewAnswer permits ReviewAnswer.Reviewed, DoesNotFit {

    /** @param decisions one for each value it was asked to decide, and no other */
    record Reviewed(Map<FieldName, Decision> decisions) implements ReviewAnswer {

        public Reviewed {
            decisions = Map.copyOf(requireNonNull(decisions, "Reviewed decisions must not be null"));
        }
    }

    sealed interface Decision permits Assured, Refused {}

    record Assured() implements Decision {}

    /** @param words why, as the model said it, which the store can keep as it came */
    record Refused(@DoNotLog String words) implements Decision {

        public Refused {
            requireNonNull(words, "Refused words must not be null");
        }
    }
}
