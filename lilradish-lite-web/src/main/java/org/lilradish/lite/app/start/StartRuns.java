package org.lilradish.lite.app.start;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;
import org.lilradish.lite.app.change.Author;
import org.lilradish.lite.app.change.ChangeTransactions;
import org.lilradish.lite.app.files.FileDeployment;
import org.lilradish.lite.app.files.RunFiles;
import org.lilradish.lite.app.filling.ValueProblemsRefusal;
import org.lilradish.lite.app.group.GroupRoles;
import org.lilradish.lite.app.library.OffersHeld;
import org.lilradish.lite.app.run.RunEngine;
import org.lilradish.lite.app.run.RunNumbering;
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
import org.lilradish.lite.web.MultipartFiles;
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

    // DB-SPECIFIC: uuidv7(), a jsonb cast and returning are PostgreSQL's.
    private static final String STARTED = """
            with minted as (select uuidv7() as run_id)
            insert into runs (run_id, group_id, number, name, entry_id, entry_version_id, root_run_id, depth,
                              started_with, created_by)
            select minted.run_id, :group,
                   %s,
                   :name, :entry, :version, minted.run_id, 0, cast(:values as jsonb), %s
              from minted
            returning run_id, number
            """.formatted(RunNumbering.next(":group"), Author.OF_CALLER);

    // DB-SPECIFIC: the file transaction needs its run ID before it reads the first file part.
    private static final String STARTED_FILES = """
            insert into runs (run_id, group_id, number, name, entry_id, entry_version_id, root_run_id, depth,
                              started_with, created_by)
            values (:run, :group, %s, :name, :entry, :version, :run, 0, cast(:values as jsonb), %s)
            returning number
            """.formatted(RunNumbering.next(":group"), Author.OF_CALLER);

    private final JdbcClient database;

    private final TransactionOperations transactions;

    private final GroupRoles roles;

    private final RunTree tree;

    private final RunEngine engine;

    private final RunFiles files;

    private final FileDeployment deployment;

    StartRuns(
            JdbcClient database,
            TransactionOperations transactions,
            GroupRoles roles,
            RunTree tree,
            RunEngine engine,
            RunFiles files,
            FileDeployment deployment) {
        this.database = database;
        this.transactions = transactions;
        this.roles = roles;
        this.tree = tree;
        this.engine = engine;
        this.files = files;
        this.deployment = deployment;
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

    /** Recheck the offered version and permissions before consuming any file part. */
    Started startFiles(
            GroupId group, EntryVersionId version, RunName name, JsonValue values, UserId caller, MultipartFiles body) {
        requireNonNull(group, "StartRuns group must not be null");
        requireNonNull(version, "StartRuns version must not be null");
        requireNonNull(name, "StartRuns name must not be null");
        requireNonNull(values, "StartRuns values must not be null");
        requireNonNull(caller, "StartRuns caller must not be null");
        requireNonNull(body, "StartRuns body must not be null");
        return requireNonNull(transactions.execute(status -> {
            try {
                return startedFiles(group, version, name, values, caller, body);
            } catch (IOException unread) {
                throw new UncheckedIOException(unread);
            }
        }));
    }

    private Started startedFiles(
            GroupId group, EntryVersionId version, RunName name, JsonValue values, UserId caller, MultipartFiles body)
            throws IOException {
        GroupRoles.StillReached reached = roles.stillReached(caller, group, GroupPermission.START_RUN);
        OffersHeld.Held held =
                OffersHeld.held(database, reached, version).orElseThrow(StartRefusal.WORKFLOW_NOT_OFFERED::raised);
        RunId run = new RunId(database.sql("select uuidv7()").query(UUID.class).single());
        FilledFields filled =
                switch (body.fill(run, group, held.takes(), values, null, files, deployment)) {
                    case FillOutcome.Unshaped ignored -> throw StartRefusal.BODY_UNUSABLE.raised();
                    case FillProblems problems -> throw new ValueProblemsRefusal(problems);
                    case FilledFields kept -> kept;
                };
        RunNumbering.hold(database, group);
        boolean readable = RunScope.readsWhatTheyStart(reached.permitted());
        int number = database.sql(STARTED_FILES)
                .param("run", run.value())
                .param("group", group.value())
                .param("name", name.value())
                .param("entry", held.entry().value())
                .param("version", version.value())
                .param("values", CanonicalJson.write(filled.values()))
                .param("caller", caller.value())
                .query(Integer.class)
                .single();
        engine.planStarted(tree.lock(group, run).orElseThrow());
        return new Started(run, number, readable);
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
        RunNumbering.hold(database, group);
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
