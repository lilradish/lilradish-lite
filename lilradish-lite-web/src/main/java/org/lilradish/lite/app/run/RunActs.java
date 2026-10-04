package org.lilradish.lite.app.run;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.app.library.ConstantJson;
import org.lilradish.lite.app.pool.PersonAnswer;
import org.lilradish.lite.app.pool.PersonRows;
import org.lilradish.lite.domain.filling.FillField;
import org.lilradish.lite.domain.run.ReviewOutcome;
import org.lilradish.lite.domain.run.RunCeilingChangeOutcome;
import org.lilradish.lite.domain.run.RunSnapshot;
import org.lilradish.lite.domain.run.StepRuns;
import org.lilradish.lite.domain.run.StepSnapshot;
import org.lilradish.lite.domain.wire.CanonicalJson;
import org.lilradish.lite.domain.wire.JsonValue;
import org.lilradish.lite.domain.wire.StoreLabels;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

final class RunActs {

    private static final String STEPS = """
            select step.run_step_id, step.workflow_step_id, step.created_at
              from run_steps step
             where step.run_id = :run
            """;

    // DB-SPECIFIC: PostgreSQL jsonb values cast to text for historical answers.
    private static final String ANSWERS = """
            select tried.production_id, step.workflow_step_id, tried.try_number, tried.ended_at, tried.explanation,
                   actor.subject_id, actor.user_id, actor.display_name,
                   coalesce(value.field_name, field.name) as field, cast(value.value as text) as value
              from productions tried
              join run_steps step on step.run_step_id = tried.run_step_id
              join subjects actor on actor.subject_id = tried.ended_by
              left join production_values value on value.production_id = tried.production_id
              left join declaration_fields field on field.declaration_field_id = value.declaration_field_id
             where tried.run_id = :run and tried.producer = 'person' and tried.ended_by_kind = 'person'
               and tried.ended_at is not null
             order by tried.ended_at, tried.production_id, field.position, value.field_name
            """;

    // DB-SPECIFIC: PostgreSQL enum outcomes cast to text for review events.
    private static final String REVIEWS = """
            select review.review_id, review.created_at, review.for_length, step.workflow_step_id,
                   tried.try_number, actor.subject_id, actor.user_id, actor.display_name,
                   coalesce(value.field_name, field.name) as field,
                   cast(decision.outcome as text) as outcome, decision.explanation,
                   held_run.run_id as held_run_id, held_run.number as held_run_number,
                   held_step.workflow_step_id as held_step_id, held_name.name as held_step_name
              from reviews review
              join productions tried on tried.production_id = review.production_id
              join run_steps step on step.run_step_id = tried.run_step_id
              join subjects actor on actor.subject_id = review.created_by
              join review_decisions decision on decision.review_id = review.review_id
              join production_values value on value.production_value_id = decision.production_value_id
              left join declaration_fields field on field.declaration_field_id = value.declaration_field_id
              left join run_step_holds held on held.run_step_hold_id = review.run_step_hold_id
              left join runs held_run on held_run.run_id = held.run_id
              left join run_steps held_step on held_step.run_step_id = held.run_step_id
              left join workflow_steps held_name on held_name.workflow_step_id = held_step.workflow_step_id
             where tried.run_id = :run and review.created_by_kind = 'person' and review.lost_reason is null
             order by review.created_at, review.review_id, field.position, value.field_name
            """;

    // DB-SPECIFIC: PostgreSQL enum outcomes cast to text for ceiling events.
    private static final String CEILINGS = """
            with recursive beneath as (
                select run_id from runs where run_id = :run and group_id = :group
                union all
                select child.run_id from runs child join beneath parent on child.parent_run_id = parent.run_id
                 where child.group_id = :group
            )
            select change.run_ceiling_change_id, change.position, target.run_id, target.number,
                   change.from_ceiling, change.to_ceiling, change.awaits_approval,
                   change.created_at, cast(change.outcome as text) as outcome, change.decided_at,
                   author.subject_id, author.user_id, author.display_name,
                   decider.subject_id as decided_subject_id, decider.user_id as decided_user_id,
                   decider.display_name as decided_display_name
              from run_ceiling_changes change
              join runs target on target.run_id = change.run_id
              join beneath on beneath.run_id = target.run_id
              join subjects author on author.subject_id = change.created_by
              left join subjects decider on decider.subject_id = change.decided_by
            """;

    private static final String STOPS = """
            select stop.run_stop_id, stop.created_at, stop.ceiling_run_id,
                   stopper.subject_id, stopper.user_id, stopper.display_name,
                   stop.opened_again_at, opener.subject_id as opened_subject_id,
                   opener.user_id as opened_user_id, opener.display_name as opened_display_name,
                   reached.run_id as reached_run_id, reached.number as reached_number
              from run_stops stop
              join runs viewed on viewed.run_id = :run
              join subjects stopper on stopper.subject_id = stop.created_by
              left join subjects opener on opener.subject_id = stop.opened_again_by
              left join lateral (select stop.ceiling_run_id as run_id
                                 union all
                                 select more.ceiling_run_id
                                   from run_stop_additional_ceilings more
                                  where more.run_stop_id = stop.run_stop_id) ceiling on true
              left join runs reached on reached.run_id = ceiling.run_id
             where stop.run_id = :root and stop.created_at >= viewed.created_at
             order by stop.created_at, stop.run_stop_id, reached.depth, reached.number
            """;

    private RunActs() {}

    static List<Event> read(JdbcClient database, NumberedRun run, NumberedRun root, UUID group, RunSnapshot snapshot) {
        List<Timed> found = new ArrayList<>();
        steps(database, run, found);
        answers(database, run, snapshot, found);
        reviews(database, run, found);
        ceilings(database, run.runId(), group, found);
        stops(database, run.runId(), root, found);
        found.sort(Comparator.comparing(Timed::at)
                .thenComparingInt(Timed::priority)
                .thenComparingLong(Timed::position)
                .thenComparing(timed -> timed.event().id()));
        return found.stream().map(Timed::event).toList();
    }

    private static void steps(JdbcClient database, NumberedRun run, List<Timed> found) {
        database.sql(STEPS).param("run", run.runId()).query(result -> {
            UUID id = result.getObject("run_step_id", UUID.class);
            Instant at = instant(result, "created_at");
            found.add(
                    new Timed(at, 0, Event.stepReached(id, at, result.getObject("workflow_step_id", UUID.class), run)));
        });
    }

    private static void answers(JdbcClient database, NumberedRun run, RunSnapshot snapshot, List<Timed> found) {
        Map<UUID, AnswerRows> grouped = new LinkedHashMap<>();
        Map<UUID, Map<String, FillField>> fields = answerFields(snapshot);
        database.sql(ANSWERS).param("run", run.runId()).query(result -> {
            UUID id = result.getObject("production_id", UUID.class);
            AnswerRows answer = grouped.computeIfAbsent(id, ignored -> answer(result));
            String field = result.getString("field");
            if (field != null) {
                answer.values().add(value(fields.get(answer.step()), field, result.getString("value")));
            }
        });
        grouped.forEach((id, answer) -> found.add(new Timed(answer.at(), 1, Event.answeredByHand(id, answer, run))));
    }

    private static AnswerRows answer(ResultSet result) {
        try {
            return new AnswerRows(
                    instant(result, "ended_at"),
                    result.getObject("workflow_step_id", UUID.class),
                    result.getInt("try_number"),
                    PersonAnswer.of(PersonRows.person(result)),
                    result.getString("explanation"),
                    new ArrayList<>());
        } catch (SQLException exception) {
            throw new IllegalStateException("A hand answer cannot be read", exception);
        }
    }

    private static Map<UUID, Map<String, FillField>> answerFields(RunSnapshot snapshot) {
        Map<UUID, Map<String, FillField>> indexed = new LinkedHashMap<>();
        for (StepSnapshot step : snapshot.steps()) {
            List<FillField> fields =
                    switch (step.planned().runs()) {
                        case StepRuns.Question question ->
                            FillField.of(question.gives().fields(), question.lists());
                        case StepRuns.Code code -> code.released() == null ? List.of() : historicalFields(code);
                        case StepRuns.Workflow ignored -> List.of();
                        case StepRuns.Route ignored -> List.of();
                        case StepRuns.Search ignored -> List.of();
                    };
            Map<String, FillField> named = new LinkedHashMap<>();
            for (FillField field : fields) {
                named.put(field.name().value(), field);
            }
            indexed.put(step.planned().id().value(), named);
        }
        return indexed;
    }

    private static List<FillField> historicalFields(StepRuns.Code code) {
        org.lilradish.lite.domain.codestep.ReleasedCodeStep released =
                java.util.Objects.requireNonNull(code.released());
        List<FillField> fields = new ArrayList<>();
        for (org.lilradish.lite.domain.declaration.Field field :
                released.declared().gives().fields()) {
            try {
                fields.addAll(FillField.of(List.of(field), released.lists()));
            } catch (IllegalArgumentException | IllegalStateException ignored) {
                // Keep the stored answer when this release cannot describe its field.
            }
        }
        return fields;
    }

    private static Value value(@Nullable Map<String, FillField> fields, String field, @Nullable String held) {
        if (fields == null) {
            throw new IllegalStateException("A hand answer names no step in its run");
        }
        JsonValue written = held == null ? new JsonValue.JsonNull() : ConstantJson.stored(held);
        FillField declared = fields.get(field);
        if (declared != null) {
            try {
                return new Value(field, WrittenValues.of(declared, written), null);
            } catch (IllegalArgumentException | IllegalStateException | ClassCastException ignored) {
                // Keep the stored answer when this release cannot read its value.
            }
        }
        return new Value(field, JsonNodeFactory.instance.stringNode(CanonicalJson.write(written)), Boolean.TRUE);
    }

    private static void reviews(JdbcClient database, NumberedRun run, List<Timed> found) {
        Map<UUID, ReviewRows> grouped = new LinkedHashMap<>();
        database.sql(REVIEWS).param("run", run.runId()).query(result -> {
            UUID id = result.getObject("review_id", UUID.class);
            ReviewRows review = grouped.computeIfAbsent(id, ignored -> review(result));
            review.values()
                    .add(new Decision(
                            result.getString("field"),
                            StoreLabels.parse(ReviewOutcome.class, result.getString("outcome"))
                                    .published(),
                            result.getString("explanation")));
        });
        grouped.forEach((id, review) -> {
            if (review.forLength()) {
                for (Decision decision : review.values()) {
                    found.add(new Timed(review.at(), 2, Event.refusedForLength(id, review, decision, run)));
                }
            } else {
                found.add(new Timed(review.at(), 2, Event.reviewed(id, review, run)));
            }
        });
    }

    private static ReviewRows review(ResultSet result) {
        try {
            UUID heldRun = result.getObject("held_run_id", UUID.class);
            Held held = heldRun == null
                    ? null
                    : new Held(
                            new NumberedRun(heldRun, result.getInt("held_run_number")),
                            result.getObject("held_step_id", UUID.class),
                            result.getString("held_step_name"));
            return new ReviewRows(
                    instant(result, "created_at"),
                    result.getObject("workflow_step_id", UUID.class),
                    result.getInt("try_number"),
                    PersonAnswer.of(PersonRows.person(result)),
                    result.getBoolean("for_length"),
                    held,
                    new ArrayList<>());
        } catch (SQLException exception) {
            throw new IllegalStateException("A review cannot be read", exception);
        }
    }

    private static void ceilings(JdbcClient database, UUID run, UUID group, List<Timed> found) {
        database.sql(CEILINGS).param("run", run).param("group", group).query(result -> {
            UUID id = result.getObject("run_ceiling_change_id", UUID.class);
            NumberedRun target = new NumberedRun(result.getObject("run_id", UUID.class), result.getInt("number"));
            String from = digits(result, "from_ceiling");
            String to = digits(result, "to_ceiling");
            PersonAnswer author = PersonAnswer.of(PersonRows.person(result));
            Instant made = instant(result, "created_at");
            long position = result.getLong("position") * 2;
            found.add(new Timed(
                    made,
                    3,
                    position,
                    Event.ceilingStarted(id, made, result.getBoolean("awaits_approval"), author, target, from, to)));
            String outcome = result.getString("outcome");
            if (outcome != null) {
                Instant decided = instant(result, "decided_at");
                found.add(new Timed(
                        decided,
                        3,
                        position + 1,
                        Event.ceilingDecided(
                                id,
                                decided,
                                StoreLabels.parse(RunCeilingChangeOutcome.class, outcome),
                                new PersonAnswer(
                                        result.getString("decided_user_id"), result.getString("decided_display_name")),
                                target,
                                from,
                                to)));
            }
        });
    }

    private static void stops(JdbcClient database, UUID run, NumberedRun root, List<Timed> found) {
        Map<UUID, StopRows> grouped = new LinkedHashMap<>();
        database.sql(STOPS).param("run", run).param("root", root.runId()).query(result -> {
            UUID id = result.getObject("run_stop_id", UUID.class);
            StopRows stop = grouped.computeIfAbsent(id, ignored -> stop(result));
            UUID reached = result.getObject("reached_run_id", UUID.class);
            if (reached != null) {
                stop.ceilings().add(new NumberedRun(reached, result.getInt("reached_number")));
            }
        });
        grouped.forEach((id, stop) -> {
            found.add(new Timed(stop.at(), 4, Event.stopped(id, stop, root)));
            if (stop.openedAt() != null) {
                found.add(new Timed(stop.openedAt(), 4, Event.openedAgain(id, stop, root)));
            }
        });
    }

    private static StopRows stop(ResultSet result) {
        try {
            boolean byCeiling = result.getObject("ceiling_run_id", UUID.class) != null;
            OffsetDateTime opened = result.getObject("opened_again_at", OffsetDateTime.class);
            String openedUser = result.getString("opened_user_id");
            return new StopRows(
                    instant(result, "created_at"),
                    byCeiling ? null : PersonAnswer.of(PersonRows.person(result)),
                    opened == null ? null : opened.toInstant(),
                    openedUser == null ? null : new PersonAnswer(openedUser, result.getString("opened_display_name")),
                    new ArrayList<>());
        } catch (SQLException exception) {
            throw new IllegalStateException("A stop cannot be read", exception);
        }
    }

    private static @Nullable String digits(ResultSet result, String column) throws SQLException {
        long number = result.getLong(column);
        return result.wasNull() ? null : Long.toString(number);
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        return result.getObject(column, OffsetDateTime.class).toInstant();
    }

    private record Timed(Instant at, int priority, long position, Event event) {
        private Timed(Instant at, int priority, Event event) {
            this(at, priority, 0, event);
        }
    }

    private record AnswerRows(Instant at, UUID step, int number, PersonAnswer by, String why, List<Value> values) {}

    private record ReviewRows(
            Instant at,
            UUID step,
            int number,
            PersonAnswer by,
            boolean forLength,
            @Nullable Held held,
            List<Decision> values) {}

    private record StopRows(
            Instant at,
            @Nullable PersonAnswer by,
            @Nullable Instant openedAt,
            @Nullable PersonAnswer openedBy,
            List<NumberedRun> ceilings) {}

    record NumberedRun(UUID runId, int number) {}

    record Held(NumberedRun run, UUID stepId, String stepName) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Value(String field, JsonNode value, @Nullable Boolean earlierShape) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Decision(String field, String outcome, @Nullable String why) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Event(
            String id,
            String kind,
            String at,
            @Nullable UUID stepId,
            @Nullable Held held,
            @Nullable PersonAnswer by,
            @Nullable Integer number,
            @Nullable List<Value> values,
            @Nullable List<Decision> decisions,
            @Nullable NumberedRun run,
            @Nullable String from,
            @Nullable String to,
            @Nullable List<NumberedRun> ceilings,
            @Nullable String why) {
        private static Event stepReached(UUID id, Instant at, UUID step, NumberedRun run) {
            return new Event(
                    "step:" + id,
                    "step_reached",
                    at.toString(),
                    step,
                    null,
                    null,
                    null,
                    null,
                    null,
                    run,
                    null,
                    null,
                    null,
                    null);
        }

        private static Event answeredByHand(UUID id, AnswerRows answer, NumberedRun run) {
            return new Event(
                    "answer:" + id,
                    "answered_by_hand",
                    answer.at().toString(),
                    answer.step(),
                    null,
                    answer.by(),
                    answer.number(),
                    List.copyOf(answer.values()),
                    null,
                    run,
                    null,
                    null,
                    null,
                    answer.why());
        }

        private static Event refusedForLength(UUID id, ReviewRows review, Decision decision, NumberedRun run) {
            return new Event(
                    "length:" + id + ":" + decision.field(),
                    "refused_for_length",
                    review.at().toString(),
                    review.step(),
                    java.util.Objects.requireNonNull(review.held(), "A length refusal has no held step"),
                    review.by(),
                    review.number(),
                    null,
                    List.of(decision),
                    run,
                    null,
                    null,
                    null,
                    null);
        }

        private static Event reviewed(UUID id, ReviewRows review, NumberedRun run) {
            return new Event(
                    "review:" + id,
                    "reviewed",
                    review.at().toString(),
                    review.step(),
                    null,
                    review.by(),
                    review.number(),
                    null,
                    List.copyOf(review.values()),
                    run,
                    null,
                    null,
                    null,
                    null);
        }

        private static Event ceilingStarted(
                UUID id,
                Instant at,
                boolean awaitsApproval,
                PersonAnswer by,
                NumberedRun run,
                @Nullable String from,
                @Nullable String to) {
            return new Event(
                    "ceiling:" + id,
                    awaitsApproval ? "ceiling_asked" : "ceiling_changed",
                    at.toString(),
                    null,
                    null,
                    by,
                    null,
                    null,
                    null,
                    run,
                    from,
                    to,
                    null,
                    null);
        }

        private static Event ceilingDecided(
                UUID id,
                Instant at,
                RunCeilingChangeOutcome outcome,
                PersonAnswer decider,
                NumberedRun run,
                @Nullable String from,
                @Nullable String to) {
            String kind =
                    switch (outcome) {
                        case APPROVED -> "ceiling_approved";
                        case REFUSED -> "ceiling_refused";
                        case WITHDRAWN_BY_REQUESTER -> "ceiling_withdrawn";
                        case SUPERSEDED_BY_CHANGE -> "ceiling_superseded";
                    };
            PersonAnswer by = outcome == RunCeilingChangeOutcome.SUPERSEDED_BY_CHANGE ? null : decider;
            return new Event(
                    "ceiling:" + id + ":outcome",
                    kind,
                    at.toString(),
                    null,
                    null,
                    by,
                    null,
                    null,
                    null,
                    run,
                    from,
                    to,
                    null,
                    null);
        }

        private static Event stopped(UUID id, StopRows stop, NumberedRun run) {
            return new Event(
                    "stop:" + id,
                    stop.by() == null ? "stopped_by_ceiling" : "stopped",
                    stop.at().toString(),
                    null,
                    null,
                    stop.by(),
                    null,
                    null,
                    null,
                    run,
                    null,
                    null,
                    stop.ceilings().isEmpty() ? null : List.copyOf(stop.ceilings()),
                    null);
        }

        private static Event openedAgain(UUID id, StopRows stop, NumberedRun run) {
            Instant at = java.util.Objects.requireNonNull(stop.openedAt(), "An opened stop has no time");
            return new Event(
                    "stop:" + id + ":opened",
                    "opened_again",
                    at.toString(),
                    null,
                    null,
                    stop.openedBy(),
                    null,
                    null,
                    null,
                    run,
                    null,
                    null,
                    null,
                    null);
        }
    }
}
