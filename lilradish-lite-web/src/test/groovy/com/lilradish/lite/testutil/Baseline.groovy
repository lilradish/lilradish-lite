package com.lilradish.lite.testutil

import com.lilradish.lite.testsupport.TestPostgres
import javax.sql.DataSource
import org.flywaydb.core.Flyway

/**
 * The real baseline applied to a database on an embedded server, and a connection to it that leaves
 * the schema to the search path as a deployment does. Application relations resolve through that
 * path, so the one the baseline was applied into is put on it here and nowhere else.
 */
final class Baseline {

    /**
     * Demanded rather than defaulted: interpolated absent, {@code filesystem:null} makes Flyway create
     * an empty schema and apply nothing, and every spec then reports an empty store rather than a
     * missing input.
     */
    static final String MIGRATIONS = Objects.requireNonNull(System.getProperty("baseline.location"),
            "baseline.location was not set; the build names it to test and to pitest")

    private Baseline() {}

    static DataSource appliedTo(TestPostgres server, String database) {
        migrate(server, database)
        server.rememberBaseline(database)
        server.dataSource(database, "?currentSchema=app")
    }

    static void prepareWorker(TestPostgres server) {
        server.prepareBaseline { migrate(server, "postgres") }
    }

    private static int migrate(TestPostgres server, String database) {
        PgTrgm.preparedIn(connectedTo(server, database, ""))
        Flyway.configure()
                .dataSource(connectedTo(server, database, ""))
                .schemas("app")
                .locations("filesystem:" + MIGRATIONS)
                .failOnMissingLocations(true)
                .validateMigrationNaming(true)
                .load()
                .migrate()
                .migrationsExecuted
    }

    private static DataSource connectedTo(TestPostgres server, String database, String options) {
        server.dataSource(database, options)
    }
}
