package org.lilradish.lite.app.library;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.app.group.GroupReach;
import org.lilradish.lite.app.group.GroupRoles;
import org.lilradish.lite.domain.declaration.AskedField;
import org.lilradish.lite.domain.declaration.Asking;
import org.lilradish.lite.domain.declaration.Declaration;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.identity.UserId;
import org.lilradish.lite.domain.registry.EntryId;
import org.lilradish.lite.domain.registry.EntryKind;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One version of a question read as one moment, only within its group: what it holds, the lists it pins and
 * could pin, and what a model is told of what it gives back, where that could be told.
 */
@Component
final class Questions {

    private static final String IN_VIEW = """
            select version.revision
              from entry_versions version
              join entries entry on entry.entry_id = version.entry_id
             where version.entry_version_id = :version and %s
            """.formatted(LibraryScope.ENTRY_IN_SCOPE);

    private static final String REVISION = "select revision from entry_versions where entry_version_id = :version";

    private final JdbcClient database;

    private final GroupRoles roles;

    private final TransactionTemplate snapshot;

    Questions(JdbcClient database, GroupRoles roles, PlatformTransactionManager transactionManager) {
        this.database = database;
        this.roles = roles;
        this.snapshot = Snapshots.readOnly(transactionManager);
    }

    /** Somebody holding nothing in the group is refused as the group is, and a version not in view alike. */
    QuestionView read(GroupId group, EntryId entry, EntryVersionId version, UserId caller) {
        QuestionView read = snapshot.execute(status -> {
            GroupReach.requireMember(roles.heldBy(caller, group));
            int revision = LibraryScope.scoped(database.sql(IN_VIEW), group, EntryKind.QUESTION, entry)
                    .param("version", version.value())
                    .query(Integer.class)
                    .optional()
                    .orElseThrow(LibraryRefusal.VERSION_NOT_IN_VIEW::raised);
            return viewIn(group, version, caller, revision);
        });
        return requireNonNull(read);
    }

    /** Inside the caller's transaction, of a question version of the group's known to be in view. */
    QuestionView viewIn(GroupId group, EntryVersionId version, UserId caller) {
        int revision = database.sql(REVISION)
                .param("version", version.value())
                .query(Integer.class)
                .single();
        return viewIn(group, version, caller, revision);
    }

    private QuestionView viewIn(GroupId group, EntryVersionId version, UserId caller, int revision) {
        StoredQuestion stored = StoredQuestion.read(database, version)
                .orElseThrow(() -> new IllegalStateException(
                        "Question version " + version.value() + " holds no question content"));
        Map<EntryVersionId, PinnedVersions.PinnedVersion> pins =
                PinnedVersions.of(database, group, caller, stored.pinned());
        List<PinnedVersions.OfferedVersion> pinnable =
                PinnedVersions.inService(database, group, EntryKind.REFERENCE_LIST);
        Declaration gives = stored.gives().declaration();
        List<AskedField> added =
                Asking.tellable(gives) ? Asking.told(gives, stored.offered(database, group, version)) : null;
        return new QuestionView(revision, stored, pins, pinnable, added);
    }

    /**
     * @param revision what a write to it names as the one it was read at
     * @param pins each list a field of it pins, as a reader would name it
     * @param pinnable every list of the group's in service now, which is all a new pin may be to
     * @param added what a model is told of what it gives back, or none where that could not be told
     */
    record QuestionView(
            int revision,
            StoredQuestion stored,
            Map<EntryVersionId, PinnedVersions.PinnedVersion> pins,
            List<PinnedVersions.OfferedVersion> pinnable,
            @Nullable List<AskedField> added) {}
}
