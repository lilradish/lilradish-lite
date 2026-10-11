package com.lilradish.lite.app.people

import com.lilradish.lite.domain.identity.SubjectId
import com.lilradish.lite.domain.people.PeopleSearch
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

/** Ordering must be independent of the database default collation. */
class PeopleIntegrationSpec extends Specification {

    static final String SEEDER = "00000000-0000-4000-8000-000000000000"

    static final String FIRST_STEWARD = "00000001-0000-4000-8000-000000000001"

    static final String POOLED = "00000002-0000-4000-8000-000000000301"

    static final String REMOVED = "00000002-0000-4000-8000-000000000303"

    static final String SMALL_E_ACUTE = Character.toString(0xE9)

    static final String CAPITAL_E_ACUTE = Character.toString(0xC9)

    static final String IDEOGRAPHIC_SPACE = Character.toString(0x3000)

    static final String NO_BREAK_SPACE = Character.toString(0x00A0)

    static final String MIXED_WHITESPACE_NAME = NO_BREAK_SPACE + "Heno" + IDEOGRAPHIC_SPACE + Character.toString(0x2028) +
            " Mockler" + Character.toString(0x202F) + " " + "Lomore" + Character.toString(0x2029)

    static final String ONLY_WHITESPACE_NAME = IDEOGRAPHIC_SPACE + " " + NO_BREAK_SPACE

    static final String NOTHING_VISIBLE_NAME = " " + Character.toString(0x200B) + " "


    static final List<List<String>> IN_DIRECTORY = [
            ["000301", "Ari Mockvale"],
            ["000302", "Ari Zomock"],
            ["000303", "Arin Moxen"],
            ["000304", "greno nockal"],
            ["000305", "Greno Nockal"],
            ["000306", CAPITAL_E_ACUTE + "vren Mack"],
            ["000307", "山田" + IDEOGRAPHIC_SPACE + "太郎"],
            ["000308", "Arlo Taffan"],
            ["000309", "Arlo Taffan"],
            ["000310", "Under_Score"],
            ["AB-12", "Upper Case Number"],
    ]

    @Shared
    @AutoCleanup
    TestPostgres server = TestPostgres.worker()

    @Shared
    DataSource database

    @Shared
    People people

    def setupSpec() {
        database = Baseline.appliedTo(server, "postgres")
        def session = JdbcClient.create(database)
        IN_DIRECTORY.each { inDirectory(session, it[0], it[1]) }
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        session.sql("""
                insert into subjects (subject_id, kind, user_id, display_name, created_by) values
                    (?::uuid, 'person', '000301', 'Ari Mockvale', ?::uuid),
                    (?::uuid, 'person', '000303', 'Arin Moxen', ?::uuid)
                """).params(POOLED, SEEDER, REMOVED, SEEDER).update()
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        session.sql("insert into pool_members (subject_id, created_by) values (?::uuid, ?::uuid)")
                .params(POOLED, FIRST_STEWARD).update()
        // DB-SPECIFIC: PostgreSQL :: casts to uuid; now().
        session.sql("""
                insert into pool_members (subject_id, created_by, removed_at, removed_by)
                values (?::uuid, ?::uuid, now(), ?::uuid)
                """).params(REMOVED, FIRST_STEWARD, FIRST_STEWARD).update()
        people = new People(session)
    }


    def "the real directory search records its complete plan at a small and a larger directory size"() {
        given:
        def connection = database.connection
        connection.autoCommit = false
        def single = new SingleConnectionDataSource(connection, true)
        def session = JdbcClient.create(single)
        def started = System.nanoTime()
        // DB-SPECIFIC: PostgreSQL generate_series and ::text populate the directory;
        // text || non-string converts the generated integer to text.
        session.sql("""
                insert into people (user_id, display_name)
                select 'S4-' || lpad(number::text, 6, '0'),
                       case when number % 1000 = 1 then 'Nerith Mockvale '
                            when number % 50 = 0 then 'Mira Mockvale !_ '
                            else 'Mira Mockvale ' end || number
                  from generate_series(1, ?) number
                """).param(size).update()
        def inserted = System.nanoTime() - started
        started = System.nanoTime()
        session.sql("update people set display_name = display_name || ' renamed' where user_id like 'S4-%'").update()
        def renamed = System.nanoTime() - started
        // DB-SPECIFIC: PostgreSQL ANALYZE refreshes the statistics used by its default planner.
        session.sql('analyze people').update()
        def observed = new ExplainedQueries(single)
        def searched = new People(JdbcClient.create(observed))
        def results = []

        when:
        ['Nerith', 'Mockvale', 'a', '!_', 'S4-000001'].each { text ->
            (1..3).each { repetition ->
                def found = searched.search(new PeopleSearch(text), 20)
                results.add([typed: text, repetition: repetition,
                             ids: found.people()*.userId()*.value(), more: found.more()])
            }
        }
        observed.save('people-' + size, [directoryAdded: size, insertedNanos: inserted, renamedNanos: renamed], results)

        then:
        observed.queries.size() == 15
        observed.queries.every { it.sql.contains('left join pool_members') && it.sql.contains('limit ?') && !it.bindings.empty }
        results.groupBy { it.typed }.values().every { runs -> runs*.ids.toSet().size() == 1 && runs*.more.toSet().size() == 1 }
        results.find { it.typed == 'S4-000001' }.ids.first() == 'S4-000001'

        cleanup:
        connection.rollback()
        connection.close()

        where:
        size << [300, 6000]
    }

    def "a search finds a user number typed whole and a name holding what was typed, in the order a reader expects"() {
        expect:
        found(typed)*.userId()*.value() == expected

        where:
        typed                         || expected
        "000301"                      || ["000301"]
        "00030"                       || []
        "0003"                        || []
        "AB-12"                       || ["AB-12"]
        "ab-12"                       || []
        "ari"                         || ["000301", "000302", "000303"]
        "ARI "                        || ["000301", "000302"]
        "Mockvale"                    || ["000301"]
        "nockal"                      || ["000304", "000305"]
        "Arlo Taffan"                 || ["000308", "000309"]
        SMALL_E_ACUTE + "vren"        || ["000306"]
        "Evren"                       || []
        "太郎"                          || ["000307"]
        "山田" + IDEOGRAPHIC_SPACE      || ["000307"]
        "山田 太郎"                       || []
        "_"                           || ["000310"]
        "%"                           || []
        "nobody holds this"           || []
    }


    def "each person found says whether they are in the pool now, by the subject addressing them there"() {
        when:
        def pooled = found("ari").collectEntries { [(it.userId().value()): it.subjectId()] }

        then:
        pooled == ["000301": new SubjectId(UUID.fromString(POOLED)), "000302": null, "000303": null]

        and: "each under the name the directory holds, whatever the pool holds"
        found("ari")*.displayName()*.value() == ["Ari Mockvale", "Ari Zomock", "Arin Moxen"]
    }


    def "each person is found under the name the directory holds spaced once, and under none where it holds nothing visible"() {
        given:
        def connection = database.connection
        connection.autoCommit = false
        def session = JdbcClient.create(new SingleConnectionDataSource(connection, true))
        inDirectory(session, user, held)

        when:
        def search = new People(session).search(new PeopleSearch(typed), 20)

        then:
        search.people()*.userId()*.value() == [user]
        search.people()*.displayName()*.value() == [spaced]

        and: "the directory's own row read and never written back"
        session.sql("select display_name from people where user_id = ?").param(user).query(String).list() == [held]

        cleanup:
        connection.rollback()
        connection.close()

        where:
        typed    | user     | held                  || spaced
        "heno"   | "000321" | MIXED_WHITESPACE_NAME || "Heno Mockler Lomore"
        "000322" | "000322" | ONLY_WHITESPACE_NAME  || null
        "000323" | "000323" | NOTHING_VISIBLE_NAME  || null
    }


    def "a search stops at the most it was asked for, and says whether the directory holds more it finds"() {
        when:
        def search = people.search(new PeopleSearch("a"), most)

        then:
        search.people().size() == shown
        search.more() == more

        where:
        most || shown | more
        1    || 1     | true
        8    || 8     | true
        9    || 9     | false
        10   || 9     | false
    }


    def "somebody whose user number was typed whole is found first, however many names hold it"() {
        given:
        def connection = database.connection
        connection.autoCommit = false
        def session = JdbcClient.create(new SingleConnectionDataSource(connection, true))
        inDirectory(session, "7a", "Zed Zulu")
        (1..25).each { inDirectory(session, "000" + (700 + it), "Unit 7a " + it) }

        when:
        def search = new People(session).search(new PeopleSearch("7a"), 20)

        then:
        search.people().first().userId().value() == "7a"
        search.people().size() == 20
        search.more()

        and: "the rest by name, the crowd cut rather than the one named whole"
        search.people().tail().every { it.displayName().value().startsWith("Unit 7a") }

        cleanup:
        connection.rollback()
        connection.close()
    }

    def "a search is one statement however many people it finds"() {
        given:
        def statements = []
        def counted = new People(JdbcClient.create(new CountingDataSource(database, statements)))

        when:
        def search = counted.search(new PeopleSearch("a"), 20)

        then:
        statements.size() == 1

        and: "over a search that did find several, one statement for many rows being the point"
        search.people().size() == 9
    }

    def "a search asked to find fewer than one person is refused"() {
        when:
        people.search(new PeopleSearch("ari"), 0)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "A search must find at least one, not 0"
    }

    /** Stored user identifiers must be readable; an invalid identifier fails the whole search. */
    def "a stored user number this system will not show fails the search loudly, rather than being shown or left out"() {
        given:
        def connection = database.connection
        connection.autoCommit = false
        def session = JdbcClient.create(new SingleConnectionDataSource(connection, true))
        inDirectory(session, " 000399", "Ari Written By Hand")

        when:
        new People(session).search(new PeopleSearch("ari"), 20)

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "Somebody in the directory holds a user number this system will not show"
        failed.cause instanceof IllegalArgumentException

        cleanup:
        connection.rollback()
        connection.close()
    }

    private List<People.InDirectory> found(String typed) {
        people.search(new PeopleSearch(typed), 20).people()
    }

    private static void inDirectory(JdbcClient session, String user, String name) {
        session.sql("insert into people (user_id, display_name) values (?, ?)").params(user, name).update()
    }
}
