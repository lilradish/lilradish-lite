package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import java.util.List;
import org.lilradish.lite.domain.workflow.Producer;

/**
 * What a run does next by itself: steps run in the order declared, a stopped run starts nothing, a stop on what
 * a step runs holds the try the step would have made, and that hold goes once the stop is let go.
 */
public final class EngineActs {

    private EngineActs() {}

    /**
     * A question or a code step a person answers is asked of them, a question a model produces is asked and then
     * sent, and a code step code produces is started; a code step the release does not hold is held instead, and
     * goes on once a release holds it again. Values waiting on the model the step names, nothing yet sent for them,
     * are sent to it, whatever stop holds what the step runs, since what was produced may still be reviewed. A step
     * running a workflow or a route is held on a stop as any is, and otherwise left as it is.
     */
    public static EngineAct next(RunSnapshot run, List<StepPosition> positions) {
        requireNonNull(run, "EngineActs run must not be null");
        requireNonNull(positions, "EngineActs positions must not be null");
        if (positions.size() != run.steps().size()) {
            throw new IllegalArgumentException("EngineActs was handed other than one position per step");
        }
        if (run.stopped()) {
            return new EngineAct.Nothing();
        }
        for (int index = 0; index < positions.size(); index++) {
            StepPosition position = positions.get(index);
            if (position instanceof StepPosition.Done) {
                continue;
            }
            StepSnapshot step = run.steps().get(index);
            boolean stopped = run.stoppedFor(step) != null;
            PlannedStep planned = step.planned();
            HoldRecord hold = step.hold();
            if (hold != null
                    && ((hold.reason() == RunStepHoldReason.ENTRY_STOPPED && !stopped)
                            || (hold.reason() == RunStepHoldReason.CODE_STEP_NOT_HELD && !unreleased(planned)))) {
                return new EngineAct.ReleaseHold(planned);
            }
            if (position instanceof StepPosition.AwaitingReview waiting) {
                return reviewedByModel(planned, waiting)
                        ? new EngineAct.Review(planned, waiting.number())
                        : new EngineAct.Nothing();
            }
            boolean newTry = position instanceof StepPosition.NotStarted
                    || (position instanceof StepPosition.Running running && running.on() == RunningOn.NEXT_TRY);
            if (!newTry) {
                return new EngineAct.Nothing();
            }
            if (stopped) {
                return new EngineAct.HoldOnStop(planned);
            }
            if (unreleased(planned)) {
                return new EngineAct.HoldNotHeld(planned);
            }
            boolean question = planned.runs() instanceof StepRuns.Question;
            boolean code = planned.runs() instanceof StepRuns.Code;
            return switch (planned.producer()) {
                case Producer.Person ignored ->
                    question || code ? new EngineAct.StartTry(planned, step.used() + 1) : new EngineAct.Nothing();
                case Producer.Model ignored -> {
                    if (!question) {
                        yield new EngineAct.Nothing();
                    }
                    yield step.newest().map(TryRecord::unsent).orElse(false)
                            ? new EngineAct.Send(planned, step.used())
                            : new EngineAct.StartTry(planned, step.used() + 1);
                }
                case Producer.Code ignored ->
                    code ? new EngineAct.StartTry(planned, step.used() + 1) : new EngineAct.Nothing();
                case null -> new EngineAct.Nothing();
            };
        }
        return new EngineAct.Nothing();
    }

    /* A code step's values go as the running release declares the step, so none go while it declares nothing of it. */
    private static boolean reviewedByModel(PlannedStep planned, StepPosition.AwaitingReview waiting) {
        return waiting.sending() == ReviewSending.UNSENT && !unreleased(planned);
    }

    /** Whether the step runs a code step the running release does not hold. */
    static boolean unreleased(PlannedStep planned) {
        return planned.runs() instanceof StepRuns.Code code && code.released() == null;
    }
}
