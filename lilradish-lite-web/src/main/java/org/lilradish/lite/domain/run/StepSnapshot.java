package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.log.annotation.DoNotLog;

/**
 * One step of one run: what the version says of it beside what the run's rows say.
 *
 * @param pinStopped the stop in force on the entry it runs; none where it is not stopped, or it runs no entry
 * @param runStep none until anything is written about the step in this run
 * @param hold the hold on it not yet released
 * @param failures every failure written down on it, in the order written
 * @param tries every try of it, oldest first
 */
public record StepSnapshot(
        @DoNotLog PlannedStep planned,
        @Nullable StopRecord pinStopped,
        @Nullable RunStepId runStep,
        @Nullable HoldRecord hold,
        List<FailureRecord> failures,
        List<TryRecord> tries) {

    public StepSnapshot {
        requireNonNull(planned, "StepSnapshot planned must not be null");
        failures = List.copyOf(requireNonNull(failures, "StepSnapshot failures must not be null"));
        tries = List.copyOf(requireNonNull(tries, "StepSnapshot tries must not be null"));
        for (int index = 0; index < tries.size(); index++) {
            if (tries.get(index).number() != index + 1) {
                throw new IllegalArgumentException("StepSnapshot holds tries numbered other than one after another");
            }
        }
        if (runStep == null && (hold != null || !failures.isEmpty() || !tries.isEmpty())) {
            throw new IllegalArgumentException("StepSnapshot holds rows of a step the run holds no row of");
        }
    }

    public Optional<TryRecord> newest() {
        return tries.isEmpty() ? Optional.empty() : Optional.of(tries.getLast());
    }

    /** Which of its tries it is on, none being nought. */
    public int used() {
        return tries.size();
    }
}
