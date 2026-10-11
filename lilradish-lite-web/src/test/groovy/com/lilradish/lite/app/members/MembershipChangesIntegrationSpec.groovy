package com.lilradish.lite.app.members

import com.lilradish.lite.app.change.ChangeTransactions
import com.lilradish.lite.app.group.GroupRoles
import com.lilradish.lite.app.pool.PoolStays
import com.lilradish.lite.domain.failure.RefusalCode
import com.lilradish.lite.domain.identity.GroupId
import com.lilradish.lite.domain.identity.GroupRole
import com.lilradish.lite.domain.identity.SubjectId
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
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/** Changes must use Read Committed and read membership again after waiting for its lock. */
class MembershipChangesIntegrationSpec extends Specification {

    // DB-SPECIFIC: SQL concatenations below use PostgreSQL's text || non-string overload
    // to render UUID authors and native group_member_removal labels.

    static final String SEEDER = "00000000-0000-4000-8000-000000000000"

    static final String FIRST_STEWARD = "00000001-0000-4000-8000-000000000001"

    static final String PAYROLL = "00000003-0000-4000-8000-000000000d01"

    static final String TRIAGE = "00000003-0000-4000-8000-000000000d02"


    static final String ADA = "00000002-0000-4000-8000-000000000d01"

    static final UserId ADA_USER = new UserId("000d01")


    static final String GRACE = "00000002-0000-4000-8000-000000000d02"

    static final UserId GRACE_USER = new UserId("000d02")


    static final String OLIVE = "00000002-0000-4000-8000-000000000d03"

    static final UserId OLIVE_USER = new UserId("000d03")


    static final String LINUS = "00000002-0000-4000-8000-000000000d04"


    static final String GONE = "00000002-0000-4000-8000-000000000d05"


    static final String TAKEN_AND_GONE = "00000002-0000-4000-8000-000000000d06"

    static final List<String> WRITTEN_TO = ["subjects", "pool_members", "groups", "group_members"]

    static final String EARLIER_OF_LINUS =
            "(select * from group_members where subject_id = '${LINUS}' and removed_at is not null)"

    @Shared
    @AutoCleanup
    TestPostgres server = TestPostgres.worker()

    @Shared
    int databasesMade = 0

    DataSource database

    JdbcClient session

    MembershipChanges changes

    @AutoCleanup("shutdownNow")
    ExecutorService racing = Executors.newFixedThreadPool(3)

    def setupSpec() {
        server.aliasWorker("baseline_applied")
        Baseline.appliedTo(server, "baseline_applied")
        server.rememberBaseline()
    }

    def setup() {
        def name = "memberships_" + (++databasesMade)
        server.beginLibraryCase(name, specificationContext.currentFeature.testTags*.value.contains("postgres-schema-changing"))
        database = Baseline.appliedTo(server, name)
        session = JdbcClient.create(database)
        changes = new MembershipChanges(session, transactions(), new Members(session), new PoolStays(session),
                new GroupRoles(session))
        pooled(ADA, "000d01", "Ari Mockvale")
        pooled(GRACE, "000d02", "Greno Nockal")
        pooled(OLIVE, "000d03", "Olive Out")
        pooled(LINUS, "000d04", "Linus Left")
        pooled(GONE, "000d05", "Gone Away")
        endedStay(GONE)
        group(PAYROLL, "PAYROLL", "Payroll")
        group(TRIAGE, "TRIAGE", "Triage")
        member(PAYROLL, ADA, "owner")
        member(PAYROLL, GRACE, "operator")
        member(TRIAGE, OLIVE, "owner")
        member(PAYROLL, LINUS, "operator")
        closed(PAYROLL, LINUS, "role_taken")
    }


    def "brings somebody in the pool into the group holding exactly the roles given, as the caller's act"() {
        when:
        def brought = changes.bringIn(groupId(PAYROLL), subjectId(OLIVE), roles, ADA_USER)

        then:
        brought.subjectId() == subjectId(OLIVE)
        brought.roles() == roles

        and: "a row for each role and no other, made by the caller and open"
        openHoldings(PAYROLL, OLIVE) == roles*.name()*.toLowerCase(Locale.ROOT).collect { "${it} ${ADA}" as String }.toSorted()

        and: "nobody else touched, and nothing in any other group"
        openHoldings(PAYROLL, GRACE) == ["operator ${FIRST_STEWARD}" as String]
        openHoldings(TRIAGE, OLIVE) == ["owner ${FIRST_STEWARD}" as String]

        where:
        roles << [EnumSet.of(GroupRole.OPERATOR), EnumSet.of(GroupRole.OPERATOR, GroupRole.OWNER), EnumSet.allOf(GroupRole)]
    }


    def "brings back somebody whose membership ended, the rows of their earlier one left as they were"() {
        given:
        def earlier = digestOf(EARLIER_OF_LINUS)

        when:
        def brought = changes.bringIn(groupId(PAYROLL), subjectId(LINUS), EnumSet.of(GroupRole.OVERSEER), ADA_USER)

        then:
        brought.roles() == EnumSet.of(GroupRole.OVERSEER)
        openHoldings(PAYROLL, LINUS) == ["overseer ${ADA}" as String]

        and:
        digestOf(EARLIER_OF_LINUS) == earlier
    }


    def "refuses to bring in anybody not in the pool, writing nothing"() {
        given:
        def before = contents()

        when:
        changes.bringIn(groupId(PAYROLL), subjectId(named), EnumSet.of(GroupRole.OPERATOR), ADA_USER)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.PERSON_NOT_IN_POOL
        refused.message == "That person is not in the pool."

        and:
        contents() == before

        where:
        named << ["00000009-0000-4000-8000-000000000009", GONE, SEEDER]
    }

    def "refuses to bring in somebody already holding a role here, writing nothing"() {
        given:
        def before = contents()

        when:
        changes.bringIn(groupId(PAYROLL), subjectId(GRACE), EnumSet.of(GroupRole.OVERSEER), ADA_USER)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.PERSON_ALREADY_IN_GROUP
        refused.message == "That person already holds a role in this group."

        and:
        contents() == before
    }

    def "bringing somebody in holding nothing is refused before anything is asked of the store"() {
        given:
        def before = contents()

        when:
        changes.bringIn(groupId(PAYROLL), subjectId(OLIVE), EnumSet.noneOf(GroupRole), ADA_USER)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "MembershipChanges brings nobody in holding nothing"

        and:
        contents() == before
    }

    def "gives a member a role beside the ones they hold, as the caller's act"() {
        when:
        def given = changes.give(groupId(PAYROLL), subjectId(GRACE), GroupRole.OVERSEER, ADA_USER)

        then:
        given.roles() == EnumSet.of(GroupRole.OPERATOR, GroupRole.OVERSEER)

        and:
        openHoldings(PAYROLL, GRACE) == ["operator ${FIRST_STEWARD}" as String, "overseer ${ADA}" as String]
    }

    def "giving a role already held records nothing and answers alike"() {
        given:
        def before = contents()

        when:
        def given = changes.give(groupId(PAYROLL), subjectId(GRACE), GroupRole.OPERATOR, ADA_USER)

        then:
        given.roles() == EnumSet.of(GroupRole.OPERATOR)

        and:
        contents() == before
    }


    def "giving a role to somebody whose last role here was taken makes them a member again"() {
        given:
        if (removedEarlier) {
            formerly(PAYROLL, OLIVE, "overseer", "removed_from_group", 2)
            formerly(PAYROLL, OLIVE, "operator", "role_taken", 1)
        }

        when:
        def given = changes.give(groupId(PAYROLL), subjectId(person), GroupRole.OPERATOR, ADA_USER)

        then:
        given.roles() == EnumSet.of(GroupRole.OPERATOR)
        openHoldings(PAYROLL, person) == ["operator ${ADA}" as String]

        where:
        person | removedEarlier
        LINUS  | false
        OLIVE  | true
    }

    def "giving a role follows the later role-taking act even when the earlier removal has a later wall time"() {
        given:
        formerly(PAYROLL, OLIVE, "operator", "removed_from_group", 2)
        formerly(PAYROLL, OLIVE, "overseer", "role_taken", 1)
        // DB-SPECIFIC: PostgreSQL interval literals and uuid casts.
        session.sql("""
                update group_members set removed_at = removed_at + interval '1 day'
                 where group_id = ?::uuid and subject_id = ?::uuid and role = 'operator'
                """).params(PAYROLL, OLIVE).update()
        def earlier = closedRowsOf(OLIVE)
        def otherGroup = openHoldings(TRIAGE, OLIVE)

        when:
        def given = changes.give(groupId(PAYROLL), subjectId(OLIVE), GroupRole.OPERATOR, ADA_USER)

        then:
        given.roles() == EnumSet.of(GroupRole.OPERATOR)
        openHoldings(PAYROLL, OLIVE) == ["operator ${ADA}" as String]
        closedRowsOf(OLIVE) == earlier
        openHoldings(TRIAGE, OLIVE) == otherGroup
        openHoldings(PAYROLL, GRACE) == ["operator ${FIRST_STEWARD}" as String]
    }

    def "giving a role refuses the later removal even when an earlier role-taking act has a later wall time"() {
        given:
        formerly(PAYROLL, OLIVE, "operator", "role_taken", 2)
        formerly(PAYROLL, OLIVE, "overseer", "removed_from_group", 1)
        // DB-SPECIFIC: PostgreSQL interval literals and uuid casts.
        session.sql("""
                update group_members set removed_at = removed_at + interval '1 day'
                 where group_id = ?::uuid and subject_id = ?::uuid and role = 'operator'
                """).params(PAYROLL, OLIVE).update()
        def before = contents()

        when:
        changes.give(groupId(PAYROLL), subjectId(OLIVE), GroupRole.OPERATOR, ADA_USER)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.MEMBER_NOT_IN_VIEW
        contents() == before
        openHoldings(PAYROLL, OLIVE).isEmpty()
    }

    def "a removal outranks a role taken in the same ending act without permitting a new holding"() {
        given:
        formerly(PAYROLL, OLIVE, "operator", "role_taken", 2)
        formerly(PAYROLL, OLIVE, "overseer", "removed_from_group", 1)
        // DB-SPECIFIC: PostgreSQL uuid casts.
        session.sql("""
                update group_members set removed_position = 3
                 where group_id = ?::uuid and subject_id = ?::uuid
                """).params(PAYROLL, OLIVE).update()
        def before = contents()

        when:
        changes.give(groupId(PAYROLL), subjectId(OLIVE), GroupRole.OPERATOR, ADA_USER)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.MEMBER_NOT_IN_VIEW
        contents() == before
        openHoldings(PAYROLL, OLIVE).isEmpty()
    }

    /** Responses for unseen identities must be indistinguishable. */
    def "refuses to give a role to anybody neither a member nor one whose last role was taken, writing nothing"() {
        given:
        formerly(PAYROLL, OLIVE, "overseer", "role_taken", 2)
        formerly(PAYROLL, OLIVE, "operator", "removed_from_group", 1)
        pooled(TAKEN_AND_GONE, "000d06", "Taken And Gone")
        member(PAYROLL, TAKEN_AND_GONE, "operator")
        closed(PAYROLL, TAKEN_AND_GONE, "role_taken")
        endedStay(TAKEN_AND_GONE)
        def before = contents()

        when:
        changes.give(groupId(PAYROLL), subjectId(named), GroupRole.OPERATOR, ADA_USER)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.MEMBER_NOT_IN_VIEW
        refused.message == "That person is not a member of this group."

        and:
        contents() == before

        where:
        named << [OLIVE, TAKEN_AND_GONE, GONE, "00000009-0000-4000-8000-000000000009", SEEDER]
    }


    def "refuses to give a role to somebody in the pool who was never in the group"() {
        given:
        def before = contents()

        when:
        changes.give(groupId(PAYROLL), subjectId(OLIVE), GroupRole.OPERATOR, ADA_USER)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.MEMBER_NOT_IN_VIEW

        and:
        contents() == before
    }


    def "takes a role from a member, closing its row as a role taken by the caller, and leaving the rest"() {
        given:
        member(PAYROLL, GRACE, "overseer")

        when:
        def taken = changes.take(groupId(PAYROLL), subjectId(GRACE), GroupRole.OPERATOR, ADA_USER)

        then:
        taken.roles() == EnumSet.of(GroupRole.OVERSEER)

        and:
        closedHoldings(PAYROLL, GRACE) == ["operator ${ADA} role_taken" as String]
        openHoldings(PAYROLL, GRACE) == ["overseer ${FIRST_STEWARD}" as String]
    }


    def "taking a member's last role ends their membership, and they are answered holding nothing"() {
        when:
        def taken = changes.take(groupId(PAYROLL), subjectId(GRACE), GroupRole.OPERATOR, ADA_USER)

        then:
        taken.subjectId() == subjectId(GRACE)
        taken.roles().isEmpty()
        taken.lastChangingRoles().isEmpty()

        and:
        openHoldings(PAYROLL, GRACE).isEmpty()
        closedHoldings(PAYROLL, GRACE) == ["operator ${ADA} role_taken" as String]
    }

    def "taking a role a member does not hold records nothing and answers alike"() {
        given:
        def before = contents()

        when:
        def taken = changes.take(groupId(PAYROLL), subjectId(GRACE), GroupRole.OWNER, ADA_USER)

        then:
        taken.roles() == EnumSet.of(GroupRole.OPERATOR)

        and:
        contents() == before
    }


    def "taking a role from anybody holding none here is refused as no member, writing nothing"() {
        given:
        def before = contents()

        when:
        changes.take(groupId(PAYROLL), subjectId(named), GroupRole.OPERATOR, ADA_USER)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.MEMBER_NOT_IN_VIEW

        and:
        contents() == before

        where:
        named << [LINUS, OLIVE, GONE, "00000009-0000-4000-8000-000000000009"]
    }


    def "the last role that lets anybody change the membership is not taken, from its holder or by them"() {
        given:
        def before = contents()

        when:
        changes.take(groupId(PAYROLL), subjectId(ADA), GroupRole.OWNER, ADA_USER)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.LAST_MEMBERSHIP_CHANGER
        refused.message == "Nobody else in this group could change its membership."

        and:
        contents() == before
    }


    def "with two who may change the membership, one takes that role from the other and one is left"() {
        given:
        member(PAYROLL, GRACE, "owner")

        when:
        def taken = changes.take(groupId(PAYROLL), subjectId(GRACE), GroupRole.OWNER, ADA_USER)

        then:
        taken.roles() == EnumSet.of(GroupRole.OPERATOR)

        and:
        openHoldingsOf("owner", PAYROLL) == [ADA]
    }


    def "a member takes their own role that lets them change the membership while another holds one too"() {
        given:
        member(PAYROLL, GRACE, "owner")

        when:
        def taken = changes.take(groupId(PAYROLL), subjectId(GRACE), GroupRole.OWNER, GRACE_USER)

        then:
        taken.roles() == EnumSet.of(GroupRole.OPERATOR)
        closedHoldings(PAYROLL, GRACE) == ["owner ${GRACE} role_taken" as String]
        openHoldingsOf("owner", PAYROLL) == [ADA]
    }


    def "taking a role leaves the rows of an earlier membership as they were"() {
        given:
        def earlier = closedRowsOf(LINUS)
        member(PAYROLL, LINUS, "operator")

        when:
        changes.take(groupId(PAYROLL), subjectId(LINUS), GroupRole.OPERATOR, ADA_USER)

        then:
        closedRowsOf(LINUS).containsAll(earlier)
        closedRowsOf(LINUS).size() == earlier.size() + 1
    }

    /** A change waits on the group's lock, so the transaction closing a row can have begun before it was made. */
    def "a role made after the change's transaction began is closed no earlier than it was made"() {
        given:
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now() and the verbose interval '1 hour' literal.
        session.sql("update group_members set created_at = now() + interval '1 hour' where subject_id = ?::uuid")
                .param(GRACE).update()

        when:
        changes.take(groupId(PAYROLL), subjectId(GRACE), GroupRole.OPERATOR, ADA_USER)

        then:
        count("select count(*) from group_members where subject_id = '${GRACE}' and removed_at = created_at") == 1
    }


    def "removes a member from the group, closing every role they hold as one removal by the caller"() {
        given:
        member(PAYROLL, GRACE, "overseer")
        def earlier = closedRowsOf(LINUS)
        def otherGroup = openHoldings(TRIAGE, OLIVE)

        when:
        changes.remove(groupId(PAYROLL), subjectId(GRACE), ADA_USER)

        then:
        openHoldings(PAYROLL, GRACE).isEmpty()
        closedHoldings(PAYROLL, GRACE) ==
                ["operator ${ADA} removed_from_group" as String, "overseer ${ADA} removed_from_group" as String]
        // DB-SPECIFIC: PostgreSQL uuid casts.
        session.sql("select removed_position from group_members where group_id = ?::uuid and subject_id = ?::uuid")
                .params(PAYROLL, GRACE).query(Long).list() == [2L, 2L]
        closedRowsOf(LINUS) == earlier
        openHoldings(TRIAGE, OLIVE) == otherGroup

        and: "nobody else touched"
        openHoldings(PAYROLL, ADA) == ["owner ${FIRST_STEWARD}" as String]
    }

    def "successive membership endings use the next positions of this group and a no-op spends none"() {
        given:
        formerly(TRIAGE, LINUS, "operator", "role_taken", 100)
        // DB-SPECIFIC: PostgreSQL uuid casts; this other group's ending position is deliberately far ahead.
        assert session.sql("""
                update group_members set removed_position = 100
                 where group_id = ?::uuid and subject_id = ?::uuid and role = 'operator' and removed_at is not null
                """).params(TRIAGE, LINUS).update() == 1
        def otherHistory = digestOf("(select * from group_members where group_id = '${TRIAGE}' and subject_id = '${LINUS}')")
        member(PAYROLL, LINUS, "operator")
        member(PAYROLL, OLIVE, "overseer")
        def earlier = digestOf(EARLIER_OF_LINUS)

        when:
        def first = changes.take(groupId(PAYROLL), subjectId(GRACE), GroupRole.OPERATOR, ADA_USER)

        then:
        first.roles().empty
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        session.sql("select removed_position from group_members where group_id = ?::uuid and subject_id = ?::uuid")
                .params(PAYROLL, GRACE).query(Long).list() == [2L]

        when:
        def beforeNoOp = contents()
        def unchanged = changes.take(groupId(PAYROLL), subjectId(OLIVE), GroupRole.OPERATOR, ADA_USER)

        then:
        unchanged.roles() == EnumSet.of(GroupRole.OVERSEER)
        contents() == beforeNoOp

        when:
        changes.remove(groupId(PAYROLL), subjectId(LINUS), ADA_USER)

        then:
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        session.sql("select removed_position from group_members where group_id = ?::uuid and subject_id = ?::uuid order by removed_position")
                .params(PAYROLL, LINUS).query(Long).list() == [1L, 3L]
        digestOf("(select * from group_members where subject_id = '${LINUS}' and removed_at is not null and removed_position <> 3)") == earlier
        digestOf("(select * from group_members where group_id = '${TRIAGE}' and subject_id = '${LINUS}')") == otherHistory
        openHoldings(PAYROLL, OLIVE) == ["overseer ${FIRST_STEWARD}" as String]
        openHoldings(TRIAGE, OLIVE) == ["owner ${FIRST_STEWARD}" as String]
        count("select count(*) from group_members where removed_at is not null") == 4
    }

    def "two successful endings waiting on the same group lock each take one distinct next position"() {
        given:
        member(PAYROLL, OLIVE, "overseer")
        formerly(TRIAGE, LINUS, "operator", "role_taken", 100)
        // DB-SPECIFIC: PostgreSQL uuid casts; this other group's ending position is deliberately far ahead.
        assert session.sql("""
                update group_members set removed_position = 100
                 where group_id = ?::uuid and subject_id = ?::uuid and role = 'operator' and removed_at is not null
                """).params(TRIAGE, LINUS).update() == 1
        def otherHistory = digestOf("(select * from group_members where group_id = '${TRIAGE}' and subject_id = '${LINUS}')")
        def earlier = digestOf(EARLIER_OF_LINUS)
        // DB-SPECIFIC: PostgreSQL SELECT FOR NO KEY UPDATE.
        def held = holding("select 1 from groups where group_id = '${PAYROLL}' for no key update")

        when:
        def first = attempting { changes.take(groupId(PAYROLL), subjectId(GRACE), GroupRole.OPERATOR, ADA_USER) }
        def second = attempting { changes.remove(groupId(PAYROLL), subjectId(OLIVE), ADA_USER) }
        untilWaiting(2)
        held.commit()
        held.close()
        def outcomes = [first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)]

        then:
        outcomes.every { !(it instanceof Throwable) }
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        session.sql("""
                select removed_position from group_members
                 where group_id = ?::uuid and subject_id in (?::uuid, ?::uuid)
                 order by removed_position
                """).params(PAYROLL, GRACE, OLIVE).query(Long).list() == [2L, 3L]
        digestOf(EARLIER_OF_LINUS) == earlier
        digestOf("(select * from group_members where group_id = '${TRIAGE}' and subject_id = '${LINUS}')") == otherHistory
        openHoldings(PAYROLL, GRACE).empty
        openHoldings(PAYROLL, OLIVE).empty
        openHoldings(PAYROLL, ADA) == ["owner ${FIRST_STEWARD}" as String]
        openHoldings(TRIAGE, OLIVE) == ["owner ${FIRST_STEWARD}" as String]
        count("select count(*) from group_members where removed_at is not null") == 4

        cleanup:
        held?.close()
    }

    def "removing anybody holding no role here is refused as no member, writing nothing"() {
        given:
        def before = contents()

        when:
        changes.remove(groupId(PAYROLL), subjectId(named), ADA_USER)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.MEMBER_NOT_IN_VIEW

        and:
        contents() == before

        where:
        named << [LINUS, OLIVE, GONE]
    }

    def "the last member who may change the membership is not removed, by themselves or anybody"() {
        given:
        member(PAYROLL, ADA, "operator")
        def before = contents()

        when:
        changes.remove(groupId(PAYROLL), subjectId(ADA), ADA_USER)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.LAST_MEMBERSHIP_CHANGER

        and:
        contents() == before
    }


    def "with two who may change the membership, either is removed, by the other or by themselves, and one is left"() {
        given:
        member(PAYROLL, GRACE, "owner")

        when:
        changes.remove(groupId(PAYROLL), subjectId(person), caller)

        then:
        openHoldings(PAYROLL, person).isEmpty()
        openHoldingsOf("owner", PAYROLL) == [left]

        where:
        person | caller     || left
        GRACE  | ADA_USER   || ADA
        GRACE  | GRACE_USER || ADA
        ADA    | ADA_USER   || GRACE
    }


    def "removing a member leaves the rows of an earlier membership as they were"() {
        given:
        def earlier = closedRowsOf(LINUS)
        member(PAYROLL, LINUS, "operator")

        when:
        changes.remove(groupId(PAYROLL), subjectId(LINUS), ADA_USER)

        then:
        closedRowsOf(LINUS).containsAll(earlier)
        closedRowsOf(LINUS).size() == earlier.size() + 1
    }

    /** Asked before anything else, so a caller who may not change it learns nothing of who is there. */
    def "a caller who may not change the membership is refused for that, whatever the change names"() {
        given:
        def before = contents()

        when:
        attempt(change, GRACE_USER, named)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.ACT_NOT_PERMITTED

        and:
        contents() == before

        where:
        [change, named] << [["bringIn", "give", "take", "remove"], [OLIVE, GONE]].combinations()
    }


    def "a caller holding nothing in the group, or asking of a group there is none of, is refused as no group in view"() {
        given:
        def before = contents()

        when:
        attemptIn(group, change, OLIVE_USER, GRACE)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.GROUP_NOT_IN_VIEW

        and:
        contents() == before

        where:
        [group, change] << [[PAYROLL, "00000003-0000-4000-8000-000000000d09"],
                            ["bringIn", "give", "take", "remove"]].combinations()
    }

    /** A second waiter must read membership again after the first change commits. */
    @spock.lang.Tag("postgres-schema-changing")
    def "two who may change the membership giving it up or taking it from each other at once leave one of them"() {
        given:
        member(PAYROLL, GRACE, "owner")
        repeatableReadByDefault()
        // DB-SPECIFIC: PostgreSQL SELECT FOR NO KEY UPDATE.
        def held = holding("select 1 from groups where group_id = '${PAYROLL}' for no key update")

        when:
        def first = attempting { sideOf(race, ADA_USER, ADA, GRACE) }
        def second = attempting { sideOf(race, GRACE_USER, GRACE, ADA) }
        untilWaiting(2)
        held.commit()
        held.close()
        def outcomes = [first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)]

        then: "one landed and the other was refused, whichever went first"
        outcomes.count { it instanceof ApiErrorException } == 1
        outcomes.find { it instanceof ApiErrorException }.errorCode() in refusals

        and: "one of them may still change the membership"
        openHoldingsOf("owner", PAYROLL).size() == 1

        where:
        race             || refusals
        "giving it up"   || [RefusalCode.LAST_MEMBERSHIP_CHANGER]
        "taking it"      || [RefusalCode.ACT_NOT_PERMITTED, RefusalCode.GROUP_NOT_IN_VIEW]
        "removing"       || [RefusalCode.ACT_NOT_PERMITTED, RefusalCode.GROUP_NOT_IN_VIEW]
    }

    /** Both wait on the group's lock, and whichever goes second reads the other's rows. */
    @spock.lang.Tag("postgres-schema-changing")
    def "somebody brought in twice at once is brought in once, and the second is refused"() {
        given:
        member(PAYROLL, GRACE, "owner")
        repeatableReadByDefault()
        // DB-SPECIFIC: PostgreSQL SELECT FOR NO KEY UPDATE.
        def held = holding("select 1 from groups where group_id = '${PAYROLL}' for no key update")

        when:
        def first = attempting {
            changes.bringIn(groupId(PAYROLL), subjectId(OLIVE), EnumSet.of(GroupRole.OPERATOR), ADA_USER)
        }
        def second = attempting {
            changes.bringIn(groupId(PAYROLL), subjectId(OLIVE), EnumSet.of(GroupRole.OVERSEER), GRACE_USER)
        }
        untilWaiting(2)
        held.commit()
        held.close()
        def outcomes = [first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)]

        then:
        outcomes.count { it instanceof ApiErrorException } == 1
        outcomes.find { it instanceof ApiErrorException }.errorCode() == RefusalCode.PERSON_ALREADY_IN_GROUP

        and: "holding exactly what the one that landed gave"
        openHoldings(PAYROLL, OLIVE).size() == 1
    }

    /** Authorization must be read again after waiting for the group lock. */
    @spock.lang.Tag("postgres-schema-changing")
    def "a change whose caller stops being able to change the membership while it waits is refused, changing nothing"() {
        given:
        member(PAYROLL, GRACE, "owner")
        repeatableReadByDefault()
        // DB-SPECIFIC: PostgreSQL SELECT FOR NO KEY UPDATE.
        def held = holding("select 1 from groups where group_id = '${PAYROLL}' for no key update")

        when:
        def changing = attempting { attempt(change, GRACE_USER, OLIVE) }
        untilWaiting(1)
        // DB-SPECIFIC: PostgreSQL :: casts to uuid; now().
        session.sql("""
                update group_members set removed_at = now(), removed_by = ?::uuid, removal = 'role_taken',
                       removed_position = (select coalesce(max(closed.removed_position), 0) + 1
                                             from group_members closed where closed.group_id = group_members.group_id)
                 where subject_id = ?::uuid and role = 'owner'
                """).params(ADA, GRACE).update()
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
        change << ["bringIn", "give", "take", "remove"]
    }

    /** Foreign-key key-share locks must allow changes taking no-key-update locks to proceed. */
    def "a change to the membership does not wait on another transaction's row pointing at the group"() {
        given:
        def other = holding("""
                insert into group_members (group_id, subject_id, role, created_by)
                values ('${PAYROLL}', '${LINUS}', 'overseer', '${FIRST_STEWARD}')
                """)

        when:
        def brought = attempting {
            changes.bringIn(groupId(PAYROLL), subjectId(OLIVE), EnumSet.of(GroupRole.OPERATOR), ADA_USER)
        }.get(10, TimeUnit.SECONDS)

        then:
        brought instanceof Members.Member

        cleanup:
        other.rollback()
        other.close()
    }

    /** The person's stay is what both wait on, and it ends before the change reads it. */
    @spock.lang.Tag("postgres-schema-changing")
    def "somebody brought in while leaving the pool is read as having left, and is refused"() {
        given:
        repeatableReadByDefault()
        // DB-SPECIFIC: PostgreSQL SELECT FOR UPDATE.
        def leaving = holding("select 1 from pool_members where subject_id = '${LINUS}' and removed_at is null for update")

        when:
        def bringing = attempting {
            changes.bringIn(groupId(PAYROLL), subjectId(LINUS), EnumSet.of(GroupRole.OPERATOR), ADA_USER)
        }
        untilWaiting(1)
        leaving.createStatement().withCloseable {
            // DB-SPECIFIC: PostgreSQL now().
            it.execute("update pool_members set removed_at = now(), removed_by = '${FIRST_STEWARD}'" +
                    " where subject_id = '${LINUS}' and removed_at is null")
        }
        leaving.commit()
        leaving.close()
        def outcome = bringing.get(10, TimeUnit.SECONDS)

        then:
        outcome instanceof ApiErrorException
        outcome.errorCode() == RefusalCode.PERSON_NOT_IN_POOL

        and: "nobody outside the pool in any group"
        count("""
                select count(*) from group_members membership
                 where membership.removed_at is null
                   and not exists (select 1 from pool_members stay
                                    where stay.subject_id = membership.subject_id and stay.removed_at is null)
                """) == 0
    }

    private Object attempt(String change, UserId caller, String named) {
        attemptIn(PAYROLL, change, caller, named)
    }

    private Object attemptIn(String group, String change, UserId caller, String named) {
        switch (change) {
            case "bringIn":
                return changes.bringIn(groupId(group), subjectId(named), EnumSet.of(GroupRole.OPERATOR), caller)
            case "give":
                return changes.give(groupId(group), subjectId(named), GroupRole.OVERSEER, caller)
            case "take":
                return changes.take(groupId(group), subjectId(named), GroupRole.OPERATOR, caller)
            default:
                changes.remove(groupId(group), subjectId(named), caller)
                return null
        }
    }


    private Object sideOf(String race, UserId caller, String self, String other) {
        switch (race) {
            case "giving it up":
                return changes.take(groupId(PAYROLL), subjectId(self), GroupRole.OWNER, caller)
            case "taking it":
                return changes.take(groupId(PAYROLL), subjectId(other), GroupRole.OWNER, caller)
            default:
                changes.remove(groupId(PAYROLL), subjectId(other), caller)
                return null
        }
    }

    private def transactions() {
        new ChangeTransactions().readCommitted(new DataSourceTransactionManager(database))
    }


    private List<String> openHoldings(String group, String person) {
        // DB-SPECIFIC: PostgreSQL :: casts to text, uuid.
        session.sql("""
                select role::text || ' ' || created_by from group_members
                 where group_id = ?::uuid and subject_id = ?::uuid and removed_at is null order by role::text
                """).params(group, person).query(String).list()
    }


    private List<String> closedRowsOf(String person) {
        // DB-SPECIFIC: PostgreSQL :: casts to text, uuid.
        session.sql("select t::text from group_members t where subject_id = ?::uuid and removed_at is not null")
                .param(person).query(String).list()
    }


    private List<String> closedHoldings(String group, String person) {
        // DB-SPECIFIC: PostgreSQL :: casts to text, uuid.
        session.sql("""
                select role::text || ' ' || removed_by || ' ' || removal from group_members
                 where group_id = ?::uuid and subject_id = ?::uuid and removed_at is not null order by role::text
                """).params(group, person).query(String).list()
    }

    private List<String> openHoldingsOf(String role, String group) {
        // DB-SPECIFIC: PostgreSQL :: casts to text, uuid, group_role.
        session.sql("""
                select subject_id::text from group_members
                 where group_id = ?::uuid and role = ?::group_role and removed_at is null
                """).params(group, role).query(String).list()
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
        assert session.sql("show default_transaction_isolation").query(String).single() == "repeatable read"
    }

    private String contents() {
        WRITTEN_TO.collect { digestOf(it) }.join(" ")
    }

    private String digestOf(String rows) {
        // DB-SPECIFIC: PostgreSQL :: casts to text; md5(), string_agg().
        session.sql("select coalesce(md5(string_agg(t::text, '|' order by t::text)), 'empty') from ${rows} t" as String)
                .query(String).single()
    }

    private long count(String query) {
        session.sql(query).query(Long).single()
    }

    private static SubjectId subjectId(String spelled) {
        new SubjectId(UUID.fromString(spelled))
    }

    private static GroupId groupId(String spelled) {
        new GroupId(UUID.fromString(spelled))
    }

    private void pooled(String subject, String user, String name) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        session.sql("""
                insert into subjects (subject_id, kind, user_id, display_name, created_by)
                values (?::uuid, 'person', ?, ?, ?::uuid)
                """).params(subject, user, name, SEEDER).update()
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        session.sql("insert into pool_members (subject_id, created_by) values (?::uuid, ?::uuid)")
                .params(subject, FIRST_STEWARD).update()
    }

    private void endedStay(String subject) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid; now().
        session.sql("update pool_members set removed_at = now(), removed_by = ?::uuid where subject_id = ?::uuid")
                .params(FIRST_STEWARD, subject).update()
    }

    private void group(String group, String key, String name) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        session.sql("insert into groups (group_id, key, name, created_by) values (?::uuid, ?, ?, ?::uuid)")
                .params(group, key, name, FIRST_STEWARD).update()
    }

    private void member(String group, String subject, String role) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid, group_role.
        session.sql("""
                insert into group_members (group_id, subject_id, role, created_by)
                values (?::uuid, ?::uuid, ?::group_role, ?::uuid)
                """).params(group, subject, role, FIRST_STEWARD).update()
    }


    private void formerly(String group, String subject, String role, String removal, int hoursAgo) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid, group_role, group_member_removal; now() and make_interval.
        session.sql("""
                insert into group_members (group_id, subject_id, role, created_by, created_at, removed_at, removed_by,
                                           removal, removed_position)
                values (?::uuid, ?::uuid, ?::group_role, ?::uuid, now() - make_interval(hours => ? + 1),
                        now() - make_interval(hours => ?), ?::uuid, ?::group_member_removal,
                        (select coalesce(max(closed.removed_position), 0) + 1
                           from group_members closed where closed.group_id = ?::uuid))
                """).params(group, subject, role, FIRST_STEWARD, hoursAgo, hoursAgo, FIRST_STEWARD, removal, group).update()
    }

    private void closed(String group, String subject, String removal) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid, group_member_removal; now().
        session.sql("""
                update group_members set removed_at = now(), removed_by = ?::uuid, removal = ?::group_member_removal,
                       removed_position = (select coalesce(max(closed.removed_position), 0) + 1
                                             from group_members closed where closed.group_id = group_members.group_id)
                 where group_id = ?::uuid and subject_id = ?::uuid and removed_at is null
                """).params(FIRST_STEWARD, removal, group, subject).update()
    }

}
