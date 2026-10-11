package com.lilradish.lite.app.library;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.app.search.SearchWays;
import com.lilradish.lite.domain.declaration.Asking;
import com.lilradish.lite.domain.declaration.FieldName;
import com.lilradish.lite.domain.declaration.FieldStanding;
import com.lilradish.lite.domain.identity.GroupId;
import com.lilradish.lite.domain.model.ModelMode;
import com.lilradish.lite.domain.model.ModelName;
import com.lilradish.lite.domain.registry.EntryKind;
import com.lilradish.lite.domain.registry.EntryVersionId;
import com.lilradish.lite.domain.run.Ceiling;
import com.lilradish.lite.domain.search.SearchWay;
import com.lilradish.lite.domain.wire.StoreLabels;
import com.lilradish.lite.domain.workflow.Binding;
import com.lilradish.lite.domain.workflow.BindingSource;
import com.lilradish.lite.domain.workflow.ModelChoice;
import com.lilradish.lite.domain.workflow.Pointer;
import com.lilradish.lite.domain.workflow.Producer;
import com.lilradish.lite.domain.workflow.RouteCase;
import com.lilradish.lite.domain.workflow.StepId;
import com.lilradish.lite.domain.workflow.StepKind;
import com.lilradish.lite.domain.workflow.StepLabel;
import com.lilradish.lite.domain.workflow.StepProducer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A workflow version's content as the store holds it: what it takes and gives back, its steps in the order they
 * run, what fills each input and output, and how its runs may spend and be helped. A stored value its type
 * refuses fails the read rather than being shown or left out.
 *
 * @param revision how many times the draft's content has been written, which a save names as the one it read
 * @param ceiling none where runs of it have none
 * @param helper none where no model is named to help
 */
record StoredWorkflow(
        int revision,
        StoredDeclarations.Half takes,
        StoredDeclarations.Half gives,
        List<Step> steps,
        List<Binding> outputs,
        @Nullable Ceiling ceiling,
        boolean keepsOwnCeiling,
        boolean raiseNeedsApproval,
        boolean mayBeHelped,
        @Nullable ModelChoice helper) {

    // DB-SPECIFIC: enum and jsonb casts to text, array casts and any(…) are PostgreSQL's.
    private static final String VERSION = """
            select workflow.entry_version_id,
                   version.revision,
                   workflow.ceiling,
                   workflow.keeps_own_ceiling,
                   workflow.raise_needs_approval,
                   workflow.may_be_helped,
                   workflow.helper_model,
                   workflow.helper_mode
              from workflow_versions workflow
              join entry_versions version on version.entry_version_id = workflow.entry_version_id
             where workflow.entry_version_id = any(cast(:versions as uuid[]))
            """;

    /* The key breaks a tie a hand-written row may hold, the same way each time. */
    private static final String STEPS = """
            select step.entry_version_id,
                   step.workflow_step_id,
                   step.name,
                   step.label,
                   cast(step.kind as text) as kind,
                   step.pinned_version_id,
                   cast(step.pinned_kind as text) as pinned_kind,
                   cast(step.code_step as text) as code_step,
                   cast(step.producer as text) as producer,
                   step.producer_model,
                   step.producer_mode,
                   step.tries,
                   step.reviewer_model,
                   step.reviewer_mode,
                   step.tells_what_happened,
                   step.search_way,
                   cast(step.search_title_standing as text) as search_title_standing,
                   cast(step.search_body_standing as text) as search_body_standing,
                   cast(step.search_references_standing as text) as search_references_standing,
                   cast(step.search_other_standing as text) as search_other_standing
              from workflow_steps step
             where step.entry_version_id = any(cast(:versions as uuid[]))
             order by step.position, step.workflow_step_id
            """;

    /* Keys are drawn in the order the cases were written, so they give that order back. */
    private static final String CASES = """
            select route.route_case_id, route.workflow_step_id, route.term, route.target_version_id
              from route_cases route
             where route.entry_version_id = any(cast(:versions as uuid[]))
             order by route.route_case_id
            """;

    private static final String BINDINGS = """
            select binding.entry_version_id,
                   binding.binding_id,
                   binding.workflow_step_id,
                   binding.route_case_id,
                   binding.target_path,
                   binding.source_step_id,
                   binding.source_path,
                   cast(binding.constant as text) as constant
              from bindings binding
             where binding.entry_version_id = any(cast(:versions as uuid[]))
             order by binding.binding_id
            """;

    StoredWorkflow {
        requireNonNull(takes, "StoredWorkflow takes must not be null");
        requireNonNull(gives, "StoredWorkflow gives must not be null");
        steps = List.copyOf(requireNonNull(steps, "StoredWorkflow steps must not be null"));
        outputs = List.copyOf(requireNonNull(outputs, "StoredWorkflow outputs must not be null"));
    }

    /** Inside the caller's transaction; none where the version holds no workflow content. */
    static Optional<StoredWorkflow> read(JdbcClient database, EntryVersionId version) {
        return Optional.ofNullable(readAll(database, List.of(version)).get(version));
    }

    /**
     * Inside the caller's transaction, in as many statements however many {@code versions} are read: each that
     * holds workflow content, one that holds none left out.
     */
    static Map<EntryVersionId, StoredWorkflow> readAll(JdbcClient database, Collection<EntryVersionId> versions) {
        requireNonNull(versions, "StoredWorkflow versions must not be null");
        if (versions.isEmpty()) {
            return Map.of();
        }
        String[] spelled = PinnedVersions.spelled(versions);
        Map<EntryVersionId, Settings> settings = new LinkedHashMap<>();
        database.sql(VERSION).param("versions", spelled).query(result -> {
            EntryVersionId version = versionOf(result);
            settings.put(version, settings(result, version));
        });
        if (settings.isEmpty()) {
            return Map.of();
        }
        Map<EntryVersionId, EntryKind> workflows = HashMap.newHashMap(settings.size());
        settings.keySet().forEach(version -> workflows.put(version, EntryKind.WORKFLOW));
        Map<EntryVersionId, StoredDeclarations.Halves> halves = StoredDeclarations.ofVersions(database, workflows);
        Map<EntryVersionId, List<StepRow>> stepRows = new HashMap<>();
        database.sql(STEPS).param("versions", spelled).query(result -> {
            EntryVersionId version = versionOf(result);
            stepRows.computeIfAbsent(version, ignored -> new ArrayList<>()).add(stepRow(result, version));
        });
        Map<UUID, List<CaseRow>> casesByStep = new HashMap<>();
        database.sql(CASES).param("versions", spelled).query(result -> {
            UUID target = result.getObject("target_version_id", UUID.class);
            casesByStep
                    .computeIfAbsent(result.getObject("workflow_step_id", UUID.class), ignored -> new ArrayList<>())
                    .add(new CaseRow(
                            result.getObject("route_case_id", UUID.class),
                            result.getString("term"),
                            target == null ? null : new EntryVersionId(target)));
        });
        Map<EntryVersionId, Consumers> consumers = new HashMap<>();
        database.sql(BINDINGS).param("versions", spelled).query(result -> {
            EntryVersionId version = versionOf(result);
            consumers.computeIfAbsent(version, ignored -> new Consumers()).add(result, version);
        });
        List<UUID> routes = stepRows.values().stream()
                .flatMap(List::stream)
                .filter(row -> row.kind() == StepKind.ROUTE)
                .map(StepRow::id)
                .toList();
        Map<UUID, StoredDeclarations.Half> routeGives =
                StoredDeclarations.ofRoutes(database, settings.keySet(), routes);
        Map<EntryVersionId, StoredWorkflow> read = LinkedHashMap.newLinkedHashMap(settings.size());
        settings.forEach((version, held) -> {
            Consumers bound = consumers.getOrDefault(version, new Consumers());
            List<Step> steps = new ArrayList<>();
            for (StepRow row : stepRows.getOrDefault(version, List.of())) {
                steps.add(row.step(bound, casesByStep, routeGives));
            }
            StoredDeclarations.Halves declared = requireNonNull(halves.get(version));
            read.put(
                    version,
                    new StoredWorkflow(
                            held.revision(),
                            declared.takes(),
                            declared.gives(),
                            steps,
                            bound.outputs,
                            held.ceiling(),
                            held.keepsOwnCeiling(),
                            held.raiseNeedsApproval(),
                            held.mayBeHelped(),
                            held.helper()));
        });
        return read;
    }

    private static EntryVersionId versionOf(ResultSet result) throws SQLException {
        return new EntryVersionId(result.getObject("entry_version_id", UUID.class));
    }

    /** Every version its steps and cases pin, each once, in the order the steps read. */
    Set<EntryVersionId> pinned() {
        Set<EntryVersionId> pinned = new LinkedHashSet<>();
        for (Step step : steps) {
            switch (step.runs()) {
                case Runs.Pinned held -> pinned.add(held.version());
                case Runs.Route route ->
                    route.cases().stream()
                            .map(RouteCase::target)
                            .filter(target -> target != null)
                            .forEach(pinned::add);
                case Runs.Unchosen ignored -> {}
                case Runs.Code ignored -> {}
                case Runs.Search search -> {
                    if (search.question() != null) {
                        pinned.add(search.question());
                    }
                }
            }
        }
        return pinned;
    }

    /** Every reference list version a field of its own, or of a route of it, pins, each once. */
    Set<EntryVersionId> listsPinned() {
        Set<EntryVersionId> pinned = new LinkedHashSet<>();
        new StoredDeclarations.Halves(takes, gives).listsPinnedInto(pinned);
        for (Step step : steps) {
            if (step.runs() instanceof Runs.Route route) {
                StoredDeclarations.pinnedIn(route.gives().declaration().fields(), pinned);
            }
        }
        return pinned;
    }

    /** Where in the list each step runs, by its key; worked out once for whatever asks it of many bindings. */
    Map<UUID, Integer> positions() {
        Map<UUID, Integer> positions = HashMap.newHashMap(steps.size());
        for (int index = 0; index < steps.size(); index++) {
            positions.put(steps.get(index).id(), index);
        }
        return positions;
    }

    boolean namesCode() {
        return steps.stream().anyMatch(step -> step.runs() instanceof Runs.Code);
    }

    /**
     * Inside the caller's transaction: what the version reaches beyond itself, judged against its group, against
     * what {@code released} declares of the code steps it names, against which of those {@code here} lets the
     * group name, and against the ways to search {@code ways} holds.
     */
    WorkflowProblems.Resolved resolvedIn(
            JdbcClient database, GroupId group, ReleasedCodeSteps released, CodeStepsHere here, SearchWays ways) {
        Map<EntryVersionId, StoredDeclarations.Halves> declared =
                StoredDeclarations.ofGroupVersions(database, group, pinned());
        Map<String, StoredDeclarations.Halves> codeSteps = new HashMap<>();
        Map<String, SearchWay> searchWays = new HashMap<>();
        for (Step step : steps) {
            if (step.runs() instanceof Runs.Code code
                    && code.codeStep() != null
                    && released.of(code.codeStep()) instanceof StoredDeclarations.Halves halves) {
                codeSteps.put(code.codeStep(), halves);
            }
            if (step.runs() instanceof Runs.Search search && search.way() != null) {
                ways.find(search.way()).ifPresent(way -> searchWays.put(way.name(), way));
            }
        }
        Set<EntryVersionId> lists = listsPinned();
        declared.values().forEach(halves -> halves.listsPinnedInto(lists));
        codeSteps.forEach((name, halves) -> {
            if (here.nameable().contains(name)) {
                halves.listsPinnedInto(lists);
            }
        });
        Map<EntryVersionId, StoredDeclarations.Halves> searchQuestions = new HashMap<>();
        for (Step step : steps) {
            if (step.runs() instanceof Runs.Search search
                    && search.question() != null
                    && declared.get(search.question()) instanceof StoredDeclarations.Halves halves) {
                searchQuestions.put(search.question(), halves);
            }
        }
        List<@Nullable StepLabel> labels = new ArrayList<>();
        steps.forEach(step -> labels.add(step.label()));
        Map<Integer, String> clashes =
                labelClashesIn(database, steps.stream().map(Step::name).toList(), labels);
        Map<UUID, String> labelsClashing = new HashMap<>();
        clashes.forEach(
                (position, name) -> labelsClashing.put(steps.get(position).id(), name));
        return new WorkflowProblems.Resolved(
                declared,
                StoredQuestion.offered(database, group, lists),
                here.nameable(),
                codeSteps,
                here.listsUnserved(),
                askedIn(database, group, declared),
                searchWays,
                StoredQuestion.readAll(database, searchQuestions),
                labelsClashing);
    }

    static Map<Integer, String> labelClashesIn(
            JdbcClient database, List<StepId> names, List<@Nullable StepLabel> labels) {
        if (labels.stream().allMatch(label -> label == null)) {
            return Map.of();
        }
        List<String> readable =
                names.stream().map(name -> name.value().replace('_', ' ')).toList();
        List<String> toFold = new ArrayList<>(readable);
        labels.stream().filter(label -> label != null).forEach(label -> toFold.add(label.value()));
        Map<String, String> folded = new HashMap<>();
        // DB-SPECIFIC: the PostgreSQL text[] type suffix types the bound name array.
        database.sql("select name, search_fold(name) as folded from unnest(cast(:names as text[])) named(name)")
                .param("names", toFold.toArray(String[]::new))
                .query((row, _) -> Map.entry(row.getString("name"), row.getString("folded")))
                .list()
                .forEach(name -> folded.put(name.getKey(), name.getValue()));
        Map<String, List<Integer>> shownAt = new HashMap<>();
        for (int position = 0; position < names.size(); position++) {
            shownAt.computeIfAbsent(requireNonNull(folded.get(readable.get(position))), _ -> new ArrayList<>())
                    .add(position);
            StepLabel label = labels.get(position);
            if (label != null) {
                shownAt.computeIfAbsent(requireNonNull(folded.get(label.value())), _ -> new ArrayList<>())
                        .add(position);
            }
        }
        Map<Integer, String> clashes = new HashMap<>();
        for (int position = 0; position < labels.size(); position++) {
            StepLabel label = labels.get(position);
            if (label == null) {
                continue;
            }
            for (int other : requireNonNull(shownAt.get(requireNonNull(folded.get(label.value()))))) {
                if (other != position) {
                    StepLabel otherLabel = labels.get(other);
                    clashes.put(position, otherLabel == null ? readable.get(other) : otherLabel.value());
                    break;
                }
            }
        }
        return Map.copyOf(clashes);
    }

    /**
     * Inside the caller's transaction: what a model is told of each question of the group's a step asks a model
     * of, producing or reviewing, from what {@code declared} holds of it; read once however many steps ask it.
     */
    Map<EntryVersionId, Asking> askedIn(
            JdbcClient database, GroupId group, Map<EntryVersionId, StoredDeclarations.Halves> declared) {
        Map<EntryVersionId, StoredDeclarations.Halves> asked = new HashMap<>();
        for (Step step : steps) {
            if (step.runs() instanceof Runs.Search search
                    && search.question() != null
                    && declared.get(search.question()) instanceof StoredDeclarations.Halves halves) {
                asked.put(search.question(), halves);
            }
            if (step.runs() instanceof Runs.Pinned pinned
                    && pinned.kind() == EntryKind.QUESTION
                    && (step.producer() instanceof Producer.Model || step.reviewer() != null)
                    && declared.get(pinned.version()) instanceof StoredDeclarations.Halves halves) {
                asked.put(pinned.version(), halves);
            }
        }
        return StoredQuestion.askings(database, group, asked);
    }

    private static Settings settings(ResultSet result, EntryVersionId version) throws SQLException {
        Long ceiling = result.getObject("ceiling", Long.class);
        try {
            return new Settings(
                    result.getInt("revision"),
                    ceiling == null ? null : new Ceiling(ceiling),
                    result.getBoolean("keeps_own_ceiling"),
                    result.getBoolean("raise_needs_approval"),
                    result.getBoolean("may_be_helped"),
                    choice(result.getString("helper_model"), result.getString("helper_mode")));
        } catch (IllegalArgumentException refused) {
            throw new IllegalStateException(
                    "Version " + version.value() + " holds a setting this system will not read", refused);
        }
    }

    private static StepRow stepRow(ResultSet result, EntryVersionId version) throws SQLException {
        UUID id = result.getObject("workflow_step_id", UUID.class);
        try {
            String kind = result.getString("kind");
            UUID pinned = result.getObject("pinned_version_id", UUID.class);
            String producer = result.getString("producer");
            String label = result.getString("label");
            return new StepRow(
                    id,
                    new StepId(result.getString("name")),
                    label == null ? null : new StepLabel(label),
                    kind == null ? null : StoreLabels.parse(StepKind.class, kind),
                    pinned == null
                            ? null
                            : new Runs.Pinned(
                                    StoreLabels.parse(EntryKind.class, result.getString("pinned_kind")),
                                    new EntryVersionId(pinned)),
                    result.getString("code_step"),
                    producer == null
                            ? null
                            : producer(
                                    StoreLabels.parse(StepProducer.class, producer),
                                    choice(result.getString("producer_model"), result.getString("producer_mode")),
                                    result.getBoolean("tells_what_happened")),
                    result.getObject("tries", Integer.class),
                    choice(result.getString("reviewer_model"), result.getString("reviewer_mode")),
                    result.getString("search_way"),
                    standings(result));
        } catch (IllegalArgumentException refused) {
            throw new IllegalStateException(
                    "Version " + version.value() + " holds a step " + id + " this system will not read", refused);
        }
    }

    private static Map<FieldName, FieldStanding> standings(ResultSet result) throws SQLException {
        Map<FieldName, FieldStanding> standings = HashMap.newHashMap(SearchWay.FIELDS.size());
        standing(standings, SearchWay.TITLE, result.getString("search_title_standing"));
        standing(standings, SearchWay.BODY, result.getString("search_body_standing"));
        standing(standings, SearchWay.REFERENCES, result.getString("search_references_standing"));
        standing(standings, SearchWay.OTHER, result.getString("search_other_standing"));
        return standings;
    }

    private static void standing(Map<FieldName, FieldStanding> standings, FieldName field, @Nullable String stored) {
        if (stored != null) {
            standings.put(field, StoreLabels.parse(FieldStanding.class, stored));
        }
    }

    /** What a binding reads: this workflow's input where it names no step. */
    private static BindingSource readFrom(ResultSet result) throws SQLException {
        Pointer pointer = Pointer.parse(requireNonNull(result.getString("source_path")));
        UUID step = result.getObject("source_step_id", UUID.class);
        return step == null ? new BindingSource.WorkflowInput(pointer) : new BindingSource.StepOutput(step, pointer);
    }

    private static Producer producer(StepProducer stored, @Nullable ModelChoice model, boolean toldWhatHappened) {
        return switch (stored) {
            case MODEL -> {
                if (model == null) {
                    throw new IllegalArgumentException("A model produces its values, and none is named");
                }
                yield new Producer.Model(model, toldWhatHappened);
            }
            case PERSON -> new Producer.Person();
            case CODE -> new Producer.Code();
        };
    }

    /** The mode the store writes for running a model as it is is read as none. */
    static @Nullable ModelChoice choice(@Nullable String model, @Nullable String mode) {
        if (model == null) {
            return null;
        }
        return new ModelChoice(
                new ModelName(model),
                mode == null || mode.equals(ModelMode.RESERVED_FOR_AS_IT_IS) ? null : new ModelMode(mode));
    }

    sealed interface Runs permits Runs.Unchosen, Runs.Pinned, Runs.Code, Runs.Route, Runs.Search {

        record Unchosen() implements Runs {}

        /** A question or a workflow, at the version pinned. */
        record Pinned(EntryKind kind, EntryVersionId version) implements Runs {

            public Pinned {
                requireNonNull(kind, "StoredWorkflow.Runs.Pinned kind must not be null");
                requireNonNull(version, "StoredWorkflow.Runs.Pinned version must not be null");
            }
        }

        /** @param codeStep none where none is chosen yet */
        record Code(@Nullable String codeStep) implements Runs {}

        /**
         * @param discriminator none where nothing is bound to choose by yet
         * @param gives what every case gives back
         * @param cases in the order written, the fallback among them
         */
        record Route(@Nullable Binding discriminator, StoredDeclarations.Half gives, List<RouteCase> cases)
                implements Runs {

            public Route {
                requireNonNull(gives, "StoredWorkflow.Runs.Route gives must not be null");
                cases = List.copyOf(requireNonNull(cases, "StoredWorkflow.Runs.Route cases must not be null"));
            }
        }

        /**
         * @param way the name of the way to search, which the release declares; none where none is chosen yet
         * @param question the question version pinned, none until one is chosen
         * @param standings how each of the way's fields stands, by its name; one none is chosen for yet is left out
         */
        record Search(@Nullable String way, @Nullable EntryVersionId question, Map<FieldName, FieldStanding> standings)
                implements Runs {

            public Search {
                standings =
                        Map.copyOf(requireNonNull(standings, "StoredWorkflow.Runs.Search standings must not be null"));
                if (standings.containsValue(FieldStanding.ABOVE_CONFIDENCE)) {
                    throw new IllegalArgumentException(
                            "StoredWorkflow.Runs.Search must not stand a field above a confidence, as a search gives"
                                    + " none");
                }
            }
        }
    }

    /**
     * @param producer none where nothing is chosen yet, and where what it runs has none
     * @param tries none where nothing is chosen yet, and where what it runs makes none
     * @param reviewer the model reviewing its productions; none where a person does, or where nobody does
     * @param bindings what fills each input it takes, in the order written
     */
    record Step(
            UUID id,
            StepId name,
            @Nullable StepLabel label,
            Runs runs,
            @Nullable Producer producer,
            @Nullable Integer tries,
            @Nullable ModelChoice reviewer,
            List<Binding> bindings) {

        Step {
            requireNonNull(id, "StoredWorkflow.Step id must not be null");
            requireNonNull(name, "StoredWorkflow.Step name must not be null");
            requireNonNull(runs, "StoredWorkflow.Step runs must not be null");
            bindings = List.copyOf(requireNonNull(bindings, "StoredWorkflow.Step bindings must not be null"));
        }
    }

    private record Settings(
            int revision,
            @Nullable Ceiling ceiling,
            boolean keepsOwnCeiling,
            boolean raiseNeedsApproval,
            boolean mayBeHelped,
            @Nullable ModelChoice helper) {}

    private record CaseRow(
            UUID id, @Nullable String term, @Nullable EntryVersionId target) {}

    private record StepRow(
            UUID id,
            StepId name,
            @Nullable StepLabel label,
            @Nullable StepKind kind,
            Runs.@Nullable Pinned pinned,
            @Nullable String codeStep,
            @Nullable Producer producer,
            @Nullable Integer tries,
            @Nullable ModelChoice reviewer,
            @Nullable String searchWay,
            Map<FieldName, FieldStanding> standings) {

        Step step(Consumers consumers, Map<UUID, List<CaseRow>> casesByStep, Map<UUID, StoredDeclarations.Half> gives) {
            Runs runs =
                    switch (kind) {
                        case null -> new Runs.Unchosen();
                        case ENTRY -> pinned == null ? new Runs.Unchosen() : pinned;
                        case CODE_STEP -> new Runs.Code(codeStep);
                        case SEARCH -> new Runs.Search(searchWay, pinned == null ? null : pinned.version(), standings);
                        case ROUTE ->
                            new Runs.Route(
                                    consumers.discriminators.get(id),
                                    requireNonNull(gives.get(id)),
                                    casesByStep.getOrDefault(id, List.of()).stream()
                                            .map(row -> new RouteCase(
                                                    row.id(),
                                                    row.term(),
                                                    row.target(),
                                                    consumers.byCase.getOrDefault(row.id(), List.of())))
                                            .toList());
                    };
            return new Step(
                    id, name, label, runs, producer, tries, reviewer, consumers.byStep.getOrDefault(id, List.of()));
        }
    }

    private static final class Consumers {

        private final Map<UUID, List<Binding>> byStep = new HashMap<>();

        private final Map<UUID, List<Binding>> byCase = new HashMap<>();

        private final Map<UUID, Binding> discriminators = new HashMap<>();

        private final List<Binding> outputs = new ArrayList<>();

        void add(ResultSet result, EntryVersionId version) throws SQLException {
            UUID id = result.getObject("binding_id", UUID.class);
            UUID step = result.getObject("workflow_step_id", UUID.class);
            UUID routeCase = result.getObject("route_case_id", UUID.class);
            String target = result.getString("target_path");
            Binding binding;
            try {
                binding = new Binding(id, target == null ? null : Pointer.parse(target), source(result));
            } catch (IllegalArgumentException refused) {
                throw new IllegalStateException(
                        "Version " + version.value() + " holds a binding " + id + " this system will not read",
                        refused);
            }
            if (step != null && target == null) {
                discriminators.put(step, binding);
            } else if (step != null) {
                byStep.computeIfAbsent(step, ignored -> new ArrayList<>()).add(binding);
            } else if (routeCase != null) {
                byCase.computeIfAbsent(routeCase, ignored -> new ArrayList<>()).add(binding);
            } else {
                outputs.add(binding);
            }
        }

        private static BindingSource source(ResultSet result) throws SQLException {
            String constant = result.getString("constant");
            if (constant != null) {
                return new BindingSource.Written(ConstantJson.stored(constant));
            }
            return readFrom(result);
        }
    }
}
