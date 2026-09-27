package org.lilradish.lite.app.pool

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.sql.Connection
import javax.sql.DataSource
import org.lilradish.lite.domain.identity.EstateRole
import org.lilradish.lite.domain.identity.GroupId
import org.lilradish.lite.domain.identity.GroupName
import org.lilradish.lite.domain.identity.SubjectId
import org.lilradish.lite.domain.identity.SystemPrincipal
import org.lilradish.lite.domain.listing.ListCursor
import org.lilradish.lite.domain.listing.ListFilter
import org.lilradish.lite.domain.listing.ListOrder
import org.lilradish.lite.domain.listing.ListPosition
import org.lilradish.lite.domain.listing.ListQuery
import org.lilradish.lite.domain.people.PeopleSearch
import org.lilradish.lite.domain.pool.PoolSortColumn
import org.lilradish.lite.testutil.Baseline
import org.lilradish.lite.testutil.CountingDataSource
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * The pool as the store answers it, asked of a real server running the real baseline. What is
 * current, how groups are counted, and which collation orders and matches a value are all decided in
 * SQL, and none of them exists anywhere but in a database.
 *
 * <p>Every collation the query names has to hold whatever the database's own default is, so the
 * orders are asked of two databases on one server: one defaulting to the C locale, which sorts by
 * byte and folds no case outside ASCII, and one defaulting to ICU, which does both the way a reader
 * would. A collation left to the default is then wrong on one of the two. The people below are chosen
 * so that every collation named orders or matches them differently from at least one default: an
 * accented capital, a pair of names differing only by case, a lower-case name after a capitalised
 * one, a user number outside ASCII, user numbers that differ only by case.
 *
 * <p>Every order is written out as a list rather than derived, so what the store answers is compared
 * with what a reader expects rather than with another reading of the store.
 *
 * <p>What a feature adds of its own is added on a connection of its own and rolled back afterwards,
 * so no feature leaves a row behind for the next to find.
 */
class PoolPeopleIntegrationSpec extends Specification {

    static final String SEEDER = "00000000-0000-4000-8000-000000000000"

    static final String SYSTEM_ACTOR = SystemPrincipal.WORKFLOW_RUNNER.subject().value().toString()

    /** Seeded by the baseline: in the pool, holding the steward role, named by nobody. */
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

    /** The subject, the user number, and the name, where one is held. */
    static final List<List<String>> PEOPLE = [
            [subject("110"), "000110", CAPITAL_E_ACUTE + "mile Zola"],
            [subject("120"), "000120", "Emma Noether"],
            [subject("130"), "000130", null],
            [subject("140"), "000140", "Grace Hopper"],
            [subject("150"), "000150", "grace hopper"],
            [subject("160"), "000160", "Alan Turing"],
            [subject("170"), "000170", "Alan Turing"],
            [subject("180"), "000180", YAMADA],
            [subject("191"), "Zoe-9", "Zoe Smith"],
            [subject("192"), "ann-2", "zed Shaw"],
            [subject("193"), "per%cent", "Per Cent"],
            [subject("194"), "under_score", "Under Score"],
            [subject("195"), "back\\slash", "Back Slash"],
            [subject("196"), ASA, null],
            [subject("197"), "000190", "Removed Person"],
            [subject("198"), "000200", "Never Pooled"],
    ]

    /** What each person in the pool holds across the estate now, and how many groups they are in. */
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

    /**
     * User numbers by code point, so a capital sorts before every small letter and a letter outside
     * ASCII after all of them. Names by the Unicode collation algorithm: an accented capital among
     * its unaccented neighbours, a small letter just before its capital, a small z among the other
     * zs, and the nameless after everybody named in both directions. Ties — the same name, the same
     * count, the nameless among themselves — by user number ascending either way.
     */
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

    /** The two defaults every order is asked under, by the name each database is created with. */
    static final List<String> DEFAULTS = ["postgres", "icu_default"]

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    Map<String, DataSource> databases = [:]

    @Shared
    Map<String, PoolPeople> stores = [:]

    static String subject(String suffix) {
        "00000002-0000-4000-8000-000000000" + suffix
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
            stores[name] = new PoolPeople(session)
        }
        // The whole point of the second database: had it a C default too, every order below would pass
        // there for the same reason as on the first.
        assert DEFAULTS.collect { defaultProviderOf(it) } == ["c", "i"]
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

    /**
     * A withdrawn grant and a membership that ended are rows still in the store, and two roles held
     * in one group are two rows of one membership: each is how a count or a set is most easily got
     * wrong, and each is somebody below.
     */
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

    /**
     * Resumed after each person in turn, so a page boundary falls between every pair of rows: a tie
     * broken by anything short of the user number repeats or skips somebody here. Each position travels
     * as the cursor a reader would carry. Where a page divides exactly, and where a row is left over,
     * is asked of the library's own spec, the pool being smaller than a page.
     */
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

    /**
     * Matched as text anywhere in either column, with case folded by the full Unicode mapping. The
     * characters a pattern would treat as wildcards and the escape a pattern would treat as an escape
     * each find only whoever holds that character, and somebody outside the pool is never found
     * however well they match.
     */
    def "a filter narrows the pool to those whose name or user number holds what was typed, whatever its case"() {
        expect:
        page(stores.postgres, query(BY_USER, new ListFilter(typed)))*.userId()*.value() == found

        where:
        typed                                 || found
        "grace"                               || ["000140", "000150"]
        "GRACE HOPPER"                        || ["000140", "000150"]
        SMALL_E_ACUTE + "mile"                || ["000110"]
        CAPITAL_E_ACUTE + "MILE ZOLA"         || ["000110"]
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

    /**
     * A name can be stored decomposed, a letter and its accent as two characters, and typed composed,
     * or the other way round; either way it is the same name. The comparison ends composed, so a
     * letter typed bare does not find a letter its accent composes with. Case is folded in full, for
     * letters that have no decomposition to fall back on as much as for those that do: a sharp s is
     * two letters folded, and a slashed o or an ash is a letter of its own.
     */
    def "a filter finds a name or a user number however either side spelt its accents or cased its letters, and a bare letter misses one its accent composes with"() {
        given:
        def (Connection connection, JdbcClient session) = rolledBackOn("postgres")
        person(session, subject("301"), "rene" + COMBINING_ACUTE + "-9", "Rene" + COMBINING_ACUTE + " Descartes")
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
        "E" + COMBINING_ACUTE + "mile"        || ["000110"]
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
        "postgres"    | new ListPosition("zed Shaw", "ann-2")     || ["000160", "000170"]
        "postgres"    | new ListPosition("Alan Turing", "000160") || ["000170"]
        "postgres"    | new ListPosition("Alan Turing", "000170") || []
        "icu_default" | null                                      || ["ann-2", "000160", "000170"]
        "icu_default" | new ListPosition("zed Shaw", "ann-2")     || ["000160", "000170"]
        "icu_default" | new ListPosition("Alan Turing", "000160") || ["000170"]
        "icu_default" | new ListPosition("Alan Turing", "000170") || []
    }

    /** Folded in by the store, so the number of statements a page costs is not the number of rows. */
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
        new ListPosition("Emma Noether", "000120")     || 5
    }

    /**
     * The store's floor admits a user number with a space in front; the type does not. Shown, it is
     * a twin of somebody else nobody can tell apart; left out, the list is not the pool. So the whole
     * read fails, and says whose row it was.
     */
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

    /** Bringing somebody in writes a name spaced, so one that is not was written round it. */
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
        subject("110") || "000110" | CAPITAL_E_ACUTE + "mile Zola" | []                                       | ["Payroll"]                                     | true
        subject("120") || "000120" | "Emma Noether"                | [EstateRole.WATCHER]                     | ["finance", "Payroll"]                          | false
        subject("140") || "000140" | "Grace Hopper"                | [EstateRole.STEWARD, EstateRole.WATCHER] | []                                              | false
        subject("150") || "000150" | "grace hopper"                | []                                       | [CAPITAL_E_ACUTE + "lan"]                       | false
        subject("160") || "000160" | "Alan Turing"                 | []                                       | []                                              | false
        subject("170") || "000170" | "Alan Turing"                 | []                                       | ["finance"]                                     | false
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

    /** Counted over the pool, so what it says is what the pool's own list already shows. */
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
        failed.message == "A group subject ${subject("999")} is in holds a name this system will not show" as String
        failed.cause instanceof IllegalArgumentException

        cleanup:
        connection.rollback()
        connection.close()
    }

    /**
     * Tried whole against a user number, case and all, and in part against a name, as a filter
     * matches, once spaced as names are: a part of a user number finds nobody by it, and somebody
     * outside the pool is never found however well they match.
     */
    def "a search finds those in the pool whose user number is what was typed, or whose name holds it"() {
        expect:
        stores.postgres.search(new PeopleSearch(typed), 20).people()*.userId()*.value() == found

        where:
        typed                                     || found
        "000140"                                  || ["000140"]
        "00014"                                   || []
        "grace"                                   || ["000150", "000140"]
        "GRACE HOPPER"                            || ["000150", "000140"]
        "an"                                      || ["000160", "000170"]
        "Zoe-9"                                   || ["Zoe-9"]
        "zoe-9"                                   || []
        "%"                                       || []
        "山田" + IDEOGRAPHIC_SPACE + "太郎"          || ["000180"]
        "Removed"                                 || []
        "Never Pooled"                            || []
        "000190"                                  || []
    }

    /** First whatever their name, so no number of names holding a user number pushes its holder past the most shown. */
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

    /** Whether more exist is learnt by finding one beyond the most, never by counting. */
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
        new PeopleSearch("ada") | 0    || IllegalArgumentException | "A search must find at least one, not 0"
    }

    /**
     * The same search and the same order, less whoever holds a role in the group now: somebody whose
     * membership there ended is found again, and somebody in another group is no member of this one.
     */
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
        null             | new PeopleSearch("ada") || "PoolPeople group must not be null"
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

    /** The position as a reader carries it: minted into the cursor a page hands out, and read back. */
    private static ListPosition carried(ListQuery<PoolSortColumn> query, ListPosition ended) {
        ListCursor.resume(PoolPeople.LISTED, query, ListCursor.mint(PoolPeople.LISTED, query, ended))
    }

    private String defaultProviderOf(String database) {
        JdbcClient.create(databases[database])
                .sql("select datlocprovider::text from pg_database where datname = current_database()")
                .query(String)
                .single()
    }

    /** A connection whose writes nobody else sees, rolled back by the feature's own cleanup. */
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
        session.sql("""
                insert into group_members (group_id, subject_id, role, created_by, removed_at, removed_by, removal)
                values (?::uuid, ?::uuid, 'operator', ?::uuid, now(), ?::uuid, 'removed_from_group')
                """).params(PAYROLL, subject("170"), FIRST_STEWARD, FIRST_STEWARD).update()
    }

    private static void person(JdbcClient session, String subject, String user, String name) {
        session.sql("""
                insert into subjects (subject_id, kind, user_id, display_name, created_by)
                values (?::uuid, 'person', ?, ?, ?::uuid)
                """).params(subject, user, name, SEEDER).update()
    }

    private static void stay(JdbcClient session, String subject, String author) {
        session.sql("insert into pool_members (subject_id, created_by) values (?::uuid, ?::uuid)")
                .params(subject, author).update()
    }

    private static void endedStay(JdbcClient session, String subject) {
        session.sql("""
                insert into pool_members (subject_id, created_by, removed_at, removed_by)
                values (?::uuid, ?::uuid, now(), ?::uuid)
                """).params(subject, FIRST_STEWARD, FIRST_STEWARD).update()
    }

    private static void holding(JdbcClient session, String subject, String role) {
        session.sql("insert into estate_role_grants (subject_id, role, created_by) values (?::uuid, ?::estate_role, ?::uuid)")
                .params(subject, role, FIRST_STEWARD).update()
    }

    private static void withdrawn(JdbcClient session, String subject, String role) {
        session.sql("""
                update estate_role_grants set removed_at = now(), removed_by = ?::uuid
                 where subject_id = ?::uuid and role = ?::estate_role and removed_at is null
                """).params(FIRST_STEWARD, subject, role).update()
    }

    private static void group(JdbcClient session, String group, String key, String name) {
        session.sql("insert into groups (group_id, key, name, created_by) values (?::uuid, ?, ?, ?::uuid)")
                .params(group, key, name, FIRST_STEWARD).update()
    }

    private static void member(JdbcClient session, String group, String subject, String role) {
        session.sql("""
                insert into group_members (group_id, subject_id, role, created_by)
                values (?::uuid, ?::uuid, ?::group_role, ?::uuid)
                """).params(group, subject, role, FIRST_STEWARD).update()
    }
}
