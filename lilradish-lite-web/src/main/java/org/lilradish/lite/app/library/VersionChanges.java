package org.lilradish.lite.app.library;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.lilradish.lite.app.change.Author;
import org.lilradish.lite.app.group.GroupRoles;
import org.lilradish.lite.domain.failure.RefusalCode;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.identity.UserId;
import org.lilradish.lite.domain.registry.ContentProblem;
import org.lilradish.lite.domain.registry.EntryAct;
import org.lilradish.lite.domain.registry.EntryId;
import org.lilradish.lite.domain.registry.EntryKind;
import org.lilradish.lite.domain.registry.EntryName;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.lilradish.lite.domain.registry.VersionAct;
import org.lilradish.lite.domain.registry.VersionStanding;
import org.lilradish.lite.domain.wire.StoreLabels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Starting a draft and moving a version between standings as the caller's act, under the group's lock. A
 * submission refused for its content names every pin retired since too, so no one refusal hides the other.
 */
@Component
final class VersionChanges {

    private static final Logger logger = LoggerFactory.getLogger(VersionChanges.class);

    // DB-SPECIFIC: now(), greatest, returning, limit, a lateral join, for no key update of, for share of,
    // max(…) in a correlated subquery and enum casts are PostgreSQL's.
    private static final String AUTHOR = Author.OF_CALLER;

    /* Read after the start, which may have waited on an approval: the newest in service, else the highest-numbered. */
    private static final String STARTED_FROM = """
            select version.entry_version_id
              from entry_versions version
             where version.entry_id = :entry and version.entry_version_id <> :started
             order by %s desc, version.number desc
             limit 1
            """.formatted(VersionMarks.inService("version"));

    /* Two started at once take one number, or the second finds the first started: either refuses the same way. */
    private static final String STARTED = """
            insert into entry_versions (entry_id, entry_kind, number, created_by)
            select entry.entry_id,
                   entry.kind,
                   coalesce((select max(used.number) from entry_versions used where used.entry_id = entry.entry_id), 0) + 1,
                   %s
              from entries entry
             where entry.entry_id = :entry
            returning entry_version_id
            """.formatted(AUTHOR);

    /* A run starting takes the version for share, which this conflicts with: retiring a version waits on every
    start holding it, and a start on the retirement. */
    private static final String LOCKED = """
            select 1
              from entry_versions version
              join entries entry on entry.entry_id = version.entry_id
             where version.entry_version_id = :version
               and %s
               for no key update of version
            """.formatted(LibraryScope.ENTRY_IN_SCOPE);

    /* A statement of its own after the lock: the one taking it reads other rows as they were before it waited. */
    private static final String MARKED = """
            select %s
              from entry_versions version
             where version.entry_version_id = :version
            """.formatted(VersionMarks.SELECTED);

    /** Every version the version bound as {@code :version} pins, by a step, a route's case or a field of terms. */
    private static final String PINNED_BY_VERSION = """
            select step.pinned_version_id
              from workflow_steps step
             where step.entry_version_id = :version and step.pinned_version_id is not null
            union
            select route.target_version_id
              from route_cases route
             where route.entry_version_id = :version and route.target_version_id is not null
            union
            select field.term_list_version_id
              from declaration_fields field
              left join workflow_steps owner on owner.workflow_step_id = field.workflow_step_id
             where coalesce(field.entry_version_id, owner.entry_version_id) = :version
               and field.term_list_version_id is not null""";

    /* Only this group's: nothing else may be pinned, so no other group's version is locked or named here. */
    private static final String PINNED_TARGETS = """
              from entry_versions target
              join entries target_entry on target_entry.entry_id = target.entry_id
             where target.entry_version_id in (%s) and target_entry.group_id = :group""".formatted(PINNED_BY_VERSION);

    /* Share: none of them is retired while this change lasts, and nothing run on them waits. */
    private static final String PINNED_HELD = "select 1 " + PINNED_TARGETS + " for share of target";

    /* Several of an entry's versions may be in service at once; the newest is the one offered. */
    private static final String PINNED_RETIRED = """
            select pinned.entry_id,
                   pinned.kind,
                   pinned.name,
                   pinned.entry_version_id,
                   pinned.number,
                   newest.entry_version_id as newest_in_service,
                   newest.number as newest_number
              from (select target.entry_id,
                           cast(target_entry.kind as text) as kind,
                           target_entry.name,
                           target.entry_version_id,
                           target.number
                     %s
                       and target.retired_at is not null) pinned
              left join lateral (select candidate.entry_version_id, candidate.number
                                   from entry_versions candidate
                                  where candidate.entry_id = pinned.entry_id and %s
                                  order by candidate.number desc
                                  limit 1) newest on true
             order by pinned.entry_id, pinned.number
            """.formatted(PINNED_TARGETS, VersionMarks.inService("candidate"));

    private static final String SUBMIT = """
            insert into entry_version_submissions (entry_version_id, created_by) values (:version, %s)
            """.formatted(AUTHOR);

    /* now() is when this transaction began, which can be before a row it waited on the lock for was made. */
    private static final String WITHDRAW = """
            update entry_version_submissions
               set withdrawn_at = greatest(now(), created_at), withdrawn_by = %s
             where entry_version_id = :version and withdrawn_at is null
            """.formatted(AUTHOR);

    /* The kind is said and not left to the column: unchecked until now, it may hold anything. */
    private static final String APPROVE = """
            update entry_versions
               set approved_at = greatest(now(), created_at), approved_by = %s, approved_by_kind = 'person'
             where entry_version_id = :version
            """.formatted(AUTHOR);

    private static final String RETIRE = """
            update entry_versions
               set retired_at = greatest(now(), approved_at), retired_by = %s, retired_by_kind = 'person'
             where entry_version_id = :version
            """.formatted(AUTHOR);

    private final JdbcClient database;

    private final TransactionOperations transactions;

    private final GroupRoles roles;

    private final ContentChecks checks;

    VersionChanges(JdbcClient database, TransactionOperations transactions, GroupRoles roles, ContentChecks checks) {
        this.database = database;
        this.transactions = transactions;
        this.roles = roles;
        this.checks = checks;
    }

    /** The entry's next version, a draft holding what the one it starts from holds; refused where it could not. */
    EntryVersionId startDraft(GroupId group, EntryKind kind, EntryId entry, UserId caller) {
        return transactions.execute(status -> {
            LibraryScope.requireStillReached(roles, caller, group, EntryAct.START_DRAFT.permission());
            LibraryScope.requireEntryInView(database, group, kind, entry);
            return draftStarted(database, kind, entry, caller);
        });
    }

    void submit(GroupId group, EntryKind kind, EntryId entry, EntryVersionId version, UserId caller) {
        act(VersionAct.SUBMIT, SUBMIT, group, kind, entry, version, caller);
    }

    void withdraw(GroupId group, EntryKind kind, EntryId entry, EntryVersionId version, UserId caller) {
        act(VersionAct.WITHDRAW, WITHDRAW, group, kind, entry, version, caller);
    }

    void approve(GroupId group, EntryKind kind, EntryId entry, EntryVersionId version, UserId caller) {
        act(VersionAct.APPROVE, APPROVE, group, kind, entry, version, caller);
    }

    void retire(GroupId group, EntryKind kind, EntryId entry, EntryVersionId version, UserId caller) {
        act(VersionAct.RETIRE, RETIRE, group, kind, entry, version, caller);
    }

    /** Inside the caller's transaction, of an entry known to be in view: the version and what it holds. */
    static EntryVersionId draftStarted(JdbcClient database, EntryKind kind, EntryId entry, UserId caller) {
        UUID started;
        try {
            started = database.sql(STARTED)
                    .param("entry", entry.value())
                    .param("caller", caller.value())
                    .query((result, number) -> result.getObject("entry_version_id", UUID.class))
                    .single();
        } catch (DuplicateKeyException alreadyStarted) {
            throw LibraryRefusal.DRAFT_ALREADY_STARTED.raised(alreadyStarted);
        }
        EntryVersionId draft = new EntryVersionId(started);
        EntryVersionId source = database.sql(STARTED_FROM)
                .param("entry", entry.value())
                .param("started", started)
                .query((result, number) -> new EntryVersionId(result.getObject(1, UUID.class)))
                .optional()
                .orElse(null);
        StartedContent.started(database, kind, draft, source, caller);
        return draft;
    }

    /**
     * Inside the caller's transaction: the version locked until it ends, and refused unless the caller may
     * do the act to it as it now stands.
     */
    static void requireAdmitted(
            JdbcClient database,
            GroupRoles roles,
            VersionAct act,
            GroupId group,
            EntryKind kind,
            EntryId entry,
            EntryVersionId version,
            UserId caller) {
        LibraryScope.requireStillReached(roles, caller, group, act.permission());
        boolean inView = LibraryScope.scoped(database.sql(LOCKED), group, kind, entry)
                .param("version", version.value())
                .query(Integer.class)
                .optional()
                .isPresent();
        if (!inView) {
            throw LibraryRefusal.VERSION_NOT_IN_VIEW.raised();
        }
        Marks marks = database.sql(MARKED)
                .param("version", version.value())
                .param("caller", caller.value())
                .query((result, number) ->
                        new Marks(VersionMarks.standing(result, version), VersionMarks.writtenByCaller(result)))
                .single();
        RefusalCode refused = act.refusal(marks.standing(), marks.writtenByCaller());
        if (refused == RefusalCode.APPROVER_WROTE_VERSION) {
            logger.warn("Refused {} to a caller who wrote version {}", act, version.value());
        }
        if (refused != null) {
            throw LibraryRefusal.answering(refused).raised();
        }
    }

    private void act(
            VersionAct act,
            String change,
            GroupId group,
            EntryKind kind,
            EntryId entry,
            EntryVersionId version,
            UserId caller) {
        transactions.executeWithoutResult(status -> {
            requireAdmitted(database, roles, act, group, kind, entry, version, caller);
            List<RetiredPinsRefusal.RetiredPin> retired =
                    act == VersionAct.SUBMIT || act == VersionAct.APPROVE ? retiredPins(group, version) : List.of();
            List<ContentProblem> problems =
                    act == VersionAct.SUBMIT ? checks.problemsIn(kind, group, version) : List.of();
            if (!problems.isEmpty()) {
                throw new ContentProblemsRefusal(problems, retired);
            }
            if (!retired.isEmpty()) {
                throw new RetiredPinsRefusal(retired);
            }
            database.sql(change)
                    .param("version", version.value())
                    .param("caller", caller.value())
                    .update();
        });
    }

    private List<RetiredPinsRefusal.RetiredPin> retiredPins(GroupId group, EntryVersionId version) {
        database.sql(PINNED_HELD)
                .param("version", version.value())
                .param("group", group.value())
                .query(Integer.class)
                .list();
        return database.sql(PINNED_RETIRED)
                .param("version", version.value())
                .param("group", group.value())
                .query((result, number) -> retiredPin(result))
                .list();
    }

    private static RetiredPinsRefusal.RetiredPin retiredPin(ResultSet result) throws SQLException {
        UUID newest = result.getObject("newest_in_service", UUID.class);
        return new RetiredPinsRefusal.RetiredPin(
                new EntryId(result.getObject("entry_id", UUID.class)),
                StoreLabels.parse(EntryKind.class, result.getString("kind")),
                new EntryName(result.getString("name")),
                new RetiredPinsRefusal.NumberedVersion(
                        new EntryVersionId(result.getObject("entry_version_id", UUID.class)), result.getInt("number")),
                newest == null
                        ? null
                        : new RetiredPinsRefusal.NumberedVersion(
                                new EntryVersionId(newest), result.getInt("newest_number")));
    }

    private record Marks(VersionStanding standing, boolean writtenByCaller) {}
}
