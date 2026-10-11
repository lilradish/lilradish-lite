package com.lilradish.lite.app.run;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.run.Ceiling;
import com.lilradish.lite.domain.run.ProductionId;
import com.lilradish.lite.domain.run.RunId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * What a run has spent and the most it may, both worked out as they are read and neither stored. What is
 * decided on the ceiling in force asks for the tree held; a reading asks for nothing.
 */
final class RunBudget {

    // DB-SPECIFIC: PostgreSQL's native run_ceiling_change_outcome enum is compared with 'approved'.
    /** A change aliased {@code change} holds where it was made in force, a raise only once approved. */
    private static final String IN_FORCE = "(not change.awaits_approval or change.outcome = 'approved')";

    /*
     * Joined to a run aliased run: its version as version, and as latest the last change made in force. A raise
     * waiting is withdrawn by any change after it, so none approved is older.
     */
    // DB-SPECIFIC: PostgreSQL LIMIT 1 selects the latest ceiling change in the lateral query.
    static final String IN_FORCE_ON_RUN = """
            join workflow_versions version on version.entry_version_id = run.entry_version_id
              left join lateral (select true as changed, change.to_ceiling
                                   from run_ceiling_changes change
                                  where change.run_id = run.run_id and %s
                                  order by change.position desc
                                  limit 1) latest on true""".formatted(IN_FORCE);

    /** The ceiling in force, read over {@link #IN_FORCE_ON_RUN}; none where it was taken away. */
    static final String CEILING_IN_FORCE = "case when latest.changed then latest.to_ceiling else version.ceiling end";

    private static final String IN_FORCE_NOW = """
            select %s as ceiling, version.raise_needs_approval
              from runs run
              %s
             where run.run_id = :run
            """.formatted(CEILING_IN_FORCE, IN_FORCE_ON_RUN);

    /*
     * Every taken-up call, one still out included unless its latest turnaway is waiting to be resent, at what it
     * sent and whatever came back. A call the model did not count is counted at what this system measured.
     */
    private static final String COUNTS = """
            coalesce(sum(call.sent_count), 0) as sent,
                   coalesce(sum(call.came_back_count), 0) as came_back,
                   count(*) filter (where call.came_back_count is null) > 0 as came_back_unknown,
                   count(*) filter (where not call.counted_by_model) as measured_here_calls,
                   count(*) as total_calls""";

    private static final String COUNTED = """
            select %s
              from model_calls call
            """.formatted(COUNTS);

    // DB-SPECIFIC: PostgreSQL's native model_call_outcome enum is compared with 'turned_away'.
    /** A call aliased {@code call} counts once taken up, except while its latest turnaway awaits a resend. */
    static final String TAKEN_UP = """
            call.outcome is distinct from 'turned_away'
            and not exists (select 1 from model_call_turnaways latest
                             where latest.model_call_turnaway_id = (%s) and latest.resent_at is null)
            """.formatted(EngineWrites.newestTurnaway("call.model_call_id"));

    /* A call to produce or to review names the try it is about; one to help names none, and is the run's. */
    private static final String SPENT_BY_TRY = """
            select call.production_id, %s
              from model_calls call
             where call.run_id = :run and call.production_id is not null
               and %s
             group by call.production_id
            """.formatted(COUNTS, TAKEN_UP);

    /**
     * A call aliased {@code call} counted as spent by the tree whose root {@code :root} names: it carries its tree's
     * root, so a whole tree is read by that alone.
     */
    static final String SPENT_IN_TREE = "call.root_run_id = :root and " + TAKEN_UP;

    private static final String SPENT_BY_TREE = COUNTED + " where " + SPENT_IN_TREE;

    /* Depth grows by one down every parent, which a key holds, so the walk down never comes back up. */
    private static final String SPENT_BENEATH = """
            with recursive tree (run_id) as (
                    select run.run_id from runs run where run.run_id = :run
                union all
                    select beneath.run_id from runs beneath join tree on beneath.parent_run_id = tree.run_id
            )
            %s
             where call.run_id in (select tree.run_id from tree) and %s
            """.formatted(COUNTED, TAKEN_UP);

    private RunBudget() {}

    static InForce inForce(JdbcClient database, LockedTree tree, RunId run) {
        requireNonNull(tree, "RunBudget tree must not be null").requireHeld();
        return inForceNow(database, run);
    }

    /** To be shown and never decided on. */
    static InForce inForceAsRead(JdbcClient database, RunId run) {
        return inForceNow(database, run);
    }

    /** What the run and every run beneath it spent, as one reading of the store sees it. */
    static Spend spentAsRead(JdbcClient database, RunId run, boolean atTop) {
        JdbcClient.StatementSpec spent = atTop
                ? database.sql(SPENT_BY_TREE).param("root", run.value())
                : database.sql(SPENT_BENEATH).param("run", run.value());
        return spent.query((result, number) -> spendIn(result)).single();
    }

    /** What the calls about each try of the run's steps spent, by the try, as one reading of the store sees it. */
    static Map<ProductionId, Spend> spentByTryAsRead(JdbcClient database, RunId run) {
        Map<ProductionId, Spend> spent = new HashMap<>();
        database.sql(SPENT_BY_TRY).param("run", run.value()).query(result -> {
            spent.put(new ProductionId(result.getObject("production_id", UUID.class)), spendIn(result));
        });
        return spent;
    }

    private static Spend spendIn(ResultSet result) throws SQLException {
        return new Spend(
                result.getLong("sent"),
                result.getLong("came_back"),
                result.getBoolean("came_back_unknown"),
                result.getLong("measured_here_calls"),
                result.getLong("total_calls"));
    }

    private static InForce inForceNow(JdbcClient database, RunId run) {
        return database.sql(IN_FORCE_NOW)
                .param("run", run.value())
                .query((result, number) -> new InForce(ceilingIn(result), result.getBoolean("raise_needs_approval")))
                .single();
    }

    private static @Nullable Ceiling ceilingIn(ResultSet result) throws SQLException {
        long held = result.getLong("ceiling");
        return result.wasNull() ? null : new Ceiling(held);
    }

    /** @param ceiling none where the run may spend without limit */
    record InForce(@Nullable Ceiling ceiling, boolean raiseNeedsApproval) {}

    enum Measurement {
        WHOLLY("wholly"),
        PARTLY("partly");

        private final String published;

        Measurement(String published) {
            this.published = published;
        }

        String published() {
            return published;
        }
    }

    /**
     * @param cameBackUnknown whether a call counted has come back saying nothing, or has not come back yet
     * @param measuredHereCalls how many counted calls this system measured, rather than their models
     * @param totalCalls how many calls are counted, whether measured here or by their models
     */
    record Spend(long sent, long cameBack, boolean cameBackUnknown, long measuredHereCalls, long totalCalls) {

        static final Spend NOTHING = new Spend(0, 0, false, 0, 0);

        Spend {
            if (measuredHereCalls < 0 || totalCalls < measuredHereCalls) {
                throw new IllegalArgumentException("Spend measured calls must be between none and all counted calls");
            }
        }

        @Nullable
        Measurement measuredHere() {
            return measuredHereCalls == 0
                    ? null
                    : measuredHereCalls == totalCalls ? Measurement.WHOLLY : Measurement.PARTLY;
        }

        long spent() {
            return Math.addExact(sent, cameBack);
        }

        Spend and(Spend other) {
            return new Spend(
                    Math.addExact(sent, other.sent),
                    Math.addExact(cameBack, other.cameBack),
                    cameBackUnknown || other.cameBackUnknown,
                    Math.addExact(measuredHereCalls, other.measuredHereCalls),
                    Math.addExact(totalCalls, other.totalCalls));
        }
    }
}
