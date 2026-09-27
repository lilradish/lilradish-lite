package org.lilradish.lite.app.pool;

import static java.util.Objects.requireNonNull;

import org.lilradish.lite.domain.identity.SubjectId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Somebody's current stay in the pool, held for a change made outside this package that has to find
 * them still in it when it lands.
 */
@Component
public final class PoolStays {

    // DB-SPECIFIC: for share is PostgreSQL's spelling.
    private static final String CURRENT_STAY_SHARED = """
            select 1
              from pool_members stay
             where stay.subject_id = :person and stay.removed_at is null
               for share
            """;

    private final JdbcClient database;

    PoolStays(JdbcClient database) {
        this.database = database;
    }

    /**
     * Whether they are in the pool now, their stay held until the caller's transaction ends: two holders
     * share it, and taking them out of the pool waits on both.
     */
    public boolean holdCurrentStay(SubjectId person) {
        requireNonNull(person, "PoolStays person must not be null");
        return database.sql(CURRENT_STAY_SHARED)
                .param("person", person.value())
                .query(Integer.class)
                .optional()
                .isPresent();
    }
}
