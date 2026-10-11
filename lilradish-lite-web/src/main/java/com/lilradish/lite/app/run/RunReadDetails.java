package com.lilradish.lite.app.run;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.declaration.Declaration;
import com.lilradish.lite.domain.declaration.Field;
import com.lilradish.lite.domain.declaration.FieldShape;
import com.lilradish.lite.domain.inference.ModelCallOutcome;
import com.lilradish.lite.domain.registry.VersionName;
import com.lilradish.lite.domain.run.InputRecord;
import com.lilradish.lite.domain.run.PlannedStep;
import com.lilradish.lite.domain.run.ProductionValueId;
import com.lilradish.lite.domain.run.RunId;
import com.lilradish.lite.domain.run.RunInTree;
import com.lilradish.lite.domain.run.RunSnapshot;
import com.lilradish.lite.domain.run.StepInputs;
import com.lilradish.lite.domain.run.StepPosition;
import com.lilradish.lite.domain.run.StepPositions;
import com.lilradish.lite.domain.run.StepRuns;
import com.lilradish.lite.domain.run.StepSnapshot;
import com.lilradish.lite.domain.run.TryRecord;
import com.lilradish.lite.domain.run.ValueRecord;
import com.lilradish.lite.domain.wire.JsonValue;
import com.lilradish.lite.domain.wire.StoreLabels;
import com.lilradish.lite.domain.workflow.Binding;
import com.lilradish.lite.domain.workflow.BindingSource;
import com.lilradish.lite.domain.workflow.Pointer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.log.annotation.DoNotLog;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Details shared by one authorised, consistent read of a run and its tree. */
final class RunReadDetails {

    private static final String MEMBERS = """
            select run.run_id, run.number, run.created_at, run.entry_id, entry.name as workflow_name,
                   version.number as version_number, run.entry_version_id, version.version_name
              from runs run
              join entries entry on entry.entry_id = run.entry_id
              join entry_versions version on version.entry_version_id = run.entry_version_id
             where run.root_run_id = :root and run.group_id = :group
            """;

    // DB-SPECIFIC: PostgreSQL uuid[] binding and model_call_outcome casts.
    private static final String SPENT_BENEATH = """
            with recursive subtree (child_run_id, run_id) as (
                    select child.run_id, child.run_id
                      from runs child
                     where child.run_id = any(cast(:children as uuid[]))
                       and child.root_run_id = :root and child.group_id = :group
                union all
                    select subtree.child_run_id, beneath.run_id
                      from runs beneath
                      join subtree on beneath.parent_run_id = subtree.run_id
                     where beneath.root_run_id = :root
            )
            select subtree.child_run_id,
                   coalesce(sum(call.sent_count), 0) as sent,
                   coalesce(sum(call.came_back_count), 0) as came_back,
                   count(*) filter (where call.came_back_count is null) > 0 as came_back_unknown,
                   count(*) filter (where not call.counted_by_model) as measured_here_calls,
                   count(*) as total_calls
              from subtree
              join model_calls call on call.run_id = subtree.run_id
             where call.outcome is distinct from cast(:turnedAway as model_call_outcome)
             group by subtree.child_run_id
            """;

    private final JdbcClient database;

    private final RunInTree root;

    private final Map<RunId, Member> members;

    private final Map<ProductionValueId, MakerRecord> makers;

    private final Map<RunId, Map<UUID, PlannedStep>> plannedSteps;

    RunReadDetails(JdbcClient database, RunInTree context) {
        this.database = requireNonNull(database, "RunReadDetails database must not be null");
        requireNonNull(context, "RunReadDetails context must not be null");
        RunInTree top = context;
        while (top.above() != null) {
            top = top.above();
        }
        root = top;
        members = new HashMap<>();
        database.sql(MEMBERS)
                .param("root", root.run().run().value())
                .param("group", root.run().group().value())
                .query(result -> {
                    RunId run = new RunId(result.getObject("run_id", UUID.class));
                    if (members.putIfAbsent(run, member(result)) != null) {
                        throw new IllegalStateException("A run tree repeats a run");
                    }
                });
        makers = new HashMap<>();
        plannedSteps = new HashMap<>();
        index(root.run());
    }

    private static Member member(ResultSet result) throws SQLException {
        return new Member(
                result.getInt("number"),
                result.getObject("created_at", OffsetDateTime.class).toInstant(),
                new Workflow(
                        result.getObject("entry_id", UUID.class),
                        result.getString("workflow_name"),
                        result.getInt("version_number"),
                        result.getObject("entry_version_id", UUID.class),
                        result.getString("version_name") == null
                                ? null
                                : new VersionName(result.getString("version_name"))));
    }

    private void index(RunSnapshot run) {
        numbered(run.run());
        Map<UUID, PlannedStep> steps = new HashMap<>();
        if (plannedSteps.putIfAbsent(run.run(), steps) != null) {
            throw new IllegalStateException("A run tree repeats a run");
        }
        for (StepSnapshot step : run.steps()) {
            if (steps.putIfAbsent(step.planned().id().value(), step.planned()) != null) {
                throw new IllegalStateException("A run repeats a planned step");
            }
            for (TryRecord aTry : step.tries()) {
                for (ValueRecord value : aTry.values()) {
                    MakerRecord maker = new MakerRecord(
                            new NumberedRunRecord(run.run(), numbered(run.run()).number()),
                            step.planned().id().value(),
                            step.planned().name().value(),
                            value.field(),
                            aTry.number());
                    if (makers.putIfAbsent(value.id(), maker) != null) {
                        throw new IllegalStateException("A run tree repeats a production value");
                    }
                }
            }
            if (step.beneath() != null) {
                index(step.beneath().run());
            }
        }
    }

    NumberedRunRecord numbered(RunId run) {
        return new NumberedRunRecord(run, memberOf(run).number());
    }

    Instant createdAt(RunId run) {
        return memberOf(run).createdAt();
    }

    private Member memberOf(RunId run) {
        Member member = members.get(requireNonNull(run, "RunReadDetails run must not be null"));
        if (member == null) {
            throw new IllegalStateException("A snapshot names a run outside its group and tree");
        }
        return member;
    }

    MakerRecord maker(ProductionValueId value) {
        MakerRecord maker = makers.get(requireNonNull(value, "RunReadDetails value must not be null"));
        if (maker == null) {
            throw new IllegalStateException("A bound value names no maker in its run tree");
        }
        return maker;
    }

    List<UsedRecord> used(ProductionValueId value) {
        requireNonNull(value, "A used value has its production identity");
        return requireNonNull(used(List.of(value)).get(value), "The requested value has its usage result");
    }

    // DB-SPECIFIC: PostgreSQL uuid[] binding and enum casts.
    Map<ProductionValueId, List<UsedRecord>> used(List<ProductionValueId> values) {
        Map<ProductionValueId, List<UsedRecord>> used = new LinkedHashMap<>();
        for (ProductionValueId value : values) {
            used.put(requireNonNull(value, "A used value has its production identity"), new ArrayList<>());
        }
        if (used.isEmpty()) {
            return Map.of();
        }
        database.sql("""
                        with sources as (
                            select input.production_input_id, input.source_production_value_id as value_id
                              from production_inputs input
                             where input.root_run_id = :root
                               and input.source_production_value_id = any(cast(:values as uuid[]))
                            union
                            select input.production_input_id, part.source_production_value_id as value_id
                              from production_inputs input
                              join production_input_parts part on part.production_input_id = input.production_input_id
                             where input.root_run_id = :root
                               and part.source_production_value_id = any(cast(:values as uuid[]))
                        )
                        select distinct sources.value_id, run.run_id, run.number, step.workflow_step_id, planned.name, planned.label,
                               input.branch_run_id is not null as chose_branch, planned.position
                          from sources
                          join production_inputs input on input.production_input_id = sources.production_input_id
                          join runs run on run.run_id = input.run_id
                          join run_steps step on step.run_step_id = input.run_step_id
                          join workflow_steps planned on planned.workflow_step_id = step.workflow_step_id
                          left join productions tried on tried.production_id = input.production_id
                         where input.root_run_id = :root and run.group_id = :group
                           and (input.branch_run_id is not null or tried.yielded
                                or tried.producer = cast(:code as step_producer)
                                or exists (select 1 from model_calls call
                                            where call.production_id = tried.production_id
                                              and call.purpose = cast(:purpose as model_call_purpose)))
                         order by run.number, planned.position, chose_branch
                        """)
                .param(
                        "values",
                        values.stream().map(value -> value.value().toString()).toArray(String[]::new))
                .param("root", root.run().run().value())
                .param("group", root.run().group().value())
                .param("code", StoreLabels.label(com.lilradish.lite.domain.workflow.StepProducer.CODE))
                .param("purpose", StoreLabels.label(com.lilradish.lite.domain.inference.ModelCallPurpose.PRODUCE))
                .query(row -> {
                    requireNonNull(used.get(new ProductionValueId(row.getObject("value_id", UUID.class))))
                            .add(new UsedRecord(
                                    new NumberedRunRecord(
                                            new RunId(row.getObject("run_id", UUID.class)), row.getInt("number")),
                                    row.getObject("workflow_step_id", UUID.class),
                                    row.getString("name"),
                                    row.getString("label"),
                                    row.getBoolean("chose_branch")));
                });
        used.replaceAll((value, places) -> List.copyOf(places));
        return Map.copyOf(used);
    }

    // DB-SPECIFIC: PostgreSQL uuid[] binding.
    Map<ProductionValueId, RunActs.Held> heldForLength(List<ProductionValueId> values) {
        if (values.isEmpty()) {
            return Map.of();
        }
        Map<ProductionValueId, RunActs.Held> holds = new HashMap<>();
        database.sql("""
                        select decision.production_value_id, run.run_id, run.number, step.workflow_step_id, planned.name, planned.label
                          from review_decisions decision
                          join reviews review on review.review_id = decision.review_id
                          join run_step_holds hold on hold.run_step_hold_id = review.run_step_hold_id
                          join runs run on run.run_id = hold.run_id
                          join run_steps step on step.run_step_id = hold.run_step_id
                          join workflow_steps planned on planned.workflow_step_id = step.workflow_step_id
                         where decision.production_value_id = any(cast(:values as uuid[])) and decision.for_length
                           and run.root_run_id = :root and run.group_id = :group
                        """)
                .param(
                        "values",
                        values.stream().map(value -> value.value().toString()).toArray(String[]::new))
                .param("root", root.run().run().value())
                .param("group", root.run().group().value())
                .query(row -> {
                    ProductionValueId value = new ProductionValueId(row.getObject("production_value_id", UUID.class));
                    RunActs.Held hold = new RunActs.Held(
                            new RunActs.NumberedRun(row.getObject("run_id", UUID.class), row.getInt("number")),
                            row.getObject("workflow_step_id", UUID.class),
                            row.getString("name"),
                            row.getString("label"));
                    if (holds.putIfAbsent(value, hold) != null) {
                        throw new IllegalStateException("A length refusal names exactly one held step");
                    }
                });
        if (values.stream().anyMatch(value -> !holds.containsKey(value))) {
            throw new IllegalStateException("A length refusal names the step that was held");
        }
        return Map.copyOf(holds);
    }

    // DB-SPECIFIC: PostgreSQL uuid[] binding and DISTINCT ON.
    Map<ProductionValueId, Long> previousLengthUnits(List<ProductionValueId> values, StepSnapshot held) {
        if (values.isEmpty()) {
            return Map.of();
        }
        Map<ProductionValueId, Long> previous = new HashMap<>();
        database.sql("""
                        select distinct on (current_value.production_value_id)
                               current_value.production_value_id, decision.length_units
                          from production_values current_value
                          join productions current_try on current_try.production_id = current_value.production_id
                          left join declaration_fields current_field on current_field.declaration_field_id = current_value.declaration_field_id
                          join productions tried on tried.run_id = current_try.run_id and tried.run_step_id = current_try.run_step_id
                          join production_values value on value.production_id = tried.production_id
                          join review_decisions decision on decision.production_value_id = value.production_value_id
                          join reviews review on review.review_id = decision.review_id
                          join run_step_holds hold on hold.run_step_hold_id = review.run_step_hold_id
                          join runs run on run.run_id = tried.run_id
                          left join declaration_fields field on field.declaration_field_id = value.declaration_field_id
                         where decision.for_length and hold.run_step_id = :held
                           and run.root_run_id = :root and run.group_id = :group
                           and current_value.production_value_id = any(cast(:values as uuid[]))
                           and coalesce(field.name, value.field_name) = coalesce(current_field.name, current_value.field_name)
                           and tried.try_number < current_try.try_number
                         order by current_value.production_value_id, tried.try_number desc
                        """)
                .param("held", requireNonNull(held.runStep()).value())
                .param("root", root.run().run().value())
                .param("group", root.run().group().value())
                .param(
                        "values",
                        values.stream().map(value -> value.value().toString()).toArray(String[]::new))
                .query(row -> {
                    previous.put(
                            new ProductionValueId(row.getObject("production_value_id", UUID.class)),
                            row.getLong("length_units"));
                });
        return Map.copyOf(previous);
    }

    PlannedStep planned(MakerRecord maker) {
        Map<UUID, PlannedStep> steps = plannedSteps.get(maker.run().run());
        PlannedStep planned = steps == null ? null : steps.get(maker.stepId());
        if (planned == null) {
            throw new IllegalStateException("A maker names no step in its read tree");
        }
        return planned;
    }

    StepSnapshot madeBy(MakerRecord maker) {
        return root.find(maker.run().run())
                .orElseThrow(() -> new IllegalStateException("A maker belongs to its read tree"))
                .run()
                .step(new com.lilradish.lite.domain.run.WorkflowStepId(maker.stepId()))
                .orElseThrow(() -> new IllegalStateException("A maker names its producing step"));
    }

    // DB-SPECIFIC: PostgreSQL run_step_hold_reason enum cast.
    UUID lengthHold(StepSnapshot step) {
        return database.sql("""
                        select hold.run_step_hold_id from run_step_holds hold
                          join runs run on run.run_id = hold.run_id
                         where hold.run_step_id = :step and run.root_run_id = :root and run.group_id = :group
                           and hold.released_at is null and hold.reason = cast(:reason as run_step_hold_reason)
                        """)
                .param("step", requireNonNull(step.runStep()).value())
                .param("root", root.run().run().value())
                .param("group", root.run().group().value())
                .param("reason", StoreLabels.label(com.lilradish.lite.domain.run.RunStepHoldReason.TOO_LONG))
                .query(UUID.class)
                .single();
    }

    @Nullable
    Beneath beneath(StepSnapshot step) {
        requireNonNull(step, "RunReadDetails step must not be null");
        StepSnapshot.Beneath child = step.beneath();
        if (child == null) {
            return null;
        }
        RunSnapshot run = child.run();
        RunSnapshot current = run;
        At at = null;
        while (true) {
            List<StepPosition> positions = StepPositions.of(current);
            int index = 0;
            while (index < positions.size() && positions.get(index) instanceof StepPosition.Done) {
                index++;
            }
            if (index == positions.size()) {
                break;
            }
            StepSnapshot pending = current.steps().get(index);
            at = new At(
                    numbered(current.run()),
                    pending.planned().id().value(),
                    pending.planned().name().value(),
                    pending.planned().label() == null
                            ? null
                            : pending.planned().label().value());
            if (pending.beneath() == null) {
                break;
            }
            current = pending.beneath().run();
        }
        return new Beneath(numbered(run.run()), memberOf(run.run()).workflow(), at);
    }

    Map<RunId, RunBudget.Spend> costBeneath(List<StepSnapshot> steps) {
        requireNonNull(steps, "RunReadDetails steps must not be null");
        Map<RunId, RunBudget.Spend> costs = new HashMap<>();
        for (StepSnapshot step : steps) {
            StepSnapshot.Beneath child =
                    requireNonNull(step, "RunReadDetails step must not be null").beneath();
            if (child != null) {
                RunId run = child.run().run();
                memberOf(run);
                costs.put(run, RunBudget.Spend.NOTHING);
            }
        }
        if (costs.isEmpty()) {
            return Map.of();
        }
        String[] children =
                costs.keySet().stream().map(run -> run.value().toString()).toArray(String[]::new);
        database.sql(SPENT_BENEATH)
                .param("turnedAway", StoreLabels.label(ModelCallOutcome.TURNED_AWAY))
                .param("children", children)
                .param("root", root.run().run().value())
                .param("group", root.run().group().value())
                .query(result -> {
                    RunId child = new RunId(result.getObject("child_run_id", UUID.class));
                    RunBudget.Spend spent = new RunBudget.Spend(
                            result.getLong("sent"),
                            result.getLong("came_back"),
                            result.getBoolean("came_back_unknown"),
                            result.getLong("measured_here_calls"),
                            result.getLong("total_calls"));
                    if (costs.replace(child, spent) == null) {
                        throw new IllegalStateException("A cost names an unrequested run");
                    }
                });
        return Map.copyOf(costs);
    }

    List<StartedInputRecord> startedWith(RunInTree child) {
        requireNonNull(child, "RunReadDetails child must not be null");
        RunInTree parent = requireNonNull(child.above(), "A child run has a parent");
        StepSnapshot from = requireNonNull(child.from(), "A child run has a starting step");
        List<Binding> bindings = bindingsOf(from);
        List<StartedInputRecord> inputs = new ArrayList<>();
        for (Binding binding : bindings) {
            Pointer target = requireNonNull(binding.target(), "A child input fills a field");
            StepInputs.BindingRecord bound = StepInputs.bound(parent, binding).orElse(null);
            inputs.add(new StartedInputRecord(
                    target.published(),
                    bound == null ? null : bound.value(),
                    bound == null || !bound.parts().isEmpty()
                            ? null
                            : origin(parent, binding.source(), List.of(), bound.source()),
                    bound == null
                            ? List.of()
                            : parts(
                                    parent,
                                    child.run().workflow().takes(),
                                    requireNonNull(binding.target()),
                                    binding.source(),
                                    bound.parts(),
                                    bound.value())));
        }
        return List.copyOf(inputs);
    }

    List<PartSourceRecord> parts(
            RunInTree run, @Nullable Declaration declaration, Binding binding, List<InputRecord.Part> recorded) {
        return parts(
                run,
                declaration,
                requireNonNull(binding.target(), "A composite input fills a field"),
                binding.source(),
                recorded,
                StepInputs.bound(run, binding)
                        .map(StepInputs.BindingRecord::value)
                        .orElse(null));
    }

    private List<PartSourceRecord> parts(
            RunInTree run,
            @Nullable Declaration declaration,
            Pointer input,
            BindingSource source,
            List<InputRecord.Part> recorded,
            @Nullable JsonValue value) {
        requireNonNull(run, "RunReadDetails run must not be null");
        requireNonNull(input, "A composite input fills a field");
        requireNonNull(recorded, "RunReadDetails recorded parts must not be null");
        List<PartSourceRecord> parts = new ArrayList<>(recorded.size());
        for (InputRecord.Part part : recorded) {
            String path = new Pointer(part.targetPath()).published();
            parts.add(new PartSourceRecord(path, origin(run, source, part.targetPath(), part.source())));
        }
        if (declaration != null) {
            Map<String, Integer> current = new HashMap<>();
            enumerate(declaration.fields(), "", current);
            boolean sourceMayHaveChanged = source instanceof BindingSource.StepOutput output
                    && run.run()
                                    .workflow()
                                    .step(new com.lilradish.lite.domain.run.WorkflowStepId(output.step()))
                                    .orElseThrow()
                                    .runs()
                            instanceof StepRuns.Code;
            for (InputRecord.Part part : recorded) {
                String path = new Pointer(part.targetPath()).published();
                if (!current.containsKey(input.published() + "." + path)
                        && (!sourceMayHaveChanged || !hasPart(value, part.targetPath()))) {
                    throw new IllegalStateException("A composite value names a part absent from its value");
                }
            }
        }
        return orderedParts(declaration, input.published(), parts);
    }

    private static boolean hasPart(
            @Nullable JsonValue value, List<com.lilradish.lite.domain.declaration.FieldName> path) {
        JsonValue here = value;
        for (com.lilradish.lite.domain.declaration.FieldName name : path) {
            if (!(here instanceof JsonValue.JsonObject object)) {
                return false;
            }
            here = object.members().stream()
                    .filter(member -> member.name().equals(name.value()))
                    .map(JsonValue.JsonMember::value)
                    .findFirst()
                    .orElse(null);
            if (here == null) {
                return false;
            }
        }
        return true;
    }

    static List<PartSourceRecord> orderedParts(
            @Nullable Declaration declaration, String target, List<PartSourceRecord> parts) {
        requireNonNull(target, "RunReadDetails target must not be null");
        requireNonNull(parts, "RunReadDetails parts must not be null");
        Set<String> seen = new HashSet<>();
        for (PartSourceRecord part : parts) {
            String path = part.targetPath();
            if (path.isEmpty() || !seen.add(path)) {
                throw new IllegalStateException("A composite value names an empty or repeated part");
            }
        }
        if (declaration == null) {
            return List.copyOf(parts);
        }
        Map<String, Integer> order = new HashMap<>();
        enumerate(declaration.fields(), "", order);
        if (parts.stream().anyMatch(part -> !order.containsKey(target + "." + part.targetPath()))) {
            return List.copyOf(parts);
        }
        List<PartSourceRecord> ordered = new ArrayList<>(parts);
        ordered.sort(Comparator.comparingInt(part -> order.get(target + "." + part.targetPath())));
        return List.copyOf(ordered);
    }

    OriginRecord origin(RunInTree run, StepInputs.BindingRecord bound) {
        requireNonNull(bound, "RunReadDetails bound input must not be null");
        if (!bound.parts().isEmpty()) {
            throw new IllegalArgumentException("A composite input has individual origins");
        }
        return origin(run, bound.binding().source(), List.of(), bound.source());
    }

    OriginRecord origin(RunInTree run, StepInputs.BindingRecord bound, InputRecord.Part part) {
        return origin(run, bound.binding().source(), part.targetPath(), part.source());
    }

    private OriginRecord origin(
            RunInTree run,
            BindingSource source,
            List<com.lilradish.lite.domain.declaration.FieldName> suffix,
            @Nullable ProductionValueId value) {
        if (value != null) {
            MakerRecord maker = maker(value);
            return new OriginRecord("step", null, maker.run(), maker);
        }
        return switch (source) {
            case BindingSource.Written ignored ->
                new OriginRecord("constant", null, numbered(run.run().run()), null);
            case BindingSource.WorkflowInput input -> {
                List<com.lilradish.lite.domain.declaration.FieldName> path =
                        new ArrayList<>(input.pointer().names());
                path.addAll(suffix);
                if (run.above() == null) {
                    yield new OriginRecord(
                            "run_input",
                            new Pointer(path).published(),
                            numbered(run.run().run()),
                            null);
                }
                StepSnapshot from = requireNonNull(run.from(), "A child run names its parent step");
                yield through(run.above(), bindingsOf(from), path);
            }
            case BindingSource.StepOutput output -> {
                StepSnapshot step = run.run()
                        .step(new com.lilradish.lite.domain.run.WorkflowStepId(output.step()))
                        .orElseThrow(() -> new IllegalStateException("A binding names no step in its run"));
                if (step.beneath() == null) {
                    throw new IllegalStateException("An unmade step value has no actual source");
                }
                List<com.lilradish.lite.domain.declaration.FieldName> path =
                        new ArrayList<>(output.pointer().names());
                path.addAll(suffix);
                yield through(run.beneath(step), step.beneath().run().workflow().outputs(), path);
            }
        };
    }

    private OriginRecord through(
            RunInTree run, List<Binding> bindings, List<com.lilradish.lite.domain.declaration.FieldName> path) {
        Pointer wanted = new Pointer(path);
        for (Binding binding : bindings) {
            Pointer target = requireNonNull(binding.target(), "A nested binding fills a field");
            if (wanted.within(target)) {
                List<com.lilradish.lite.domain.declaration.FieldName> suffix =
                        path.subList(target.names().size(), path.size());
                StepInputs.BindingRecord bound = StepInputs.bound(run, binding).orElse(null);
                if (bound == null) {
                    throw new IllegalStateException("An unmade input has no actual source");
                }
                return origin(run, binding.source(), suffix, bound.source());
            }
        }
        throw new IllegalStateException("A composite part has no declared binding");
    }

    private static List<Binding> bindingsOf(StepSnapshot step) {
        return switch (step.planned().runs()) {
            case StepRuns.Workflow ignored -> step.planned().bindings();
            case StepRuns.Route route -> {
                UUID chosen = requireNonNull(step.beneath(), "A route child names a case")
                        .routeCase();
                yield route.cases().stream()
                        .filter(aCase -> aCase.id().equals(chosen))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("A route child names no declared case"))
                        .bindings();
            }
            default -> throw new IllegalStateException("Only a workflow or route starts a child run");
        };
    }

    private static void enumerate(List<Field> fields, String prefix, Map<String, Integer> order) {
        for (Field field : fields) {
            String path = prefix.isEmpty()
                    ? field.name().value()
                    : prefix + "." + field.name().value();
            order.put(path, order.size());
            if (field.shape() instanceof FieldShape.Nested nested) {
                enumerate(nested.fields(), path, order);
            }
        }
    }

    record NumberedRunRecord(RunId run, int number) {}

    record At(
            NumberedRunRecord run,
            UUID stepId,
            String name,
            @Nullable String label) {}

    /** @param at absent once every step beneath is done */
    record Beneath(
            NumberedRunRecord run,
            Workflow workflow,
            @Nullable At at) {}

    record MakerRecord(
            NumberedRunRecord run,
            UUID stepId,
            String name,
            String field,
            @Nullable Integer tryNumber) {}

    record UsedRecord(
            NumberedRunRecord run,
            UUID stepId,
            String name,
            @Nullable String label,
            boolean choseBranch) {}

    /** @param value absent when a bound input has no standing value yet */
    record StartedInputRecord(
            String input,
            @DoNotLog @Nullable JsonValue value,
            @Nullable OriginRecord from,
            List<PartSourceRecord> parts) {}

    record PartSourceRecord(String targetPath, OriginRecord from) {}

    /** @param maker present only for a value made by a step */
    record OriginRecord(
            String kind,
            @Nullable String path,
            @Nullable NumberedRunRecord run,
            @Nullable MakerRecord maker) {}

    record Workflow(
            UUID entryId,
            String name,
            int version,
            UUID versionId,
            @Nullable VersionName versionName) {}

    private record Member(int number, Instant createdAt, Workflow workflow) {}
}
