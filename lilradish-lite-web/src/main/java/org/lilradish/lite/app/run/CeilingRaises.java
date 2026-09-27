package org.lilradish.lite.app.run;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.app.pool.PersonRows;
import org.lilradish.lite.domain.identity.UserId;
import org.lilradish.lite.domain.run.Ceiling;
import org.lilradish.lite.domain.run.CeilingChangeId;
import org.lilradish.lite.domain.run.RunId;
import org.springframework.jdbc.core.simple.JdbcClient;

/** A raise of a run's ceiling waiting on approval, which is not in force, as one reader stands to it. */
final class CeilingRaises {

    /** A change aliased {@code change} still waits: undecided, and no change of the run made after it. */
    static final String STILL_WAITING = """
            change.awaits_approval and change.outcome is null
            and not exists (select 1 from run_ceiling_changes later
                             where later.run_id = change.run_id and later.position > change.position)
            """;

    private static final String WAITING = """
            select change.run_ceiling_change_id, change.to_ceiling, change.created_at,
                   asker.subject_id, asker.user_id, asker.display_name
              from run_ceiling_changes change
              join subjects asker on asker.subject_id = change.created_by
             where change.run_id = :run and %s
            """.formatted(STILL_WAITING);

    private CeilingRaises() {}

    static Optional<Waiting> waiting(JdbcClient database, RunId run, UserId reader) {
        return database.sql(WAITING)
                .param("run", run.value())
                .query((result, number) -> waitingIn(result, reader))
                .optional();
    }

    private static Waiting waitingIn(ResultSet result, UserId reader) throws SQLException {
        PersonRows.Person asker = PersonRows.person(result);
        long held = result.getLong("to_ceiling");
        Ceiling to = result.wasNull() ? null : new Ceiling(held);
        return new Waiting(
                new CeilingChangeId(result.getObject("run_ceiling_change_id", UUID.class)),
                to,
                asker,
                result.getObject("created_at", OffsetDateTime.class).toInstant(),
                asker.userId().equals(reader));
    }

    /** @param to none where the raise takes the ceiling away */
    record Waiting(
            CeilingChangeId change,
            @Nullable Ceiling to,
            PersonRows.Person askedBy,
            Instant askedAt,
            boolean askedByReader) {}
}
