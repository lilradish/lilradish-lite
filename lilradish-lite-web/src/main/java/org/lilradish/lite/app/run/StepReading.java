package org.lilradish.lite.app.run;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.log.annotation.DoNotLog;
import org.lilradish.lite.app.filling.FillFieldAnswer;
import org.lilradish.lite.app.pool.PersonAnswer;
import org.lilradish.lite.domain.codestep.CodeError;
import org.lilradish.lite.domain.codestep.CodeStepName;
import org.lilradish.lite.domain.codestep.ReleasedCodeStep;
import org.lilradish.lite.domain.declaration.AskedField;
import org.lilradish.lite.domain.declaration.Asking;
import org.lilradish.lite.domain.declaration.Declaration;
import org.lilradish.lite.domain.declaration.DeclarationSide;
import org.lilradish.lite.domain.declaration.Demand;
import org.lilradish.lite.domain.declaration.Demands;
import org.lilradish.lite.domain.declaration.Field;
import org.lilradish.lite.domain.declaration.FieldName;
import org.lilradish.lite.domain.declaration.FieldShape;
import org.lilradish.lite.domain.declaration.FieldStanding;
import org.lilradish.lite.domain.declaration.OfferedTerms;
import org.lilradish.lite.domain.filling.FillField;
import org.lilradish.lite.domain.identity.GroupPermission;
import org.lilradish.lite.domain.identity.SubjectId;
import org.lilradish.lite.domain.inference.DidNotFitReason;
import org.lilradish.lite.domain.inference.GivenBack;
import org.lilradish.lite.domain.inference.ModelCallPurpose;
import org.lilradish.lite.domain.inference.Unwrapping;
import org.lilradish.lite.domain.model.ModelMode;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.lilradish.lite.domain.run.CallRecord;
import org.lilradish.lite.domain.run.DecisionRecord;
import org.lilradish.lite.domain.run.FailureRecord;
import org.lilradish.lite.domain.run.HoldRecord;
import org.lilradish.lite.domain.run.PlannedStep;
import org.lilradish.lite.domain.run.ProductionId;
import org.lilradish.lite.domain.run.ProductionValueId;
import org.lilradish.lite.domain.run.ReviewRecord;
import org.lilradish.lite.domain.run.ReviewSending;
import org.lilradish.lite.domain.run.RunPayloads;
import org.lilradish.lite.domain.run.RunPositions;
import org.lilradish.lite.domain.run.RunSnapshot;
import org.lilradish.lite.domain.run.RunState;
import org.lilradish.lite.domain.run.RunStepHoldReason;
import org.lilradish.lite.domain.run.RunStepSendAttemptId;
import org.lilradish.lite.domain.run.StepAct;
import org.lilradish.lite.domain.run.StepFailure;
import org.lilradish.lite.domain.run.StepGround;
import org.lilradish.lite.domain.run.StepInputs;
import org.lilradish.lite.domain.run.StepPosition;
import org.lilradish.lite.domain.run.StepPositions;
import org.lilradish.lite.domain.run.StepRuns;
import org.lilradish.lite.domain.run.StepSnapshot;
import org.lilradish.lite.domain.run.StopRecord;
import org.lilradish.lite.domain.run.TryLostReason;
import org.lilradish.lite.domain.run.TryRecord;
import org.lilradish.lite.domain.run.ValueRecord;
import org.lilradish.lite.domain.run.ValueStanding;
import org.lilradish.lite.domain.run.WaitsOn;
import org.lilradish.lite.domain.run.WorkflowStepId;
import org.lilradish.lite.domain.wire.CanonicalJson;
import org.lilradish.lite.domain.wire.JsonValue;
import org.lilradish.lite.domain.workflow.Binding;
import org.lilradish.lite.domain.workflow.BindingSource;
import org.lilradish.lite.domain.workflow.ModelChoice;
import org.lilradish.lite.domain.workflow.Pointer;
import org.lilradish.lite.domain.workflow.Producer;
import org.lilradish.lite.domain.workflow.StepProducer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * One read of a run's steps shaped as its reader is answered, from what was read of the store and nothing else:
 * where each step is, what went in and came out, every try, and what the reader may do. What a model said is
 * withheld from a reader who may not read it, and says that it was.
 */
final class StepReading {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private static final StepAnswers.CostAnswer NO_CALL =
            new StepAnswers.CostAnswer(false, null, null, null, null, null);

    private final RunSnapshot run;

    private final List<StepPosition> positions;

    private final Set<GroupPermission> permitted;

    private final @Nullable SubjectId reader;

    private final StepAnswers.HeaderAnswer header;

    private final Map<String, StepAnswers.DeclaredAnswer> declarations;

    private final Map<ProductionValueId, Produced> produced = new HashMap<>();

    private final Map<SubjectId, PersonAnswer> people;

    private final Map<ProductionId, RunBudget.Spend> spent;

    private final Map<ProductionId, LostRecord> lost;

    private final Map<ProductionId, List<TurnawayRecord>> turnedAway;

    private final boolean readsModels;

    /**
     * @param number the run's number in its group
     * @param raise a raise waiting on the run's ceiling, as the reader stands to it
     * @param people each person {@link #named} names, as a reader reads them
     * @param spent what each try's calls spent between them
     * @param lost what is kept of why each try a model lost was lost, by the try; a try it names nothing of has none
     * @param turnedAway each time a call to produce a try was turned away, by the try, oldest first; one it names
     *     nothing of was never turned away
     */
    StepReading(
            RunSnapshot run,
            int number,
            boolean atTop,
            Set<GroupPermission> permitted,
            @Nullable SubjectId reader,
            Optional<CeilingRaises.Waiting> raise,
            Map<SubjectId, PersonAnswer> people,
            Map<ProductionId, RunBudget.Spend> spent,
            Map<ProductionId, LostRecord> lost,
            Map<ProductionId, List<TurnawayRecord>> turnedAway) {
        this.run = requireNonNull(run, "StepReading run must not be null");
        this.positions = StepPositions.of(run);
        this.permitted = requireNonNull(permitted, "StepReading permitted must not be null");
        this.reader = reader;
        this.readsModels = permitted.contains(GroupPermission.READ_INFERENCE_CONTENT);
        this.people = requireNonNull(people, "StepReading people must not be null");
        this.spent = requireNonNull(spent, "StepReading spent must not be null");
        this.lost = requireNonNull(lost, "StepReading lost must not be null");
        this.turnedAway = requireNonNull(turnedAway, "StepReading turnedAway must not be null");
        RunState state = RunPositions.state(run, positions);
        this.header = new StepAnswers.HeaderAnswer(
                run.run().value(),
                number,
                run.version().value(),
                state.published(),
                RunPositions.at(run, positions)
                        .map(step -> new RunsController.AtAnswer(
                                step.planned().id().value(),
                                step.planned().name().value()))
                        .orElse(null),
                Runs.acts(permitted, atTop, run.stopped(), state, raise).stream()
                        .map(act -> act.published())
                        .toList(),
                new StepAnswers.ProgressAnswer(RunPositions.done(positions), positions.size()));
        this.declarations = declarations(run);
        for (StepSnapshot step : run.steps()) {
            for (TryRecord aTry : step.tries()) {
                for (ValueRecord value : aTry.values()) {
                    produced.put(value.id(), new Produced(aTry, value));
                }
            }
        }
    }

    /** Every person a read of {@code run} names: who asked for, produced and reviewed each try, and who stopped. */
    static Set<SubjectId> named(RunSnapshot run) {
        Set<SubjectId> named = new LinkedHashSet<>();
        StopRecord workflowStopped = run.workflowStopped();
        if (workflowStopped != null) {
            named.add(workflowStopped.by());
        }
        for (StepSnapshot step : run.steps()) {
            StopRecord pinStopped = step.pinStopped();
            if (pinStopped != null) {
                named.add(pinStopped.by());
            }
            for (TryRecord aTry : step.tries()) {
                addNamed(named, aTry.askedBy());
                addNamed(named, aTry.endedBy());
                aTry.reviews().forEach(review -> addNamed(named, review.by()));
            }
        }
        return named;
    }

    /** Whether a model lost any try of {@code run} as not fitting or gone wrong, whose why only the store says. */
    static boolean namesLost(RunSnapshot run) {
        for (StepSnapshot step : run.steps()) {
            for (TryRecord aTry : step.tries()) {
                TryLostReason lost = aTry.lost();
                if (aTry.producer() == StepProducer.MODEL
                        && (lost == TryLostReason.DID_NOT_FIT || lost == TryLostReason.ERRORED)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether any try of {@code run} sent a call, which is all a turnaway is ever of. */
    static boolean namesCalls(RunSnapshot run) {
        for (StepSnapshot step : run.steps()) {
            for (TryRecord aTry : step.tries()) {
                if (!aTry.calls().isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    StepAnswers.RunStepsAnswer steps() {
        List<StepAnswers.StepRowAnswer> rows = new ArrayList<>(run.steps().size());
        for (int index = 0; index < run.steps().size(); index++) {
            rows.add(row(index, true));
        }
        return new StepAnswers.RunStepsAnswer(header, declarations, gaveBack(), rows, rereadAfter());
    }

    /** A step no version of the run holds is refused as none. */
    StepAnswers.StepAnswer step(WorkflowStepId id) {
        int index = indexOf(id);
        StepSnapshot step = run.steps().get(index);
        WentIn wentIn = wentIn(step);
        StepAnswers.StepRowAnswer row = row(index, false);
        return new StepAnswers.StepAnswer(
                header,
                declarations,
                row,
                wentIn == null ? null : wentIn.inputs(),
                wentIn == null ? null : wentIn.from().published(),
                cameOut(step),
                step.tries().stream().map(aTry -> tryAnswer(step, aTry)).toList(),
                row.acts().contains(StepAct.ANSWER.published()) ? answering(step, index) : null,
                rereadAfter());
    }

    private int indexOf(WorkflowStepId id) {
        for (int index = 0; index < run.steps().size(); index++) {
            if (run.steps().get(index).planned().id().equals(id)) {
                return index;
            }
        }
        throw RunRefusal.STEP_NOT_IN_VIEW.raised();
    }

    private @Nullable Integer rereadAfter() {
        return header.state().equals(RunState.RUNNING.published()) ? RunSteps.REREAD_AFTER_SECONDS : null;
    }

    private StepAnswers.GaveBackAnswer gaveBack() {
        if (run.workflow().gives().fields().isEmpty()) {
            return new StepAnswers.GaveBackAnswer("nothing", null);
        }
        List<FillField> gives =
                FillField.of(run.workflow().gives().fields(), run.workflow().lists());
        List<Binding> outputs = new ArrayList<>(run.workflow().outputs());
        outputs.sort((one, other) -> Integer.compare(declaredAt(gives, one), declaredAt(gives, other)));
        List<StepAnswers.GivenValueAnswer> standing = new ArrayList<>();
        for (Binding output : outputs) {
            Pointer target = requireNonNull(output.target(), "an output fills a field");
            Optional<StepInputs.BindingRecord> bound = StepInputs.bound(run, output);
            if (bound.isPresent()) {
                Shown shown = shown(
                        fieldAt(gives, target.names()),
                        bound.get().value(),
                        bound.get().source());
                standing.add(new StepAnswers.GivenValueAnswer(target.published(), shown.value(), shown.withheld()));
            }
        }
        return new StepAnswers.GaveBackAnswer("values", standing);
    }

    private StepAnswers.StepRowAnswer row(int index, boolean withInputs) {
        StepSnapshot step = run.steps().get(index);
        PlannedStep planned = step.planned();
        StepPosition position = positions.get(index);
        StepGround standing = StepGround.of(run, step, position, reader);
        List<String> acts = StepAct.admitted(permitted, standing).stream()
                .map(StepAct::published)
                .toList();
        List<StepAnswers.WithheldAnswer> withheld = new ArrayList<>();
        StepAct.withheld(permitted, standing)
                .forEach((act, refusal) ->
                        withheld.add(StepAnswers.WithheldAnswer.of(act, refusal, standing.codeGivesOtherwise())));
        WentIn wentIn = withInputs && acts.contains(StepAct.REVIEW.published()) ? wentIn(step) : null;
        return new StepAnswers.StepRowAnswer(
                planned.id().value(),
                planned.order(),
                planned.name().value(),
                runs(planned),
                producer(planned),
                reviewer(planned),
                position.state().published(),
                where(step, position),
                takesFrom(planned),
                planned.tries() == null
                        ? null
                        : new StepAnswers.TriesAnswer(
                                step.used(), planned.declaredTries(), step.used() > planned.declaredTries()),
                cost(planned, step.tries()),
                gaveBack(step),
                wentIn == null ? null : wentIn.inputs(),
                wentIn == null ? null : wentIn.from().published(),
                next(position),
                acts,
                withheld);
    }

    private StepAnswers.@Nullable NextAnswer next(StepPosition position) {
        StepPosition.Owed owed = position.nextTry();
        return owed == null ? null : new StepAnswers.NextAnswer(owed.number(), owed.beyond());
    }

    private StepAnswers.@Nullable WhereAnswer where(StepSnapshot step, StepPosition position) {
        WaitsOn on = StepPositions.waitsOn(run, position);
        String waitsOn = on == null ? null : on.published();
        return switch (position) {
            case StepPosition.NotStarted ignored -> null;
            case StepPosition.Done ignored -> null;
            case StepPosition.Running running ->
                new StepAnswers.WhereAnswer(
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
                        running.on().published(),
                        null,
                        null,
                        null,
                        waitsOn,
                        null);
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
                        time(held.since()),
                        stopped(step),
                        turned == null ? null : turnaways(step),
                        waitsOn,
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
                        time(waiting.since()),
                        null,
                        null,
                        waitsOn,
                        waiting.sending() == null ? null : waiting.sending().published());
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
                        time(owed.since()),
                        null,
                        null,
                        waitsOn,
                        null);
            case StepPosition.Failed failed -> {
                FailureReason why = FailureReason.of(failed.why());
                NotHeld notHeld = why == FailureReason.MODEL_NOT_DEPLOYED ? notHeld(step) : null;
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
                        time(failed.since()),
                        failed.why() instanceof StepFailure.Recorded recorded
                                        && recorded.purpose() == ModelCallPurpose.REVIEW
                                ? null
                                : stopped(step),
                        null,
                        waitsOn,
                        null);
            }
        };
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
        return newest == null ? null : turnaways(newest);
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
    private StepAnswers.@Nullable StoppedAnswer stopped(StepSnapshot step) {
        StopRecord stop = run.stoppedFor(step);
        if (stop == null) {
            return null;
        }
        StoppedWhat what = run.workflowStopped() != null ? StoppedWhat.WORKFLOW : StoppedWhat.ENTRY;
        return new StepAnswers.StoppedAnswer(what.published(), person(stop.by()), time(stop.since()));
    }

    private List<StepAnswers.TakesFromAnswer> takesFrom(PlannedStep planned) {
        List<StepAnswers.TakesFromAnswer> taken =
                new ArrayList<>(planned.bindings().size());
        for (Binding binding : planned.bindings()) {
            Pointer target = binding.target();
            if (target != null) {
                taken.add(new StepAnswers.TakesFromAnswer(target.published(), from(binding)));
            }
        }
        return taken;
    }

    private StepAnswers.FromAnswer from(Binding binding) {
        return switch (binding.source()) {
            case BindingSource.WorkflowInput input ->
                new StepAnswers.FromAnswer(
                        FromKind.RUN_INPUT.published(), input.pointer().published(), null, null, null, null);
            case BindingSource.StepOutput output -> {
                PlannedStep source =
                        run.workflow().step(new WorkflowStepId(output.step())).orElseThrow();
                yield new StepAnswers.FromAnswer(
                        FromKind.STEP.published(),
                        output.pointer().published(),
                        output.step(),
                        source.name().value(),
                        switch (source.runs()) {
                            case StepRuns.Question question ->
                                question.pinned().version().value();
                            case StepRuns.Workflow workflow ->
                                workflow.pinned().version().value();
                            case StepRuns.Code ignored -> null;
                            case StepRuns.Route ignored -> null;
                        },
                        source.runs() instanceof StepRuns.Code code ? code.codeStep() : null);
            }
            case BindingSource.Written ignored ->
                new StepAnswers.FromAnswer(FromKind.CONSTANT.published(), null, null, null, null, null);
        };
    }

    private StepAnswers.CostAnswer cost(PlannedStep planned, List<TryRecord> tries) {
        if (!callsAModel(planned)) {
            return NO_CALL;
        }
        RunBudget.Spend total = RunBudget.Spend.NOTHING;
        for (TryRecord aTry : tries) {
            total = total.and(spent.getOrDefault(aTry.id(), RunBudget.Spend.NOTHING));
        }
        return spend(total);
    }

    private static boolean callsAModel(PlannedStep planned) {
        return planned.producer() instanceof Producer.Model || planned.reviewer() != null;
    }

    private static StepAnswers.CostAnswer spend(RunBudget.Spend spend) {
        return new StepAnswers.CostAnswer(
                true,
                Long.toString(spend.sent()),
                Long.toString(spend.cameBack()),
                Long.toString(spend.spent()),
                spend.cameBackUnknown(),
                spend.measuredHere() ? Boolean.TRUE : null);
    }

    private static StepAnswers.RunsAnswer runs(PlannedStep planned) {
        return switch (planned.runs()) {
            case StepRuns.Question question ->
                new StepAnswers.RunsAnswer(
                        "question",
                        question.pinned().entry().value(),
                        question.pinned().name().value(),
                        question.pinned().number(),
                        question.pinned().version().value(),
                        null);
            case StepRuns.Workflow workflow ->
                new StepAnswers.RunsAnswer(
                        "workflow",
                        workflow.pinned().entry().value(),
                        workflow.pinned().name().value(),
                        workflow.pinned().number(),
                        workflow.pinned().version().value(),
                        null);
            case StepRuns.Code code -> new StepAnswers.RunsAnswer("code_step", null, null, null, null, code.codeStep());
            case StepRuns.Route ignored -> new StepAnswers.RunsAnswer("route", null, null, null, null, null);
        };
    }

    private static StepAnswers.@Nullable WhoAnswer producer(PlannedStep planned) {
        Producer producer = planned.producer();
        return switch (producer) {
            case null -> null;
            case Producer.Model model -> model(model.choice());
            case Producer.Person ignored -> new StepAnswers.WhoAnswer("person", null, null, null);
            case Producer.Code ignored -> new StepAnswers.WhoAnswer("code", null, null, null);
        };
    }

    /* Standing above a confidence waits on a person as never standing does: a person's production carries none. */
    private static StepAnswers.@Nullable WhoAnswer reviewer(PlannedStep planned) {
        ModelChoice reviewer = planned.reviewer();
        if (reviewer != null) {
            return model(reviewer);
        }
        Declaration gives = null;
        if (planned.runs() instanceof StepRuns.Question question) {
            gives = question.gives();
        } else if (planned.runs() instanceof StepRuns.Code code) {
            ReleasedCodeStep released = code.released();
            gives = released == null ? null : released.declared().gives();
        }
        if (gives == null) {
            return null;
        }
        for (Field field : gives.fields()) {
            if (!(field.demand() instanceof Demand.Stands stands && stands.standing() == FieldStanding.ALWAYS)) {
                return new StepAnswers.WhoAnswer(StepProducer.PERSON.published(), null, null, null);
            }
        }
        return null;
    }

    private static StepAnswers.WhoAnswer model(ModelChoice choice) {
        ModelMode mode = choice.mode();
        return new StepAnswers.WhoAnswer("model", null, choice.model().value(), mode == null ? null : mode.value());
    }

    private @Nullable List<StepAnswers.ShownValueAnswer> gaveBack(StepSnapshot step) {
        TryRecord newest = step.newest().filter(TryRecord::yielded).orElse(null);
        ValueReading reading = reading(step.planned());
        if (newest == null || reading == null) {
            return null;
        }
        List<ValueRecord> values = reading.values(newest);
        List<StepAnswers.ShownValueAnswer> shown = new ArrayList<>(values.size());
        for (ValueRecord value : values) {
            Shown written = reading.shown(value);
            shown.add(new StepAnswers.ShownValueAnswer(
                    value.field(),
                    written.value(),
                    written.withheld(),
                    ValueStanding.of(newest, value).published(),
                    written.earlierShape()));
        }
        return shown;
    }

    private @Nullable List<StepAnswers.CameOutAnswer> cameOut(StepSnapshot step) {
        ValueReading reading = reading(step.planned());
        if (reading == null || step.tries().stream().noneMatch(TryRecord::yielded)) {
            return null;
        }
        TryRecord newest = step.newest().orElseThrow();
        List<String> fields = reading.fields(newest);
        List<StepAnswers.CameOutAnswer> came = new ArrayList<>(fields.size());
        for (String field : fields) {
            ValueRecord value = newest.yielded() ? valueOf(newest, field) : null;
            if (value == null) {
                came.add(new StepAnswers.CameOutAnswer(field, null, CameOutNow.NONE.published()));
                continue;
            }
            ValueStanding now = ValueStanding.of(newest, value);
            Shown shown = reading.shown(value);
            came.add(new StepAnswers.CameOutAnswer(
                    field,
                    now == ValueStanding.STANDS
                            ? new StepAnswers.StandingAnswer(
                                    newest.number(), shown.value(), shown.withheld(), shown.earlierShape())
                            : null,
                    CameOutNow.of(now).published()));
        }
        return came;
    }

    private StepAnswers.TryAnswer tryAnswer(StepSnapshot step, TryRecord aTry) {
        PlannedStep planned = step.planned();
        ValueReading reading = reading(planned);
        ReviewRecord review = aTry.onReview().orElse(null);
        LostRecord why = lost.get(aTry.id());
        DidNotFitReason misfit = why == null ? null : why.didNotFit();
        return new StepAnswers.TryAnswer(
                aTry.number(),
                aTry.number() > planned.declaredTries(),
                person(aTry.askedBy()),
                producedBy(planned, aTry),
                aTry.explanation(),
                reading == null ? List.of() : triedValues(aTry, reading),
                new StepAnswers.ReviewAnswer(
                        aTry.values().stream().anyMatch(ValueRecord::needsReview),
                        review == null ? null : reviewedBy(planned, review),
                        review != null && review.lost() != null ? Boolean.TRUE : null,
                        review == null || review.lost() == null
                                ? null
                                : review.lost().published(),
                        review == null || review.misfit() == null
                                ? null
                                : review.misfit().published()),
                ended(aTry).published(),
                misfit == null ? null : misfit.published(),
                wentWrong(aTry, why),
                erroredFor(aTry),
                aTry.returned(),
                turnaways(aTry),
                callsAModel(planned) ? spend(spent.getOrDefault(aTry.id(), RunBudget.Spend.NOTHING)) : NO_CALL);
    }

    /*
     * Where the reason is found in what came back, the code gave back something, so none kept is something lost; for
     * any other reason nothing came back to keep, which is not the same absence.
     */
    private static StepAnswers.@Nullable ErroredForAnswer erroredFor(TryRecord aTry) {
        CodeError.Fault fault = aTry.fault();
        if (fault == null) {
            return null;
        }
        CodeError.ReadBy readBy = fault.readBy();
        return new StepAnswers.ErroredForAnswer(
                fault.reason().published(),
                fault.path().isEmpty() ? null : new Pointer(fault.path()).published(),
                fault.member(),
                readBy == null ? null : StepAnswers.ReadByAnswer.of(readBy),
                fault.reason().aboutWhatCameBack() && aTry.returned() == null ? Boolean.TRUE : null);
    }

    /** What code said went wrong, which nobody is kept from; what a model's call said, only where models are read. */
    private StepAnswers.@Nullable WentWrongAnswer wentWrong(TryRecord aTry, @Nullable LostRecord why) {
        String detail = aTry.lostDetail();
        if (detail != null) {
            return new StepAnswers.WentWrongAnswer(detail, aTry.lostDetailCut(), null);
        }
        String said = why == null ? null : why.wentWrong();
        if (said == null) {
            return null;
        }
        return readsModels
                ? new StepAnswers.WentWrongAnswer(said, requireNonNull(why).wentWrongCut(), null)
                : new StepAnswers.WentWrongAnswer(null, null, Boolean.TRUE);
    }

    private @Nullable List<StepAnswers.TurnawayAnswer> turnaways(TryRecord aTry) {
        List<TurnawayRecord> turned = turnedAway.get(aTry.id());
        return turned == null ? null : turned.stream().map(this::turnaway).toList();
    }

    /** What a model said in turning a call away is what a model said, withheld as the rest of it is. */
    private StepAnswers.TurnawayAnswer turnaway(TurnawayRecord turnaway) {
        String said = turnaway.said();
        boolean withheld = said != null && !readsModels;
        return new StepAnswers.TurnawayAnswer(
                time(turnaway.at()),
                withheld ? null : said,
                said == null || withheld ? null : turnaway.saidCut(),
                withheld ? Boolean.TRUE : null,
                turnaway.sentAgain());
    }

    private StepAnswers.WhoAnswer reviewedBy(PlannedStep planned, ReviewRecord review) {
        SubjectId by = review.by();
        if (by != null) {
            return new StepAnswers.WhoAnswer("person", person(by), null, null);
        }
        return model(requireNonNull(planned.reviewer(), "only the model a step names reviews in its stead"));
    }

    private StepAnswers.WhoAnswer producedBy(PlannedStep planned, TryRecord aTry) {
        return switch (aTry.producer()) {
            case PERSON -> new StepAnswers.WhoAnswer("person", person(aTry.endedBy()), null, null);
            case CODE -> new StepAnswers.WhoAnswer("code", null, null, null);
            case MODEL -> {
                if (!(planned.producer() instanceof Producer.Model model)) {
                    throw new IllegalStateException(
                            "A model produced try " + aTry.id().value() + " of a step naming none");
                }
                yield model(model.choice());
            }
        };
    }

    private List<StepAnswers.TriedValueAnswer> triedValues(TryRecord aTry, ValueReading reading) {
        List<StepAnswers.TriedValueAnswer> values =
                new ArrayList<>(aTry.values().size());
        boolean modelSaid = aTry.producer() == StepProducer.MODEL && !readsModels;
        boolean reviewerSaid =
                aTry.onReview().map(review -> review.by() == null).orElse(false) && !readsModels;
        for (ValueRecord value : reading.values(aTry)) {
            Shown shown = reading.shown(value);
            DecisionRecord decision = aTry.onReview()
                    .flatMap(review -> review.decisionOn(value.id()))
                    .orElse(null);
            values.add(new StepAnswers.TriedValueAnswer(
                    value.field(),
                    shown.value(),
                    shown.withheld(),
                    ValueStanding.of(aTry, value).published(),
                    modelSaid ? null : value.confidence(),
                    decision == null
                            ? null
                            : new StepAnswers.DecisionAnswer(
                                    decision.outcome().published(),
                                    reviewerSaid ? null : decision.why(),
                                    reviewerSaid && decision.why() != null ? Boolean.TRUE : null),
                    shown.earlierShape()));
        }
        return values;
    }

    /*
     * Drawn only where answering is admitted, which is judged as the answer itself is: a code step on what its
     * release gives back now alone. It tells nothing, and is answered as that release declares it.
     */
    private StepAnswers.@Nullable AnsweringAnswer answering(StepSnapshot step, int index) {
        ValueReading reading = reading(step.planned());
        List<FillField> gives =
                switch (step.planned().runs()) {
                    case StepRuns.Question question ->
                        FillField.of(question.gives().fields(), question.lists());
                    case StepRuns.Code code -> {
                        ReleasedCodeStep released =
                                requireNonNull(code.released(), "a code step answered here is one the release holds");
                        yield FillField.of(released.declared().gives().fields(), released.lists());
                    }
                    case StepRuns.Workflow ignored -> null;
                    case StepRuns.Route ignored -> null;
                };
        if (gives == null || reading == null) {
            return null;
        }
        String instruction = step.planned().runs() instanceof StepRuns.Question question
                ? question.instruction().value()
                : null;
        StepAnswers.NextAnswer next = requireNonNull(next(positions.get(index)), "an answer fills a try owed");
        TryRecord refused = RunPayloads.refusedInWords(step, next.number()).orElse(null);
        return new StepAnswers.AnsweringAnswer(
                next.number(),
                next.beyond(),
                instruction,
                FillFieldAnswer.of(gives),
                refused == null
                        ? null
                        : new StepAnswers.LastRefusedAnswer(refused.number(), triedValues(refused, reading)));
    }

    /** What went into the step's newest try, or what would go in now where it has none. */
    private @Nullable WentIn wentIn(StepSnapshot step) {
        StepRuns runs = step.planned().runs();
        if (!(runs instanceof StepRuns.Question || runs instanceof StepRuns.Code)) {
            return null;
        }
        TryRecord newest = step.newest().orElse(null);
        if (newest != null) {
            return new WentIn(wentIn(runs, StepInputs.took(run, step, newest)), WentInFrom.TRY);
        }
        Optional<List<StepInputs.BindingRecord>> live = StepInputs.bound(run, step);
        if (live.isEmpty()) {
            return null;
        }
        return new WentIn(wentIn(runs, live.get()), WentInFrom.NOT_YET_SENT);
    }

    private List<StepAnswers.WentInAnswer> wentIn(StepRuns runs, List<StepInputs.BindingRecord> read) {
        List<FillField> takes = runs instanceof StepRuns.Question question
                ? FillField.of(question.takes().fields(), question.lists())
                : null;
        List<StepAnswers.WentInAnswer> inputs = new ArrayList<>(read.size());
        for (StepInputs.BindingRecord bound : read) {
            Pointer target = bound.binding().target();
            if (target != null) {
                Shown shown = runs instanceof StepRuns.Code code
                        ? kept(
                                fitting(code, DeclarationSide.TAKES, target.names(), bound.value()),
                                bound.value(),
                                bound.source())
                        : shown(fieldAt(requireNonNull(takes), target.names()), bound.value(), bound.source());
                inputs.add(new StepAnswers.WentInAnswer(
                        target.published(),
                        from(bound.binding()),
                        shown.value(),
                        shown.withheld(),
                        shown.earlierShape()));
            }
        }
        return inputs;
    }

    private Shown shown(FillField field, JsonValue value, @Nullable ProductionValueId source) {
        if (withheld(source)) {
            return new Shown(null, Boolean.TRUE, null);
        }
        return new Shown(WrittenValues.of(field, value), null, null);
    }

    /* A value its field no longer fits, or of a field no longer declared, is given as kept, as none is its field. */
    private Shown kept(@Nullable FillField field, JsonValue value, @Nullable ProductionValueId source) {
        if (withheld(source)) {
            return new Shown(null, Boolean.TRUE, null);
        }
        if (field != null) {
            return new Shown(WrittenValues.of(field, value), null, null);
        }
        return new Shown(
                value instanceof JsonValue.JsonNull ? NODES.nullNode() : NODES.stringNode(CanonicalJson.write(value)),
                null,
                Boolean.TRUE);
    }

    private boolean withheld(@Nullable ProductionValueId source) {
        Produced from = source == null ? null : produced.get(source);
        return from != null && from.aTry().producer() == StepProducer.MODEL && !readsModels;
    }

    /* A question's values are read as the version it pins declares them; a code step's by the name each was kept
     * under, since its release may have changed what it declares since they were given. */
    private @Nullable ValueReading reading(PlannedStep planned) {
        return switch (planned.runs()) {
            case StepRuns.Question question ->
                new QuestionValues(FillField.of(question.gives().fields(), question.lists()));
            case StepRuns.Code code -> new CodeValues(code);
            case StepRuns.Workflow ignored -> null;
            case StepRuns.Route ignored -> null;
        };
    }

    /**
     * The field at {@code names} as the release declares it now, exactly where {@code value} fits it as code giving it
     * back now would be read: none where none is declared, its list is not here, or the value no longer fits.
     */
    private static @Nullable FillField fitting(
            StepRuns.Code code, DeclarationSide side, List<FieldName> names, JsonValue value) {
        ReleasedCodeStep released = code.released();
        if (released == null) {
            return null;
        }
        Declaration half = side == DeclarationSide.TAKES
                ? released.declared().takes()
                : released.declared().gives();
        Field first = Field.named(half.fields(), names.getFirst());
        if (first == null || !listsHeld(first, released.lists())) {
            return null;
        }
        List<Field> alone = List.of(first);
        AskedField asked = Asking.told(new Declaration(side, Demands.ofQuestion(side), alone), released.lists())
                .getFirst();
        for (FieldName name : names.subList(1, names.size())) {
            asked = asked.fields().stream()
                    .filter(inner -> inner.name().equals(name))
                    .findFirst()
                    .orElse(null);
            if (asked == null) {
                return null;
            }
        }
        JsonValue.JsonObject given = new JsonValue.JsonObject(
                List.of(new JsonValue.JsonMember(asked.name().value(), value)));
        if (!(Unwrapping.given(List.of(asked), given) instanceof GivenBack.Fits)) {
            return null;
        }
        return WrittenValues.at(FillField.of(alone, released.lists()), names);
    }

    private static boolean listsHeld(Field field, Map<EntryVersionId, OfferedTerms> lists) {
        return switch (field.shape()) {
            case FieldShape.Term term -> term.list() == null || lists.containsKey(term.list());
            case FieldShape.Nested nested -> nested.fields().stream().allMatch(inner -> listsHeld(inner, lists));
            case FieldShape.Text ignored -> true;
            case FieldShape.Plain ignored -> true;
        };
    }

    private @Nullable PersonAnswer person(@Nullable SubjectId subject) {
        return subject == null ? null : people.get(subject);
    }

    /*
     * A code step's name, held to CodeStepName, has no hyphen, so it never spells a version's identifier. One is
     * held only where every list it pins is here, since a field is read with its list's terms.
     */
    private static Map<String, StepAnswers.DeclaredAnswer> declarations(RunSnapshot run) {
        Map<String, StepAnswers.DeclaredAnswer> declared = new LinkedHashMap<>();
        declared.put(
                run.version().value().toString(),
                new StepAnswers.DeclaredAnswer(
                        FillFieldAnswer.of(WrittenValues.takesOf(run)),
                        FillFieldAnswer.of(FillField.of(
                                run.workflow().gives().fields(), run.workflow().lists()))));
        for (PlannedStep step : run.workflow().steps()) {
            if (step.runs() instanceof StepRuns.Question question) {
                declared.putIfAbsent(
                        question.pinned().version().value().toString(),
                        new StepAnswers.DeclaredAnswer(
                                FillFieldAnswer.of(FillField.of(question.takes().fields(), question.lists())),
                                FillFieldAnswer.of(FillField.of(question.gives().fields(), question.lists()))));
            } else if (step.runs() instanceof StepRuns.Code code) {
                ReleasedCodeStep released = code.released();
                if (released != null && released.listsMissing().isEmpty()) {
                    declared.putIfAbsent(
                            new CodeStepName(code.codeStep()).value(),
                            new StepAnswers.DeclaredAnswer(
                                    FillFieldAnswer.of(FillField.of(
                                            released.declared().takes().fields(), released.lists())),
                                    FillFieldAnswer.of(FillField.of(
                                            released.declared().gives().fields(), released.lists()))));
                }
            }
        }
        return declared;
    }

    private static List<ValueRecord> inDeclaredOrder(TryRecord aTry, List<FillField> fields) {
        List<ValueRecord> ordered = new ArrayList<>(aTry.values().size());
        for (FillField field : fields) {
            ValueRecord value = valueOf(aTry, field.name().value());
            if (value != null) {
                ordered.add(value);
            }
        }
        return ordered;
    }

    private static @Nullable ValueRecord valueOf(TryRecord aTry, String field) {
        return aTry.values().stream()
                .filter(value -> value.field().equals(field))
                .findFirst()
                .orElse(null);
    }

    private static FillField fieldAt(List<FillField> fields, List<FieldName> names) {
        FillField found = WrittenValues.at(fields, names);
        if (found == null) {
            throw new IllegalStateException("No field declared is at " + names);
        }
        return found;
    }

    private static int declaredAt(List<FillField> fields, Binding output) {
        FieldName first = requireNonNull(output.target(), "an output fills a field")
                .names()
                .getFirst();
        for (int index = 0; index < fields.size(); index++) {
            if (fields.get(index).name().equals(first)) {
                return index;
            }
        }
        return fields.size();
    }

    private static TryEnded ended(TryRecord aTry) {
        if (aTry.open()) {
            return TryEnded.OPEN;
        }
        TryLostReason lost = aTry.lost();
        if (lost != null) {
            return TryEnded.of(lost);
        }
        Set<ValueStanding> standings = new LinkedHashSet<>();
        aTry.values().forEach(value -> standings.add(ValueStanding.of(aTry, value)));
        if (standings.contains(ValueStanding.REFUSED)) {
            return TryEnded.REFUSED_ON_REVIEW;
        }
        if (standings.contains(ValueStanding.REFUSED_FOR_LENGTH)) {
            return TryEnded.REFUSED_FOR_LENGTH;
        }
        return standings.contains(ValueStanding.WAITING_ON_REVIEW) ? TryEnded.WAITING : TryEnded.STANDS;
    }

    private static void addNamed(Set<SubjectId> named, @Nullable SubjectId subject) {
        if (subject != null) {
            named.add(subject);
        }
    }

    private static String time(Instant at) {
        return at.toString();
    }

    /** A value made in this run, and the try it was made in. */
    private record Produced(TryRecord aTry, ValueRecord value) {}

    /**
     * @param value none where it is withheld
     * @param earlierShape present where it is given as kept, being of a shape its field is no longer declared with
     */
    private record Shown(
            @Nullable JsonNode value,
            @Nullable Boolean withheld,
            @Nullable Boolean earlierShape) {}

    private record WentIn(List<StepAnswers.WentInAnswer> inputs, WentInFrom from) {}

    // Both names end in Record, a suffix AppLoggingConvention matches: without that, @DoNotLog does nothing.
    /**
     * What the store keeps of why a model lost a try.
     *
     * @param didNotFit why its answer did not fit, where it did not
     * @param wentWrong what its call said went wrong, where it did, cut to its bound where {@code wentWrongCut}
     */
    record LostRecord(
            @Nullable DidNotFitReason didNotFit,
            @DoNotLog @Nullable String wentWrong,
            boolean wentWrongCut) {}

    /**
     * One time a model turned away a call to produce a try, as the store keeps it.
     *
     * @param attempt the attempt whose call it was
     * @param said what the model said, where it said anything, cut to its bound where {@code saidCut}
     * @param sentAgain whether the call was sent again after it
     */
    record TurnawayRecord(
            RunStepSendAttemptId attempt,
            Instant at,
            @DoNotLog @Nullable String said,
            boolean saidCut,
            boolean sentAgain) {}

    /** The model a step failed for not being held, and whether it was the one to review rather than to produce. */
    private record NotHeld(ModelChoice choice, boolean reviewing) {}

    /** How the values a step's tries gave back are read. */
    private interface ValueReading {

        /** Each field what came out is said of, a newest try that gave nothing back giving each as none. */
        List<String> fields(TryRecord newest);

        /** What {@code aTry} gave back, in the order read. */
        List<ValueRecord> values(TryRecord aTry);

        Shown shown(ValueRecord value);
    }

    private final class QuestionValues implements ValueReading {

        private final List<FillField> gives;

        QuestionValues(List<FillField> gives) {
            this.gives = gives;
        }

        @Override
        public List<String> fields(TryRecord newest) {
            return gives.stream().map(field -> field.name().value()).toList();
        }

        @Override
        public List<ValueRecord> values(TryRecord aTry) {
            return inDeclaredOrder(aTry, gives);
        }

        @Override
        public Shown shown(ValueRecord value) {
            return StepReading.this.shown(
                    fieldAt(gives, List.of(new FieldName(value.field()))), value.value(), value.id());
        }
    }

    /** A code step's values by the name each was kept under, which the snapshot puts in the release's order. */
    private final class CodeValues implements ValueReading {

        private final StepRuns.Code code;

        CodeValues(StepRuns.Code code) {
            this.code = code;
        }

        @Override
        public List<String> fields(TryRecord newest) {
            if (newest.yielded()) {
                return newest.values().stream().map(ValueRecord::field).toList();
            }
            ReleasedCodeStep released = code.released();
            return released == null
                    ? List.of()
                    : released.declared().gives().fields().stream()
                            .map(field -> field.name().value())
                            .toList();
        }

        @Override
        public List<ValueRecord> values(TryRecord aTry) {
            return aTry.values();
        }

        @Override
        public Shown shown(ValueRecord value) {
            return kept(
                    fitting(code, DeclarationSide.GIVES, List.of(new FieldName(value.field())), value.value()),
                    value.value(),
                    value.id());
        }
    }
}
