package org.lilradish.lite.app.estate

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.sql.Connection
import org.flywaydb.core.Flyway
import org.lilradish.lite.domain.identity.EstateRole
import org.lilradish.lite.domain.identity.UserId
import org.postgresql.Driver
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * Who holds what across the estate, asked of a real server running the real baseline. What decides
 * this is a join and a partial index, and neither exists anywhere but in a database: a stub standing
 * in for the store would be asserting the answers this file was written to discover.
 *
 * <p>The engine is pinned to production's, because a rule proved on another engine has been proved
 * about another engine. Testcontainers would be the usual way and needs a container runtime, which
 * this machine has none of.
 */
class EstateRoleGrantsIntegrationSpec extends Specification {

    /** Seeded by the baseline, holding the estate's first role. Nothing in this system granted it. */
    static final UserId FIRST_STEWARD = new UserId("000001")

    static final UserId NOBODY = new UserId("there-is-no-such-user")

    static final String SEEDER = "00000000-0000-4000-8000-000000000000"

    static final String WATCHER_SUBJECT = "00000001-0000-4000-8000-0000000000a1"

    static final UserId WATCHER = new UserId("000002")

    static final String UNHELD_SUBJECT = "00000001-0000-4000-8000-0000000000a2"

    static final UserId HOLDS_NOTHING = new UserId("000003")

    static final String WITHDRAWN_SUBJECT = "00000001-0000-4000-8000-0000000000a3"

    static final UserId ONCE_A_STEWARD = new UserId("000004")

    /**
     * Demanded rather than defaulted, for the reason every spec reading it gives: interpolated
     * absent, {@code filesystem:null} makes Flyway create an empty schema and apply nothing, and
     * every feature below then reports an empty estate rather than a missing input.
     */
    static final String MIGRATIONS = Objects.requireNonNull(System.getProperty("baseline.location"),
            "baseline.location was not set; the build names it to test and to pitest")

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    EstateRoleGrants grants

    @Shared
    JdbcClient session

    def setupSpec() {
        Flyway.configure()
                .dataSource(server.postgresDatabase)
                .schemas("app")
                .locations("filesystem:" + MIGRATIONS)
                .failOnMissingLocations(true)
                .validateMigrationNaming(true)
                .load()
                .migrate()
        // The schema the query runs against is the deployer's to place on the search path, which is
        // why no statement in this system names one.
        def database = new SimpleDriverDataSource(new Driver(),
                "jdbc:postgresql://localhost:${server.port}/postgres?currentSchema=app", "postgres", "")
        database.connection.withCloseable { Connection session ->
            session.createStatement().withCloseable { statement ->
                statement.execute("""
                    insert into subjects (subject_id, kind, user_id, created_by) values
                        ('${WATCHER_SUBJECT}', 'person', '${WATCHER.value()}', '${SEEDER}'),
                        ('${UNHELD_SUBJECT}', 'person', '${HOLDS_NOTHING.value()}', '${SEEDER}'),
                        ('${WITHDRAWN_SUBJECT}', 'person', '${ONCE_A_STEWARD.value()}', '${SEEDER}')
                    """)
                statement.execute("""
                    insert into estate_role_grants (subject_id, role, created_by) values
                        ('${WATCHER_SUBJECT}', 'watcher', '${SEEDER}'),
                        ('${WATCHER_SUBJECT}', 'steward', '${SEEDER}')
                    """)
                statement.execute("""
                    insert into estate_role_grants
                            (subject_id, role, created_by, removed_at, removed_by) values
                        ('${WITHDRAWN_SUBJECT}', 'steward', '${SEEDER}', now(), '${WATCHER_SUBJECT}')
                    """)
            }
        }
        session = JdbcClient.create(database)
        grants = new EstateRoleGrants(session)
    }

    /**
     * The deployment's first authority, and the only one no act of this system produced. It is read
     * here exactly as any other holding is, which is the point: what arrives from outside becomes an
     * ordinary row and is answered by the ordinary query.
     */
    def "the user the baseline seeds holds the estate's first role when they sign in"() {
        expect:
        grants.heldBy(FIRST_STEWARD) == [EstateRole.STEWARD] as Set

        and: "and only that one, a seeded steward being no watcher"
        !grants.heldBy(FIRST_STEWARD).contains(EstateRole.WATCHER)
    }

    def "somebody granted both roles holds both, the two being recorded as two rows rather than one"() {
        expect:
        grants.heldBy(WATCHER) == EstateRole.values() as Set
    }

    /**
     * The row this endpoint is most easily got wrong on, and the reason it is a row at all. An
     * user number that names nobody here is not an error and not a missing resource: it holds
     * nothing, which is the same answer as somebody known holding nothing, and the two are answered
     * alike so that neither can be told from the other by changing what is sent.
     */
    def "a user number naming nobody answers as one naming somebody who holds nothing"() {
        expect:
        grants.heldBy(NOBODY).isEmpty()

        and:
        grants.heldBy(HOLDS_NOTHING).isEmpty()

        and: "over a store that does answer somebody, so the emptiness is a reading and not a silence"
        !grants.heldBy(FIRST_STEWARD).isEmpty()
    }

    /**
     * Nothing caches this, so the only thing between a withdrawal and its effect is the predicate.
     * A grant that was withdrawn is a row that is still there — the table keeps the history — and a
     * query that forgot the predicate would go on answering with it.
     */
    def "a withdrawn grant is held no longer, though the row recording it remains"() {
        expect:
        grants.heldBy(ONCE_A_STEWARD).isEmpty()

        and: "while the row is still in the store, so this is the predicate and not a deletion"
        rowsRecordedFor(WITHDRAWN_SUBJECT) == 1
    }

    def "a holding a caller was handed cannot be widened through the set it came back in"() {
        given:
        def held = grants.heldBy(FIRST_STEWARD)

        when:
        held.add(EstateRole.WATCHER)

        then:
        thrown(UnsupportedOperationException)

        and:
        grants.heldBy(FIRST_STEWARD) == [EstateRole.STEWARD] as Set
    }

    private int rowsRecordedFor(String subject) {
        session.sql("select count(*) from estate_role_grants where subject_id = ?::uuid")
                .param(subject)
                .query(Integer.class)
                .single()
    }
}
