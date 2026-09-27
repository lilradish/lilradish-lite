package org.lilradish.lite.app.groupregister

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.sql.Connection
import javax.sql.DataSource
import org.lilradish.lite.domain.groupregister.GroupSortColumn
import org.lilradish.lite.domain.identity.GroupId
import org.lilradish.lite.domain.listing.ListCursor
import org.lilradish.lite.domain.listing.ListFilter
import org.lilradish.lite.domain.listing.ListOrder
import org.lilradish.lite.domain.listing.ListPosition
import org.lilradish.lite.domain.listing.ListQuery
import org.lilradish.lite.testutil.Baseline
import org.lilradish.lite.testutil.CountingDataSource
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * The register of groups as the store answers it, asked of a real server running the real baseline.
 * Which membership is current, which role may change it, how people are counted, and which collation
 * orders and matches a value are all decided in SQL.
 *
 * <p>Every order is asked of two databases on one server, one defaulting to the C locale and one to
 * ICU: the names below order differently under each, so a collation left to the default is wrong on
 * one of them. Every order is written out as a list.
 *
 * <p>What a feature adds of its own is added on a connection of its own and rolled back afterwards.
 */
class GroupRegisterIntegrationSpec extends Specification {

    static final String SEEDER = "00000000-0000-4000-8000-000000000000"

    static final String FIRST_STEWARD = "00000001-0000-4000-8000-000000000001"

    static final String CAPITAL_E_ACUTE = Character.toString(0xC9)

    static final String SMALL_E_ACUTE = Character.toString(0xE9)

    /** The group, its key and its name. */
    static final List<List<String>> GROUPS = [
            [group("1"), "PAYROLL", "Payroll"],
            [group("2"), "FINANCE", "finance"],
            [group("3"), "ELAN", CAPITAL_E_ACUTE + "lan"],
            [group("4"), "VOID", "Empty room"],
            [group("5"), "BOARD", "Two owners"],
    ]

    /**
     * Whether each can still be administered, and how many are in it. An owner and an operator; one
     * person holding two roles, neither of them the owner's; an owner whose role was taken beside an
     * operator still there; nobody at all; two owners, and an operator who has left.
     */
    static final Map<String, List> STANDING = [
            "PAYROLL": [true, 2L],
            "FINANCE": [false, 1L],
            "ELAN"   : [false, 1L],
            "VOID"   : [false, 0L],
            "BOARD"  : [true, 2L],
    ]

    /**
     * Each column in each direction, and the keys in the order it puts them. Keys by code point. Names
     * by the Unicode collation algorithm: an accented capital among its unaccented neighbours, a small
     * letter among capitals. Those nobody can administer, and those nobody is in, gather at one end.
     * Ties by key ascending either way.
     */
    static final List<List> ORDERS = [
            [GroupSortColumn.KEY, false, ["BOARD", "ELAN", "FINANCE", "PAYROLL", "VOID"]],
            [GroupSortColumn.KEY, true, ["VOID", "PAYROLL", "FINANCE", "ELAN", "BOARD"]],
            [GroupSortColumn.NAME, false, ["ELAN", "VOID", "FINANCE", "PAYROLL", "BOARD"]],
            [GroupSortColumn.NAME, true, ["BOARD", "PAYROLL", "FINANCE", "VOID", "ELAN"]],
            [GroupSortColumn.CAN_BE_ADMINISTERED, false, ["ELAN", "FINANCE", "VOID", "BOARD", "PAYROLL"]],
            [GroupSortColumn.CAN_BE_ADMINISTERED, true, ["BOARD", "PAYROLL", "ELAN", "FINANCE", "VOID"]],
            [GroupSortColumn.MEMBER_COUNT, false, ["VOID", "ELAN", "FINANCE", "BOARD", "PAYROLL"]],
            [GroupSortColumn.MEMBER_COUNT, true, ["BOARD", "PAYROLL", "ELAN", "FINANCE", "VOID"]],
    ]

    static final Map<ListOrder<GroupSortColumn>, List<String>> EXPECTED =
            ORDERS.collectEntries { [(new ListOrder<>(it[0] as GroupSortColumn, it[1] as boolean)): it[2]] }

    static final ListOrder<GroupSortColumn> BY_KEY = new ListOrder<>(GroupSortColumn.KEY, false)

    static final ListOrder<GroupSortColumn> BY_NAME = new ListOrder<>(GroupSortColumn.NAME, false)

    static final ListOrder<GroupSortColumn> BY_ADMINISTERED = new ListOrder<>(GroupSortColumn.CAN_BE_ADMINISTERED, false)

    static final ListOrder<GroupSortColumn> BY_MEMBERS_DESCENDING = new ListOrder<>(GroupSortColumn.MEMBER_COUNT, true)

    static final List<String> DEFAULTS = ["postgres", "icu_default"]

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    Map<String, DataSource> databases = [:]

    @Shared
    Map<String, GroupRegister> registers = [:]

    static String group(String suffix) {
        "00000003-0000-4000-8000-00000000070" + suffix
    }

    static String person(String suffix) {
        "00000002-0000-4000-8000-00000000070" + suffix
    }

    def setupSpec() {
        server.postgresDatabase.connection.withCloseable { Connection administration ->
            administration.createStatement().withCloseable { statement ->
                statement.execute("""
                        create database icu_default template template0 encoding 'UTF8'
                            locale_provider icu icu_locale 'und' locale 'C'
                        """)
            }
        }
        DEFAULTS.each { name ->
            databases[name] = Baseline.appliedTo(server, name)
            def session = JdbcClient.create(databases[name])
            populate(session)
            registers[name] = new GroupRegister(session)
        }
        assert DEFAULTS.collect { defaultProviderOf(it) } == ["c", "i"]
    }

    /** Written out rather than derived, so an order nobody wrote down is one this spec does not ask. */
    def "the orders written out are every column the register sorts by, in both directions, and nothing else"() {
        expect:
        EXPECTED.keySet() == GroupSortColumn.values().collectMany { [new ListOrder<>(it, false), new ListOrder<>(it, true)] } as Set
        EXPECTED.size() == ORDERS.size()
    }

    def "the register lists every group, each once, with whether it can be administered and how many are in it"() {
        when:
        def rows = everything(registers.postgres, BY_KEY)

        then:
        rows.collectEntries { [(it.key().value()): [it.canBeAdministered(), it.memberCount()]] } == STANDING
        rows.size() == GROUPS.size()

        and: "each under its name and addressed by the identifier the store holds for it"
        rows.collectEntries { [(it.key().value()): [it.groupId().value().toString(), it.name().value()]] } ==
                GROUPS.collectEntries { [(it[1]): [it[0], it[2]]] }
    }

    def "every order puts the whole register in the order a reader expects of it, whatever the database defaults to"() {
        expect:
        keysOf(everything(registers[database], order)) == EXPECTED[order]

        where:
        [database, order] << [DEFAULTS, EXPECTED.keySet() as List].combinations()
    }

    /** Resumed after each group in turn as a reader carries the position, so a boundary falls between every pair. */
    def "resumed after any group, a page holds every group after it in that order and offers nothing beyond"() {
        given:
        def asked = query(order, null)
        def positions = EXPECTED[order].collect { key -> new ListPosition(heldIn(key, order.column()), key) }

        when:
        def resumed = positions.collect { registers[database].page(asked, carried(asked, it)) }

        then:
        resumed.indices.every { keysOf(resumed[it].rows()) == EXPECTED[order].drop(it + 1) }
        resumed.every { it.next() == null }

        where:
        [database, order] << [DEFAULTS, EXPECTED.keySet() as List].combinations()
    }

    /**
     * Matched as text anywhere in the name as SearchFolding folds it — case folded, canonical spellings
     * equal, an accent still an accent; a key is not a name, and a wildcard is itself.
     */
    def "a filter narrows the register to the groups whose name holds what was typed, whatever its case"() {
        expect:
        keysOf(registers.postgres.page(query(BY_NAME, new ListFilter(typed)), null).rows()) == found

        where:
        typed                          || found
        "fin"                          || ["FINANCE"]
        "FINANCE"                      || ["FINANCE"]
        SMALL_E_ACUTE + "LAN"          || ["ELAN"]
        "e" + Character.toString(0x0301) + "lan" || ["ELAN"]
        "o"                            || ["VOID", "PAYROLL", "BOARD"]
        "Two owners"                   || ["BOARD"]
        "VOID"                         || []
        "%"                            || []
        "_"                            || []
        "elan"                         || []
    }

    def "a filter and an order read on together from any group, ties included"() {
        given:
        def asked = query(BY_MEMBERS_DESCENDING, new ListFilter("o"))

        expect:
        keysOf(registers.postgres.page(asked, after == null ? null : carried(asked, after)).rows()) == rest

        where:
        after                           || rest
        null                            || ["BOARD", "PAYROLL", "VOID"]
        new ListPosition(2L, "BOARD")   || ["PAYROLL", "VOID"]
        new ListPosition(2L, "PAYROLL") || ["VOID"]
        new ListPosition(0L, "VOID")    || []
    }

    def "a page is one statement however many groups it holds"() {
        given:
        def statements = []
        def counted = new GroupRegister(JdbcClient.create(new CountingDataSource(databases.postgres, statements)))

        when:
        def page = counted.page(query(BY_ADMINISTERED, null), after)

        then:
        statements.size() == 1

        and:
        page.rows().size() == held

        where:
        after                           || held
        null                            || 5
        new ListPosition(false, "ELAN") || 4
    }

    /** A name the store's floor admits and the type does not: shown, it is a twin; left out, the list is not the register. */
    def "a stored name this system will not show fails the whole read loudly, naming whose row it was"() {
        given:
        def (Connection connection, JdbcClient session) = rolledBackOn("postgres")
        session.sql("insert into groups (group_id, key, name, created_by) values (?::uuid, 'LEADING', ' Leading', ?::uuid)")
                .params(group("9"), FIRST_STEWARD).update()

        when:
        new GroupRegister(session).page(query(BY_KEY, null), null)

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "Group ${group("9")} holds a key or a name this system will not show" as String
        failed.cause instanceof IllegalArgumentException

        cleanup:
        connection.rollback()
        connection.close()
    }

    def "one group is read as the register reads it"() {
        when:
        def row = registers.postgres.group(new GroupId(UUID.fromString(addressed))).orElseThrow()

        then:
        row.key().value() == key
        [row.canBeAdministered(), row.memberCount()] == STANDING[key]

        where:
        addressed  || key
        group("1") || "PAYROLL"
        group("3") || "ELAN"
        group("4") || "VOID"
    }

    def "no group is no group, whatever the identifier names instead"() {
        expect:
        registers.postgres.group(new GroupId(UUID.fromString(addressed))).isEmpty()

        where:
        addressed << ["00000009-0000-4000-8000-000000000009", person("1"), FIRST_STEWARD]
    }

    def "a group is one statement however many are in it"() {
        given:
        def statements = []
        def counted = new GroupRegister(JdbcClient.create(new CountingDataSource(databases.postgres, statements)))

        when:
        def row = counted.group(new GroupId(UUID.fromString(group("5")))).orElseThrow()

        then:
        statements.size() == 1

        and:
        row.memberCount() == 2L
    }

    private static ListQuery<GroupSortColumn> query(ListOrder<GroupSortColumn> order, ListFilter filter) {
        new ListQuery<>(GroupRegister.WHOLE_REGISTER, order, filter)
    }

    private static List<GroupRegister.GroupRow> everything(GroupRegister register, ListOrder<GroupSortColumn> order) {
        register.page(query(order, null), null).rows()
    }

    private static List<String> keysOf(List<GroupRegister.GroupRow> rows) {
        rows*.key()*.value()
    }

    /** What the group under this key holds in the column, as the fixture above wrote it. */
    private static Object heldIn(String key, GroupSortColumn column) {
        switch (column) {
            case GroupSortColumn.KEY: return key
            case GroupSortColumn.NAME: return GROUPS.find { it[1] == key }[2]
            case GroupSortColumn.CAN_BE_ADMINISTERED: return STANDING[key][0]
            default: return STANDING[key][1]
        }
    }

    private static ListPosition carried(ListQuery<GroupSortColumn> query, ListPosition ended) {
        ListCursor.resume(GroupRegister.LISTED, query, ListCursor.mint(GroupRegister.LISTED, query, ended))
    }

    private String defaultProviderOf(String database) {
        JdbcClient.create(databases[database])
                .sql("select datlocprovider::text from pg_database where datname = current_database()")
                .query(String)
                .single()
    }

    private List rolledBackOn(String database) {
        def connection = databases[database].connection
        connection.autoCommit = false
        [connection, JdbcClient.create(new SingleConnectionDataSource(connection, true))]
    }

    private static void populate(JdbcClient session) {
        (1..8).each {
            session.sql("""
                    insert into subjects (subject_id, kind, user_id, display_name, created_by)
                    values (?::uuid, 'person', ?, ?, ?::uuid)
                    """).params(person("$it"), "00070$it", "Person $it", SEEDER).update()
            session.sql("insert into pool_members (subject_id, created_by) values (?::uuid, ?::uuid)")
                    .params(person("$it"), FIRST_STEWARD).update()
        }
        GROUPS.each {
            session.sql("insert into groups (group_id, key, name, created_by) values (?::uuid, ?, ?, ?::uuid)")
                    .params(it[0], it[1], it[2], FIRST_STEWARD).update()
        }
        member(session, group("1"), person("1"), "owner")
        member(session, group("1"), person("2"), "operator")
        member(session, group("2"), person("3"), "operator")
        member(session, group("2"), person("3"), "overseer")
        member(session, group("3"), person("4"), "operator")
        ended(session, group("3"), person("7"), "owner", "role_taken")
        member(session, group("5"), person("5"), "owner")
        member(session, group("5"), person("6"), "owner")
        ended(session, group("5"), person("8"), "operator", "removed_from_group")
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
