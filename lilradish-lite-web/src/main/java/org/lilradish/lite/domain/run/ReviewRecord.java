package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.identity.SubjectId;
import org.lilradish.lite.domain.inference.DidNotFitReason;

/**
 * One review of one try: a decision on each value it decided, or none where it went wrong.
 *
 * @param by the person who reviewed; none where the model the step names did
 * @param forLength whether it refused values that had stood, for the length they carried, rather than deciding
 *     values waiting on it
 * @param lost how it went wrong, which decides nothing; only a model's review goes wrong
 * @param misfit why what the model gave back did not fit, exactly where it did not
 */
public record ReviewRecord(
        @Nullable SubjectId by,
        Instant at,
        boolean forLength,
        @Nullable TryLostReason lost,
        @Nullable DidNotFitReason misfit,
        List<DecisionRecord> decisions) {

    public ReviewRecord {
        requireNonNull(at, "ReviewRecord at must not be null");
        decisions = List.copyOf(requireNonNull(decisions, "ReviewRecord decisions must not be null"));
        if (lost != null && (by != null || !decisions.isEmpty())) {
            throw new IllegalArgumentException("ReviewRecord went wrong only as a model's, deciding nothing");
        }
        if ((misfit != null) != (lost == TryLostReason.DID_NOT_FIT)) {
            throw new IllegalArgumentException("ReviewRecord says why it did not fit exactly where it did not");
        }
    }

    public Optional<DecisionRecord> decisionOn(ProductionValueId value) {
        requireNonNull(value, "ReviewRecord value must not be null");
        return decisions.stream()
                .filter(decision -> decision.value().equals(value))
                .findFirst();
    }
}
