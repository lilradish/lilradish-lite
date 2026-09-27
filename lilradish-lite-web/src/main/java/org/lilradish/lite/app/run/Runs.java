package org.lilradish.lite.app.run;

import static java.util.Objects.requireNonNull;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
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
import org.lilradish.lite.domain.run.RunAct;
import org.lilradish.lite.domain.run.RunId;
import org.lilradish.lite.domain.run.RunName;
import org.lilradish.lite.domain.run.RunPosition;
import org.lilradish.lite.domain.run.RunPositions;
import org.lilradish.lite.domain.run.RunSnapshot;
import org.lilradish.lite.domain.run.RunState;
import org.lilradish.lite.domain.run.StepPosition;
import org.lilradish.lite.domain.run.StepPositions;
import org.lilradish.lite.domain.run.WorkflowStepId;
import org.lilradish.lite.domain.wire.JsonValue;
import org.lilradish.lite.domain.workflow.StepId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * One run as one moment of the store reads it, with what the caller may do to it, read afresh on every call and
 * only within the one group asking. A stored value its type refuses fails the whole read.
 */
@Component
final class Runs {

    // DB-SPECIFIC: an enum compared to a literal is PostgreSQL's.
    private static final String HEADER = """
            select run.number, run.name, run.parent_run_id, run.root_run_id, run.created_at,
                   tree_top.number as top_number, content.keeps_own_ceiling,
                   entry.entry_id, entry.name as workflow_name, version.number as version_number,
                   starter.kind = 'person' as started_by_person,
                   starter.subject_id, starter.user_id, starter.display_name
              from runs run
              join runs tree_top on tree_top.run_id = run.root_run_id
              join entries entry on entry.entry_id = run.entry_id
              join entry_versions version on version.entry_version_id = run.entry_version_id
              join workflow_versions content on content.entry_version_id = run.entry_version_id
              join subjects starter on starter.subject_id = run.created_by
             where %s
            """.formatted(RunScope.RUN_IN_VIEW);

    /* On the root alone: a run beneath is stopped and opened only with the run at the top. */
    private static final String STOP = """
            select stop.created_at, stop.ceiling_run_id, reached.number as ceiling_run_number,
                   stopper.kind = 'person' as by_person,
                   stopper.subject_id, stopper.user_id, stopper.display_name
              from run_stops stop
              join subjects stopper on stopper.subject_id = stop.created_by
              left join runs reached on reached.run_id = stop.ceiling_run_id
             where stop.run_id = :root and stop.opened_again_at is null
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
            Stop stop = database.sql(STOP)
                    .param("root", header.top().run().value())
                    .query((result, number) -> stop(result))
                    .optional()
                    .orElse(null);
            boolean atTop = header.above() == null;
            RunSnapshot snapshot = snapshots.asRead(group, run);
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
                    header.workflow(),
                    header.startedBy(),
                    header.startedAt(),
                    startedWith(snapshot),
                    state,
                    RunPositions.at(snapshot, positions)
                            .map(step ->
                                    new At(step.planned().id(), step.planned().name()))
                            .orElse(null),
                    stop,
                    RunBudget.spentAsRead(database, run, atTop),
                    ceiling,
                    acts(permitted, atTop, snapshot.stopped(), state, waiting));
        });
        return requireNonNull(read);
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
        EntryId entry = new EntryId(result.getObject("entry_id", UUID.class));
        try {
            return new Header(
                    result.getInt("number"),
                    name == null ? null : new RunName(name),
                    above == null ? null : new RunId(above),
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

    private static Stop stop(ResultSet result) throws SQLException {
        Instant at = result.getObject("created_at", OffsetDateTime.class).toInstant();
        if (result.getBoolean("by_person")) {
            return new Stop(at, new Stopper.ByPerson(PersonRows.person(result)));
        }
        UUID reached = result.getObject("ceiling_run_id", UUID.class);
        if (reached == null) {
            throw new IllegalStateException("A stop made at " + at + " was made by nobody and by no ceiling");
        }
        return new Stop(
                at, new Stopper.ByCeiling(new NumberedRun(new RunId(reached), result.getInt("ceiling_run_number"))));
    }

    /**
     * @param name none beneath another run, which the run above started rather than anybody
     * @param startedWith none beneath another run, which the store keeps nothing it was started with for
     * @param at none where it is not running
     * @param ceiling its own, or the one at the top where a run beneath keeps none of its own
     */
    record RunView(
            RunId runId,
            int number,
            @Nullable RunName name,
            @Nullable RunId above,
            Workflow workflow,
            PersonRows.@Nullable Person startedBy,
            Instant startedAt,
            @Nullable JsonNode startedWith,
            RunState state,
            @Nullable At at,
            @Nullable Stop stop,
            RunBudget.Spend spend,
            CeilingHeld ceiling,
            Set<RunAct> acts) {}

    /** The step a running run is on, which is the first not done. */
    record At(WorkflowStepId step, StepId name) {}

    /** @param version the number of the version that ran */
    record Workflow(EntryId entryId, EntryName name, int version) {}

    record NumberedRun(RunId run, int number) {}

    record Stop(Instant at, Stopper by) {}

    /** Somebody pressing Stop, or a ceiling reached, which names nobody. */
    sealed interface Stopper {

        record ByPerson(PersonRows.Person person) implements Stopper {}

        /** @param reached the run whose ceiling it was, which may be one beneath */
        record ByCeiling(NumberedRun reached) implements Stopper {}
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
            NumberedRun top,
            boolean keepsOwnCeiling,
            Workflow workflow,
            PersonRows.@Nullable Person startedBy,
            Instant startedAt) {}
}
