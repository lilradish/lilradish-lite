package com.lilradish.lite.app.pool

import com.lilradish.lite.domain.identity.EstateRole
import com.lilradish.lite.domain.identity.GroupId
import com.lilradish.lite.domain.identity.GroupName
import com.lilradish.lite.domain.identity.SubjectId
import com.lilradish.lite.domain.identity.SystemPrincipal
import com.lilradish.lite.domain.listing.ListCursor
import com.lilradish.lite.domain.listing.ListFilter
import com.lilradish.lite.domain.listing.ListOrder
import com.lilradish.lite.domain.listing.ListPosition
import com.lilradish.lite.domain.listing.ListQuery
import com.lilradish.lite.domain.people.PeopleSearch
import com.lilradish.lite.domain.pool.PoolSortColumn
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.Baseline
import com.lilradish.lite.testutil.CountingDataSource
import com.lilradish.lite.testutil.ExplainedQueries
import java.sql.Connection
import javax.sql.DataSource
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/** Ordering must be independent of either database default collation. */
class PoolPeopleIntegrationSpec extends Specification {

    // DB-SPECIFIC: the SQL fixtures and predicates below input/compare PostgreSQL native
    // subject_kind, estate_role, group_role and group_member_removal labels.

    static final String SEEDER = "00000000-0000-4000-8000-000000000000"

    static final String SYSTEM_ACTOR = SystemPrincipal.WORKFLOW_RUNNER.subject().value().toString()


    static final String FIRST_STEWARD = "00000001-0000-4000-8000-000000000001"

    static final String PAYROLL = "00000003-0000-4000-8000-000000000001"

    static final String FINANCE = "00000003-0000-4000-8000-000000000002"

    static final String ELAN = "00000003-0000-4000-8000-000000000003"

    static final String CAPITAL_E_ACUTE = Character.toString(0xC9)

    static final String SMALL_E_ACUTE = Character.toString(0xE9)

    static final String COMBINING_ACUTE = Character.toString(0x0301)

    static final String CAPITAL_A_RING = Character.toString(0xC5)

    static final String SMALL_A_RING = Character.toString(0xE5)

    static final String IDEOGRAPHIC_SPACE = Character.toString(0x3000)

    static final String SHARP_S = Character.toString(0xDF)

    static final String CAPITAL_O_STROKE = Character.toString(0xD8)

    static final String SMALL_O_STROKE = Character.toString(0xF8)

    static final String CAPITAL_AE = Character.toString(0xC6)

    static final String SMALL_AE = Character.toString(0xE6)

    static final String ASA = CAPITAL_A_RING + "SA-7"

    static final String YAMADA = "山田 太郎"


    static final List<List<String>> PEOPLE = [
            [subject("110"), "000110", CAPITAL_E_ACUTE + "vren Mack"],
            [subject("120"), "000120", "Ewra Mockner"],
            [subject("130"), "000130", null],
            [subject("140"), "000140", "Greno Nockal"],
            [subject("150"), "000150", "greno nockal"],
            [subject("160"), "000160", "Arlo Taffan"],
            [subject("170"), "000170", "Arlo Taffan"],
            [subject("180"), "000180", YAMADA],
            [subject("191"), "Zoe-9", "Zoe Moxen"],
            [subject("192"), "ann-2", "zed Shore"],
            [subject("193"), "per%cent", "Per Cent"],
            [subject("194"), "under_score", "Under Score"],
            [subject("195"), "back\\slash", "Back Slash"],
            [subject("196"), ASA, null],
            [subject("197"), "000190", "Removed Person"],
            [subject("198"), "000200", "Never Pooled"],
    ]


    static final Map<String, List> HOLDINGS = [
            "000001"     : [[EstateRole.STEWARD] as Set, 0],
            "000110"     : [[] as Set, 1],
            "000120"     : [[EstateRole.WATCHER] as Set, 2],
            "000130"     : [[] as Set, 1],
            "000140"     : [[EstateRole.STEWARD, EstateRole.WATCHER] as Set, 0],
            "000150"     : [[] as Set, 1],
            "000160"     : [[] as Set, 0],
            "000170"     : [[] as Set, 1],
            "000180"     : [[] as Set, 3],
            "Zoe-9"      : [[] as Set, 0],
            "ann-2"      : [[] as Set, 1],
            "per%cent"   : [[] as Set, 0],
            "under_score": [[] as Set, 0],
            "back\\slash": [[] as Set, 0],
            (ASA)        : [[] as Set, 0],
    ]

    static final ListOrder<PoolSortColumn> BY_USER = new ListOrder<>(PoolSortColumn.USER_ID, false)

    static final ListOrder<PoolSortColumn> BY_USER_DESCENDING = new ListOrder<>(PoolSortColumn.USER_ID, true)

    static final ListOrder<PoolSortColumn> BY_NAME = new ListOrder<>(PoolSortColumn.DISPLAY_NAME, false)

    static final ListOrder<PoolSortColumn> BY_NAME_DESCENDING = new ListOrder<>(PoolSortColumn.DISPLAY_NAME, true)

    static final ListOrder<PoolSortColumn> BY_COUNT = new ListOrder<>(PoolSortColumn.GROUP_COUNT, false)

    static final ListOrder<PoolSortColumn> BY_COUNT_DESCENDING = new ListOrder<>(PoolSortColumn.GROUP_COUNT, true)

    /** Expected ordering is independent of the implementation, with nameless people last and a unique tie-breaker. */
    static final Map<ListOrder<PoolSortColumn>, List<String>> EXPECTED = [
            (BY_USER)            : ["000001", "000110", "000120", "000130", "000140", "000150", "000160", "000170",
                                    "000180", "Zoe-9", "ann-2", "back\\slash", "per%cent", "under_score", ASA],
            (BY_USER_DESCENDING) : [ASA, "under_score", "per%cent", "back\\slash", "ann-2", "Zoe-9", "000180",
                                    "000170", "000160", "000150", "000140", "000130", "000120", "000110", "000001"],
            (BY_NAME)            : ["000160", "000170", "back\\slash", "000110", "000120", "000150", "000140",
                                    "per%cent", "under_score", "ann-2", "Zoe-9", "000180", "000001", "000130", ASA],
            (BY_NAME_DESCENDING) : ["000180", "Zoe-9", "ann-2", "under_score", "per%cent", "000140", "000150",
                                    "000120", "000110", "back\\slash", "000160", "000170", "000001", "000130", ASA],
            (BY_COUNT)           : ["000001", "000140", "000160", "Zoe-9", "back\\slash", "per%cent", "under_score",
                                    ASA, "000110", "000130", "000150", "000170", "ann-2", "000120", "000180"],
            (BY_COUNT_DESCENDING): ["000180", "000120", "000110", "000130", "000150", "000170", "ann-2", "000001",
                                    "000140", "000160", "Zoe-9", "back\\slash", "per%cent", "under_score", ASA],
    ]


    static final List<String> DEFAULTS = ["postgres", "icu_default"]

    @Shared
    @AutoCleanup
    TestPostgres server = TestPostgres.worker()

    @Shared
    Map<String, DataSource> databases = [:]

    @Shared
    Map<String, PoolPeople> stores = [:]

    static String subject(String suffix) {
        "00000002-0000-4000-8000-000000000" + suffix
    }

    def setupSpec() {
        // DB-SPECIFIC: PostgreSQL ICU locale-provider clauses define the second ordering fixture.
        server.createDatabase("icu_default", "locale_provider icu icu_locale 'und' locale 'C'")
        DEFAULTS.each { name ->
            databases[name] = Baseline.appliedTo(server, name)
            def session = JdbcClient.create(databases[name])
            populate(session)
            stores[name] = new PoolPeople(session)
        }
        // The whole point of the second database: had it a C default too, every order below would pass
        // there for the same reason as on the first.
        assert DEFAULTS.collect { defaultProviderOf(it) } == ["c", "i"]
    }

    def "all three pool searches record the complete bound query over hundreds of current stays and a larger history"() {
        given:
        def (Connection connection, JdbcClient session) = rolledBackOn('postgres')
        def single = new SingleConnectionDataSource(connection, true)
        def started = System.nanoTime()
        // DB-SPECIFIC: PostgreSQL generate_series, ::uuid/::text casts and now() populate subjects and stays;
        // text || non-string renders the generated integer.
        session.sql("""
                insert into subjects (subject_id, kind, user_id, display_name, created_by)
                select ('00000002-0000-4000-9000-' || lpad(number::text, 12, '0'))::uuid,
                       'person', 'S4-' || lpad(number::text, 6, '0'),
                       case when number % 1000 = 1 then 'Nerith Mockvale '
                            when number % 50 = 0 then 'Mira Mockvale !_ '
                            else 'Mira Mockvale ' end || number, ?::uuid
                  from generate_series(1, ?) number
                """).params(SEEDER, history).update()
        session.sql("""
                insert into pool_members (subject_id, created_by, removed_at, removed_by)
                select subject_id, ?::uuid, case when user_id > 'S4-000300' then now() end,
                       case when user_id > 'S4-000300' then ?::uuid end
                  from subjects where user_id like 'S4-%'
                """).params(FIRST_STEWARD, FIRST_STEWARD).update()
        def inserted = System.nanoTime() - started
        started = System.nanoTime()
        session.sql("update subjects set display_name = display_name || ' renamed' where user_id like 'S4-%'").update()
        def renamed = System.nanoTime() - started
        // DB-SPECIFIC: PostgreSQL ANALYZE refreshes the statistics used by its default planner.
        session.sql('analyze subjects').update()
        session.sql('analyze pool_members').update()
        def observed = new ExplainedQueries(single)
        def searched = new PoolPeople(JdbcClient.create(observed))
        def results = []

        when:
        ['Nerith', 'Mockvale', 'a', '!_', 'S4-000001'].each { text ->
            (1..3).each { repetition ->
                def page = searched.page(query(BY_NAME, new ListFilter(text)), null)
                def found = searched.search(new PeopleSearch(text), 20)
                def outside = searched.searchOutside(new GroupId(UUID.fromString(PAYROLL)), new PeopleSearch(text), 20)
                results.add([typed: text, repetition: repetition, current: page.rows()*.userId()*.value(),
                             found: found.people()*.userId()*.value(), outside: outside.people()*.userId()*.value()])
            }
        }
        observed.save('pool-' + history, [currentAdded: 300, totalStaysAdded: history,
                      insertedNanos: inserted, renamedNanos: renamed], results)

        then:
        observed.queries.size() == 45
        observed.queries.every { it.sql.contains('stay.removed_at is null') && !it.bindings.empty }
        results.find { it.typed == 'S4-000001' }.found.first() == 'S4-000001'
        results.groupBy { it.typed }.values().every { runs -> runs*.current.toSet().size() == 1 && runs*.found.toSet().size() == 1 }

        cleanup:
        connection.rollback()
        connection.close()

        where:
        history << [300, 6000]
    }

    def "the pool lists everybody whose stay has not ended, each of them once, and nobody else"() {
        when:
        def listed = everybody(stores.postgres, BY_USER)*.userId()*.value()

        then:
        listed as Set == HOLDINGS.keySet()
        listed.size() == HOLDINGS.size()

        and: "somebody whose stay ended and somebody never brought in are absent, though both are people"
        !listed.contains("000190")
        !listed.contains("000200")
    }


    def "each person carries the estate roles they hold now and how many groups they are in now"() {
        when:
        def rows = everybody(stores.postgres, BY_USER)

        then:
        rows.collectEntries { [(it.userId().value()): [it.estateRoles(), it.groupCount()]] } == HOLDINGS

        and: "a name where one is held and none where none is, never a stand-in for it"
        rows.collectEntries { [(it.userId().value()): it.displayName()?.value()] } ==
                PEOPLE.findAll { HOLDINGS.containsKey(it[1]) }.collectEntries { [(it[1]): it[2]] } + ["000001": null]

        and: "each addressed by the subject the store holds for them"
        rows.find { it.userId().value() == "000110" }.subjectId() == new SubjectId(UUID.fromString(subject("110")))
    }

    def "every order puts the whole pool in the order a reader expects of it, whatever the database defaults to"() {
        expect:
        everybody(stores[database], order)*.userId()*.value() == EXPECTED[order]

        where:
        [database, order] << [DEFAULTS, EXPECTED.keySet() as List].combinations()
    }

    /** Ties must be exercised at every possible page boundary. */
    def "resumed after anybody, a page holds everybody after them in that order and offers nothing beyond"() {
        given:
        def asked = query(order, null)
        def whole = everybody(stores[database], order)

        when:
        def resumed = whole.collect { person ->
            def ended = new ListPosition(person.valueIn(order.column()), person.userId().value())
            stores[database].page(asked, carried(asked, ended))
        }

        then:
        whole*.userId()*.value() == EXPECTED[order]
        whole.indices.every { resumed[it].rows()*.userId()*.value() == EXPECTED[order].drop(it + 1) }

        and: "the pool being smaller than a page, none of them offers a page beyond it"
        resumed.every { it.next() == null }

        where:
        [database, order] << [DEFAULTS, EXPECTED.keySet() as List].combinations()
    }


    def "a filter narrows the pool to those whose name or user number holds what was typed, whatever its case"() {
        expect:
        page(stores.postgres, query(BY_USER, new ListFilter(typed)))*.userId()*.value() == found

        where:
        typed                                 || found
        "greno"                               || ["000140", "000150"]
        "GRENO NOCKAL"                        || ["000140", "000150"]
        SMALL_E_ACUTE + "vren"                || ["000110"]
        CAPITAL_E_ACUTE + "VREN MACK"         || ["000110"]
        SMALL_A_RING + "sa"                   || [ASA]
        "15"                                  || ["000150"]
        "an"                                  || ["000160", "000170", "ann-2"]
        "%"                                   || ["per%cent"]
        "_"                                   || ["under_score"]
        "\\"                                  || ["back\\slash"]
        "山田"                                 || ["000180"]
        YAMADA                                || ["000180"]
        "Removed"                             || []
        "Never Pooled"                        || []
        "nobody holds this"                   || []
    }

    /** Canonical normalization and full case folding must agree for stored and typed names. */
    def "a filter finds a name or a user number however either side spelt its accents or cased its letters, and a bare letter misses one its accent composes with"() {
        given:
        def (Connection connection, JdbcClient session) = rolledBackOn("postgres")
        person(session, subject("301"), "rene" + COMBINING_ACUTE + "-9", "Rene" + COMBINING_ACUTE + " Mockartes")
        stay(session, subject("301"), FIRST_STEWARD)
        person(session, subject("302"), CAPITAL_AE + "SIR-3", "Stra" + SHARP_S + "e " + CAPITAL_O_STROKE + "re")
        stay(session, subject("302"), FIRST_STEWARD)

        expect:
        page(new PoolPeople(session), query(BY_USER, new ListFilter(typed)))*.userId()*.value() == found

        cleanup:
        connection.rollback()
        connection.close()

        where:
        typed                                 || found
        "REN" + SMALL_E_ACUTE                 || ["rene" + COMBINING_ACUTE + "-9"]
        "ren" + SMALL_E_ACUTE + "-9"          || ["rene" + COMBINING_ACUTE + "-9"]
        "E" + COMBINING_ACUTE + "vren"        || ["000110"]
        "rene"                                || []
        "Rene "                               || []
        "STRASSE"                             || [CAPITAL_AE + "SIR-3"]
        SMALL_O_STROKE + "RE"                 || [CAPITAL_AE + "SIR-3"]
        SMALL_AE + "sir"                      || [CAPITAL_AE + "SIR-3"]
    }

    def "a filter and an order read on together from any row, ties included"() {
        given:
        def asked = query(BY_NAME_DESCENDING, new ListFilter("an"))

        expect:
        stores[database].page(asked, after == null ? null : carried(asked, after)).rows()*.userId()*.value() == rest

        where:
        database      | after                                     || rest
        "postgres"    | null                                      || ["ann-2", "000160", "000170"]
        "postgres"    | new ListPosition("zed Shore", "ann-2")    || ["000160", "000170"]
        "postgres"    | new ListPosition("Arlo Taffan", "000160") || ["000170"]
        "postgres"    | new ListPosition("Arlo Taffan", "000170") || []
        "icu_default" | null                                      || ["ann-2", "000160", "000170"]
        "icu_default" | new ListPosition("zed Shore", "ann-2")    || ["000160", "000170"]
        "icu_default" | new ListPosition("Arlo Taffan", "000160") || ["000170"]
        "icu_default" | new ListPosition("Arlo Taffan", "000170") || []
    }


    def "a page is one statement however many people it holds"() {
        given:
        def statements = []
        def counted = new PoolPeople(JdbcClient.create(new CountingDataSource(databases.postgres, statements)))

        when:
        def page = counted.page(query(BY_NAME, new ListFilter("0")), after)

        then:
        statements.size() == 1

        and: "over a page that did hold people, one statement for many rows being the point"
        page.rows().size() == held

        where:
        after                                          || held
        null                                           || 9
        new ListPosition("Ewra Mockner", "000120")     || 5
    }

    /** Stored user identifiers must be readable; an invalid identifier fails the whole read. */
    def "a stored user number this system will not show fails the whole read loudly, rather than being shown or left out"() {
        given:
        def (Connection connection, JdbcClient session) = rolledBackOn("postgres")
        person(session, subject("999"), " 000999", "Written By Hand")
        stay(session, subject("999"), FIRST_STEWARD)

        when:
        new PoolPeople(session).page(query(BY_USER, null), null)

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "Subject ${subject("999")} holds a user id this system will not show" as String
        failed.cause instanceof IllegalArgumentException

        cleanup:
        connection.rollback()
        connection.close()
    }

    /** Stored names must use the spacing this system accepts. */
    def "a stored name this system will not show fails the whole read loudly, naming whose row it was"() {
        given:
        def (Connection connection, JdbcClient session) = rolledBackOn("postgres")
        person(session, subject("999"), "000999", name)
        stay(session, subject("999"), FIRST_STEWARD)

        when:
        new PoolPeople(session).page(query(BY_USER, null), null)

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "Subject ${subject("999")} holds a name this system will not show" as String
        failed.cause instanceof IllegalArgumentException

        cleanup:
        connection.rollback()
        connection.close()

        where:
        name << ["Written" + IDEOGRAPHIC_SPACE + "By Hand", " Written By Hand", Character.toString(0x200B)]
    }

    def "somebody in the pool is read with what they hold, every group they are in by name, and whether a migration seeded their stay"() {
        when:
        def panel = stores.postgres.person(new SubjectId(UUID.fromString(addressed))).orElseThrow()

        then:
        panel.userId().value() == userId
        panel.displayName()?.value() == name
        panel.estateRoles() == roles as Set
        panel.groups() == groups.collect { new GroupName(it) }
        panel.seeded() == seeded

        where:
        addressed      || userId   | name                          | roles                                    | groups                                          | seeded
        FIRST_STEWARD  || "000001" | null                          | [EstateRole.STEWARD]                     | []                                              | true
        subject("110") || "000110" | CAPITAL_E_ACUTE + "vren Mack" | []                                       | ["Payroll"]                                     | true
        subject("120") || "000120" | "Ewra Mockner"                | [EstateRole.WATCHER]                     | ["finance", "Payroll"]                          | false
        subject("140") || "000140" | "Greno Nockal"                | [EstateRole.STEWARD, EstateRole.WATCHER] | []                                              | false
        subject("150") || "000150" | "greno nockal"                | []                                       | [CAPITAL_E_ACUTE + "lan"]                       | false
        subject("160") || "000160" | "Arlo Taffan"                 | []                                       | []                                              | false
        subject("170") || "000170" | "Arlo Taffan"                 | []                                       | ["finance"]                                     | false
        subject("180") || "000180" | YAMADA                        | []                                       | [CAPITAL_E_ACUTE + "lan", "finance", "Payroll"] | false
    }

    def "while two in the pool may grant an estate role, nothing anybody holds is the last that lets anybody grant"() {
        expect:
        stores.postgres.person(new SubjectId(UUID.fromString(addressed))).orElseThrow().lastGrantingRoles().isEmpty()

        where:
        addressed << [FIRST_STEWARD, subject("140"), subject("120"), subject("110")]
    }

    def "the only one in the pool who may grant an estate role holds the role letting them as the last, and nothing else as it"() {
        given:
        def (Connection connection, JdbcClient session) = rolledBackOn("postgres")
        withdrawn(session, subject("140"), "steward")
        holding(session, FIRST_STEWARD, "watcher")
        def people = new PoolPeople(session)

        when:
        def last = people.person(new SubjectId(UUID.fromString(FIRST_STEWARD))).orElseThrow()
        def watching = people.person(new SubjectId(UUID.fromString(subject("140")))).orElseThrow()

        then:
        last.lastGrantingRoles() == [EstateRole.STEWARD] as Set
        last.estateRoles() == [EstateRole.STEWARD, EstateRole.WATCHER] as Set

        and: "and a role granting nothing is the last of nothing, whoever holds it"
        watching.estateRoles() == [EstateRole.WATCHER] as Set
        watching.lastGrantingRoles().isEmpty()

        cleanup:
        connection.rollback()
        connection.close()
    }


    def "a grant held by somebody outside the pool leaves the only one inside it the last who may grant"() {
        given:
        def (Connection connection, JdbcClient session) = rolledBackOn("postgres")
        withdrawn(session, subject("140"), "steward")
        holding(session, subject("197"), "steward")

        def people = new PoolPeople(session)

        when:
        def last = people.person(new SubjectId(UUID.fromString(FIRST_STEWARD))).orElseThrow()

        then:
        last.lastGrantingRoles() == [EstateRole.STEWARD] as Set

        and: "and the holder outside is nobody the pool shows, which is why their grant is not counted"
        people.person(new SubjectId(UUID.fromString(subject("197")))).isEmpty()

        cleanup:
        connection.rollback()
        connection.close()
    }

    def "nobody in view is nobody, however they came to be missing"() {
        expect:
        stores.postgres.person(new SubjectId(UUID.fromString(addressed))).isEmpty()

        where:
        addressed << [subject("197"), subject("198"), SEEDER, SYSTEM_ACTOR, "00000009-0000-4000-8000-000000000009", PAYROLL]
    }

    def "a person is one statement however many groups they are in"() {
        given:
        def statements = []
        def counted = new PoolPeople(JdbcClient.create(new CountingDataSource(databases.postgres, statements)))

        when:
        def panel = counted.person(new SubjectId(UUID.fromString(subject("180")))).orElseThrow()

        then:
        statements.size() == 1

        and:
        panel.groups().size() == 3
    }

    def "a stored user number this system will not show fails the read of that person loudly"() {
        given:
        def (Connection connection, JdbcClient session) = rolledBackOn("postgres")
        person(session, subject("999"), " 000999", "Written By Hand")
        stay(session, subject("999"), FIRST_STEWARD)

        when:
        new PoolPeople(session).person(new SubjectId(UUID.fromString(subject("999"))))

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "Subject ${subject("999")} holds a user id this system will not show" as String

        cleanup:
        connection.rollback()
        connection.close()
    }

    def "a group name this system will not show fails the read of anybody in it loudly, rather than naming it or leaving it out"() {
        given:
        def (Connection connection, JdbcClient session) = rolledBackOn("postgres")
        person(session, subject("999"), "000999", "In A Group Named By Hand")
        stay(session, subject("999"), FIRST_STEWARD)
        group(session, "00000003-0000-4000-8000-000000000999", "LEADING", " Leading")
        member(session, "00000003-0000-4000-8000-000000000999", subject("999"), "operator")

        when:
        new PoolPeople(session).person(new SubjectId(UUID.fromString(subject("999"))))

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "A group that subject ${subject("999")} is in holds a name this system will not show" as String
        failed.cause instanceof IllegalArgumentException

        cleanup:
        connection.rollback()
        connection.close()
    }


    def "a search finds those in the pool whose user number is what was typed, or whose name holds it"() {
        expect:
        stores.postgres.search(new PeopleSearch(typed), 20).people()*.userId()*.value() == found

        where:
        typed                                     || found
        "000140"                                  || ["000140"]
        "00014"                                   || []
        "greno"                                   || ["000150", "000140"]
        "GRENO NOCKAL"                            || ["000150", "000140"]
        "an"                                      || ["000160", "000170"]
        "Zoe-9"                                   || ["Zoe-9"]
        "zoe-9"                                   || []
        "%"                                       || []
        "山田" + IDEOGRAPHIC_SPACE + "太郎"          || ["000180"]
        "Removed"                                 || []
        "Never Pooled"                            || []
        "000190"                                  || []
    }


    def "somebody whose user number is what was typed is found before everybody whose name merely holds it"() {
        given:
        def (Connection connection, JdbcClient session) = rolledBackOn("postgres")
        person(session, subject("901"), "000901", "Agent 000110")
        stay(session, subject("901"), FIRST_STEWARD)

        when:
        def found = new PoolPeople(session).search(new PeopleSearch("000110"), most)

        then:
        found.people()*.userId()*.value() == shown
        found.more() == more

        cleanup:
        connection.rollback()
        connection.close()

        where:
        most || shown                | more
        1    || ["000110"]           | true
        2    || ["000110", "000901"] | false
    }


    def "a search answers at most the most asked for, and says whether it found more"() {
        when:
        def found = stores.postgres.search(new PeopleSearch("e"), most)

        then:
        found.people()*.userId()*.value() == shown
        found.more() == more

        where:
        most || shown                                                                                   | more
        2    || ["000110", "000120"]                                                                    | true
        20   || ["000110", "000120", "000150", "000140", "per%cent", "under_score", "ann-2", "Zoe-9"]  | false
    }

    def "a search is one statement however many people it finds"() {
        given:
        def statements = []
        def counted = new PoolPeople(JdbcClient.create(new CountingDataSource(databases.postgres, statements)))

        when:
        def found = counted.search(new PeopleSearch("a"), 20)

        then:
        statements.size() == 1

        and:
        found.people().size() > 1
    }

    def "a search for nobody at all, or finding none at most, is refused rather than run"() {
        when:
        stores.postgres.search(search, most)

        then:
        def refused = thrown(expected)
        refused.message == message

        where:
        search                  | most || expected                 | message
        null                    | 20   || NullPointerException     | "PoolPeople search must not be null"
        new PeopleSearch("ari") | 0    || IllegalArgumentException | "A search must find at least one, not 0"
    }


    def "a search from inside a group finds whom the pool's search finds, leaving out whoever holds a role there now"() {
        when:
        def found = stores.postgres.searchOutside(groupId(group), new PeopleSearch(typed), most)

        then:
        found.people()*.userId()*.value() == shown
        found.more() == more

        and: "none of them anybody the pool's own search would not have found"
        stores.postgres.search(new PeopleSearch(typed), 20).people()*.userId()*.value().containsAll(shown)

        where:
        group   | typed    | most || shown                                                                  | more
        PAYROLL | "e"      | 20   || ["000150", "000140", "per%cent", "under_score", "ann-2", "Zoe-9"]      | false
        PAYROLL | "e"      | 2    || ["000150", "000140"]                                                   | true
        PAYROLL | "an"     | 20   || ["000160", "000170"]                                                   | false
        FINANCE | "an"     | 20   || ["000160"]                                                             | false
        PAYROLL | "000110" | 20   || []                                                                     | false
        FINANCE | "000110" | 20   || ["000110"]                                                             | false
    }

    def "a search from inside no group at all is refused rather than run"() {
        when:
        stores.postgres.searchOutside(group, search, 20)

        then:
        def refused = thrown(NullPointerException)
        refused.message == message

        where:
        group            | search                  || message
        null             | new PeopleSearch("ari") || "PoolPeople group must not be null"
        groupId(PAYROLL) | null                    || "PoolPeople search must not be null"
    }

    private static GroupId groupId(String spelled) {
        new GroupId(UUID.fromString(spelled))
    }

    private static ListQuery<PoolSortColumn> query(ListOrder<PoolSortColumn> order, ListFilter filter) {
        new ListQuery<>(PoolPeople.WHOLE_POOL, order, filter)
    }

    private static List<PoolPeople.PoolPerson> page(PoolPeople people, ListQuery<PoolSortColumn> query) {
        people.page(query, null).rows()
    }

    private static List<PoolPeople.PoolPerson> everybody(PoolPeople people, ListOrder<PoolSortColumn> order) {
        page(people, query(order, null))
    }


    private static ListPosition carried(ListQuery<PoolSortColumn> query, ListPosition ended) {
        ListCursor.resume(PoolPeople.LISTED, query, ListCursor.mint(PoolPeople.LISTED, query, ended))
    }

    private String defaultProviderOf(String database) {
        JdbcClient.create(databases[database])
                // DB-SPECIFIC: PostgreSQL :: casts to text; pg_database; current_database().
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
        PEOPLE.each { person(session, it[0], it[1], it[2]) }
        stay(session, subject("110"), SEEDER)
        stay(session, subject("120"), SYSTEM_ACTOR)
        ["130", "140", "150", "160", "170", "180", "191", "192", "193", "194", "195", "196"].each {
            stay(session, subject(it), FIRST_STEWARD)
        }
        endedStay(session, subject("140"))
        endedStay(session, subject("197"))

        holding(session, subject("120"), "watcher")
        holding(session, subject("140"), "steward")
        holding(session, subject("140"), "watcher")
        // DB-SPECIFIC: PostgreSQL :: casts to uuid; now().
        session.sql("""
                insert into estate_role_grants (subject_id, role, created_by, removed_at, removed_by)
                values (?::uuid, 'steward', ?::uuid, now(), ?::uuid)
                """).params(subject("160"), FIRST_STEWARD, FIRST_STEWARD).update()

        group(session, PAYROLL, "PAYROLL", "Payroll")
        group(session, FINANCE, "FINANCE", "finance")
        group(session, ELAN, "ELAN", CAPITAL_E_ACUTE + "lan")
        member(session, PAYROLL, subject("110"), "operator")
        member(session, PAYROLL, subject("120"), "operator")
        member(session, FINANCE, subject("120"), "owner")
        member(session, FINANCE, subject("130"), "operator")
        member(session, ELAN, subject("150"), "operator")
        member(session, ELAN, subject("150"), "owner")
        member(session, FINANCE, subject("170"), "overseer")
        member(session, PAYROLL, subject("180"), "operator")
        member(session, FINANCE, subject("180"), "operator")
        member(session, ELAN, subject("180"), "operator")
        member(session, ELAN, subject("192"), "operator")
        // DB-SPECIFIC: PostgreSQL :: casts to uuid; now().
        session.sql("""
                insert into group_members (group_id, subject_id, role, created_by, removed_at, removed_by, removal, removed_position)
                values (?::uuid, ?::uuid, 'operator', ?::uuid, now(), ?::uuid, 'removed_from_group', 1)
                """).params(PAYROLL, subject("170"), FIRST_STEWARD, FIRST_STEWARD).update()
    }

    private static void person(JdbcClient session, String subject, String user, String name) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        session.sql("""
                insert into subjects (subject_id, kind, user_id, display_name, created_by)
                values (?::uuid, 'person', ?, ?, ?::uuid)
                """).params(subject, user, name, SEEDER).update()
    }

    private static void stay(JdbcClient session, String subject, String author) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        session.sql("insert into pool_members (subject_id, created_by) values (?::uuid, ?::uuid)")
                .params(subject, author).update()
    }

    private static void endedStay(JdbcClient session, String subject) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid; now().
        session.sql("""
                insert into pool_members (subject_id, created_by, removed_at, removed_by)
                values (?::uuid, ?::uuid, now(), ?::uuid)
                """).params(subject, FIRST_STEWARD, FIRST_STEWARD).update()
    }

    private static void holding(JdbcClient session, String subject, String role) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid, estate_role.
        session.sql("insert into estate_role_grants (subject_id, role, created_by) values (?::uuid, ?::estate_role, ?::uuid)")
                .params(subject, role, FIRST_STEWARD).update()
    }

    private static void withdrawn(JdbcClient session, String subject, String role) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid, estate_role; now().
        session.sql("""
                update estate_role_grants set removed_at = now(), removed_by = ?::uuid
                 where subject_id = ?::uuid and role = ?::estate_role and removed_at is null
                """).params(FIRST_STEWARD, subject, role).update()
    }

    private static void group(JdbcClient session, String group, String key, String name) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        session.sql("insert into groups (group_id, key, name, created_by) values (?::uuid, ?, ?, ?::uuid)")
                .params(group, key, name, FIRST_STEWARD).update()
    }

    private static void member(JdbcClient session, String group, String subject, String role) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid, group_role.
        session.sql("""
                insert into group_members (group_id, subject_id, role, created_by)
                values (?::uuid, ?::uuid, ?::group_role, ?::uuid)
                """).params(group, subject, role, FIRST_STEWARD).update()
    }
}
