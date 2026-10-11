package com.lilradish.lite.app.run;

import com.lilradish.lite.app.change.Author;
import com.lilradish.lite.app.change.ChangeTransactions;
import com.lilradish.lite.app.group.GroupReach;
import com.lilradish.lite.app.group.GroupRoles;
import com.lilradish.lite.domain.failure.RefusalCode;
import com.lilradish.lite.domain.identity.GroupId;
import com.lilradish.lite.domain.identity.GroupPermission;
import com.lilradish.lite.domain.identity.UserId;
import com.lilradish.lite.domain.run.Ceiling;
import com.lilradish.lite.domain.run.CeilingChangeId;
import com.lilradish.lite.domain.run.CeilingMove;
import com.lilradish.lite.domain.run.RunAct;
import com.lilradish.lite.domain.run.RunCeilingChangeOutcome;
import com.lilradish.lite.domain.run.RunId;
import com.lilradish.lite.domain.run.RunName;
import com.lilradish.lite.domain.run.RunPosition;
import com.lilradish.lite.domain.run.RunPositions;
import com.lilradish.lite.domain.run.RunSnapshot;
import com.lilradish.lite.domain.run.RunState;
import com.lilradish.lite.domain.run.StepPositions;
import com.lilradish.lite.domain.wire.StoreLabels;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Every act on a run, each in {@link ChangeTransactions}' transaction as the caller's act, under its tree's lock
 * and then the group's; a guarded write finding nothing to change was changed outside those locks, and fails.
 */
@Component
final class RunChanges {

    private static final String AUTHOR = Author.OF_CALLER;

    private static final String IN_VIEW = "select 1 from runs run where " + RunScope.RUN_IN_VIEW;

    private static final String HELD = """
            select run.parent_run_id is null as at_top, run.name, %s as stopped
              from runs run
             where %s
            """.formatted(RunTree.stopInForce("run.root_run_id"), RunScope.RUN_IN_VIEW);

    /* No earlier than the last opening again, so the stops of a run read in the order they were made. */
    // DB-SPECIFIC: PostgreSQL now() uses the transaction's start time; greatest ignores a null
    // latest opening time when the run has not been opened again.
    private static final String STOP = """
            insert into run_stops (run_id, root_run_id, created_at, created_by)
            select :run, :run, greatest(now(), max(stop.opened_again_at)), %s
              from run_stops stop
             where stop.run_id = :run
            """.formatted(AUTHOR);

    // DB-SPECIFIC: PostgreSQL now() uses the transaction's start time.
    private static final String OPEN_AGAIN = """
            update run_stops
               set opened_again_at = greatest(now(), created_at), opened_again_by = %s
             where run_id = :run and opened_again_at is null
            """.formatted(AUTHOR);

    // DB-SPECIFIC: PostgreSQL now() uses the transaction's start time.
    private static final String RENAME = """
            update runs set name = :name, updated_at = greatest(now(), created_at), updated_by = %s
             where run_id = :run
            """.formatted(AUTHOR);

    /* Next after every change of the run, which only the tree's lock keeps from being taken twice. */
    // DB-SPECIFIC: PostgreSQL clock_timestamp() takes the current wall time; greatest ignores null
    // aggregates when no ceiling change or decision has been written.
    private static final String CHANGE_CEILING = """
            insert into run_ceiling_changes (run_id, position, from_ceiling, to_ceiling, awaits_approval,
                                             created_at, created_by)
            select :run, coalesce(max(change.position), 0) + 1, :from, :to, :awaits,
                   greatest(clock_timestamp(), max(change.created_at), max(change.decided_at)), %s
              from run_ceiling_changes change
             where change.run_id = :run
            """.formatted(AUTHOR);

    // DB-SPECIFIC: PostgreSQL clock_timestamp() and the enum cast set this decision.
    private static final String DECIDE = """
            update run_ceiling_changes change
               set outcome = cast(:outcome as run_ceiling_change_outcome),
                   decided_at = greatest(clock_timestamp(), change.created_at), decided_by = %s
             where change.run_ceiling_change_id = :change and change.run_id = :run and %s
            """.formatted(AUTHOR, CeilingRaises.STILL_WAITING);

    private static final String CEILING_TARGET = """
            select target.run_id
              from runs target
              join workflow_versions version on version.entry_version_id = target.entry_version_id
             where target.run_id = :target and target.root_run_id = :root
               and (target.run_id = :root or (target.parent_run_id is not null and version.keeps_own_ceiling))
            """;

    private static final String CHANGE_TARGET = """
            select target.run_id,
                   case when %s then asker.user_id = :caller else null end as raise_asked_by_caller
              from run_ceiling_changes change
              join runs target on target.run_id = change.run_id
              join workflow_versions version on version.entry_version_id = target.entry_version_id
              join subjects asker on asker.subject_id = change.created_by
             where change.run_ceiling_change_id = :change and target.root_run_id = :root
               and (target.run_id = :root or (target.parent_run_id is not null and version.keeps_own_ceiling))
            """.formatted(CeilingRaises.STILL_WAITING);

    private final JdbcClient database;

    private final TransactionOperations transactions;

    private final GroupRoles roles;

    private final RunTree tree;

    private final RunSnapshots snapshots;

    private final RunEngine engine;

    RunChanges(
            JdbcClient database,
            TransactionOperations transactions,
            GroupRoles roles,
            RunTree tree,
            RunSnapshots snapshots,
            RunEngine engine) {
        this.database = database;
        this.transactions = transactions;
        this.roles = roles;
        this.tree = tree;
        this.snapshots = snapshots;
        this.engine = engine;
    }

    void stop(GroupId group, RunId run, UserId caller) {
        transactions.executeWithoutResult(status -> {
            Held held = held(group, run, caller, RunAct.STOP);
            if (!held.stopped() && !held.position().done()) {
                changedOne(bound(STOP, run, caller).update(), run);
                engine.endWaitingCalls(held.tree());
            }
        });
    }

    /** Not stopped, nothing is recorded; opened, it goes on from wherever its steps are. */
    void openAgain(GroupId group, RunId run, UserId caller) {
        Boolean opened = transactions.execute(status -> {
            if (!held(group, run, caller, RunAct.OPEN_AGAIN).stopped()) {
                return false;
            }
            changedOne(bound(OPEN_AGAIN, run, caller).update(), run);
            return true;
        });
        if (Boolean.TRUE.equals(opened)) {
            engine.goOn(group, run);
        }
    }

    private boolean done(LockedTree held, RunId run) {
        RunSnapshot snapshot = snapshots.locked(held, run);
        return RunPositions.state(snapshot, StepPositions.of(snapshot)) == RunState.DONE;
    }

    void rename(GroupId group, RunId run, RunName name, UserId caller) {
        transactions.executeWithoutResult(status -> {
            if (!name.value().equals(held(group, run, caller, RunAct.RENAME).name())) {
                changedOne(
                        bound(RENAME, run, caller).param("name", name.value()).update(), run);
            }
        });
    }

    /**
     * Asked to be none takes the ceiling away. As it stands already, nothing is recorded; otherwise a raise
     * waiting is withdrawn by it, whether it holds at once or waits in its turn. Lowered to what was spent, it
     * stops the top for that ceiling alone, unless its run is done, which spends nothing more until tried again.
     */
    void changeCeiling(GroupId group, RunId root, RunId target, @Nullable Ceiling asked, UserId caller) {
        transactions.executeWithoutResult(status -> {
            Held held = held(group, root, caller, RunAct.CHANGE_CEILING);
            RunId run = ceilingTarget(root, target);
            RunBudget.InForce inForce = RunBudget.inForce(database, held.tree(), run);
            CeilingMove move = CeilingMove.of(inForce.ceiling(), asked, inForce.raiseNeedsApproval());
            if (move == CeilingMove.UNCHANGED) {
                return;
            }
            Optional<CeilingRaises.Waiting> waiting = CeilingRaises.waiting(database, run, caller);
            if (waiting.isPresent()) {
                decided(run, waiting.get().change(), RunCeilingChangeOutcome.SUPERSEDED_BY_CHANGE, caller);
            }
            Ceiling from = inForce.ceiling();
            changedOne(
                    bound(CHANGE_CEILING, run, caller)
                            .param("from", from == null ? null : from.value())
                            .param("to", asked == null ? null : asked.value())
                            .param("awaits", move == CeilingMove.AWAITS_APPROVAL)
                            .update(),
                    run);
            if (lowered(from, asked) && !held.stopped() && !done(held.tree(), run)) {
                engine.stopAtReachedCeilingOf(held.tree(), run);
            }
        });
    }

    /** Lowered, or given where there was none: a ceiling that may now stand at or under what was spent. */
    private static boolean lowered(@Nullable Ceiling from, @Nullable Ceiling asked) {
        return asked != null && (from == null || asked.value() < from.value());
    }

    /** In force from now, stopped or not: a run its ceiling stopped stays stopped until somebody opens it. */
    void approveRaise(GroupId group, RunId run, CeilingChangeId change, UserId caller) {
        transactions.executeWithoutResult(status -> {
            heldForDecision(group, run, caller, RunAct.APPROVE_RAISE);
            decided(
                    changeTarget(run, change, caller, RunAct.APPROVE_RAISE),
                    change,
                    RunCeilingChangeOutcome.APPROVED,
                    caller);
        });
    }

    void refuseRaise(GroupId group, RunId run, CeilingChangeId change, UserId caller) {
        transactions.executeWithoutResult(status -> {
            heldForDecision(group, run, caller, RunAct.REFUSE_RAISE);
            decided(
                    changeTarget(run, change, caller, RunAct.REFUSE_RAISE),
                    change,
                    RunCeilingChangeOutcome.REFUSED,
                    caller);
        });
    }

    void withdrawRaise(GroupId group, RunId run, CeilingChangeId change, UserId caller) {
        transactions.executeWithoutResult(status -> {
            heldForDecision(group, run, caller, RunAct.WITHDRAW_RAISE);
            decided(
                    changeTarget(run, change, caller, RunAct.WITHDRAW_RAISE),
                    change,
                    RunCeilingChangeOutcome.WITHDRAWN_BY_REQUESTER,
                    caller);
        });
    }

    private RunId ceilingTarget(RunId root, RunId target) {
        return database.sql(CEILING_TARGET)
                .param("root", root.value())
                .param("target", target.value())
                .query(UUID.class)
                .optional()
                .map(RunId::new)
                .orElseThrow(RunRefusal.RUN_NOT_IN_VIEW::raised);
    }

    private RunId changeTarget(RunId root, CeilingChangeId change, UserId caller, RunAct act) {
        return database.sql(CHANGE_TARGET)
                .param("root", root.value())
                .param("change", change.value())
                .param("caller", caller.value())
                .query((result, number) -> {
                    boolean askedByCaller = result.getBoolean("raise_asked_by_caller");
                    RunPosition.Raise raise = result.wasNull()
                            ? RunPosition.Raise.NONE_WAITING
                            : askedByCaller ? RunPosition.Raise.ASKED_BY_READER : RunPosition.Raise.ASKED_BY_ANOTHER;
                    return new ChangeTarget(new RunId(result.getObject("run_id", UUID.class)), raise);
                })
                .optional()
                .map(target -> {
                    RefusalCode refused = act.refusal(new RunPosition(true, false, false, target.raise()));
                    if (refused != null) {
                        throw RunRefusal.answering(refused).raised();
                    }
                    return target.run();
                })
                .orElseThrow(RunRefusal.CEILING_RAISE_NOT_WAITING::raised);
    }

    private void heldForDecision(GroupId group, RunId root, UserId caller, RunAct act) {
        held(group, root, caller, act, true);
    }

    /**
     * A run the caller may not read takes the path of one nobody holds, and no lock; one they may, the tree's lock,
     * then what they hold, then the run re-read, each refused in that order.
     */
    private Held held(GroupId group, RunId run, UserId caller, RunAct act) {
        return held(group, run, caller, act, false);
    }

    private Held held(GroupId group, RunId run, UserId caller, RunAct act, boolean deferRaise) {
        Set<GroupPermission> seen = GroupReach.reachedBy(roles.heldBy(caller, group));
        boolean inView = RunScope.scoped(database.sql(IN_VIEW), group, run, caller, seen)
                .query(Integer.class)
                .optional()
                .isPresent();
        Optional<LockedTree> locked = inView ? tree.lock(group, run) : Optional.empty();
        Set<GroupPermission> permitted = roles.stillReaching(caller, group, act.permission());
        LockedTree lockedTree = locked.orElseThrow(RunRefusal.RUN_NOT_IN_VIEW::raised);
        HeldRow row = RunScope.scoped(database.sql(HELD), group, run, caller, permitted)
                .query((result, number) -> new HeldRow(
                        result.getBoolean("at_top"), result.getBoolean("stopped"), result.getString("name")))
                .optional()
                .orElseThrow(RunRefusal.RUN_NOT_IN_VIEW::raised);
        // Of the acts held here only a stop turns on the run being done; a lowering reads that of its own run itself.
        boolean done = act == RunAct.STOP && done(lockedTree, run);
        Held held = new Held(
                lockedTree,
                new RunPosition(row.atTop(), row.stopped(), done, RunPosition.Raise.NONE_WAITING),
                row.name());
        RefusalCode refused =
                deferRaise ? row.atTop() ? null : RefusalCode.RUN_BENEATH_ANOTHER : act.refusal(held.position());
        if (refused != null) {
            throw RunRefusal.answering(refused).raised();
        }
        return held;
    }

    private void decided(RunId run, CeilingChangeId change, RunCeilingChangeOutcome outcome, UserId caller) {
        changedOne(
                bound(DECIDE, run, caller)
                        .param("change", change.value())
                        .param("outcome", StoreLabels.label(outcome))
                        .update(),
                run);
    }

    private JdbcClient.StatementSpec bound(String statement, RunId run, UserId caller) {
        return database.sql(statement).param("run", run.value()).param("caller", caller.value());
    }

    private static void changedOne(int changed, RunId run) {
        if (changed != 1) {
            throw new IllegalStateException(
                    "Run " + run.value() + " was changed outside its tree's lock: " + changed + " rows, not one");
        }
    }

    private record HeldRow(
            boolean atTop, boolean stopped, @Nullable String name) {}

    /** @param name none for a run beneath another, which the run above started rather than anybody */
    private record Held(
            LockedTree tree, RunPosition position, @Nullable String name) {

        boolean stopped() {
            return position.stopped();
        }
    }

    private record ChangeTarget(RunId run, RunPosition.Raise raise) {}
}
