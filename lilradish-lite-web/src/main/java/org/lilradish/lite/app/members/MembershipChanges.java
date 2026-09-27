package org.lilradish.lite.app.members;

import java.util.Set;
import org.libprunus.core.error.ApiErrorException;
import org.lilradish.lite.app.change.Author;
import org.lilradish.lite.app.change.ChangeTransactions;
import org.lilradish.lite.app.group.GroupReach;
import org.lilradish.lite.app.group.GroupRoles;
import org.lilradish.lite.app.pool.PoolStays;
import org.lilradish.lite.domain.failure.RefusalCode;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.identity.GroupPermission;
import org.lilradish.lite.domain.identity.GroupRole;
import org.lilradish.lite.domain.identity.SubjectId;
import org.lilradish.lite.domain.identity.UserId;
import org.lilradish.lite.domain.members.GroupMemberRemoval;
import org.lilradish.lite.domain.wire.StoreLabels;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Every change to who is in a group and to which roles each of them holds there, each made whole or not
 * at all. Nothing is ever deleted: a role stops being held by its row being closed, naming who closed it
 * and which of the two acts that was, so every act recorded against somebody still names them.
 *
 * <p>Every change first locks the group's row, so two changes to one group's membership run one after
 * the other: two members taking the last role that may change it from each other cannot each pass the
 * check the other makes untrue, and nobody is brought in twice. The lock is the one that does not
 * conflict with a row pointing at the group, so nothing else that points at a group waits on it.
 *
 * <p>A group always keeps somebody who may change its membership: nothing outside the group can give
 * anybody that again, so neither the last role that lets anybody nor the last member holding one is
 * taken away. Both are judged on the member as {@link Members} reads them, the reading a page withholds
 * the same controls by, so the two cannot come to disagree.
 *
 * <p>A role is given only to a member, or to somebody whose membership ended with their last role taken:
 * that is taking a member back, where anybody else is somebody to bring in. Everybody else is answered as
 * no member, whether or not they are in the pool, which is not the group's to learn.
 *
 * <p>Somebody given a role is held in the pool as {@link PoolStays} holds them, so nobody leaves the pool
 * while being put into a group. Each change is made in {@link ChangeTransactions}' transaction, asks
 * {@link GroupReach#requireStillReached} after the group's lock, and records its {@link Author}.
 */
@Component
final class MembershipChanges {

    // DB-SPECIFIC: now(), greatest, for no key update, unnest, limit, on conflict naming an index predicate,
    // an enum cast to text and enum casts are PostgreSQL's.
    private static final String AUTHOR = Author.OF_CALLER;

    private static final String GROUP_LOCKED = """
            select 1 from groups circle where circle.group_id = :group for no key update
            """;

    private static final String HELD = """
            select holding.role::text
              from group_members holding
             where holding.group_id = :group and holding.subject_id = :subject and holding.removed_at is null
            """;

    /* A removal outranks a role taken closed at the same instant, so a tie is never taken for a way back. */
    private static final String TAKEN_BACK = """
            select exists (select 1
                             from group_members holding
                            where holding.group_id = :group
                              and holding.subject_id = :subject
                              and holding.removed_at is null)
                   or coalesce((select closed.removal = cast(:taken as group_member_removal)
                                  from group_members closed
                                 where closed.group_id = :group
                                   and closed.subject_id = :subject
                                   and closed.removed_at is not null
                                 order by closed.removed_at desc, closed.removal desc
                                 limit 1), false)
            """;

    private static final String BRING_IN = """
            insert into group_members (group_id, subject_id, role, created_by)
            select :group, :subject, given.role, %s
              from unnest(cast(:roles as group_role[])) given (role)
            """.formatted(AUTHOR);

    private static final String GIVE = """
            insert into group_members (group_id, subject_id, role, created_by)
            values (:group, :subject, cast(:role as group_role), %s)
            on conflict (group_id, subject_id, role) where removed_at is null do nothing
            """.formatted(AUTHOR);

    /* now() is when this transaction began, which can be before a row it waited on the lock for was made. */
    private static final String CLOSED = """
            update group_members
               set removed_at = greatest(now(), created_at),
                   removed_by = %s,
                   removal = cast(:removal as group_member_removal)
             where group_id = :group and subject_id = :subject and removed_at is null
            """.formatted(AUTHOR);

    private static final String TAKE = CLOSED + " and role = cast(:role as group_role)";

    private static final String NOT_IN_POOL = "That person is not in the pool.";

    private static final String ALREADY_IN_GROUP = "That person already holds a role in this group.";

    private static final String LAST_CHANGER = "Nobody else in this group could change its membership.";

    private final JdbcClient database;

    private final TransactionOperations transactions;

    private final Members members;

    private final PoolStays stays;

    private final GroupRoles roles;

    MembershipChanges(
            JdbcClient database,
            TransactionOperations transactions,
            Members members,
            PoolStays stays,
            GroupRoles roles) {
        this.database = database;
        this.transactions = transactions;
        this.members = members;
        this.stays = stays;
        this.roles = roles;
    }

    /** Somebody in the pool and holding nothing here, holding exactly the roles given once it lands. */
    Members.Member bringIn(GroupId group, SubjectId person, Set<GroupRole> given, UserId caller) {
        if (given.isEmpty()) {
            throw new IllegalArgumentException("MembershipChanges brings nobody in holding nothing");
        }
        return transactions.execute(status -> {
            lockedAndStillChangedBy(group, caller);
            if (!stays.holdCurrentStay(person)) {
                throw new ApiErrorException(RefusalCode.PERSON_NOT_IN_POOL, NOT_IN_POOL);
            }
            if (!heldBy(group, person).isEmpty()) {
                throw new ApiErrorException(RefusalCode.PERSON_ALREADY_IN_GROUP, ALREADY_IN_GROUP);
            }
            database.sql(BRING_IN)
                    .param("group", group.value())
                    .param("subject", person.value())
                    .param("roles", given.stream().map(StoreLabels::label).toArray(String[]::new))
                    .param("caller", caller.value())
                    .update();
            return standingOf(group, person);
        });
    }

    /**
     * Held already, it is held still and nothing is recorded. Given to somebody whose last role here was
     * taken, it makes them a member again.
     */
    Members.Member give(GroupId group, SubjectId person, GroupRole role, UserId caller) {
        return transactions.execute(status -> {
            lockedAndStillChangedBy(group, caller);
            if (!stays.holdCurrentStay(person) || !takenBack(group, person)) {
                throw Members.notInView();
            }
            database.sql(GIVE)
                    .param("group", group.value())
                    .param("subject", person.value())
                    .param("role", StoreLabels.label(role))
                    .param("caller", caller.value())
                    .update();
            return standingOf(group, person);
        });
    }

    /**
     * Not held, it is not held still and nothing is recorded. The last role somebody holds taken, they
     * are a member no longer, and are answered holding nothing.
     */
    Members.Member take(GroupId group, SubjectId person, GroupRole role, UserId caller) {
        return transactions.execute(status -> {
            lockedAndStillChangedBy(group, caller);
            Members.Member now = members.member(group, person).orElseThrow(Members::notInView);
            if (now.roles().contains(role)) {
                if (now.lastChangingRoles().contains(role)) {
                    throw new ApiErrorException(RefusalCode.LAST_MEMBERSHIP_CHANGER, LAST_CHANGER);
                }
                database.sql(TAKE)
                        .param("removal", StoreLabels.label(GroupMemberRemoval.ROLE_TAKEN))
                        .param("group", group.value())
                        .param("subject", person.value())
                        .param("role", StoreLabels.label(role))
                        .param("caller", caller.value())
                        .update();
            }
            return standingOf(group, person);
        });
    }

    /** Every role they hold here taken at once, as the one act of taking them out of the group. */
    void remove(GroupId group, SubjectId person, UserId caller) {
        transactions.executeWithoutResult(status -> {
            lockedAndStillChangedBy(group, caller);
            if (!members.member(group, person).orElseThrow(Members::notInView).removable()) {
                throw new ApiErrorException(RefusalCode.LAST_MEMBERSHIP_CHANGER, LAST_CHANGER);
            }
            database.sql(CLOSED)
                    .param("removal", StoreLabels.label(GroupMemberRemoval.REMOVED_FROM_GROUP))
                    .param("group", group.value())
                    .param("subject", person.value())
                    .param("caller", caller.value())
                    .update();
        });
    }

    /* Whether the group exists is the caller's roles' to say, asked straight after: nobody holds one in a
     * group there is none of. */
    private void lockedAndStillChangedBy(GroupId group, UserId caller) {
        database.sql(GROUP_LOCKED)
                .param("group", group.value())
                .query(Integer.class)
                .optional();
        GroupReach.requireStillReached(roles, caller, group, GroupPermission.CHANGE_MEMBERSHIP);
    }

    private Set<GroupRole> heldBy(GroupId group, SubjectId person) {
        return Set.copyOf(database.sql(HELD)
                .param("group", group.value())
                .param("subject", person.value())
                .query((result, number) -> StoreLabels.parse(GroupRole.class, result.getString(1)))
                .list());
    }

    private boolean takenBack(GroupId group, SubjectId person) {
        return database.sql(TAKEN_BACK)
                .param("group", group.value())
                .param("subject", person.value())
                .param("taken", StoreLabels.label(GroupMemberRemoval.ROLE_TAKEN))
                .query(Boolean.class)
                .single();
    }

    private Members.Member standingOf(GroupId group, SubjectId person) {
        return members.standing(group, person)
                .orElseThrow(() -> new IllegalStateException(
                        "Subject " + person.value() + " is not in the pool inside a change that found them there"));
    }
}
