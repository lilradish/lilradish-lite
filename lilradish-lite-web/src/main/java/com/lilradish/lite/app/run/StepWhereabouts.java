package com.lilradish.lite.app.run;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.inference.ModelCallPurpose;
import com.lilradish.lite.domain.model.ModelMode;
import com.lilradish.lite.domain.run.CallRecord;
import com.lilradish.lite.domain.run.FailureRecord;
import com.lilradish.lite.domain.run.HoldRecord;
import com.lilradish.lite.domain.run.ReviewSending;
import com.lilradish.lite.domain.run.RunInTree;
import com.lilradish.lite.domain.run.RunSnapshot;
import com.lilradish.lite.domain.run.RunStepHoldReason;
import com.lilradish.lite.domain.run.RunningOn;
import com.lilradish.lite.domain.run.StepFailure;
import com.lilradish.lite.domain.run.StepInputs;
import com.lilradish.lite.domain.run.StepPosition;
import com.lilradish.lite.domain.run.StepPositions;
import com.lilradish.lite.domain.run.StepRuns;
import com.lilradish.lite.domain.run.StepSnapshot;
import com.lilradish.lite.domain.run.StopRecord;
import com.lilradish.lite.domain.run.TryRecord;
import com.lilradish.lite.domain.run.WaitsOn;
import com.lilradish.lite.domain.run.WorkflowStepId;
import com.lilradish.lite.domain.workflow.ModelChoice;
import com.lilradish.lite.domain.workflow.Producer;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Where a step is as its reader is answered, and the try it is owed next. Shared tree details are read when needed
 * from the reading this is part of.
 */
final class StepWhereabouts {

    private final StepReading stepReading;

    private final ShownValues shownValues;

    private final StepTries stepTries;

    StepWhereabouts(StepReading stepReading, ShownValues shownValues, StepTries stepTries) {
        this.stepReading = stepReading;
        this.shownValues = shownValues;
        this.stepTries = stepTries;
    }

    StepAnswers.@Nullable NextAnswer next(StepPosition position) {
        StepPosition.Owed owed = position.nextTry();
        return owed == null ? null : new StepAnswers.NextAnswer(owed.number(), owed.beyond());
    }

    /** Names the starter from the actual run hierarchy, even when the row shows a nested child step. */
    @Nullable
    String waitsOnStarter(RunSnapshot current, StepPosition position) {
        if (position instanceof StepPosition.Beneath beneath) {
            return waitsOnStarter(beneath.run(), beneath.where());
        }
        if (StepPositions.waitsOn(current, position) != WaitsOn.STARTER) {
            return null;
        }
        return current.run().equals(current.root()) ? "this_run" : "top_level_run";
    }

    StepAnswers.@Nullable WhereAnswer where(RunSnapshot current, StepSnapshot step, StepPosition position) {
        WaitsOn on = StepPositions.waitsOn(current, position);
        String waitsOn = on == null ? null : on.published();
        return switch (position) {
            case StepPosition.Beneath beneath ->
                beneath.where() instanceof StepPosition.NotStarted
                        ? runningWhere(null, null)
                        : where(beneath.run(), beneath.at(), beneath.where());
            case StepPosition.NotStarted ignored -> null;
            case StepPosition.Done ignored -> null;
            case StepPosition.Running running -> runningWhere(running.on(), waitsOn);
            case StepPosition.HeldBack held -> {
                HoldRecord turned = holdTurnedAway(step, held);
                yield new StepAnswers.WhereAnswer(
                        WhereKind.HELD_BACK.published(),
                        held.reason().published(),
                        turned != null && turned.spentUp() ? Boolean.TRUE : null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        StepReading.time(held.since()),
                        stopped(current, step),
                        turned == null ? null : turnaways(step),
                        waitsOn,
                        null,
                        null,
                        null,
                        null,
                        heldSource(step),
                        null,
                        null);
            }
            case StepPosition.AwaitingReview waiting ->
                new StepAnswers.WhereAnswer(
                        WhereKind.WAITING_ON_REVIEW.published(),
                        null,
                        reviewSpentUp(step, waiting) ? Boolean.TRUE : null,
                        null,
                        null,
                        null,
                        waiting.number(),
                        waiting.values().stream()
                                .map(value -> new StepAnswers.WaitingValueAnswer(
                                        value.field(), value.on().published()))
                                .toList(),
                        null,
                        null,
                        null,
                        StepReading.time(waiting.since()),
                        null,
                        null,
                        waitsOn,
                        waiting.sending() == null ? null : waiting.sending().published(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
            case StepPosition.Owed owed ->
                new StepAnswers.WhereAnswer(
                        WhereKind.OWED_TRY.published(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        owed.open(),
                        owed.beyond(),
                        null,
                        StepReading.time(owed.since()),
                        null,
                        null,
                        waitsOn,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
            case StepPosition.Failed failed -> {
                FailureReason why = FailureReason.of(failed.why());
                NotHeld notHeld = why == FailureReason.MODEL_NOT_DEPLOYED || why == FailureReason.TOOL_NOT_OFFERED
                        ? notHeld(step)
                        : null;
                ModelMode mode = notHeld == null ? null : notHeld.choice().mode();
                yield new StepAnswers.WhereAnswer(
                        WhereKind.FAILED.published(),
                        why.published(),
                        null,
                        notHeld == null ? null : notHeld.choice().model().value(),
                        mode == null ? null : mode.value(),
                        notHeld != null && notHeld.reviewing() ? Boolean.TRUE : null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        StepReading.time(failed.since()),
                        failed.why() instanceof StepFailure.Recorded recorded
                                        && recorded.purpose() == ModelCallPurpose.REVIEW
                                ? null
                                : stopped(current, step),
                        null,
                        waitsOn,
                        null,
                        why == FailureReason.UNCLAIMED_VALUE ? unclaimed(current, step) : null,
                        why == FailureReason.TOO_DEEP
                                ? new StepAnswers.TooDeepAnswer(Integer.toString(stepReading.deployment.maximumDepth()))
                                : null,
                        why == FailureReason.TOOL_NOT_OFFERED
                                ? StepPositions.inForce(step).orElseThrow().tool()
                                : null,
                        null,
                        why == FailureReason.SOURCE_NO_LONGER_MATCHES ? failedSource(step) : null,
                        why == FailureReason.SOURCE_LIST_NOT_HERE ? failedSource(step) : null);
            }
        };
    }

    private StepAnswers.@Nullable HeldSourceAnswer heldSource(StepSnapshot step) {
        HoldRecord.SourceRecord source =
                step.hold() == null ? null : step.hold().source();
        return source == null ? null : source(source);
    }

    private StepAnswers.FailedSourceAnswer failedSource(StepSnapshot step) {
        FailureRecord.SourceRecord failed =
                requireNonNull(StepPositions.inForce(step).orElseThrow().source(), "A source failure names its source");
        return new StepAnswers.FailedSourceAnswer(source(failed.maker()), failed.field());
    }

    private StepAnswers.HeldSourceAnswer source(HoldRecord.SourceRecord source) {
        RunInTree root = stepReading.context;
        while (root.above() != null) {
            root = requireNonNull(root.above());
        }
        RunSnapshot sourceRun = root.find(source.run())
                .orElseThrow(() -> new IllegalStateException("A held source belongs to the run's tree"))
                .run();
        StepSnapshot sourceStep = sourceRun
                .step(new WorkflowStepId(source.step()))
                .orElseThrow(() -> new IllegalStateException("A held source names a planned step"));
        if (!source.runStep().equals(sourceStep.runStep())
                || !(sourceStep.planned().runs() instanceof StepRuns.Code code)) {
            throw new IllegalStateException("A held source names the actual code step that was reached");
        }
        int number = source.run().equals(stepReading.run.run())
                ? stepReading.header.number()
                : requireNonNull(stepReading.details(), "A cross-run held source has its tree's read details")
                        .numbered(source.run())
                        .number();
        return new StepAnswers.HeldSourceAnswer(
                source.run().value(),
                number,
                source.step(),
                sourceStep.planned().name().value(),
                sourceStep.planned().label() == null
                        ? null
                        : sourceStep.planned().label().value(),
                code.codeStep());
    }

    private static StepAnswers.WhereAnswer runningWhere(@Nullable RunningOn on, @Nullable String waitsOn) {
        return new StepAnswers.WhereAnswer(
                WhereKind.RUNNING.published(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                on == null ? null : on.published(),
                null,
                null,
                null,
                waitsOn,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /* Withheld as the value it was chosen by is, judged through the route's own binding where the route runs. */
    private StepAnswers.UnclaimedAnswer unclaimed(RunSnapshot current, StepSnapshot step) {
        String term = StepPositions.inForce(step)
                .orElseThrow(() -> new IllegalStateException(
                        "Step " + step.planned().name().value() + " failed on a value no case claimed, recording none"))
                .term();
        if (term == null || stepReading.readsModels) {
            return new StepAnswers.UnclaimedAnswer.Named(term);
        }
        StepRuns.Route route = (StepRuns.Route) step.planned().runs();
        RunInTree at = stepReading
                .context
                .find(current.run())
                .orElseThrow(() ->
                        new IllegalStateException("Run " + current.run().value() + " is not in the tree read from "
                                + stepReading.run.run().value()));
        StepInputs.BindingRecord chosenBy = StepInputs.bound(at, route.discriminator())
                .orElseThrow(() -> new IllegalStateException(
                        "Route " + step.planned().name().value() + " failed on a value that does not stand"));
        return shownValues.withheld(chosenBy.sources())
                ? new StepAnswers.UnclaimedAnswer.Withheld(true)
                : new StepAnswers.UnclaimedAnswer.Named(term);
    }

    /* Only where the review waits on being tried again: the call that ended it is the newest sent for it. */
    private static boolean reviewSpentUp(StepSnapshot step, StepPosition.AwaitingReview waiting) {
        return waiting.sending() == ReviewSending.TURNED_AWAY
                && ReviewSending.newestCall(step.newest().orElseThrow())
                        .map(CallRecord::spentUp)
                        .orElse(false);
    }

    private static @Nullable HoldRecord holdTurnedAway(StepSnapshot step, StepPosition.HeldBack held) {
        return held.reason() == RunStepHoldReason.TURNED_AWAY ? step.hold() : null;
    }

    /** Each time a call to produce the newest try was turned away, oldest first, whichever attempt sent it. */
    private @Nullable List<StepAnswers.TurnawayAnswer> turnaways(StepSnapshot step) {
        TryRecord newest = step.newest().orElse(null);
        return newest == null ? null : stepTries.turnaways(newest);
    }

    /** Whose model was not held, as the failure in force says what it was sending for; none where it names none. */
    private static @Nullable NotHeld notHeld(StepSnapshot step) {
        FailureRecord failure = StepPositions.inForce(step).orElse(null);
        if (failure == null || failure.purpose() == null) {
            return null;
        }
        boolean reviewing = failure.purpose() == ModelCallPurpose.REVIEW;
        ModelChoice choice = reviewing
                ? step.planned().reviewer()
                : step.planned().producer() instanceof Producer.Model model ? model.choice() : null;
        return choice == null ? null : new NotHeld(choice, reviewing);
    }

    /**
     * The stop in force on what the step runs, the workflow's ahead of it; none once it is let go. Read first on a
     * step held back, or failed other than for its reviewer, since it keeps what it runs from being sent again.
     */
    private StepAnswers.@Nullable StoppedAnswer stopped(RunSnapshot current, StepSnapshot step) {
        StopRecord stop = current.stoppedFor(step);
        if (stop == null) {
            return null;
        }
        StoppedWhat what = current.workflowStopped() != null ? StoppedWhat.WORKFLOW : StoppedWhat.ENTRY;
        return new StepAnswers.StoppedAnswer(
                what.published(), stepReading.person(stop.by()), StepReading.time(stop.since()));
    }

    private record NotHeld(ModelChoice choice, boolean reviewing) {}
}
