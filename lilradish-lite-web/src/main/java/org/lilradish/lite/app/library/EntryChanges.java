package org.lilradish.lite.app.library;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.app.change.Author;
import org.lilradish.lite.app.change.ChangeTransactions;
import org.lilradish.lite.app.group.GroupRoles;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.identity.GroupPermission;
import org.lilradish.lite.domain.identity.UserId;
import org.lilradish.lite.domain.registry.EntryAct;
import org.lilradish.lite.domain.registry.EntryId;
import org.lilradish.lite.domain.registry.EntryKind;
import org.lilradish.lite.domain.registry.EntryName;
import org.lilradish.lite.domain.registry.EntryPurpose;
import org.lilradish.lite.domain.wire.StoreLabels;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Starting an entry, renaming it and saying what it is for, stopping it being used and letting it go again,
 * each in {@link ChangeTransactions}' transaction as the caller's act. None touches a version.
 */
@Component
final class EntryChanges {

    // DB-SPECIFIC: uuidv7(), now(), greatest, returning, for update, for no key update, on conflict naming an
    // index predicate and enum casts are PostgreSQL's.
    private static final String AUTHOR = Author.OF_CALLER;

    /* Waits on an entry holding the name that is not yet committed, and is refused if it lands. */
    private static final String START = """
            insert into entries (entry_id, group_id, kind, name, purpose, created_by)
            values (uuidv7(), :group, cast(:kind as entry_kind), :name, :purpose, %s)
            returning entry_id
            """.formatted(AUTHOR);

    /* For update, which the write takes anyway, a uniquely indexed column changing; no lock is raised midway. */
    private static final String HELD_NOW = "select entry.name, entry.purpose from entries entry where %s for update"
            .formatted(LibraryScope.ENTRY_IN_SCOPE);

    /* now() is when this transaction began, which can be before a row it waited on the lock for was made. */
    private static final String RENAME = """
            update entries
               set name = :name, purpose = :purpose, updated_at = greatest(now(), created_at), updated_by = %s
             where entry_id = :entry
            """.formatted(AUTHOR);

    /* A stop already in force is the one that stands, and nothing is recorded beside it. The predicate is spelt
     * as the partial index spells it, unqualified, so the conflict is inferred to that index: not EntryStops'. */
    private static final String STOP = """
            insert into entry_stops (entry_id, created_by) values (:entry, %s)
            on conflict (entry_id) where let_go_at is null do nothing
            """.formatted(AUTHOR);

    private static final String LET_GO = """
            update entry_stops stop set let_go_at = greatest(now(), stop.created_at), let_go_by = %s
             where stop.entry_id = :entry and %s
            """.formatted(AUTHOR, EntryStops.inForce("stop"));

    private final JdbcClient database;

    private final TransactionOperations transactions;

    private final GroupRoles roles;

    private final EntryLetGo letGo;

    EntryChanges(JdbcClient database, TransactionOperations transactions, GroupRoles roles, EntryLetGo letGo) {
        this.database = database;
        this.transactions = transactions;
        this.roles = roles;
        this.letGo = letGo;
    }

    /** An entry owned by the group, and its first version, a draft. */
    EntryId start(GroupId group, EntryKind kind, EntryName name, @Nullable EntryPurpose purpose, UserId caller) {
        return transactions.execute(status -> {
            LibraryScope.requireStillReached(roles, caller, group, GroupPermission.AUTHOR_ENTRY);
            UUID started;
            try {
                started = database.sql(START)
                        .param("group", group.value())
                        .param("kind", StoreLabels.label(kind))
                        .param("name", name.value())
                        .param("purpose", purpose == null ? null : purpose.value())
                        .param("caller", caller.value())
                        .query((result, number) -> result.getObject("entry_id", UUID.class))
                        .single();
            } catch (DuplicateKeyException taken) {
                throw LibraryRefusal.ENTRY_NAME_TAKEN.raised(taken);
            }
            EntryId entry = new EntryId(started);
            VersionChanges.draftStarted(database, kind, entry, caller);
            return entry;
        });
    }

    /**
     * The name and what it is for, as one act; saying nothing is holding no purpose. Both as given already,
     * nothing is recorded.
     */
    void rename(
            GroupId group,
            EntryKind kind,
            EntryId entry,
            EntryName name,
            @Nullable EntryPurpose purpose,
            UserId caller) {
        transactions.executeWithoutResult(status -> {
            LibraryScope.requireStillReached(roles, caller, group, EntryAct.RENAME.permission());
            Held now = LibraryScope.scoped(database.sql(HELD_NOW), group, kind, entry)
                    .query((result, number) -> new Held(result.getString("name"), result.getString("purpose")))
                    .optional()
                    .orElseThrow(LibraryRefusal.ENTRY_NOT_IN_VIEW::raised);
            String described = purpose == null ? null : purpose.value();
            if (name.value().equals(now.name()) && Objects.equals(described, now.purpose())) {
                return;
            }
            try {
                database.sql(RENAME)
                        .param("name", name.value())
                        .param("purpose", described)
                        .param("caller", caller.value())
                        .param("entry", entry.value())
                        .update();
            } catch (DuplicateKeyException taken) {
                throw LibraryRefusal.ENTRY_NAME_TAKEN.raised(taken);
            }
        });
    }

    /** Takes effect at once, waiting on nobody's approval; stopped already, it stays as it was stopped. */
    void stop(GroupId group, EntryKind kind, EntryId entry, UserId caller) {
        switched(EntryAct.STOP, STOP, group, kind, entry, caller);
    }

    /** Not stopped, nothing is recorded; let go, what the stop held goes on once this has committed. */
    void letGo(GroupId group, EntryKind kind, EntryId entry, UserId caller) {
        switched(EntryAct.LET_GO, LET_GO, group, kind, entry, caller);
    }

    private void switched(EntryAct act, String change, GroupId group, EntryKind kind, EntryId entry, UserId caller) {
        transactions.executeWithoutResult(status -> {
            LibraryScope.requireStillReached(roles, caller, group, act.permission());
            if (LibraryScope.scoped(database.sql(EntrySwitch.HELD_TO_SWITCH), group, kind, entry)
                    .query(Integer.class)
                    .optional()
                    .isEmpty()) {
                throw LibraryRefusal.ENTRY_NOT_IN_VIEW.raised();
            }
            int switched = database.sql(change)
                    .param("entry", entry.value())
                    .param("caller", caller.value())
                    .update();
            if (act == EntryAct.LET_GO && switched > 0) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        letGo.goesOn(entry);
                    }
                });
            }
        });
    }

    /** An entry's name and purpose as it stands. */
    private record Held(String name, @Nullable String purpose) {}
}
