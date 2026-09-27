package org.lilradish.lite.app.library;

import org.lilradish.lite.app.group.GroupRoles;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.identity.GroupPermission;
import org.lilradish.lite.domain.identity.UserId;
import org.lilradish.lite.domain.registry.EntryId;
import org.lilradish.lite.domain.registry.EntryKind;
import org.lilradish.lite.domain.wire.StoreLabels;
import org.springframework.jdbc.core.simple.JdbcClient;

/** What an entry is found within, written once: its group, its kind, and nothing another group holds. */
final class LibraryScope {

    // DB-SPECIFIC: enum casts are PostgreSQL's.
    /** An entry aliased {@code entry}, bound as {@code :entry}, {@code :group} and {@code :kind}. */
    static final String ENTRY_IN_SCOPE =
            "entry.entry_id = :entry and entry.group_id = :group and entry.kind = cast(:kind as entry_kind)";

    private static final String ENTRY_IN_VIEW = "select 1 from entries entry where " + ENTRY_IN_SCOPE;

    private LibraryScope() {}

    static JdbcClient.StatementSpec scoped(
            JdbcClient.StatementSpec statement, GroupId group, EntryKind kind, EntryId entry) {
        return statement
                .param("entry", entry.value())
                .param("group", group.value())
                .param("kind", StoreLabels.label(kind));
    }

    /** First in every change: what the caller holds in the group cannot move again until the change ends. */
    static void requireStillReached(GroupRoles roles, UserId caller, GroupId group, GroupPermission permission) {
        roles.stillReaching(caller, group, permission);
    }

    static void requireEntryInView(JdbcClient database, GroupId group, EntryKind kind, EntryId entry) {
        if (scoped(database.sql(ENTRY_IN_VIEW), group, kind, entry)
                .query(Integer.class)
                .optional()
                .isEmpty()) {
            throw LibraryRefusal.ENTRY_NOT_IN_VIEW.raised();
        }
    }
}
