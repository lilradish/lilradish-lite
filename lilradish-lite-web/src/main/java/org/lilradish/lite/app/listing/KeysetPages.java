package org.lilradish.lite.app.listing;

import static java.util.Objects.requireNonNull;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.listing.ListColumn;
import org.lilradish.lite.domain.listing.ListPage;
import org.lilradish.lite.domain.listing.ListPosition;
import org.lilradish.lite.domain.listing.ListQuery;
import org.lilradish.lite.domain.listing.ListRow;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * One page of a list read out of the store, in one statement however many rows it holds. Nothing is
 * decided here that could be decided without a database, so nothing here goes untested for want of one.
 */
public final class KeysetPages {

    private KeysetPages() {}

    public static <C extends Enum<C> & ListColumn, R extends ListRow<C>> ListPage<R> read(
            JdbcClient database,
            KeysetStatements<C> statements,
            ListQuery<C> query,
            @Nullable ListPosition after,
            RowMapper<R> rows) {
        requireNonNull(database, "KeysetPages database must not be null");
        requireNonNull(statements, "KeysetPages statements must not be null");
        requireNonNull(query, "KeysetPages query must not be null");
        requireNonNull(rows, "KeysetPages row mapper must not be null");
        List<R> fetched = database.sql(statements.statement(query, after))
                .params(statements.parameters(query, after))
                .query(rows)
                .list();
        return statements.page(fetched, query);
    }
}
