package org.lilradish.lite.app.groupregister;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.libprunus.core.error.ApiErrorException;
import org.lilradish.lite.app.change.Author;
import org.lilradish.lite.app.change.ChangeTransactions;
import org.lilradish.lite.app.estate.EstateReach;
import org.lilradish.lite.app.estate.EstateRoleGrants;
import org.lilradish.lite.app.pool.PoolStays;
import org.lilradish.lite.domain.failure.RefusalCode;
import org.lilradish.lite.domain.identity.EstateAct;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.identity.GroupKey;
import org.lilradish.lite.domain.identity.GroupName;
import org.lilradish.lite.domain.identity.GroupPermission;
import org.lilradish.lite.domain.identity.GroupRole;
import org.lilradish.lite.domain.identity.SubjectId;
import org.lilradish.lite.domain.identity.UserId;
import org.lilradish.lite.domain.wire.StoreLabels;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Creating a group and renaming one. It narrows nothing to the caller, so the act the handler asks is
 * its only guard and nothing outside this package reaches it.
 *
 * <p>Creating a group gives the one person named the one role that may change its membership, and
 * that is the only role the estate ever sets: a group nobody may change the membership of could never
 * gain anybody who may. The person's stay is held first, as {@link PoolStays} holds one, so nobody
 * leaves the pool while being put into a group.
 *
 * <p>A key is never changed once given, so nothing here writes one but the insert creating a group.
 *
 * <p>Each change is made in {@link ChangeTransactions}' transaction, asks {@link
 * EstateReach#requireStillReached} after its locks, and records its {@link Author}.
 */
@Component
final class GroupChanges {

    private static final GroupRole FOUNDING = soleRoleReaching(GroupPermission.CHANGE_MEMBERSHIP);

    // DB-SPECIFIC: uuidv7(), now(), greatest, on conflict do nothing, returning, for update, exists(…) selected as a
    // boolean and enum casts are PostgreSQL's.
    private static final String AUTHOR = Author.OF_CALLER;

    /* Waits on a group holding the key or the name that is not yet committed, so what is asked after sees it. */
    private static final String CREATE = """
            insert into groups (group_id, key, name, created_by)
            values (uuidv7(), :key, :name, %s)
            on conflict do nothing
            returning group_id
            """.formatted(AUTHOR);

    private static final String KEY_HELD = """
            select exists (select 1 from groups other where other.key = :key)
            """;

    private static final String FIRST_MEMBER = """
            insert into group_members (group_id, subject_id, role, created_by)
            values (:group, :person, cast(:role as group_role), %s)
            """.formatted(AUTHOR);

    /* For update, which the write takes anyway, a uniquely indexed column changing; taken first, no
     * lock is raised midway. */
    private static final String NAMED_NOW = """
            select circle.name from groups circle where circle.group_id = :group for update
            """;

    /* now() is when this transaction began, which can be before the group, or its last rename, was made;
     * greatest passes over an updated_at still null. */
    private static final String RENAME = """
            update groups
               set name = :name, updated_at = greatest(now(), created_at, updated_at), updated_by = %s
             where group_id = :group
            """.formatted(AUTHOR);

    private static final String KEY_TAKEN = "Another group already holds that key.";

    private static final String NAME_TAKEN = "Another group already has that name.";

    private static final String NOT_IN_POOL = "That person is not in the pool.";

    private static final String NOT_IN_VIEW = "That group is not in the register.";

    private final JdbcClient database;

    private final TransactionOperations transactions;

    private final GroupRegister register;

    private final PoolStays stays;

    private final EstateRoleGrants grants;

    GroupChanges(
            JdbcClient database,
            TransactionOperations transactions,
            GroupRegister register,
            PoolStays stays,
            EstateRoleGrants grants) {
        this.database = database;
        this.transactions = transactions;
        this.register = register;
        this.stays = stays;
        this.grants = grants;
    }

    /** A group under the name and the key given, whose one member is the person named, as its first. */
    GroupRegister.GroupRow create(GroupName name, GroupKey key, SubjectId person, UserId caller) {
        return transactions.execute(status -> {
            boolean pooled = stays.holdCurrentStay(person);
            stillHolds(caller);
            if (!pooled) {
                throw new ApiErrorException(RefusalCode.PERSON_NOT_IN_POOL, NOT_IN_POOL);
            }
            UUID created = database.sql(CREATE)
                    .param("key", key.value())
                    .param("name", name.value())
                    .param("caller", caller.value())
                    .query((result, number) -> result.getObject("group_id", UUID.class))
                    .optional()
                    .orElseThrow(() -> heldAlready(key));
            database.sql(FIRST_MEMBER)
                    .param("group", created)
                    .param("person", person.value())
                    .param("role", StoreLabels.label(FOUNDING))
                    .param("caller", caller.value())
                    .update();
            return rowOf(new GroupId(created));
        });
    }

    /** Named so already, it is named so still, and nothing is recorded. */
    GroupRegister.GroupRow rename(GroupId group, GroupName name, UserId caller) {
        return transactions.execute(status -> {
            Optional<String> named = database.sql(NAMED_NOW)
                    .param("group", group.value())
                    .query(String.class)
                    .optional();
            stillHolds(caller);
            String now = named.orElseThrow(GroupChanges::notInView);
            if (!now.equals(name.value())) {
                try {
                    database.sql(RENAME)
                            .param("name", name.value())
                            .param("caller", caller.value())
                            .param("group", group.value())
                            .update();
                } catch (DuplicateKeyException taken) {
                    throw new ApiErrorException(RefusalCode.GROUP_NAME_TAKEN, NAME_TAKEN, taken);
                }
            }
            return rowOf(group);
        });
    }

    /** Every way of naming no group is answered with this one refusal, whoever raises it. */
    static ApiErrorException notInView() {
        return new ApiErrorException(RefusalCode.GROUP_NOT_IN_VIEW, NOT_IN_VIEW);
    }

    /* A table where two roles reach it has to say which one the estate sets, which this cannot guess. */
    private static GroupRole soleRoleReaching(GroupPermission permission) {
        Set<GroupRole> reaching = GroupRole.reaching(permission);
        if (reaching.size() != 1) {
            throw new IllegalStateException(
                    reaching.size() + " roles may " + permission + " where the estate sets exactly one");
        }
        return reaching.iterator().next();
    }

    /* Keys never change and no group is deleted, so a clash the key is not held for was over the name.
     * The key is asked about first, and refuses whatever the name does. */
    private ApiErrorException heldAlready(GroupKey key) {
        boolean keyHeld = database.sql(KEY_HELD)
                .param("key", key.value())
                .query(Boolean.class)
                .single();
        return keyHeld
                ? new ApiErrorException(RefusalCode.GROUP_KEY_TAKEN, KEY_TAKEN)
                : new ApiErrorException(RefusalCode.GROUP_NAME_TAKEN, NAME_TAKEN);
    }

    private void stillHolds(UserId caller) {
        EstateReach.requireStillReached(grants, caller, EstateAct.KEEP_GROUP_REGISTER);
    }

    private GroupRegister.GroupRow rowOf(GroupId group) {
        return register.group(group)
                .orElseThrow(() -> new IllegalStateException(
                        "Group " + group.value() + " is not in the register inside the change that holds it there"));
    }
}
