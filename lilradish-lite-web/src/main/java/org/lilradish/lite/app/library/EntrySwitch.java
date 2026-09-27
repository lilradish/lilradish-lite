package org.lilradish.lite.app.library;

import static java.util.Objects.requireNonNull;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.identity.SubjectId;
import org.lilradish.lite.domain.registry.EntryId;
import org.lilradish.lite.domain.run.StopRecord;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Stopping an entry holds it for no key update before writing; a run's start holds it for share, then reads
 * whether it is stopped. Each waits for the other, so neither acts on what the other is changing.
 */
public final class EntrySwitch {

    // DB-SPECIFIC: for no key update and for share are PostgreSQL's.
    /* No key update: the key share writing a stop alone takes passes a start's share, which this waits for. */
    static final String HELD_TO_SWITCH =
            "select 1 from entries entry where %s for no key update".formatted(LibraryScope.ENTRY_IN_SCOPE);

    private static final String HELD_TO_START =
            "select 1 from entries entry where entry.entry_id = :entry and entry.group_id = :group for share";

    private static final String STOPPED = "select " + EntryStops.stopped(":entry");

    // DB-SPECIFIC: for share, array casts and any(…) are PostgreSQL's.
    /* In key order, so two transactions holding several never wait on each other the other way round. */
    private static final String HELD_TO_RUN = """
            select entry.entry_id from entries entry
             where entry.entry_id = any(cast(:entries as uuid[])) and entry.group_id = :group
             order by entry.entry_id
               for share
            """;

    private static final String STOPS = """
            select stop.entry_id, stop.created_by, stop.created_at from entry_stops stop
             where stop.entry_id = any(cast(:entries as uuid[])) and %s
            """.formatted(EntryStops.inForce("stop"));

    private EntrySwitch() {}

    /**
     * Inside a run tree's read-committed transaction, its root held first: each entry held until it ends, then
     * the stop in force on each that is stopped, read in a later statement as {@link #stoppedOnceHeld} reads.
     */
    public static Map<EntryId, StopRecord> stopsOnceHeld(
            JdbcClient database, GroupId group, Collection<EntryId> entries) {
        requireNonNull(group, "EntrySwitch group must not be null");
        requireReadCommitted();
        int held = database.sql(HELD_TO_RUN)
                .param("entries", spelled(entries))
                .param("group", group.value())
                .query(UUID.class)
                .list()
                .size();
        if (held != Set.copyOf(entries).size()) {
            throw new IllegalStateException("Group " + group.value() + " holds no entry among " + entries);
        }
        return stops(database, entries);
    }

    /** The stop in force on each of {@code entries} that is stopped, as the caller's transaction reads it. */
    public static Map<EntryId, StopRecord> stops(JdbcClient database, Collection<EntryId> entries) {
        Map<EntryId, StopRecord> stopped = new HashMap<>();
        database.sql(STOPS).param("entries", spelled(entries)).query(result -> {
            stopped.put(
                    new EntryId(result.getObject("entry_id", UUID.class)),
                    new StopRecord(
                            new SubjectId(result.getObject("created_by", UUID.class)),
                            result.getObject("created_at", OffsetDateTime.class).toInstant()));
        });
        return stopped;
    }

    private static String[] spelled(Collection<EntryId> entries) {
        return entries.stream().map(entry -> entry.value().toString()).toArray(String[]::new);
    }

    private static void requireReadCommitted() {
        Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || isolation == null
                || isolation != TransactionDefinition.ISOLATION_READ_COMMITTED) {
            throw new IllegalStateException("EntrySwitch was asked outside a read-committed transaction");
        }
    }

    /**
     * First in a run's start, inside its read-committed transaction: the entry held until it ends, then
     * whether it is stopped, read in a later statement so a stop that landed while this waited is seen.
     */
    public static boolean stoppedOnceHeld(JdbcClient database, GroupId group, EntryId entry) {
        requireNonNull(group, "EntrySwitch group must not be null");
        requireNonNull(entry, "EntrySwitch entry must not be null");
        requireReadCommitted();
        if (database.sql(HELD_TO_START)
                .param("entry", entry.value())
                .param("group", group.value())
                .query(Integer.class)
                .optional()
                .isEmpty()) {
            throw new IllegalStateException("Group " + group.value() + " holds no entry " + entry.value());
        }
        return database.sql(STOPPED)
                .param("entry", entry.value())
                .query(Boolean.class)
                .single();
    }
}
