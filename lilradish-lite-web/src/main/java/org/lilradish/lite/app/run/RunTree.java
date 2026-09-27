package org.lilradish.lite.app.run;

import static java.util.Objects.requireNonNull;

import java.util.Optional;
import java.util.UUID;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.run.RunId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The lock every transaction deciding or writing anything about a run tree takes first: its root's row, for no
 * key update. Everything decided on is read afresh after it, a group's row and the entries the run runs held for
 * share. Every writer of the tree holds it, so it alone orders them: beneath it rows are written as their keys
 * demand, a call ended before the try that names how it ended.
 */
@Component
public final class RunTree {

    // DB-SPECIFIC: for no key update is PostgreSQL's.
    /* Unlocked: a run's root and group never change, and a foreign key's key share never waits on the lock below. */
    private static final String ROOT =
            "select run.root_run_id from runs run where run.run_id = :run and run.group_id = :group";

    private static final String LOCKED =
            "select root.run_id from runs root where root.run_id = :root for no key update";

    private final JdbcClient database;

    RunTree(JdbcClient database) {
        this.database = database;
    }

    /** Whether a stop is in force on the tree whose root {@code root} names: one not yet opened again. */
    static String stopInForce(String root) {
        return """
                exists (select 1 from run_stops stop
                         where stop.run_id = %s and stop.opened_again_at is null)""".formatted(root);
    }

    /** The tree of the group's run, held until the calling transaction ends; none where it holds no such run. */
    public Optional<LockedTree> lock(GroupId group, RunId run) {
        requireNonNull(group, "RunTree group must not be null");
        requireNonNull(run, "RunTree run must not be null");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("A run tree is locked only inside the transaction that is to hold it");
        }
        Optional<UUID> root = database.sql(ROOT)
                .param("run", run.value())
                .param("group", group.value())
                .query(UUID.class)
                .optional();
        root.ifPresent(held ->
                database.sql(LOCKED).param("root", held).query(UUID.class).single());
        return root.map(held -> {
            LockedTree locked = new LockedTree(group, new RunId(held));
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    locked.released();
                }
            });
            return locked;
        });
    }
}
