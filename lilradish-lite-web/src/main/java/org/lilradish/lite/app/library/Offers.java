package org.lilradish.lite.app.library;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.declaration.Field;
import org.lilradish.lite.domain.declaration.OfferedTerms;
import org.lilradish.lite.domain.filling.FillField;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.registry.EntryId;
import org.lilradish.lite.domain.registry.EntryKind;
import org.lilradish.lite.domain.registry.EntryName;
import org.lilradish.lite.domain.registry.EntryPurpose;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.lilradish.lite.domain.wire.StoreLabels;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What a group may start a run of, read as one moment: each workflow it owns with a version in service, unstopped,
 * and each such version with the fields it takes as whoever starts it fills them in.
 */
@Component
final class Offers {

    // DB-SPECIFIC: an enum cast and the collation named below are PostgreSQL's.
    private static final String OFFERED = """
            select entry.entry_id, entry.name, entry.purpose, version.entry_version_id, version.number
              from entries entry
              join entry_versions version on version.entry_id = entry.entry_id
             where %s
             order by entry.name collate "unicode", entry.entry_id, version.number desc
            """.formatted(offered("entry", "version"));

    private final JdbcClient database;

    private final TransactionTemplate snapshot;

    Offers(JdbcClient database, PlatformTransactionManager transactionManager) {
        this.database = database;
        this.snapshot = Snapshots.readOnly(transactionManager);
    }

    /** A workflow of the group bound as {@code :group}, the version in service and the entry unstopped. */
    static String offered(String entry, String version) {
        return """
                %1$s.group_id = :group and %1$s.kind = cast('%3$s' as entry_kind) and %2$s.entry_id = %1$s.entry_id
                and %4$s and not %5$s""".formatted(
                        entry,
                        version,
                        StoreLabels.label(EntryKind.WORKFLOW),
                        VersionMarks.inService(version),
                        EntryStops.stopped(entry + ".entry_id"));
    }

    /**
     * Inside the caller's transaction, of a workflow version of the group's: the fields it takes, with the terms
     * of every list they pin.
     */
    static List<FillField> takes(JdbcClient database, GroupId group, EntryVersionId version, List<Field> fields) {
        Set<EntryVersionId> pinned = new HashSet<>();
        StoredDeclarations.pinnedIn(fields, pinned);
        Map<EntryVersionId, OfferedTerms> lists = StoredQuestion.offered(database, group, version, pinned);
        return FillField.of(fields, lists);
    }

    /** Workflows by name, each version newest first. */
    List<Offered> offeredIn(GroupId group) {
        requireNonNull(group, "Offers group must not be null");
        return requireNonNull(snapshot.execute(status -> read(group)));
    }

    private List<Offered> read(GroupId group) {
        Map<EntryId, Offered> workflows = new LinkedHashMap<>();
        Map<EntryId, List<Row>> rows = new HashMap<>();
        Map<EntryVersionId, EntryKind> versions = new LinkedHashMap<>();
        database.sql(OFFERED).param("group", group.value()).query(result -> {
            EntryId entry = new EntryId(result.getObject("entry_id", UUID.class));
            if (!workflows.containsKey(entry)) {
                workflows.put(entry, workflow(entry, result.getString("name"), result.getString("purpose")));
            }
            EntryVersionId version = new EntryVersionId(result.getObject("entry_version_id", UUID.class));
            rows.computeIfAbsent(entry, ignored -> new ArrayList<>()).add(new Row(version, result.getInt("number")));
            versions.put(version, EntryKind.WORKFLOW);
        });
        if (versions.isEmpty()) {
            return List.of();
        }
        Map<EntryVersionId, StoredDeclarations.Halves> declared = StoredDeclarations.ofVersions(database, versions);
        Set<EntryVersionId> pinned = new HashSet<>();
        for (StoredDeclarations.Halves halves : declared.values()) {
            StoredDeclarations.pinnedIn(halves.takes().declaration().fields(), pinned);
        }
        Map<EntryVersionId, OfferedTerms> lists = StoredQuestion.offered(database, group, pinned);
        List<Offered> offered = new ArrayList<>(workflows.size());
        workflows.forEach((entry, workflow) -> {
            List<OfferedVersion> inService = new ArrayList<>();
            for (Row row : requireNonNull(rows.get(entry))) {
                List<Field> fields = requireNonNull(declared.get(row.version()))
                        .takes()
                        .declaration()
                        .fields();
                inService.add(new OfferedVersion(row.version(), row.number(), FillField.of(fields, lists)));
            }
            offered.add(new Offered(workflow.entryId(), workflow.name(), workflow.purpose(), inService));
        });
        return List.copyOf(offered);
    }

    private static Offered workflow(EntryId entry, String name, @Nullable String purpose) {
        try {
            return new Offered(
                    entry, new EntryName(name), purpose == null ? null : new EntryPurpose(purpose), List.of());
        } catch (IllegalArgumentException refused) {
            throw new IllegalStateException(
                    "Entry " + entry.value() + " holds words this system will not show", refused);
        }
    }

    /** @param versions every version of it in service, newest first */
    record Offered(
            EntryId entryId, EntryName name, @Nullable EntryPurpose purpose, List<OfferedVersion> versions) {

        Offered {
            versions = List.copyOf(versions);
        }
    }

    /** @param takes what whoever starts a run of it fills in, in declared order */
    record OfferedVersion(EntryVersionId version, int number, List<FillField> takes) {

        OfferedVersion {
            takes = List.copyOf(takes);
        }
    }

    private record Row(EntryVersionId version, int number) {}
}
