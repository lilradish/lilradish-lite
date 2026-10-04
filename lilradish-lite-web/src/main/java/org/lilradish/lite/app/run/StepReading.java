package org.lilradish.lite.app.run;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
import org.lilradish.lite.domain.declaration.FieldKind;
import org.lilradish.lite.domain.declaration.FieldName;
import org.lilradish.lite.domain.declaration.FieldShape;
import org.lilradish.lite.domain.declaration.FieldStanding;
import org.lilradish.lite.domain.declaration.OfferedTerms;
import org.lilradish.lite.domain.file.StoredFile;
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
import org.lilradish.lite.domain.run.InputRecord;
import org.lilradish.lite.domain.run.PlannedStep;
import org.lilradish.lite.domain.run.ProductionId;
import org.lilradish.lite.domain.run.ProductionValueId;
import org.lilradish.lite.domain.run.ReviewRecord;
import org.lilradish.lite.domain.run.ReviewSending;
import org.lilradish.lite.domain.run.RunInTree;
import org.lilradish.lite.domain.run.RunPayloads;
import org.lilradish.lite.domain.run.RunPositions;
import org.lilradish.lite.domain.run.RunSnapshot;
import org.lilradish.lite.domain.run.RunState;
import org.lilradish.lite.domain.run.RunStepHoldReason;
import org.lilradish.lite.domain.run.RunStepSendAttemptId;
import org.lilradish.lite.domain.run.RunningOn;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * One read of a run's steps shaped as its reader is answered, from what was read of the store and nothing else:
 * where each step is, what went in and came out, every try, and what the reader may do. What a model said is
 * withheld from a reader who may not read it, and says that it was.
 */
final class StepReading {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private static final JsonValue.JsonObject WITHHELD_HISTORICAL =
            new JsonValue.JsonObject(List.of(new JsonValue.JsonMember("withheld", new JsonValue.JsonBoolean(true))));

    private static final StepAnswers.CostAnswer NO_CALL =
            new StepAnswers.CostAnswer(false, null, null, null, null, null);

    private final RunSnapshot run;

    private final RunInTree context;

    private final List<StepPosition> positions;

    private final Set<GroupPermission> permitted;

    private final @Nullable SubjectId reader;

    private final StepAnswers.HeaderAnswer header;

    private final Map<String, StepAnswers.DeclaredAnswer> declarations;

    private final Map<ProductionValueId, Produced> produced = new HashMap<>();

    private boolean indexedTree;

    private final Map<org.lilradish.lite.domain.run.RunId, RunState> childStates = new HashMap<>();

    private final Map<org.lilradish.lite.domain.run.RunId, List<StepAnswers.CameOutAnswer>> childOutputs =
            new HashMap<>();

    private final Map<SubjectId, PersonAnswer> people;

    private final Map<ProductionId, RunBudget.Spend> spent;

    private final Map<ProductionId, LostRecord> lost;

    private final Map<ProductionId, List<TurnawayRecord>> turnedAway;

    private Map<ProductionId, DeclinedRecord> productionDeclines = Map.of();

    private Map<ProductionId, DeclinedRecord> reviewDeclines = Map.of();

    private Map<ProductionId, UnreadableRecord> productionUnreadable = Map.of();

    private Map<ProductionId, UnreadableRecord> reviewUnreadable = Map.of();

    private final boolean readsModels;

    private final RunDeployment deployment;

    private @Nullable RunReadDetails details;

    private Map<ProductionId, List<StepAnswers.KeptCodeFileAnswer>> keptFiles = Map.of();

    private Map<UUID, StoredFile> heldFiles = Map.of();
    private Map<ProductionId, StepAnswers.SearchAnswer> searches = Map.of();
    private @Nullable String sentSearchQuestion;
    private @Nullable String heldSearchQuestion;
    private @Nullable WorkflowStepId searchDetail;

    private Map<org.lilradish.lite.domain.run.RunId, RunBudget.Spend> beneathCost = Map.of();

    /**
     * @param number the run's number in its group
     * @param raise a raise waiting on the run's ceiling, as the reader stands to it
     * @param people each person {@link #named} names, as a reader reads them
     * @param spent what each try's calls spent between them
     * @param lost what is kept of why each try a model lost was lost, by the try; a try it names nothing of has none
     * @param turnedAway each time a call to produce a try was turned away, by the try, oldest first; one it names
     *     nothing of was never turned away
     * @param deployment the deployment as it stands now, whose depth a step failed for going too deep is read with
     */
    StepReading(
            RunInTree context,
            int number,
            boolean atTop,
            Set<GroupPermission> permitted,
            @Nullable SubjectId reader,
            Optional<CeilingRaises.Waiting> raise,
            Map<SubjectId, PersonAnswer> people,
            Map<ProductionId, RunBudget.Spend> spent,
            Map<ProductionId, LostRecord> lost,
            Map<ProductionId, List<TurnawayRecord>> turnedAway,
            RunDeployment deployment) {
        this.context = requireNonNull(context, "StepReading context must not be null");
        this.deployment = requireNonNull(deployment, "StepReading deployment must not be null");
        RunSnapshot run = context.run();
        this.run = run;
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
        if (!readsModels) {
            rememberOwn(run);
        }
    }

    StepReading withSearch(
            Map<ProductionId, StepAnswers.SearchAnswer> searches,
            @Nullable String sentQuestion,
            @Nullable String heldQuestion,
            WorkflowStepId detail) {
        this.searches = Map.copyOf(searches);
        this.sentSearchQuestion = sentQuestion;
        this.heldSearchQuestion = heldQuestion;
        this.searchDetail = detail;
        return this;
    }

    StepReading withKeptFiles(Map<ProductionId, List<StepAnswers.KeptCodeFileAnswer>> keptFiles) {
        this.keptFiles = Map.copyOf(keptFiles);
        return this;
    }

    StepReading withDeclined(
            Map<ProductionId, DeclinedRecord> productionDeclines, Map<ProductionId, DeclinedRecord> reviewDeclines) {
        this.productionDeclines = Map.copyOf(productionDeclines);
        this.reviewDeclines = Map.copyOf(reviewDeclines);
        return this;
    }

    StepReading withUnreadable(
            Map<ProductionId, UnreadableRecord> productionUnreadable,
            Map<ProductionId, UnreadableRecord> reviewUnreadable) {
        this.productionUnreadable = Map.copyOf(productionUnreadable);
        this.reviewUnreadable = Map.copyOf(reviewUnreadable);
        return this;
    }

    StepReading withHeldFiles(Map<UUID, StoredFile> heldFiles) {
        this.heldFiles = Map.copyOf(heldFiles);
        return this;
    }

    StepReading withDetails(RunReadDetails details) {
        this.details = requireNonNull(details, "StepReading details must not be null");
        this.beneathCost = details.costBeneath(run.steps());
        return this;
    }

    private void rememberOwn(RunSnapshot current) {
        for (StepSnapshot step : current.steps()) {
            for (TryRecord aTry : step.tries()) {
                for (ValueRecord value : aTry.values()) {
                    produced.put(value.id(), new Produced(aTry, value));
                }
            }
        }
    }

    private void rememberTree(RunSnapshot current) {
        rememberOwn(current);
        for (StepSnapshot step : current.steps()) {
            if (step.beneath() != null) {
                rememberTree(step.beneath().run());
            }
        }
    }

    static List<RunSnapshot> visibleRuns(RunSnapshot run) {
        List<RunSnapshot> visible = new ArrayList<>();
        visible.add(run);
        for (StepPosition position : StepPositions.of(run)) {
            while (position instanceof StepPosition.Beneath beneath) {
                visible.add(beneath.run());
                position = beneath.where();
            }
        }
        return visible;
    }

    static List<ProductionId> visibleProductions(RunSnapshot run) {
        List<ProductionId> productions = new ArrayList<>();
        for (StepPosition position : StepPositions.of(run)) {
            while (position instanceof StepPosition.Beneath beneath) {
                TryRecord newest = beneath.at().newest().orElse(null);
                if (newest != null && !newest.calls().isEmpty()) {
                    productions.add(newest.id());
                }
                position = beneath.where();
            }
        }
        return productions;
    }

    /** A child stop can name the person shown in its parent step's whereabouts. */
    static Set<SubjectId> named(RunSnapshot run) {
        Set<SubjectId> named = new LinkedHashSet<>();
        addNamed(run, named);
        for (StepPosition position : StepPositions.of(run)) {
            while (position instanceof StepPosition.Beneath beneath) {
                StopRecord stopped = beneath.run().stoppedFor(beneath.at());
                if (stopped != null) {
                    named.add(stopped.by());
                }
                position = beneath.where();
            }
        }
        return named;
    }

    private static void addNamed(RunSnapshot run, Set<SubjectId> named) {
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
            rows.add(row(index, true, null));
        }
        return new StepAnswers.RunStepsAnswer(header, declarations, gaveBack(), rows, rereadAfter());
    }

    /** A step no version of the run holds is refused as none. */
    StepAnswers.StepAnswer step(WorkflowStepId id) {
        int index = indexOf(id);
        StepSnapshot step = run.steps().get(index);
        WentIn wentIn = wentIn(index, step);
        List<StepAnswers.CameOutAnswer> cameOut = cameOut(step);
        StepAnswers.StepRowAnswer row = row(index, false, cameOut);
        return new StepAnswers.StepAnswer(
                header,
                declarations,
                row,
                wentIn == null ? null : wentIn.inputs(),
                wentIn == null ? null : wentIn.from().published(),
                cameOut,
                step.tries().stream()
                        .filter(aTry -> aTry.number() != null)
                        .map(aTry -> tryAnswer(step, aTry))
                        .toList(),
                row.acts().contains(StepAct.ANSWER.published()) ? answering(step, index) : null,
                null,
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
            Optional<StepInputs.BindingRecord> bound = StepInputs.bound(context, output);
            if (bound.isPresent()) {
                Shown shown = shown(
                        fieldAt(gives, target.names()),
                        bound.get().value(),
                        bound.get().sources(),
                        bound.get().parts());
                standing.add(new StepAnswers.GivenValueAnswer(target.published(), shown.value(), shown.withheld()));
            }
        }
        return new StepAnswers.GaveBackAnswer("values", standing);
    }

    private StepAnswers.StepRowAnswer row(
            int index, boolean withInputs, @Nullable List<StepAnswers.CameOutAnswer> cameOut) {
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
        WentIn wentIn = withInputs && acts.contains(StepAct.REVIEW.published()) ? wentIn(index, step) : null;
        return new StepAnswers.StepRowAnswer(
                planned.id().value(),
                planned.order(),
                planned.name().value(),
                runs(planned),
                beneath(step),
                producer(planned),
                reviewer(planned),
                position.state().published(),
                where(run, step, position),
                takesFrom(planned),
                planned.tries() == null
                        ? null
                        : new StepAnswers.TriesAnswer(
                                step.used(), planned.declaredTries(), step.used() > planned.declaredTries()),
                step.beneath() == null || details == null ? cost(planned, step.tries()) : costBeneath(step),
                gaveBack(step, cameOut),
                wentIn == null ? null : wentIn.inputs(),
                wentIn == null ? null : wentIn.from().published(),
                next(position),
                acts,
                withheld);
    }

    private StepAnswers.@Nullable BeneathAnswer beneath(StepSnapshot step) {
        RunReadDetails read = details;
        if (step.beneath() == null || read == null) {
            return null;
        }
        RunReadDetails.Beneath child = requireNonNull(read.beneath(step));
        RunReadDetails.Workflow workflow = child.workflow();
        RunReadDetails.At at = child.at();
        return new StepAnswers.BeneathAnswer(
                child.run().run().value(),
                child.run().number(),
                new StepAnswers.RunsAnswer(
                        "workflow",
                        workflow.entryId(),
                        workflow.name(),
                        workflow.version(),
                        workflow.versionId(),
                        null,
                        null,
                        null),
                at == null
                        ? null
                        : new StepAnswers.BeneathAtAnswer(
                                at.run().run().value(), at.run().number(), at.stepId(), at.name()));
    }

    private StepAnswers.CostAnswer costBeneath(StepSnapshot step) {
        RunBudget.Spend cost =
                beneathCost.get(requireNonNull(step.beneath()).run().run());
        if (cost == null) {
            throw new IllegalStateException("A child run has no cost in this read");
        }
        return cost.totalCalls() == 0 ? NO_CALL : spend(cost);
    }

    private StepAnswers.@Nullable NextAnswer next(StepPosition position) {
        StepPosition.Owed owed = position.nextTry();
        return owed == null ? null : new StepAnswers.NextAnswer(owed.number(), owed.beyond());
    }

    private StepAnswers.@Nullable WhereAnswer where(RunSnapshot current, StepSnapshot step, StepPosition position) {
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
                        time(held.since()),
                        stopped(current, step),
                        turned == null ? null : turnaways(step),
                        waitsOn,
                        null,
                        null,
                        null,
                        null,
                        readsModels && isHeldSearchDetail(step, held)
                                ? requireNonNull(heldSearchQuestion, "A held search attempt has its question")
                                : null,
                        !readsModels && isHeldSearchDetail(step, held) ? Boolean.TRUE : null,
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
                        time(waiting.since()),
                        null,
                        null,
                        waitsOn,
                        waiting.sending() == null ? null : waiting.sending().published(),
                        null,
                        null,
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
                        time(owed.since()),
                        null,
                        null,
                        waitsOn,
                        null,
                        null,
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
                        time(failed.since()),
                        failed.why() instanceof StepFailure.Recorded recorded
                                        && recorded.purpose() == ModelCallPurpose.REVIEW
                                ? null
                                : stopped(current, step),
                        null,
                        waitsOn,
                        null,
                        why == FailureReason.UNCLAIMED_VALUE ? unclaimed(current, step) : null,
                        why == FailureReason.TOO_DEEP
                                ? new StepAnswers.TooDeepAnswer(Integer.toString(deployment.maximumDepth()))
                                : null,
                        why == FailureReason.TOOL_NOT_OFFERED
                                ? StepPositions.inForce(step).orElseThrow().tool()
                                : null,
                        null,
                        null,
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

    private StepAnswers.SourceNoLongerMatchesAnswer failedSource(StepSnapshot step) {
        FailureRecord.SourceRecord failed =
                requireNonNull(StepPositions.inForce(step).orElseThrow().source(), "A source failure names its source");
        return new StepAnswers.SourceNoLongerMatchesAnswer(source(failed.maker()), failed.field());
    }

    private StepAnswers.HeldSourceAnswer source(HoldRecord.SourceRecord source) {
        RunInTree root = context;
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
        int number = source.run().equals(run.run())
                ? header.number()
                : requireNonNull(details, "A cross-run held source has its tree's read details")
                        .numbered(source.run())
                        .number();
        return new StepAnswers.HeldSourceAnswer(
                source.run().value(),
                number,
                source.step(),
                sourceStep.planned().name().value(),
                code.codeStep());
    }

    private boolean isHeldSearchDetail(StepSnapshot step, StepPosition.HeldBack held) {
        return step.planned().id().equals(searchDetail)
                && step.planned().runs() instanceof StepRuns.Search
                && (held.reason() == RunStepHoldReason.TOO_LONG || held.reason() == RunStepHoldReason.TURNED_AWAY);
    }

    private String answeringSearchQuestion(StepRuns.Search search) {
        if (sentSearchQuestion != null) {
            return sentSearchQuestion;
        }
        return org.lilradish.lite.domain.workflow.SearchQuestion.asSent(search.question(), read -> {
            StepInputs.ReadRecord value = StepInputs.bound(context, read)
                    .orElseThrow(
                            () -> new IllegalStateException("An answering search reads a value that does not stand"));
            JsonValue given = value.value();
            if (given instanceof JsonValue.JsonNull) {
                return null;
            }
            FillField field = searchSourceField(read.source(), searchSourcePath(read.source()), given);
            if (field != null
                    && (field.kind() == FieldKind.TEXT || field.kind() == FieldKind.TERM)
                    && given instanceof JsonValue.JsonString text) {
                return text.value();
            }
            return CanonicalJson.write(given);
        });
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
        if (term == null || readsModels) {
            return new StepAnswers.UnclaimedAnswer.Named(term);
        }
        StepRuns.Route route = (StepRuns.Route) step.planned().runs();
        RunInTree at = context.find(current.run())
                .orElseThrow(
                        () -> new IllegalStateException("Run " + current.run().value()
                                + " is not in the tree read from " + run.run().value()));
        StepInputs.BindingRecord chosenBy = StepInputs.bound(at, route.discriminator())
                .orElseThrow(() -> new IllegalStateException(
                        "Route " + step.planned().name().value() + " failed on a value that does not stand"));
        return withheld(chosenBy.sources())
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
    private StepAnswers.@Nullable StoppedAnswer stopped(RunSnapshot current, StepSnapshot step) {
        StopRecord stop = current.stoppedFor(step);
        if (stop == null) {
            return null;
        }
        StoppedWhat what = current.workflowStopped() != null ? StoppedWhat.WORKFLOW : StoppedWhat.ENTRY;
        return new StepAnswers.StoppedAnswer(what.published(), person(stop.by()), time(stop.since()));
    }

    private List<StepAnswers.TakesFromAnswer> takesFrom(PlannedStep planned) {
        if (planned.runs() instanceof StepRuns.Search search) {
            return search.question().stream()
                    .filter(org.lilradish.lite.domain.workflow.SearchQuestionPart.Read.class::isInstance)
                    .map(org.lilradish.lite.domain.workflow.SearchQuestionPart.Read.class::cast)
                    .map(read -> new StepAnswers.TakesFromAnswer(
                            searchSourcePath(read.source()).published(), from(read.source()), read.id()))
                    .toList();
        }
        List<StepAnswers.TakesFromAnswer> taken =
                new ArrayList<>(planned.bindings().size());
        for (Binding binding : planned.bindings()) {
            Pointer target = binding.target();
            if (target != null) {
                taken.add(new StepAnswers.TakesFromAnswer(target.published(), from(binding), null));
            }
        }
        return taken;
    }

    private StepAnswers.FromAnswer from(Binding binding) {
        return from(binding.source());
    }

    private StepAnswers.FromAnswer from(BindingSource bindingSource) {
        return switch (bindingSource) {
            case BindingSource.WorkflowInput input ->
                new StepAnswers.FromAnswer(
                        FromKind.RUN_INPUT.published(),
                        input.pointer().published(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
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
                            case StepRuns.Search ignored -> null;
                            case StepRuns.Route ignored -> null;
                        },
                        source.runs() instanceof StepRuns.Code code ? code.codeStep() : null,
                        null,
                        null,
                        source.runs() instanceof StepRuns.Search search ? search.way() : null);
            }
            case BindingSource.Written ignored ->
                new StepAnswers.FromAnswer(
                        FromKind.CONSTANT.published(), null, null, null, null, null, null, null, null);
        };
    }

    private StepAnswers.FromAnswer from(RunReadDetails.OriginRecord origin) {
        return from(requireNonNull(details, "A source needs its read details"), origin);
    }

    static StepAnswers.FromAnswer from(RunReadDetails read, RunReadDetails.OriginRecord origin) {
        RunReadDetails.NumberedRunRecord numbered = origin.run();
        RunReadDetails.MakerRecord maker = origin.maker();
        if (maker == null) {
            return new StepAnswers.FromAnswer(
                    origin.kind(),
                    origin.path(),
                    null,
                    null,
                    null,
                    null,
                    numbered == null ? null : numbered.run().value(),
                    numbered == null ? null : numbered.number(),
                    null);
        }
        PlannedStep source = read.planned(maker);
        return new StepAnswers.FromAnswer(
                FromKind.STEP.published(),
                maker.field(),
                maker.stepId(),
                maker.name(),
                switch (source.runs()) {
                    case StepRuns.Question question ->
                        question.pinned().version().value();
                    case StepRuns.Workflow workflow ->
                        workflow.pinned().version().value();
                    case StepRuns.Code ignored -> null;
                    case StepRuns.Search ignored -> null;
                    case StepRuns.Route ignored -> null;
                },
                source.runs() instanceof StepRuns.Code code ? code.codeStep() : null,
                maker.run().run().value(),
                maker.run().number(),
                source.runs() instanceof StepRuns.Search search ? search.way() : null);
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
                spend.measuredHere() == null ? null : spend.measuredHere().published());
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
                        null,
                        null,
                        null);
            case StepRuns.Workflow workflow ->
                new StepAnswers.RunsAnswer(
                        "workflow",
                        workflow.pinned().entry().value(),
                        workflow.pinned().name().value(),
                        workflow.pinned().number(),
                        workflow.pinned().version().value(),
                        null,
                        null,
                        null);
            case StepRuns.Code code ->
                new StepAnswers.RunsAnswer("code_step", null, null, null, null, code.codeStep(), null, null);
            case StepRuns.Search search ->
                new StepAnswers.RunsAnswer(
                        "search",
                        null,
                        null,
                        null,
                        null,
                        null,
                        search.way(),
                        search.released() == null
                                ? null
                                : search.released().requiredTool().value());
            case StepRuns.Route ignored ->
                new StepAnswers.RunsAnswer("route", null, null, null, null, null, null, null);
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

    private @Nullable List<StepAnswers.ShownValueAnswer> gaveBack(
            StepSnapshot step, @Nullable List<StepAnswers.CameOutAnswer> cameOut) {
        if (step.beneath() != null && details != null) {
            RunSnapshot child = step.beneath().run();
            if (childStates.computeIfAbsent(child.run(), ignored -> RunPositions.state(child, StepPositions.of(child)))
                    != RunState.DONE) {
                return null;
            }
            List<StepAnswers.ShownValueAnswer> shown = new ArrayList<>();
            for (StepAnswers.CameOutAnswer output : cameOut == null ? cameOutBeneath(step) : cameOut) {
                StepAnswers.StandingAnswer standing = output.standing();
                if (standing != null) {
                    shown.add(new StepAnswers.ShownValueAnswer(
                            output.field(),
                            standing.value(),
                            standing.withheld(),
                            CameOutNow.STANDS.published(),
                            standing.earlierShape()));
                }
            }
            return shown.isEmpty() ? null : List.copyOf(shown);
        }
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
        if (step.beneath() != null) {
            return cameOutBeneath(step);
        }
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
                came.add(new StepAnswers.CameOutAnswer(field, null, CameOutNow.NONE.published(), null, null, null));
                continue;
            }
            ValueStanding now = ValueStanding.of(newest, value);
            Shown shown = reading.shown(value);
            came.add(new StepAnswers.CameOutAnswer(
                    field,
                    now == ValueStanding.STANDS
                            ? new StepAnswers.StandingAnswer(
                                    requireNonNull(newest.number()),
                                    shown.value(),
                                    shown.withheld(),
                                    shown.earlierShape())
                            : null,
                    CameOutNow.of(now).published(),
                    null,
                    null,
                    null));
        }
        return came;
    }

    private List<StepAnswers.CameOutAnswer> cameOutBeneath(StepSnapshot step) {
        RunInTree child = context.beneath(step);
        return childOutputs.computeIfAbsent(child.run().run(), ignored -> cameOutBeneath(child));
    }

    private List<StepAnswers.CameOutAnswer> cameOutBeneath(RunInTree child) {
        Declaration gives = child.run().workflow().gives();
        List<Binding> outputs = child.run().workflow().outputs();
        List<StepInputs.BindingRecord> bound = new ArrayList<>();
        Map<FieldName, List<Binding>> outputsByField = new HashMap<>();
        Map<FieldName, List<StepInputs.BindingRecord>> boundByField = new HashMap<>();
        Set<UUID> boundIds = new HashSet<>();
        for (Binding output : outputs) {
            FieldName first = requireNonNull(output.target()).names().getFirst();
            outputsByField.computeIfAbsent(first, ignored -> new ArrayList<>()).add(output);
            StepInputs.bound(child, output).ifPresent(read -> {
                bound.add(read);
                boundIds.add(output.id());
                boundByField
                        .computeIfAbsent(first, ignored -> new ArrayList<>())
                        .add(read);
            });
        }
        JsonValue.JsonObject values = StepInputs.filled(gives, bound);
        List<FillField> fields =
                FillField.of(gives.fields(), child.run().workflow().lists());
        List<StepAnswers.CameOutAnswer> came = new ArrayList<>(fields.size());
        for (FillField field : fields) {
            List<Binding> forField = outputsByField.getOrDefault(field.name(), List.of());
            if (forField.isEmpty() || forField.stream().anyMatch(output -> !boundIds.contains(output.id()))) {
                came.add(new StepAnswers.CameOutAnswer(
                        field.name().value(), null, CameOutNow.NONE.published(), null, null, null));
                continue;
            }
            List<StepInputs.BindingRecord> fieldBound = boundByField.getOrDefault(field.name(), List.of());
            List<ProductionValueId> sources = new ArrayList<>();
            for (StepInputs.BindingRecord read : fieldBound) {
                sources.addAll(read.sources());
            }
            JsonValue value = StepInputs.within(values, List.of(field.name()));
            Shown shown = shown(field, value, sources, partsOf(fieldBound));
            StepInputs.BindingRecord single = fieldBound.size() == 1
                            && requireNonNull(fieldBound.getFirst().binding().target())
                                            .names()
                                            .size()
                                    == 1
                            && fieldBound.getFirst().parts().isEmpty()
                    ? fieldBound.getFirst()
                    : null;
            RunReadDetails read = requireNonNull(details, "A child output needs its read details");
            ProductionValueId source = single == null ? null : single.source();
            RunReadDetails.MakerRecord maker = source == null ? null : read.maker(source);
            Integer number =
                    maker == null ? null : requireNonNull(maker.tryNumber(), "A standing value has a numbered try");
            came.add(new StepAnswers.CameOutAnswer(
                    field.name().value(),
                    new StepAnswers.StandingAnswer(number, shown.value(), shown.withheld(), null),
                    CameOutNow.STANDS.published(),
                    maker == null
                            ? null
                            : new RunsController.NumberedRunAnswer(
                                    maker.run().run().value(), maker.run().number()),
                    single == null ? null : from(read.origin(child, single)),
                    single == null ? outputSources(child, gives, field, fieldBound) : null));
        }
        return List.copyOf(came);
    }

    private List<StepAnswers.SourceAnswer> outputSources(
            RunInTree child, Declaration gives, FillField field, List<StepInputs.BindingRecord> bound) {
        RunReadDetails read = requireNonNull(details, "A child output needs its read details");
        List<RunReadDetails.PartSourceRecord> parts = new ArrayList<>();
        for (StepInputs.BindingRecord each : bound) {
            List<FieldName> target = requireNonNull(each.binding().target()).names();
            String prefix = target.size() == 1 ? "" : new Pointer(target.subList(1, target.size())).published();
            if (each.parts().isEmpty()) {
                parts.add(new RunReadDetails.PartSourceRecord(prefix, read.origin(child, each)));
            } else {
                for (RunReadDetails.PartSourceRecord part : read.parts(child, gives, each.binding(), each.parts())) {
                    String path = prefix.isEmpty() ? part.targetPath() : prefix + "." + part.targetPath();
                    parts.add(new RunReadDetails.PartSourceRecord(path, part.from()));
                }
            }
        }
        return RunReadDetails.orderedParts(gives, field.name().value(), parts).stream()
                .map(part -> new StepAnswers.SourceAnswer(part.targetPath(), from(part.from())))
                .toList();
    }

    private StepAnswers.TryAnswer tryAnswer(StepSnapshot step, TryRecord aTry) {
        PlannedStep planned = step.planned();
        ValueReading reading = reading(planned);
        ReviewRecord review = aTry.onReview().orElse(null);
        LostRecord why = lost.get(aTry.id());
        DidNotFitReason misfit = why == null ? null : why.didNotFit();
        return new StepAnswers.TryAnswer(
                requireNonNull(aTry.number()),
                requireNonNull(aTry.number()) > planned.declaredTries(),
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
                                : review.misfit().published(),
                        declined(review == null ? null : review.misfit(), reviewDeclines.get(aTry.id())),
                        unreadable(reviewUnreadable.get(aTry.id()))),
                ended(aTry).published(),
                misfit == null ? null : misfit.published(),
                wentWrong(aTry, why),
                erroredFor(aTry),
                aTry.returned(),
                keptFiles.get(aTry.id()),
                turnaways(aTry),
                callsAModel(planned) ? spend(spent.getOrDefault(aTry.id(), RunBudget.Spend.NOTHING)) : NO_CALL,
                planned.runs() instanceof StepRuns.Search && aTry.producer() == StepProducer.MODEL
                        ? searches.get(aTry.id())
                        : null,
                declined(misfit, productionDeclines.get(aTry.id())),
                unreadable(productionUnreadable.get(aTry.id())));
    }

    private StepAnswers.@Nullable UnreadableResponseRecord unreadable(@Nullable UnreadableRecord kept) {
        if (kept == null || kept.text().isEmpty()) {
            return null;
        }
        return new StepAnswers.UnreadableResponseRecord(kept.text(), kept.altered(), kept.truncated());
    }

    private StepAnswers.@Nullable DeclinedAnswer declined(
            @Nullable DidNotFitReason reason, @Nullable DeclinedRecord kept) {
        if (reason != DidNotFitReason.MODEL_DECLINED) {
            return null;
        }
        if (!readsModels) {
            return new StepAnswers.DeclinedAnswer(null, null, true);
        }
        if (kept == null) {
            return null;
        }
        StepAnswers.DeclinedTextAnswer words = declinedText(kept.words(), kept.wordsAltered(), kept.wordsTruncated());
        StepAnswers.DeclinedTextAnswer content =
                declinedText(kept.content(), kept.contentAltered(), kept.contentTruncated());
        return words == null && content == null ? null : new StepAnswers.DeclinedAnswer(words, content, null);
    }

    private static StepAnswers.@Nullable DeclinedTextAnswer declinedText(
            @Nullable String text, boolean altered, boolean truncated) {
        return text == null || text.isEmpty() ? null : new StepAnswers.DeclinedTextAnswer(text, altered, truncated);
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
        CodeError.UndeclaredMember member = fault.member();
        CodeError.ReadBy readBy = fault.readBy();
        return new StepAnswers.ErroredForAnswer(
                fault.reason().published(),
                fault.path().isEmpty() ? null : new Pointer(fault.path()).published(),
                member == null ? null : member.name(),
                member != null && member.cut() ? Boolean.TRUE : null,
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
        if (why != null && why.unreadableResponsePresent()) {
            return new StepAnswers.WentWrongAnswer(
                    "The model service returned a response this system could not read.", false, null);
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
        if (next(positions.get(index)) == null) {
            return null;
        }
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
                    case StepRuns.Search search ->
                        FillField.of(requireNonNull(search.released()).outputs(), Map.of());
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
        TryRecord refused = RunPayloads.refusedBeforeAnswer(step).orElse(null);
        return new StepAnswers.AnsweringAnswer(
                next.number(),
                next.beyond(),
                instruction,
                FillFieldAnswer.of(gives),
                refused == null
                        ? null
                        : new StepAnswers.LastRefusedAnswer(
                                requireNonNull(refused.number()), triedValues(refused, reading)),
                step.planned().runs() instanceof StepRuns.Search search && readsModels
                        ? answeringSearchQuestion(search)
                        : null,
                step.planned().runs() instanceof StepRuns.Search && readsModels ? sentSearchQuestion != null : null,
                step.planned().runs() instanceof StepRuns.Search && !readsModels ? Boolean.TRUE : null);
    }

    /** For a reached step, what its newest try took, or what would go in now where it has none. */
    private @Nullable WentIn wentIn(int index, StepSnapshot step) {
        if (positions.get(index) instanceof StepPosition.NotStarted) {
            return null;
        }
        StepRuns runs = step.planned().runs();
        if (runs instanceof StepRuns.Search search) {
            return searchWentIn(step, search);
        }
        if (!(runs instanceof StepRuns.Question || runs instanceof StepRuns.Code)) {
            return null;
        }
        TryRecord newest = step.newest().orElse(null);
        if (newest != null) {
            List<Optional<StepInputs.BindingRecord>> taken = StepInputs.tookForReading(context, step, newest);
            List<StepAnswers.WentInAnswer> answers = new ArrayList<>(taken.size());
            for (int at = 0; at < taken.size(); at++) {
                Optional<StepInputs.BindingRecord> read = taken.get(at);
                if (read.isPresent()) {
                    answers.add(wentIn(runs, List.of(read.get())).getFirst());
                } else {
                    Binding binding = step.planned().bindings().get(at);
                    answers.add(new StepAnswers.WentInAnswer(
                            requireNonNull(binding.target()).published(),
                            from(binding),
                            null,
                            Boolean.TRUE,
                            null,
                            null,
                            null));
                }
            }
            return new WentIn(List.copyOf(answers), WentInFrom.TRY);
        }
        Optional<List<StepInputs.BindingRecord>> live = StepInputs.bound(context, step);
        if (live.isEmpty()) {
            return null;
        }
        return new WentIn(wentIn(runs, live.get()), WentInFrom.NOT_YET_SENT);
    }

    private @Nullable WentIn searchWentIn(StepSnapshot step, StepRuns.Search search) {
        TryRecord newest = step.newest().orElse(null);
        Map<UUID, InputRecord> inputs = newest == null ? Map.of() : searchInputs(search, newest);
        List<StepAnswers.WentInAnswer> values = new ArrayList<>();
        for (org.lilradish.lite.domain.workflow.SearchQuestionPart part : search.question()) {
            if (!(part instanceof org.lilradish.lite.domain.workflow.SearchQuestionPart.Read read)) {
                continue;
            }
            StepInputs.ReadRecord bound;
            if (newest == null) {
                Optional<StepInputs.ReadRecord> live = StepInputs.bound(context, read);
                if (live.isEmpty()) {
                    return null;
                }
                bound = live.get();
            } else {
                Optional<StepInputs.ReadRecord> taken =
                        StepInputs.tookSearchForReading(context, read, requireNonNull(inputs.get(read.id())));
                if (taken.isEmpty()) {
                    values.add(new StepAnswers.WentInAnswer(
                            searchSourcePath(read.source()).published(),
                            from(read.source()),
                            null,
                            Boolean.TRUE,
                            null,
                            null,
                            read.id()));
                    continue;
                }
                bound = taken.get();
            }
            Pointer path = searchSourcePath(read.source());
            SearchSource sourceDefinition = searchSource(read.source());
            List<Field> sourceFields = sourceDefinition.fields();
            FillField field = searchSourceField(
                    sourceDefinition.runs(), sourceFields, sourceDefinition.lists(), path, bound.value());
            Shown shown = field == null
                    ? kept(null, bound.value(), bound.sources(), bound.parts())
                    : shown(field, bound.value(), bound.sources(), bound.parts());
            List<StepAnswers.SourceAnswer> sources = null;
            if (!bound.parts().isEmpty()) {
                Pointer.Reach reached = path.reach(sourceFields);
                Field declared = reached instanceof Pointer.Reach.At at ? at.field() : null;
                Declaration declaration = declared == null
                        ? null
                        : new Declaration(
                                DeclarationSide.TAKES,
                                org.lilradish.lite.domain.declaration.Demands.ofWorkflow(DeclarationSide.TAKES),
                                List.of(declared));
                sources =
                        requireNonNull(details, "A composite input has its tree read")
                                .parts(context, declaration, path, bound)
                                .stream()
                                .map(source -> new StepAnswers.SourceAnswer(source.targetPath(), from(source.from())))
                                .toList();
            }
            values.add(new StepAnswers.WentInAnswer(
                    path.published(),
                    sources == null ? actualFrom(bound) : null,
                    shown.value(),
                    shown.withheld(),
                    shown.earlierShape(),
                    sources,
                    read.id()));
        }
        return values.isEmpty()
                ? null
                : new WentIn(List.copyOf(values), newest == null ? WentInFrom.NOT_YET_SENT : WentInFrom.TRY);
    }

    private static Map<UUID, InputRecord> searchInputs(StepRuns.Search search, TryRecord tried) {
        Set<UUID> planned = new HashSet<>();
        for (org.lilradish.lite.domain.workflow.SearchQuestionPart part : search.question()) {
            if (part instanceof org.lilradish.lite.domain.workflow.SearchQuestionPart.Read read
                    && !planned.add(read.id())) {
                throw new IllegalStateException("A search plan repeats a read part");
            }
        }
        Map<UUID, InputRecord> inputs = new HashMap<>();
        for (InputRecord input : tried.inputs()) {
            UUID part = input.searchQuestionPart();
            if (input.binding() != null || part == null || !planned.contains(part)) {
                throw new IllegalStateException("A search try has an input outside its planned read parts");
            }
            if (inputs.putIfAbsent(part, input) != null) {
                throw new IllegalStateException("A search try repeats a read part input");
            }
        }
        if (inputs.size() != planned.size()) {
            throw new IllegalStateException("A search try lacks a planned read part input");
        }
        return Map.copyOf(inputs);
    }

    private static Pointer searchSourcePath(BindingSource source) {
        return switch (source) {
            case BindingSource.WorkflowInput input -> input.pointer();
            case BindingSource.StepOutput output -> output.pointer();
            case BindingSource.Written ignored -> throw new IllegalStateException("A search read is not a constant");
        };
    }

    private List<Field> searchSourceFields(@Nullable StepRuns runs, @Nullable RunSnapshot child) {
        if (runs == null) {
            return run.workflow().takes().fields();
        }
        return switch (runs) {
            case StepRuns.Question question -> question.gives().fields();
            case StepRuns.Code code ->
                code.released() == null
                        ? List.of()
                        : code.released().declared().gives().fields();
            case StepRuns.Search search ->
                search.released() == null ? List.of() : search.released().outputs();
            case StepRuns.Route route -> route.gives().fields();
            case StepRuns.Workflow _ ->
                child == null ? List.of() : child.workflow().gives().fields();
        };
    }

    private @Nullable FillField searchSourceField(BindingSource source, Pointer path, JsonValue value) {
        SearchSource read = searchSource(source);
        return searchSourceField(read.runs(), read.fields(), read.lists(), path, value);
    }

    private record SearchSource(@Nullable StepRuns runs, List<Field> fields, Map<EntryVersionId, OfferedTerms> lists) {}

    private SearchSource searchSource(BindingSource source) {
        StepRuns runs = null;
        RunSnapshot child = null;
        if (source instanceof BindingSource.StepOutput output) {
            runs = run.workflow()
                    .step(new WorkflowStepId(output.step()))
                    .orElseThrow()
                    .runs();
            if (runs instanceof StepRuns.Workflow) {
                StepSnapshot sourceStep = run.steps().stream()
                        .filter(each -> each.planned().id().value().equals(output.step()))
                        .findFirst()
                        .orElseThrow();
                child = sourceStep.beneath() == null
                        ? null
                        : sourceStep.beneath().run();
            }
        } else if (!(source instanceof BindingSource.WorkflowInput)) {
            throw new IllegalStateException("A search read is not a constant");
        }
        return new SearchSource(runs, searchSourceFields(runs, child), searchSourceLists(runs, child));
    }

    private @Nullable FillField searchSourceField(
            @Nullable StepRuns runs,
            List<Field> fields,
            Map<EntryVersionId, OfferedTerms> lists,
            Pointer path,
            JsonValue value) {
        return runs instanceof StepRuns.Code code
                ? fitting(code, DeclarationSide.GIVES, path.names(), value)
                : WrittenValues.at(FillField.of(fields, lists), path.names());
    }

    private Map<EntryVersionId, OfferedTerms> searchSourceLists(@Nullable StepRuns runs, @Nullable RunSnapshot child) {
        if (runs == null) {
            return run.workflow().lists();
        }
        return switch (runs) {
            case StepRuns.Question question -> question.lists();
            case StepRuns.Code code ->
                code.released() == null ? Map.of() : code.released().lists();
            case StepRuns.Search _ -> Map.of();
            case StepRuns.Route _ -> run.workflow().lists();
            case StepRuns.Workflow _ ->
                child == null ? Map.of() : child.workflow().lists();
        };
    }

    private List<StepAnswers.WentInAnswer> wentIn(StepRuns runs, List<StepInputs.BindingRecord> read) {
        Declaration declared = runs instanceof StepRuns.Question question
                ? question.takes()
                : runs instanceof StepRuns.Code code && code.released() != null
                        ? code.released().declared().takes()
                        : null;
        List<FillField> takes = runs instanceof StepRuns.Question question
                ? FillField.of(question.takes().fields(), question.lists())
                : null;
        List<StepAnswers.WentInAnswer> inputs = new ArrayList<>(read.size());
        for (StepInputs.BindingRecord bound : read) {
            Pointer target = bound.binding().target();
            if (target != null) {
                Shown shown = bound.parts().isEmpty() && withheld(bound.sources())
                        ? new Shown(null, Boolean.TRUE, null)
                        : runs instanceof StepRuns.Code code
                                ? kept(
                                        fitting(code, DeclarationSide.TAKES, target.names(), bound.value()),
                                        bound.value(),
                                        bound.sources(),
                                        bound.parts())
                                : shown(
                                        fieldAt(requireNonNull(takes), target.names()),
                                        bound.value(),
                                        bound.sources(),
                                        bound.parts());
                List<StepAnswers.SourceAnswer> sources = bound.parts().isEmpty() ? null : sources(bound, declared);
                inputs.add(new StepAnswers.WentInAnswer(
                        target.published(),
                        sources == null ? actualFrom(bound) : null,
                        shown.value(),
                        shown.withheld(),
                        shown.earlierShape(),
                        sources,
                        null));
            }
        }
        return inputs;
    }

    private StepAnswers.FromAnswer actualFrom(StepInputs.ReadRecord read) {
        if (details != null) {
            return from(details.origin(context, read));
        }
        return actualFrom(from(read.read().source()));
    }

    private StepAnswers.FromAnswer actualFrom(StepInputs.BindingRecord bound) {
        RunReadDetails read = details;
        if (read != null) {
            return from(read.origin(context, bound));
        }
        return actualFrom(from(bound.binding()));
    }

    private StepAnswers.FromAnswer actualFrom(StepAnswers.FromAnswer declared) {
        if (FromKind.CONSTANT.published().equals(declared.kind())) {
            return declared;
        }
        return new StepAnswers.FromAnswer(
                declared.kind(),
                declared.path(),
                declared.stepId(),
                declared.name(),
                declared.versionId(),
                declared.codeStep(),
                run.run().value(),
                header.number(),
                declared.searchWay());
    }

    private List<StepAnswers.SourceAnswer> sources(StepInputs.BindingRecord bound, @Nullable Declaration declared) {
        return requireNonNull(details, "A composite input needs its run details")
                .parts(context, declared, bound.binding(), bound.parts())
                .stream()
                .map(part -> new StepAnswers.SourceAnswer(part.targetPath(), from(part.from())))
                .toList();
    }

    private Shown shown(FillField field, JsonValue value, @Nullable ProductionValueId source) {
        return shown(field, value, source == null ? List.of() : List.of(source));
    }

    private Shown shown(FillField field, JsonValue value, List<ProductionValueId> sources) {
        return shown(field, value, sources, List.of());
    }

    private Shown shown(
            FillField field, JsonValue value, List<ProductionValueId> sources, List<InputRecord.Part> parts) {
        if ((parts.isEmpty() || field.kind() == FieldKind.FILE) && withheld(sources)) {
            return new Shown(null, Boolean.TRUE, null);
        }
        if (!parts.isEmpty()) {
            return new Shown(withheldParts(field, WrittenValues.of(field, value), parts), null, null);
        }
        return new Shown(WrittenValues.of(field, value), null, null);
    }

    /* A value its field no longer fits, or of a field no longer declared, is given as kept, as none is its field. */
    private Shown kept(@Nullable FillField field, JsonValue value, @Nullable ProductionValueId source) {
        return kept(field, value, source == null ? List.of() : List.of(source));
    }

    private Shown kept(@Nullable FillField field, JsonValue value, List<ProductionValueId> sources) {
        return kept(field, value, sources, List.of());
    }

    private Shown kept(
            @Nullable FillField field, JsonValue value, List<ProductionValueId> sources, List<InputRecord.Part> parts) {
        if ((parts.isEmpty() || (field != null && field.kind() == FieldKind.FILE)) && withheld(sources)) {
            return new Shown(null, Boolean.TRUE, null);
        }
        if (field != null && !parts.isEmpty()) {
            return new Shown(withheldParts(field, WrittenValues.of(field, value), parts), null, null);
        }
        if (field != null) {
            return new Shown(WrittenValues.of(field, value), null, null);
        }
        Set<ProductionValueId> partSources = new HashSet<>();
        parts.stream()
                .map(InputRecord.Part::source)
                .filter(java.util.Objects::nonNull)
                .forEach(partSources::add);
        boolean unknownPart = false;
        for (ProductionValueId source : sources) {
            if (withheld(List.of(source)) && !partSources.contains(source)) {
                unknownPart = true;
            }
        }
        HistoricalMask mask = new HistoricalMask();
        for (InputRecord.Part part : parts) {
            if (part.source() != null && withheld(List.of(part.source()))) {
                mask.add(part.targetPath());
            }
        }
        if (unknownPart) {
            return new Shown(null, Boolean.TRUE, null);
        }
        JsonValue kept = value;
        try {
            kept = withheldHistorical(kept, mask);
        } catch (IllegalStateException ignored) {
            return new Shown(null, Boolean.TRUE, null);
        }
        return new Shown(
                kept instanceof JsonValue.JsonNull ? NODES.nullNode() : NODES.stringNode(CanonicalJson.write(kept)),
                null,
                Boolean.TRUE);
    }

    private static final class HistoricalMask {

        private boolean hide;
        private final Map<String, HistoricalMask> children = new HashMap<>();

        private void add(List<FieldName> path) {
            HistoricalMask here = this;
            for (FieldName name : path) {
                here = here.children.computeIfAbsent(name.value(), ignored -> new HistoricalMask());
            }
            here.hide = true;
        }
    }

    private static JsonValue withheldHistorical(JsonValue value, HistoricalMask mask) {
        if (mask.hide) {
            return WITHHELD_HISTORICAL;
        }
        if (mask.children.isEmpty()) {
            return value;
        }
        if (value instanceof JsonValue.JsonArray array) {
            if (array.items().isEmpty()) {
                throw new IllegalStateException("A historical component source names no stored part");
            }
            return new JsonValue.JsonArray(array.items().stream()
                    .map(item -> withheldHistorical(item, mask))
                    .toList());
        }
        if (!(value instanceof JsonValue.JsonObject object)) {
            throw new IllegalStateException("A historical component source names no stored value");
        }
        List<JsonValue.JsonMember> members = new ArrayList<>(object.members().size());
        Set<String> missing = new HashSet<>(mask.children.keySet());
        for (JsonValue.JsonMember member : object.members()) {
            HistoricalMask beneath = mask.children.get(member.name());
            members.add(
                    beneath == null
                            ? member
                            : new JsonValue.JsonMember(member.name(), withheldHistorical(member.value(), beneath)));
            missing.remove(member.name());
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("A historical component source names no stored part");
        }
        return new JsonValue.JsonObject(members);
    }

    private JsonNode withheldParts(FillField field, JsonNode value, List<InputRecord.Part> parts) {
        JsonNode shown = value;
        for (InputRecord.Part part : parts) {
            if (part.source() != null && withheld(List.of(part.source()))) {
                shown = withheldAt(field, shown, part.targetPath(), 0);
            }
        }
        return shown;
    }

    static ObjectNode withheldParts(
            List<FillField> fields,
            ObjectNode value,
            List<InputRecord.Part> parts,
            Set<GroupPermission> permitted,
            Map<ProductionValueId, StepProducer> producers) {
        boolean readsModels = permitted.contains(GroupPermission.READ_INFERENCE_CONTENT);
        for (InputRecord.Part part : parts) {
            ProductionValueId source = part.source();
            if (source == null) {
                continue;
            }
            StepProducer producer = producers.get(source);
            if (producer == null) {
                throw new IllegalStateException("A bound value names no production in its run tree");
            }
            if (!withheldSource(readsModels, producer)) {
                continue;
            }
            List<FieldName> path = part.targetPath();
            FieldName first = path.getFirst();
            FillField field = fields.stream()
                    .filter(each -> each.name().equals(first))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("A component source names no declared field"));
            value.set(first.value(), withheldAt(field, value.get(first.value()), path, 1));
        }
        return value;
    }

    static Map<ProductionValueId, StepProducer> producersIn(RunInTree context) {
        RunInTree root = context;
        while (root.above() != null) {
            root = root.above();
        }
        Map<ProductionValueId, StepProducer> found = new HashMap<>();
        rememberProducers(root.run(), found);
        return Map.copyOf(found);
    }

    private static void rememberProducers(RunSnapshot run, Map<ProductionValueId, StepProducer> found) {
        for (StepSnapshot step : run.steps()) {
            for (TryRecord aTry : step.tries()) {
                for (ValueRecord value : aTry.values()) {
                    if (found.putIfAbsent(value.id(), aTry.producer()) != null) {
                        throw new IllegalStateException("A run tree repeats a produced value");
                    }
                }
            }
            if (step.beneath() != null) {
                rememberProducers(step.beneath().run(), found);
            }
        }
    }

    private static boolean withheldSource(boolean readsModels, StepProducer producer) {
        return !readsModels && producer == StepProducer.MODEL;
    }

    private static JsonNode withheldAt(FillField field, JsonNode value, List<FieldName> path, int depth) {
        if (value instanceof ArrayNode array) {
            for (int index = 0; index < array.size(); index++) {
                array.set(index, withheldAt(field, array.get(index), path, depth));
            }
            return array;
        }
        if (depth == path.size() || field.kind() == FieldKind.FILE) {
            return withheldLeaves(field, value);
        }
        if (field.kind() != FieldKind.FIELDS || !(value instanceof ObjectNode object)) {
            throw new IllegalStateException("A component source names no nested field");
        }
        FieldName name = path.get(depth);
        FillField child = field.fields().stream()
                .filter(each -> each.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("A component source names no declared field"));
        object.set(name.value(), withheldAt(child, object.get(name.value()), path, depth + 1));
        return object;
    }

    private static JsonNode withheldLeaves(FillField field, JsonNode value) {
        if (value instanceof ArrayNode array) {
            for (int index = 0; index < array.size(); index++) {
                array.set(index, withheldLeaves(field, array.get(index)));
            }
            return array;
        }
        if (value.isNull()) {
            return NODES.objectNode().put("withheld", true);
        }
        if (field.kind() == FieldKind.FIELDS) {
            if (!(value instanceof ObjectNode object)) {
                throw new IllegalStateException("A nested value holds no declared fields");
            }
            for (FillField child : field.fields()) {
                object.set(
                        child.name().value(),
                        withheldLeaves(child, object.get(child.name().value())));
            }
            return object;
        }
        return NODES.objectNode().put("withheld", true);
    }

    private static List<InputRecord.Part> partsOf(List<StepInputs.BindingRecord> bound) {
        List<InputRecord.Part> parts = new ArrayList<>();
        for (StepInputs.BindingRecord input : bound) {
            List<FieldName> path = requireNonNull(input.binding().target())
                    .names()
                    .subList(1, requireNonNull(input.binding().target()).names().size());
            if (input.parts().isEmpty()) {
                if (!path.isEmpty()) {
                    parts.add(new InputRecord.Part(path, input.source()));
                }
            } else {
                for (InputRecord.Part nested : input.parts()) {
                    List<FieldName> at = new ArrayList<>(path);
                    at.addAll(nested.targetPath());
                    parts.add(new InputRecord.Part(at, nested.source()));
                }
            }
        }
        return parts;
    }

    private boolean withheld(List<ProductionValueId> sources) {
        boolean hide = false;
        for (ProductionValueId source : sources) {
            Produced from = produced.get(source);
            if (from == null && !indexedTree) {
                RunInTree root = context;
                while (root.above() != null) {
                    root = root.above();
                }
                rememberTree(root.run());
                indexedTree = true;
                from = produced.get(source);
            }
            if (from == null) {
                throw new IllegalStateException("A bound value names no production in its run tree");
            }
            if (withheldSource(readsModels, from.aTry().producer())) {
                hide = true;
            }
        }
        return hide;
    }

    /* A question's values are read as the version it pins declares them; a code step's by the name each was kept
     * under, since its release may have changed what it declares since they were given. */
    private @Nullable ValueReading reading(PlannedStep planned) {
        return switch (planned.runs()) {
            case StepRuns.Question question ->
                new QuestionValues(FillField.of(question.gives().fields(), question.lists()));
            case StepRuns.Code code -> new CodeValues(code);
            case StepRuns.Search search -> new SearchValues(search);
            case StepRuns.Workflow ignored -> null;
            case StepRuns.Route ignored -> null;
        };
    }

    /**
     * The field at {@code names} as the release declares it now, exactly where {@code value} fits it as code giving it
     * back now would be read: none where none is declared, its list is not here, or the value no longer fits.
     */
    private @Nullable FillField fitting(
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
        if (!(Unwrapping.givenAfterFileFillings(List.of(asked), given) instanceof GivenBack.Fits)) {
            return null;
        }
        FillField current = WrittenValues.at(FillField.of(alone, released.lists()), names);
        return current != null && storedFilesFit(current, value) ? current : null;
    }

    private boolean storedFilesFit(FillField field, JsonValue value) {
        if (value instanceof JsonValue.JsonArray many) {
            return many.items().stream().allMatch(item -> storedFilesFit(field, item));
        }
        if (field.kind() == FieldKind.FILE && value instanceof JsonValue.JsonObject) {
            StoredFile file = StoredFile.fromKept(value);
            return file.equals(heldFiles.get(file.id()))
                    && requireNonNull(field.fileTypes()).contains(file.type())
                    && file.size() <= requireNonNull(field.maximumBytes());
        }
        if (field.kind() == FieldKind.FIELDS && value instanceof JsonValue.JsonObject object) {
            for (JsonValue.JsonMember member : object.members()) {
                FillField inner = field.fields().stream()
                        .filter(candidate -> candidate.name().value().equals(member.name()))
                        .findFirst()
                        .orElse(null);
                if (inner != null && !storedFilesFit(inner, member.value())) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean listsHeld(Field field, Map<EntryVersionId, OfferedTerms> lists) {
        return switch (field.shape()) {
            case FieldShape.Term term -> term.list() == null || lists.containsKey(term.list());
            case FieldShape.Nested nested -> nested.fields().stream().allMatch(inner -> listsHeld(inner, lists));
            case FieldShape.Text ignored -> true;
            case FieldShape.File ignored -> true;
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
            } else if (step.runs() instanceof StepRuns.Workflow workflow) {
                StepSnapshot held = run.steps().stream()
                        .filter(each -> each.planned().id().equals(step.id()))
                        .findFirst()
                        .orElseThrow();
                if (held.beneath() != null) {
                    RunSnapshot child = held.beneath().run();
                    declared.putIfAbsent(
                            workflow.pinned().version().value().toString(),
                            new StepAnswers.DeclaredAnswer(
                                    FillFieldAnswer.of(FillField.of(
                                            child.workflow().takes().fields(),
                                            child.workflow().lists())),
                                    FillFieldAnswer.of(FillField.of(
                                            child.workflow().gives().fields(),
                                            child.workflow().lists()))));
                }
            } else if (step.runs() instanceof StepRuns.Route route) {
                declared.putIfAbsent(
                        "route:" + step.id().value(),
                        new StepAnswers.DeclaredAnswer(
                                List.of(),
                                FillFieldAnswer.of(FillField.of(
                                        route.gives().fields(), run.workflow().lists()))));
            } else if (step.runs() instanceof StepRuns.Search search) {
                if (search.released() != null) {
                    declared.putIfAbsent(
                            "search:" + search.way(),
                            new StepAnswers.DeclaredAnswer(
                                    List.of(),
                                    FillFieldAnswer.of(
                                            FillField.of(search.released().outputs(), Map.of()))));
                }
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
            boolean wentWrongCut,
            boolean unreadableResponsePresent) {}

    record UnreadableRecord(
            @DoNotLog String text,
            @DoNotLog boolean altered,
            @DoNotLog boolean truncated) {}

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

    /** The two separately kept parts of a declined model call. */
    record DeclinedRecord(
            @DoNotLog @Nullable String words,
            boolean wordsAltered,
            boolean wordsTruncated,
            @DoNotLog @Nullable String content,
            boolean contentAltered,
            boolean contentTruncated) {}

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

    private final class SearchValues implements ValueReading {
        private final StepRuns.Search search;

        SearchValues(StepRuns.Search search) {
            this.search = search;
        }

        @Override
        public List<String> fields(TryRecord newest) {
            return org.lilradish.lite.domain.search.SearchWay.FIELDS.stream()
                    .map(FieldName::value)
                    .toList();
        }

        @Override
        public List<ValueRecord> values(TryRecord aTry) {
            List<ValueRecord> ordered = new ArrayList<>();
            for (String name : fields(aTry)) {
                ValueRecord value = valueOf(aTry, name);
                if (value != null) {
                    ordered.add(value);
                }
            }
            return ordered;
        }

        @Override
        public Shown shown(ValueRecord value) {
            List<FillField> fields = search.released() == null
                    ? List.of()
                    : FillField.of(search.released().outputs(), Map.of());
            FillField field = WrittenValues.at(fields, List.of(new FieldName(value.field())));
            return kept(field, value.value(), value.id());
        }
    }
}
