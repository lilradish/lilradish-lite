package com.lilradish.lite.migration.fixture

import static com.lilradish.lite.migration.fixture.SchemaKeys.*

import com.lilradish.lite.testsupport.TestPostgres
import java.sql.Connection
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import org.postgresql.util.PSQLException
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * A server of the version production runs, migrated as the runner migrates and seeded with every
 * migration's fixture, one for each spec extending this; and the ways a case asks it something.
 */
abstract class SchemaConstraintSpecification extends Specification {

    static final List<String> FIXTURE = [*SubjectCases.FIXTURE, *PoolCases.FIXTURE, *EstateRoleCases.FIXTURE, *GroupCases.FIXTURE,
                                         *PeopleCases.FIXTURE, *EntryCases.FIXTURE, *LibraryCases.FIXTURE, *StepAndDeclarationCases.FIXTURE,
                                         *RunCases.FIXTURE, *ProductionAndCallCases.FIXTURE, *SoundnessCases.FIXTURE, *GroupCurrencyCases.FIXTURE, *FileCases.FIXTURE]

    /** Each with whether it is a path; the longest is the longest held, one character short of refused. */
    static final List<List<Object>> PATHS = [
        ["a", true],
        ["a.b", true],
        ["details.order_reference", true],
        ["a" * 63 + ".b", true],
        ["a" + ".a" * 511, true],
        ["a" * 64 + ".b", false],
        ["", false],
        ["a.", false],
        [".a", false],
        ["a..b", false],
        ["a.B", false],
        ["a[0]", false],
        ["a.0", false],
        ["a/b", false],
        ["a. b", false],
    ]

    @Shared
    @AutoCleanup
    TestPostgres server = TestPostgres.isolated()

    /** One session for the suite. Every case is its own statement, and a failed statement in
     * autocommit leaves the session usable, so a connection per statement buys nothing. */
    @Shared
    @AutoCleanup
    Connection session

    def setupSpec() {
        DataSource database = server.postgresDatabase
        PgTrgm.preparedIn(database)
        /* The location and the two flags are the runner's own, from its application.yaml. Without
         * them this path accepts what the deploy path refuses — a location holding nothing, or a
         * file whose name Flyway will not parse — and the build goes green over an artefact that
         * cannot start. The location is also what keeps the development fixture out of this server:
         * it sits under the same root, and a root is scanned recursively. */
        Flyway.configure()
                .dataSource(database)
                .schemas("app")
                .locations("classpath:db/migration/common")
                .failOnMissingLocations(true)
                .validateMigrationNaming(true)
                .load()
                .migrate()
        session = database.connection
        /* Committed before any fixture names them: a label added to a type that already exists cannot
         * be used in the transaction that added it. */
        // DB-SPECIFIC: alter type uses PostgreSQL's syntax and semantics.
        [CODE_STEP, OTHER_CODE_STEP].each { execute("alter type app.code_step add value '${it}'") }
        FIXTURE.each { execute(it) }
    }

    /**
     * The whole of a table rather than its cardinality, because half of these cases are updates and
     * an update that lands leaves the count alone. What it does not see is a write to any other
     * table — a trigger's or a cascade's — so it is a probe on the table the case names and not a
     * statement that nothing anywhere moved. Nor is it a guard against a partial write: every case
     * in CASES is one statement, and one statement is atomic.
     */
    protected String contentsOf(String table) {
        // DB-SPECIFIC: ::, string_agg(), md5() and text use PostgreSQL's syntax and semantics.
        queryForStrings("select coalesce(md5(string_agg(t::text, '|' order by t::text)), 'empty')" +
                " from app.${table} t").first()
    }

    /**
     * Run inside a transaction that is always rolled back, so a row admitted here is there for no later
     * case. Deferred rules are brought forward before it is judged, or they would be judged by nobody.
     */
    protected String attempt(String table, String statement) {
        def before = contentsOf(table)
        session.autoCommit = false
        try {
            execute(statement)
            execute("set constraints all immediate")
            assert contentsOf(table) != before, "admitted, yet ${table} holds what it held before"
            null
        } catch (PSQLException refused) {
            def rule = refused.serverErrorMessage?.constraint
            if (rule == null) {
                throw refused
            }
            rule
        } finally {
            session.rollback()
            session.autoCommit = true
        }
    }

    /** What a query reads back of a statement run inside a transaction that is always rolled back. */
    protected String readInside(String statement, String query) {
        session.autoCommit = false
        try {
            execute(statement)
            queryForStrings(query).first()
        } finally {
            session.rollback()
            session.autoCommit = true
        }
    }

    protected void execute(String statement) {
        session.createStatement().withCloseable { it.execute(statement) }
    }

    protected Set<String> queryForStrings(String query) {
        session.createStatement().withCloseable { statement ->
            statement.executeQuery(query).withCloseable { rows ->
                def found = [] as Set
                while (rows.next()) {
                    found << rows.getString(1)
                }
                found
            }
        }
    }
}
