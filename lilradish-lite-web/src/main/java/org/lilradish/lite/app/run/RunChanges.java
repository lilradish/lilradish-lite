package org.lilradish.lite.app.run;

import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.app.change.Author;
import org.lilradish.lite.app.change.ChangeTransactions;
import org.lilradish.lite.app.group.GroupReach;
import org.lilradish.lite.app.group.GroupRoles;
import org.lilradish.lite.domain.failure.RefusalCode;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.identity.GroupPermission;
import org.lilradish.lite.domain.identity.UserId;
import org.lilradish.lite.domain.run.Ceiling;
import org.lilradish.lite.domain.run.CeilingChangeId;
import org.lilradish.lite.domain.run.CeilingMove;
import org.lilradish.lite.domain.run.RunAct;
import org.lilradish.lite.domain.run.RunId;
import org.lilradish.lite.domain.run.RunName;
import org.lilradish.lite.domain.run.RunPosition;
import org.lilradish.lite.domain.run.RunPositions;
import org.lilradish.lite.domain.run.RunSnapshot;
import org.lilradish.lite.domain.run.RunState;
import org.lilradish.lite.domain.run.StepPositions;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Every act on a run, each in {@link ChangeTransactions}' transaction as the caller's act, under its tree's lock
 * and then the group's; a guarded write finding nothing to change was changed outside those locks, and fails.
 */
@Component
final class RunChanges {

    // DB-SPECIFIC: now(), greatest, exists(…) selected as a boolean and enum casts are PostgreSQL's.
    private static final String AUTHOR = Author.OF_CALLER;

    private static final String IN_VIEW = "select 1 from runs run where " + RunScope.RUN_IN_VIEW;

    /* Names the raise asked about; a raise waiting under another identifier is none of this act's. */
    private static final String HELD =
            """
            select run.parent_run_id is null as at_top,
                   run.name,
                   %s as stopped,
                   (select asker.user_id = :caller
                      from run_ceiling_changes change
                      join subjects asker on asker.subject_id = change.created_by
                     where change.run_id = run.run_id and change.run_ceiling_change_id = cast(:change as uuid)
                       and %s) as raise_asked_by_caller
              from runs run
             where %s
            """.formatted(RunTree.stopInForce("run.root_run_id"), CeilingRaises.STILL_WAITING, RunScope.RUN_IN_VIEW);

    /* No earlier than the last opening again, so the stops of a run read in the order they were made. */
    private static final String STOP = """
            insert into run_stops (run_id, root_run_id, created_at, created_by)
            select :run, :run, greatest(now(), max(stop.opened_again_at)), %s
              from run_stops stop
             where stop.run_id = :run
            """.formatted(AUTHOR);

    private static final String OPEN_AGAIN = """
            update run_stops
               set opened_again_at = greatest(now(), created_at), opened_again_by = %s
             where run_id = :run and opened_again_at is null
            """.formatted(AUTHOR);

    private static final String RENAME = """
            update runs set name = :name, updated_at = greatest(now(), created_at), updated_by = %s
             where run_id = :run
            """.formatted(AUTHOR);

    /* Next after every change of the run, which only the tree's lock keeps from being taken twice. */
    private static final String CHANGE_CEILING = """
            insert into run_ceiling_changes (run_id, position, from_ceiling, to_ceiling, awaits_approval, created_by)
            select :run, coalesce(max(change.position), 0) + 1, :from, :to, :awaits, %s
              from run_ceiling_changes change
             where change.run_id = :run
            """.formatted(AUTHOR);

    private static final String DECIDE = """
            update run_ceiling_changes change
               set outcome = cast(:outcome as run_ceiling_change_outcome),
                   decided_at = greatest(now(), change.created_at), decided_by = %s
             where change.run_ceiling_change_id = :change and change.run_id = :run and %s
            """.formatted(AUTHOR, CeilingRaises.STILL_WAITING);

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

    /** Stopped already, it stays as it was stopped; done, nothing is left to stop. */
    void stop(GroupId group, RunId run, UserId caller) {
        transactions.executeWithoutResult(status -> {
            Held held = held(group, run, caller, RunAct.STOP, null);
            if (!held.stopped() && !held.position().done()) {
                changedOne(bound(STOP, run, caller).update(), run);
            }
        });
    }

    /** Not stopped, nothing is recorded; opened, it goes on from wherever its steps are. */
    void openAgain(GroupId group, RunId run, UserId caller) {
        Boolean opened = transactions.execute(status -> {
            if (!held(group, run, caller, RunAct.OPEN_AGAIN, null).stopped()) {
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

    /** Named so already, nothing is recorded. */
    void rename(GroupId group, RunId run, RunName name, UserId caller) {
        transactions.executeWithoutResult(status -> {
            if (!name.value()
                    .equals(held(group, run, caller, RunAct.RENAME, null).name())) {
                changedOne(
                        bound(RENAME, run, caller).param("name", name.value()).update(), run);
            }
        });
    }

    /**
     * Asked to be none takes the ceiling away. As it stands already, nothing is recorded; otherwise a raise
     * waiting is withdrawn by it, whether it holds at once or waits in its turn.
     */
    void changeCeiling(GroupId group, RunId run, @Nullable Ceiling asked, UserId caller) {
        transactions.executeWithoutResult(status -> {
            Held held = held(group, run, caller, RunAct.CHANGE_CEILING, null);
            RunBudget.InForce inForce = RunBudget.inForce(database, held.tree(), run);
            CeilingMove move = CeilingMove.of(inForce.ceiling(), asked, inForce.raiseNeedsApproval());
            if (move == CeilingMove.UNCHANGED) {
                return;
            }
            Optional<CeilingRaises.Waiting> waiting = CeilingRaises.waiting(database, run, caller);
            if (waiting.isPresent()) {
                decided(run, waiting.get().change(), "withdrawn", caller);
            }
            Ceiling from = inForce.ceiling();
            changedOne(
                    bound(CHANGE_CEILING, run, caller)
                            .param("from", from == null ? null : from.value())
                            .param("to", asked == null ? null : asked.value())
                            .param("awaits", move == CeilingMove.AWAITS_APPROVAL)
                            .update(),
                    run);
        });
    }

    /** In force from now, stopped or not: a run its ceiling stopped stays stopped until somebody opens it. */
    void approveRaise(GroupId group, RunId run, CeilingChangeId change, UserId caller) {
        transactions.executeWithoutResult(status -> {
            held(group, run, caller, RunAct.APPROVE_RAISE, change);
            decided(run, change, "approved", caller);
        });
    }

    void refuseRaise(GroupId group, RunId run, CeilingChangeId change, UserId caller) {
        transactions.executeWithoutResult(status -> {
            held(group, run, caller, RunAct.REFUSE_RAISE, change);
            decided(run, change, "refused", caller);
        });
    }

    void withdrawRaise(GroupId group, RunId run, CeilingChangeId change, UserId caller) {
        transactions.executeWithoutResult(status -> {
            held(group, run, caller, RunAct.WITHDRAW_RAISE, change);
            decided(run, change, "withdrawn", caller);
        });
    }

    /**
     * A run the caller may not read takes the path of one nobody holds, and no lock; one they may, the tree's lock,
     * then what they hold, then the run re-read, each refused in that order.
     */
    private Held held(GroupId group, RunId run, UserId caller, RunAct act, @Nullable CeilingChangeId change) {
        Set<GroupPermission> seen = GroupReach.reachedBy(roles.heldBy(caller, group));
        boolean inView = RunScope.scoped(database.sql(IN_VIEW), group, run, caller, seen)
                .query(Integer.class)
                .optional()
                .isPresent();
        Optional<LockedTree> locked = inView ? tree.lock(group, run) : Optional.empty();
        Set<GroupPermission> permitted = roles.stillReaching(caller, group, act.permission());
        LockedTree lockedTree = locked.orElseThrow(RunRefusal.RUN_NOT_IN_VIEW::raised);
        HeldRow row = RunScope.scoped(database.sql(HELD), group, run, caller, permitted)
                .param("change", change == null ? null : change.value())
                .query((result, number) -> {
                    boolean askedByCaller = result.getBoolean("raise_asked_by_caller");
                    RunPosition.Raise raise = result.wasNull()
                            ? RunPosition.Raise.NONE_WAITING
                            : askedByCaller ? RunPosition.Raise.ASKED_BY_READER : RunPosition.Raise.ASKED_BY_ANOTHER;
                    return new HeldRow(
                            result.getBoolean("at_top"), result.getBoolean("stopped"), raise, result.getString("name"));
                })
                .optional()
                .orElseThrow(RunRefusal.RUN_NOT_IN_VIEW::raised);
        // Only a stop turns on the run being done; reading every step for any other act would be wasted.
        boolean done = act == RunAct.STOP && done(lockedTree, run);
        Held held = new Held(lockedTree, new RunPosition(row.atTop(), row.stopped(), done, row.raise()), row.name());
        RefusalCode refused = act.refusal(held.position());
        if (refused != null) {
            throw RunRefusal.answering(refused).raised();
        }
        return held;
    }

    private void decided(RunId run, CeilingChangeId change, String outcome, UserId caller) {
        changedOne(
                bound(DECIDE, run, caller)
                        .param("change", change.value())
                        .param("outcome", outcome)
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
            boolean atTop,
            boolean stopped,
            RunPosition.Raise raise,
            @Nullable String name) {}

    /** @param name none for a run beneath another, which the run above started rather than anybody */
    private record Held(
            LockedTree tree, RunPosition position, @Nullable String name) {

        boolean stopped() {
            return position.stopped();
        }
    }
}
