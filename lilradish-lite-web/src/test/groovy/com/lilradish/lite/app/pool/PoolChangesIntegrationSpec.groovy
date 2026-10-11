package com.lilradish.lite.app.pool

import com.lilradish.lite.app.change.ChangeTransactions
import com.lilradish.lite.app.estate.EstateRoleGrants
import com.lilradish.lite.domain.failure.RefusalCode
import com.lilradish.lite.domain.identity.EstateRole
import com.lilradish.lite.domain.identity.SubjectId
import com.lilradish.lite.domain.identity.SystemPrincipal
import com.lilradish.lite.domain.identity.UserId
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.Baseline
import java.sql.Connection
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import org.libprunus.core.error.ApiErrorException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/** Changes must use Read Committed and read pool holdings again after waiting for their lock. */
class PoolChangesIntegrationSpec extends Specification {

    // DB-SPECIFIC: the SQL fixtures and predicates below input/compare PostgreSQL native
    // subject_kind, estate_role, group_role and group_member_removal labels; text || non-string
    // renders estate_role labels in the SQL assertions.

    static final String SEEDER = "00000000-0000-4000-8000-000000000000"

    static final String SYSTEM_ACTOR = SystemPrincipal.WORKFLOW_RUNNER.subject().value().toString()


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

    static final String MIXED_WHITESPACE_NAME = NO_BREAK_SPACE + "Ari" + IDEOGRAPHIC_SPACE + Character.toString(0x2028) +
            " Mockvale" + Character.toString(0x202F)

    static final String ONLY_WHITESPACE_NAME = IDEOGRAPHIC_SPACE + " " + NO_BREAK_SPACE

    @Shared
    @AutoCleanup
    TestPostgres server = TestPostgres.worker()

    @Shared
    int databasesMade = 0

    DataSource database

    JdbcClient session

    PoolChanges changes

    @AutoCleanup("shutdownNow")
    ExecutorService racing = Executors.newFixedThreadPool(2)

    def setupSpec() {
        server.aliasWorker("baseline_applied")
        Baseline.appliedTo(server, "baseline_applied")
        server.rememberBaseline()
    }

    def setup() {
        def name = "changes_" + (++databasesMade)
        server.beginLibraryCase(name, specificationContext.currentFeature.testTags*.value.contains("postgres-schema-changing"))
        database = Baseline.appliedTo(server, name)
        session = JdbcClient.create(database)
        changes = new PoolChanges(session, new ChangeTransactions().readCommitted(new DataSourceTransactionManager(database)),
                new PoolPeople(session), new EstateRoleGrants(session))
    }

    def "brings somebody in the directory into the pool as a subject of their own, under the name the directory holds"() {
        given:
        inDirectory("000501", "Ari Mockvale")

        when:
        def panel = changes.bringIn(new UserId("000501"), STEWARD)

        then:
        panel.userId().value() == "000501"
        panel.displayName().value() == "Ari Mockvale"
        panel.estateRoles().isEmpty()
        panel.groups().isEmpty()
        !panel.seeded()

        and: "a person the caller made, in the pool by the caller's act"
        // DB-SPECIFIC: PostgreSQL :: casts to text, uuid.
        text("select kind::text from subjects where subject_id = ?::uuid", panel.subjectId()) == "person"
        // DB-SPECIFIC: PostgreSQL :: casts to text, uuid.
        text("select created_by::text from subjects where subject_id = ?::uuid", panel.subjectId()) == FIRST_STEWARD
        // DB-SPECIFIC: PostgreSQL :: casts to text, uuid.
        text("select created_by::text from pool_members where subject_id = ?::uuid and removed_at is null",
                panel.subjectId()) == FIRST_STEWARD

        and: "and nothing else done for them: no role, no group, one stay"
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        count("select count(*) from estate_role_grants where subject_id = ?::uuid", panel.subjectId()) == 0
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        count("select count(*) from group_members where subject_id = ?::uuid", panel.subjectId()) == 0
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        count("select count(*) from pool_members where subject_id = ?::uuid", panel.subjectId()) == 1
    }


    def "brings somebody in under the name the directory holds spaced once, and under none where it holds only whitespace"() {
        given:
        if (returning) {
            person(PERSON, "000507", "Ari Mocka")
            endedStay(PERSON)
        }
        inDirectory("000507", held)

        when:
        def panel = changes.bringIn(new UserId("000507"), STEWARD)

        then:
        panel.displayName()?.value() == spaced
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        texts("select display_name from subjects where subject_id = ?::uuid", panel.subjectId()) == [spaced]

        and: "one subject for them, and the directory's own row left as it was held"
        count("select count(*) from subjects where user_id = '000507'") == 1
        texts("select display_name from people where user_id = '000507'") == [held]

        where:
        held                  | returning || spaced
        MIXED_WHITESPACE_NAME | false     || "Ari Mockvale"
        MIXED_WHITESPACE_NAME | true      || "Ari Mockvale"
        ONLY_WHITESPACE_NAME  | false     || null
        ONLY_WHITESPACE_NAME  | true      || null
    }

    /** Returning people reuse their subject and its history, with the directory's current name. */
    def "brings back somebody taken out as the subject they were, under the name the directory holds now"() {
        given:
        person(PERSON, "000502", "Ari Mocka")
        endedStay(PERSON)
        inDirectory("000502", "Ari Mockvale")

        when:
        def panel = changes.bringIn(new UserId("000502"), STEWARD)

        then:
        panel.subjectId() == subjectId(PERSON)
        panel.displayName().value() == "Ari Mockvale"

        and: "one subject for them still, made by whoever made it first"
        count("select count(*) from subjects where user_id = '000502'") == 1
        // DB-SPECIFIC: PostgreSQL :: casts to text, uuid.
        text("select created_by::text from subjects where subject_id = ?::uuid", subjectId(PERSON)) == SEEDER

        and: "the stay that ended still ended beside the one begun"
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        count("select count(*) from pool_members where subject_id = ?::uuid and removed_at is not null",
                subjectId(PERSON)) == 1
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        count("select count(*) from pool_members where subject_id = ?::uuid and removed_at is null",
                subjectId(PERSON)) == 1
    }


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

    /** Uniqueness decides a competing insert only after the first transaction ends. */
    def "an arrival racing another for the same person is decided by the index allowing one current stay"() {
        given:
        person(PERSON, "000505", "Ari Mockvale")
        inDirectory("000505", "Ari Mockvale")
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
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        count("select count(*) from pool_members where subject_id = ?::uuid and removed_at is null",
                subjectId(PERSON)) == 1

        where:
        otherLands || refusal
        true       || RefusalCode.PERSON_ALREADY_IN_POOL
        false      || null
    }

    def "a caller no subject answers for is refused by the store rather than recorded as nobody"() {
        given:
        inDirectory("000506", "Ari Mockvale")
        def before = contents()

        when:
        changes.bringIn(new UserId("000506"), new UserId("000999"))

        then:
        thrown(DataIntegrityViolationException)

        and:
        contents() == before
    }


    def "takes somebody holding nothing out of the pool as the caller's act, keeping the subject their acts name"() {
        given:
        person(PERSON, "000511", "Ari Mockvale")
        stay(PERSON)

        when:
        changes.remove(subjectId(PERSON), STEWARD)

        then:
        // DB-SPECIFIC: PostgreSQL :: casts to text, uuid.
        text("select removed_by::text from pool_members where subject_id = ?::uuid", subjectId(PERSON)) == FIRST_STEWARD
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        count("select count(*) from pool_members where subject_id = ?::uuid and removed_at is null",
                subjectId(PERSON)) == 0

        and: "the subject still there, and nobody else's stay touched"
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        count("select count(*) from subjects where subject_id = ?::uuid", subjectId(PERSON)) == 1
        count("select count(*) from pool_members where removed_at is null") == 1
    }

    /** The transaction begins before the stay is first read, so a stay committed in between is the one closed. */
    def "a stay is closed when the change's transaction began, or when it was made if that was later"() {
        given:
        person(PERSON, "000516", "Ari Mockvale")
        stay(PERSON)
        // DB-SPECIFIC: PostgreSQL :: casts to interval, uuid; now().
        session.sql("update pool_members set created_at = now() + ?::interval where subject_id = ?::uuid")
                .params(offset, PERSON).update()
        def others = (WRITTEN_TO - "pool_members").collectEntries { [it, digestOf(it)] }

        when:
        changes.remove(subjectId(PERSON), STEWARD)

        then:
        // DB-SPECIFIC: PostgreSQL :: casts to text, uuid.
        texts("""
                select removed_by::text || ' ' || case when removed_at = created_at then 'when made'
                                                       when removed_at > created_at then 'after made' end
                  from pool_members where subject_id = ?::uuid
                """, subjectId(PERSON)) == ["${FIRST_STEWARD} ${closed}" as String]

        and: "only that stay closed, and nothing else written"
        // DB-SPECIFIC: PostgreSQL :: casts to text.
        texts("select subject_id::text from pool_members where removed_at is null") == [FIRST_STEWARD]
        others.every { table, digest -> digestOf(table) == digest }

        where:
        offset    || closed
        "-1 hour" || "after made"
        "+1 hour" || "when made"
    }


    def "refuses to take out somebody still holding what has to go first, changing nothing"() {
        given:
        person(PERSON, "000512", "Ari Mockvale")
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


    def "takes out somebody whose estate grant and group membership have both ended, leaving that history as it was"() {
        given:
        person(PERSON, "000513", "Ari Mockvale")
        stay(PERSON)
        endedGrant(PERSON, "watcher")
        endedMember(PERSON)
        def history = ["estate_role_grants", "groups", "group_members"].collectEntries { [it, digestOf(it)] }

        when:
        changes.remove(subjectId(PERSON), STEWARD)

        then:
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        count("select count(*) from pool_members where subject_id = ?::uuid and removed_at is null",
                subjectId(PERSON)) == 0

        and: "the ended grant and membership are neither reopened nor rewritten"
        history.every { table, digest -> digestOf(table) == digest }
    }


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

    /** Read the pool stay again after waiting for its lock. */
    @spock.lang.Tag("postgres-schema-changing")
    def "a removal waiting behind a grant for one person reads the grant, and is refused"() {
        given:
        person(PERSON, "000515", "Ari Mockvale")
        stay(PERSON)
        repeatableReadByDefault()
        // DB-SPECIFIC: PostgreSQL SELECT FOR UPDATE.
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
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        count("""
                select count(*) from estate_role_grants holding
                 where holding.subject_id = ?::uuid and holding.removed_at is null
                   and not exists (select 1 from pool_members stay
                                    where stay.subject_id = holding.subject_id and stay.removed_at is null)
                """, subjectId(PERSON)) == 0
    }

    def "grants a role to somebody in the pool as the caller's act, and answers with what they hold now"() {
        given:
        person(PERSON, "000521", "Ari Mockvale")
        stay(PERSON)

        when:
        def panel = changes.grant(subjectId(PERSON), EstateRole.WATCHER, STEWARD)

        then:
        panel.subjectId() == subjectId(PERSON)
        panel.estateRoles() == [EstateRole.WATCHER] as Set
        // DB-SPECIFIC: PostgreSQL :: casts to text, uuid.
        text("select created_by::text from estate_role_grants where subject_id = ?::uuid", subjectId(PERSON)) ==
                FIRST_STEWARD

        and: "that role and no other, recorded once"
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        count("select count(*) from estate_role_grants where subject_id = ?::uuid", subjectId(PERSON)) == 1
    }


    def "granting a role already held records nothing and answers alike"() {
        given:
        person(PERSON, "000522", "Ari Mockvale")
        stay(PERSON)
        def first = changes.grant(subjectId(PERSON), EstateRole.WATCHER, STEWARD)
        def before = contents()

        when:
        def again = changes.grant(subjectId(PERSON), EstateRole.WATCHER, STEWARD)

        then:
        again == first
        contents() == before
    }


    def "a caller granting themselves the watcher role holds it, by an act recorded as their own"() {
        when:
        def panel = changes.grant(subjectId(FIRST_STEWARD), EstateRole.WATCHER, STEWARD)

        then:
        panel.estateRoles() == [EstateRole.STEWARD, EstateRole.WATCHER] as Set
        // DB-SPECIFIC: PostgreSQL :: casts to text, uuid.
        text("select created_by::text from estate_role_grants where subject_id = ?::uuid and role = 'watcher'",
                subjectId(FIRST_STEWARD)) == FIRST_STEWARD
    }

    def "withdraws a role somebody holds as the caller's act, and answers with what they still hold"() {
        given:
        person(PERSON, "000531", "Ari Mockvale")
        stay(PERSON)
        grant(PERSON, "watcher")
        grant(PERSON, "steward")

        when:
        def panel = changes.withdraw(subjectId(PERSON), EstateRole.WATCHER, STEWARD)

        then:
        panel.estateRoles() == [EstateRole.STEWARD] as Set
        // DB-SPECIFIC: PostgreSQL :: casts to text, uuid.
        text("select removed_by::text from estate_role_grants where subject_id = ?::uuid and role = 'watcher'",
                subjectId(PERSON)) == FIRST_STEWARD

        and: "the grant withdrawn still on record, and the other untouched"
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        count("select count(*) from estate_role_grants where subject_id = ?::uuid", subjectId(PERSON)) == 2
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        count("select count(*) from estate_role_grants where subject_id = ?::uuid and removed_at is null",
                subjectId(PERSON)) == 1
    }

    /** It waits on the person's stay lock, which a grant committing first held, so the grant can be younger than it. */
    def "a grant is withdrawn when the change's transaction began, or when it was made if that was later"() {
        given:
        person(PERSON, "000537", "Ari Mockvale")
        stay(PERSON)
        grant(PERSON, "watcher")
        // DB-SPECIFIC: PostgreSQL :: casts to interval, uuid; now().
        session.sql("update estate_role_grants set created_at = now() + ?::interval where subject_id = ?::uuid")
                .params(offset, PERSON).update()
        def others = (WRITTEN_TO - "estate_role_grants").collectEntries { [it, digestOf(it)] }

        when:
        changes.withdraw(subjectId(PERSON), EstateRole.WATCHER, STEWARD)

        then:
        // DB-SPECIFIC: PostgreSQL :: casts to text, uuid.
        texts("""
                select removed_by::text || ' ' || case when removed_at = created_at then 'when made'
                                                       when removed_at > created_at then 'after made' end
                  from estate_role_grants where subject_id = ?::uuid and role = 'watcher'
                """, subjectId(PERSON)) == ["${FIRST_STEWARD} ${closed}" as String]

        and: "only that grant closed, and nothing else written"
        // DB-SPECIFIC: PostgreSQL :: casts to text.
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
        person(PERSON, "000532", "Ari Mockvale")
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
        person(PERSON, "000533", "Greno Nockal")
        stay(PERSON)
        grant(PERSON, "steward")

        when:
        def panel = changes.withdraw(subjectId(FIRST_STEWARD), EstateRole.STEWARD, new UserId("000533"))

        then:
        panel.estateRoles().isEmpty()
        panel.lastGrantingRoles().isEmpty()
        // DB-SPECIFIC: PostgreSQL :: casts to text.
        text("select subject_id::text from estate_role_grants where role = 'steward' and removed_at is null") == PERSON
    }


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
        person(OTHER, "000536", "Greno Nockal")
        endedStay(OTHER)
        grant(OTHER, "steward")

        when:
        def panel = changes.withdraw(subjectId(FIRST_STEWARD), EstateRole.STEWARD, STEWARD)

        then:
        panel.estateRoles().isEmpty()
        panel.lastGrantingRoles().isEmpty()

        and:
        // DB-SPECIFIC: PostgreSQL :: casts to text.
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

    /** Steward changes must serialize so they cannot remove the last steward. */
    @spock.lang.Tag("postgres-schema-changing")
    def "the two last stewards withdrawing their own role at once leave one, the second refused as the last"() {
        given:
        person(PERSON, "000534", "Greno Nockal")
        stay(PERSON)
        grant(PERSON, "steward")
        repeatableReadByDefault()
        // DB-SPECIFIC: PostgreSQL SELECT FOR UPDATE.
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
        // DB-SPECIFIC: PostgreSQL :: casts to text.
        text("select subject_id::text from estate_role_grants where role = 'steward' and removed_at is null") == PERSON
    }


    @spock.lang.Tag("postgres-schema-changing")
    def "two stewards withdrawing the role from each other at once leave one, the second refused its act"() {
        given:
        person(PERSON, "000535", "Greno Nockal")
        stay(PERSON)
        grant(PERSON, "steward")
        repeatableReadByDefault()
        // DB-SPECIFIC: PostgreSQL SELECT FOR UPDATE.
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
        // DB-SPECIFIC: PostgreSQL :: casts to text.
        text("select subject_id::text from estate_role_grants where role = 'steward' and removed_at is null") == PERSON
    }

    /** Authorization must be read again after waiting for the pool lock. */
    @spock.lang.Tag("postgres-schema-changing")
    def "a change whose caller stops holding its act while it waits on its locks is refused, changing nothing"() {
        given:
        person(OTHER, "000540", "Greno Nockal")
        stay(OTHER)
        grant(OTHER, "steward")
        person(PERSON, "000541", "Ari Mockvale")
        stay(PERSON)
        person(RETURNING, "000542", "Arlo Taffan")
        endedStay(RETURNING)
        inDirectory("000542", "Arlo Taffan")
        repeatableReadByDefault()
        def held = holding(change == "bringIn"
                // DB-SPECIFIC: PostgreSQL SELECT FOR UPDATE.
                ? "select 1 from subjects where user_id = '000542' for update"
                // DB-SPECIFIC: PostgreSQL SELECT FOR UPDATE.
                : "select 1 from pool_members where subject_id = '${PERSON}' and removed_at is null for update")

        when:
        def changing = attempting { attemptAs(change, new UserId("000540")) }
        untilWaiting(1)
        // DB-SPECIFIC: PostgreSQL :: casts to uuid; now().
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

    /** Check authorization before revealing whether the person exists. */
    def "a caller no longer holding the act is refused for that, whether or not the person is in the pool"() {
        given:
        person(OTHER, "000540", "Greno Nockal")
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


    private Future<Object> attempting(Closure change) {
        racing.submit({
            try {
                change()
            } catch (Throwable thrown) {
                thrown
            }
        } as Callable<Object>)
    }


    private Connection holding(String statement) {
        def connection = database.connection
        connection.autoCommit = false
        connection.createStatement().withCloseable { it.execute(statement) }
        connection
    }

    private void untilWaiting(int sessions) {
        def deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        // DB-SPECIFIC: PostgreSQL pg_stat_activity; current_database().
        while (count("""
                select count(*) from pg_stat_activity
                 where datname = current_database() and wait_event_type = 'Lock'
                """) < sessions) {
            assert System.nanoTime() < deadline: "fewer than ${sessions} sessions ever waited on a lock"
            Thread.sleep(10)
        }
    }

    private void repeatableReadByDefault() {
        // DB-SPECIFIC: PostgreSQL current_database().
        session.sql("select current_database()").query(String).single().with { name ->
            // DB-SPECIFIC: PostgreSQL ALTER DATABASE settings.
            session.sql("alter database \"${name}\" set default_transaction_isolation = 'repeatable read'").update()
        }
        // DB-SPECIFIC: PostgreSQL SHOW default_transaction_isolation.
        assert text("show default_transaction_isolation") == "repeatable read"
    }

    private String contents() {
        WRITTEN_TO.collect { digestOf(it) }.join(" ")
    }

    private String digestOf(String table) {
        // DB-SPECIFIC: PostgreSQL :: casts to text; md5(), string_agg().
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
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        session.sql("""
                insert into subjects (subject_id, kind, user_id, display_name, created_by)
                values (?::uuid, 'person', ?, ?, ?::uuid)
                """).params(subject, user, name, SEEDER).update()
    }

    private void stay(String subject) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        session.sql("insert into pool_members (subject_id, created_by) values (?::uuid, ?::uuid)")
                .params(subject, FIRST_STEWARD).update()
    }

    private void endedStay(String subject) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid; now().
        session.sql("""
                insert into pool_members (subject_id, created_by, removed_at, removed_by)
                values (?::uuid, ?::uuid, now(), ?::uuid)
                """).params(subject, FIRST_STEWARD, FIRST_STEWARD).update()
    }

    private void grant(String subject, String role) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid, estate_role.
        session.sql("insert into estate_role_grants (subject_id, role, created_by) values (?::uuid, ?::estate_role, ?::uuid)")
                .params(subject, role, FIRST_STEWARD).update()
    }

    private void endedGrant(String subject, String role) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid, estate_role; now().
        session.sql("""
                insert into estate_role_grants (subject_id, role, created_by, removed_at, removed_by)
                values (?::uuid, ?::estate_role, ?::uuid, now(), ?::uuid)
                """).params(subject, role, FIRST_STEWARD, FIRST_STEWARD).update()
    }

    private void member(String subject) {
        payroll()
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        session.sql("""
                insert into group_members (group_id, subject_id, role, created_by)
                values (?::uuid, ?::uuid, 'operator', ?::uuid)
                """).params(GROUP, subject, FIRST_STEWARD).update()
    }

    private void endedMember(String subject) {
        payroll()
        // DB-SPECIFIC: PostgreSQL :: casts to uuid; now().
        session.sql("""
                insert into group_members (group_id, subject_id, role, created_by, removed_at, removed_by, removal, removed_position)
                values (?::uuid, ?::uuid, 'operator', ?::uuid, now(), ?::uuid, 'removed_from_group', 1)
                """).params(GROUP, subject, FIRST_STEWARD, FIRST_STEWARD).update()
    }

    private void payroll() {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        session.sql("insert into groups (group_id, key, name, created_by) values (?::uuid, 'PAYROLL', 'Payroll', ?::uuid)")
                .params(GROUP, FIRST_STEWARD).update()
    }

}
