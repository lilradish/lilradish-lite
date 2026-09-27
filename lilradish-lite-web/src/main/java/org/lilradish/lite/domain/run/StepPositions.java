package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.workflow.Producer;
import org.lilradish.lite.domain.workflow.StepProducer;

/**
 * Where each step of a run is, worked out from what the run holds and nothing else. Tries are judged newest by
 * their number and never by time, which ties within a transaction.
 */
public final class StepPositions {

    private StepPositions() {}

    /** One per step of the run, in the order they run. */
    public static List<StepPosition> of(RunSnapshot run) {
        requireNonNull(run, "StepPositions run must not be null");
        List<StepPosition> positions = new ArrayList<>(run.steps().size());
        boolean reached = true;
        for (StepSnapshot step : run.steps()) {
            StepPosition position = of(run, step, reached);
            positions.add(position);
            reached = reached && position instanceof StepPosition.Done;
        }
        return List.copyOf(positions);
    }

    /** Where {@code step} is, as {@link #of(RunSnapshot)} has it. */
    public static StepPosition of(RunSnapshot run, StepSnapshot step) {
        requireNonNull(run, "StepPositions run must not be null");
        requireNonNull(step, "StepPositions step must not be null");
        boolean reached = true;
        for (StepSnapshot each : run.steps()) {
            if (each.planned().id().equals(step.planned().id())) {
                return of(run, step, reached);
            }
            reached = reached && of(run, each, true) instanceof StepPosition.Done;
        }
        throw new IllegalArgumentException("StepPositions was asked of a step its run does not hold");
    }

    /**
     * Whom a step waits on, none where it waits on nobody: a stopped run waits on nobody until it is opened again,
     * and a step held back or failed, or a review the model would not take, waits on whoever started the run, not on
     * whoever may answer or review it.
     */
    public static @Nullable WaitsOn waitsOn(RunSnapshot run, StepPosition position) {
        requireNonNull(run, "StepPositions run must not be null");
        requireNonNull(position, "StepPositions position must not be null");
        if (run.stopped()) {
            return null;
        }
        return switch (position) {
            case StepPosition.NotStarted ignored -> null;
            case StepPosition.Running ignored -> null;
            case StepPosition.Done ignored -> null;
            case StepPosition.HeldBack ignored -> WaitsOn.STARTER;
            case StepPosition.Failed ignored -> WaitsOn.STARTER;
            case StepPosition.Owed ignored -> WaitsOn.ANSWER_STEP;
            case StepPosition.AwaitingReview waiting ->
                waiting.sending() == ReviewSending.TURNED_AWAY
                        ? WaitsOn.STARTER
                        : waiting.values().getFirst().on();
        };
    }

    /**
     * A try only a person may make is held while what the step runs, or the workflow, is stopped, and goes on
     * when it is let go; values already produced may still be reviewed.
     */
    private static StepPosition of(RunSnapshot run, StepSnapshot step, boolean reached) {
        StopRecord stopped = run.stoppedFor(step);
        HoldRecord hold = step.hold();
        if (hold != null) {
            return stopped == null && hold.reason() == RunStepHoldReason.ENTRY_STOPPED
                    ? unreleased(step, letGo(step, hold))
                    : new StepPosition.HeldBack(hold.reason(), hold.since(), null);
        }
        StepPosition position = unreleased(step, unheld(step, reached));
        return stopped != null && position instanceof StepPosition.Owed owed
                ? new StepPosition.HeldBack(RunStepHoldReason.ENTRY_STOPPED, stopped.since(), owed)
                : position;
    }

    /* A stop let go with nothing gone on since leaves its hold until something does, as acting on it does; the
     * try it held reads as asked, since releasing it asks that try before anything else. */
    private static StepPosition letGo(StepSnapshot step, HoldRecord hold) {
        PlannedStep planned = step.planned();
        if (!(planned.runs() instanceof StepRuns.Question || planned.runs() instanceof StepRuns.Code)
                || !(planned.producer() instanceof Producer.Person)) {
            return new StepPosition.HeldBack(hold.reason(), hold.since(), null);
        }
        int number = step.used() + 1;
        return new StepPosition.Owed(number, true, number > planned.declaredTries(), hold.since());
    }

    /* Nobody can make a try of what no release declares, so a try a person may make of it is held instead. Held so
     * it has no hold row, unlike the try the engine would have made, whose hold the engine writes. */
    private static StepPosition unreleased(StepSnapshot step, StepPosition position) {
        StepPosition.Owed owed = position.nextTry();
        return owed != null && EngineActs.unreleased(step.planned())
                ? new StepPosition.HeldBack(RunStepHoldReason.CODE_STEP_NOT_HELD, owed.since(), null)
                : position;
    }

    private static StepPosition unheld(StepSnapshot step, boolean reached) {
        if (step.runStep() == null) {
            return unasked(step, reached);
        }
        TryRecord newest = step.newest().orElse(null);
        Optional<FailureRecord> failure = inForce(step);
        if (failure.isPresent()) {
            return new StepPosition.Failed(
                    new StepFailure.Recorded(
                            failure.get().reason(), failure.get().purpose()),
                    failure.get().at());
        }
        if (newest == null) {
            return unasked(step, reached);
        }
        int declared = step.planned().declaredTries();
        if (newest.open()) {
            return switch (newest.producer()) {
                case PERSON ->
                    new StepPosition.Owed(newest.number(), true, newest.number() > declared, newest.askedAt());
                case CODE -> new StepPosition.Running(RunningOn.CODE);
                // Running(CALL): a model's try with an attempt to produce it written; until then it is being made.
                case MODEL -> new StepPosition.Running(newest.unsent() ? RunningOn.NEXT_TRY : RunningOn.CALL);
            };
        }
        Instant ended = requireNonNull(newest.endedAt());
        if (newest.lost() != null) {
            return spent(step, newest, declared, ended);
        }
        return yielded(step, newest, declared);
    }

    /* One reached that a model or code produces reads as its first try being made, which the system makes itself. */
    private static StepPosition unasked(StepSnapshot step, boolean reached) {
        if (!reached) {
            return new StepPosition.NotStarted();
        }
        Producer producer = step.planned().producer();
        return producer instanceof Producer.Model || producer instanceof Producer.Code
                ? new StepPosition.Running(RunningOn.NEXT_TRY)
                : new StepPosition.NotStarted();
    }

    /* As the running release says now, never as it said when an earlier try was made. */
    private static boolean mayRunAgain(StepSnapshot step) {
        return step.planned().runs() instanceof StepRuns.Code code
                && code.released() != null
                && code.released().mayRunAgain();
    }

    /**
     * The failure written down on {@code step} that is in force: the last written on its newest try or on none, and
     * only while it is not answered. A later failure supersedes every one before it, and answering it ends them all;
     * a failure on an earlier try ended with that try.
     */
    public static Optional<FailureRecord> inForce(StepSnapshot step) {
        requireNonNull(step, "StepPositions step must not be null");
        TryRecord newest = step.newest().orElse(null);
        FailureRecord found = null;
        for (FailureRecord failure : step.failures()) {
            ProductionId onTry = failure.onTry();
            if (onTry == null || (newest != null && onTry.equals(newest.id()))) {
                found = failure;
            }
        }
        return found == null || found.answered() ? Optional.empty() : Optional.of(found);
    }

    /* A person's try is never lost, and code that may not be run again is only ever answered by a person. */
    private static StepPosition spent(StepSnapshot step, TryRecord newest, int declared, Instant ended) {
        if (newest.producer() == StepProducer.PERSON) {
            throw new IllegalStateException("Try " + newest.id().value() + " of a person was lost");
        }
        if (newest.number() >= declared) {
            return new StepPosition.Failed(new StepFailure.TriesSpent(newest.number(), declared), ended);
        }
        return newest.producer() == StepProducer.MODEL || mayRunAgain(step)
                ? new StepPosition.Running(RunningOn.NEXT_TRY)
                : new StepPosition.Owed(newest.number() + 1, false, false, ended);
    }

    private static StepPosition yielded(StepSnapshot step, TryRecord newest, int declared) {
        Instant ended = requireNonNull(newest.endedAt());
        ReviewRecord refusal = null;
        List<StepPosition.WaitingValue> waiting = new ArrayList<>();
        ReviewSending sending = step.planned().reviewer() == null ? null : ReviewSending.of(newest);
        WaitsOn on = sending == null || sending.toPerson() ? WaitsOn.REVIEW_AT_GATE : WaitsOn.MODEL;
        for (ValueRecord value : newest.values()) {
            switch (ValueStanding.of(newest, value)) {
                case STANDS -> {}
                case WAITING_ON_REVIEW -> waiting.add(new StepPosition.WaitingValue(value.id(), value.field(), on));
                case REFUSED -> refusal = newest.onReview().orElseThrow();
                case REFUSED_FOR_LENGTH -> refusal = refusal == null ? forLength(newest, value) : refusal;
            }
        }
        if (refusal != null) {
            if (newest.number() >= declared) {
                return new StepPosition.Failed(new StepFailure.TriesSpent(newest.number(), declared), refusal.at());
            }
            boolean byPerson = refusal.by() != null || refusal.forLength();
            boolean onceOnly = step.planned().producer() instanceof Producer.Code && !mayRunAgain(step);
            return byPerson || onceOnly
                    ? new StepPosition.Owed(newest.number() + 1, false, false, refusal.at())
                    : new StepPosition.Running(RunningOn.NEXT_TRY);
        }
        if (waiting.isEmpty()) {
            return new StepPosition.Done();
        }
        return new StepPosition.AwaitingReview(
                newest.number(),
                waiting,
                newest.producer() == StepProducer.PERSON ? newest.endedBy() : null,
                ended,
                sending);
    }

    private static ReviewRecord forLength(TryRecord newest, ValueRecord value) {
        return newest.reviews().stream()
                .filter(review -> review.forLength()
                        && review.decisionOn(value.id())
                                .map(decision -> decision.outcome() == ReviewOutcome.REFUSED)
                                .orElse(false))
                .findFirst()
                .orElseThrow();
    }
}
