package com.lilradish.lite.app.members

import com.lilradish.lite.app.group.GroupLists
import com.lilradish.lite.domain.identity.GroupId
import com.lilradish.lite.domain.identity.GroupRole
import com.lilradish.lite.domain.identity.SubjectId
import com.lilradish.lite.domain.listing.ListCursor
import com.lilradish.lite.domain.listing.ListFilter
import com.lilradish.lite.domain.listing.ListOrder
import com.lilradish.lite.domain.listing.ListPage
import com.lilradish.lite.domain.listing.ListQuery
import com.lilradish.lite.domain.members.MemberSortColumn
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.Baseline
import com.lilradish.lite.testutil.CountingDataSource
import com.lilradish.lite.testutil.ExplainedQueries
import javax.sql.DataSource
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/** Ordering must be independent of either database default collation. */
class MembersIntegrationSpec extends Specification {

    static final String SEEDER = "00000000-0000-4000-8000-000000000000"

    static final String FIRST_STEWARD = "00000001-0000-4000-8000-000000000001"

    static final GroupId PAYROLL = groupId("c01")

    static final GroupId TRIAGE = groupId("c02")


    static final GroupId DUO = groupId("c03")


    static final GroupId CROWD = groupId("c04")

    static final GroupId NO_GROUP = groupId("c09")

    static final String CAPITAL_E_ACUTE = Character.toString(0xC9)


    static final List<List<String>> PEOPLE = [
            [subject("c10"), "000310", "Ari Mockvale"],
            [subject("c20"), "000320", "greno nockal"],
            [subject("c30"), "000330", null],
            [subject("c40"), "000340", CAPITAL_E_ACUTE + "vren Mack"],
            [subject("c50"), "000350", "Linus Left"],
            [subject("c60"), "000360", "Olive Out"],
            [subject("c70"), "000370", "Greno Nockal"],
    ]


    static final Map<String, Set<GroupRole>> PAYROLL_MEMBERS = [
            "000310": EnumSet.of(GroupRole.OPERATOR, GroupRole.OWNER),
            "000320": EnumSet.of(GroupRole.OPERATOR),
            "000330": EnumSet.of(GroupRole.OVERSEER),
            "000340": EnumSet.of(GroupRole.OPERATOR),
            "000370": EnumSet.of(GroupRole.OVERSEER, GroupRole.OPERATOR),
    ]

    static final ListOrder<MemberSortColumn> BY_USER = new ListOrder<>(MemberSortColumn.USER_ID, false)

    static final ListOrder<MemberSortColumn> BY_USER_DESCENDING = new ListOrder<>(MemberSortColumn.USER_ID, true)

    static final ListOrder<MemberSortColumn> BY_NAME = new ListOrder<>(MemberSortColumn.DISPLAY_NAME, false)

    static final ListOrder<MemberSortColumn> BY_NAME_DESCENDING = new ListOrder<>(MemberSortColumn.DISPLAY_NAME, true)

    /** Expected Unicode ordering is independent of the implementation, with nameless people last. */
    static final Map<ListOrder<MemberSortColumn>, List<String>> EXPECTED = [
            (BY_USER)           : ["000310", "000320", "000330", "000340", "000370"],
            (BY_USER_DESCENDING): ["000370", "000340", "000330", "000320", "000310"],
            (BY_NAME)           : ["000310", "000340", "000320", "000370", "000330"],
            (BY_NAME_DESCENDING): ["000370", "000320", "000340", "000310", "000330"],
    ]

    static final List<String> DEFAULTS = ["postgres", "icu_default"]

    @Shared
    @AutoCleanup
    TestPostgres server = TestPostgres.worker()

    @Shared
    Map<String, DataSource> databases = [:]

    @Shared
    Map<String, Members> stores = [:]

    static String subject(String suffix) {
        "00000002-0000-4000-8000-000000000" + suffix
    }

    static GroupId groupId(String suffix) {
        new GroupId(UUID.fromString("00000003-0000-4000-8000-000000000" + suffix))
    }

    def setupSpec() {
        // DB-SPECIFIC: PostgreSQL ICU locale-provider clauses define the second ordering fixture.
        server.createDatabase("icu_default", "locale_provider icu icu_locale 'und' locale 'C'")
        DEFAULTS.each { name ->
            databases[name] = Baseline.appliedTo(server, name)
            def session = JdbcClient.create(databases[name])
            populate(session)
            stores[name] = new Members(session)
        }
    }


    def "the complete member aggregate and both folded predicates are observed beside a larger subject history"() {
        given:
        def connection = databases.postgres.connection
        connection.autoCommit = false
        def single = new SingleConnectionDataSource(connection, true)
        def session = JdbcClient.create(single)
        def started = System.nanoTime()
        // DB-SPECIFIC: PostgreSQL generate_series and ::uuid/::text populate subjects and memberships;
        // text || non-string converts the generated integer to text.
        session.sql("""
                insert into subjects (subject_id, kind, user_id, display_name, created_by)
                select ('00000002-0000-4000-9000-' || lpad(number::text, 12, '0'))::uuid,
                       'person', 'S4-' || lpad(number::text, 6, '0'),
                       case when number = 1 then 'Nerith Mockvale '
                            when number % 50 = 0 then 'Mira Mockvale !_ '
                            else 'Mira Mockvale ' end || number, ?::uuid
                  from generate_series(1, ?) number
                """).params(SEEDER, history).update()
        session.sql("""
                insert into group_members (group_id, subject_id, role, created_by)
                select ?::uuid, subject_id, 'operator', ?::uuid
                  from subjects where user_id >= 'S4-000001' and user_id <= 'S4-000300'
                """).params(PAYROLL.value(), FIRST_STEWARD).update()
        def inserted = System.nanoTime() - started
        started = System.nanoTime()
        session.sql("update subjects set display_name = display_name || ' renamed' where user_id like 'S4-%'").update()
        def renamed = System.nanoTime() - started
        // DB-SPECIFIC: PostgreSQL ANALYZE refreshes the statistics used by its default planner.
        session.sql('analyze subjects').update()
        session.sql('analyze group_members').update()
        def observed = new ExplainedQueries(single)
        def searched = new Members(JdbcClient.create(observed))
        def results = []

        when:
        ['Nerith', 'Mockvale', 'a', '!_', 'S4-000001'].each { text ->
            (1..3).each { repetition ->
                def read = searched.page(query(PAYROLL, BY_NAME, new ListFilter(text)), null)
                results.add([typed: text, repetition: repetition, ids: read.rows()*.userId()*.value()])
            }
        }
        observed.save('members-' + history, [currentAdded: 300, subjectHistoryAdded: history,
                      insertedNanos: inserted, renamedNanos: renamed], results)

        then:
        observed.queries.size() == 15
        observed.queries.every { it.sql.contains('array_agg') && it.sql.contains('holding.removed_at is null') && !it.bindings.empty }
        results.find { it.typed == 'S4-000001' }.ids == ['S4-000001']
        results.groupBy { it.typed }.values().every { runs -> runs*.ids.toSet().size() == 1 }

        cleanup:
        connection.rollback()
        connection.close()

        where:
        history << [300, 6000]
    }

    def "the group's members are listed once each with every role they hold there, and nobody else"() {
        when:
        def rows = page(stores.postgres, query(PAYROLL, BY_USER, null)).rows()

        then:
        rows.collectEntries { [(it.userId().value()): it.roles()] } == PAYROLL_MEMBERS

        and: "a name where one is held and none where none is, never a stand-in for it"
        rows.collectEntries { [(it.userId().value()): it.displayName()?.value()] } ==
                PEOPLE.findAll { PAYROLL_MEMBERS.containsKey(it[1]) }.collectEntries { [(it[1]): it[2]] }

        and: "each addressed by the subject the store holds for them"
        rows.find { it.userId().value() == "000310" }.subjectId() == new SubjectId(UUID.fromString(subject("c10")))
    }

    def "a group nobody holds a role in, and one there is none of, list nobody"() {
        expect:
        page(stores.postgres, query(group, BY_USER, null)).rows().isEmpty()

        and: "over a store that does list somebody"
        !page(stores.postgres, query(PAYROLL, BY_USER, null)).rows().isEmpty()

        where:
        group << [NO_GROUP, groupId("c05")]
    }

    def "every order puts the group's members in the order a reader expects of it, whatever the database defaults to"() {
        expect:
        page(stores[database], query(PAYROLL, order, null)).rows()*.userId()*.value() == EXPECTED[order]

        where:
        [database, order] << [DEFAULTS, EXPECTED.keySet() as List].combinations()
    }


    def "a filter narrows the members to those whose name or user number holds what was typed, whatever its case"() {
        expect:
        page(stores.postgres, query(PAYROLL, BY_USER, new ListFilter(typed))).rows()*.userId()*.value() == found

        where:
        typed        || found
        "greno"      || ["000320", "000370"]
        "NOCKAL"     || ["000320", "000370"]
        "0003"       || ["000310", "000320", "000330", "000340", "000370"]
        "000330"     || ["000330"]
        "Olive"      || []
        "000360"     || []
        "Linus"      || []
        "VREN"       || ["000340"]
    }


    def "a group holding more members than a page is read a page at a time, within that group alone"() {
        given:
        def asked = query(CROWD, BY_USER, null)

        when:
        def first = page(stores.postgres, asked)
        def second = stores.postgres.page(asked,
                ListCursor.resume(Members.LISTED, asked, ListCursor.mint(Members.LISTED, asked, first.next())))

        then:
        first.rows().size() == ListPage.SIZE
        second.rows().size() == 1
        second.next() == null

        and: "every one of the crowd once, and nobody from any other group"
        (first.rows() + second.rows())*.userId()*.value() == (1..ListPage.SIZE + 1).collect { "c" + (it as String).padLeft(3, "0") }
    }

    def "a page is one statement however many members it holds"() {
        given:
        def statements = []
        def counted = new Members(JdbcClient.create(new CountingDataSource(databases.postgres, statements)))

        when:
        def listed = counted.page(query(PAYROLL, BY_USER, null), null)

        then:
        statements.size() == 1

        and:
        listed.rows().size() == PAYROLL_MEMBERS.size()
    }

    def "a member is read with every role they hold there"() {
        when:
        def member = stores.postgres.member(PAYROLL, new SubjectId(UUID.fromString(subject("c70")))).get()

        then:
        member.userId().value() == "000370"
        member.displayName().value() == "Greno Nockal"
        member.roles() == EnumSet.of(GroupRole.OVERSEER, GroupRole.OPERATOR)

        and: "none of them the last that lets anybody change the membership, holding none that may"
        member.lastChangingRoles().isEmpty()
    }


    def "the only member who may change the membership holds the role letting them as the last, and nothing else as it"() {
        expect:
        stores.postgres.member(group, new SubjectId(UUID.fromString(subject(person)))).get().lastChangingRoles() == last

        where:
        group   | person || last
        PAYROLL | "c10"  || EnumSet.of(GroupRole.OWNER)
        TRIAGE  | "c20"  || EnumSet.of(GroupRole.OWNER)
        DUO     | "c10"  || EnumSet.noneOf(GroupRole)
        DUO     | "c20"  || EnumSet.noneOf(GroupRole)
    }


    def "a member is removable wherever somebody else could still change the membership once all theirs are taken"() {
        expect:
        stores.postgres.member(group, new SubjectId(UUID.fromString(subject(person)))).get().removable() == removable

        where:
        group   | person || removable
        PAYROLL | "c10"  || false
        PAYROLL | "c20"  || true
        PAYROLL | "c70"  || true
        DUO     | "c10"  || true
        DUO     | "c20"  || true
        TRIAGE  | "c20"  || false
        TRIAGE  | "c60"  || true
    }


    def "nobody holding a role in the group now is no member of it, however they came to be missing"() {
        expect:
        !stores.postgres.member(PAYROLL, new SubjectId(UUID.fromString(named))).isPresent()

        and: "over a group that does have members"
        stores.postgres.member(PAYROLL, new SubjectId(UUID.fromString(subject("c10")))).isPresent()

        where:
        named << [subject("c50"), subject("c60"), subject("c99"), FIRST_STEWARD]
    }


    def "somebody in the pool is read as the group holds them, holding nothing where they are no member"() {
        when:
        def standing = stores.postgres.standing(PAYROLL, new SubjectId(UUID.fromString(subject(person))))

        then:
        standing.get().userId().value() == user
        standing.get().roles() == roles

        where:
        person || user     | roles
        "c10"  || "000310" | EnumSet.of(GroupRole.OPERATOR, GroupRole.OWNER)
        "c50"  || "000350" | EnumSet.noneOf(GroupRole)
        "c60"  || "000360" | EnumSet.noneOf(GroupRole)
    }

    def "somebody out of the pool, or nobody at all, is nobody the group holds"() {
        expect:
        !stores.postgres.standing(PAYROLL, new SubjectId(UUID.fromString(named))).isPresent()

        where:
        named << [subject("c98"), subject("c99")]
    }

    def "no member is one refusal, the same code and the same words whoever raises it"() {
        expect:
        Members.notInView().errorCode().code() == "MEMBER_NOT_IN_VIEW"
        Members.notInView().message == "That person is not a member of this group."
    }

    private static ListQuery<MemberSortColumn> query(GroupId group, ListOrder<MemberSortColumn> order, ListFilter filter) {
        new ListQuery<>(GroupLists.within(group), order, filter)
    }

    private static ListPage<Members.MemberRow> page(Members members, ListQuery<MemberSortColumn> query) {
        members.page(query, null)
    }

    private static void populate(JdbcClient session) {
        PEOPLE.each { person(session, it[0], it[1], it[2]) }
        ["c10", "c20", "c30", "c40", "c50", "c60", "c70"].each { stay(session, subject(it)) }
        person(session, subject("c98"), "000398", "Out Of Pool")
        group(session, PAYROLL, "PAYROLL", "Payroll")
        group(session, TRIAGE, "TRIAGE", "Triage")
        group(session, DUO, "DUO", "Duo")
        group(session, CROWD, "CROWD", "Crowd")
        group(session, groupId("c05"), "EMPTY", "Empty")
        member(session, PAYROLL, "c10", "owner", null)
        member(session, PAYROLL, "c10", "operator", null)
        member(session, PAYROLL, "c10", "overseer", "role_taken")
        member(session, PAYROLL, "c20", "operator", null)
        member(session, PAYROLL, "c20", "owner", "role_taken")
        member(session, PAYROLL, "c30", "overseer", null)
        member(session, PAYROLL, "c40", "operator", null)
        member(session, PAYROLL, "c50", "operator", "removed_from_group")
        member(session, PAYROLL, "c70", "overseer", null)
        member(session, PAYROLL, "c70", "operator", null)
        member(session, TRIAGE, "c20", "owner", null)
        member(session, TRIAGE, "c60", "operator", null)
        member(session, DUO, "c10", "owner", null)
        member(session, DUO, "c20", "owner", null)
        member(session, groupId("c05"), "c40", "owner", "removed_from_group")
        // DB-SPECIFIC: PostgreSQL :: casts to text, uuid; gen_random_uuid(), generate_series();
        // data-modifying WITH and RETURNING populate the crowd and its stays.
        session.sql("""
                with crowd as (
                         insert into subjects (subject_id, kind, user_id, created_by)
                         select gen_random_uuid(), 'person', 'c' || lpad(n::text, 3, '0'), ?::uuid
                           from generate_series(1, ?) n
                         returning subject_id),
                     stays as (
                         insert into pool_members (subject_id, created_by)
                         select subject_id, ?::uuid from crowd)
                insert into group_members (group_id, subject_id, role, created_by)
                select ?, subject_id, 'operator', ?::uuid from crowd
                """).params(SEEDER, ListPage.SIZE + 1, FIRST_STEWARD, CROWD.value(), FIRST_STEWARD).update()
    }

    private static void person(JdbcClient session, String subject, String user, String name) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        session.sql("""
                insert into subjects (subject_id, kind, user_id, display_name, created_by)
                values (?::uuid, 'person', ?, ?, ?::uuid)
                """).params(subject, user, name, SEEDER).update()
    }

    private static void stay(JdbcClient session, String subject) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        session.sql("insert into pool_members (subject_id, created_by) values (?::uuid, ?::uuid)")
                .params(subject, FIRST_STEWARD).update()
    }

    private static void group(JdbcClient session, GroupId group, String key, String name) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        session.sql("insert into groups (group_id, key, name, created_by) values (?, ?, ?, ?::uuid)")
                .params(group.value(), key, name, FIRST_STEWARD).update()
    }


    private static void member(JdbcClient session, GroupId group, String person, String role, String removal) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid; now().
        def closed = removal == null ? "null, null, null, null" : "now(), '${FIRST_STEWARD}'::uuid, '${removal}', 1"
        // DB-SPECIFIC: PostgreSQL :: casts to uuid, group_role.
        session.sql("""
                insert into group_members (group_id, subject_id, role, created_by, removed_at, removed_by, removal, removed_position)
                values (?, ?::uuid, ?::group_role, ?::uuid, ${closed})
                """).params(group.value(), subject(person), role, FIRST_STEWARD).update()
    }
}
