package org.lilradish.lite.app.pool

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.sql.Connection
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import org.libprunus.core.error.ApiErrorException
import org.lilradish.lite.app.change.ChangeTransactions
import org.lilradish.lite.app.estate.EstateRoleGrants
import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.identity.EstateRole
import org.lilradish.lite.domain.identity.SubjectId
import org.lilradish.lite.domain.identity.SystemPrincipal
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.testutil.Baseline
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * Every change to the pool as the store makes it, on a real server running the real baseline and
 * under real transactions: whether a change lands whole or not at all, and what two changes arriving
 * at once leave behind, exist nowhere but in a database.
 *
 * <p>Each feature has a database of its own, copied from one the baseline was applied to, because
 * what is under test commits: nothing short of a fresh database would keep one feature's writes from
 * the next.
 *
 * <p>Where two changes race, the database they race on defaults to repeatable read. Under that
 * default a check made after waiting for a lock would answer from before the wait, so these are
 * also where the isolation the changes name for themselves is what is being held to. Which change
 * waits first is arranged by holding the lock both need until both are seen waiting for it.
 */
class PoolChangesIntegrationSpec extends Specification {

    static final String SEEDER = "00000000-0000-4000-8000-000000000000"

    static final String SYSTEM_ACTOR = SystemPrincipal.WORKFLOW_RUNNER.subject().value().toString()

    /** Seeded by the baseline: in the pool, holding the steward role, and the one making changes here. */
    static final String FIRST_STEWARD = "00000001-0000-4000-8000-000000000001"

    static final UserId STEWARD = new UserId("000001")

    static final String PERSON = "00000002-0000-4000-8000-000000000501"

    static final String OTHER = "00000002-0000-4000-8000-000000000502"

    static final String RETURNING = "00000002-0000-4000-8000-000000000503"

    static final String GROUP = "00000003-0000-4000-8000-000000000501"

    static final List<String> WRITTEN_TO = ["subjects", "pool_members", "estate_role_grants", "groups", "group_members"]

    static final String HOLDS_ESTATE_ROLES = "That person holds an estate role. Withdraw their estate roles first."

    static final String IDEOGRAPHIC_SPACE = Character.toString(0x3000)

    static final String NO_BREAK_SPACE = Character.toString(0x00A0)

    static final String MIXED_WHITESPACE_NAME = NO_BREAK_SPACE + "Ada" + IDEOGRAPHIC_SPACE + Character.toString(0x2028) +
            " Lovelace" + Character.toString(0x202F)

    static final String ONLY_WHITESPACE_NAME = IDEOGRAPHIC_SPACE + " " + NO_BREAK_SPACE

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    DataSource database

    JdbcClient session

    PoolChanges changes

    @AutoCleanup("shutdownNow")
    ExecutorService racing = Executors.newFixedThreadPool(2)

    def setupSpec() {
        administer("create database baseline_applied")
        Baseline.appliedTo(server, "baseline_applied")
    }

    def setup() {
        def name = "changes_" + (++databasesMade)
        administer("create database ${name} template baseline_applied")
        database = Baseline.appliedTo(server, name)
        session = JdbcClient.create(database)
        changes = new PoolChanges(session, new ChangeTransactions().readCommitted(new DataSourceTransactionManager(database)),
                new PoolPeople(session), new EstateRoleGrants(session))
    }

    def "brings somebody in the directory into the pool as a subject of their own, under the name the directory holds"() {
        given:
        inDirectory("000501", "Ada Lovelace")

        when:
        def panel = changes.bringIn(new UserId("000501"), STEWARD)

        then:
        panel.userId().value() == "000501"
        panel.displayName().value() == "Ada Lovelace"
        panel.estateRoles().isEmpty()
        panel.groups().isEmpty()
        !panel.seeded()

        and: "a person the caller made, in the pool by the caller's act"
        text("select kind::text from subjects where subject_id = ?::uuid", panel.subjectId()) == "person"
        text("select created_by::text from subjects where subject_id = ?::uuid", panel.subjectId()) == FIRST_STEWARD
        text("select created_by::text from pool_members where subject_id = ?::uuid and removed_at is null",
                panel.subjectId()) == FIRST_STEWARD

        and: "and nothing else done for them: no role, no group, one stay"
        count("select count(*) from estate_role_grants where subject_id = ?::uuid", panel.subjectId()) == 0
        count("select count(*) from group_members where subject_id = ?::uuid", panel.subjectId()) == 0
        count("select count(*) from pool_members where subject_id = ?::uuid", panel.subjectId()) == 1
    }

    /**
     * The name is read, spaced and written inside the one change, so the pool's copy never holds the
     * directory's spacing, and somebody brought back loses a name the directory no longer holds.
     */
    def "brings somebody in under the name the directory holds spaced once, and under none where it holds only whitespace"() {
        given:
        if (returning) {
            person(PERSON, "000507", "Ada Byron")
            endedStay(PERSON)
        }
        inDirectory("000507", held)

        when:
        def panel = changes.bringIn(new UserId("000507"), STEWARD)

        then:
        panel.displayName()?.value() == spaced
        texts("select display_name from subjects where subject_id = ?::uuid", panel.subjectId()) == [spaced]

        and: "one subject for them, and the directory's own row left as it was held"
        count("select count(*) from subjects where user_id = '000507'") == 1
        texts("select display_name from people where user_id = '000507'") == [held]

        where:
        held                  | returning || spaced
        MIXED_WHITESPACE_NAME | false     || "Ada Lovelace"
        MIXED_WHITESPACE_NAME | true      || "Ada Lovelace"
        ONLY_WHITESPACE_NAME  | false     || null
        ONLY_WHITESPACE_NAME  | true      || null
    }

    /**
     * The subject is who every past act names, so somebody brought back is that subject again, with
     * whoever first made it still saying so. The name is the directory's as it stands now.
     */
    def "brings back somebody taken out as the subject they were, under the name the directory holds now"() {
        given:
        person(PERSON, "000502", "Ada Byron")
        endedStay(PERSON)
        inDirectory("000502", "Ada Lovelace")

        when:
        def panel = changes.bringIn(new UserId("000502"), STEWARD)

        then:
        panel.subjectId() == subjectId(PERSON)
        panel.displayName().value() == "Ada Lovelace"

        and: "one subject for them still, made by whoever made it first"
        count("select count(*) from subjects where user_id = '000502'") == 1
        text("select created_by::text from subjects where subject_id = ?::uuid", subjectId(PERSON)) == SEEDER

        and: "the stay that ended still ended beside the one begun"
        count("select count(*) from pool_members where subject_id = ?::uuid and removed_at is not null",
                subjectId(PERSON)) == 1
        count("select count(*) from pool_members where subject_id = ?::uuid and removed_at is null",
                subjectId(PERSON)) == 1
    }

    /** Somebody still in the pool after leaving the directory is not in it either, and is refused alike. */
    def "refuses a user the directory does not hold, writing nothing"() {
        given:
        person(PERSON, "000503", "Left The Directory")
        stay(PERSON)
        def before = contents()

        when:
        changes.bringIn(new UserId(user), STEWARD)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.USER_NOT_IN_DIRECTORY
        refused.message == "The directory holds no such user."

        and:
        contents() == before

        where:
        user << ["000599", "000503"]
    }

    /** Refused as a whole: the name the directory holds now is not written for them either. */
    def "refuses somebody already in the pool, leaving even the name held for them as it was"() {
        given:
        person(PERSON, "000504", "Old Name")
        stay(PERSON)
        inDirectory("000504", "New Name")
        def before = contents()

        when:
        changes.bringIn(new UserId("000504"), STEWARD)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.PERSON_ALREADY_IN_POOL
        refused.message == "That person is already in the pool."

        and:
        contents() == before
    }

    /**
     * Another arrival holds a stay for the same person that is not yet committed, so nothing either
     * could read first would decide between them. The index does: this one waits for the other, and
     * is refused if it landed and let in if it did not.
     */
    def "an arrival racing another for the same person is decided by the index allowing one current stay"() {
        given:
        person(PERSON, "000505", "Ada Lovelace")
        inDirectory("000505", "Ada Lovelace")
        def other = holding("insert into pool_members (subject_id, created_by) values ('${PERSON}', '${FIRST_STEWARD}')")

        when:
        def arrival = attempting { changes.bringIn(new UserId("000505"), STEWARD) }
        untilWaiting(1)
        otherLands ? other.commit() : other.rollback()
        other.close()
        def outcome = arrival.get(10, TimeUnit.SECONDS)

        then:
        outcome.getClass() == (refusal == null ? PoolPeople.PoolPersonPanel : ApiErrorException)
        refusal == null || outcome.errorCode() == refusal

        and: "one current stay whichever way it went"
        count("select count(*) from pool_members where subject_id = ?::uuid and removed_at is null",
                subjectId(PERSON)) == 1

        where:
        otherLands || refusal
        true       || RefusalCode.PERSON_ALREADY_IN_POOL
        false      || null
    }

    def "a caller no subject answers for is refused by the store rather than recorded as nobody"() {
        given:
        inDirectory("000506", "Ada Lovelace")
        def before = contents()

        when:
        changes.bringIn(new UserId("000506"), new UserId("000999"))

        then:
        thrown(DataIntegrityViolationException)

        and:
        contents() == before
    }

    /** The subject stays, so every act recorded against them goes on naming them. */
    def "takes somebody holding nothing out of the pool as the caller's act, keeping the subject their acts name"() {
        given:
        person(PERSON, "000511", "Ada Lovelace")
        stay(PERSON)

        when:
        changes.remove(subjectId(PERSON), STEWARD)

        then:
        text("select removed_by::text from pool_members where subject_id = ?::uuid", subjectId(PERSON)) == FIRST_STEWARD
        count("select count(*) from pool_members where subject_id = ?::uuid and removed_at is null",
                subjectId(PERSON)) == 0

        and: "the subject still there, and nobody else's stay touched"
        count("select count(*) from subjects where subject_id = ?::uuid", subjectId(PERSON)) == 1
        count("select count(*) from pool_members where removed_at is null") == 1
    }

    /** The transaction begins before the stay is first read, so a stay committed in between is the one closed. */
    def "a stay is closed when the change's transaction began, or when it was made if that was later"() {
        given:
        person(PERSON, "000516", "Ada Lovelace")
        stay(PERSON)
        session.sql("update pool_members set created_at = now() + ?::interval where subject_id = ?::uuid")
                .params(offset, PERSON).update()
        def others = (WRITTEN_TO - "pool_members").collectEntries { [it, digestOf(it)] }

        when:
        changes.remove(subjectId(PERSON), STEWARD)

        then:
        texts("""
                select removed_by::text || ' ' || case when removed_at = created_at then 'when made'
                                                       when removed_at > created_at then 'after made' end
                  from pool_members where subject_id = ?::uuid
                """, subjectId(PERSON)) == ["${FIRST_STEWARD} ${closed}" as String]

        and: "only that stay closed, and nothing else written"
        texts("select subject_id::text from pool_members where removed_at is null") == [FIRST_STEWARD]
        others.every { table, digest -> digestOf(table) == digest }

        where:
        offset    || closed
        "-1 hour" || "after made"
        "+1 hour" || "when made"
    }

    /** The roles are asked about before the groups, so somebody holding both is told about the roles. */
    def "refuses to take out somebody still holding what has to go first, changing nothing"() {
        given:
        person(PERSON, "000512", "Ada Lovelace")
        stay(PERSON)
        if (role) {
            grant(PERSON, "watcher")
        }
        if (grouped) {
            member(PERSON)
        }
        def before = contents()

        when:
        changes.remove(subjectId(PERSON), STEWARD)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == code
        refused.message == message

        and:
        contents() == before

        where:
        role  | grouped || code                                  | message
        true  | false   || RefusalCode.PERSON_HOLDS_ESTATE_ROLES | HOLDS_ESTATE_ROLES
        false | true    || RefusalCode.PERSON_IN_GROUPS          | "That person is still in a group."
        true  | true    || RefusalCode.PERSON_HOLDS_ESTATE_ROLES | HOLDS_ESTATE_ROLES
    }

    /** Only what is held now stands in the way: an ended grant and an ended membership are history. */
    def "takes out somebody whose estate grant and group membership have both ended, leaving that history as it was"() {
        given:
        person(PERSON, "000513", "Ada Lovelace")
        stay(PERSON)
        endedGrant(PERSON, "watcher")
        endedMember(PERSON)
        def history = ["estate_role_grants", "groups", "group_members"].collectEntries { [it, digestOf(it)] }

        when:
        changes.remove(subjectId(PERSON), STEWARD)

        then:
        count("select count(*) from pool_members where subject_id = ?::uuid and removed_at is null",
                subjectId(PERSON)) == 0

        and: "the ended grant and membership are neither reopened nor rewritten"
        history.every { table, digest -> digestOf(table) == digest }
    }

    /**
     * An identifier nobody holds, somebody whose stay ended, somebody never brought in, an actor that
     * is no person, and the act of seeding: none is in the pool, and none may be told from another.
     */
    def "every change to somebody not in the pool is refused alike, changing nothing"() {
        given:
        person(PERSON, "000513", "Removed Person")
        endedStay(PERSON)
        person(OTHER, "000514", "Never Pooled")
        def before = contents()

        when:
        attempt(change, subjectId(address))

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.PERSON_NOT_IN_VIEW
        refused.message == "That person is not in view."

        and:
        contents() == before

        where:
        [change, address] << [["remove", "grant", "withdraw"],
                              ["00000009-0000-4000-8000-000000000009", PERSON, OTHER, SYSTEM_ACTOR, SEEDER]].combinations()
    }

    /**
     * Both wait on the same stay, the grant first, so the grant lands and the removal then reads it:
     * under the default this database has, that is only so while the removal reads what the grant
     * committed rather than what stood before it waited.
     */
    def "a removal waiting behind a grant for one person reads the grant, and is refused"() {
        given:
        person(PERSON, "000515", "Ada Lovelace")
        stay(PERSON)
        repeatableReadByDefault()
        def held = holding("select 1 from pool_members where subject_id = '${PERSON}' for update")

        when:
        def granting = attempting { changes.grant(subjectId(PERSON), EstateRole.WATCHER, STEWARD) }
        untilWaiting(1)
        def removing = attempting { changes.remove(subjectId(PERSON), STEWARD) }
        untilWaiting(2)
        held.commit()
        held.close()
        def granted = granting.get(10, TimeUnit.SECONDS)
        def removed = removing.get(10, TimeUnit.SECONDS)

        then:
        granted instanceof PoolPeople.PoolPersonPanel
        granted.estateRoles() == [EstateRole.WATCHER] as Set
        removed instanceof ApiErrorException
        removed.errorCode() == RefusalCode.PERSON_HOLDS_ESTATE_ROLES

        and: "nobody outside the pool holds a role, which is what the lock is for"
        count("""
                select count(*) from estate_role_grants holding
                 where holding.subject_id = ?::uuid and holding.removed_at is null
                   and not exists (select 1 from pool_members stay
                                    where stay.subject_id = holding.subject_id and stay.removed_at is null)
                """, subjectId(PERSON)) == 0
    }

    def "grants a role to somebody in the pool as the caller's act, and answers with what they hold now"() {
        given:
        person(PERSON, "000521", "Ada Lovelace")
        stay(PERSON)

        when:
        def panel = changes.grant(subjectId(PERSON), EstateRole.WATCHER, STEWARD)

        then:
        panel.subjectId() == subjectId(PERSON)
        panel.estateRoles() == [EstateRole.WATCHER] as Set
        text("select created_by::text from estate_role_grants where subject_id = ?::uuid", subjectId(PERSON)) ==
                FIRST_STEWARD

        and: "that role and no other, recorded once"
        count("select count(*) from estate_role_grants where subject_id = ?::uuid", subjectId(PERSON)) == 1
    }

    /** Held already, it is held still: the grant recorded first stays the one on record. */
    def "granting a role already held records nothing and answers alike"() {
        given:
        person(PERSON, "000522", "Ada Lovelace")
        stay(PERSON)
        def first = changes.grant(subjectId(PERSON), EstateRole.WATCHER, STEWARD)
        def before = contents()

        when:
        def again = changes.grant(subjectId(PERSON), EstateRole.WATCHER, STEWARD)

        then:
        again == first
        contents() == before
    }

    /** A caller may grant a role to themselves, and the record of it says it was theirs. */
    def "a caller granting themselves the watcher role holds it, by an act recorded as their own"() {
        when:
        def panel = changes.grant(subjectId(FIRST_STEWARD), EstateRole.WATCHER, STEWARD)

        then:
        panel.estateRoles() == [EstateRole.STEWARD, EstateRole.WATCHER] as Set
        text("select created_by::text from estate_role_grants where subject_id = ?::uuid and role = 'watcher'",
                subjectId(FIRST_STEWARD)) == FIRST_STEWARD
    }

    def "withdraws a role somebody holds as the caller's act, and answers with what they still hold"() {
        given:
        person(PERSON, "000531", "Ada Lovelace")
        stay(PERSON)
        grant(PERSON, "watcher")
        grant(PERSON, "steward")

        when:
        def panel = changes.withdraw(subjectId(PERSON), EstateRole.WATCHER, STEWARD)

        then:
        panel.estateRoles() == [EstateRole.STEWARD] as Set
        text("select removed_by::text from estate_role_grants where subject_id = ?::uuid and role = 'watcher'",
                subjectId(PERSON)) == FIRST_STEWARD

        and: "the grant withdrawn still on record, and the other untouched"
        count("select count(*) from estate_role_grants where subject_id = ?::uuid", subjectId(PERSON)) == 2
        count("select count(*) from estate_role_grants where subject_id = ?::uuid and removed_at is null",
                subjectId(PERSON)) == 1
    }

    /** It waits on the person's stay lock, which a grant committing first held, so the grant can be younger than it. */
    def "a grant is withdrawn when the change's transaction began, or when it was made if that was later"() {
        given:
        person(PERSON, "000537", "Ada Lovelace")
        stay(PERSON)
        grant(PERSON, "watcher")
        session.sql("update estate_role_grants set created_at = now() + ?::interval where subject_id = ?::uuid")
                .params(offset, PERSON).update()
        def others = (WRITTEN_TO - "estate_role_grants").collectEntries { [it, digestOf(it)] }

        when:
        changes.withdraw(subjectId(PERSON), EstateRole.WATCHER, STEWARD)

        then:
        texts("""
                select removed_by::text || ' ' || case when removed_at = created_at then 'when made'
                                                       when removed_at > created_at then 'after made' end
                  from estate_role_grants where subject_id = ?::uuid and role = 'watcher'
                """, subjectId(PERSON)) == ["${FIRST_STEWARD} ${closed}" as String]

        and: "only that grant closed, and nothing else written"
        texts("select subject_id::text || ' ' || role from estate_role_grants where removed_at is null") ==
                ["${FIRST_STEWARD} steward" as String]
        others.every { table, digest -> digestOf(table) == digest }

        where:
        offset    || closed
        "-1 hour" || "after made"
        "+1 hour" || "when made"
    }

    def "withdrawing a role not held records nothing and answers alike"() {
        given:
        person(PERSON, "000532", "Ada Lovelace")
        stay(PERSON)
        def before = contents()

        when:
        def panel = changes.withdraw(subjectId(PERSON), role, STEWARD)

        then:
        panel.estateRoles().isEmpty()
        contents() == before

        where:
        role << EstateRole.values()
    }

    def "one of two stewards may be withdrawn, leaving the other"() {
        given:
        person(PERSON, "000533", "Grace Hopper")
        stay(PERSON)
        grant(PERSON, "steward")

        when:
        def panel = changes.withdraw(subjectId(FIRST_STEWARD), EstateRole.STEWARD, new UserId("000533"))

        then:
        panel.estateRoles().isEmpty()
        panel.lastGrantingRoles().isEmpty()
        text("select subject_id::text from estate_role_grants where role = 'steward' and removed_at is null") == PERSON
    }

    /** Nothing inside this system grants the first estate role, so the last way anybody may grant one is kept. */
    def "withdrawing the last role that lets anybody grant an estate role is refused as such, changing nothing"() {
        given:
        def before = contents()

        when:
        changes.withdraw(subjectId(FIRST_STEWARD), EstateRole.STEWARD, STEWARD)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.LAST_ESTATE_ROLE_GRANTOR
        refused.message =="Withdrawing this would leave nobody who may grant an estate role."

        and:
        contents() == before
    }

    /** Counted over every holding, where the pool's reading counts only the pool's, so this refuses less than it shows. */
    def "a steward whose only other grantor is outside the pool may withdraw their own role, leaving the outsider theirs"() {
        given:
        person(OTHER, "000536", "Grace Hopper")
        endedStay(OTHER)
        grant(OTHER, "steward")

        when:
        def panel = changes.withdraw(subjectId(FIRST_STEWARD), EstateRole.STEWARD, STEWARD)

        then:
        panel.estateRoles().isEmpty()
        panel.lastGrantingRoles().isEmpty()

        and:
        texts("select subject_id::text from estate_role_grants where role = 'steward' and removed_at is null") == [OTHER]
    }

    def "a role granting nothing is withdrawn from the only one who may grant, who still may"() {
        given:
        grant(FIRST_STEWARD, "watcher")

        when:
        def panel = changes.withdraw(subjectId(FIRST_STEWARD), EstateRole.WATCHER, STEWARD)

        then:
        panel.estateRoles() == [EstateRole.STEWARD] as Set
        panel.lastGrantingRoles() == [EstateRole.STEWARD] as Set

        and:
        count("select count(*) from estate_role_grants where role = 'watcher' and removed_at is null") == 0
    }

    /**
     * The only two holders of the steward role each withdrawing it from themselves, both waiting on the
     * grants every withdrawal of the role locks. Counted without that lock, each would still see the
     * other and both would land, leaving an estate nobody can ever grant anything in again.
     */
    def "the two last stewards withdrawing their own role at once leave one, the second refused as the last"() {
        given:
        person(PERSON, "000534", "Grace Hopper")
        stay(PERSON)
        grant(PERSON, "steward")
        repeatableReadByDefault()
        def held = holding("select 1 from estate_role_grants where role = 'steward' and removed_at is null for update")

        when:
        def first = attempting { changes.withdraw(subjectId(FIRST_STEWARD), EstateRole.STEWARD, STEWARD) }
        untilWaiting(1)
        def second = attempting { changes.withdraw(subjectId(PERSON), EstateRole.STEWARD, new UserId("000534")) }
        untilWaiting(2)
        held.commit()
        held.close()
        def withdrawn = first.get(10, TimeUnit.SECONDS)
        def refused = second.get(10, TimeUnit.SECONDS)

        then:
        withdrawn instanceof PoolPeople.PoolPersonPanel
        refused instanceof ApiErrorException
        refused.errorCode() == RefusalCode.LAST_ESTATE_ROLE_GRANTOR

        and:
        text("select subject_id::text from estate_role_grants where role = 'steward' and removed_at is null") == PERSON
    }

    /**
     * Two stewards withdrawing the role from each other: whichever lands first takes it from the
     * other's caller, so the second is refused for its caller no longer holding the act — the estate
     * keeps a steward either way.
     */
    def "two stewards withdrawing the role from each other at once leave one, the second refused its act"() {
        given:
        person(PERSON, "000535", "Grace Hopper")
        stay(PERSON)
        grant(PERSON, "steward")
        repeatableReadByDefault()
        def held = holding("select 1 from estate_role_grants where role = 'steward' and removed_at is null for update")

        when:
        def first = attempting { changes.withdraw(subjectId(FIRST_STEWARD), EstateRole.STEWARD, new UserId("000535")) }
        untilWaiting(1)
        def second = attempting { changes.withdraw(subjectId(PERSON), EstateRole.STEWARD, STEWARD) }
        untilWaiting(2)
        held.commit()
        held.close()
        def withdrawn = first.get(10, TimeUnit.SECONDS)
        def refused = second.get(10, TimeUnit.SECONDS)

        then:
        withdrawn instanceof PoolPeople.PoolPersonPanel
        refused instanceof ApiErrorException
        refused.errorCode() == RefusalCode.ACT_NOT_PERMITTED

        and:
        text("select subject_id::text from estate_role_grants where role = 'steward' and removed_at is null") == PERSON
    }

    /**
     * The caller holds the act when the change starts and loses it while the change waits on its
     * locks, the withdrawal committing before the change goes on. What was asked at the gate no longer
     * holds, and under the default this database has only a read made after the wait can say so.
     */
    def "a change whose caller stops holding its act while it waits on its locks is refused, changing nothing"() {
        given:
        person(OTHER, "000540", "Grace Hopper")
        stay(OTHER)
        grant(OTHER, "steward")
        person(PERSON, "000541", "Ada Lovelace")
        stay(PERSON)
        person(RETURNING, "000542", "Alan Turing")
        endedStay(RETURNING)
        inDirectory("000542", "Alan Turing")
        repeatableReadByDefault()
        def held = holding(change == "bringIn"
                ? "select 1 from subjects where user_id = '000542' for update"
                : "select 1 from pool_members where subject_id = '${PERSON}' and removed_at is null for update")

        when:
        def changing = attempting { attemptAs(change, new UserId("000540")) }
        untilWaiting(1)
        session.sql("""
                update estate_role_grants set removed_at = now(), removed_by = ?::uuid
                 where subject_id = ?::uuid and role = 'steward'
                """).params(FIRST_STEWARD, OTHER).update()
        def before = contents()
        held.commit()
        held.close()
        def outcome = changing.get(10, TimeUnit.SECONDS)

        then:
        outcome instanceof ApiErrorException
        outcome.errorCode() == RefusalCode.ACT_NOT_PERMITTED

        and:
        contents() == before

        where:
        change << ["bringIn", "remove", "grant", "withdraw"]
    }

    /**
     * Whether the caller still holds the act is asked before whether anybody is there to change, so a
     * caller who no longer holds it is told that, and nothing about who is in the pool.
     */
    def "a caller no longer holding the act is refused for that, whether or not the person is in the pool"() {
        given:
        person(OTHER, "000540", "Grace Hopper")
        stay(OTHER)
        person(PERSON, "000541", "Removed Person")
        endedStay(PERSON)
        def before = contents()

        when:
        attemptAs(change, new UserId("000540"), subjectId(address))

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.ACT_NOT_PERMITTED

        and:
        contents() == before

        where:
        [change, address] << [["remove", "grant", "withdraw"], [PERSON, "00000009-0000-4000-8000-000000000009", OTHER]]
                .combinations()
    }

    private Object attemptAs(String change, UserId caller, SubjectId subject) {
        switch (change) {
            case "remove": return changes.remove(subject, caller)
            case "grant": return changes.grant(subject, EstateRole.WATCHER, caller)
            case "withdraw": return changes.withdraw(subject, EstateRole.WATCHER, caller)
        }
        throw new IllegalArgumentException("No change is called " + change)
    }

    private Object attemptAs(String change, UserId caller) {
        switch (change) {
            case "bringIn": return changes.bringIn(new UserId("000542"), caller)
            case "remove": return changes.remove(subjectId(PERSON), caller)
            case "grant": return changes.grant(subjectId(PERSON), EstateRole.WATCHER, caller)
            case "withdraw": return changes.withdraw(subjectId(PERSON), EstateRole.WATCHER, caller)
        }
        throw new IllegalArgumentException("No change is called " + change)
    }

    private Object attempt(String change, SubjectId subject) {
        switch (change) {
            case "remove": return changes.remove(subject, STEWARD)
            case "grant": return changes.grant(subject, EstateRole.WATCHER, STEWARD)
            case "withdraw": return changes.withdraw(subject, EstateRole.STEWARD, STEWARD)
        }
        throw new IllegalArgumentException("No change is called " + change)
    }

    /** What the change answered, or what it threw, taken on a thread of its own. */
    private Future<Object> attempting(Closure change) {
        racing.submit({
            try {
                change()
            } catch (Throwable thrown) {
                thrown
            }
        } as Callable<Object>)
    }

    /** A transaction of the test's own, left open holding whatever the statement took. */
    private Connection holding(String statement) {
        def connection = database.connection
        connection.autoCommit = false
        connection.createStatement().withCloseable { it.execute(statement) }
        connection
    }

    private void untilWaiting(int sessions) {
        def deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (count("""
                select count(*) from pg_stat_activity
                 where datname = current_database() and wait_event_type = 'Lock'
                """) < sessions) {
            assert System.nanoTime() < deadline: "fewer than ${sessions} sessions ever waited on a lock"
            Thread.sleep(10)
        }
    }

    private void repeatableReadByDefault() {
        session.sql("select current_database()").query(String).single().with { name ->
            session.sql("alter database \"${name}\" set default_transaction_isolation = 'repeatable read'").update()
        }
        assert text("show default_transaction_isolation") == "repeatable read"
    }

    private String contents() {
        WRITTEN_TO.collect { digestOf(it) }.join(" ")
    }

    private String digestOf(String table) {
        text("select coalesce(md5(string_agg(t::text, '|' order by t::text)), 'empty') from ${table} t")
    }

    private String text(String query, SubjectId subject = null) {
        (subject == null ? session.sql(query) : session.sql(query).param(subject.value().toString()))
                .query(String).single()
    }

    /** Every value the query reads, a null included, which {@code single()} refuses. */
    private List<String> texts(String query, SubjectId subject = null) {
        (subject == null ? session.sql(query) : session.sql(query).param(subject.value().toString()))
                .query(String).list()
    }

    private long count(String query, SubjectId subject = null) {
        (subject == null ? session.sql(query) : session.sql(query).param(subject.value().toString()))
                .query(Long).single()
    }

    private static SubjectId subjectId(String spelled) {
        new SubjectId(UUID.fromString(spelled))
    }

    private void inDirectory(String user, String name) {
        session.sql("insert into people (user_id, display_name) values (?, ?)").params(user, name).update()
    }

    private void person(String subject, String user, String name) {
        session.sql("""
                insert into subjects (subject_id, kind, user_id, display_name, created_by)
                values (?::uuid, 'person', ?, ?, ?::uuid)
                """).params(subject, user, name, SEEDER).update()
    }

    private void stay(String subject) {
        session.sql("insert into pool_members (subject_id, created_by) values (?::uuid, ?::uuid)")
                .params(subject, FIRST_STEWARD).update()
    }

    private void endedStay(String subject) {
        session.sql("""
                insert into pool_members (subject_id, created_by, removed_at, removed_by)
                values (?::uuid, ?::uuid, now(), ?::uuid)
                """).params(subject, FIRST_STEWARD, FIRST_STEWARD).update()
    }

    private void grant(String subject, String role) {
        session.sql("insert into estate_role_grants (subject_id, role, created_by) values (?::uuid, ?::estate_role, ?::uuid)")
                .params(subject, role, FIRST_STEWARD).update()
    }

    private void endedGrant(String subject, String role) {
        session.sql("""
                insert into estate_role_grants (subject_id, role, created_by, removed_at, removed_by)
                values (?::uuid, ?::estate_role, ?::uuid, now(), ?::uuid)
                """).params(subject, role, FIRST_STEWARD, FIRST_STEWARD).update()
    }

    private void member(String subject) {
        payroll()
        session.sql("""
                insert into group_members (group_id, subject_id, role, created_by)
                values (?::uuid, ?::uuid, 'operator', ?::uuid)
                """).params(GROUP, subject, FIRST_STEWARD).update()
    }

    private void endedMember(String subject) {
        payroll()
        session.sql("""
                insert into group_members (group_id, subject_id, role, created_by, removed_at, removed_by, removal)
                values (?::uuid, ?::uuid, 'operator', ?::uuid, now(), ?::uuid, 'removed_from_group')
                """).params(GROUP, subject, FIRST_STEWARD, FIRST_STEWARD).update()
    }

    private void payroll() {
        session.sql("insert into groups (group_id, key, name, created_by) values (?::uuid, 'PAYROLL', 'Payroll', ?::uuid)")
                .params(GROUP, FIRST_STEWARD).update()
    }

    private void administer(String statement) {
        server.postgresDatabase.connection.withCloseable { connection ->
            connection.createStatement().withCloseable { it.execute(statement) }
        }
    }
}
