package com.lilradish.lite.app.run;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.identity.GroupId;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Allocates run numbers within a group under one transaction-level lock. */
public final class RunNumbering {

    private static final int NUMBERING = 1;

    // DB-SPECIFIC: PostgreSQL advisory locks, hashtext() and the text type.
    private static final String NUMBERED = "select pg_advisory_xact_lock(:numbering, hashtext(cast(:group as text)))";

    private RunNumbering() {}

    /** The lock is a separate statement so the next-number read takes a snapshot after it is granted. */
    public static void hold(JdbcClient database, GroupId group) {
        requireNonNull(database, "RunNumbering database must not be null");
        requireNonNull(group, "RunNumbering group must not be null");
        database.sql(NUMBERED)
                .param("numbering", NUMBERING)
                .param("group", group.value())
                .query()
                .listOfRows();
    }

    public static String next(String group) {
        return "(select coalesce(max(taken.number), 0) + 1 from runs taken where taken.group_id = %s)"
                .formatted(requireNonNull(group, "RunNumbering group expression must not be null"));
    }
}
