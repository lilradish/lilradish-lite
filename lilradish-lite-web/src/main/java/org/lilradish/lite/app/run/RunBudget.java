package org.lilradish.lite.app.run;

import static java.util.Objects.requireNonNull;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.run.Ceiling;
import org.lilradish.lite.domain.run.ProductionId;
import org.lilradish.lite.domain.run.RunId;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * What a run has spent and the most it may, both worked out as they are read and neither stored. What is
 * decided on the ceiling in force asks for the tree held; a reading asks for nothing.
 */
final class RunBudget {

    // DB-SPECIFIC: a lateral join, recursive with and count(…) filter are PostgreSQL's.
    /** A change aliased {@code change} holds where it was made in force, a raise only once approved. */
    private static final String IN_FORCE = "(not change.awaits_approval or change.outcome = 'approved')";

    /*
     * Joined to a run aliased run: its version as version, and as latest the last change made in force. A raise
     * waiting is withdrawn by any change after it, so none approved is older.
     */
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
     * Every call but one turned away, one still out included, at what it sent and whatever came back. A call the
     * model did not count, one that never came back among them, is counted at what this system measured.
     */
    private static final String COUNTS = """
            coalesce(sum(call.sent_count), 0) as sent,
                   coalesce(sum(call.came_back_count), 0) as came_back,
                   count(*) filter (where call.came_back_count is null) > 0 as came_back_unknown,
                   count(*) filter (where not call.counted_by_model) > 0 as measured_here""";

    private static final String COUNTED = """
            select %s
              from model_calls call
            """.formatted(COUNTS);

    /* A call to produce or to review names the try it is about; one to help names none, and is the run's. */
    private static final String SPENT_BY_TRY = """
            select call.production_id, %s
              from model_calls call
             where call.run_id = :run and call.production_id is not null
               and call.outcome is distinct from 'turned_away'
             group by call.production_id
            """.formatted(COUNTS);

    /**
     * A call aliased {@code call} counted as spent by the tree whose root {@code :root} names: it carries its tree's
     * root, so a whole tree is read by that alone, and one turned away every time was never taken up.
     */
    static final String SPENT_IN_TREE = "call.root_run_id = :root and call.outcome is distinct from 'turned_away'";

    private static final String SPENT_BY_TREE = COUNTED + " where " + SPENT_IN_TREE;

    /* Depth grows by one down every parent, which a key holds, so the walk down never comes back up. */
    private static final String SPENT_BENEATH = """
            with recursive tree (run_id) as (
                    select run.run_id from runs run where run.run_id = :run
                union all
                    select beneath.run_id from runs beneath join tree on beneath.parent_run_id = tree.run_id
            )
            %s
             where call.run_id in (select tree.run_id from tree) and call.outcome is distinct from 'turned_away'
            """.formatted(COUNTED);

    private RunBudget() {}

    /** The ceiling in force on a run of the tree held. */
    static InForce inForce(JdbcClient database, LockedTree tree, RunId run) {
        requireNonNull(tree, "RunBudget tree must not be null").requireHeld();
        return inForceNow(database, run);
    }

    /** The same as one reading of the store sees it, to be shown and never decided on. */
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
                result.getBoolean("measured_here"));
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

    /**
     * @param cameBackUnknown whether a call counted has come back saying nothing, or has not come back yet
     * @param measuredHere whether any of it is what this system measured, the model having counted none of that call
     */
    record Spend(long sent, long cameBack, boolean cameBackUnknown, boolean measuredHere) {

        static final Spend NOTHING = new Spend(0, 0, false, false);

        long spent() {
            return Math.addExact(sent, cameBack);
        }

        Spend and(Spend other) {
            return new Spend(
                    Math.addExact(sent, other.sent),
                    Math.addExact(cameBack, other.cameBack),
                    cameBackUnknown || other.cameBackUnknown,
                    measuredHere || other.measuredHere);
        }
    }
}
