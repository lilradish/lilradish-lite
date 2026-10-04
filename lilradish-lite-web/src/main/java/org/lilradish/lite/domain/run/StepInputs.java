package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.log.annotation.DoNotLog;
import org.lilradish.lite.domain.declaration.AskedField;
import org.lilradish.lite.domain.declaration.Asking;
import org.lilradish.lite.domain.declaration.Declaration;
import org.lilradish.lite.domain.declaration.Field;
import org.lilradish.lite.domain.declaration.FieldFit;
import org.lilradish.lite.domain.declaration.FieldKind;
import org.lilradish.lite.domain.declaration.FieldName;
import org.lilradish.lite.domain.declaration.FieldShape;
import org.lilradish.lite.domain.declaration.HowMany;
import org.lilradish.lite.domain.declaration.OfferedTerms;
import org.lilradish.lite.domain.file.StoredFile;
import org.lilradish.lite.domain.filling.FillPath;
import org.lilradish.lite.domain.inference.GivenBack;
import org.lilradish.lite.domain.inference.Unwrapping;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.lilradish.lite.domain.wire.JsonValue;
import org.lilradish.lite.domain.wire.JsonValue.JsonArray;
import org.lilradish.lite.domain.wire.JsonValue.JsonMember;
import org.lilradish.lite.domain.wire.JsonValue.JsonObject;
import org.lilradish.lite.domain.workflow.Binding;
import org.lilradish.lite.domain.workflow.BindingSource;
import org.lilradish.lite.domain.workflow.Pointer;
import org.lilradish.lite.domain.workflow.SearchQuestion;
import org.lilradish.lite.domain.workflow.SearchQuestionPart;

/** What a step takes through its run tree, keeping each binding beside its ultimate sources. */
public final class StepInputs {

    private static final JsonValue NONE = new JsonValue.JsonNull();

    private StepInputs() {}

    /** A model production reads its open try's makers, or standing makers before a try is opened. */
    public static Optional<HoldRecord.SourceRecord> missingCodeSource(RunInTree at, StepSnapshot step) {
        for (ProductionRead input : productionReads(at, step)) {
            for (CodeRead read : input.input().codeReads()) {
                if (((StepRuns.Code) read.step().planned().runs()).released() == null) {
                    return Optional.of(read.maker());
                }
            }
        }
        return Optional.empty();
    }

    /** Only an actually read portion of a code value is judged against its current declaration. */
    public static Optional<SourceFailureRecord> sourceFailure(RunInTree at, StepSnapshot step) {
        for (ProductionRead input : productionReads(at, step)) {
            for (CodeRead read : input.input().codeReads()) {
                StepRuns.Code code = (StepRuns.Code) read.step().planned().runs();
                if (code.released() == null) {
                    continue;
                }
                Pointer.Reach reach =
                        read.field().reach(code.released().declared().gives().fields());
                boolean matches = reach instanceof Pointer.Reach.At current && !SearchQuestion.takesFile(current);
                RunStepFailureReason reason = RunStepFailureReason.SOURCE_NO_LONGER_MATCHES;
                if (matches && reach instanceof Pointer.Reach.At current) {
                    RunStepFailureReason invalid = currentFieldFailure(
                            current.field(), code.released().lists(), read.value(), current.given());
                    if (invalid != null) {
                        reason = invalid;
                        matches = false;
                    }
                    if (matches
                            && input.target() != null
                            && step.planned().runs() instanceof StepRuns.Question question) {
                        Pointer target = new Pointer(followed(input.target().names(), read.target()));
                        Pointer.Reach into = target.reach(question.takes().fields());
                        matches = into instanceof Pointer.Reach.At expected
                                && FieldFit.fits(current.field(), expected.field())
                                && (!expected.field().demand().mustBe() || current.given())
                                && FieldFit.givenWithin(current.field(), expected.field());
                    }
                }
                if (!matches) {
                    return Optional.of(new SourceFailureRecord(
                            reason,
                            new FailureRecord.SourceRecord(
                                    read.maker(), read.field().published())));
                }
            }
        }
        return Optional.empty();
    }

    private static @Nullable RunStepFailureReason currentFieldFailure(
            Field field, Map<EntryVersionId, OfferedTerms> lists, JsonValue value, boolean given) {
        if (value instanceof JsonValue.JsonNull) {
            return given ? RunStepFailureReason.SOURCE_NO_LONGER_MATCHES : null;
        }
        AskedField told;
        try {
            told = Asking.told(field, lists);
        } catch (IllegalArgumentException notTold) {
            return RunStepFailureReason.SOURCE_LIST_NOT_HERE;
        }
        return Unwrapping.given(
                                List.of(told),
                                new JsonObject(
                                        List.of(new JsonMember(field.name().value(), value))))
                        instanceof GivenBack.Fits
                ? null
                : RunStepFailureReason.SOURCE_NO_LONGER_MATCHES;
    }

    /** The classified current declaration failure beside the actual code maker and field read. */
    public record SourceFailureRecord(RunStepFailureReason reason, FailureRecord.SourceRecord source) {
        public SourceFailureRecord {
            requireNonNull(reason, "StepInputs source failure reason must not be null");
            requireNonNull(source, "StepInputs source failure must name its actual source");
            if (reason != RunStepFailureReason.SOURCE_NO_LONGER_MATCHES
                    && reason != RunStepFailureReason.SOURCE_LIST_NOT_HERE) {
                throw new IllegalArgumentException(
                        "StepInputs source failure classifies a declaration or missing list");
            }
        }
    }

    private static List<ProductionRead> productionReads(RunInTree at, StepSnapshot step) {
        requireNonNull(at, "StepInputs source context must not be null");
        requireNonNull(step, "StepInputs source step must not be null");
        TryRecord open = step.newest().filter(TryRecord::open).orElse(null);
        List<ProductionRead> read = new ArrayList<>();
        switch (step.planned().runs()) {
            case StepRuns.Question ignored -> {
                List<BindingRecord> inputs = open == null
                        ? bound(at, step)
                                .orElseThrow(
                                        () -> new IllegalStateException("A model production reads standing inputs"))
                        : took(at, step, open);
                for (BindingRecord input : inputs) {
                    Resolved resolved = resolve(
                                    at,
                                    input.binding().source(),
                                    List.of(),
                                    input.source(),
                                    input.parts(),
                                    "Binding " + input.binding().id())
                            .orElseThrow(() -> new IllegalStateException("A model production reads its actual makers"));
                    read.add(new ProductionRead(
                            resolved, requireNonNull(input.binding().target(), "A question binding fills an input")));
                }
            }
            case StepRuns.Search search -> {
                List<ReadRecord> inputs;
                if (open == null) {
                    inputs = new ArrayList<>();
                    for (SearchQuestionPart part : search.question()) {
                        if (part instanceof SearchQuestionPart.Read from) {
                            inputs.add(bound(at, from)
                                    .orElseThrow(() ->
                                            new IllegalStateException("A model production reads standing inputs")));
                        }
                    }
                } else {
                    inputs = tookSearch(at, search.question(), open);
                }
                for (ReadRecord input : inputs) {
                    Resolved resolved = resolve(
                                    at,
                                    input.read().source(),
                                    List.of(),
                                    input.source(),
                                    input.parts(),
                                    "Search question part " + input.read().id())
                            .orElseThrow(() -> new IllegalStateException("A model production reads its actual makers"));
                    read.add(new ProductionRead(resolved, null));
                }
            }
            default ->
                throw new IllegalArgumentException(
                        "An absent source code step holds only a question or search production");
        }
        return List.copyOf(read);
    }

    /** The source fact is qualified by its actual tree, written step, workflow step and code kind. */
    public static StepSnapshot codeSource(RunInTree at, HoldRecord.SourceRecord source) {
        requireNonNull(at, "StepInputs source context must not be null");
        requireNonNull(source, "StepInputs code source must not be null");
        RunSnapshot run = root(at).find(source.run())
                .orElseThrow(() -> new IllegalStateException("A code source belongs to its run tree"))
                .run();
        StepSnapshot step = run.step(new WorkflowStepId(source.step()))
                .orElseThrow(() -> new IllegalStateException("A code source names its actual workflow step"));
        if (!source.runStep().equals(step.runStep()) || !(step.planned().runs() instanceof StepRuns.Code)) {
            throw new IllegalStateException("A code source names its actual written code step");
        }
        return step;
    }

    private static RunInTree root(RunInTree at) {
        RunInTree root = at;
        while (root.above() != null) {
            root = requireNonNull(root.above());
        }
        return root;
    }

    /**
     * One per binding of {@code step}, in the order written, where every step it reads from has a value standing
     * there; none where one has not yet. What the store holds that no approved version could bind is thrown.
     */
    public static Optional<List<InputRecord>> traced(RunInTree run, StepSnapshot step, Declaration takes) {
        requireNonNull(takes, "StepInputs takes must not be null");
        return bound(run, step).map(read -> traced(read, takes));
    }

    public static List<InputRecord> traced(List<BindingRecord> bound, Declaration takes) {
        List<InputRecord> traced = new ArrayList<>(bound.size());
        for (Reached each : reached(bound, takes)) {
            traced.add(new InputRecord(
                    each.id(), each.input().source(), each.input().parts()));
        }
        return List.copyOf(traced);
    }

    /**
     * One value per field {@code takes} declares at its first level, in declared order: none where nothing was
     * bound there, and every object built or taken whole holding each field declared of it, in declared order and
     * none where unfilled, at every depth. What the store holds that no approved version could bind is thrown.
     */
    public static JsonObject filled(Declaration takes, List<BindingRecord> bound) {
        requireNonNull(takes, "StepInputs takes must not be null");
        requireNonNull(bound, "StepInputs bound must not be null");
        return object(takes.fields(), 0, reached(bound, takes));
    }

    /** A file at the exact position it fills, traced to the run that actually held its source value. */
    public record FileSource(RunId run, UUID file) {
        public FileSource {
            requireNonNull(run, "StepInputs.FileSource run must not be null");
            requireNonNull(file, "StepInputs.FileSource file must not be null");
        }
    }

    public static Map<FillPath, FileSource> fileSources(RunInTree run, List<BindingRecord> bound, Declaration takes) {
        requireNonNull(run, "StepInputs file source run must not be null");
        requireNonNull(bound, "StepInputs file source bindings must not be null");
        requireNonNull(takes, "StepInputs file source declaration must not be null");
        if (!hasFileFields(takes.fields())) {
            return Map.of();
        }
        Map<List<FieldName>, RunId> origins = new HashMap<>();
        for (BindingRecord binding : bound) {
            Resolved read = resolve(
                            run,
                            binding.binding().source(),
                            List.of(),
                            null,
                            List.of(),
                            "Binding " + binding.binding().id())
                    .orElseThrow(() -> new IllegalStateException("A standing binding lost its source"));
            List<FieldName> target = requireNonNull(binding.binding().target(), "a step's binding fills a field")
                    .names();
            for (Map.Entry<List<FieldName>, RunId> origin : read.origins().entrySet()) {
                origins.put(followed(target, origin.getKey()), origin.getValue());
            }
        }
        Map<FillPath, FileSource> files = new HashMap<>();
        fileSources(takes.fields(), filled(takes, bound), List.of(), List.of(), origins, files);
        return Map.copyOf(files);
    }

    private static boolean hasFileFields(List<Field> fields) {
        for (Field field : fields) {
            if (field.shape().kind() == FieldKind.FILE
                    || field.shape() instanceof FieldShape.Nested nested && hasFileFields(nested.fields())) {
                return true;
            }
        }
        return false;
    }

    /** Values and files bound into a newly started child, before any of its steps has been planned. */
    public record WorkflowInput(JsonObject values, Map<FillPath, FileSource> files) {}

    public static WorkflowInput workflowInput(RunInTree child) {
        if (child.above() == null) {
            throw new IllegalArgumentException("A root has no bound child input");
        }
        Declaration takes = child.run().workflow().takes();
        List<JsonMember> members = new ArrayList<>();
        Map<List<FieldName>, RunId> origins = new HashMap<>();
        for (Field field : takes.fields()) {
            Resolved read = fromInput(
                            child,
                            List.of(field.name()),
                            null,
                            List.of(),
                            "Binding " + child.run().run().value(),
                            false)
                    .orElse(null);
            members.add(new JsonMember(field.name().value(), read == null ? NONE : read.value()));
            if (read != null) {
                for (Map.Entry<List<FieldName>, RunId> origin : read.origins().entrySet()) {
                    origins.put(followed(List.of(field.name()), origin.getKey()), origin.getValue());
                }
            }
        }
        JsonObject values = new JsonObject(members);
        Map<FillPath, FileSource> files = new HashMap<>();
        fileSources(takes.fields(), values, List.of(), List.of(), origins, files);
        return new WorkflowInput(values, Map.copyOf(files));
    }

    private static void fileSources(
            List<Field> fields,
            JsonObject object,
            List<FieldName> names,
            List<FillPath.Step> steps,
            Map<List<FieldName>, RunId> origins,
            Map<FillPath, FileSource> files) {
        for (Field field : fields) {
            JsonValue value = member(object, field.name().value());
            List<FieldName> atNames = followed(names, List.of(field.name()));
            List<FillPath.Step> atSteps = new ArrayList<>(steps);
            atSteps.add(new FillPath.Named(field.name()));
            if (field.shape().kind() == FieldKind.FILE) {
                if (value instanceof JsonValue.JsonNull) {
                    continue;
                }
                if (value instanceof JsonArray many) {
                    for (int index = 0; index < many.items().size(); index++) {
                        List<FillPath.Step> item = new ArrayList<>(atSteps);
                        item.add(new FillPath.Place(index));
                        source(many.items().get(index), atNames, item, origins, files);
                    }
                } else {
                    source(value, atNames, atSteps, origins, files);
                }
            } else if (field.shape() instanceof FieldShape.Nested nested) {
                if (value instanceof JsonObject inner) {
                    fileSources(nested.fields(), inner, atNames, atSteps, origins, files);
                } else if (value instanceof JsonArray many) {
                    for (int index = 0; index < many.items().size(); index++) {
                        List<FillPath.Step> item = new ArrayList<>(atSteps);
                        item.add(new FillPath.Place(index));
                        if (many.items().get(index) instanceof JsonObject inner) {
                            fileSources(nested.fields(), inner, atNames, item, origins, files);
                        }
                    }
                }
            }
        }
    }

    private static void source(
            JsonValue value,
            List<FieldName> names,
            List<FillPath.Step> steps,
            Map<List<FieldName>, RunId> origins,
            Map<FillPath, FileSource> files) {
        StoredFile stored = StoredFile.fromKept(value);
        RunId run = null;
        int longest = -1;
        for (Map.Entry<List<FieldName>, RunId> origin : origins.entrySet()) {
            if (startsWith(names, origin.getKey()) && origin.getKey().size() > longest) {
                run = origin.getValue();
                longest = origin.getKey().size();
            }
        }
        if (run == null) {
            throw new IllegalStateException("A bound file has no trusted source run");
        }
        files.put(new FillPath(steps), new FileSource(run, stored.id()));
    }

    /**
     * What {@code aTry} of {@code step} took, one per binding it took through in the order written, read back from
     * what it was traced to in the tree, and otherwise what the binding reads now.
     */
    public static List<BindingRecord> took(RunInTree run, StepSnapshot step, TryRecord aTry) {
        return tookForReading(run, step, aTry).stream()
                .map(read -> read.orElseThrow(HistoricalSourceUnclear::new))
                .toList();
    }

    /** Saved inputs in planned order; an empty one has a component whose old producing source was not saved. */
    public static List<Optional<BindingRecord>> tookForReading(RunInTree run, StepSnapshot step, TryRecord aTry) {
        requireNonNull(run, "StepInputs run must not be null");
        requireNonNull(step, "StepInputs step must not be null");
        requireNonNull(aTry, "StepInputs try must not be null");
        Map<UUID, InputRecord> recorded = new HashMap<>();
        for (InputRecord input : aTry.inputs()) {
            if (recorded.putIfAbsent(input.binding(), input) != null) {
                throw new IllegalStateException("Try " + aTry.id().value() + " repeats binding " + input.binding());
            }
        }
        List<Optional<BindingRecord>> taken =
                new ArrayList<>(step.planned().bindings().size());
        for (Binding binding : step.planned().bindings()) {
            InputRecord input = recorded.remove(binding.id());
            if (input == null) {
                throw new IllegalStateException(
                        "Try " + aTry.id().value() + " has no input for binding " + binding.id());
            }
            taken.add(tookForReading(run, binding, input));
        }
        if (!recorded.isEmpty()) {
            throw new IllegalStateException("Try " + aTry.id().value() + " holds an unplanned input");
        }
        return List.copyOf(taken);
    }

    private static Optional<BindingRecord> tookForReading(RunInTree run, Binding binding, InputRecord input) {
        try {
            return Optional.of(took(run, binding, input));
        } catch (HistoricalSourceUnclear ignored) {
            return Optional.empty();
        }
    }

    public static BindingRecord took(RunInTree run, Binding binding, InputRecord input) {
        requireNonNull(run, "StepInputs run must not be null");
        requireNonNull(binding, "StepInputs binding must not be null");
        requireNonNull(input, "StepInputs input must not be null");
        if (!binding.id().equals(input.binding())) {
            throw new IllegalStateException("Binding " + binding.id() + " does not own its saved input");
        }
        Resolved read = taken(run, binding.source(), input, "Binding " + binding.id());
        return new BindingRecord(binding, read.value(), input.source(), read.sources(), input.parts());
    }

    public static List<ReadRecord> tookSearch(RunInTree run, List<SearchQuestionPart> question, TryRecord aTry) {
        requireNonNull(run, "StepInputs run must not be null");
        requireNonNull(question, "StepInputs search question must not be null");
        requireNonNull(aTry, "StepInputs try must not be null");
        Map<UUID, InputRecord> saved = new HashMap<>();
        for (InputRecord input : aTry.inputs()) {
            UUID part = input.searchQuestionPart();
            if (part == null || saved.putIfAbsent(part, input) != null) {
                throw new IllegalStateException("A search try holds distinct search question part inputs");
            }
        }
        List<ReadRecord> took = new ArrayList<>();
        for (SearchQuestionPart part : question) {
            if (part instanceof SearchQuestionPart.Read read) {
                InputRecord input = saved.remove(read.id());
                if (input == null) {
                    throw new IllegalStateException("A search try has no input for its read part");
                }
                took.add(took(run, read, input));
            }
        }
        if (!saved.isEmpty()) {
            throw new IllegalStateException("A search try holds an unplanned input");
        }
        return List.copyOf(took);
    }

    public static ReadRecord took(RunInTree run, SearchQuestionPart.Read part, InputRecord input) {
        requireNonNull(run, "StepInputs run must not be null");
        requireNonNull(part, "StepInputs read must not be null");
        requireNonNull(input, "StepInputs input must not be null");
        if (input.binding() != null || !part.id().equals(input.searchQuestionPart())) {
            throw new IllegalStateException("Search question part " + part.id() + " does not own its saved input");
        }
        Resolved read = taken(run, part.source(), input, "Search question part " + part.id());
        return new ReadRecord(part, read.value(), input.source(), read.sources(), input.parts());
    }

    /** A read with no saved source for a produced component is withheld by the reader. */
    public static Optional<ReadRecord> tookSearchForReading(
            RunInTree run, SearchQuestionPart.Read part, InputRecord input) {
        try {
            return Optional.of(took(run, part, input));
        } catch (HistoricalSourceUnclear ignored) {
            return Optional.empty();
        }
    }

    public static Optional<ReadRecord> bound(RunInTree run, SearchQuestionPart.Read part) {
        requireNonNull(run, "StepInputs run must not be null");
        requireNonNull(part, "StepInputs read must not be null");
        return resolve(run, part.source(), List.of(), null, List.of(), "Search question part " + part.id())
                .map(read -> new ReadRecord(part, read.value(), read.source(), read.sources(), read.parts()));
    }

    private static Resolved taken(RunInTree run, BindingSource from, InputRecord input, String owner) {
        ProductionValueId source = input.source();
        List<InputRecord.Part> parts = input.parts();
        Resolved read = resolve(run, from, List.of(), source, parts, owner)
                .orElseThrow(() -> new IllegalStateException(owner + " read nothing"));
        if (!Objects.equals(source, read.source())) {
            throw new IllegalStateException(owner + " took a value its source does not hold");
        }
        if (parts.size() != read.parts().size() || !parts.containsAll(read.parts())) {
            if (missingHistoricalSource(parts, read.parts())) {
                throw new HistoricalSourceUnclear();
            }
            throw new IllegalStateException(owner + " took components its source does not hold");
        }
        return read;
    }

    private static boolean missingHistoricalSource(List<InputRecord.Part> saved, List<InputRecord.Part> resolved) {
        if (saved.size() != resolved.size()) {
            return false;
        }
        Set<List<FieldName>> paths = new HashSet<>();
        List<InputRecord.Part> remaining = new ArrayList<>(resolved);
        boolean missing = false;
        for (InputRecord.Part part : saved) {
            if (!paths.add(part.targetPath())) {
                return false;
            }
            if (remaining.remove(part)) {
                continue;
            }
            if (part.source() != null) {
                return false;
            }
            InputRecord.Part produced = remaining.stream()
                    .filter(each -> each.targetPath().equals(part.targetPath()) && each.source() != null)
                    .findFirst()
                    .orElse(null);
            if (produced == null) {
                return false;
            }
            remaining.remove(produced);
            missing = true;
        }
        return missing && remaining.isEmpty();
    }

    /** Only an absent historical component maker, never a wrong or foreign maker. */
    public static final class HistoricalSourceUnclear extends IllegalStateException {

        private HistoricalSourceUnclear() {
            super("A saved component has no trustworthy producing source");
        }
    }

    /** Each binding's value, in the order written; none where a step it reads from has no value standing yet. */
    public static Optional<List<BindingRecord>> bound(RunInTree run, StepSnapshot step) {
        requireNonNull(run, "StepInputs run must not be null");
        requireNonNull(step, "StepInputs step must not be null");
        List<BindingRecord> bound = new ArrayList<>(step.planned().bindings().size());
        for (Binding binding : step.planned().bindings()) {
            Optional<BindingRecord> read = bound(run, binding);
            if (read.isEmpty()) {
                return Optional.empty();
            }
            bound.add(read.get());
        }
        return Optional.of(List.copyOf(bound));
    }

    /** One binding's value as it would be read now; none where the step it reads from has none standing yet. */
    public static Optional<BindingRecord> bound(RunInTree run, Binding binding) {
        requireNonNull(run, "StepInputs run must not be null");
        requireNonNull(binding, "StepInputs binding must not be null");
        return resolve(run, binding.source(), List.of(), null, List.of(), "Binding " + binding.id())
                .map(read -> new BindingRecord(binding, read.value(), read.source(), read.sources(), read.parts()));
    }

    private static Optional<Resolved> resolve(
            RunInTree run,
            BindingSource source,
            List<FieldName> suffix,
            @Nullable ProductionValueId historical,
            List<InputRecord.Part> historicalParts,
            String owner) {
        return resolve(run, source, suffix, historical, historicalParts, owner, false);
    }

    private static Optional<Resolved> resolve(
            RunInTree run,
            BindingSource source,
            List<FieldName> suffix,
            @Nullable ProductionValueId historical,
            List<InputRecord.Part> historicalParts,
            String owner,
            boolean savedComponent) {
        return switch (source) {
            case BindingSource.Written written -> {
                if (historical != null || !historicalParts.isEmpty()) {
                    throw new IllegalStateException(owner + " took a value its source does not hold");
                }
                yield Optional.of(new Resolved(within(written.constant(), suffix), null, Map.of()));
            }
            case BindingSource.WorkflowInput input ->
                fromInput(
                        run,
                        followed(input.pointer().names(), suffix),
                        historical,
                        historicalParts,
                        owner,
                        savedComponent);
            case BindingSource.StepOutput output ->
                fromStep(
                        run,
                        output,
                        followed(output.pointer().names(), suffix),
                        historical,
                        historicalParts,
                        owner,
                        savedComponent);
        };
    }

    private static Optional<Resolved> fromInput(
            RunInTree run,
            List<FieldName> path,
            @Nullable ProductionValueId historical,
            List<InputRecord.Part> historicalParts,
            String owner,
            boolean savedComponent) {
        RunInTree above = run.above();
        if (above == null) {
            JsonObject started = run.run().startedWith();
            if (started == null) {
                throw new IllegalStateException(
                        "Run " + run.run().run().value() + " beneath another reads what no run above bound into it");
            }
            if (historical != null || !historicalParts.isEmpty()) {
                throw new IllegalStateException(owner + " took a value its source does not hold");
            }
            return Optional.of(new Resolved(
                    within(started, path), null, Map.of(List.of(), run.run().run())));
        }
        StepSnapshot parent = requireNonNull(run.from(), "a run beneath names its parent step");
        List<Binding> bindings =
                switch (parent.planned().runs()) {
                    case StepRuns.Workflow ignored -> parent.planned().bindings();
                    case StepRuns.Route route -> {
                        UUID chosen = requireNonNull(parent.beneath(), "a run beneath a route names its case")
                                .routeCase();
                        yield route.cases().stream()
                                .filter(aCase -> aCase.id().equals(chosen))
                                .findFirst()
                                .orElseThrow(() -> new IllegalStateException("Run "
                                        + run.run().run().value() + " names a route case its parent does not hold"))
                                .bindings();
                    }
                    case StepRuns.Question ignored ->
                        throw new IllegalStateException("Only a workflow or route starts a run");
                    case StepRuns.Code ignored ->
                        throw new IllegalStateException("Only a workflow or route starts a run");
                    case StepRuns.Search ignored ->
                        throw new IllegalStateException("Only a workflow or route starts a run");
                };
        return fromBindings(
                above,
                bindings,
                run.run().workflow().takes(),
                path,
                historical,
                historicalParts,
                owner,
                savedComponent);
    }

    private static Optional<Resolved> fromStep(
            RunInTree run,
            BindingSource.StepOutput output,
            List<FieldName> path,
            @Nullable ProductionValueId historical,
            List<InputRecord.Part> historicalParts,
            String owner,
            boolean savedComponent) {
        StepSnapshot source = run.run()
                .step(new WorkflowStepId(output.step()))
                .orElseThrow(() -> new IllegalStateException(owner + " reads a step its version does not hold"));
        if (source.planned().runs() instanceof StepRuns.Workflow
                || source.planned().runs() instanceof StepRuns.Route) {
            if (source.beneath() == null) {
                return Optional.empty();
            }
            RunInTree child = run.beneath(source);
            return fromBindings(
                    child,
                    child.run().workflow().outputs(),
                    child.run().workflow().gives(),
                    path,
                    historical,
                    historicalParts,
                    owner,
                    savedComponent);
        }
        if (!historicalParts.isEmpty()) {
            throw new IllegalStateException(owner + " took components its source does not hold");
        }
        if (savedComponent && historical == null) {
            throw new HistoricalSourceUnclear();
        }
        return fromProduced(run, source, path, historical, owner);
    }

    private static Optional<Resolved> fromProduced(
            RunInTree run,
            StepSnapshot source,
            List<FieldName> path,
            @Nullable ProductionValueId historical,
            String owner) {
        if (historical != null) {
            for (TryRecord aTry : source.tries()) {
                for (ValueRecord value : aTry.values()) {
                    if (value.id().equals(historical)) {
                        if (!value.field().equals(path.getFirst().value())) {
                            throw new IllegalStateException(owner + " took a value its step does not hold");
                        }
                        return Optional.of(produced(run, source, path, value));
                    }
                }
            }
            throw new IllegalStateException(owner + " took a value its step does not hold");
        }
        TryRecord newest = source.newest().filter(TryRecord::yielded).orElse(null);
        if (newest == null) {
            return Optional.empty();
        }
        String first = path.getFirst().value();
        ValueRecord value = newest.values().stream()
                .filter(held -> held.field().equals(first))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(owner + " reads a field its step gave nothing back for"));
        if (ValueStanding.of(newest, value) != ValueStanding.STANDS) {
            return Optional.empty();
        }
        return Optional.of(produced(run, source, path, value));
    }

    private static Resolved produced(RunInTree run, StepSnapshot source, List<FieldName> path, ValueRecord value) {
        JsonValue read = within(value.value(), path.subList(1, path.size()));
        List<CodeRead> codeReads = source.planned().runs() instanceof StepRuns.Code
                ? List.of(new CodeRead(run.run().run(), source, new Pointer(path), read, List.of()))
                : List.of();
        return new Resolved(
                read,
                value.id(),
                List.of(value.id()),
                List.of(),
                Map.of(List.of(), run.run().run()),
                codeReads);
    }

    private static Optional<Resolved> fromBindings(
            RunInTree run,
            List<Binding> bindings,
            Declaration declared,
            List<FieldName> path,
            @Nullable ProductionValueId historical,
            List<InputRecord.Part> historicalParts,
            String owner,
            boolean savedComponent) {
        Pointer wanted = new Pointer(path);
        for (Binding each : bindings) {
            Pointer target = requireNonNull(each.target(), "an input or output fills a field");
            if (wanted.within(target)) {
                return resolve(
                        run,
                        each.source(),
                        path.subList(target.names().size(), path.size()),
                        historical,
                        historicalParts,
                        owner,
                        savedComponent);
            }
        }
        if (historical != null) {
            throw new IllegalStateException(owner + " took a value its source does not hold");
        }
        List<BindingRecord> parts = new ArrayList<>();
        List<CodeRead> codeReads = new ArrayList<>();
        Map<List<FieldName>, RunId> origins = new HashMap<>();
        Set<List<FieldName>> savedPaths = new HashSet<>();
        for (InputRecord.Part saved : historicalParts) {
            if (!savedPaths.add(saved.targetPath())) {
                throw new IllegalStateException(owner + " repeats a historical component");
            }
        }
        Set<List<FieldName>> matchedPaths = new HashSet<>();
        boolean unclear = false;
        for (Binding each : bindings) {
            Pointer target = requireNonNull(each.target(), "an input or output fills a field");
            if (target.within(wanted)) {
                List<FieldName> relative = target.names()
                        .subList(wanted.names().size(), target.names().size());
                ProductionValueId directSource = null;
                List<InputRecord.Part> nested = List.of();
                boolean directComponent = false;
                if (!historicalParts.isEmpty()) {
                    List<InputRecord.Part> matching = historicalParts.stream()
                            .filter(saved -> startsWith(saved.targetPath(), relative))
                            .toList();
                    if (matching.isEmpty()) {
                        throw new IllegalStateException(owner + " has no historical component at " + target);
                    }
                    boolean direct = matching.size() == 1
                            && matching.getFirst().targetPath().equals(relative);
                    directComponent = direct;
                    matching.forEach(saved -> matchedPaths.add(saved.targetPath()));
                    nested = direct
                            ? List.of()
                            : matching.stream()
                                    .map(saved -> new InputRecord.Part(
                                            saved.targetPath()
                                                    .subList(
                                                            relative.size(),
                                                            saved.targetPath().size()),
                                            saved.source()))
                                    .toList();
                    directSource = direct ? matching.getFirst().source() : null;
                }
                Optional<Resolved> read;
                try {
                    read = resolve(
                            run,
                            each.source(),
                            List.of(),
                            directSource,
                            nested,
                            "Binding " + each.id(),
                            directComponent);
                } catch (HistoricalSourceUnclear ignored) {
                    unclear = true;
                    continue;
                }
                if (read.isEmpty()) {
                    return Optional.empty();
                }
                Resolved part = read.get();
                parts.add(new BindingRecord(each, part.value(), part.source(), part.sources(), part.parts()));
                for (CodeRead codeRead : part.codeReads()) {
                    codeReads.add(new CodeRead(
                            codeRead.run(),
                            codeRead.step(),
                            codeRead.field(),
                            codeRead.value(),
                            followed(relative, codeRead.target())));
                }
                for (Map.Entry<List<FieldName>, RunId> origin : part.origins().entrySet()) {
                    origins.put(followed(relative, origin.getKey()), origin.getValue());
                }
            }
        }
        if (!matchedPaths.equals(savedPaths)) {
            throw new IllegalStateException(owner + " took components its source does not hold");
        }
        if (unclear) {
            throw new HistoricalSourceUnclear();
        }
        if (parts.isEmpty()) {
            return Optional.empty();
        }
        List<InputRecord.Part> components = new ArrayList<>();
        for (BindingRecord part : parts) {
            List<FieldName> target = requireNonNull(part.binding().target(), "a component fills a field")
                    .names();
            List<FieldName> relative = target.subList(wanted.names().size(), target.size());
            if (part.parts().isEmpty()) {
                components.add(new InputRecord.Part(relative, part.source()));
            } else {
                for (InputRecord.Part nested : part.parts()) {
                    components.add(new InputRecord.Part(followed(relative, nested.targetPath()), nested.source()));
                }
            }
        }
        List<ProductionValueId> sources = components.stream()
                .map(InputRecord.Part::source)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        return Optional.of(new Resolved(
                within(filled(declared, parts), path),
                null,
                sources,
                components,
                narrowed(origins, path),
                List.copyOf(codeReads)));
    }

    private static boolean startsWith(List<FieldName> path, List<FieldName> prefix) {
        return path.size() >= prefix.size() && path.subList(0, prefix.size()).equals(prefix);
    }

    private static List<FieldName> followed(List<FieldName> names, List<FieldName> suffix) {
        if (suffix.isEmpty()) {
            return names;
        }
        List<FieldName> followed = new ArrayList<>(names.size() + suffix.size());
        followed.addAll(names);
        followed.addAll(suffix);
        return List.copyOf(followed);
    }

    private static Map<List<FieldName>, RunId> narrowed(Map<List<FieldName>, RunId> origins, List<FieldName> path) {
        Map<List<FieldName>, RunId> narrow = new HashMap<>();
        for (Map.Entry<List<FieldName>, RunId> origin : origins.entrySet()) {
            List<FieldName> prefix = origin.getKey();
            if (startsWith(path, prefix)) {
                narrow.put(List.of(), origin.getValue());
            } else if (startsWith(prefix, path)) {
                narrow.put(List.copyOf(prefix.subList(path.size(), prefix.size())), origin.getValue());
            }
        }
        return Map.copyOf(narrow);
    }

    private record Resolved(
            JsonValue value,
            @Nullable ProductionValueId source,
            List<ProductionValueId> sources,
            List<InputRecord.Part> parts,
            Map<List<FieldName>, RunId> origins,
            List<CodeRead> codeReads) {

        private Resolved(JsonValue value, @Nullable ProductionValueId source, Map<List<FieldName>, RunId> origins) {
            this(value, source, source == null ? List.of() : List.of(source), List.of(), origins, List.of());
        }
    }

    private record CodeRead(RunId run, StepSnapshot step, Pointer field, JsonValue value, List<FieldName> target) {
        HoldRecord.SourceRecord maker() {
            return new HoldRecord.SourceRecord(
                    run,
                    requireNonNull(step.runStep(), "A produced code value belongs to a written step"),
                    step.planned().id().value());
        }
    }

    private record ProductionRead(Resolved input, @Nullable Pointer target) {}

    /** What stands at those names within {@code value}, a missing member or anything not an object being none. */
    public static JsonValue within(JsonValue value, List<FieldName> names) {
        requireNonNull(value, "StepInputs value must not be null");
        requireNonNull(names, "StepInputs names must not be null");
        JsonValue here = value;
        for (FieldName name : names) {
            if (!(here instanceof JsonObject object)) {
                return NONE;
            }
            here = member(object, name.value());
        }
        return here;
    }

    private static JsonValue member(JsonObject object, String name) {
        for (JsonMember member : object.members()) {
            if (member.name().equals(name)) {
                return member.value();
            }
        }
        return NONE;
    }

    /**
     * Each binding beside the one field it fills of {@code takes}, in the order written: one within many names no
     * place, and no two fill one place or one within the other, as submitting refuses.
     */
    private static List<Reached> reached(List<BindingRecord> bound, Declaration takes) {
        List<Reached> reached = new ArrayList<>(bound.size());
        for (BindingRecord input : bound) {
            Pointer target = input.binding().target();
            if (target == null) {
                throw new IllegalStateException("Binding " + input.binding().id() + " fills no input");
            }
            if (!(target.reach(takes.fields()) instanceof Pointer.Reach.At)) {
                throw new IllegalStateException("Binding " + input.binding().id() + " fills nothing its step takes");
            }
            for (Reached earlier : reached) {
                if (target.within(earlier.target()) || earlier.target().within(target)) {
                    throw new IllegalStateException(
                            "Binding " + input.binding().id() + " fills what binding " + earlier.id() + " fills");
                }
            }
            reached.add(new Reached(input, target));
        }
        return reached;
    }

    private static JsonObject object(List<Field> fields, int depth, List<Reached> within) {
        List<JsonMember> members = new ArrayList<>(fields.size());
        for (Field field : fields) {
            List<Reached> here = null;
            for (Reached each : within) {
                if (each.target().names().get(depth).equals(field.name())) {
                    if (here == null) {
                        here = new ArrayList<>();
                    }
                    here.add(each);
                }
            }
            members.add(new JsonMember(field.name().value(), here == null ? NONE : filled(field, depth, here)));
        }
        return new JsonObject(members);
    }

    private static JsonValue filled(Field field, int depth, List<Reached> here) {
        Reached first = here.getFirst();
        if (first.target().names().size() == depth + 1) {
            // Alone here: reached refuses any other binding at this field or within it.
            return completed(field, first.input().value(), first.id());
        }
        // Reached through it, so it holds one value of fields: Pointer.reach stops at anything else.
        return object(((FieldShape.Nested) field.shape()).fields(), depth + 1, here);
    }

    /** Every field a value of fields declares is there, none where nothing was given for it; no other may be. */
    private static JsonValue completed(Field field, JsonValue value, UUID binding) {
        if (!(field.shape() instanceof FieldShape.Nested nested)) {
            return value;
        }
        if (field.howMany() instanceof HowMany.One) {
            return value instanceof JsonObject object ? members(nested.fields(), object, binding) : value;
        }
        if (!(value instanceof JsonArray many)) {
            return value;
        }
        List<JsonValue> items = new ArrayList<>(many.items().size());
        for (JsonValue item : many.items()) {
            items.add(item instanceof JsonObject object ? members(nested.fields(), object, binding) : item);
        }
        return new JsonArray(items);
    }

    private static JsonObject members(List<Field> fields, JsonObject object, UUID binding) {
        List<JsonMember> members = new ArrayList<>(fields.size());
        int found = 0;
        for (Field inner : fields) {
            JsonValue value = NONE;
            for (JsonMember member : object.members()) {
                if (member.name().equals(inner.name().value())) {
                    value = member.value();
                    found++;
                    break;
                }
            }
            members.add(new JsonMember(inner.name().value(), completed(inner, value, binding)));
        }
        if (found != object.members().size()) {
            throw new IllegalStateException("Binding " + binding + " fills a value holding a field not declared");
        }
        return new JsonObject(members);
    }

    /** A binding beside where it fills. */
    private record Reached(BindingRecord input, Pointer target) {

        UUID id() {
            return input.binding().id();
        }
    }

    /** One search question read beside its value and ultimate makers. */
    public record ReadRecord(
            @DoNotLog SearchQuestionPart.Read read,
            @DoNotLog JsonValue value,
            @Nullable ProductionValueId source,
            @DoNotLog List<ProductionValueId> sources,
            @DoNotLog List<InputRecord.Part> parts) {

        public ReadRecord {
            requireNonNull(read, "StepInputs.ReadRecord read must not be null");
            requireNonNull(value, "StepInputs.ReadRecord value must not be null");
            sources = List.copyOf(requireNonNull(sources, "StepInputs.ReadRecord sources must not be null"));
            parts = List.copyOf(requireNonNull(parts, "StepInputs.ReadRecord parts must not be null"));
            if (source != null && !sources.equals(List.of(source))) {
                throw new IllegalArgumentException("One direct value has one producing source");
            }
        }
    }

    /** One binding's value and ultimate sources; source is present only where one produced value made it. */
    public record BindingRecord(
            @DoNotLog Binding binding,
            @DoNotLog JsonValue value,
            @Nullable ProductionValueId source,
            @DoNotLog List<ProductionValueId> sources,
            @DoNotLog List<InputRecord.Part> parts) {

        public BindingRecord {
            requireNonNull(binding, "StepInputs.BindingRecord binding must not be null");
            requireNonNull(value, "StepInputs.BindingRecord value must not be null");
            sources = List.copyOf(requireNonNull(sources, "StepInputs.BindingRecord sources must not be null"));
            parts = List.copyOf(requireNonNull(parts, "StepInputs.BindingRecord parts must not be null"));
            if (source != null && !sources.equals(List.of(source))) {
                throw new IllegalArgumentException("One direct value has one producing source");
            }
        }

        public BindingRecord(
                Binding binding, JsonValue value, @Nullable ProductionValueId source, List<ProductionValueId> sources) {
            this(binding, value, source, sources, List.of());
        }
    }
}
