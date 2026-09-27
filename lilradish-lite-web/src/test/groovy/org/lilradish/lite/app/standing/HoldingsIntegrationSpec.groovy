package org.lilradish.lite.app.standing

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import javax.sql.DataSource
import org.lilradish.lite.app.estate.EstateRoleGrants
import org.lilradish.lite.domain.identity.EstateAct
import org.lilradish.lite.domain.identity.EstateRole
import org.lilradish.lite.domain.identity.GroupId
import org.lilradish.lite.domain.identity.GroupPermission
import org.lilradish.lite.domain.identity.GroupRole
import org.lilradish.lite.domain.identity.Scope
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.testutil.Baseline
import org.lilradish.lite.testutil.CountingDataSource
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * What somebody holds, asked of the real baseline on a server whose database defaults to the C locale,
 * so an order only the named ICU collation gives proves it was named.
 */
class HoldingsIntegrationSpec extends Specification {

    static final String SEEDER = "00000000-0000-4000-8000-000000000000"

    static final String FIRST_STEWARD = "00000001-0000-4000-8000-000000000001"

    static final String CAPITAL_E_ACUTE = Character.toString(0xC9)

    /** In three groups, and once in one they no longer hold anything in; a watcher, and a steward no longer. */
    static final UserId READER = new UserId("000801")

    /** A steward in no group. */
    static final UserId ESTATE_ONLY = new UserId("000802")

    static final UserId HOLDS_NOTHING = new UserId("000803")

    /** The owner of payroll, beside the reader there, and nothing across the estate. */
    static final UserId OWNER_ELSEWHERE = new UserId("000804")

    static final UserId NOBODY = new UserId("there-is-no-such-user")

    /** What an operator's role bundles, written out rather than read off the role. */
    static final List<GroupPermission> OPERATING = [GroupPermission.READ_MEMBERSHIP, GroupPermission.START_RUN,
                                                    GroupPermission.ANSWER_STEP, GroupPermission.READ_OWN_RUNS,
                                                    GroupPermission.AUTHOR_ENTRY,
                                                    GroupPermission.READ_INFERENCE_CONTENT]

    /** And an overseer's, which is also what an operator and an overseer hold between them. */
    static final List<GroupPermission> OVERSEEING = OPERATING + [GroupPermission.READ_ALL_RUNS,
                                                                 GroupPermission.REVIEW_AT_GATE,
                                                                 GroupPermission.APPROVE_ENTRY,
                                                                 GroupPermission.REVOKE_ENTRY]

    static final Scope.Group PAYROLL = inGroup("1")

    static final Scope.Group FINANCE = inGroup("2")

    static final Scope.Group ELAN = inGroup("3")

    static final Scope.Group VOID = inGroup("4")

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    DataSource database

    @Shared
    Holdings holdings

    @Shared
    EstateRoleGrants grants

    static String person(String suffix) {
        "00000002-0000-4000-8000-00000000080" + suffix
    }

    static String group(String suffix) {
        "00000003-0000-4000-8000-00000000080" + suffix
    }

    static Scope.Group inGroup(String suffix) {
        new Scope.Group(new GroupId(UUID.fromString(group(suffix))))
    }

    def setupSpec() {
        database = Baseline.appliedTo(server, "postgres")
        def session = JdbcClient.create(database)
        populate(session)
        holdings = new Holdings(session)
        grants = new EstateRoleGrants(session)
    }

    /** Two roles in one group are their union, a role taken is not held, and an owner beside the reader lends nothing. */
    def "a reader is read in every group they are in, holding there what their open roles bundle"() {
        when:
        def principal = holdings.heldBy(READER).orElseThrow().principal()

        then:
        principal.roles(scope) == roles as Set
        principal.permissions(scope) == permissions as Set

        where:
        scope   || roles                                    | permissions
        PAYROLL || [GroupRole.OPERATOR, GroupRole.OVERSEER] | OVERSEEING
        FINANCE || [GroupRole.OPERATOR]                     | OPERATING
        ELAN    || [GroupRole.OWNER]                        | GroupPermission.values() as List
        VOID    || []                                       | []
    }

    def "the groups a reader is in come back each once, with key and name, in the order ICU gives names"() {
        when:
        def groups = holdings.heldBy(READER).orElseThrow().groups()

        then:
        groups.collect { [it.groupId().value().toString(), it.key().value(), it.name().value()] } == [
                [group("3"), "ELAN", CAPITAL_E_ACUTE + "lan"],
                [group("2"), "FINANCE", "finance"],
                [group("1"), "PAYROLL", "Payroll"]]

        and: "and a group holding nothing of theirs but rows since closed is not among them"
        !groups*.groupId().contains(VOID.groupId())
    }

    def "a reader stands where they hold something, and a withdrawn estate role reaches nothing"() {
        when:
        def principal = holdings.heldBy(READER).orElseThrow().principal()

        then:
        principal.scopes() == [PAYROLL, FINANCE, ELAN, Scope.ESTATE] as Set
        principal.estateReach() == [EstateAct.CHECK_SOUNDNESS, EstateAct.READ_MEASUREMENTS] as Set
    }

    /** The reader's roles in payroll are theirs alone: whoever else is there holds only what their own rows say. */
    def "somebody in one group with the reader holds only their own role there, and nothing elsewhere"() {
        when:
        def held = holdings.heldBy(OWNER_ELSEWHERE).orElseThrow()

        then:
        held.principal().scopes() == [PAYROLL] as Set
        held.principal().roles(PAYROLL) == [GroupRole.OWNER] as Set
        held.principal().estateReach().isEmpty()
        held.groups()*.groupId() == [PAYROLL.groupId()]
    }

    def "somebody the estate alone knows is read in no group at all"() {
        when:
        def held = holdings.heldBy(ESTATE_ONLY).orElseThrow()

        then:
        held.groups().isEmpty()
        held.principal().scopes() == [Scope.ESTATE] as Set

        and: "while what they hold across the estate is read all the same"
        held.principal().estateReach() == [EstateAct.KEEP_POOL, EstateAct.GRANT_ESTATE_ROLE,
                                           EstateAct.KEEP_GROUP_REGISTER] as Set
    }

    def "a user nobody here knows answers as one known and holding nothing, with nothing"() {
        when:
        def held = holdings.heldBy(user)

        then:
        held.isEmpty()

        where:
        user << [NOBODY, HOLDS_NOTHING]
    }

    /** Two readers of held estate roles, the gate's and the standing's, that must never disagree. */
    def "what the standing reads across the estate is what the gate reads, for anybody at all"() {
        when:
        def standing = holdings.heldBy(user).map { it.principal().estateReach() }.orElse([] as Set)
        def gate = EstateRole.actsOf(grants.heldBy(user))

        then:
        standing == gate

        where:
        user << [READER, ESTATE_ONLY, HOLDS_NOTHING, NOBODY]
    }

    def "what somebody holds is one statement, however much it is and wherever it is held"() {
        given:
        def statements = []
        def counted = new Holdings(JdbcClient.create(new CountingDataSource(database, statements)))

        when:
        def held = counted.heldBy(user)

        then:
        statements.size() == 1

        and:
        held.map { it.groups().size() }.orElse(0) == groups

        where:
        user        || groups
        READER      || 3
        ESTATE_ONLY || 0
        NOBODY      || 0
    }

    /** Shown, a name the type refuses is a twin; left out, the reader is in fewer groups than they are. */
    def "a stored name this system will not show fails the whole read loudly, naming whose group it was"() {
        given:
        def connection = database.connection
        connection.autoCommit = false
        def session = JdbcClient.create(new SingleConnectionDataSource(connection, true))
        session.sql("insert into groups (group_id, key, name, created_by) values (?::uuid, 'LEADING', ' Leading', ?::uuid)")
                .params(group("9"), FIRST_STEWARD).update()
        member(session, group("9"), person("1"), "operator")

        when:
        new Holdings(session).heldBy(READER)

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "Group ${group("9")} holds a key or a name this system will not show" as String
        failed.cause instanceof IllegalArgumentException

        cleanup:
        connection.rollback()
        connection.close()
    }

    private static void populate(JdbcClient session) {
        (1..4).each {
            session.sql("""
                    insert into subjects (subject_id, kind, user_id, display_name, created_by)
                    values (?::uuid, 'person', ?, ?, ?::uuid)
                    """).params(person("$it"), "00080$it", "Person $it", SEEDER).update()
        }
        // Nobody outside the pool is in any group.
        [person("1"), person("4")].each {
            session.sql("insert into pool_members (subject_id, created_by) values (?::uuid, ?::uuid)")
                    .params(it, FIRST_STEWARD).update()
        }
        [[group("1"), "PAYROLL", "Payroll"],
         [group("2"), "FINANCE", "finance"],
         [group("3"), "ELAN", CAPITAL_E_ACUTE + "lan"],
         [group("4"), "VOID", "Empty room"]].each {
            session.sql("insert into groups (group_id, key, name, created_by) values (?::uuid, ?, ?, ?::uuid)")
                    .params(it[0], it[1], it[2], FIRST_STEWARD).update()
        }
        member(session, group("1"), person("1"), "operator")
        member(session, group("1"), person("1"), "overseer")
        ended(session, group("1"), person("1"), "owner", "role_taken")
        member(session, group("2"), person("1"), "operator")
        member(session, group("3"), person("1"), "owner")
        ended(session, group("4"), person("1"), "operator", "removed_from_group")
        member(session, group("1"), person("4"), "owner")
        granted(session, person("1"), "watcher")
        session.sql("""
                insert into estate_role_grants (subject_id, role, created_by, removed_at, removed_by)
                values (?::uuid, 'steward', ?::uuid, now(), ?::uuid)
                """).params(person("1"), SEEDER, FIRST_STEWARD).update()
        granted(session, person("2"), "steward")
    }

    private static void granted(JdbcClient session, String subject, String role) {
        session.sql("insert into estate_role_grants (subject_id, role, created_by) values (?::uuid, ?::estate_role, ?::uuid)")
                .params(subject, role, SEEDER).update()
    }

    private static void member(JdbcClient session, String group, String subject, String role) {
        session.sql("""
                insert into group_members (group_id, subject_id, role, created_by)
                values (?::uuid, ?::uuid, ?::group_role, ?::uuid)
                """).params(group, subject, role, FIRST_STEWARD).update()
    }

    private static void ended(JdbcClient session, String group, String subject, String role, String removal) {
        session.sql("""
                insert into group_members (group_id, subject_id, role, created_by, removed_at, removed_by, removal)
                values (?::uuid, ?::uuid, ?::group_role, ?::uuid, now(), ?::uuid, ?::group_member_removal)
                """).params(group, subject, role, FIRST_STEWARD, FIRST_STEWARD, removal).update()
    }
}
