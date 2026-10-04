package org.lilradish.lite.app.run;

import static java.util.Objects.requireNonNull;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.app.group.GroupReach;
import org.lilradish.lite.app.group.GroupRoles;
import org.lilradish.lite.app.pool.PersonRows;
import org.lilradish.lite.domain.filling.FillField;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.identity.GroupPermission;
import org.lilradish.lite.domain.identity.GroupRole;
import org.lilradish.lite.domain.identity.UserId;
import org.lilradish.lite.domain.registry.EntryId;
import org.lilradish.lite.domain.registry.EntryName;
import org.lilradish.lite.domain.run.InputRecord;
import org.lilradish.lite.domain.run.RunAct;
import org.lilradish.lite.domain.run.RunId;
import org.lilradish.lite.domain.run.RunInTree;
import org.lilradish.lite.domain.run.RunName;
import org.lilradish.lite.domain.run.RunPosition;
import org.lilradish.lite.domain.run.RunPositions;
import org.lilradish.lite.domain.run.RunSnapshot;
import org.lilradish.lite.domain.run.RunState;
import org.lilradish.lite.domain.run.StepInputs;
import org.lilradish.lite.domain.run.StepPosition;
import org.lilradish.lite.domain.run.StepPositions;
import org.lilradish.lite.domain.run.StepRuns;
import org.lilradish.lite.domain.run.StepSnapshot;
import org.lilradish.lite.domain.run.WorkflowStepId;
import org.lilradish.lite.domain.wire.JsonValue;
import org.lilradish.lite.domain.workflow.Binding;
import org.lilradish.lite.domain.workflow.StepId;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * One run as one moment of the store reads it, with what the caller may do to it, read afresh on every call and
 * only within the one group asking. A stored value its type refuses fails the whole read.
 */
@Component
final class Runs {

    // DB-SPECIFIC: an enum compared to a literal is PostgreSQL's.
    private static final String HEADER = """
            select run.number, run.name, run.parent_run_id, run.root_run_id, run.created_at,
                   parent.number as parent_number, parent_step.workflow_step_id as parent_step_id,
                   parent_workflow_step.name as parent_step_name,
                   tree_top.number as top_number, content.keeps_own_ceiling,
                   entry.entry_id, entry.name as workflow_name, version.number as version_number,
                   starter.kind = 'person' as started_by_person,
                   starter.subject_id, starter.user_id, starter.display_name
              from runs run
              join runs tree_top on tree_top.run_id = run.root_run_id
              left join runs parent on parent.run_id = run.parent_run_id and parent.group_id = run.group_id
              left join run_steps parent_step on parent_step.run_id = parent.run_id
                                             and parent_step.run_step_id = run.parent_run_step_id
                                             and parent_step.workflow_step_id = run.parent_workflow_step_id
              left join workflow_steps parent_workflow_step
                     on parent_workflow_step.workflow_step_id = parent_step.workflow_step_id
              join entries entry on entry.entry_id = run.entry_id
              join entry_versions version on version.entry_version_id = run.entry_version_id
              join workflow_versions content on content.entry_version_id = run.entry_version_id
              join subjects starter on starter.subject_id = run.created_by
             where %s
            """.formatted(RunScope.RUN_IN_VIEW);

    // DB-SPECIFIC: a lateral join is PostgreSQL's.
    /* On the root alone: a run beneath is stopped and opened only with the run at the top. One row per ceiling. */
    private static final String STOP = """
            select stop.created_at, stop.ceiling_run_id, reached.run_id as reached_run_id,
                   reached.number as reached_number,
                   stopper.kind = 'person' as by_person,
                   stopper.subject_id, stopper.user_id, stopper.display_name
              from run_stops stop
              join subjects stopper on stopper.subject_id = stop.created_by
              left join lateral (select stop.ceiling_run_id as run_id
                                 union all
                                 select further.ceiling_run_id
                                   from run_stop_additional_ceilings further
                                  where further.run_stop_id = stop.run_stop_id) ceiling on true
              left join runs reached on reached.run_id = ceiling.run_id
             where stop.run_id = :root and stop.opened_again_at is null
             order by reached.depth, reached.number
            """;

    // DB-SPECIFIC: `with recursive` is how PostgreSQL spells a common table expression that names itself.
    /* Within the tree the run was found in, so a run read there reads every run above it under the same rule. */
    private static final String ABOVE = """
            with recursive above (run_id, parent_run_id, number, name, depth) as (
                    select run.run_id, run.parent_run_id, run.number, run.name, run.depth
                      from runs run
                     where run.run_id = :parent and run.root_run_id = :root and run.group_id = :group
                union all
                    select parent.run_id, parent.parent_run_id, parent.number, parent.name, parent.depth
                      from runs parent
                      join above on parent.run_id = above.parent_run_id
                     where parent.root_run_id = :root and parent.group_id = :group
            )
            select run_id, number, name from above order by depth
            """;

    private final JdbcClient database;

    private final GroupRoles roles;

    private final TransactionTemplate snapshot;

    private final RunSnapshots snapshots;

    Runs(JdbcClient database, GroupRoles roles, PlatformTransactionManager transactionManager, RunSnapshots snapshots) {
        this.database = database;
        this.roles = roles;
        this.snapshots = snapshots;
        TransactionTemplate snapshot = new TransactionTemplate(transactionManager);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setReadOnly(true);
        this.snapshot = snapshot;
    }

    /** Somebody holding nothing in the group is refused as the group is, and a run they may not read as none. */
    RunView run(GroupId group, RunId run, UserId caller) {
        RunView read = snapshot.execute(status -> {
            Set<GroupRole> held = roles.heldBy(caller, group);
            GroupReach.requireMember(held);
            Set<GroupPermission> permitted = GroupReach.reachedBy(held);
            Header header = RunScope.scoped(database.sql(HEADER), group, run, caller, permitted)
                    .query((result, number) -> header(result, run))
                    .optional()
                    .orElseThrow(RunRefusal.RUN_NOT_IN_VIEW::raised);
            ResultSetExtractor<Optional<Stop>> inForce = Runs::stop;
            Stop stop = database.sql(STOP)
                    .param("root", header.top().run().value())
                    .query(inForce)
                    .orElse(null);
            boolean atTop = header.above() == null;
            RunSnapshot snapshot;
            Started started;
            if (atTop) {
                snapshot = snapshots.asRead(group, run);
                started = new Started(startedWith(snapshot), null);
            } else {
                RunInTree context = snapshots.inTreeAsRead(group, run);
                snapshot = context.run();
                started = startedWith(context, permitted);
            }
            List<StepPosition> positions = StepPositions.of(snapshot);
            RunState state = RunPositions.state(snapshot, positions);
            Optional<CeilingRaises.Waiting> waiting = CeilingRaises.waiting(database, run, caller);
            CeilingHeld ceiling = atTop || header.keepsOwnCeiling()
                    ? new CeilingHeld.Own(RunBudget.inForceAsRead(database, run), waiting.orElse(null))
                    : new CeilingHeld.AtTop(header.top());
            return new RunView(
                    run,
                    header.number(),
                    header.name(),
                    header.above(),
                    header.from(),
                    runsAbove(group, run, header),
                    header.workflow(),
                    header.startedBy(),
                    header.startedAt(),
                    started.value(),
                    started.sources(),
                    state,
                    RunPositions.at(snapshot, positions)
                            .map(step ->
                                    new At(step.planned().id(), step.planned().name()))
                            .orElse(null),
                    stop,
                    RunBudget.spentAsRead(database, run, atTop),
                    ceiling,
                    acts(permitted, atTop, snapshot.stopped(), state, waiting),
                    RunActs.read(
                            database,
                            new RunActs.NumberedRun(run.value(), header.number()),
                            new RunActs.NumberedRun(
                                    header.top().run().value(), header.top().number()),
                            group.value(),
                            snapshot));
        });
        return requireNonNull(read);
    }

    /** Every run above this one, from the top down; none above a run at the top. */
    private List<RunAbove> runsAbove(GroupId group, RunId run, Header header) {
        RunId parent = header.above();
        if (parent == null) {
            return List.of();
        }
        List<RunAbove> chain = database.sql(ABOVE)
                .param("parent", parent.value())
                .param("root", header.top().run().value())
                .param("group", group.value())
                .query((result, number) -> runAbove(result, run))
                .list();
        if (chain.isEmpty() || !chain.getFirst().run().equals(header.top().run())) {
            throw new IllegalStateException("Run " + run.value() + " reaches no run at the top of its tree");
        }
        return List.copyOf(chain);
    }

    private static RunAbove runAbove(ResultSet result, RunId run) throws SQLException {
        String name = result.getString("name");
        try {
            return new RunAbove(
                    new RunId(result.getObject("run_id", UUID.class)),
                    result.getInt("number"),
                    name == null ? null : new RunName(name));
        } catch (IllegalArgumentException refused) {
            throw new IllegalStateException(
                    "A run above run " + run.value() + " holds a name this system will not show", refused);
        }
    }

    /** What the reader may do to the run where it stands, a raise named being the one waiting, if any. */
    static Set<RunAct> acts(
            Set<GroupPermission> permitted,
            boolean atTop,
            boolean stopped,
            RunState state,
            Optional<CeilingRaises.Waiting> waiting) {
        RunPosition.Raise raise = waiting.map(asked ->
                        asked.askedByReader() ? RunPosition.Raise.ASKED_BY_READER : RunPosition.Raise.ASKED_BY_ANOTHER)
                .orElse(RunPosition.Raise.NONE_WAITING);
        return RunAct.admitted(permitted, new RunPosition(atTop, stopped, state == RunState.DONE, raise));
    }

    private Started startedWith(RunInTree child, Set<GroupPermission> permitted) {
        RunInTree parent = requireNonNull(child.above());
        StepSnapshot from = requireNonNull(child.from());
        List<Binding> bindings =
                switch (from.planned().runs()) {
                    case StepRuns.Workflow ignored -> from.planned().bindings();
                    case StepRuns.Route route -> {
                        UUID chosen = requireNonNull(from.beneath()).routeCase();
                        yield route.cases().stream()
                                .filter(aCase -> aCase.id().equals(chosen))
                                .findFirst()
                                .orElseThrow(() -> new IllegalStateException("A route child names no declared case"))
                                .bindings();
                    }
                    default -> throw new IllegalStateException("Only a workflow or route starts a child run");
                };
        List<StepInputs.BindingRecord> bound = new ArrayList<>();
        List<InputRecord.Part> parts = new ArrayList<>();
        for (Binding binding : bindings) {
            StepInputs.BindingRecord read = StepInputs.bound(parent, binding).orElse(null);
            if (read == null) {
                continue;
            }
            bound.add(read);
            List<org.lilradish.lite.domain.declaration.FieldName> target = requireNonNull(
                            binding.target(), "A child input fills a field")
                    .names();
            if (read.parts().isEmpty()) {
                parts.add(new InputRecord.Part(target, read.source()));
            } else {
                for (InputRecord.Part part : read.parts()) {
                    List<org.lilradish.lite.domain.declaration.FieldName> path = new ArrayList<>(target);
                    path.addAll(part.targetPath());
                    parts.add(new InputRecord.Part(path, part.source()));
                }
            }
        }
        if (bound.isEmpty()) {
            return new Started(null, null);
        }
        List<FillField> fields = WrittenValues.takesOf(child.run());
        ObjectNode value = WrittenValues.of(
                fields, StepInputs.filled(child.run().workflow().takes(), bound));
        StepReading.withheldParts(fields, value, parts, permitted, StepReading.producersIn(child));
        RunReadDetails details = new RunReadDetails(database, child);
        List<StartedSource> sources = details.startedWith(child).stream()
                .filter(input -> input.value() != null)
                .map(input -> new StartedSource(
                        input.input(),
                        input.from() == null ? null : StepReading.from(details, input.from()),
                        input.parts().isEmpty()
                                ? null
                                : input.parts().stream()
                                        .map(part -> new StepAnswers.SourceAnswer(
                                                part.targetPath(), StepReading.from(details, part.from())))
                                        .toList()))
                .toList();
        return new Started(value, sources);
    }

    private record Started(
            @Nullable JsonNode value, @Nullable List<StartedSource> sources) {}

    record StartedSource(
            String input,
            StepAnswers.@Nullable FromAnswer from,
            @Nullable List<StepAnswers.SourceAnswer> sources) {

        StartedSource {
            if ((from == null) == (sources == null) || (sources != null && sources.isEmpty())) {
                throw new IllegalArgumentException("A started input has one origin or individual part origins");
            }
        }
    }

    private static @Nullable JsonNode startedWith(RunSnapshot snapshot) {
        JsonValue.JsonObject started = snapshot.startedWith();
        if (started == null) {
            return null;
        }
        List<FillField> takes = WrittenValues.takesOf(snapshot);
        try {
            return WrittenValues.of(takes, started);
        } catch (IllegalStateException unlike) {
            throw new IllegalStateException(
                    "Run " + snapshot.run().value() + " was started with other than what its version takes", unlike);
        }
    }

    private static Header header(ResultSet result, RunId run) throws SQLException {
        String name = result.getString("name");
        UUID above = result.getObject("parent_run_id", UUID.class);
        Number parentNumber = (Number) result.getObject("parent_number");
        UUID parentStep = result.getObject("parent_step_id", UUID.class);
        String parentStepName = result.getString("parent_step_name");
        if (above != null && (parentNumber == null || parentStep == null || parentStepName == null)) {
            throw new IllegalStateException("Run " + run.value() + " has no readable parent step");
        }
        EntryId entry = new EntryId(result.getObject("entry_id", UUID.class));
        try {
            return new Header(
                    result.getInt("number"),
                    name == null ? null : new RunName(name),
                    above == null ? null : new RunId(above),
                    above == null
                            ? null
                            : new From(
                                    new NumberedRun(new RunId(above), parentNumber.intValue()),
                                    new WorkflowStepId(parentStep),
                                    new StepId(parentStepName)),
                    new NumberedRun(
                            new RunId(result.getObject("root_run_id", UUID.class)), result.getInt("top_number")),
                    result.getBoolean("keeps_own_ceiling"),
                    new Workflow(
                            entry, new EntryName(result.getString("workflow_name")), result.getInt("version_number")),
                    result.getBoolean("started_by_person") ? PersonRows.person(result) : null,
                    result.getObject("created_at", OffsetDateTime.class).toInstant());
        } catch (IllegalArgumentException refused) {
            throw new IllegalStateException("Run " + run.value() + " holds a name this system will not show", refused);
        }
    }

    /** The stop in force, none where none is; a stop by ceilings names first the one it was stored naming. */
    private static Optional<Stop> stop(ResultSet result) throws SQLException {
        if (!result.next()) {
            return Optional.empty();
        }
        Instant at = result.getObject("created_at", OffsetDateTime.class).toInstant();
        if (result.getBoolean("by_person")) {
            return Optional.of(new Stop(at, new Stopper.ByPerson(PersonRows.person(result))));
        }
        UUID named = result.getObject("ceiling_run_id", UUID.class);
        if (named == null) {
            throw new IllegalStateException("A stop made at " + at + " was made by nobody and by no ceiling");
        }
        List<NumberedRun> reached = new ArrayList<>();
        do {
            reached.add(new NumberedRun(
                    new RunId(result.getObject("reached_run_id", UUID.class)), result.getInt("reached_number")));
        } while (result.next());
        if (!reached.getFirst().run().value().equals(named)) {
            throw new IllegalStateException("A stop made at " + at + " names run " + named
                    + " beneath another of the ceilings it was made for");
        }
        return Optional.of(new Stop(at, new Stopper.ByCeiling(reached)));
    }

    /**
     * @param name none beneath another run, which the run above started rather than anybody
     * @param runsAbove every run above it, from the top down; none for a run at the top
     * @param startedWith values as filled when this run started
     * @param at none where it is not running
     * @param ceiling its own, or the one at the top where a run beneath keeps none of its own
     */
    record RunView(
            RunId runId,
            int number,
            @Nullable RunName name,
            @Nullable RunId above,
            @Nullable From from,
            List<RunAbove> runsAbove,
            Workflow workflow,
            PersonRows.@Nullable Person startedBy,
            Instant startedAt,
            @Nullable JsonNode startedWith,
            @Nullable List<StartedSource> startedWithFrom,
            RunState state,
            @Nullable At at,
            @Nullable Stop stop,
            RunBudget.Spend spend,
            CeilingHeld ceiling,
            Set<RunAct> acts,
            List<RunActs.Event> conversationEvents) {}

    /** The step a running run is on, which is the first not done. */
    record At(WorkflowStepId step, StepId name) {}

    /** @param version the number of the version that ran */
    record Workflow(EntryId entryId, EntryName name, int version) {}

    record NumberedRun(RunId run, int number) {}

    /** @param name none but at the top of the tree, as a run beneath is started by the run above and named by none */
    record RunAbove(RunId run, int number, @Nullable RunName name) {}

    record From(NumberedRun run, WorkflowStepId step, StepId name) {}

    record Stop(Instant at, Stopper by) {}

    /** Somebody pressing Stop, or a ceiling reached, which names nobody. */
    sealed interface Stopper {

        record ByPerson(PersonRows.Person person) implements Stopper {}

        /**
         * @param reached every run whose ceiling it was, any of them beneath, from the top down and by number at one
         *     depth: the first is the one the stop names
         */
        record ByCeiling(List<NumberedRun> reached) implements Stopper {

            public ByCeiling {
                reached = List.copyOf(reached);
                if (reached.isEmpty()) {
                    throw new IllegalArgumentException("A stop by a ceiling names one ceiling at least");
                }
            }
        }
    }

    /** The ceiling a run is held to: its own, or the one at the top of its tree. */
    sealed interface CeilingHeld {

        /** @param waiting a raise of it waiting on approval, which is not in force */
        record Own(RunBudget.InForce inForce, CeilingRaises.@Nullable Waiting waiting) implements CeilingHeld {}

        record AtTop(NumberedRun top) implements CeilingHeld {}
    }

    private record Header(
            int number,
            @Nullable RunName name,
            @Nullable RunId above,
            @Nullable From from,
            NumberedRun top,
            boolean keepsOwnCeiling,
            Workflow workflow,
            PersonRows.@Nullable Person startedBy,
            Instant startedAt) {}
}
