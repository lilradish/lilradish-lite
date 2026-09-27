package org.lilradish.lite.app.run;

import static java.util.Objects.requireNonNull;

import java.sql.ResultSet;
import java.sql.SQLException;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.model.DeployedModel;
import org.lilradish.lite.domain.model.ModelCatalog;
import org.lilradish.lite.domain.model.ModelName;
import org.lilradish.lite.domain.run.ModelCallId;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The one place a run tree is found to have reached its ceiling, and stopped for it: what the whole tree spent
 * against the ceiling in force at its top, each call still out counted at the most it may yet come to.
 */
@Component
final class CeilingReach {

    // DB-SPECIFIC: a lateral join, count(…) filter, and enum and uuid casts are PostgreSQL's.
    /* One row per model called, or one counting nothing; a sum over bigint is numeric, hence the cast. */
    private static final String REACHED =
            """
            with in_force as (
                    select %s as ceiling
                      from runs run
                      %s
                     where run.run_id = :root)
            select in_force.ceiling, spent.model, spent.counted, spent.out
              from in_force
              left join lateral (
                    select call.model,
                           cast(coalesce(sum(call.sent_count), 0) + coalesce(sum(call.came_back_count), 0) as bigint)
                               as counted,
                           count(*) filter (where call.outcome is null) as out
                      from model_calls call
                     where in_force.ceiling is not null
                       and %s
                       and call.model_call_id is distinct from cast(:excluded as uuid)
                     group by call.model) spent on true
            """.formatted(RunBudget.CEILING_IN_FORCE, RunBudget.IN_FORCE_ON_RUN, RunBudget.SPENT_IN_TREE);

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
     * Whether what the tree held has spent, {@code excluded} counting for nothing, reaches the ceiling in force at
     * its top; where it does, the tree is stopped by that ceiling before this returns. None in force reaches nothing.
     */
    boolean stopsAt(LockedTree tree, @Nullable ModelCallId excluded) {
        requireNonNull(tree, "CeilingReach tree must not be null").requireHeld();
        Tally tally = new Tally();
        database.sql(REACHED)
                .param("root", tree.root().value())
                .param("excluded", excluded == null ? null : excluded.value())
                .query(tally);
        if (!tally.reached()) {
            return false;
        }
        writes.stoppedAtCeiling(tree);
        return true;
    }

    // Conservative estimate: a model no longer held counts the largest came-back limit held.
    private long cameBackLimit(String model) {
        return catalog.find(new ModelName(model))
                .map(DeployedModel::cameBackPerCallLimit)
                .orElse(largestHeld);
    }

    /** One check's rows added up: each call out at what it sent and the most its model may give back. */
    private final class Tally implements RowCallbackHandler {

        private @Nullable Long ceiling;

        private long spent;

        @Override
        public void processRow(ResultSet row) throws SQLException {
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
