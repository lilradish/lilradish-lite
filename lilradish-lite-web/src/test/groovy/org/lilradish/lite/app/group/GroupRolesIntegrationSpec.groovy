package org.lilradish.lite.app.group

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.sql.SQLException
import javax.sql.DataSource
import org.libprunus.core.error.ApiErrorException
import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.identity.GroupId
import org.lilradish.lite.domain.identity.GroupPermission
import org.lilradish.lite.domain.identity.GroupRole
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.testutil.Baseline
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * What somebody holds in one group, asked of a real server running the real baseline: a join, a
 * partial predicate and one group among several, none of which exists anywhere but in a database.
 */
class GroupRolesIntegrationSpec extends Specification {

    static final String SEEDER = "00000000-0000-4000-8000-000000000000"

    static final String FIRST_STEWARD = "00000001-0000-4000-8000-000000000001"

    static final GroupId PAYROLL = new GroupId(UUID.fromString("00000003-0000-4000-8000-000000000a01"))

    static final GroupId TRIAGE = new GroupId(UUID.fromString("00000003-0000-4000-8000-000000000a02"))

    static final GroupId NO_GROUP = new GroupId(UUID.fromString("00000003-0000-4000-8000-000000000a09"))

    static final String ADA_SUBJECT = "00000002-0000-4000-8000-000000000a01"

    static final UserId ADA = new UserId("000a01")

    static final String GRACE_SUBJECT = "00000002-0000-4000-8000-000000000a02"

    static final UserId GRACE = new UserId("000a02")

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    DataSource source

    @Shared
    JdbcClient session

    @Shared
    GroupRoles roles

    def setupSpec() {
        server.postgresDatabase.connection.withCloseable { it.createStatement().withCloseable {
            it.execute("create database group_roles")
        } }
        source = Baseline.appliedTo(server, "group_roles")
        session = JdbcClient.create(source)
        roles = new GroupRoles(session)
        person(ADA_SUBJECT, ADA)
        person(GRACE_SUBJECT, GRACE)
        group(PAYROLL, "PAYROLL", "Payroll")
        group(TRIAGE, "TRIAGE", "Triage")
        member(PAYROLL, ADA_SUBJECT, "owner", false)
        member(PAYROLL, ADA_SUBJECT, "operator", false)
        member(PAYROLL, ADA_SUBJECT, "overseer", true)
        member(TRIAGE, ADA_SUBJECT, "operator", false)
        member(PAYROLL, GRACE_SUBJECT, "owner", true)
    }

    def "somebody holding several roles in a group holds every one of them there, and only there"() {
        expect:
        roles.heldBy(ADA, PAYROLL) == EnumSet.of(GroupRole.OWNER, GroupRole.OPERATOR)

        and: "and in another group only what they hold in that one"
        roles.heldBy(ADA, TRIAGE) == EnumSet.of(GroupRole.OPERATOR)
    }

    /**
     * A role taken is a row still there, the store keeping the history, so the predicate is the only
     * thing between the taking and its effect.
     */
    def "a role taken or a membership ended is held no longer, though its row remains"() {
        expect:
        !roles.heldBy(ADA, PAYROLL).contains(GroupRole.OVERSEER)
        roles.heldBy(GRACE, PAYROLL).isEmpty()

        and: "while the rows are still in the store"
        session.sql("select count(*) from group_members where removed_at is not null").query(Long).single() == 2L
    }

    /** Nobody, a group nobody holds and a group somebody holds nothing in are one answer: nothing. */
    def "a group that does not exist, a user nobody is, and a group somebody holds nothing in all answer with no role"() {
        expect:
        roles.heldBy(user, group).isEmpty()

        and: "over a store that does answer somebody, so the emptiness is a reading and not a silence"
        !roles.heldBy(ADA, PAYROLL).isEmpty()

        where:
        user                          | group
        ADA                           | NO_GROUP
        new UserId("nobody-is-this")  | PAYROLL
        new UserId("000001")          | PAYROLL
        GRACE                         | TRIAGE
    }

    def "a holding a caller was handed cannot be widened through the set it came back in"() {
        given:
        def held = roles.heldBy(ADA, TRIAGE)

        when:
        held.add(GroupRole.OWNER)

        then:
        thrown(UnsupportedOperationException)

        and:
        roles.heldBy(ADA, TRIAGE) == EnumSet.of(GroupRole.OPERATOR)
    }

    /** An owner reaches every permission; what the operator's role adds changes nothing about it. */
    def "a caller still reaching the permission asked is answered with every permission their roles reach there"() {
        expect:
        inTransaction { roles.stillReaching(ADA, PAYROLL, GroupPermission.CHANGE_MEMBERSHIP) } ==
                EnumSet.allOf(GroupPermission)

        and: "and in another group only what the roles held in that one reach"
        !inTransaction { roles.stillReaching(ADA, TRIAGE, GroupPermission.START_RUN) }
                .contains(GroupPermission.CHANGE_MEMBERSHIP)
    }

    def "a caller no longer reaching the permission asked is refused as the gate refuses them"() {
        when:
        inTransaction { roles.stillReaching(user, group, GroupPermission.CHANGE_MEMBERSHIP) }

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == refusal

        where:
        user  | group    || refusal
        ADA   | TRIAGE   || RefusalCode.ACT_NOT_PERMITTED
        GRACE | PAYROLL  || RefusalCode.GROUP_NOT_IN_VIEW
        ADA   | NO_GROUP || RefusalCode.GROUP_NOT_IN_VIEW
    }

    /** Held for share: a change to the group's membership, which takes its row for no key update, waits on it. */
    def "the group's row is held until the transaction asking ends, and let go of then"() {
        when:
        def whileHeld = inTransaction {
            roles.stillReaching(ADA, PAYROLL, GroupPermission.START_RUN)
            membershipMayChange(PAYROLL)
        }

        then:
        !whileHeld

        and:
        membershipMayChange(PAYROLL)
    }

    def "a caller still reaching the permission asked is handed the group and the permission so reached, its row held until the end"() {
        when:
        def whileHeld = inTransaction {
            def reached = roles.stillReached(ADA, PAYROLL, GroupPermission.START_RUN)
            [reached.group(), reached.permission(), membershipMayChange(PAYROLL)]
        }

        then:
        whileHeld == [PAYROLL, GroupPermission.START_RUN, false]

        and:
        membershipMayChange(PAYROLL)
    }

    def "a caller still reaching the permission asked is handed every permission their roles reach there, read under the row held"() {
        when:
        def permitted = inTransaction {
            def reached = roles.stillReached(ADA, group, GroupPermission.START_RUN)
            assert !membershipMayChange(group)
            reached.permitted()
        }

        then:
        permitted == reachedThere

        where:
        group   || reachedThere
        PAYROLL || EnumSet.allOf(GroupPermission)
        TRIAGE  || EnumSet.of(GroupPermission.READ_MEMBERSHIP, GroupPermission.START_RUN, GroupPermission.ANSWER_STEP,
                GroupPermission.READ_OWN_RUNS, GroupPermission.AUTHOR_ENTRY, GroupPermission.READ_INFERENCE_CONTENT)
    }

    def "a caller no longer reaching the permission asked is refused the group as the gate refuses them"() {
        when:
        inTransaction { roles.stillReached(user, group, GroupPermission.CHANGE_MEMBERSHIP) }

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == refusal

        where:
        user  | group    || refusal
        ADA   | TRIAGE   || RefusalCode.ACT_NOT_PERMITTED
        GRACE | PAYROLL  || RefusalCode.GROUP_NOT_IN_VIEW
        ADA   | NO_GROUP || RefusalCode.GROUP_NOT_IN_VIEW
    }

    private <T> T inTransaction(Closure<T> work) {
        new TransactionTemplate(new JdbcTransactionManager(source)).execute { status -> work() }
    }

    private boolean membershipMayChange(GroupId group) {
        source.connection.withCloseable { connection ->
            try {
                connection.prepareStatement(
                        "select 1 from groups where group_id = '${group.value()}' for no key update nowait")
                        .withCloseable { it.executeQuery().close() }
                true
            } catch (SQLException refused) {
                assert refused.SQLState == "55P03"
                false
            }
        }
    }

    private void person(String subject, UserId user) {
        session.sql("insert into subjects (subject_id, kind, user_id, created_by) values (?::uuid, 'person', ?, ?::uuid)")
                .params(subject, user.value(), SEEDER).update()
        session.sql("insert into pool_members (subject_id, created_by) values (?::uuid, ?::uuid)")
                .params(subject, FIRST_STEWARD).update()
    }

    private void group(GroupId group, String key, String name) {
        session.sql("insert into groups (group_id, key, name, created_by) values (?, ?, ?, ?::uuid)")
                .params(group.value(), key, name, FIRST_STEWARD).update()
    }

    private void member(GroupId group, String subject, String role, boolean taken) {
        session.sql("""
                insert into group_members (group_id, subject_id, role, created_by, removed_at, removed_by, removal)
                values (?, ?::uuid, ?::group_role, ?::uuid, ${taken ? "now()" : "null"},
                        ${taken ? "'" + FIRST_STEWARD + "'::uuid" : "null"},
                        ${taken ? "'role_taken'::group_member_removal" : "null"})
                """).params(group.value(), subject, role, FIRST_STEWARD).update()
    }
}
