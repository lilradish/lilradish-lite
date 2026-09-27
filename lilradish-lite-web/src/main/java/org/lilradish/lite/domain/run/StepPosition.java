package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.identity.SubjectId;

/** Where one step of one run is, and what that turns on: the state it reads as, and whatever that state names. */
public sealed interface StepPosition
        permits StepPosition.NotStarted,
                StepPosition.Running,
                StepPosition.HeldBack,
                StepPosition.AwaitingReview,
                StepPosition.Owed,
                StepPosition.Failed,
                StepPosition.Done {

    StepState state();

    /**
     * The try a person may make next, none where the step owes none: the one asked already or owed, the one a stop
     * holds, or the one beyond every try declared once they are spent.
     */
    default @Nullable Owed nextTry() {
        return switch (this) {
            case Owed owed -> owed;
            case HeldBack held -> held.owed();
            case Failed failed ->
                failed.why() instanceof StepFailure.TriesSpent spent
                        ? new Owed(spent.used() + 1, false, true, failed.since())
                        : null;
            case NotStarted ignored -> null;
            case Running ignored -> null;
            case AwaitingReview ignored -> null;
            case Done ignored -> null;
        };
    }

    record NotStarted() implements StepPosition {

        @Override
        public StepState state() {
            return StepState.NOT_STARTED;
        }
    }

    record Running(RunningOn on) implements StepPosition {

        public Running {
            requireNonNull(on, "StepPosition.Running on must not be null");
        }

        @Override
        public StepState state() {
            return StepState.RUNNING;
        }
    }

    /**
     * @param owed the try a person owes that a stop holds; none where the hold stands in place of a try the
     *     system would make
     */
    record HeldBack(
            RunStepHoldReason reason,
            Instant since,
            @Nullable Owed owed) implements StepPosition {

        public HeldBack {
            requireNonNull(reason, "StepPosition.HeldBack reason must not be null");
            requireNonNull(since, "StepPosition.HeldBack since must not be null");
            if (owed != null && reason != RunStepHoldReason.ENTRY_STOPPED) {
                throw new IllegalArgumentException("StepPosition.HeldBack holds a try owed only on a stop");
            }
        }

        @Override
        public StepState state() {
            return StepState.HELD_BACK;
        }
    }

    /**
     * Values of the step's newest try waiting on a review, which one review decides together.
     *
     * @param number the try they are of
     * @param values in the order the step's declaration gives them back
     * @param producedBy the person who produced them, whom nobody lets review them; none where no person did
     * @param since when they were produced
     * @param sending how the try stands with the model the step names to review it; none exactly where the step
     *     names none
     */
    record AwaitingReview(
            int number,
            List<WaitingValue> values,
            @Nullable SubjectId producedBy,
            Instant since,
            @Nullable ReviewSending sending)
            implements StepPosition {

        public AwaitingReview {
            values = List.copyOf(requireNonNull(values, "StepPosition.AwaitingReview values must not be null"));
            requireNonNull(since, "StepPosition.AwaitingReview since must not be null");
            if (values.isEmpty()) {
                throw new IllegalArgumentException("StepPosition.AwaitingReview names a value waiting");
            }
        }

        @Override
        public StepState state() {
            return StepState.WAITING;
        }
    }

    /**
     * A next try only a person may make: asked already and waiting on an answer where {@code open}, and otherwise
     * owed after the last was refused or lost.
     *
     * @param number the try an answer fills, or the one to be made
     * @param beyond whether that try is beyond the tries the step declares
     * @param since when it was asked, or when the try before it ended
     */
    record Owed(int number, boolean open, boolean beyond, Instant since) implements StepPosition {

        public Owed {
            requireNonNull(since, "StepPosition.Owed since must not be null");
            if (number < 1) {
                throw new IllegalArgumentException("StepPosition.Owed number must be positive: " + number);
            }
        }

        @Override
        public StepState state() {
            return StepState.WAITING;
        }
    }

    record Failed(StepFailure why, Instant since) implements StepPosition {

        public Failed {
            requireNonNull(why, "StepPosition.Failed why must not be null");
            requireNonNull(since, "StepPosition.Failed since must not be null");
        }

        @Override
        public StepState state() {
            return StepState.FAILED;
        }
    }

    record Done() implements StepPosition {

        @Override
        public StepState state() {
            return StepState.DONE;
        }
    }

    /** One value waiting on a review, and whom on. */
    record WaitingValue(ProductionValueId value, String field, WaitsOn on) {

        public WaitingValue {
            requireNonNull(value, "StepPosition.WaitingValue value must not be null");
            requireNonNull(field, "StepPosition.WaitingValue field must not be null");
            requireNonNull(on, "StepPosition.WaitingValue on must not be null");
        }
    }
}
