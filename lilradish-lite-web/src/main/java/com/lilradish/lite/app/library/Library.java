package com.lilradish.lite.app.library;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.app.group.GroupReach;
import com.lilradish.lite.app.group.GroupRoles;
import com.lilradish.lite.app.listing.KeysetPages;
import com.lilradish.lite.app.listing.KeysetStatements;
import com.lilradish.lite.app.listing.SearchFolding;
import com.lilradish.lite.app.listing.SortExpression;
import com.lilradish.lite.app.pool.PersonRows;
import com.lilradish.lite.domain.identity.GroupId;
import com.lilradish.lite.domain.identity.GroupRole;
import com.lilradish.lite.domain.identity.UserId;
import com.lilradish.lite.domain.listing.ListOrder;
import com.lilradish.lite.domain.listing.ListPage;
import com.lilradish.lite.domain.listing.ListPosition;
import com.lilradish.lite.domain.listing.ListQuery;
import com.lilradish.lite.domain.listing.ListRow;
import com.lilradish.lite.domain.listing.ListShape;
import com.lilradish.lite.domain.registry.EntryAct;
import com.lilradish.lite.domain.registry.EntryId;
import com.lilradish.lite.domain.registry.EntryKind;
import com.lilradish.lite.domain.registry.EntryName;
import com.lilradish.lite.domain.registry.EntryPurpose;
import com.lilradish.lite.domain.registry.EntryVersionId;
import com.lilradish.lite.domain.registry.LibrarySortColumn;
import com.lilradish.lite.domain.registry.VersionAct;
import com.lilradish.lite.domain.registry.VersionName;
import com.lilradish.lite.domain.registry.VersionStanding;
import com.lilradish.lite.domain.wire.StoreLabels;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What a group's library holds, read afresh on every call and only ever within the one group asking.
 * A stored value its type refuses fails the whole read rather than being shown or left out.
 */
@Component
final class Library {

    // DB-SPECIFIC: PostgreSQL native enum casts and literals, and LIMIT.
    private static final String ENTRIES = """
            select library.entry_id, library.name, library.purpose, library.in_service, library.version_name,
                   library.submitted, library.stopped, library.status_rank
              from (select facts.*,
                           cast(case when facts.submitted then 0 when facts.stopped then 1 else 2 end as bigint)
                                   as status_rank
                      from (select entry.entry_id,
                                   entry.name,
                                   entry.purpose,
                                   entry.name_folded,
                                   (select max(version.number)
                                      from entry_versions version
                                     where version.entry_id = entry.entry_id and %s) as in_service,
                                   (select version.version_name
                                      from entry_versions version
                                     where version.entry_id = entry.entry_id and %s
                                     order by version.number desc limit 1) as version_name,
                                   exists (select 1
                                             from entry_versions version
                                            where version.entry_id = entry.entry_id and %s) as submitted,
                                   %s as stopped
                              from entries entry
                             where entry.group_id = cast(%s as uuid)
                               and entry.kind = cast(:kind as entry_kind)) facts) library
             where true
            """.formatted(
                    VersionMarks.inService("version"),
                    VersionMarks.inService("version"),
                    VersionMarks.awaitingApproval("version"),
                    EntryStops.stopped("entry.entry_id"),
                    KeysetStatements.WITHIN);

    /* Names are unique within a group's entries of one kind, so they break every tie. */
    private static final Map<EntryKind, KeysetStatements<LibrarySortColumn>> PAGES = declared();

    private static final String HEADER = """
            select entry.name,
                   entry.purpose,
                   stop.created_at as stopped_at,
                   stopper.subject_id,
                   stopper.user_id,
                   stopper.display_name
              from entries entry
              left join entry_stops stop on stop.entry_id = entry.entry_id and %s
              left join subjects stopper on stopper.subject_id = stop.created_by
             where %s
            """.formatted(EntryStops.inForce("stop"), LibraryScope.ENTRY_IN_SCOPE);

    private static final String VERSIONS = """
            select version.entry_version_id,
                   version.number,
                   version.version_name,
                   version.revision,
                   version.approved_by_kind = 'seeder' as by_migration,
                   starter.kind = 'seeder' as started_by_migration,
                   approver.subject_id,
                   approver.user_id,
                   approver.display_name,
                   %s
              from entry_versions version
              join subjects starter on starter.subject_id = version.created_by
              left join subjects approver on approver.subject_id = version.approved_by and approver.kind = 'person'
             where version.entry_id = :entry
             order by version.number desc
            """.formatted(VersionMarks.STANDING_SELECTED);

    /* Whoever started a version where a person did, then whoever wrote it since, each in the order they first did. */
    private static final String WRITERS = """
            select authored.entry_version_id, person.subject_id, person.user_id, person.display_name
              from (select version.entry_version_id, version.created_by, version.created_at
                      from entry_versions version
                     where version.entry_id = :entry
                    union all
                    select writer.entry_version_id, writer.created_by, writer.created_at
                      from entry_version_writers writer
                      join entry_versions version on version.entry_version_id = writer.entry_version_id
                     where version.entry_id = :entry) authored
              join subjects person on person.subject_id = authored.created_by and person.kind = 'person'
             order by authored.entry_version_id, authored.created_at, person.subject_id
            """;

    /* Only a version in service pins anything that is counted, and one pinning twice counts once. */
    // DB-SPECIFIC: PostgreSQL's native entry_kind enum is cast to text.
    private static final String PINS = """
            with versions as (select version.entry_version_id from entry_versions version where version.entry_id = :entry),
                 holding as (select step.pinned_version_id as target, step.entry_version_id as holder
                               from workflow_steps step
                              where step.pinned_version_id in (select entry_version_id from versions)
                             union
                             select route.target_version_id, route.entry_version_id
                               from route_cases route
                              where route.target_version_id in (select entry_version_id from versions)
                             union
                             select field.term_list_version_id, coalesce(field.entry_version_id, owner.entry_version_id)
                               from declaration_fields field
                               left join workflow_steps owner on owner.workflow_step_id = field.workflow_step_id
                              where field.term_list_version_id in (select entry_version_id from versions))
            select holding.target,
                   holder.entry_version_id as holder_version_id,
                   holder.number as holder_number,
                   holder.version_name,
                   holder_entry.entry_id as holder_entry_id,
                   holder_entry.name as holder_name,
                   cast(holder_entry.kind as text) as holder_kind
              from holding
              join entry_versions holder on holder.entry_version_id = holding.holder
              join entries holder_entry on holder_entry.entry_id = holder.entry_id
             where holder_entry.group_id = :group and %s
             order by holding.target, holder_entry.name collate "unicode", holder.number
            """.formatted(VersionMarks.inService("holder"));

    private final JdbcClient database;

    private final GroupRoles roles;

    private final TransactionTemplate snapshot;

    Library(JdbcClient database, GroupRoles roles, PlatformTransactionManager transactionManager) {
        this.database = database;
        this.roles = roles;
        TransactionTemplate snapshot = new TransactionTemplate(transactionManager);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setReadOnly(true);
        this.snapshot = snapshot;
    }

    /** The list a group's entries of one kind are read as, which every cursor it hands out is bound to. */
    static ListShape<LibrarySortColumn> listed(EntryKind kind) {
        return statementsOf(kind).shape();
    }

    /**
     * One page of the entries of the kind in the group the query is read within, after {@code after} or
     * from the start where there is none.
     */
    ListPage<LibraryRow> page(EntryKind kind, ListQuery<LibrarySortColumn> query, @Nullable ListPosition after) {
        requireNonNull(query, "Library query must not be null");
        return KeysetPages.read(database, statementsOf(kind), query, after, (result, number) -> row(result));
    }

    /** Only matches the versions already offered to a selector, within their actual group's kind. */
    List<UUID> versionNameMatches(
            GroupId group, EntryKind kind, List<EntryVersionId> candidates, String typed, UserId caller) {
        requireNonNull(typed);
        List<UUID> read = snapshot.execute(ignored -> {
            GroupReach.requireMember(roles.heldBy(caller, group));
            if (candidates.isEmpty()) {
                return List.of();
            }
            Integer number = versionNumber(typed);
            // DB-SPECIFIC: PostgreSQL unnest reads a uuid array, enum casts scope the kind, and search_fold
            // with escaped LIKE gives names the same literal-fragment matching as entry names.
            String sql = """
                    select distinct version.entry_version_id
                      from unnest(cast(:versions as uuid[])) candidate(version_id)
                      join entry_versions version on version.entry_version_id = candidate.version_id
                      join entries entry on entry.entry_id = version.entry_id
                     where entry.group_id = :group and entry.kind = cast(:kind as entry_kind)
                       and (%s or %s or version.number = :number)
                    """.formatted(
                            SearchFolding.holds("entry.name_folded", ":typed"),
                            SearchFolding.holds("search_fold(version.version_name)", ":typed"));
            return database.sql(sql)
                    .param(
                            "versions",
                            candidates.stream()
                                    .map(candidate -> candidate.value().toString())
                                    .toArray(String[]::new))
                    .param("group", group.value())
                    .param("kind", StoreLabels.label(kind))
                    .param("typed", typed)
                    .param("number", number, java.sql.Types.INTEGER)
                    .query((result, row) -> result.getObject(1, UUID.class))
                    .list();
        });
        return requireNonNull(read);
    }

    /** An entire ASCII integer, or none; an overflow remains eligible for literal name matching. */
    static @Nullable Integer versionNumber(String typed) {
        if (typed.matches("[0-9]+")) {
            try {
                return Integer.valueOf(typed);
            } catch (NumberFormatException outsideIntegerRange) {
                return null;
            }
        }
        return null;
    }

    /**
     * One entry and every version of it, newest first, as one moment of the store read it, with their
     * state acts; somebody holding nothing in the group is refused as the group is.
     */
    EntryView entry(GroupId group, EntryKind kind, EntryId entry, UserId caller) {
        EntryView read = snapshot.execute(status -> {
            Set<GroupRole> held = roles.heldBy(caller, group);
            GroupReach.requireMember(held);
            Header header = LibraryScope.scoped(database.sql(HEADER), group, kind, entry)
                    .query((result, number) -> header(result, entry))
                    .optional()
                    .orElseThrow(LibraryRefusal.ENTRY_NOT_IN_VIEW::raised);
            Map<UUID, List<PersonRows.Person>> writers = writers(entry);
            Map<UUID, List<PinnedBy>> pins = pins(group, entry);
            List<VersionView> versions = database.sql(VERSIONS)
                    .param("entry", entry.value())
                    .query((result, number) -> version(result, writers, pins))
                    .list();
            boolean draftUnderWay = versions.stream()
                    .anyMatch(version -> version.standing() == VersionStanding.DRAFT
                            || version.standing() == VersionStanding.SUBMITTED);
            return new EntryView(
                    entry,
                    kind,
                    header.name(),
                    header.purpose(),
                    header.stop(),
                    EntryAct.stateActs(header.stop() != null, draftUnderWay),
                    versions);
        });
        return requireNonNull(read);
    }

    private Map<UUID, List<PersonRows.Person>> writers(EntryId entry) {
        Map<UUID, List<PersonRows.Person>> writers = new HashMap<>();
        database.sql(WRITERS).param("entry", entry.value()).query(result -> {
            UUID version = result.getObject("entry_version_id", UUID.class);
            writers.computeIfAbsent(version, ignored -> new ArrayList<>()).add(PersonRows.person(result));
        });
        return writers;
    }

    private Map<UUID, List<PinnedBy>> pins(GroupId group, EntryId entry) {
        Map<UUID, List<PinnedBy>> pins = new HashMap<>();
        database.sql(PINS)
                .param("entry", entry.value())
                .param("group", group.value())
                .query(result -> {
                    UUID target = result.getObject("target", UUID.class);
                    pins.computeIfAbsent(target, ignored -> new ArrayList<>()).add(pinnedBy(result));
                });
        return pins;
    }

    private static KeysetStatements<LibrarySortColumn> statementsOf(EntryKind kind) {
        requireNonNull(kind, "Library kind must not be null");
        return requireNonNull(PAGES.get(kind));
    }

    private static Map<EntryKind, KeysetStatements<LibrarySortColumn>> declared() {
        Map<EntryKind, KeysetStatements<LibrarySortColumn>> declared = new EnumMap<>(EntryKind.class);
        for (EntryKind kind : EntryKind.values()) {
            String label = StoreLabels.label(kind);
            declared.put(
                    kind,
                    new KeysetStatements<>(
                            "library of " + label,
                            LibrarySortColumn.NAME,
                            new ListOrder<>(LibrarySortColumn.NAME, false),
                            ENTRIES,
                            SearchFolding.holds("library.name_folded", KeysetStatements.TYPED),
                            List.of(
                                    new SortExpression<>(LibrarySortColumn.NAME, "library.name", "\"unicode\""),
                                    new SortExpression<>(
                                            LibrarySortColumn.IN_SERVICE, "(library.in_service is not null)", null),
                                    new SortExpression<>(LibrarySortColumn.STATUS, "library.status_rank", null)),
                            Map.of("kind", label)));
        }
        return Collections.unmodifiableMap(declared);
    }

    private static LibraryRow row(ResultSet result) throws SQLException {
        EntryId entry = new EntryId(result.getObject("entry_id", UUID.class));
        int number = result.getInt("in_service");
        Integer inService = result.wasNull() ? null : number;
        return new LibraryRow(
                entry,
                nameOf(result.getString("name"), entry),
                purposeOf(result.getString("purpose"), entry),
                inService,
                result.getBoolean("submitted"),
                result.getBoolean("stopped"),
                result.getLong("status_rank"),
                versionName(result.getString("version_name")));
    }

    private static Header header(ResultSet result, EntryId entry) throws SQLException {
        EntryName name = nameOf(result.getString("name"), entry);
        EntryPurpose purpose = purposeOf(result.getString("purpose"), entry);
        OffsetDateTime stoppedAt = result.getObject("stopped_at", OffsetDateTime.class);
        Stop stop = stoppedAt == null ? null : new Stop(PersonRows.person(result), stoppedAt.toInstant());
        return new Header(name, purpose, stop);
    }

    private static VersionView version(
            ResultSet result, Map<UUID, List<PersonRows.Person>> writers, Map<UUID, List<PinnedBy>> pins)
            throws SQLException {
        UUID stored = result.getObject("entry_version_id", UUID.class);
        EntryVersionId version = new EntryVersionId(stored);
        VersionStanding standing = VersionMarks.standing(result, version);
        Approval approval = null;
        if (result.getBoolean("approved")) {
            approval = result.getBoolean("by_migration")
                    ? new Approval.ByMigration()
                    : new Approval.ByPerson(PersonRows.person(result));
        }
        List<PersonRows.Person> wrote = List.copyOf(writers.getOrDefault(stored, List.of()));
        boolean startedByMigration = result.getBoolean("started_by_migration");
        if (wrote.isEmpty() && !startedByMigration) {
            throw new IllegalStateException("Version " + stored + " was written by nobody this system can name");
        }
        return new VersionView(
                version,
                result.getInt("number"),
                result.getInt("revision"),
                standing,
                wrote,
                startedByMigration,
                approval,
                VersionAct.stateActs(standing),
                List.copyOf(pins.getOrDefault(stored, List.of())),
                versionName(result.getString("version_name")));
    }

    private static PinnedBy pinnedBy(ResultSet result) throws SQLException {
        EntryId entry = new EntryId(result.getObject("holder_entry_id", UUID.class));
        return new PinnedBy(
                entry,
                StoreLabels.parse(EntryKind.class, result.getString("holder_kind")),
                nameOf(result.getString("holder_name"), entry),
                new EntryVersionId(result.getObject("holder_version_id", UUID.class)),
                result.getInt("holder_number"),
                versionName(result.getString("version_name")));
    }

    private static @Nullable VersionName versionName(@Nullable String stored) {
        return stored == null ? null : new VersionName(stored);
    }

    private static @Nullable EntryPurpose purposeOf(@Nullable String stored, EntryId entry) {
        if (stored == null) {
            return null;
        }
        try {
            return new EntryPurpose(stored);
        } catch (IllegalArgumentException refused) {
            throw new IllegalStateException(
                    "Entry " + entry.value() + " holds a purpose this system will not show", refused);
        }
    }

    private static EntryName nameOf(String stored, EntryId entry) {
        try {
            return new EntryName(stored);
        } catch (IllegalArgumentException refused) {
            throw new IllegalStateException(
                    "Entry " + entry.value() + " holds a name this system will not show", refused);
        }
    }

    /** @param inService the number of the newest version in service, or none where none is */
    record LibraryRow(
            EntryId entryId,
            EntryName name,
            @Nullable EntryPurpose purpose,
            @Nullable Integer inService,
            boolean submitted,
            boolean stopped,
            long statusRank,
            @Nullable VersionName versionName)
            implements ListRow<LibrarySortColumn> {

        @Override
        public Object valueIn(LibrarySortColumn column) {
            return switch (column) {
                case NAME -> name.value();
                case IN_SERVICE -> inService != null;
                case STATUS -> statusRank;
            };
        }
    }

    /**
     * @param stop the stop in force, or none where the entry may be run
     * @param stateActs what the entry state allows, independent of its reader
     */
    record EntryView(
            EntryId entryId,
            EntryKind kind,
            EntryName name,
            @Nullable EntryPurpose purpose,
            @Nullable Stop stop,
            @Nullable Set<EntryAct> stateActs,
            List<VersionView> versions) {}

    /**
     * @param revision what a write to its content names as the one it was read at
     * @param writers everyone who wrote it, in the order they first did; none only where a migration did
     * @param startedByMigration whether a migration started it, which is nobody and never shown as somebody
     * @param pinnedBy the versions in service in this group pinning it, the only ones counted
     */
    record VersionView(
            EntryVersionId versionId,
            int number,
            int revision,
            VersionStanding standing,
            List<PersonRows.Person> writers,
            boolean startedByMigration,
            @Nullable Approval approval,
            @Nullable Set<VersionAct> stateActs,
            List<PinnedBy> pinnedBy,
            @Nullable VersionName versionName) {}

    /** Put into service by a person, or by a migration, which is nobody and never shown as somebody. */
    sealed interface Approval {

        record ByPerson(PersonRows.Person approver) implements Approval {}

        record ByMigration() implements Approval {}
    }

    record PinnedBy(
            EntryId entryId,
            EntryKind kind,
            EntryName name,
            EntryVersionId versionId,
            int number,
            @Nullable VersionName versionName) {}

    record Stop(PersonRows.Person by, Instant at) {}

    private record Header(
            EntryName name,
            @Nullable EntryPurpose purpose,
            @Nullable Stop stop) {}
}
