package org.lilradish.lite.testutil

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import org.postgresql.Driver
import org.springframework.jdbc.datasource.SimpleDriverDataSource

/**
 * The real baseline applied to a database on an embedded server, and a connection to it that leaves
 * the schema to the search path as a deployment does. No statement in this system names a schema, so
 * the one the baseline was applied into is put on the path here and nowhere else.
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

    static DataSource appliedTo(EmbeddedPostgres server, String database) {
        Flyway.configure()
                .dataSource(connectedTo(server, database, ""))
                .schemas("app")
                .locations("filesystem:" + MIGRATIONS)
                .failOnMissingLocations(true)
                .validateMigrationNaming(true)
                .load()
                .migrate()
        connectedTo(server, database, "?currentSchema=app")
    }

    private static DataSource connectedTo(EmbeddedPostgres server, String database, String options) {
        new SimpleDriverDataSource(new Driver(), "jdbc:postgresql://localhost:${server.port}/${database}${options}",
                "postgres", "")
    }
}
