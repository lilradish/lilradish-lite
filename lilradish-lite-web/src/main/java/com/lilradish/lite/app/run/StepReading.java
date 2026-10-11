package com.lilradish.lite.app.run;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.app.filling.FillFieldAnswer;
import com.lilradish.lite.app.pool.PersonAnswer;
import com.lilradish.lite.domain.codestep.CodeStepName;
import com.lilradish.lite.domain.codestep.ReleasedCodeStep;
import com.lilradish.lite.domain.declaration.FieldName;
import com.lilradish.lite.domain.file.StoredFile;
import com.lilradish.lite.domain.filling.FillField;
import com.lilradish.lite.domain.identity.GroupPermission;
import com.lilradish.lite.domain.identity.SubjectId;
import com.lilradish.lite.domain.model.DeployedModel;
import com.lilradish.lite.domain.model.ModelCatalog;
import com.lilradish.lite.domain.registry.VersionName;
import com.lilradish.lite.domain.run.PlannedStep;
import com.lilradish.lite.domain.run.ProductionId;
import com.lilradish.lite.domain.run.ProductionValueId;
import com.lilradish.lite.domain.run.RunAct;
import com.lilradish.lite.domain.run.RunInTree;
import com.lilradish.lite.domain.run.RunPayloads;
import com.lilradish.lite.domain.run.RunPosition;
import com.lilradish.lite.domain.run.RunPositions;
import com.lilradish.lite.domain.run.RunSnapshot;
import com.lilradish.lite.domain.run.RunState;
import com.lilradish.lite.domain.run.RunStepHoldReason;
import com.lilradish.lite.domain.run.StepAct;
import com.lilradish.lite.domain.run.StepGround;
import com.lilradish.lite.domain.run.StepInputs;
import com.lilradish.lite.domain.run.StepPosition;
import com.lilradish.lite.domain.run.StepPositions;
import com.lilradish.lite.domain.run.StepRuns;
import com.lilradish.lite.domain.run.StepSnapshot;
import com.lilradish.lite.domain.run.StopRecord;
import com.lilradish.lite.domain.run.TryLostReason;
import com.lilradish.lite.domain.run.TryRecord;
import com.lilradish.lite.domain.run.WorkflowStepId;
import com.lilradish.lite.domain.workflow.Binding;
import com.lilradish.lite.domain.workflow.Pointer;
import com.lilradish.lite.domain.workflow.Producer;
import com.lilradish.lite.domain.workflow.StepProducer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Shaped from one store reading alone. Model words are withheld, with that fact stated,
 * from readers who may not read them.
 */
final class StepReading {

    final RunSnapshot run;

    final RunInTree context;

    final List<StepPosition> positions;

    private final Set<GroupPermission> permitted;

    final StepAnswers.HeaderAnswer header;

    private final Map<String, StepAnswers.DeclaredAnswer> declarations;

    private final Map<SubjectId, PersonAnswer> people;

    final boolean readsModels;

    final RunDeployment deployment;

    private @Nullable RunReadDetails details;

    private @Nullable ModelCatalog catalog;

    private Map<UUID, VersionName> versionNames = Map.of();

    private final ShownValues shownValues;

    private final StepSources stepSources;

    private final StepGivenBack stepGivenBack;

    private final StepTries stepTries;

    private final StepWhereabouts stepWhereabouts;

    /**
     * @param number the run's number in its group
     * @param raise a raise waiting on the run's ceiling; requester checks belong to the actual writer
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
            Optional<CeilingRaises.Waiting> raise,
            Map<SubjectId, PersonAnswer> people,
            Map<ProductionId, RunBudget.Spend> spent,
            Map<ProductionId, StepTries.LostRecord> lost,
            Map<ProductionId, List<StepTries.TurnawayRecord>> turnedAway,
            RunDeployment deployment) {
        this.context = requireNonNull(context, "StepReading context must not be null");
        this.deployment = requireNonNull(deployment, "StepReading deployment must not be null");
        RunSnapshot run = context.run();
        this.run = run;
        this.positions = StepPositions.of(run);
        this.permitted = requireNonNull(permitted, "StepReading permitted must not be null");
        this.readsModels = permitted.contains(GroupPermission.READ_INFERENCE_CONTENT);
        this.people = requireNonNull(people, "StepReading people must not be null");
        requireNonNull(spent, "StepReading spent must not be null");
        requireNonNull(lost, "StepReading lost must not be null");
        requireNonNull(turnedAway, "StepReading turnedAway must not be null");
        RunState state = RunPositions.state(run, positions);
        this.header = new StepAnswers.HeaderAnswer(
                run.run().value(),
                number,
                run.version().value(),
                state.published(),
                RunPositions.at(run, positions)
                        .map(step -> new RunsController.AtAnswer(
                                step.planned().id().value(),
                                step.planned().name().value(),
                                step.planned().label() == null
                                        ? null
                                        : step.planned().label().value()))
                        .orElse(null),
                RunAct.stateActs(new RunPosition(
                                atTop,
                                run.stopped(),
                                state == RunState.DONE,
                                raise.isPresent()
                                        ? RunPosition.Raise.ASKED_BY_ANOTHER
                                        : RunPosition.Raise.NONE_WAITING))
                        .stream()
                        .map(act -> act.published())
                        .toList(),
                new StepAnswers.ProgressAnswer(RunPositions.done(positions), positions.size()));
        this.declarations = declarations(run);
        this.shownValues = new ShownValues(context, readsModels);
        this.stepSources = new StepSources(this, shownValues);
        this.stepGivenBack = new StepGivenBack(this, shownValues, stepSources);
        this.stepTries = new StepTries(this, stepGivenBack, spent, lost, turnedAway);
        this.stepWhereabouts = new StepWhereabouts(this, shownValues, stepTries);
    }

    StepReading withSearch(Map<ProductionId, StepAnswers.SearchCountAnswer> searches) {
        stepTries.withSearch(searches);
        return this;
    }

    StepReading withKeptFiles(Map<ProductionId, List<StepAnswers.KeptCodeFileAnswer>> keptFiles) {
        stepTries.withKeptFiles(keptFiles);
        return this;
    }

    StepReading withRawFacts(Map<ProductionId, StepTries.RawFactsRecord> facts) {
        stepTries.withRawFacts(facts);
        return this;
    }

    StepReading withHeldFiles(Map<UUID, StoredFile> heldFiles) {
        stepSources.withHeldFiles(heldFiles);
        return this;
    }

    StepReading withDetails(RunReadDetails details) {
        this.details = requireNonNull(details, "StepReading details must not be null");
        stepTries.withBeneathCost(details.costBeneath(run.steps()));
        return this;
    }

    StepReading withModels(ModelCatalog catalog) {
        this.catalog = requireNonNull(catalog, "StepReading catalog must not be null");
        return this;
    }

    StepReading withVersionNames(Map<UUID, VersionName> names) {
        versionNames = Map.copyOf(names);
        return this;
    }

    private @Nullable String versionName(UUID version) {
        VersionName name = versionNames.get(version);
        return name == null ? null : name.value();
    }

    @Nullable
    RunReadDetails details() {
        return details;
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
        StepSources.WentIn wentIn = stepSources.wentIn(index, step);
        List<StepAnswers.CameOutAnswer> cameOut = stepGivenBack.cameOut(step);
        StepAnswers.StepRowAnswer row = row(index, false, cameOut);
        Map<ProductionValueId, RunActs.Held> heldForLength = stepTries.heldForLength(step);
        return new StepAnswers.StepAnswer(
                header,
                declarations,
                row,
                wentIn == null ? null : wentIn.inputs(),
                wentIn == null ? null : wentIn.from().published(),
                cameOut,
                step.tries().stream()
                        .filter(aTry -> aTry.number() != null)
                        .map(aTry -> stepTries.tryAnswer(step, aTry, heldForLength))
                        .toList(),
                row.stateActs() != null && row.stateActs().contains(StepAct.ANSWER.published())
                        ? answering(step, index, heldForLength)
                        : null,
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
                ShownValues.Shown shown = shownValues.shown(
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
        StepGround.State standing = StepGround.stateOf(run, step, position);
        List<String> acts =
                StepAct.stateActs(standing).stream().map(StepAct::published).toList();
        StepPosition.AwaitingReview waiting =
                position instanceof StepPosition.AwaitingReview awaiting ? awaiting : null;
        TryRecord production = waiting == null
                ? null
                : step.tries().stream()
                        .filter(aTry -> Integer.valueOf(waiting.number()).equals(aTry.number()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("A waiting review names its actual production"));
        PersonAnswer producedBy = waiting == null ? null : person(waiting.producedBy());
        StepSources.WentIn wentIn =
                withInputs && acts.contains(StepAct.REVIEW.published()) ? stepSources.wentIn(index, step) : null;
        var fault = standing.codeGivesOtherwise();
        return new StepAnswers.StepRowAnswer(
                planned.id().value(),
                planned.order(),
                planned.name().value(),
                planned.label() == null ? null : planned.label().value(),
                runs(planned),
                beneath(step),
                StepProducersAndReviewers.producer(planned),
                StepProducersAndReviewers.reviewer(planned),
                position.state().published(),
                stepWhereabouts.where(run, step, position),
                stepSources.takesFrom(planned),
                planned.tries() == null
                        ? null
                        : new StepAnswers.TriesAnswer(
                                step.used(), planned.declaredTries(), step.used() > planned.declaredTries()),
                step.beneath() == null || details == null
                        ? stepTries.cost(planned, step.tries())
                        : stepTries.costBeneath(step),
                stepGivenBack.gaveBack(step, cameOut),
                wentIn == null ? null : wentIn.inputs(),
                wentIn == null ? null : wentIn.from().published(),
                stepWhereabouts.next(position),
                acts,
                StepAct.stateRefusals(standing).entrySet().stream()
                        .map(refused -> new StepAnswers.StateRefusalAnswer(
                                refused.getKey().published(), refused.getValue().code()))
                        .toList(),
                producedBy == null ? null : producedBy.userId(),
                production == null || production.producer() == null
                        ? null
                        : production.producer().published(),
                fault == null ? null : StepAnswers.GivesOtherwiseNames.of(fault),
                standing.entryStopped()
                        ? StepAct.stateActs(new StepGround.State(
                                        position,
                                        standing.runStopped(),
                                        false,
                                        standing.producer(),
                                        standing.codeMayRunAgain(),
                                        standing.codeGivesOtherwise(),
                                        standing.unreleased()))
                                .stream()
                                .map(StepAct::published)
                                .toList()
                        : null,
                stepWhereabouts.waitsOnStarter(run, position),
                length(step));
    }

    private StepAnswers.@Nullable LengthAnswer length(StepSnapshot step) {
        RunReadDetails read = details;
        ModelCatalog models = catalog;
        if (read == null
                || models == null
                || step.hold() == null
                || step.hold().reason() != RunStepHoldReason.TOO_LONG
                || !(step.planned().runs() instanceof StepRuns.Question question)
                || !(step.planned().producer() instanceof Producer.Model producer)) {
            return null;
        }
        StepLengthDetails.Measured actual = StepLengthDetails.question(context, step, models);
        if (actual == null) {
            return null;
        }
        DeployedModel model = actual.model();
        StepLengthDetails measured = actual.details();
        List<StepAnswers.LengthValueAnswer> values =
                new ArrayList<>(measured.rows().size());
        List<ProductionValueId> sources = new ArrayList<>();
        for (StepLengthDetails.Row row : measured.rows()) {
            ProductionValueId source = row.source();
            if (source != null) {
                sources.add(source);
            }
        }
        Map<ProductionValueId, List<RunReadDetails.UsedRecord>> usedByValue = read.used(sources);
        Map<ProductionValueId, Long> previousByValue = read.previousLengthUnits(sources, step);
        for (StepLengthDetails.Row row : measured.rows()) {
            StepLengthDetails.Occurrence first = row.occurrences().getFirst();
            RunReadDetails.OriginRecord origin = first.part() == null
                    ? read.origin(context, first.input())
                    : read.origin(context, first.input(), first.part());
            RunReadDetails.MakerRecord maker = origin.maker();
            List<RunReadDetails.UsedRecord> used =
                    row.source() == null ? List.of() : usedByValue.getOrDefault(row.source(), List.of());
            boolean fromHereOrAbove = false;
            if (maker != null) {
                for (RunInTree at = context; at != null; at = at.above()) {
                    if (at.run().run().equals(maker.run().run())) {
                        fromHereOrAbove = true;
                        break;
                    }
                }
            }
            StepSnapshot madeBy = maker == null ? null : read.madeBy(maker);
            Long previous = row.source() == null ? null : previousByValue.get(row.source());
            values.add(new StepAnswers.LengthValueAnswer(
                    row.source() == null ? null : row.source().value(),
                    row.occurrences().stream()
                            .map(occurrence -> new Pointer(occurrence.position()).published())
                            .toList(),
                    Long.toString(model.unitsOf(row.characters())),
                    StepSources.from(read, origin),
                    maker != null,
                    maker != null && !fromHereOrAbove,
                    madeBy != null && madeBy.used() >= madeBy.planned().declaredTries(),
                    fromHereOrAbove
                            && model.unitsPast(measured.sent().characters()) > 0
                            && used.isEmpty()
                            && !run.stopped()
                            && permitted.contains(GroupPermission.REVIEW_AT_GATE),
                    used.stream()
                            .map(each -> new StepAnswers.LengthUsedAnswer(
                                    each.run().run().value(),
                                    each.run().number(),
                                    each.stepId(),
                                    each.name(),
                                    each.label(),
                                    each.choseBranch()))
                            .toList(),
                    previous == null ? null : Long.toString(previous)));
        }
        return new StepAnswers.LengthAnswer(
                read.lengthHold(step),
                model.name().value(),
                Long.toString(model.sentPerCallLimit()),
                Long.toString(model.unitsOf(measured.sent().characters())),
                Long.toString(model.unitsPast(measured.sent().characters())),
                Long.toString(model.unitsOf(measured.fixedCharacters())),
                producer.toldWhatHappened(),
                values);
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
                        null,
                        workflow.versionName() == null
                                ? null
                                : workflow.versionName().value()),
                at == null
                        ? null
                        : new StepAnswers.BeneathAtAnswer(
                                at.run().run().value(), at.run().number(), at.stepId(), at.name(), at.label()));
    }

    private StepAnswers.RunsAnswer runs(PlannedStep planned) {
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
                        null,
                        versionName(question.pinned().version().value()));
            case StepRuns.Workflow workflow ->
                new StepAnswers.RunsAnswer(
                        "workflow",
                        workflow.pinned().entry().value(),
                        workflow.pinned().name().value(),
                        workflow.pinned().number(),
                        workflow.pinned().version().value(),
                        null,
                        null,
                        null,
                        versionName(workflow.pinned().version().value()));
            case StepRuns.Code code ->
                new StepAnswers.RunsAnswer("code_step", null, null, null, null, code.codeStep(), null, null, null);
            case StepRuns.Search search ->
                new StepAnswers.RunsAnswer(
                        "search",
                        search.question().pinned().entry().value(),
                        search.question().pinned().name().value(),
                        search.question().pinned().number(),
                        search.question().pinned().version().value(),
                        null,
                        search.way(),
                        search.released() == null
                                ? null
                                : search.released().requiredTool().value(),
                        versionName(search.question().pinned().version().value()));
            case StepRuns.Route ignored ->
                new StepAnswers.RunsAnswer("route", null, null, null, null, null, null, null, null);
        };
    }

    /*
     * Drawn only where answering is admitted, which is judged as the answer itself is: a code step on what its
     * release gives back now alone. It tells nothing, and is answered as that release declares it.
     */
    private StepAnswers.@Nullable AnsweringAnswer answering(
            StepSnapshot step, int index, Map<ProductionValueId, RunActs.Held> heldForLength) {
        if (stepWhereabouts.next(positions.get(index)) == null) {
            return null;
        }
        StepGivenBack.ValueReading reading = stepGivenBack.reading(step.planned());
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
                : step.planned().runs() instanceof StepRuns.Search search
                        ? search.question().instruction().value()
                        : null;
        StepAnswers.NextAnswer next =
                requireNonNull(stepWhereabouts.next(positions.get(index)), "an answer fills a try owed");
        TryRecord refused = RunPayloads.refusedBeforeAnswer(step).orElse(null);
        return new StepAnswers.AnsweringAnswer(
                next.number(),
                next.beyond(),
                instruction,
                FillFieldAnswer.of(gives),
                refused == null
                        ? null
                        : new StepAnswers.LastRefusedAnswer(
                                requireNonNull(refused.number()),
                                stepTries.triedValues(refused, reading, heldForLength)));
    }

    @Nullable
    PersonAnswer person(@Nullable SubjectId subject) {
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
                StepRuns.Question question = search.question();
                declared.putIfAbsent(
                        question.pinned().version().value().toString(),
                        new StepAnswers.DeclaredAnswer(
                                FillFieldAnswer.of(FillField.of(question.takes().fields(), question.lists())),
                                FillFieldAnswer.of(FillField.of(question.gives().fields(), question.lists()))));
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

    static FillField fieldAt(List<FillField> fields, List<FieldName> names) {
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

    private static void addNamed(Set<SubjectId> named, @Nullable SubjectId subject) {
        if (subject != null) {
            named.add(subject);
        }
    }

    static String time(Instant at) {
        return at.toString();
    }
}
