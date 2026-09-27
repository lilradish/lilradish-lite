package org.lilradish.lite.app.library;

/** Whether an entry is stopped, written once for every statement asking. */
final class EntryStops {

    private EntryStops() {}

    /** Whether the stop aliased {@code alias} is in force, never having been let go. */
    static String inForce(String alias) {
        return "%s.let_go_at is null".formatted(alias);
    }

    /** Whether the entry whose identifier is {@code entry}, a column or a parameter, is stopped now. */
    static String stopped(String entry) {
        return "exists (select 1 from entry_stops stop where stop.entry_id = %s and %s)"
                .formatted(entry, inForce("stop"));
    }
}
