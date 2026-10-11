package com.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
 * @param beneath the run this step started, if one has started
 * @param casesStopped stops on route targets, keyed by the case that pins them
 */
public record StepSnapshot(
        @DoNotLog PlannedStep planned,
        @Nullable StopRecord pinStopped,
        @Nullable RunStepId runStep,
        @Nullable HoldRecord hold,
        List<FailureRecord> failures,
        List<TryRecord> tries,
        @DoNotLog @Nullable Beneath beneath,
        Map<UUID, StopRecord> casesStopped) {

    public StepSnapshot {
        requireNonNull(planned, "StepSnapshot planned must not be null");
        failures = List.copyOf(requireNonNull(failures, "StepSnapshot failures must not be null"));
        tries = List.copyOf(requireNonNull(tries, "StepSnapshot tries must not be null"));
        casesStopped = Map.copyOf(requireNonNull(casesStopped, "StepSnapshot casesStopped must not be null"));
        if (beneath != null
                && !(planned.runs() instanceof StepRuns.Workflow || planned.runs() instanceof StepRuns.Route)) {
            throw new IllegalArgumentException("Only a workflow or route step starts a run beneath");
        }
        if (beneath != null && (planned.runs() instanceof StepRuns.Route) != (beneath.routeCase() != null)) {
            throw new IllegalArgumentException("A run beneath a route must name its case");
        }
        if (!casesStopped.isEmpty() && !(planned.runs() instanceof StepRuns.Route)) {
            throw new IllegalArgumentException("Only a route step has case stops");
        }
        for (int index = 0; index < tries.size(); index++) {
            Integer number = tries.get(index).number();
            if (number == null ? index != tries.size() - 1 : number != index + 1) {
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

    /** How many tries it spent; an open model production not sent counts none. */
    public int used() {
        return tries.size() - (tries.isEmpty() || tries.getLast().number() != null ? 0 : 1);
    }

    public record Beneath(RunSnapshot run, @Nullable UUID routeCase) {

        public Beneath {
            requireNonNull(run, "StepSnapshot.Beneath run must not be null");
        }

        @Override
        public String toString() {
            return "Beneath[run=" + run.run().value() + "]";
        }
    }
}
