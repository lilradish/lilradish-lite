package org.lilradish.lite.app.start;

import static java.util.Objects.requireNonNull;

import java.util.UUID;
import org.lilradish.lite.app.change.Author;
import org.lilradish.lite.app.change.ChangeTransactions;
import org.lilradish.lite.app.filling.ValueProblemsRefusal;
import org.lilradish.lite.app.group.GroupRoles;
import org.lilradish.lite.app.library.OffersHeld;
import org.lilradish.lite.app.run.RunEngine;
import org.lilradish.lite.app.run.RunScope;
import org.lilradish.lite.app.run.RunTree;
import org.lilradish.lite.domain.filling.FillOutcome;
import org.lilradish.lite.domain.filling.FillProblems;
import org.lilradish.lite.domain.filling.FilledFields;
import org.lilradish.lite.domain.filling.Filling;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.identity.GroupPermission;
import org.lilradish.lite.domain.identity.UserId;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.lilradish.lite.domain.run.RunId;
import org.lilradish.lite.domain.run.RunName;
import org.lilradish.lite.domain.wire.CanonicalJson;
import org.lilradish.lite.domain.wire.JsonValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * A run started in {@link ChangeTransactions}' transaction as the caller's act: the group held, then the version
 * held as offered, then the values read against what it takes, then the group's numbering held and the run written
 * at its next number, and its first step planned under its own tree's lock before the transaction ends.
 */
@Component
final class StartRuns {

    /* The first key of every advisory lock on a group's run numbers; any other advisory lock, session-level ones
    above all, takes a first key of its own. */
    private static final int NUMBERING = 1;

    // DB-SPECIFIC: advisory locks and hashtext are PostgreSQL's.
    /* A statement of its own: one statement reads under the snapshot taken before a lock inside it was granted. */
    private static final String NUMBERED = "select pg_advisory_xact_lock(:numbering, hashtext(cast(:group as text)))";

    // DB-SPECIFIC: uuidv7(), a jsonb cast and returning are PostgreSQL's.
    private static final String STARTED = """
            with minted as (select uuidv7() as run_id)
            insert into runs (run_id, group_id, number, name, entry_id, entry_version_id, root_run_id, depth,
                              started_with, created_by)
            select minted.run_id, :group,
                   (select coalesce(max(taken.number), 0) + 1 from runs taken where taken.group_id = :group),
                   :name, :entry, :version, minted.run_id, 0, cast(:values as jsonb), %s
              from minted
            returning run_id, number
            """.formatted(Author.OF_CALLER);

    private final JdbcClient database;

    private final TransactionOperations transactions;

    private final GroupRoles roles;

    private final RunTree tree;

    private final RunEngine engine;

    StartRuns(
            JdbcClient database, TransactionOperations transactions, GroupRoles roles, RunTree tree, RunEngine engine) {
        this.database = database;
        this.transactions = transactions;
        this.roles = roles;
        this.tree = tree;
        this.engine = engine;
    }

    /** The run started, the number it took and whether the caller may read it, in one transaction. */
    Started start(GroupId group, EntryVersionId version, RunName name, JsonValue values, UserId caller) {
        requireNonNull(group, "StartRuns group must not be null");
        requireNonNull(version, "StartRuns version must not be null");
        requireNonNull(name, "StartRuns name must not be null");
        requireNonNull(values, "StartRuns values must not be null");
        requireNonNull(caller, "StartRuns caller must not be null");
        return requireNonNull(transactions.execute(status -> started(group, version, name, values, caller)));
    }

    private Started started(GroupId group, EntryVersionId version, RunName name, JsonValue values, UserId caller) {
        GroupRoles.StillReached reached = roles.stillReached(caller, group, GroupPermission.START_RUN);
        OffersHeld.Held held =
                OffersHeld.held(database, reached, version).orElseThrow(StartRefusal.WORKFLOW_NOT_OFFERED::raised);
        FilledFields filled =
                switch (Filling.of(held.takes(), values)) {
                    case FillOutcome.Unshaped ignored -> throw StartRefusal.BODY_UNUSABLE.raised();
                    case FillProblems problems -> throw new ValueProblemsRefusal(problems);
                    case FilledFields kept -> kept;
                };
        /* Held after every row lock above, so a start waiting on the numbering holds none another start waits on. */
        database.sql(NUMBERED)
                .param("numbering", NUMBERING)
                .param("group", group.value())
                .query()
                .listOfRows();
        boolean readable = RunScope.readsWhatTheyStart(reached.permitted());
        Started started = database.sql(STARTED)
                .param("group", group.value())
                .param("name", name.value())
                .param("entry", held.entry().value())
                .param("version", version.value())
                .param("values", CanonicalJson.write(filled.values()))
                .param("caller", caller.value())
                .query((result, number) -> new Started(
                        new RunId(result.getObject("run_id", UUID.class)), result.getInt("number"), readable))
                .single();
        /* Held last: nobody else can see the run yet, so its lock waits on nothing the locks above could. */
        engine.planStarted(tree.lock(group, started.run()).orElseThrow());
        return started;
    }

    /** @param number the group's next, counting every run of it */
    record Started(RunId run, int number, boolean readable) {}
}
