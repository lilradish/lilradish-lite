package org.lilradish.lite.app.run;

import static java.util.Objects.requireNonNull;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.inference.ModelCallPurpose;
import org.lilradish.lite.domain.run.ModelCallId;
import org.lilradish.lite.domain.run.ProductionId;
import org.lilradish.lite.domain.run.RunId;
import org.lilradish.lite.domain.run.RunStepSendAttemptId;
import org.lilradish.lite.domain.wire.StoreLabels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * What this system does as it starts, however it stopped, and the one place that knows it started. Before it serves,
 * whatever was out is settled, each tree in a transaction of its own under its lock: a call or code that was out is
 * a try or a review spent that nothing came back for, and a call waiting to be sent again ends turned away, its step
 * held, or its values left waiting where it was to review them. Once it serves, every run at the top not stopped goes
 * on from wherever its steps are, as a drive works it out.
 */
@Component
final class EngineRestart implements SmartInitializingSingleton, SmartLifecycle {

    private static final Logger logger = LoggerFactory.getLogger(EngineRestart.class);

    private static final int BATCH = 200;

    // DB-SPECIFIC: an enum compared to a literal is PostgreSQL's.
    /* Unlocked: only a lead, each tree read again under its own lock before anything of it is ended. */
    private static final String OUT = """
            select run.group_id, run.run_id
              from runs run
             where run.run_id in (select call.root_run_id from model_calls call where call.outcome is null
                                  union
                                  select try.root_run_id from productions try
                                   where try.ended_at is null and try.producer = 'code')
            """;

    // DB-SPECIFIC: booleans selected as columns are PostgreSQL's.
    /* The newest turnaway picked as the resend after it picks it, so the two can never tell it apart. */
    private static final String UNENDED = """
            select call.model_call_id, call.purpose, call.production_id, call.run_step_send_attempt_id,
                   latest.model_call_turnaway_id is not null as turned_away_before,
                   latest.resent_at is not null as resent
              from model_calls call
              left join model_call_turnaways latest on latest.model_call_turnaway_id = (%s)
             where call.root_run_id = :root and call.outcome is null
             order by call.created_at, call.model_call_id
            """.formatted(EngineWrites.newestTurnaway("call.model_call_id"));

    // DB-SPECIFIC: limit is PostgreSQL's.
    /* Unlocked as well: a drive takes each tree's lock and works out afresh what, if anything, follows. */
    private static final String UNSTOPPED = """
            select run.group_id, run.run_id
              from runs run
             where run.parent_run_id is null and run.run_id > :after and not %s
             order by run.run_id
             limit %d
            """.formatted(RunTree.stopInForce("run.run_id"), BATCH);

    private final JdbcClient database;

    private final TransactionOperations transactions;

    private final RunTree tree;

    private final EngineWrites writes;

    private final EngineCalls calls;

    private final RunEngine engine;

    private final EngineExecutor executor;

    private volatile boolean settled;

    private volatile boolean running;

    EngineRestart(
            JdbcClient database,
            TransactionOperations transactions,
            RunTree tree,
            EngineWrites writes,
            EngineCalls calls,
            RunEngine engine,
            EngineExecutor executor) {
        this.database = database;
        this.transactions = transactions;
        this.tree = tree;
        this.writes = writes;
        this.calls = calls;
        this.engine = engine;
        this.executor = executor;
    }

    /**
     * Once every singleton is made and before the web server takes a request: before serving begins nothing hands a
     * task to the executor, so nothing can meet what is being settled. The lead failing refuses the start; a tree
     * failing is left as it reads.
     */
    @Override
    public void afterSingletonsInstantiated() {
        List<Tree> out = database.sql(OUT).query(EngineRestart::treeOf).list();
        for (Tree each : out) {
            @Nullable Settled settledHere;
            try {
                settledHere = transactions.execute(status ->
                        tree.lock(each.group(), each.root()).map(this::settle).orElse(null));
            } catch (RuntimeException failed) {
                logger.error(
                        "Run {} of group {} could not be settled as this system started, and is left as it reads",
                        each.root().value(),
                        each.group().value(),
                        failed);
                continue;
            }
            if (settledHere != null) {
                logged(each.root(), settledHere);
            }
        }
        settled = true;
    }

    private Settled settle(LockedTree held) {
        List<UnendedCall> unended = database.sql(UNENDED)
                .param("root", held.root().value())
                .query(EngineRestart::unended)
                .list();
        for (UnendedCall call : unended) {
            boolean ended =
                    switch (call.purpose()) {
                        case PRODUCE -> produced(held, call);
                        case REVIEW -> reviewed(held, call);
                        case HELP ->
                            throw new IllegalStateException(
                                    "Call " + call.call().value() + " under run "
                                            + held.root().value() + " was out to " + StoreLabels.label(call.purpose())
                                            + ", which nothing here settles yet");
                    };
            if (!ended) {
                throw new IllegalStateException(
                        "Call " + call.call().value() + " was ended outside its tree's lock as it was settled");
            }
        }
        return new Settled(unended, writes.codeLost(held));
    }

    private boolean produced(LockedTree held, UnendedCall call) {
        ProductionId aTry = requireNonNull(call.aTry(), "a call to produce names its try");
        return call.wasOut()
                ? calls.nothingCameBack(held, call.call(), aTry)
                : calls.notResent(
                        held, call.call(), requireNonNull(call.attempt(), "a call to produce names its attempt"));
    }

    /* A review nothing came back for is lost, which spends the try; one turned away leaves its values waiting. */
    private boolean reviewed(LockedTree held, UnendedCall call) {
        return call.wasOut()
                ? calls.reviewNothingCameBack(
                        held, call.call(), requireNonNull(call.aTry(), "a call to review names its try"))
                : calls.reviewNotResent(held, call.call());
    }

    /* Only once committed, so nothing is said to have ended that a later failure took back. */
    private static void logged(RunId root, Settled settledHere) {
        for (UnendedCall call : settledHere.calls()) {
            UUID aTry = requireNonNull(call.aTry(), "a call to produce or review names its try")
                    .value();
            if (call.purpose() == ModelCallPurpose.REVIEW) {
                logger.warn(
                        call.wasOut()
                                ? "Call {} reviewing try {} under run {} was out as this system stopped, and ended as"
                                        + " nothing came back"
                                : "Call {} reviewing try {} under run {} waited to be sent again as this system"
                                        + " stopped, and ended turned away, what it reviews waiting on it still",
                        call.call().value(),
                        aTry,
                        root.value());
            } else if (call.wasOut()) {
                logger.warn(
                        "Call {} of try {} under run {} was out as this system stopped, and ended as nothing came"
                                + " back",
                        call.call().value(),
                        aTry,
                        root.value());
            } else {
                logger.warn(
                        "Call {} of try {} under run {} waited to be sent again as this system stopped, and ended"
                                + " turned away, its step held",
                        call.call().value(),
                        aTry,
                        root.value());
            }
        }
        for (ProductionId lost : settledHere.codeLost()) {
            logger.warn(
                    "Try {} under run {} was running code as this system stopped, and ended as nothing came back",
                    lost.value(),
                    root.value());
        }
    }

    /** Once the web server serves, so a start that failed before it never hands a model call to a closing context. */
    @Override
    public void start() {
        if (!settled) {
            throw new IllegalStateException(
                    "Runs were to go on by themselves before what was out when this system stopped was settled");
        }
        running = true;
        executor.execute(this::resume);
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return DEFAULT_PHASE;
    }

    /**
     * On one of the engine's threads, leaving the other to live work: each run at the top not stopped is driven once,
     * a batch at a time, until none is left, this is stopped, or this system begins stopping. A drive finding nothing
     * writes nothing. A batch that cannot be read ends the walk, naming the last run it reached.
     */
    void resume() {
        UUID after = new UUID(0, 0);
        while (going()) {
            List<Tree> batch;
            try {
                batch = database.sql(UNSTOPPED)
                        .param("after", after)
                        .query(EngineRestart::treeOf)
                        .list();
            } catch (RuntimeException failed) {
                logger.error(
                        "Runs after run {} could not be read to go on by themselves, failing with {}; they go on as"
                                + " they are next driven",
                        after,
                        failed.getClass().getName());
                return;
            }
            for (Tree each : batch) {
                if (!going()) {
                    return;
                }
                engine.goOn(each.group(), each.root());
            }
            if (batch.size() < BATCH) {
                return;
            }
            after = batch.getLast().root().value();
        }
    }

    private boolean going() {
        return running && !executor.stopping();
    }

    private static Tree treeOf(ResultSet result, int number) throws SQLException {
        return new Tree(
                new GroupId(result.getObject("group_id", UUID.class)),
                new RunId(result.getObject("run_id", UUID.class)));
    }

    private static UnendedCall unended(ResultSet result, int number) throws SQLException {
        UUID aTry = result.getObject("production_id", UUID.class);
        UUID attempt = result.getObject("run_step_send_attempt_id", UUID.class);
        return new UnendedCall(
                new ModelCallId(result.getObject("model_call_id", UUID.class)),
                StoreLabels.parse(ModelCallPurpose.class, result.getString("purpose")),
                aTry == null ? null : new ProductionId(aTry),
                attempt == null ? null : new RunStepSendAttemptId(attempt),
                result.getBoolean("turned_away_before"),
                result.getBoolean("resent"));
    }

    private record Tree(GroupId group, RunId root) {}

    /** What one tree's transaction ended: its calls in the order they were written, then its code tries. */
    private record Settled(List<UnendedCall> calls, List<ProductionId> codeLost) {}
}
