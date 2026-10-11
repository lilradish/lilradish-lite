package com.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.search.QuestionSearchShape;
import com.lilradish.lite.domain.wire.JsonValue;
import com.lilradish.lite.domain.workflow.Producer;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What a run does next by itself: steps run in declared order, a stopped run starts nothing, and a step not done
 * whose run beneath is under way leaves the next act to that run. A step that would start a run beneath fails, in
 * this order, on a value no case claims, then on that run going deeper than runs may; only a step that could start
 * is held by a stop on what it runs, until the stop is let go.
 */
public final class EngineActs {

    private EngineActs() {}

    /** @param maximumDepth how many runs deep a tree may go, the run at the top counted as one */
    public static EngineAct next(RunInTree at, int maximumDepth) {
        requireNonNull(at, "EngineActs at must not be null");
        if (at.run().stopped()) {
            return new EngineAct.Nothing(at.run().run());
        }
        return next(at, StepPositions.of(at.run()), maximumDepth);
    }

    static EngineAct next(RunInTree at, List<StepPosition> positions, int maximumDepth) {
        requireNonNull(at, "EngineActs at must not be null");
        RunSnapshot run = at.run();
        requireNonNull(positions, "EngineActs positions must not be null");
        if (positions.size() != run.steps().size()) {
            throw new IllegalArgumentException("EngineActs was handed other than one position per step");
        }
        if (maximumDepth < 1) {
            throw new IllegalArgumentException("EngineActs maximumDepth must be positive: " + maximumDepth);
        }
        if (run.stopped()) {
            return new EngineAct.Nothing(run.run());
        }
        for (int index = 0; index < positions.size(); index++) {
            StepPosition position = positions.get(index);
            if (!(position instanceof StepPosition.Done)) {
                return nextAt(at, run.steps().get(index), position, maximumDepth);
            }
        }
        return new EngineAct.Nothing(run.run());
    }

    private static EngineAct nextAt(RunInTree at, StepSnapshot step, StepPosition position, int maximumDepth) {
        RunSnapshot run = at.run();
        if (position instanceof StepPosition.Beneath beneath) {
            RunInTree child = at.beneath(step);
            return child.run().stopped()
                    ? new EngineAct.Nothing(child.run().run())
                    : nextAt(child, beneath.at(), beneath.where(), maximumDepth);
        }
        PlannedStep planned = step.planned();
        Choice choice = planned.runs() instanceof StepRuns.Route route ? choice(at, route) : null;
        boolean stopped = run.stoppedFor(step) != null
                || (choice != null
                        && choice.selected() != null
                        && step.casesStopped().containsKey(choice.selected().id()));
        boolean beforeSending = position instanceof StepPosition.NotStarted
                || position instanceof StepPosition.Owed
                || position instanceof StepPosition.Running running && running.on() == RunningOn.NEXT_TRY;
        if (!stopped
                && beforeSending
                && planned.runs() instanceof StepRuns.Search search
                && search.released() != null
                && (!search.way().equals(search.markedWay())
                        || !QuestionSearchShape.matches(
                                search.released(), search.question().gives().fields()))) {
            return new EngineAct.FailSearchShape(run.run(), planned);
        }
        HoldRecord hold = step.hold();
        if (hold != null
                && hold.source() != null
                && !unreleased(StepInputs.codeSource(at, hold.source()).planned())) {
            if (stopped) {
                return new EngineAct.Nothing(run.run());
            }
            return unreleased(planned)
                    ? new EngineAct.HoldNotHeld(run.run(), planned, null)
                    : new EngineAct.ReleaseHold(run.run(), planned);
        }
        if (hold != null
                && ((hold.reason() == RunStepHoldReason.ENTRY_STOPPED && !stopped)
                        || ((hold.reason() == RunStepHoldReason.CODE_STEP_NOT_HELD
                                        || hold.reason() == RunStepHoldReason.SEARCH_WAY_NOT_HELD)
                                && hold.source() == null
                                && !stopped
                                && !unreleased(planned)))) {
            return new EngineAct.ReleaseHold(run.run(), planned);
        }
        if (position instanceof StepPosition.AwaitingReview waiting) {
            return needsReview(step, waiting)
                    ? new EngineAct.Review(
                            run.run(), planned, step.newest().orElseThrow().id())
                    : new EngineAct.Nothing(run.run());
        }
        boolean readyForNextAct = position instanceof StepPosition.NotStarted
                || (position instanceof StepPosition.Running running && running.on() == RunningOn.NEXT_TRY);
        if (!readyForNextAct) {
            return new EngineAct.Nothing(run.run());
        }
        // What no letting go could mend fails first, so neither failure waits on a stop being let go.
        if (choice != null && choice.standing() && choice.selected() == null) {
            return new EngineAct.FailUnclaimed(run.run(), planned, choice.said());
        }
        EngineAct.StartBeneath beneath = beneath(run.run(), planned, choice);
        if (beneath != null && depth(at) >= maximumDepth) {
            return new EngineAct.FailTooDeep(run.run(), planned);
        }
        if (stopped) {
            return new EngineAct.HoldOnStop(run.run(), planned);
        }
        if (unreleased(planned)) {
            return new EngineAct.HoldNotHeld(run.run(), planned, null);
        }
        if (planned.producer() instanceof Producer.Model
                && (planned.runs() instanceof StepRuns.Question || planned.runs() instanceof StepRuns.Search)) {
            HoldRecord.SourceRecord missing =
                    StepInputs.missingCodeSource(at, step).orElse(null);
            if (missing != null) {
                return new EngineAct.HoldNotHeld(run.run(), planned, missing);
            }
        }
        if (beneath != null) {
            return beneath;
        }
        if (planned.runs() instanceof StepRuns.Route) {
            return new EngineAct.Nothing(run.run());
        }
        boolean question = planned.runs() instanceof StepRuns.Question;
        boolean code = planned.runs() instanceof StepRuns.Code;
        boolean search = planned.runs() instanceof StepRuns.Search;
        return switch (planned.producer()) {
            case Producer.Person ignored ->
                question || code || search
                        ? new EngineAct.StartTry(run.run(), planned, step.used() + 1)
                        : new EngineAct.Nothing(run.run());
            case Producer.Model ignored -> {
                if (!question && !search) {
                    yield new EngineAct.Nothing(run.run());
                }
                yield step.newest().map(TryRecord::unsent).orElse(false)
                        ? new EngineAct.Send(
                                run.run(), planned, step.newest().orElseThrow().id())
                        : new EngineAct.StartTry(run.run(), planned, step.used() + 1);
            }
            case Producer.Code ignored ->
                code ? new EngineAct.StartTry(run.run(), planned, step.used() + 1) : new EngineAct.Nothing(run.run());
            case null -> new EngineAct.Nothing(run.run());
        };
    }

    /** The run {@code planned} would start beneath, none where it runs no workflow or its route has chosen no case. */
    private static EngineAct.@Nullable StartBeneath beneath(RunId run, PlannedStep planned, @Nullable Choice choice) {
        if (planned.runs() instanceof StepRuns.Workflow workflow) {
            return new EngineAct.StartBeneath(run, planned, workflow.pinned().version(), null);
        }
        StepRuns.Route.Case selected = choice == null ? null : choice.selected();
        return selected == null
                ? null
                : new EngineAct.StartBeneath(run, planned, selected.target().version(), selected.id());
    }

    /* The run at the top counts as one, so a run started beneath {@code at} would be one more than this counts. */
    private static int depth(RunInTree at) {
        int depth = 1;
        for (RunInTree above = at.above(); above != null; above = above.above()) {
            depth++;
        }
        return depth;
    }

    private static Choice choice(RunInTree at, StepRuns.Route route) {
        StepInputs.BindingRecord bound =
                StepInputs.bound(at, route.discriminator()).orElse(null);
        if (bound == null) {
            return new Choice(false, null, null);
        }
        String said =
                switch (bound.value()) {
                    case JsonValue.JsonString string -> string.value();
                    case JsonValue.JsonNull ignored -> null;
                    default -> throw new IllegalStateException("A route discriminator must hold a term or nothing");
                };
        StepRuns.Route.Case selected = null;
        StepRuns.Route.Case fallback = null;
        for (StepRuns.Route.Case aCase : route.cases()) {
            if (aCase.term() == null) {
                fallback = aCase;
            } else if (aCase.term().equals(said)) {
                selected = aCase;
            }
        }
        return new Choice(true, selected == null ? fallback : selected, said);
    }

    private record Choice(
            boolean standing,
            StepRuns.Route.@Nullable Case selected,
            @Nullable String said) {}

    private static boolean needsReview(StepSnapshot step, StepPosition.AwaitingReview waiting) {
        if (waiting.sending() == ReviewSending.UNSENT) {
            return true;
        }
        if (waiting.sending() != ReviewSending.NO_LONGER_DECLARED || !unreleased(step.planned())) {
            return false;
        }
        ReviewSending sent = ReviewSending.of(step.newest().orElseThrow());
        return sent == ReviewSending.UNSENT || sent == ReviewSending.TURNED_AWAY;
    }

    static boolean unreleased(PlannedStep planned) {
        return (planned.runs() instanceof StepRuns.Code code && code.released() == null)
                || (planned.runs() instanceof StepRuns.Search search && search.released() == null);
    }
}
