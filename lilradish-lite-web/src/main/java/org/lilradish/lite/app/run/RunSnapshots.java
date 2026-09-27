package org.lilradish.lite.app.run;

import static java.util.Objects.requireNonNull;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.app.codestep.CodeSteps;
import org.lilradish.lite.app.library.ConstantJson;
import org.lilradish.lite.app.library.EntrySwitch;
import org.lilradish.lite.app.library.RunnableWorkflows;
import org.lilradish.lite.domain.codestep.CodeError;
import org.lilradish.lite.domain.codestep.CodeErrorReason;
import org.lilradish.lite.domain.declaration.Field;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.identity.SubjectId;
import org.lilradish.lite.domain.inference.DidNotFitReason;
import org.lilradish.lite.domain.inference.ModelCallOutcome;
import org.lilradish.lite.domain.inference.ModelCallPurpose;
import org.lilradish.lite.domain.registry.EntryId;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.lilradish.lite.domain.run.AttemptRecord;
import org.lilradish.lite.domain.run.CallRecord;
import org.lilradish.lite.domain.run.DecisionRecord;
import org.lilradish.lite.domain.run.FailureRecord;
import org.lilradish.lite.domain.run.HoldRecord;
import org.lilradish.lite.domain.run.InputRecord;
import org.lilradish.lite.domain.run.ModelCallId;
import org.lilradish.lite.domain.run.PlannedStep;
import org.lilradish.lite.domain.run.ProductionId;
import org.lilradish.lite.domain.run.ProductionValueId;
import org.lilradish.lite.domain.run.ReviewOutcome;
import org.lilradish.lite.domain.run.ReviewRecord;
import org.lilradish.lite.domain.run.ReviewUnbuiltReason;
import org.lilradish.lite.domain.run.RunId;
import org.lilradish.lite.domain.run.RunSnapshot;
import org.lilradish.lite.domain.run.RunStepFailureId;
import org.lilradish.lite.domain.run.RunStepFailureReason;
import org.lilradish.lite.domain.run.RunStepHoldReason;
import org.lilradish.lite.domain.run.RunStepId;
import org.lilradish.lite.domain.run.RunStepSendAttemptId;
import org.lilradish.lite.domain.run.RunnableWorkflow;
import org.lilradish.lite.domain.run.StepRuns;
import org.lilradish.lite.domain.run.StepSnapshot;
import org.lilradish.lite.domain.run.StopRecord;
import org.lilradish.lite.domain.run.TryLostReason;
import org.lilradish.lite.domain.run.TryRecord;
import org.lilradish.lite.domain.run.ValueRecord;
import org.lilradish.lite.domain.wire.JsonValue;
import org.lilradish.lite.domain.wire.StoreLabels;
import org.lilradish.lite.domain.workflow.Pointer;
import org.lilradish.lite.domain.workflow.StepProducer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Runs as the store holds them, read in the caller's transaction by statements made after whatever it locked:
 * under a tree's lock, each entry a run runs held for share; within a read-only snapshot, nothing held. However
 * many runs are read, and however many steps each has, the statements are as many, save the few each distinct
 * version they run takes to read. A stored value its type refuses fails the read.
 */
@Component
final class RunSnapshots {

    // DB-SPECIFIC: enum and jsonb casts to text, array casts, any(…), an enum compared to a literal, and booleans
    // and exists(…) selected as columns are PostgreSQL's.
    private static final String RUNS = """
            select run.run_id, run.root_run_id, run.entry_id, run.entry_version_id,
                   cast(run.started_with as text) as started_with, %s as stopped
              from runs run
             where run.run_id = any(cast(:runs as uuid[])) and run.group_id = :group
            """.formatted(RunTree.stopInForce("run.root_run_id"));

    private static final String STEPS = """
            select step.run_id, step.run_step_id, step.workflow_step_id
              from run_steps step
             where step.run_id = any(cast(:runs as uuid[]))
            """;

    private static final String TRIES = """
            select try.production_id, try.run_step_id, try.try_number, cast(try.producer as text) as producer,
                   try.created_at, try.created_by, try.created_by_kind = 'person' as asked_by_person,
                   try.ended_at, try.ended_by, try.ended_by_kind = 'person' as ended_by_person,
                   try.explanation, cast(try.lost_reason as text) as lost_reason,
                   cast(try.code_error_reason as text) as code_error_reason, try.code_error_path,
                   try.code_error_member, try.code_error_read_by_step, try.code_error_read_by_output,
                   try.lost_detail, try.lost_detail_truncated, try.returned_by_code
              from productions try
             where try.run_id = any(cast(:runs as uuid[]))
             order by try.run_step_id, try.try_number
            """;

    /* A question's value names its field by key and a code step's by name; the store orders neither. */
    private static final String VALUES = """
            select held.production_value_id, held.production_id, coalesce(held.field_name, field.name) as field,
                   cast(held.value as text) as value, held.confidence, held.needs_review
              from production_values held
              join productions try on try.production_id = held.production_id
              left join declaration_fields field on field.declaration_field_id = held.declaration_field_id
             where try.run_id = any(cast(:runs as uuid[]))
             order by held.production_id, field.position, held.field_name
            """;

    private static final String REVIEWS = """
            select review.review_id, review.production_id, review.created_by,
                   review.created_by_kind = 'person' as by_person, review.created_at, review.for_length,
                   cast(review.lost_reason as text) as lost_reason,
                   cast(review.did_not_fit_reason as text) as did_not_fit_reason
              from reviews review
              join productions try on try.production_id = review.production_id
             where try.run_id = any(cast(:runs as uuid[]))
             order by review.production_id, review.review_id
            """;

    private static final String DECISIONS = """
            select decision.review_id, decision.production_value_id, cast(decision.outcome as text) as outcome,
                   decision.explanation
              from review_decisions decision
              join productions try on try.production_id = decision.production_id
             where try.run_id = any(cast(:runs as uuid[]))
            """;

    private static final String INPUTS = """
            select taken.production_id, taken.binding_id, taken.source_production_value_id
              from production_inputs taken
             where taken.run_id = any(cast(:runs as uuid[])) and taken.production_id is not null
             order by taken.production_id, taken.production_input_id
            """;

    /* What an attempt would send, and what a call sent or got back, is never read into a snapshot. */
    private static final String ATTEMPTS = """
            select attempt.run_step_send_attempt_id, attempt.production_id, cast(attempt.purpose as text) as purpose,
                   attempt.too_long, cast(attempt.unbuilt_reason as text) as unbuilt_reason
              from run_step_send_attempts attempt
             where attempt.run_id = any(cast(:runs as uuid[]))
             order by attempt.production_id, attempt.created_at, attempt.run_step_send_attempt_id
            """;

    private static final String CALLS = """
            select call.model_call_id, call.production_id, call.run_step_send_attempt_id,
                   cast(call.outcome as text) as outcome,
                   exists (select 1 from model_call_turnaways turnaway
                            where turnaway.model_call_id = call.model_call_id and turnaway.spent_up) as spent_up
              from model_calls call
             where call.run_id = any(cast(:runs as uuid[])) and call.run_step_send_attempt_id is not null
             order by call.production_id, call.created_at, call.model_call_id
            """;

    private static final String HOLDS = """
            select hold.run_step_id, cast(hold.reason as text) as reason, hold.created_at,
                   hold.run_step_send_attempt_id,
                   exists (select 1 from model_calls call
                             join model_call_turnaways turnaway on turnaway.model_call_id = call.model_call_id
                            where call.run_step_send_attempt_id = hold.run_step_send_attempt_id
                              and turnaway.spent_up) as spent_up
              from run_step_holds hold
             where hold.run_id = any(cast(:runs as uuid[])) and hold.released_at is null
            """;

    private static final String FAILURES = """
            select failure.run_step_failure_id, failure.run_step_id, cast(failure.reason as text) as reason,
                   failure.production_id, cast(failure.purpose as text) as purpose, failure.created_at,
                   exists (select 1 from run_step_send_attempts attempt
                            where attempt.answers_failure_id = failure.run_step_failure_id) as answered
              from run_step_failures failure
             where failure.run_id = any(cast(:runs as uuid[]))
             order by failure.created_at, failure.run_step_failure_id
            """;

    private final JdbcClient database;

    private final CodeSteps release;

    RunSnapshots(JdbcClient database, CodeSteps release) {
        this.database = database;
        this.release = release;
    }

    /** Under the tree's lock, a run of that tree; each entry the run runs is held for share before it is read. */
    RunSnapshot locked(LockedTree tree, RunId run) {
        requireNonNull(tree, "RunSnapshots tree must not be null");
        tree.requireHeld();
        RunSnapshot read = read(tree.group(), List.of(run), true).getFirst();
        if (!read.root().equals(tree.root())) {
            throw new IllegalStateException("Run " + run.value() + " is not of the tree held");
        }
        return read;
    }

    /** Inside a read-only snapshot, holding nothing. */
    RunSnapshot asRead(GroupId group, RunId run) {
        return read(group, List.of(run), false).getFirst();
    }

    /**
     * Inside a read-only snapshot, holding nothing: each of {@code runs}, all of the group, in the order given.
     * One the group does not hold fails the read.
     */
    List<RunSnapshot> asRead(GroupId group, List<RunId> runs) {
        return read(group, runs, false);
    }

    private List<RunSnapshot> read(GroupId group, List<RunId> runs, boolean holding) {
        requireNonNull(group, "RunSnapshots group must not be null");
        requireNonNull(runs, "RunSnapshots runs must not be null");
        if (runs.isEmpty()) {
            return List.of();
        }
        String[] spelled =
                runs.stream().map(run -> run.value().toString()).distinct().toArray(String[]::new);
        Map<RunId, RunRow> rows = new HashMap<>();
        database.sql(RUNS).param("runs", spelled).param("group", group.value()).query(result -> {
            RunRow row = runRow(result);
            rows.put(row.run(), row);
        });
        Set<EntryVersionId> versions = new LinkedHashSet<>();
        for (RunId run : runs) {
            RunRow row = rows.get(run);
            if (row == null) {
                throw new IllegalStateException("Group " + group.value() + " holds no run " + run.value() + " to read");
            }
            versions.add(row.version());
        }
        Map<EntryVersionId, RunnableWorkflow> workflows = RunnableWorkflows.read(database, group, versions, release);
        Set<EntryId> entries = new LinkedHashSet<>();
        rows.values().forEach(row -> entries.add(row.entry()));
        workflows.values().forEach(workflow -> workflow.steps().forEach(step -> {
            EntryId pinned = pinnedEntry(step);
            if (pinned != null) {
                entries.add(pinned);
            }
        }));
        Map<EntryId, StopRecord> stopped =
                holding ? EntrySwitch.stopsOnceHeld(database, group, entries) : EntrySwitch.stops(database, entries);
        Map<RunId, Map<UUID, UUID>> runSteps = new HashMap<>();
        database.sql(STEPS).param("runs", spelled).query(result -> {
            runSteps.computeIfAbsent(new RunId(result.getObject("run_id", UUID.class)), ignored -> new HashMap<>())
                    .put(result.getObject("workflow_step_id", UUID.class), result.getObject("run_step_id", UUID.class));
        });
        Rows held = rows(spelled);
        List<RunSnapshot> read = new ArrayList<>(runs.size());
        for (RunId run : runs) {
            RunRow row = requireNonNull(rows.get(run));
            RunnableWorkflow workflow = requireNonNull(workflows.get(row.version()));
            Map<UUID, UUID> runStepOf = runSteps.getOrDefault(run, Map.of());
            List<StepSnapshot> steps = new ArrayList<>(workflow.steps().size());
            for (PlannedStep planned : workflow.steps()) {
                UUID runStep = runStepOf.get(planned.id().value());
                EntryId pinned = pinnedEntry(planned);
                steps.add(new StepSnapshot(
                        planned,
                        pinned == null ? null : stopped.get(pinned),
                        runStep == null ? null : new RunStepId(runStep),
                        runStep == null ? null : held.holds.get(runStep),
                        runStep == null ? List.of() : held.failures.getOrDefault(runStep, List.of()),
                        runStep == null ? List.of() : inDeclaredOrder(planned, held.tries(runStep))));
            }
            read.add(new RunSnapshot(
                    run,
                    row.root(),
                    group,
                    row.version(),
                    row.stopped(),
                    stopped.get(row.entry()),
                    startedWith(run, row.startedWith()),
                    workflow,
                    steps));
        }
        return read;
    }

    private static JsonValue.@Nullable JsonObject startedWith(RunId run, @Nullable String started) {
        if (started == null) {
            return null;
        }
        if (!(ConstantJson.stored(started) instanceof JsonValue.JsonObject object)) {
            throw new IllegalStateException("Run " + run.value() + " was started with other than an object");
        }
        return object;
    }

    /* A code step's values are read in name order, which the release's declaration puts in its own; a field it no
     * longer declares goes after every one it does, in name order still. */
    private static List<TryRecord> inDeclaredOrder(PlannedStep planned, List<TryRecord> tries) {
        if (!(planned.runs() instanceof StepRuns.Code code) || code.released() == null) {
            return tries;
        }
        List<Field> declared = code.released().declared().gives().fields();
        List<TryRecord> ordered = new ArrayList<>(tries.size());
        for (TryRecord aTry : tries) {
            List<ValueRecord> values = new ArrayList<>(aTry.values());
            values.sort(Comparator.comparingInt(value -> declaredAt(declared, value.field())));
            ordered.add(new TryRecord(
                    aTry.id(),
                    aTry.number(),
                    aTry.producer(),
                    aTry.askedBy(),
                    aTry.askedAt(),
                    aTry.endedAt(),
                    aTry.endedBy(),
                    aTry.explanation(),
                    aTry.lost(),
                    aTry.fault(),
                    aTry.lostDetail(),
                    aTry.lostDetailCut(),
                    aTry.returned(),
                    values,
                    aTry.reviews(),
                    aTry.inputs(),
                    aTry.attempts(),
                    aTry.calls()));
        }
        return ordered;
    }

    private static int declaredAt(List<Field> declared, String field) {
        for (int index = 0; index < declared.size(); index++) {
            if (declared.get(index).name().value().equals(field)) {
                return index;
            }
        }
        return declared.size();
    }

    private static @Nullable EntryId pinnedEntry(PlannedStep step) {
        return switch (step.runs()) {
            case StepRuns.Question question -> question.pinned().entry();
            case StepRuns.Workflow pinned -> pinned.pinned().entry();
            case StepRuns.Code ignored -> null;
            case StepRuns.Route ignored -> null;
        };
    }

    private Rows rows(String[] runs) {
        Rows rows = new Rows();
        Map<UUID, List<ValueRecord>> values = new HashMap<>();
        database.sql(VALUES).param("runs", runs).query(result -> {
            values.computeIfAbsent(result.getObject("production_id", UUID.class), ignored -> new ArrayList<>())
                    .add(value(result));
        });
        Map<UUID, List<DecisionRecord>> decisions = new HashMap<>();
        database.sql(DECISIONS).param("runs", runs).query(result -> {
            String outcome = result.getString("outcome");
            decisions
                    .computeIfAbsent(result.getObject("review_id", UUID.class), ignored -> new ArrayList<>())
                    .add(new DecisionRecord(
                            new ProductionValueId(result.getObject("production_value_id", UUID.class)),
                            StoreLabels.parse(ReviewOutcome.class, requireNonNull(outcome)),
                            result.getString("explanation")));
        });
        Map<UUID, List<ReviewRecord>> reviews = new HashMap<>();
        database.sql(REVIEWS).param("runs", runs).query(result -> {
            UUID review = result.getObject("review_id", UUID.class);
            String lost = result.getString("lost_reason");
            String misfit = result.getString("did_not_fit_reason");
            reviews.computeIfAbsent(result.getObject("production_id", UUID.class), ignored -> new ArrayList<>())
                    .add(new ReviewRecord(
                            result.getBoolean("by_person")
                                    ? new SubjectId(result.getObject("created_by", UUID.class))
                                    : null,
                            instant(result, "created_at"),
                            result.getBoolean("for_length"),
                            lost == null ? null : StoreLabels.parse(TryLostReason.class, lost),
                            misfit == null ? null : StoreLabels.parse(DidNotFitReason.class, misfit),
                            decisions.getOrDefault(review, List.of())));
        });
        Map<UUID, List<InputRecord>> inputs = new HashMap<>();
        database.sql(INPUTS).param("runs", runs).query(result -> {
            UUID source = result.getObject("source_production_value_id", UUID.class);
            inputs.computeIfAbsent(result.getObject("production_id", UUID.class), ignored -> new ArrayList<>())
                    .add(new InputRecord(
                            result.getObject("binding_id", UUID.class),
                            source == null ? null : new ProductionValueId(source)));
        });
        Map<UUID, List<AttemptRecord>> attempts = new HashMap<>();
        database.sql(ATTEMPTS).param("runs", runs).query(result -> {
            String unbuilt = result.getString("unbuilt_reason");
            attempts.computeIfAbsent(result.getObject("production_id", UUID.class), ignored -> new ArrayList<>())
                    .add(new AttemptRecord(
                            new RunStepSendAttemptId(result.getObject("run_step_send_attempt_id", UUID.class)),
                            StoreLabels.parse(ModelCallPurpose.class, requireNonNull(result.getString("purpose"))),
                            result.getBoolean("too_long"),
                            unbuilt == null ? null : StoreLabels.parse(ReviewUnbuiltReason.class, unbuilt)));
        });
        Map<UUID, List<CallRecord>> calls = new HashMap<>();
        database.sql(CALLS).param("runs", runs).query(result -> {
            String outcome = result.getString("outcome");
            calls.computeIfAbsent(result.getObject("production_id", UUID.class), ignored -> new ArrayList<>())
                    .add(new CallRecord(
                            new ModelCallId(result.getObject("model_call_id", UUID.class)),
                            new RunStepSendAttemptId(result.getObject("run_step_send_attempt_id", UUID.class)),
                            outcome == null ? null : StoreLabels.parse(ModelCallOutcome.class, outcome),
                            result.getBoolean("spent_up")));
        });
        database.sql(TRIES).param("runs", runs).query(result -> {
            UUID id = result.getObject("production_id", UUID.class);
            rows.tries
                    .computeIfAbsent(result.getObject("run_step_id", UUID.class), ignored -> new ArrayList<>())
                    .add(tried(result, id, values, reviews, inputs, attempts, calls));
        });
        database.sql(HOLDS).param("runs", runs).query(result -> {
            UUID runStep = result.getObject("run_step_id", UUID.class);
            UUID attempt = result.getObject("run_step_send_attempt_id", UUID.class);
            HoldRecord hold = new HoldRecord(
                    StoreLabels.parse(RunStepHoldReason.class, requireNonNull(result.getString("reason"))),
                    instant(result, "created_at"),
                    attempt == null ? null : new RunStepSendAttemptId(attempt),
                    result.getBoolean("spent_up"));
            if (rows.holds.put(runStep, hold) != null) {
                throw new IllegalStateException("Step " + runStep + " is held back twice at once");
            }
        });
        database.sql(FAILURES).param("runs", runs).query(result -> {
            UUID onTry = result.getObject("production_id", UUID.class);
            String purpose = result.getString("purpose");
            rows.failures
                    .computeIfAbsent(result.getObject("run_step_id", UUID.class), ignored -> new ArrayList<>())
                    .add(new FailureRecord(
                            new RunStepFailureId(result.getObject("run_step_failure_id", UUID.class)),
                            StoreLabels.parse(RunStepFailureReason.class, requireNonNull(result.getString("reason"))),
                            onTry == null ? null : new ProductionId(onTry),
                            purpose == null ? null : StoreLabels.parse(ModelCallPurpose.class, purpose),
                            instant(result, "created_at"),
                            result.getBoolean("answered")));
        });
        return rows;
    }

    private static TryRecord tried(
            ResultSet result,
            UUID id,
            Map<UUID, List<ValueRecord>> values,
            Map<UUID, List<ReviewRecord>> reviews,
            Map<UUID, List<InputRecord>> inputs,
            Map<UUID, List<AttemptRecord>> attempts,
            Map<UUID, List<CallRecord>> calls)
            throws SQLException {
        OffsetDateTime ended = result.getObject("ended_at", OffsetDateTime.class);
        String lost = result.getString("lost_reason");
        return new TryRecord(
                new ProductionId(id),
                result.getInt("try_number"),
                StoreLabels.parse(StepProducer.class, requireNonNull(result.getString("producer"))),
                result.getBoolean("asked_by_person") ? new SubjectId(result.getObject("created_by", UUID.class)) : null,
                instant(result, "created_at"),
                ended == null ? null : ended.toInstant(),
                result.getBoolean("ended_by_person") && ended != null
                        ? new SubjectId(result.getObject("ended_by", UUID.class))
                        : null,
                result.getString("explanation"),
                lost == null ? null : StoreLabels.parse(TryLostReason.class, lost),
                fault(result),
                result.getString("lost_detail"),
                result.getBoolean("lost_detail_truncated"),
                result.getString("returned_by_code"),
                values.getOrDefault(id, List.of()),
                reviews.getOrDefault(id, List.of()),
                inputs.getOrDefault(id, List.of()),
                attempts.getOrDefault(id, List.of()),
                calls.getOrDefault(id, List.of()));
    }

    private static CodeError.@Nullable Fault fault(ResultSet result) throws SQLException {
        String reason = result.getString("code_error_reason");
        if (reason == null) {
            return null;
        }
        String path = result.getString("code_error_path");
        UUID readByStep = result.getObject("code_error_read_by_step", UUID.class);
        String readByOutput = result.getString("code_error_read_by_output");
        CodeError.ReadBy readBy = readByStep != null
                ? new CodeError.StepReads(readByStep)
                : readByOutput != null
                        ? new CodeError.OutputReads(Pointer.parse(readByOutput).names())
                        : null;
        return new CodeError.Fault(
                StoreLabels.parse(CodeErrorReason.class, reason),
                path == null ? List.of() : Pointer.parse(path).names(),
                result.getString("code_error_member"),
                readBy);
    }

    private static ValueRecord value(ResultSet result) throws SQLException {
        String held = result.getString("value");
        return new ValueRecord(
                new ProductionValueId(result.getObject("production_value_id", UUID.class)),
                requireNonNull(result.getString("field")),
                held == null ? new JsonValue.JsonNull() : ConstantJson.stored(held),
                result.getObject("confidence", Integer.class),
                result.getBoolean("needs_review"));
    }

    private static RunRow runRow(ResultSet result) throws SQLException {
        return new RunRow(
                new RunId(result.getObject("run_id", UUID.class)),
                new RunId(result.getObject("root_run_id", UUID.class)),
                new EntryId(result.getObject("entry_id", UUID.class)),
                new EntryVersionId(result.getObject("entry_version_id", UUID.class)),
                result.getString("started_with"),
                result.getBoolean("stopped"));
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        return result.getObject(column, OffsetDateTime.class).toInstant();
    }

    private record RunRow(
            RunId run,
            RunId root,
            EntryId entry,
            EntryVersionId version,
            @Nullable String startedWith,
            boolean stopped) {}

    /** What the runs' steps hold, by each step's row. */
    private static final class Rows {

        private final Map<UUID, List<TryRecord>> tries = new HashMap<>();

        private final Map<UUID, HoldRecord> holds = new HashMap<>();

        private final Map<UUID, List<FailureRecord>> failures = new HashMap<>();

        List<TryRecord> tries(UUID runStep) {
            return tries.getOrDefault(runStep, List.of());
        }
    }
}
