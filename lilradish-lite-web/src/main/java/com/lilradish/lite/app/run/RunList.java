package com.lilradish.lite.app.run;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.app.group.GroupLists;
import com.lilradish.lite.app.group.GroupReach;
import com.lilradish.lite.app.group.GroupRoles;
import com.lilradish.lite.app.listing.KeysetPages;
import com.lilradish.lite.app.listing.KeysetStatements;
import com.lilradish.lite.app.listing.SearchFolding;
import com.lilradish.lite.app.listing.SortExpression;
import com.lilradish.lite.app.pool.PersonRows;
import com.lilradish.lite.domain.identity.GroupId;
import com.lilradish.lite.domain.identity.GroupPermission;
import com.lilradish.lite.domain.identity.GroupRole;
import com.lilradish.lite.domain.identity.UserId;
import com.lilradish.lite.domain.listing.ListOrder;
import com.lilradish.lite.domain.listing.ListPage;
import com.lilradish.lite.domain.listing.ListPosition;
import com.lilradish.lite.domain.listing.ListQuery;
import com.lilradish.lite.domain.listing.ListRow;
import com.lilradish.lite.domain.listing.ListShape;
import com.lilradish.lite.domain.people.PersonName;
import com.lilradish.lite.domain.registry.EntryName;
import com.lilradish.lite.domain.registry.VersionName;
import com.lilradish.lite.domain.run.RunId;
import com.lilradish.lite.domain.run.RunName;
import com.lilradish.lite.domain.run.RunPositions;
import com.lilradish.lite.domain.run.RunSnapshot;
import com.lilradish.lite.domain.run.RunSortColumn;
import com.lilradish.lite.domain.run.RunState;
import com.lilradish.lite.domain.run.StepPosition;
import com.lilradish.lite.domain.run.StepPositions;
import com.lilradish.lite.domain.run.StepSnapshot;
import com.lilradish.lite.domain.workflow.StepId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A group's runs at the top a page at a time, as the caller may read them: every run, the ones they started, or
 * none, their own included. Each is a list of its own, so a cursor minted by one is refused by another. Where each
 * run is, is worked out as its own page works it out, from one read of every run the page holds.
 */
@Component
final class RunList {

    // DB-SPECIFIC: extract(epoch …), left, substr and search_fold are PostgreSQL's.
    private static final String RUNS = """
            select listed.run_id, listed.number, listed.name, listed.workflow_name, listed.version_number, listed.version_name,
                   listed.created_at, listed.started,
                   listed.subject_id, listed.user_id, listed.display_name
              from (select run.run_id,
                           run.number,
                           run.name,
                           run.name_folded,
                           entry.name as workflow_name,
                           entry.name_folded as workflow_folded,
                           version.number as version_number,
                           version.version_name,
                           run.created_at,
                           cast(extract(epoch from run.created_at) * 1000000 as bigint) as started,
                           starter.subject_id,
                           starter.user_id,
                           starter.display_name
                      from runs run
                      join entries entry on entry.entry_id = run.entry_id
                      join entry_versions version on version.entry_version_id = run.entry_version_id
                      join subjects starter on starter.subject_id = run.created_by
                     where run.parent_run_id is null and %s) listed
             where true
            """;

    // DB-SPECIFIC: PostgreSQL timestamptz names the epoch's timestamp-with-time-zone type;
    // the verbose interval literal names its quantity and microsecond unit.
    // Held within 2^53 either way, where a double still holds every microsecond, so no cursor sent overflows.
    private static final String STARTED_AT = "timestamptz 'epoch' + least(greatest(%s, -9007199254740992), "
            + "9007199254740992) * interval '1 microsecond'";

    private static final String EVERY_RUN = "run.group_id = cast(%s as uuid)".formatted(KeysetStatements.WITHIN);

    // Read back using the same spelling that ownScope writes.
    private static final String OWN_RUNS = "run.group_id = cast(left(%s, 36) as uuid) and %s"
            .formatted(
                    KeysetStatements.WITHIN,
                    RunScope.startedBy("run", "substr(%s, 38)".formatted(KeysetStatements.WITHIN)));

    // Reading neither is answered with none rather than refused, the reader's own included: a role that grants
    // no reading of runs fails closed.
    private static final String NO_RUN = "false and " + EVERY_RUN;

    private static final String NARROWED = "%s or %s"
            .formatted(
                    SearchFolding.holds("listed.name_folded", KeysetStatements.TYPED),
                    SearchFolding.holds("listed.workflow_folded", KeysetStatements.TYPED_AS_NAMES));

    private static final KeysetStatements<RunSortColumn> EVERY = declared("every run of a group", EVERY_RUN);

    private static final KeysetStatements<RunSortColumn> OWN = declared("own runs in a group", OWN_RUNS);

    private static final KeysetStatements<RunSortColumn> NONE = declared("no run of a group", NO_RUN);

    private final JdbcClient database;

    private final GroupRoles roles;

    private final TransactionTemplate snapshot;

    private final RunSnapshots snapshots;

    RunList(
            JdbcClient database,
            GroupRoles roles,
            PlatformTransactionManager transactionManager,
            RunSnapshots snapshots) {
        this.database = database;
        this.roles = roles;
        this.snapshots = snapshots;
        TransactionTemplate snapshot = new TransactionTemplate(transactionManager);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setReadOnly(true);
        this.snapshot = snapshot;
    }

    Reading readingOf(GroupId group, UserId caller) {
        Set<GroupRole> held = roles.heldBy(caller, group);
        GroupReach.requireMember(held);
        return readingOf(GroupReach.reachedBy(held), group, caller);
    }

    static Reading readingOf(Set<GroupPermission> permitted, GroupId group, UserId caller) {
        requireNonNull(caller, "RunList caller must not be null");
        RunScope.Reach reach = RunScope.reachedBy(permitted);
        return switch (reach) {
            case EVERY -> new Reading(reach, EVERY, group, GroupLists.within(group));
            case OWN -> new Reading(reach, OWN, group, ownScope(group, caller));
            case NONE -> new Reading(reach, NONE, group, GroupLists.within(group));
        };
    }

    /** The scope a reader's own runs are read within, which {@link #OWN_RUNS} reads back. */
    static String ownScope(GroupId group, UserId caller) {
        return GroupLists.within(group) + "/" + caller.value();
    }

    /**
     * One page of the runs the reading reaches, after {@code after} or from the start where there is none, each
     * where it is as the one moment of the store that listed it holds it.
     */
    ListPage<RunRow> page(Reading reading, ListQuery<RunSortColumn> query, @Nullable ListPosition after) {
        requireNonNull(query, "RunList query must not be null");
        // The listing is narrowed by the query's scope and the runs read by the reading's group: one must be the other.
        if (!query.scope().equals(reading.scope())) {
            throw new IllegalStateException("RunList query is read within a scope other than its reading's");
        }
        ListPage<RunRow> page = snapshot.execute(status -> {
            ListPage<Listed> listed =
                    KeysetPages.read(database, reading.statements(), query, after, (result, number) -> listed(result));
            List<Listed> rows = listed.rows();
            List<RunSnapshot> read = snapshots.asRead(
                    reading.group(), rows.stream().map(Listed::runId).toList());
            return new ListPage<>(
                    IntStream.range(0, rows.size())
                            .mapToObj(index -> row(rows.get(index), read.get(index)))
                            .toList(),
                    listed.next());
        });
        return requireNonNull(page);
    }

    /* Opening newest first, on a column no row leaves empty; the number breaks every tie, being unique in a group. */
    private static KeysetStatements<RunSortColumn> declared(String name, String reached) {
        return new KeysetStatements<>(
                name,
                RunSortColumn.NUMBER,
                new ListOrder<>(RunSortColumn.STARTED, true),
                RUNS.formatted(reached),
                NARROWED,
                List.of(
                        new SortExpression<>(RunSortColumn.NUMBER, "listed.number", null),
                        new SortExpression<>(RunSortColumn.NAME, "listed.name", "\"unicode\""),
                        new SortExpression<>(RunSortColumn.WORKFLOW, "listed.workflow_name", "\"unicode\""),
                        new SortExpression<>(RunSortColumn.STARTED, "listed.created_at", null, STARTED_AT),
                        new SortExpression<>(RunSortColumn.STARTED_BY, "listed.display_name", "\"unicode\"")),
                Map.of());
    }

    private static Listed listed(ResultSet result) throws SQLException {
        RunId run = new RunId(result.getObject("run_id", UUID.class));
        PersonRows.Person starter = PersonRows.person(result);
        try {
            return new Listed(
                    run,
                    result.getInt("number"),
                    new RunName(result.getString("name")),
                    new EntryName(result.getString("workflow_name")),
                    result.getInt("version_number"),
                    result.getObject("created_at", OffsetDateTime.class).toInstant(),
                    result.getLong("started"),
                    starter,
                    result.getString("version_name") == null
                            ? null
                            : new VersionName(result.getString("version_name")));
        } catch (IllegalArgumentException refused) {
            throw new IllegalStateException("Run " + run.value() + " holds a name this system will not show", refused);
        }
    }

    private static RunRow row(Listed listed, RunSnapshot run) {
        List<StepPosition> positions = StepPositions.of(run);
        Optional<StepSnapshot> at = RunPositions.at(run, positions);
        return new RunRow(
                listed,
                RunPositions.state(run, positions),
                at.map(step -> step.planned().name()).orElse(null),
                at.map(step -> step.planned().label())
                        .map(label -> label.value())
                        .orElse(null));
    }

    /** Which list the caller reads the group's runs as, and within what, which its cursors are bound to. */
    record Reading(RunScope.Reach reach, KeysetStatements<RunSortColumn> statements, GroupId group, String scope) {

        Reading {
            requireNonNull(reach, "RunList.Reading reach must not be null");
            requireNonNull(statements, "RunList.Reading statements must not be null");
            requireNonNull(group, "RunList.Reading group must not be null");
            requireNonNull(scope, "RunList.Reading scope must not be null");
        }

        ListShape<RunSortColumn> shape() {
            return statements.shape();
        }
    }

    /** A run as the page's one statement lists it, before where it is has been worked out. */
    record Listed(
            RunId runId,
            int number,
            RunName name,
            EntryName workflow,
            int version,
            Instant startedAt,
            long startedMicroseconds,
            PersonRows.Person startedBy,
            @Nullable VersionName versionName)
            implements ListRow<RunSortColumn> {

        @Override
        public @Nullable Object valueIn(RunSortColumn column) {
            PersonName starterName = startedBy.displayName();
            return switch (column) {
                case NUMBER -> (long) number;
                case NAME -> name.value();
                case WORKFLOW -> workflow.value();
                case STARTED -> startedMicroseconds;
                case STARTED_BY -> starterName == null ? null : starterName.value();
            };
        }
    }

    /** @param at the step a running run is on, by what its workflow calls it; none where it is not running */
    record RunRow(
            Listed listed,
            RunState state,
            @Nullable StepId at,
            @Nullable String atLabel) {

        RunRow {
            requireNonNull(listed, "RunList.RunRow listed must not be null");
            requireNonNull(state, "RunList.RunRow state must not be null");
        }
    }
}
