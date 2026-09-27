package org.lilradish.lite.app.people

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.sql.Connection
import javax.sql.DataSource
import org.lilradish.lite.domain.identity.SubjectId
import org.lilradish.lite.domain.people.PeopleSearch
import org.lilradish.lite.testutil.Baseline
import org.lilradish.lite.testutil.CountingDataSource
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * The directory as a search reaches it, asked of a real server running the real baseline: which rows
 * a search finds, how they are ordered and folded, and whether each of them is in the pool now are
 * all decided in SQL.
 *
 * <p>Asked of a database defaulting to the C locale, which folds no case outside ASCII and sorts by
 * byte, so a match or an order left to the default would be wrong here. Every expectation is written
 * out rather than derived, so what the store answers is compared with what a reader expects.
 */
class PeopleIntegrationSpec extends Specification {

    static final String SEEDER = "00000000-0000-4000-8000-000000000000"

    static final String FIRST_STEWARD = "00000001-0000-4000-8000-000000000001"

    static final String POOLED = "00000002-0000-4000-8000-000000000301"

    static final String REMOVED = "00000002-0000-4000-8000-000000000303"

    static final String SMALL_E_ACUTE = Character.toString(0xE9)

    static final String CAPITAL_E_ACUTE = Character.toString(0xC9)

    static final String IDEOGRAPHIC_SPACE = Character.toString(0x3000)

    static final String NO_BREAK_SPACE = Character.toString(0x00A0)

    static final String MIXED_WHITESPACE_NAME = NO_BREAK_SPACE + "Hedy" + IDEOGRAPHIC_SPACE + Character.toString(0x2028) +
            " Kiesler" + Character.toString(0x202F) + " " + "Lamarr" + Character.toString(0x2029)

    static final String ONLY_WHITESPACE_NAME = IDEOGRAPHIC_SPACE + " " + NO_BREAK_SPACE

    static final String NOTHING_VISIBLE_NAME = " " + Character.toString(0x200B) + " "

    /** The user number and the name the directory holds for them. */
    static final List<List<String>> IN_DIRECTORY = [
            ["000301", "Ada Lovelace"],
            ["000302", "Ada Yonath"],
            ["000303", "Adam Smith"],
            ["000304", "grace hopper"],
            ["000305", "Grace Hopper"],
            ["000306", CAPITAL_E_ACUTE + "mile Zola"],
            ["000307", "山田" + IDEOGRAPHIC_SPACE + "太郎"],
            ["000308", "Alan Turing"],
            ["000309", "Alan Turing"],
            ["000310", "Under_Score"],
            ["AB-12", "Upper Case Number"],
    ]

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    DataSource database

    @Shared
    People people

    def setupSpec() {
        database = Baseline.appliedTo(server, "postgres")
        def session = JdbcClient.create(database)
        IN_DIRECTORY.each { inDirectory(session, it[0], it[1]) }
        session.sql("""
                insert into subjects (subject_id, kind, user_id, display_name, created_by) values
                    (?::uuid, 'person', '000301', 'Ada Lovelace', ?::uuid),
                    (?::uuid, 'person', '000303', 'Adam Smith', ?::uuid)
                """).params(POOLED, SEEDER, REMOVED, SEEDER).update()
        session.sql("insert into pool_members (subject_id, created_by) values (?::uuid, ?::uuid)")
                .params(POOLED, FIRST_STEWARD).update()
        session.sql("""
                insert into pool_members (subject_id, created_by, removed_at, removed_by)
                values (?::uuid, ?::uuid, now(), ?::uuid)
                """).params(REMOVED, FIRST_STEWARD, FIRST_STEWARD).update()
        people = new People(session)
    }

    /**
     * Tried both ways on whatever was typed. A user number is found whole and exactly, its case being
     * the identity provider's; a name is found by any part of it, folded as the pool's filter folds.
     * A character a pattern would read as a wildcard finds only whoever holds that character. A name
     * typed back as shown misses one the directory holds with another space.
     */
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
        "ada"                         || ["000301", "000302", "000303"]
        "ADA "                        || ["000301", "000302"]
        "Lovelace"                    || ["000301"]
        "hopper"                      || ["000304", "000305"]
        "Alan Turing"                 || ["000308", "000309"]
        SMALL_E_ACUTE + "mile"        || ["000306"]
        "Emile"                       || []
        "太郎"                          || ["000307"]
        "山田" + IDEOGRAPHIC_SPACE      || ["000307"]
        "山田 太郎"                       || []
        "_"                           || ["000310"]
        "%"                           || []
        "nobody holds this"           || []
    }

    /**
     * Somebody in the pool is found like anybody else and carries what addresses them there; somebody
     * whose stay ended, or who was never brought in, carries nothing, being in the pool now neither.
     */
    def "each person found says whether they are in the pool now, by the subject addressing them there"() {
        when:
        def pooled = found("ada").collectEntries { [(it.userId().value()): it.subjectId()] }

        then:
        pooled == ["000301": new SubjectId(UUID.fromString(POOLED)), "000302": null, "000303": null]

        and: "each under the name the directory holds, whatever the pool holds"
        found("ada")*.displayName()*.value() == ["Ada Lovelace", "Ada Yonath", "Adam Smith"]
    }

    /**
     * The directory's floor admits every White_Space code point but the controls. Found by name in part
     * or by number whole, a name is shown spaced once; one holding nothing visible is shown as none.
     */
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
        "hedy"   | "000321" | MIXED_WHITESPACE_NAME || "Hedy Kiesler Lamarr"
        "000322" | "000322" | ONLY_WHITESPACE_NAME  || null
        "000323" | "000323" | NOTHING_VISIBLE_NAME  || null
    }

    /** One past the most is how more are known of, so the boundary is where a count would go wrong. */
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

    /**
     * More names hold what was typed than a search shows, and the one person whose user number it is
     * sorts after all of them by name: they are found first all the same, so no crowd of names can
     * hide the one person named whole.
     */
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
        people.search(new PeopleSearch("ada"), 0)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "A search must find at least one, not 0"
    }

    /**
     * The directory's floor admits a user number with a space in front; the type does not. Shown, it
     * is a twin of somebody else nobody can tell apart; left out, the search lies about the directory.
     */
    def "a stored user number this system will not show fails the search loudly, rather than being shown or left out"() {
        given:
        def connection = database.connection
        connection.autoCommit = false
        def session = JdbcClient.create(new SingleConnectionDataSource(connection, true))
        inDirectory(session, " 000399", "Ada Written By Hand")

        when:
        new People(session).search(new PeopleSearch("ada"), 20)

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
