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
import org.lilradish.lite.app.listing.KeysetPages;
import org.lilradish.lite.app.listing.KeysetStatements;
import org.lilradish.lite.app.listing.SearchFolding;
import org.lilradish.lite.app.listing.SortExpression;
import org.lilradish.lite.domain.declaration.Field;
import org.lilradish.lite.domain.declaration.OfferedTerms;
import org.lilradish.lite.domain.filling.FillField;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.listing.ListColumn;
import org.lilradish.lite.domain.listing.ListOrder;
import org.lilradish.lite.domain.listing.ListPage;
import org.lilradish.lite.domain.listing.ListPosition;
import org.lilradish.lite.domain.listing.ListQuery;
import org.lilradish.lite.domain.listing.ListRow;
import org.lilradish.lite.domain.listing.ListShape;
import org.lilradish.lite.domain.listing.ListValueKind;
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
 * One page of unstopped workflows a group may start a run of, read as one moment. Each returned workflow includes
 * every version of it in service, with the fields whoever starts it fills in.
 */
@Component
final class Offers {

    // DB-SPECIFIC: the enum and UUID casts are PostgreSQL's.
    private static final String SEEDS = """
            select entry.entry_id, entry.name, entry.purpose
              from entries entry
             where exists (select 1 from entry_versions version where %s)
            """.formatted(offered("entry", "version", "cast(:within as uuid)"));

    // DB-SPECIFIC: any with a UUID array cast is PostgreSQL's.
    private static final String VERSIONS = """
            select version.entry_id, version.entry_version_id, version.number
              from entry_versions version
             where version.entry_id = any(cast(:entries as uuid[])) and %s
             order by version.entry_id, version.number desc
            """.formatted(VersionMarks.inService("version"));

    // DB-SPECIFIC: ::text and the named unicode/C collations are PostgreSQL's.
    private static final KeysetStatements<Column> PAGES = new KeysetStatements<>(
            "workflows offered to start",
            Column.ENTRY_ID,
            new ListOrder<>(Column.NAME, false),
            SEEDS,
            SearchFolding.holds("entry.name_folded", KeysetStatements.TYPED),
            List.of(
                    new SortExpression<>(Column.NAME, "entry.name", "\"unicode\""),
                    new SortExpression<>(Column.ENTRY_ID, "entry.entry_id::text", "\"C\"")),
            Map.of());

    private final JdbcClient database;

    private final TransactionTemplate snapshot;

    Offers(JdbcClient database, PlatformTransactionManager transactionManager) {
        this.database = database;
        this.snapshot = Snapshots.readOnly(transactionManager);
    }

    /** A workflow of the group bound as {@code :group}, the version in service and the entry unstopped. */
    static String offered(String entry, String version) {
        return offered(entry, version, ":group");
    }

    private static String offered(String entry, String version, String scope) {
        return """
                %1$s.group_id = %6$s and %1$s.kind = cast('%3$s' as entry_kind) and %2$s.entry_id = %1$s.entry_id
                and %4$s and not %5$s""".formatted(
                        entry,
                        version,
                        StoreLabels.label(EntryKind.WORKFLOW),
                        VersionMarks.inService(version),
                        EntryStops.stopped(entry + ".entry_id"),
                        scope);
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

    static ListShape<Column> listed() {
        return PAGES.shape();
    }

    /** One page of offered workflows, each with all its in-service versions newest first. */
    ListPage<Offered> offeredIn(ListQuery<Column> query, @Nullable ListPosition after) {
        requireNonNull(query, "Offers query must not be null");
        return requireNonNull(snapshot.execute(status -> read(query, after)));
    }

    private ListPage<Offered> read(ListQuery<Column> query, @Nullable ListPosition after) {
        ListPage<Seed> page = KeysetPages.read(
                database,
                PAGES,
                query,
                after,
                (result, number) -> new Seed(workflow(
                        new EntryId(result.getObject("entry_id", UUID.class)),
                        result.getString("name"),
                        result.getString("purpose"))));
        if (page.rows().isEmpty()) {
            return new ListPage<>(List.of(), page.next());
        }
        GroupId group = new GroupId(UUID.fromString(query.scope()));
        Map<EntryId, Offered> workflows = new LinkedHashMap<>();
        Map<EntryId, List<Row>> rows = new HashMap<>();
        Map<EntryVersionId, EntryKind> versions = new LinkedHashMap<>();
        page.rows().forEach(seed -> workflows.put(seed.workflow().entryId(), seed.workflow()));
        String[] entries = workflows.keySet().stream()
                .map(entry -> entry.value().toString())
                .toArray(String[]::new);
        database.sql(VERSIONS).param("entries", entries).query(result -> {
            EntryId entry = new EntryId(result.getObject("entry_id", UUID.class));
            EntryVersionId version = new EntryVersionId(result.getObject("entry_version_id", UUID.class));
            rows.computeIfAbsent(entry, ignored -> new ArrayList<>()).add(new Row(version, result.getInt("number")));
            versions.put(version, EntryKind.WORKFLOW);
        });
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
        return new ListPage<>(List.copyOf(offered), page.next());
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

    enum Column implements ListColumn {
        NAME("name"),
        ENTRY_ID("entryId");

        private final String published;

        Column(String published) {
            this.published = published;
        }

        @Override
        public String published() {
            return published;
        }

        @Override
        public ListValueKind kind() {
            return ListValueKind.TEXT;
        }
    }

    private record Seed(Offered workflow) implements ListRow<Column> {

        @Override
        public Object valueIn(Column column) {
            return switch (column) {
                case NAME -> workflow.name().value();
                case ENTRY_ID -> workflow.entryId().value().toString();
            };
        }
    }
}
