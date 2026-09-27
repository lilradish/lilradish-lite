package org.lilradish.lite.app.library;

import static java.util.Objects.requireNonNull;

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
import org.lilradish.lite.app.group.GroupReach;
import org.lilradish.lite.app.group.GroupRoles;
import org.lilradish.lite.app.listing.KeysetPages;
import org.lilradish.lite.app.listing.KeysetStatements;
import org.lilradish.lite.app.listing.SearchFolding;
import org.lilradish.lite.app.listing.SortExpression;
import org.lilradish.lite.app.pool.PersonRows;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.identity.GroupPermission;
import org.lilradish.lite.domain.identity.GroupRole;
import org.lilradish.lite.domain.identity.UserId;
import org.lilradish.lite.domain.listing.ListOrder;
import org.lilradish.lite.domain.listing.ListPage;
import org.lilradish.lite.domain.listing.ListPosition;
import org.lilradish.lite.domain.listing.ListQuery;
import org.lilradish.lite.domain.listing.ListRow;
import org.lilradish.lite.domain.listing.ListShape;
import org.lilradish.lite.domain.registry.EntryAct;
import org.lilradish.lite.domain.registry.EntryId;
import org.lilradish.lite.domain.registry.EntryKind;
import org.lilradish.lite.domain.registry.EntryName;
import org.lilradish.lite.domain.registry.EntryPurpose;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.lilradish.lite.domain.registry.LibrarySortColumn;
import org.lilradish.lite.domain.registry.VersionAct;
import org.lilradish.lite.domain.registry.VersionStanding;
import org.lilradish.lite.domain.wire.StoreLabels;
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

    // DB-SPECIFIC: exists(…) selected as a boolean, a correlated max(…), a common table expression, enum
    // casts and every collation named below are PostgreSQL's.
    private static final String ENTRIES = """
            select library.entry_id, library.name, library.in_service, library.submitted, library.stopped
              from (select entry.entry_id,
                           entry.name,
                           entry.name_folded,
                           (select max(version.number)
                              from entry_versions version
                             where version.entry_id = entry.entry_id and %s) as in_service,
                           exists (select 1
                                     from entry_versions version
                                    where version.entry_id = entry.entry_id and %s) as submitted,
                           %s as stopped
                      from entries entry
                     where entry.group_id = cast(%s as uuid) and entry.kind = cast(:kind as entry_kind)) library
             where true
            """.formatted(
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
            """.formatted(VersionMarks.SELECTED);

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

    /**
     * One entry and every version of it, newest first, as one moment of the store read it, with what the
     * caller may do to each; somebody holding nothing in the group is refused as the group is.
     */
    EntryView entry(GroupId group, EntryKind kind, EntryId entry, UserId caller) {
        EntryView read = snapshot.execute(status -> {
            Set<GroupRole> held = roles.heldBy(caller, group);
            GroupReach.requireMember(held);
            Set<GroupPermission> permitted = GroupReach.reachedBy(held);
            Header header = LibraryScope.scoped(database.sql(HEADER), group, kind, entry)
                    .query((result, number) -> header(result, entry))
                    .optional()
                    .orElseThrow(LibraryRefusal.ENTRY_NOT_IN_VIEW::raised);
            Map<UUID, List<PersonRows.Person>> writers = writers(entry);
            Map<UUID, List<PinnedBy>> pins = pins(group, entry);
            List<VersionView> versions = database.sql(VERSIONS)
                    .param("entry", entry.value())
                    .param("caller", caller.value())
                    .query((result, number) -> version(result, permitted, writers, pins))
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
                    EntryAct.admitted(permitted, header.stop() != null, draftUnderWay),
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
                                    new SortExpression<>(LibrarySortColumn.SUBMITTED, "library.submitted", null),
                                    new SortExpression<>(LibrarySortColumn.STOPPED, "library.stopped", null)),
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
                inService,
                result.getBoolean("submitted"),
                result.getBoolean("stopped"));
    }

    private static Header header(ResultSet result, EntryId entry) throws SQLException {
        EntryName name = nameOf(result.getString("name"), entry);
        String purpose = result.getString("purpose");
        OffsetDateTime stoppedAt = result.getObject("stopped_at", OffsetDateTime.class);
        Stop stop = stoppedAt == null ? null : new Stop(PersonRows.person(result), stoppedAt.toInstant());
        try {
            return new Header(name, purpose == null ? null : new EntryPurpose(purpose), stop);
        } catch (IllegalArgumentException refused) {
            throw new IllegalStateException(
                    "Entry " + entry.value() + " holds a purpose this system will not show", refused);
        }
    }

    private static VersionView version(
            ResultSet result,
            Set<GroupPermission> permitted,
            Map<UUID, List<PersonRows.Person>> writers,
            Map<UUID, List<PinnedBy>> pins)
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
                VersionAct.admitted(standing, permitted, VersionMarks.writtenByCaller(result)),
                List.copyOf(pins.getOrDefault(stored, List.of())));
    }

    private static PinnedBy pinnedBy(ResultSet result) throws SQLException {
        EntryId entry = new EntryId(result.getObject("holder_entry_id", UUID.class));
        return new PinnedBy(
                entry,
                StoreLabels.parse(EntryKind.class, result.getString("holder_kind")),
                nameOf(result.getString("holder_name"), entry),
                new EntryVersionId(result.getObject("holder_version_id", UUID.class)),
                result.getInt("holder_number"));
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
            EntryId entryId, EntryName name, @Nullable Integer inService, boolean submitted, boolean stopped)
            implements ListRow<LibrarySortColumn> {

        @Override
        public Object valueIn(LibrarySortColumn column) {
            return switch (column) {
                case NAME -> name.value();
                case IN_SERVICE -> inService != null;
                case SUBMITTED -> submitted;
                case STOPPED -> stopped;
            };
        }
    }

    /**
     * @param stop the stop in force, or none where the entry may be run
     * @param acts what the caller may do to the entry as a whole now
     */
    record EntryView(
            EntryId entryId,
            EntryKind kind,
            EntryName name,
            @Nullable EntryPurpose purpose,
            @Nullable Stop stop,
            Set<EntryAct> acts,
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
            Set<VersionAct> acts,
            List<PinnedBy> pinnedBy) {}

    /** Put into service by a person, or by a migration, which is nobody and never shown as somebody. */
    sealed interface Approval {

        record ByPerson(PersonRows.Person approver) implements Approval {}

        record ByMigration() implements Approval {}
    }

    record PinnedBy(EntryId entryId, EntryKind kind, EntryName name, EntryVersionId versionId, int number) {}

    record Stop(PersonRows.Person by, Instant at) {}

    private record Header(
            EntryName name,
            @Nullable EntryPurpose purpose,
            @Nullable Stop stop) {}
}
