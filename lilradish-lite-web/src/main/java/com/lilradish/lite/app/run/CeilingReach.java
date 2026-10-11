package com.lilradish.lite.app.run;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.model.DeployedModel;
import com.lilradish.lite.domain.model.ModelCatalog;
import com.lilradish.lite.domain.model.ModelName;
import com.lilradish.lite.domain.run.ModelCallId;
import com.lilradish.lite.domain.run.RunId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The one place a run tree is found to have reached a ceiling, and stopped for it: each ceiling judged against what
 * its run and every run beneath it spent, each call still out counted at the most it may yet come to, and every
 * ceiling reached at once named by the one stop.
 */
@Component
final class CeilingReach {

    /** A run aliased run over {@link RunBudget#IN_FORCE_ON_RUN}: the top always, one beneath only by its version. */
    private static final String HELD_TO_A_CEILING = "(run.parent_run_id is null or version.keeps_own_ceiling)";

    // DB-SPECIFIC: the SQL constants below use PostgreSQL MATERIALIZED WITH, the uuid target type, and native
    // run_ceiling_change_outcome and model_call_outcome enum-label comparisons through RunBudget's fragments.
    /* Depth falls by one up every parent, which a key holds, so the walk up ends at the top. */
    private static final String UP_FROM_THE_RUN =
            """
            with recursive chain (run_id) as (
                    select run.run_id from runs run where run.run_id = :run and run.root_run_id = :root
                union all
                    select above.parent_run_id
                      from runs above join chain on above.run_id = chain.run_id
                     where above.parent_run_id is not null
            ),
            held as (
                    select run.run_id, run.parent_run_id is null as at_top, run.depth, %s as ceiling
                      from chain
                      join runs run on run.run_id = chain.run_id
                      %s
                     where %s
            ),
            """.formatted(RunBudget.CEILING_IN_FORCE, RunBudget.IN_FORCE_ON_RUN, HELD_TO_A_CEILING);

    private static final String THE_RUN_ALONE =
            """
            with recursive held as (
                    select run.run_id, run.parent_run_id is null as at_top, run.depth, %s as ceiling
                      from runs run
                      %s
                     where run.run_id = :run and run.root_run_id = :root and %s
            ),
            """.formatted(RunBudget.CEILING_IN_FORCE, RunBudget.IN_FORCE_ON_RUN, HELD_TO_A_CEILING);

    /*
     * One row per ceiling and model called, or one counting nothing; sums over bigint are numeric, hence the casts.
     * Added up once by run, materialized, or the planner would add the tree's calls up again for every ceiling.
     */
    private static final String SPENT_BENEATH_EACH = """
            beneath (holder, run_id) as (
                    select held.run_id, held.run_id from held where held.ceiling is not null and not held.at_top
                union all
                    select beneath.holder, below.run_id from runs below join beneath on below.parent_run_id = beneath.run_id
            ),
            by_run as materialized (
                    select call.run_id, call.model,
                           coalesce(sum(call.sent_count), 0) + coalesce(sum(call.came_back_count), 0) as counted,
                           count(*) filter (where call.outcome is null) as out
                      from model_calls call
                     where %s
                       and call.model_call_id is distinct from cast(:excluded as uuid)
                       and exists (select 1 from held where held.ceiling is not null)
                     group by call.run_id, call.model
            )
            select held.run_id, held.ceiling, spent.model, spent.counted, spent.out
              from held
              left join lateral (
                    select by_run.model, cast(sum(by_run.counted) as bigint) as counted,
                           cast(sum(by_run.out) as bigint) as out
                      from by_run
                     where held.ceiling is not null
                       and (held.at_top
                            or by_run.run_id in (select beneath.run_id from beneath where beneath.holder = held.run_id))
                     group by by_run.model) spent on true
             order by held.depth
            """.formatted(RunBudget.SPENT_IN_TREE);

    private static final String REACHED_FROM_THE_RUN = UP_FROM_THE_RUN + SPENT_BENEATH_EACH;

    private static final String REACHED_ON_THE_RUN = THE_RUN_ALONE + SPENT_BENEATH_EACH;

    private final JdbcClient database;

    private final ModelCatalog catalog;

    private final EngineWrites writes;

    private final long largestHeld;

    CeilingReach(JdbcClient database, ModelCatalog catalog, EngineWrites writes) {
        this.database = database;
        this.catalog = catalog;
        this.writes = writes;
        this.largestHeld = catalog.all().stream()
                .mapToLong(DeployedModel::cameBackPerCallLimit)
                .max()
                .orElseThrow();
    }

    /**
     * Whether a call from {@code calling} would go past a ceiling: the top's, and that of each run above it, itself
     * included, whose version keeps its own, judged on what was spent with {@code excluded} counting for nothing.
     * Where any is reached the tree is stopped, naming every one reached, before this returns.
     */
    boolean stopsAt(LockedTree tree, RunId calling, @Nullable ModelCallId excluded) {
        requireNonNull(tree, "CeilingReach tree must not be null").requireHeld();
        requireNonNull(calling, "CeilingReach calling must not be null");
        return stoppedAt(tree, REACHED_FROM_THE_RUN, calling, tree.root(), excluded);
    }

    /**
     * Whether what {@code run} and every run beneath it spent has reached the ceiling now in force on it, and that
     * alone; where it has, the tree is stopped for it before this returns. The run is the top, or keeps its own.
     */
    boolean stopsAtCeilingOf(LockedTree tree, RunId run) {
        requireNonNull(tree, "CeilingReach tree must not be null").requireHeld();
        requireNonNull(run, "CeilingReach run must not be null");
        return stoppedAt(tree, REACHED_ON_THE_RUN, run, run, null);
    }

    private boolean stoppedAt(
            LockedTree tree, String reachedSql, RunId run, RunId judged, @Nullable ModelCallId excluded) {
        Map<RunId, Tally> held = new LinkedHashMap<>();
        database.sql(reachedSql)
                .param("root", tree.root().value())
                .param("run", run.value())
                .param("excluded", excluded == null ? null : excluded.value())
                .query(row -> {
                    RunId holder = new RunId(row.getObject("run_id", UUID.class));
                    held.computeIfAbsent(holder, ignored -> new Tally()).add(row);
                });
        // An absent run must fail; otherwise it would send without a ceiling check.
        if (!held.containsKey(judged)) {
            throw new IllegalStateException("Tree " + tree.root().value() + " holds no ceiling of run " + judged.value()
                    + " to judge run " + run.value() + " by");
        }
        List<RunId> reached = held.entrySet().stream()
                .filter(each -> each.getValue().reached())
                .map(Map.Entry::getKey)
                .toList();
        if (reached.isEmpty()) {
            return false;
        }
        writes.stoppedAtCeilings(tree, reached);
        return true;
    }

    // Conservative estimate: a model absent from the catalog counts the largest came-back limit held.
    private long cameBackLimit(String model) {
        return catalog.find(new ModelName(model))
                .map(DeployedModel::cameBackPerCallLimit)
                .orElse(largestHeld);
    }

    /** Each call still out counts at what it sent and the most its model may give back. */
    private final class Tally {

        private @Nullable Long ceiling;

        private long spent;

        void add(ResultSet row) throws SQLException {
            long inForce = row.getLong("ceiling");
            if (row.wasNull()) {
                return;
            }
            ceiling = inForce;
            String model = row.getString("model");
            if (model == null) {
                return;
            }
            long out = Math.multiplyExact(row.getLong("out"), cameBackLimit(model));
            spent = Math.addExact(spent, Math.addExact(row.getLong("counted"), out));
        }

        boolean reached() {
            return ceiling != null && spent >= ceiling;
        }
    }
}
