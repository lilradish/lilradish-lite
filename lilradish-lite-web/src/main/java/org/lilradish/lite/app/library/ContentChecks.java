package org.lilradish.lite.app.library;

import static java.util.Objects.requireNonNull;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.registry.ContentProblem;
import org.lilradish.lite.domain.registry.EntryKind;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.lilradish.lite.domain.wire.StoreLabels;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Every kind's {@link ContentCheck}, exactly one each: a kind with none, or with two, refuses the start
 * rather than letting a submission of it through unasked.
 */
@Component
public final class ContentChecks {

    // DB-SPECIFIC: an enum cast to text is PostgreSQL's.
    private static final String KIND_IN_GROUP = """
            select cast(version.entry_kind as text)
              from entry_versions version
              join entries entry on entry.entry_id = version.entry_id
             where version.entry_version_id = :version and entry.group_id = :group
            """;

    private final JdbcClient database;

    private final Map<EntryKind, ContentCheck> byKind;

    ContentChecks(JdbcClient database, List<ContentCheck> checks) {
        this.database = database;
        Map<EntryKind, ContentCheck> byKind = new EnumMap<>(EntryKind.class);
        for (ContentCheck check : checks) {
            if (byKind.put(check.kind(), check) != null) {
                throw new IllegalStateException("Two content checks are declared for " + check.kind());
            }
        }
        for (EntryKind kind : EntryKind.values()) {
            if (!byKind.containsKey(kind)) {
                throw new IllegalStateException("No content check is declared for " + kind);
            }
        }
        this.byKind = byKind;
    }

    /**
     * A stored version of the group's held again to what submitting held it to; only inside a transaction
     * reading one moment, and a version the group does not hold is a store gone wrong.
     */
    public List<ContentProblem> recheck(GroupId group, EntryVersionId version) {
        requireNonNull(group, "ContentChecks group must not be null");
        requireNonNull(version, "ContentChecks version must not be null");
        if (!Snapshots.inForceReadsOneMoment()) {
            throw new IllegalStateException(
                    "ContentChecks was asked to recheck outside a transaction reading one moment");
        }
        EntryKind kind = database.sql(KIND_IN_GROUP)
                .param("version", version.value())
                .param("group", group.value())
                .query(String.class)
                .optional()
                .map(label -> StoreLabels.parse(EntryKind.class, label))
                .orElseThrow(() ->
                        new IllegalStateException("Group " + group.value() + " holds no version " + version.value()));
        return problemsIn(kind, group, version);
    }

    /** Inside the caller's transaction, of a version of that kind the group is known to hold. */
    List<ContentProblem> problemsIn(EntryKind kind, GroupId group, EntryVersionId version) {
        return requireNonNull(byKind.get(kind)).problemsIn(group, version);
    }
}
