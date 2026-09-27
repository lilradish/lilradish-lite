package org.lilradish.lite.app.measurements;

import static java.util.Objects.requireNonNull;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.model.ModelMode;
import org.lilradish.lite.domain.model.ModelName;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What every model has done and where calls to them failed, counted over everything ever recorded and
 * worked out afresh on every read. It narrows nothing to the caller, so the act the handler asks is its
 * only guard and nothing outside this package reaches it.
 *
 * <p>A model is named as its calls named it, whether or not the deployment still holds it, so a model
 * taken away keeps its rows. Both tables are read as one moment of the store, so a call made between
 * the two reads is counted in both or in neither.
 *
 * <p>A production counts once however many of its values were refused, and a refusal for length counts
 * nowhere. A review that went wrong, never came back or was never sent is not one the model made.
 *
 * <p>Model names order by code point; under each, the model run as it is comes first and its modes follow
 * by code point. A stored model name or mode its type refuses fails the whole read rather than being shown
 * or left out, for the reason the pool gives.
 */
@Component
final class Measurements {

    // DB-SPECIFIC: count(…) filter (where …), exists(…) in a filter and the collation named are PostgreSQL's.
    private static final String MODELS = """
            select call.model,
                   call.mode,
                   count(*) filter (where production.yielded) as productions,
                   count(*) filter (where production.yielded
                                      and exists (select 1
                                                    from review_decisions refusal
                                                   where refusal.production_id = production.production_id
                                                     and refusal.outcome = 'refused'
                                                     and not refusal.for_length)) as refused_on_review,
                   count(*) filter (where review.decided and not review.for_length) as reviews,
                   count(*) filter (where review.decided and not review.for_length
                                      and exists (select 1
                                                    from review_decisions refusal
                                                   where refusal.review_id = review.review_id
                                                     and refusal.outcome = 'refused'
                                                     and not refusal.for_length)) as refusing,
                   count(*) filter (where production.lost_reason = 'did_not_fit'
                                       or review.lost_reason = 'did_not_fit') as did_not_fit
              from model_calls call
              left join productions production
                     on production.model_call_id = call.model_call_id
                    and call.purpose = 'produce'
                    and production.producer = 'model'
                    and production.model_call_outcome = 'came_back'
              left join reviews review
                     on review.model_call_id = call.model_call_id
                    and call.purpose = 'review'
                    and review.model_call_outcome = 'came_back'
             group by call.model, call.mode
             order by call.model collate ucs_basic, call.mode <> :asItIs, call.mode collate ucs_basic
            """;

    private static final String SYSTEM = """
            select called.model, called.went_wrong, called.never_came_back, coalesce(turned.times, 0) as turned_away
              from (select call.model,
                           count(*) filter (where call.outcome = 'errored') as went_wrong,
                           count(*) filter (where call.outcome = 'nothing_came_back') as never_came_back
                      from model_calls call
                     group by call.model) called
              left join (select call.model, count(*) as times
                           from model_call_turnaways turnaway
                           join model_calls call on call.model_call_id = turnaway.model_call_id
                          group by call.model) turned
                     on turned.model = called.model
             order by called.model collate ucs_basic
            """;

    private final JdbcClient database;

    private final TransactionTemplate snapshot;

    Measurements(JdbcClient database, PlatformTransactionManager transactionManager) {
        this.database = database;
        TransactionTemplate snapshot = new TransactionTemplate(transactionManager);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setReadOnly(true);
        this.snapshot = snapshot;
    }

    Measured read() {
        Measured measured = snapshot.execute(status -> new Measured(
                database.sql(MODELS)
                        .param("asItIs", ModelMode.RESERVED_FOR_AS_IT_IS)
                        .query((result, number) -> modelFigures(result))
                        .list(),
                database.sql(SYSTEM)
                        .query((result, number) -> systemFigures(result))
                        .list()));
        return requireNonNull(measured);
    }

    private static ModelFigures modelFigures(ResultSet result) throws SQLException {
        ModelName model;
        @Nullable ModelMode mode;
        try {
            model = new ModelName(result.getString("model"));
            mode = modeOf(result.getString("mode"));
        } catch (IllegalArgumentException refused) {
            throw unshown(refused);
        }
        return new ModelFigures(
                model,
                mode,
                result.getLong("productions"),
                result.getLong("refused_on_review"),
                result.getLong("reviews"),
                result.getLong("refusing"),
                result.getLong("did_not_fit"));
    }

    private static SystemFigures systemFigures(ResultSet result) throws SQLException {
        return new SystemFigures(
                modelOf(result),
                result.getLong("went_wrong"),
                result.getLong("never_came_back"),
                result.getLong("turned_away"));
    }

    private static ModelName modelOf(ResultSet result) throws SQLException {
        try {
            return new ModelName(result.getString("model"));
        } catch (IllegalArgumentException refused) {
            throw unshown(refused);
        }
    }

    private static @Nullable ModelMode modeOf(String stored) {
        // Read before constructing, which refuses the word.
        if (stored.equals(ModelMode.RESERVED_FOR_AS_IT_IS)) {
            return null;
        }
        return new ModelMode(stored);
    }

    private static IllegalStateException unshown(IllegalArgumentException refused) {
        return new IllegalStateException("A call names a model or a mode this system will not show", refused);
    }

    record Measured(List<ModelFigures> models, List<SystemFigures> system) {}

    /**
     * One model in one mode, or run as it is.
     *
     * @param mode none where the model was run as it is
     * @param productions productions it made that were taken
     * @param refusedOnReview of those, how many had a value refused on review
     * @param reviews reviews it made that decided something
     * @param refusing of those, how many refused a value
     * @param didNotFit answers it gave, producing or reviewing, that did not fit what was declared
     */
    record ModelFigures(
            ModelName model,
            @Nullable ModelMode mode,
            long productions,
            long refusedOnReview,
            long reviews,
            long refusing,
            long didNotFit) {}

    /**
     * One model, whatever mode it was asked in.
     *
     * @param wentWrong calls that went wrong or were not answered in time
     * @param neverCameBack calls nothing came back for
     * @param turnedAway every time a call to it was turned away
     */
    record SystemFigures(ModelName model, long wentWrong, long neverCameBack, long turnedAway) {}
}
