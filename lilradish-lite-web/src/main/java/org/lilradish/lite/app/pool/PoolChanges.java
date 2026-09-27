package org.lilradish.lite.app.pool;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.error.ApiErrorException;
import org.lilradish.lite.app.change.Author;
import org.lilradish.lite.app.change.ChangeTransactions;
import org.lilradish.lite.app.estate.EstateReach;
import org.lilradish.lite.app.estate.EstateRoleGrants;
import org.lilradish.lite.domain.failure.RefusalCode;
import org.lilradish.lite.domain.identity.EstateAct;
import org.lilradish.lite.domain.identity.EstateRole;
import org.lilradish.lite.domain.identity.SubjectId;
import org.lilradish.lite.domain.identity.UserId;
import org.lilradish.lite.domain.people.PersonName;
import org.lilradish.lite.domain.wire.StoreLabels;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Every change to who is in the pool and to which estate roles somebody in it holds, each made whole
 * or not at all. Nothing outside this package reaches it, for the reason {@link PoolPeople} gives.
 *
 * <p>A change to somebody already in the pool first locks their current stay and only then reads what
 * it decides on, so two changes to one person run one after the other: a removal and a grant cannot
 * each pass the check the other makes untrue. Bringing somebody in has no stay to lock, and the index
 * allowing one current stay per person is what decides between two arriving at once.
 *
 * <p>Each change is made in {@link ChangeTransactions}' transaction, asks {@link
 * EstateReach#requireStillReached} after its locks, and records its {@link Author}.
 */
@Component
final class PoolChanges {

    // DB-SPECIFIC: uuidv7(), now(), greatest, a data-modifying with, on conflict … do update and on conflict naming
    // an index predicate, returning, for update, exists(…) selected as a boolean, = any over an array,
    // an enum cast to text and enum casts are PostgreSQL's.
    private static final String AUTHOR = Author.OF_CALLER;

    private static final String NAMED = """
            select person.display_name from people person where person.user_id = :user
            """;

    private static final String BRING_IN = """
            with known as (
                     insert into subjects (subject_id, kind, user_id, display_name, created_by)
                     values (uuidv7(), 'person', :user, :name, %1$s)
                     on conflict (user_id) do update set display_name = excluded.display_name
                     returning subject_id),
                 stay as (
                     insert into pool_members (subject_id, created_by)
                     select known.subject_id, %1$s from known
                     on conflict (subject_id) where removed_at is null do nothing
                     returning subject_id)
            select stay.subject_id from stay
            """.formatted(AUTHOR);

    private static final String CURRENT_STAY = """
            select stay.pool_member_id
              from pool_members stay
             where stay.subject_id = :subject and stay.removed_at is null
               for update
            """;

    private static final String HELD_ON_TO = """
            select exists (select 1
                             from estate_role_grants holding
                            where holding.subject_id = :subject and holding.removed_at is null) as holds_roles,
                   exists (select 1
                             from group_members membership
                            where membership.subject_id = :subject and membership.removed_at is null) as in_groups
            """;

    /* now() is when this transaction began, which can be before the row it closes was made. */
    private static final String REMOVE = """
            update pool_members
               set removed_at = greatest(now(), created_at), removed_by = %s
             where pool_member_id = :stay
            """.formatted(AUTHOR);

    private static final String GRANT = """
            insert into estate_role_grants (subject_id, role, created_by)
            values (:subject, cast(:role as estate_role), %s)
            on conflict (subject_id, role) where removed_at is null do nothing
            """.formatted(AUTHOR);

    /* Every current holding of a granting role locked before any is counted, and in one order, so two
     * withdrawing each other wait on one another rather than both counting the other still there, or deadlocking. */
    private static final String GRANTORS = """
            select holding.subject_id, holding.role::text as role
              from estate_role_grants holding
             where holding.role = any (cast(:granting as estate_role[])) and holding.removed_at is null
             order by holding.estate_role_grant_id
               for update
            """;

    /* It can wait on the stay's lock, so the grant it withdraws can have been made after its transaction began. */
    private static final String WITHDRAW = """
            update estate_role_grants
               set removed_at = greatest(now(), created_at), removed_by = %s
             where subject_id = :subject and role = cast(:role as estate_role) and removed_at is null
            """.formatted(AUTHOR);

    private static final String NOT_IN_DIRECTORY = "The directory holds no such user.";

    private static final String ALREADY_IN_POOL = "That person is already in the pool.";

    private static final String HOLDS_ESTATE_ROLES =
            "That person holds an estate role. Withdraw their estate roles first.";

    private static final String IN_GROUPS = "That person is still in a group.";

    private static final String LAST_GRANTOR = "Withdrawing this would leave nobody who may grant an estate role.";

    private final JdbcClient database;

    private final TransactionOperations transactions;

    private final PoolPeople people;

    private final EstateRoleGrants grants;

    PoolChanges(JdbcClient database, TransactionOperations transactions, PoolPeople people, EstateRoleGrants grants) {
        this.database = database;
        this.transactions = transactions;
        this.people = people;
        this.grants = grants;
    }

    /**
     * Somebody the directory holds, under its name as {@link PersonName#fromDirectory} spaces it: the
     * subject they were before if they had been in the pool, a new one otherwise, and nothing granted.
     */
    PoolPeople.PoolPersonPanel bringIn(UserId user, UserId caller) {
        return transactions.execute(status -> {
            Optional<String> named = database.sql(NAMED)
                    .param("user", user.value())
                    .query(String.class)
                    .optional();
            UUID broughtIn = named.isPresent() ? arrival(user, nameOf(user, named.get()), caller) : null;
            stillHolds(caller, EstateAct.KEEP_POOL);
            if (named.isEmpty()) {
                throw new ApiErrorException(RefusalCode.USER_NOT_IN_DIRECTORY, NOT_IN_DIRECTORY);
            }
            if (broughtIn == null) {
                throw new ApiErrorException(RefusalCode.PERSON_ALREADY_IN_POOL, ALREADY_IN_POOL);
            }
            return panelOf(new SubjectId(broughtIn));
        });
    }

    /** Their stay ends; the subject stays, so every act recorded against them still names them. */
    void remove(SubjectId subject, UserId caller) {
        transactions.executeWithoutResult(status -> {
            Optional<UUID> current = currentStayLocked(subject);
            stillHolds(caller, EstateAct.KEEP_POOL);
            UUID stay = current.orElseThrow(PoolPeople::notInView);
            HeldOnTo held = database.sql(HELD_ON_TO)
                    .param("subject", subject.value())
                    .query((result, number) ->
                            new HeldOnTo(result.getBoolean("holds_roles"), result.getBoolean("in_groups")))
                    .single();
            if (held.estateRoles()) {
                throw new ApiErrorException(RefusalCode.PERSON_HOLDS_ESTATE_ROLES, HOLDS_ESTATE_ROLES);
            }
            if (held.groups()) {
                throw new ApiErrorException(RefusalCode.PERSON_IN_GROUPS, IN_GROUPS);
            }
            database.sql(REMOVE)
                    .param("stay", stay)
                    .param("caller", caller.value())
                    .update();
        });
    }

    /** Held already, it is held still, and nothing new is recorded. */
    PoolPeople.PoolPersonPanel grant(SubjectId subject, EstateRole role, UserId caller) {
        return transactions.execute(status -> {
            Optional<UUID> current = currentStayLocked(subject);
            stillHolds(caller, EstateAct.GRANT_ESTATE_ROLE);
            current.orElseThrow(PoolPeople::notInView);
            database.sql(GRANT)
                    .param("subject", subject.value())
                    .param("role", StoreLabels.label(role))
                    .param("caller", caller.value())
                    .update();
            return panelOf(subject);
        });
    }

    /**
     * Not held, it is not held still, and nothing is recorded. The last holding that lets anybody grant
     * an estate role is kept: nothing inside this system grants the first one, so an estate left with
     * nobody who may could never be given anybody again.
     */
    PoolPeople.PoolPersonPanel withdraw(SubjectId subject, EstateRole role, UserId caller) {
        return transactions.execute(status -> {
            Optional<UUID> current = currentStayLocked(subject);
            boolean granting = current.isPresent() && PoolPeople.grants(role);
            List<Holding> grantors = granting ? grantorsLocked() : List.of();
            stillHolds(caller, EstateAct.GRANT_ESTATE_ROLE);
            current.orElseThrow(PoolPeople::notInView);
            if (grantors.equals(List.of(new Holding(subject.value(), role)))) {
                throw new ApiErrorException(RefusalCode.LAST_ESTATE_ROLE_GRANTOR, LAST_GRANTOR);
            }
            database.sql(WITHDRAW)
                    .param("subject", subject.value())
                    .param("role", StoreLabels.label(role))
                    .param("caller", caller.value())
                    .update();
            return panelOf(subject);
        });
    }

    private static @Nullable PersonName nameOf(UserId user, String held) {
        try {
            return PersonName.fromDirectory(held);
        } catch (IllegalArgumentException refused) {
            throw new IllegalStateException(
                    "User " + user.value() + " holds a name in the directory this system will not show", refused);
        }
    }

    /** The subject now in the pool, absent where somebody already was. */
    private @Nullable UUID arrival(UserId user, @Nullable PersonName name, UserId caller) {
        return database.sql(BRING_IN)
                .param("user", user.value())
                .param("name", name == null ? null : name.value())
                .param("caller", caller.value())
                .query((result, number) -> result.getObject("subject_id", UUID.class))
                .optional()
                .orElse(null);
    }

    /* Absent is not refused here: whether the caller still holds the act is asked first, so a caller
     * who no longer does learns nothing of who is in the pool. */
    private Optional<UUID> currentStayLocked(SubjectId subject) {
        return database.sql(CURRENT_STAY)
                .param("subject", subject.value())
                .query((result, number) -> result.getObject("pool_member_id", UUID.class))
                .optional();
    }

    private void stillHolds(UserId caller, EstateAct act) {
        EstateReach.requireStillReached(grants, caller, act);
    }

    private List<Holding> grantorsLocked() {
        return PoolPeople.withGranting(database.sql(GRANTORS))
                .query((result, number) -> new Holding(
                        result.getObject("subject_id", UUID.class),
                        StoreLabels.parse(EstateRole.class, result.getString("role"))))
                .list();
    }

    private PoolPeople.PoolPersonPanel panelOf(SubjectId subject) {
        return people.person(subject)
                .orElseThrow(() -> new IllegalStateException(
                        "Subject " + subject.value() + " is not in the pool inside the change that holds them there"));
    }

    private record HeldOnTo(boolean estateRoles, boolean groups) {}

    private record Holding(UUID subject, EstateRole role) {}
}
