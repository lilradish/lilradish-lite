package org.lilradish.lite.app.library;

import static java.util.Objects.requireNonNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.app.change.Author;
import org.lilradish.lite.domain.declaration.Declaration;
import org.lilradish.lite.domain.declaration.DeclarationSide;
import org.lilradish.lite.domain.declaration.Field;
import org.lilradish.lite.domain.declaration.FieldShape;
import org.lilradish.lite.domain.declaration.OfferedTerms;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.identity.UserId;
import org.lilradish.lite.domain.model.ModelMode;
import org.lilradish.lite.domain.referencelist.Term;
import org.lilradish.lite.domain.registry.EntryId;
import org.lilradish.lite.domain.registry.EntryKind;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.lilradish.lite.domain.run.Ceiling;
import org.lilradish.lite.domain.wire.StoreLabels;
import org.lilradish.lite.domain.workflow.ModelChoice;
import org.lilradish.lite.domain.workflow.Pointer;
import org.lilradish.lite.domain.workflow.Producer;
import org.lilradish.lite.domain.workflow.StepKind;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * A workflow draft's halves, its steps with what fills them, and how its runs may spend and be helped, each
 * written through {@link Drafts} and answered with the version as the same change then reads it. A step, a case
 * or a field keeps the version it pins already, whatever that now stands at; every other pin must be in service
 * in this group.
 */
@Component
final class WorkflowDrafts {

    // DB-SPECIFIC: now(), greatest, uuidv7(), generate_series, unnest, using and enum, jsonb and array casts are
    // PostgreSQL's.
    private static final String AUTHOR = Author.OF_CALLER;

    /* Every row naming a step goes before the steps; a route's fields go in one statement, parents with children. */
    private static final String BINDINGS_CLEARED = "delete from bindings where entry_version_id = :version";

    private static final String ROUTE_FIELDS_CLEARED = """
            delete from declaration_fields field
             using workflow_steps step
             where field.workflow_step_id = step.workflow_step_id and step.entry_version_id = :version
            """;

    private static final String CASES_CLEARED = "delete from route_cases where entry_version_id = :version";

    private static final String STEPS_CLEARED = "delete from workflow_steps where entry_version_id = :version";

    /* Drawn before the rows are written, so what names a step or a case is written knowing its key. */
    private static final String KEYS = "select uuidv7() from generate_series(1, :count)";

    private static final String STEPS = """
            insert into workflow_steps (workflow_step_id, entry_version_id, position, name, kind, pinned_version_id,
                                        pinned_kind, code_step, producer, producer_model, producer_mode, tries,
                                        reviewer_model, reviewer_mode, tells_what_happened, created_by)
            select step.id, :version, step.position, step.name, cast(step.kind as step_kind), step.pinned,
                   cast(step.pinned_kind as entry_kind), cast(step.code_step as code_step),
                   cast(step.producer as step_producer), step.producer_model, step.producer_mode, step.tries,
                   step.reviewer_model, step.reviewer_mode, step.told, %s
              from unnest(cast(:ids as uuid[]), cast(:positions as integer[]), cast(:names as text[]),
                          cast(:kinds as text[]), cast(:pinned as uuid[]), cast(:pinnedKinds as text[]),
                          cast(:codeSteps as text[]), cast(:producers as text[]), cast(:producerModels as text[]),
                          cast(:producerModes as text[]), cast(:tries as integer[]), cast(:reviewerModels as text[]),
                          cast(:reviewerModes as text[]), cast(:told as boolean[]))
                   as step(id, position, name, kind, pinned, pinned_kind, code_step, producer, producer_model,
                           producer_mode, tries, reviewer_model, reviewer_mode, told)
            """.formatted(AUTHOR);

    private static final String CASES = """
            insert into route_cases (route_case_id, entry_version_id, workflow_step_id, term, target_version_id,
                                     created_by)
            select routed.id, :version, routed.step, routed.term, routed.target, %s
              from unnest(cast(:ids as uuid[]), cast(:steps as uuid[]), cast(:terms as text[]),
                          cast(:targets as uuid[])) as routed(id, step, term, target)
            """.formatted(AUTHOR);

    /* Each key is drawn as its row is written, so the order written is the order the keys give back. */
    private static final String BINDINGS = """
            insert into bindings (entry_version_id, workflow_step_id, step_kind, route_case_id, target_path,
                                  source_step_id, source_path, constant, created_by)
            select :version, bound.step, cast(bound.step_kind as step_kind), bound.route_case, bound.target,
                   bound.source_step, bound.source_path, cast(bound.constant as jsonb), %s
              from unnest(cast(:steps as uuid[]), cast(:stepKinds as text[]), cast(:cases as uuid[]),
                          cast(:targets as text[]), cast(:sourceSteps as uuid[]), cast(:sourcePaths as text[]),
                          cast(:constants as text[]))
                   as bound(step, step_kind, route_case, target, source_step, source_path, constant)
            """.formatted(AUTHOR);

    /* now() is when this transaction began, which can be before the row it waited on the lock for was made,
     * or last changed; greatest passes over an updated_at still null. */
    private static final String CEILING = """
            update workflow_versions
               set ceiling = :ceiling, keeps_own_ceiling = :keepsOwnCeiling, raise_needs_approval = :raiseNeedsApproval,
                   updated_at = greatest(now(), created_at, updated_at), updated_by = %s
             where entry_version_id = :version
            """.formatted(AUTHOR);

    private static final String HELP = """
            update workflow_versions
               set may_be_helped = :mayBeHelped, helper_model = :model, helper_mode = :mode,
                   updated_at = greatest(now(), created_at, updated_at), updated_by = %s
             where entry_version_id = :version
            """.formatted(AUTHOR);

    private final JdbcClient database;

    private final Drafts drafts;

    private final Workflows workflows;

    private final ReleasedCodeSteps released;

    WorkflowDrafts(JdbcClient database, Drafts drafts, Workflows workflows, ReleasedCodeSteps released) {
        this.database = database;
        this.drafts = drafts;
        this.workflows = workflows;
        this.released = released;
    }

    /**
     * The half written whole in its declared order in place of what it held; {@code readAs} is the key each field
     * was read under, in {@link DeclarationBody.Sent}'s order, none for one added.
     */
    Workflows.WorkflowView declare(
            GroupId group,
            EntryId entry,
            EntryVersionId version,
            UserId caller,
            int seenRevision,
            Declaration half,
            List<@Nullable UUID> readAs) {
        requireNonNull(half, "WorkflowDrafts half must not be null");
        requireNonNull(readAs, "WorkflowDrafts keys read must not be null");
        return written(
                group,
                entry,
                version,
                caller,
                seenRevision,
                draft -> FieldWriting.halfRewritten(database, draft, half, readAs, caller));
    }

    /** Every step, and what fills each value the workflow gives back, written in place of what the draft held. */
    Workflows.WorkflowView flow(
            GroupId group, EntryId entry, EntryVersionId version, UserId caller, FlowBody.Sent sent) {
        requireNonNull(sent, "WorkflowDrafts sent must not be null");
        return written(group, entry, version, caller, sent.revision(), draft -> {
            Pins pins = pinsOf(group, draft, sent.steps());
            requireBounded(group, draft, sent);
            for (String cleared : List.of(BINDINGS_CLEARED, ROUTE_FIELDS_CLEARED, CASES_CLEARED, STEPS_CLEARED)) {
                database.sql(cleared).param("version", draft.version().value()).update();
            }
            List<UUID> steps = keys(sent.steps().size());
            writeSteps(draft, sent.steps(), steps, pins, caller);
            List<List<UUID>> cases = writeRoutes(draft, sent.steps(), steps, pins, caller);
            Bindings bindings = new Bindings(steps);
            for (int index = 0; index < sent.steps().size(); index++) {
                FlowBody.SentStep step = sent.steps().get(index);
                UUID key = steps.get(index);
                bindings.addAll(step.bindings(), key, null);
                if (step.runs() instanceof FlowBody.SentRuns.Route route) {
                    if (route.discriminator() != null) {
                        bindings.add(null, route.discriminator(), key, null);
                    }
                    for (int caseAt = 0; caseAt < route.cases().size(); caseAt++) {
                        bindings.addAll(
                                route.cases().get(caseAt).bindings(),
                                null,
                                cases.get(index).get(caseAt));
                    }
                }
            }
            bindings.addAll(sent.outputs(), null, null);
            bindings.write(draft, caller);
        });
    }

    /** How much a run of it may spend, none being no ceiling, and the two declarations beside it. */
    Workflows.WorkflowView ceiling(
            GroupId group,
            EntryId entry,
            EntryVersionId version,
            UserId caller,
            int seenRevision,
            @Nullable Ceiling ceiling,
            boolean keepsOwnCeiling,
            boolean raiseNeedsApproval) {
        return written(
                group,
                entry,
                version,
                caller,
                seenRevision,
                draft -> settled(
                        database.sql(CEILING)
                                .param("ceiling", ceiling == null ? null : ceiling.value())
                                .param("keepsOwnCeiling", keepsOwnCeiling)
                                .param("raiseNeedsApproval", raiseNeedsApproval)
                                .param("version", draft.version().value())
                                .param("caller", caller.value())
                                .update(),
                        draft));
    }

    /** Whether its runs may be helped, and the model naming none where they may not be. */
    Workflows.WorkflowView help(
            GroupId group,
            EntryId entry,
            EntryVersionId version,
            UserId caller,
            int seenRevision,
            boolean mayBeHelped,
            @Nullable ModelChoice helper) {
        if (helper != null && !mayBeHelped) {
            throw new IllegalArgumentException("WorkflowDrafts names a helper for runs that may not be helped");
        }
        return written(
                group,
                entry,
                version,
                caller,
                seenRevision,
                draft -> settled(
                        database.sql(HELP)
                                .param("mayBeHelped", mayBeHelped)
                                .param(
                                        "model",
                                        helper == null ? null : helper.model().value())
                                .param("mode", helper == null ? null : modeStored(helper))
                                .param("version", draft.version().value())
                                .param("caller", caller.value())
                                .update(),
                        draft));
    }

    private Workflows.WorkflowView written(
            GroupId group, EntryId entry, EntryVersionId version, UserId caller, int seenRevision, Writing writing) {
        return drafts.write(group, EntryKind.WORKFLOW, entry, version, caller, seenRevision, draft -> {
            writing.write(draft);
            return workflows.viewIn(group, draft.version(), caller);
        });
    }

    private static void settled(int written, OpenDraft draft) {
        if (written != 1) {
            throw new IllegalStateException(
                    "Workflow version " + draft.version().value() + " holds no workflow content");
        }
    }

    /** Found before anything is cleared, so a pin refused leaves the draft as it was. */
    private Pins pinsOf(GroupId group, OpenDraft draft, List<FlowBody.SentStep> steps) {
        Pins pins = new Pins();
        CodeStepsHere here = null;
        for (FlowBody.SentStep step : steps) {
            switch (step.runs()) {
                case FlowBody.SentRuns.Pinned held ->
                    pins.steps.add(pinned(
                            step.readAs() == null
                                    ? Optional.empty()
                                    : draft.stepPinnedAlready(step.readAs(), held.version()),
                            draft,
                            held.version(),
                            held.kind()));
                case FlowBody.SentRuns.Code code -> {
                    if (code.codeStep() != null) {
                        here = here == null ? CodeStepsHere.read(database, group, released) : here;
                        if (!here.nameable().contains(code.codeStep())) {
                            throw LibraryRefusal.CODE_STEP_NOT_PUBLISHED.raised();
                        }
                    }
                }
                case FlowBody.SentRuns.Route route -> {
                    pins.fields.add(FieldWriting.pinnable(
                            draft,
                            DeclarationSide.GIVES,
                            route.gives().half().fields(),
                            route.gives().readAs()));
                    for (FlowBody.SentCase routeCase : route.cases()) {
                        EntryVersionId target = routeCase.workflow();
                        if (target != null) {
                            pins.cases.add(pinned(
                                    routeCase.readAs() == null
                                            ? Optional.empty()
                                            : draft.casePinnedAlready(routeCase.readAs(), target),
                                    draft,
                                    target,
                                    EntryKind.WORKFLOW));
                        }
                    }
                }
                case FlowBody.SentRuns.Unchosen ignored -> {}
            }
        }
        return pins;
    }

    /**
     * Refused before anything is cleared: no more bindings than what they fill declares fields, and no more cases
     * than the list a route chooses by offers terms and a fallback; any declaration's or list's most where unknown.
     */
    private void requireBounded(GroupId group, OpenDraft draft, FlowBody.Sent sent) {
        Set<EntryVersionId> targets = new HashSet<>();
        for (FlowBody.SentStep step : sent.steps()) {
            switch (step.runs()) {
                case FlowBody.SentRuns.Pinned held -> targets.add(held.version());
                case FlowBody.SentRuns.Route route ->
                    route.cases().stream()
                            .map(FlowBody.SentCase::workflow)
                            .filter(target -> target != null)
                            .forEach(targets::add);
                case FlowBody.SentRuns.Code ignored -> {}
                case FlowBody.SentRuns.Unchosen ignored -> {}
            }
        }
        Map<EntryVersionId, StoredDeclarations.Halves> declared =
                StoredDeclarations.ofGroupVersions(database, group, targets);
        StoredDeclarations.Halves own =
                requireNonNull(StoredDeclarations.ofVersions(database, Map.of(draft.version(), EntryKind.WORKFLOW))
                        .get(draft.version()));
        for (FlowBody.SentStep step : sent.steps()) {
            int taken =
                    switch (step.runs()) {
                        case FlowBody.SentRuns.Pinned held -> fieldsTaken(declared.get(held.version()));
                        case FlowBody.SentRuns.Route ignored -> 0;
                        case FlowBody.SentRuns.Code ignored -> Declaration.MOST_FIELDS;
                        case FlowBody.SentRuns.Unchosen ignored -> Declaration.MOST_FIELDS;
                    };
            requireWithin(step.bindings(), taken);
            if (step.runs() instanceof FlowBody.SentRuns.Route route) {
                if (route.cases().size() > termsOffered(group, route, sent.steps(), own, declared) + 1) {
                    throw LibraryRefusal.CASES_PAST_TERMS.raised();
                }
                for (FlowBody.SentCase routeCase : route.cases()) {
                    EntryVersionId target = routeCase.workflow();
                    requireWithin(
                            routeCase.bindings(),
                            target == null ? Declaration.MOST_FIELDS : fieldsTaken(declared.get(target)));
                }
            }
        }
        requireWithin(sent.outputs(), own.gives().declaration().fieldsHeld());
    }

    private static int fieldsTaken(StoredDeclarations.@Nullable Halves declared) {
        return declared == null
                ? Declaration.MOST_FIELDS
                : declared.takes().declaration().fieldsHeld();
    }

    private static void requireWithin(List<FlowBody.SentBinding> bindings, int fields) {
        if (bindings.size() > fields) {
            throw LibraryRefusal.BINDINGS_PAST_INPUTS.raised();
        }
    }

    /** How many terms the list a route chooses by offers; any list's most where that list is not known. */
    private int termsOffered(
            GroupId group,
            FlowBody.SentRuns.Route route,
            List<FlowBody.SentStep> steps,
            StoredDeclarations.Halves own,
            Map<EntryVersionId, StoredDeclarations.Halves> declared) {
        FlowBody.SentSource chosenBy = route.discriminator();
        List<Field> level = null;
        Pointer pointer = null;
        if (chosenBy instanceof FlowBody.SentSource.Input input) {
            level = own.takes().declaration().fields();
            pointer = input.pointer();
        } else if (chosenBy instanceof FlowBody.SentSource.Step read) {
            pointer = read.pointer();
            level = switch (steps.get(read.index()).runs()) {
                case FlowBody.SentRuns.Pinned held -> {
                    StoredDeclarations.Halves gives = declared.get(held.version());
                    yield gives == null ? null : gives.gives().declaration().fields();
                }
                case FlowBody.SentRuns.Route earlier -> earlier.gives().half().fields();
                case FlowBody.SentRuns.Code ignored -> null;
                case FlowBody.SentRuns.Unchosen ignored -> null;
            };
        }
        if (level == null
                || pointer == null
                || !(pointer.reach(level) instanceof Pointer.Reach.At at)
                || !(at.field().shape() instanceof FieldShape.Term term)
                || term.list() == null) {
            return Term.MOST_IN_A_LIST;
        }
        EntryVersionId list = term.list();
        OfferedTerms offered =
                StoredQuestion.offered(database, group, List.of(list)).get(list);
        return offered == null ? 0 : offered.terms().size();
    }

    private static OpenDraft.PinnableVersion pinned(
            Optional<OpenDraft.PinnableVersion> kept, OpenDraft draft, EntryVersionId target, EntryKind kind) {
        OpenDraft.PinnableVersion found = kept.orElseGet(() -> draft.pinInService(target));
        if (found.kind() != kind) {
            throw LibraryRefusal.VERSION_NOT_PINNABLE.raised();
        }
        return found;
    }

    private void writeSteps(OpenDraft draft, List<FlowBody.SentStep> sent, List<UUID> keys, Pins pins, UserId caller) {
        if (sent.isEmpty()) {
            return;
        }
        StepRows rows = new StepRows();
        for (int index = 0; index < sent.size(); index++) {
            FlowBody.SentStep step = sent.get(index);
            OpenDraft.PinnableVersion pinned = null;
            if (step.runs() instanceof FlowBody.SentRuns.Pinned) {
                pinned = requireNonNull(pins.steps.poll());
                draft.requireIssued(pinned);
            }
            rows.add(keys.get(index), index + 1, step, pinned);
        }
        rows.bound(database.sql(STEPS))
                .param("version", draft.version().value())
                .param("caller", caller.value())
                .update();
    }

    /** Each route's fields and cases, and each step's cases' keys in the order sent, none for a step not a route. */
    private List<List<UUID>> writeRoutes(
            OpenDraft draft, List<FlowBody.SentStep> sent, List<UUID> steps, Pins pins, UserId caller) {
        List<List<UUID>> cases = new ArrayList<>(sent.size());
        List<String> ids = new ArrayList<>();
        List<String> owners = new ArrayList<>();
        List<@Nullable String> terms = new ArrayList<>();
        List<@Nullable String> targets = new ArrayList<>();
        int count = sent.stream()
                .mapToInt(step -> step.runs() instanceof FlowBody.SentRuns.Route route
                        ? route.cases().size()
                        : 0)
                .sum();
        Iterator<UUID> keys = keys(count).iterator();
        for (int index = 0; index < sent.size(); index++) {
            if (!(sent.get(index).runs() instanceof FlowBody.SentRuns.Route route)) {
                cases.add(List.of());
                continue;
            }
            FieldWriting.written(
                    database,
                    draft,
                    new FieldWriting.Owner.RouteStep(steps.get(index)),
                    DeclarationSide.GIVES,
                    route.gives().half().fields(),
                    requireNonNull(pins.fields.poll()),
                    caller);
            List<UUID> here = new ArrayList<>(route.cases().size());
            for (FlowBody.SentCase routeCase : route.cases()) {
                UUID key = keys.next();
                here.add(key);
                ids.add(key.toString());
                owners.add(steps.get(index).toString());
                terms.add(routeCase.term());
                OpenDraft.PinnableVersion target = null;
                if (routeCase.workflow() != null) {
                    target = requireNonNull(pins.cases.poll());
                    draft.requireIssued(target);
                }
                targets.add(target == null ? null : target.version().value().toString());
            }
            cases.add(here);
        }
        if (!ids.isEmpty()) {
            database.sql(CASES)
                    .param("ids", ids.toArray(String[]::new))
                    .param("steps", owners.toArray(String[]::new))
                    .param("terms", terms.toArray(String[]::new))
                    .param("targets", targets.toArray(String[]::new))
                    .param("version", draft.version().value())
                    .param("caller", caller.value())
                    .update();
        }
        return cases;
    }

    private List<UUID> keys(int count) {
        if (count == 0) {
            return List.of();
        }
        return database.sql(KEYS)
                .param("count", count)
                .query((result, number) -> result.getObject(1, UUID.class))
                .list();
    }

    private static @Nullable String modeStored(ModelChoice choice) {
        ModelMode mode = choice.mode();
        return mode == null ? ModelMode.RESERVED_FOR_AS_IT_IS : mode.value();
    }

    @FunctionalInterface
    private interface Writing {

        void write(OpenDraft draft);
    }

    /** What each step, route field and case sent pins, in the order they were sent. */
    private static final class Pins {

        private final Deque<OpenDraft.PinnableVersion> steps = new ArrayDeque<>();

        private final Deque<Deque<OpenDraft.PinnableVersion>> fields = new ArrayDeque<>();

        private final Deque<OpenDraft.PinnableVersion> cases = new ArrayDeque<>();
    }

    /** Every step as one column per stored column, bound as arrays to a single statement. */
    private static final class StepRows {

        private final List<String> ids = new ArrayList<>();

        private final List<Integer> positions = new ArrayList<>();

        private final List<String> names = new ArrayList<>();

        private final List<@Nullable String> kinds = new ArrayList<>();

        private final List<@Nullable String> pinned = new ArrayList<>();

        private final List<@Nullable String> pinnedKinds = new ArrayList<>();

        private final List<@Nullable String> codeSteps = new ArrayList<>();

        private final List<@Nullable String> producers = new ArrayList<>();

        private final List<@Nullable String> producerModels = new ArrayList<>();

        private final List<@Nullable String> producerModes = new ArrayList<>();

        private final List<@Nullable Integer> tries = new ArrayList<>();

        private final List<@Nullable String> reviewerModels = new ArrayList<>();

        private final List<@Nullable String> reviewerModes = new ArrayList<>();

        private final List<Boolean> told = new ArrayList<>();

        void add(UUID key, int position, FlowBody.SentStep step, OpenDraft.@Nullable PinnableVersion pin) {
            ids.add(key.toString());
            positions.add(position);
            names.add(step.name().value());
            StepKind kind =
                    switch (step.runs()) {
                        case FlowBody.SentRuns.Unchosen ignored -> null;
                        case FlowBody.SentRuns.Pinned ignored -> StepKind.ENTRY;
                        case FlowBody.SentRuns.Code ignored -> StepKind.CODE_STEP;
                        case FlowBody.SentRuns.Route ignored -> StepKind.ROUTE;
                    };
            kinds.add(kind == null ? null : StoreLabels.label(kind));
            pinned.add(pin == null ? null : pin.version().value().toString());
            pinnedKinds.add(pin == null ? null : StoreLabels.label(pin.kind()));
            codeSteps.add(step.runs() instanceof FlowBody.SentRuns.Code code ? code.codeStep() : null);
            Producer producer = step.producer();
            producers.add(producer == null ? null : StoreLabels.label(producer.kind()));
            ModelChoice model = producer instanceof Producer.Model chosen ? chosen.choice() : null;
            producerModels.add(model == null ? null : model.model().value());
            producerModes.add(model == null ? null : modeStored(model));
            tries.add(step.tries());
            ModelChoice reviewer = step.reviewer();
            reviewerModels.add(reviewer == null ? null : reviewer.model().value());
            reviewerModes.add(reviewer == null ? null : modeStored(reviewer));
            told.add(producer instanceof Producer.Model chosen && chosen.toldWhatHappened());
        }

        JdbcClient.StatementSpec bound(JdbcClient.StatementSpec statement) {
            return statement
                    .param("ids", ids.toArray(String[]::new))
                    .param("positions", positions.toArray(Integer[]::new))
                    .param("names", names.toArray(String[]::new))
                    .param("kinds", kinds.toArray(String[]::new))
                    .param("pinned", pinned.toArray(String[]::new))
                    .param("pinnedKinds", pinnedKinds.toArray(String[]::new))
                    .param("codeSteps", codeSteps.toArray(String[]::new))
                    .param("producers", producers.toArray(String[]::new))
                    .param("producerModels", producerModels.toArray(String[]::new))
                    .param("producerModes", producerModes.toArray(String[]::new))
                    .param("tries", tries.toArray(Integer[]::new))
                    .param("reviewerModels", reviewerModels.toArray(String[]::new))
                    .param("reviewerModes", reviewerModes.toArray(String[]::new))
                    .param("told", told.toArray(Boolean[]::new));
        }
    }

    /** Every binding written, by what it fills, as one column per stored column. */
    private final class Bindings {

        private final List<UUID> steps;

        private final List<@Nullable String> consumers = new ArrayList<>();

        private final List<@Nullable String> stepKinds = new ArrayList<>();

        private final List<@Nullable String> cases = new ArrayList<>();

        private final List<@Nullable String> targets = new ArrayList<>();

        private final List<@Nullable String> sourceSteps = new ArrayList<>();

        private final List<@Nullable String> sourcePaths = new ArrayList<>();

        private final List<@Nullable String> constants = new ArrayList<>();

        Bindings(List<UUID> steps) {
            this.steps = steps;
        }

        void addAll(List<FlowBody.SentBinding> bindings, @Nullable UUID step, @Nullable UUID routeCase) {
            for (FlowBody.SentBinding binding : bindings) {
                add(binding.target().published(), binding.source(), step, routeCase);
            }
        }

        /** {@code target} none only for what a route chooses by, which {@code step} is then. */
        void add(@Nullable String target, FlowBody.SentSource source, @Nullable UUID step, @Nullable UUID routeCase) {
            consumers.add(step == null ? null : step.toString());
            stepKinds.add(target == null ? StoreLabels.label(StepKind.ROUTE) : null);
            cases.add(routeCase == null ? null : routeCase.toString());
            targets.add(target);
            switch (source) {
                case FlowBody.SentSource.Input input -> {
                    sourceSteps.add(null);
                    sourcePaths.add(input.pointer().published());
                    constants.add(null);
                }
                case FlowBody.SentSource.Step output -> {
                    sourceSteps.add(steps.get(output.index()).toString());
                    sourcePaths.add(output.pointer().published());
                    constants.add(null);
                }
                case FlowBody.SentSource.Written written -> {
                    sourceSteps.add(null);
                    sourcePaths.add(null);
                    constants.add(written.stored());
                }
            }
        }

        void write(OpenDraft draft, UserId caller) {
            if (targets.isEmpty()) {
                return;
            }
            database.sql(BINDINGS)
                    .param("steps", consumers.toArray(String[]::new))
                    .param("stepKinds", stepKinds.toArray(String[]::new))
                    .param("cases", cases.toArray(String[]::new))
                    .param("targets", targets.toArray(String[]::new))
                    .param("sourceSteps", sourceSteps.toArray(String[]::new))
                    .param("sourcePaths", sourcePaths.toArray(String[]::new))
                    .param("constants", constants.toArray(String[]::new))
                    .param("version", draft.version().value())
                    .param("caller", caller.value())
                    .update();
        }
    }
}
