package org.lilradish.lite.app.library;

import static java.util.Objects.requireNonNull;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.app.group.GroupReach;
import org.lilradish.lite.app.group.GroupRoles;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.identity.UserId;
import org.lilradish.lite.domain.referencelist.ListNote;
import org.lilradish.lite.domain.referencelist.Term;
import org.lilradish.lite.domain.referencelist.TermMeaning;
import org.lilradish.lite.domain.registry.EntryId;
import org.lilradish.lite.domain.registry.EntryKind;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One version of a reference list read as one moment, only within its group: its note and its terms in the
 * order it gives them. A stored value its type refuses fails the whole read rather than being shown.
 */
@Component
final class ReferenceLists {

    private static final String IN_VIEW = """
            select version.revision
              from entry_versions version
              join entries entry on entry.entry_id = version.entry_id
             where version.entry_version_id = :version and %s
            """.formatted(LibraryScope.ENTRY_IN_SCOPE);

    private static final String REVISION = "select revision from entry_versions where entry_version_id = :version";

    private static final String NOTE =
            "select list.note from reference_list_versions list where list.entry_version_id = :version";

    private static final String TERMS = """
            select term.reference_list_term_id, term.term, term.meaning, %s as alike_earlier
              from reference_list_terms term
             where term.entry_version_id = :version
             order by term.position
            """.formatted(ReferenceListContentCheck.ALIKE_EARLIER);

    private final JdbcClient database;

    private final GroupRoles roles;

    private final TransactionTemplate snapshot;

    ReferenceLists(JdbcClient database, GroupRoles roles, PlatformTransactionManager transactionManager) {
        this.database = database;
        this.roles = roles;
        this.snapshot = Snapshots.readOnly(transactionManager);
    }

    /** Somebody holding nothing in the group is refused as the group is, and a version not in view alike. */
    ListView read(GroupId group, EntryId entry, EntryVersionId version, UserId caller) {
        ListView read = snapshot.execute(status -> {
            GroupReach.requireMember(roles.heldBy(caller, group));
            int revision = LibraryScope.scoped(database.sql(IN_VIEW), group, EntryKind.REFERENCE_LIST, entry)
                    .param("version", version.value())
                    .query(Integer.class)
                    .optional()
                    .orElseThrow(LibraryRefusal.VERSION_NOT_IN_VIEW::raised);
            return viewIn(version, revision);
        });
        return requireNonNull(read);
    }

    /** Inside the caller's transaction, of a reference list version of the group's known to be in view. */
    ListView viewIn(EntryVersionId version) {
        int revision = database.sql(REVISION)
                .param("version", version.value())
                .query(Integer.class)
                .single();
        return viewIn(version, revision);
    }

    private ListView viewIn(EntryVersionId version, int revision) {
        List<Optional<String>> noted = database.sql(NOTE)
                .param("version", version.value())
                .query((result, number) -> Optional.ofNullable(result.getString("note")))
                .list();
        if (noted.isEmpty()) {
            throw new IllegalStateException(
                    "Reference list version " + version.value() + " holds no reference list content");
        }
        try {
            List<HeldTerm> terms = database.sql(TERMS)
                    .param("version", version.value())
                    .query((result, number) -> heldTerm(result))
                    .list();
            return new ListView(revision, noted.getFirst().map(ListNote::new).orElse(null), terms);
        } catch (IllegalArgumentException refused) {
            throw new IllegalStateException(
                    "Reference list version " + version.value() + " holds words this system will not show", refused);
        }
    }

    private static HeldTerm heldTerm(ResultSet result) throws SQLException {
        return new HeldTerm(
                result.getObject("reference_list_term_id", UUID.class),
                new Term(result.getString("term")),
                new TermMeaning(result.getString("meaning")),
                result.getBoolean("alike_earlier"));
    }

    /**
     * @param revision what a write to it names as the one it was read at
     * @param note none where the list says nothing on choosing among its terms
     * @param terms in the order the version gives them
     */
    record ListView(int revision, @Nullable ListNote note, List<HeldTerm> terms) {}

    /**
     * @param termId the stored term's own key, which it keeps until it is removed
     * @param alikeEarlier whether a term before it is this one as submitting compares them
     */
    record HeldTerm(UUID termId, Term term, TermMeaning meaning, boolean alikeEarlier) {}
}
