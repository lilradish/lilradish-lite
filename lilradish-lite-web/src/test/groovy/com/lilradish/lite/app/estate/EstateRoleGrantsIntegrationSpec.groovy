package com.lilradish.lite.app.estate

import com.lilradish.lite.domain.identity.EstateRole
import com.lilradish.lite.domain.identity.UserId
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.PgTrgm
import java.sql.Connection
import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.simple.JdbcClient
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/** Tests use the database engine version pinned for deployment. */
class EstateRoleGrantsIntegrationSpec extends Specification {


    static final UserId FIRST_STEWARD = new UserId("000001")

    static final UserId NOBODY = new UserId("there-is-no-such-user")

    static final String SEEDER = "00000000-0000-4000-8000-000000000000"

    static final String WATCHER_SUBJECT = "00000001-0000-4000-8000-0000000000a1"

    static final UserId WATCHER = new UserId("000002")

    static final String UNHELD_SUBJECT = "00000001-0000-4000-8000-0000000000a2"

    static final UserId HOLDS_NOTHING = new UserId("000003")

    static final String WITHDRAWN_SUBJECT = "00000001-0000-4000-8000-0000000000a3"

    static final UserId ONCE_A_STEWARD = new UserId("000004")

    /** The baseline location must be explicit so an empty Flyway migration set cannot pass. */
    static final String MIGRATIONS = Objects.requireNonNull(System.getProperty("baseline.location"),
            "baseline.location was not set; the build names it to test and to pitest")

    @Shared
    @AutoCleanup
    TestPostgres server = TestPostgres.worker()

    @Shared
    EstateRoleGrants grants

    @Shared
    JdbcClient session

    def setupSpec() {
        PgTrgm.preparedIn(server.postgresDatabase)
        Flyway.configure()
                .dataSource(server.postgresDatabase)
                .schemas("app")
                .locations("filesystem:" + MIGRATIONS)
                .failOnMissingLocations(true)
                .validateMigrationNaming(true)
                .load()
                .migrate()
        // The schema the query runs against is the deployer's to place on the search path.
        server.rememberBaseline()
        def database = server.dataSource("?currentSchema=app")
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
                // DB-SPECIFIC: PostgreSQL now().
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

    /** Unknown identities and known identities holding no role must receive indistinguishable answers. */
    def "a user number naming nobody answers as one naming somebody who holds nothing"() {
        expect:
        grants.heldBy(NOBODY).isEmpty()

        and:
        grants.heldBy(HOLDS_NOTHING).isEmpty()

        and: "over a store that does answer somebody, so the emptiness is a reading and not a silence"
        !grants.heldBy(FIRST_STEWARD).isEmpty()
    }


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
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        session.sql("select count(*) from estate_role_grants where subject_id = ?::uuid")
                .param(subject)
                .query(Integer.class)
                .single()
    }
}
