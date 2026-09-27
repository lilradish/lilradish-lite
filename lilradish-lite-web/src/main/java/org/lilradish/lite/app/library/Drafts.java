package org.lilradish.lite.app.library;

import static java.util.Objects.requireNonNull;

import org.lilradish.lite.app.change.Author;
import org.lilradish.lite.app.group.GroupRoles;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.identity.UserId;
import org.lilradish.lite.domain.registry.EntryId;
import org.lilradish.lite.domain.registry.EntryKind;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.lilradish.lite.domain.registry.VersionAct;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The one way into a draft's content, which is why it and whatever writes a kind's content stay in this
 * package: the draft locked, the caller admitted to write it, its revision counted past the one the caller
 * read or the write refused before anything is touched, the caller recorded as one who wrote it, then its kind's.
 */
@Component
final class Drafts {

    /* Run only once the draft's lock is held, so two writes from one reading are never both counted. */
    private static final String REVISED = """
            update entry_versions
               set revision = revision + 1
             where entry_version_id = :version and revision = :seen
            """;

    /* The starter is an author already and never held twice; a caller with no subject fails the insert. */
    private static final String WRITER = """
            insert into entry_version_writers (entry_version_id, created_by)
            select version.entry_version_id, %1$s
              from entry_versions version
             where version.entry_version_id = :version and version.created_by is distinct from %1$s
            on conflict do nothing
            """.formatted(Author.OF_CALLER);

    /* Positions are unique only at commit, which no catch beside a statement sees; this brings it to one. */
    // DB-SPECIFIC: set constraints and on conflict are PostgreSQL's.
    private static final String IMMEDIATE = "set constraints all immediate";

    private final JdbcClient database;

    private final TransactionOperations transactions;

    private final GroupRoles roles;

    Drafts(JdbcClient database, TransactionOperations transactions, GroupRoles roles) {
        this.database = database;
        this.transactions = transactions;
        this.roles = roles;
    }

    <T> T write(
            GroupId group,
            EntryKind kind,
            EntryId entry,
            EntryVersionId version,
            UserId caller,
            int seenRevision,
            DraftContent<T> content) {
        requireNonNull(content, "Drafts content must not be null");
        if (seenRevision < 1) {
            throw new IllegalArgumentException("Drafts seen revision must be at least 1, but was " + seenRevision);
        }
        return transactions.execute(status -> {
            VersionChanges.requireAdmitted(database, roles, VersionAct.WRITE, group, kind, entry, version, caller);
            int revised = database.sql(REVISED)
                    .param("version", version.value())
                    .param("seen", seenRevision)
                    .update();
            if (revised != 1) {
                throw LibraryRefusal.DRAFT_WRITTEN_SINCE_READ.raised();
            }
            database.sql(WRITER)
                    .param("version", version.value())
                    .param("caller", caller.value())
                    .update();
            OpenDraft draft = new OpenDraft(database, group, version, kind);
            T written;
            try {
                written = content.write(draft);
            } finally {
                draft.close();
            }
            database.sql(IMMEDIATE).update();
            return written;
        });
    }
}
