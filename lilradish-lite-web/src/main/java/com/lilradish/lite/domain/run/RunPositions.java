package com.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Optional;

/**
 * Whether a run is stopped comes from the stop in force. Steps start only after all earlier steps are done,
 * so the first unfinished step locates a running run.
 */
public final class RunPositions {

    private RunPositions() {}

    public static RunState state(RunSnapshot run, List<StepPosition> positions) {
        requireNonNull(run, "RunPositions run must not be null");
        requireOne(run, positions);
        boolean done = positions.stream().allMatch(position -> position instanceof StepPosition.Done);
        if (done) {
            return RunState.DONE;
        }
        if (run.stopped()) {
            return RunState.STOPPED;
        }
        return positions.stream().anyMatch(position -> position.state() == StepState.FAILED)
                ? RunState.FAILED
                : RunState.RUNNING;
    }

    /** The first step not done, which is the one a running run is on; none where it is not running. */
    public static Optional<StepSnapshot> at(RunSnapshot run, List<StepPosition> positions) {
        if (state(run, positions) != RunState.RUNNING) {
            return Optional.empty();
        }
        for (int index = 0; index < positions.size(); index++) {
            if (!(positions.get(index) instanceof StepPosition.Done)) {
                return Optional.of(run.steps().get(index));
            }
        }
        throw new IllegalStateException("A running run has a step not done");
    }

    public static int done(List<StepPosition> positions) {
        requireNonNull(positions, "RunPositions positions must not be null");
        return (int) positions.stream()
                .filter(position -> position instanceof StepPosition.Done)
                .count();
    }

    private static void requireOne(RunSnapshot run, List<StepPosition> positions) {
        requireNonNull(positions, "RunPositions positions must not be null");
        if (positions.size() != run.steps().size()) {
            throw new IllegalArgumentException("RunPositions was handed other than one position per step");
        }
    }
}
