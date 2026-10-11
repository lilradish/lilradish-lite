package com.lilradish.lite.migration

import com.lilradish.lite.testsupport.TestPostgres
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.postgresql.util.PSQLException
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class PgTrgmPrerequisiteIntegrationSpec extends Specification {

    @Shared
    @AutoCleanup
    TestPostgres server = TestPostgres.isolated()

    def "a migration role without database creation rights is refused before business DDL when pg_trgm is absent"() {
        given:
        def target = databaseWithMigrationRole()
        noInstallationRights(target.migration)
        assert extensionSchema(target.administrator) == null

        when:
        migrateFirst(target.migration)

        then:
        def failure = thrown(FlywayException)
        failure.message.contains("pg_trgm must be installed by the database administrator before migrations are run")
        sqlCauseOf(failure).SQLState == "P0001"
        extensionSchema(target.administrator) == null
        noBusinessObjects(target.administrator)
    }

    def "pg_trgm in another schema is refused before business DDL when the migration role has no USAGE there"() {
        given:
        def target = databaseWithMigrationRole()
        installIn(target.administrator, '"Demo Sample extension"')
        noInstallationRights(target.migration)
        // DB-SPECIFIC: PostgreSQL checks USAGE on the installed extension's actual schema.
        assert !query(target.migration, "select has_schema_privilege('Demo Sample extension', 'USAGE')")
        def installedBefore = extensionIdentity(target.administrator)

        when:
        migrateFirst(target.migration)

        then:
        def failure = thrown(FlywayException)
        failure.message.contains("The migration role requires USAGE on the schema containing pg_trgm")
        sqlCauseOf(failure).SQLState == "P0001"
        extensionIdentity(target.administrator) == installedBefore
        // DB-SPECIFIC: PostgreSQL keeps the extension schema's USAGE separate from application ownership.
        !query(target.migration, "select has_schema_privilege('Demo Sample extension', 'USAGE')")
        noBusinessObjects(target.administrator)
    }

    def "the real first migration accepts a DBA installation in #schema without giving its role database creation rights"() {
        given:
        def target = databaseWithMigrationRole()
        installIn(target.administrator, schema)
        execute(target.administrator, "grant usage on schema ${schema} to ${target.role}")
        noInstallationRights(target.migration)
        def installedBefore = extensionIdentity(target.administrator)

        when:
        def migrated = migrateFirst(target.migration)

        then:
        migrated.migrationsExecuted == 1
        query(target.administrator, "select count(*) from app.subjects") == 2L
        query(target.administrator, "select count(*) from app.flyway_schema_history where version = '1' and success") == 1L
        // DB-SPECIFIC: PostgreSQL's reg-object lookup proves the two real business objects were created.
        query(target.administrator, "select to_regtype('app.subject_kind') is not null")
        query(target.administrator, "select to_regprocedure('app.search_fold(text)') is not null")
        extensionIdentity(target.administrator) == installedBefore
        noInstallationRights(target.migration)

        where:
        schema << ["app", '"Demo Sample extension"']
    }

    private Map<String, Object> databaseWithMigrationRole() {
        String suffix = UUID.randomUUID().toString().replace("-", "")
        String name = "prerequisite_${suffix}"
        String role = "migration_${suffix}"
        // DB-SPECIFIC: PostgreSQL separates role attributes, database CREATE and owned-schema rights.
        server.createRole(role, "login nosuperuser nocreatedb nocreaterole noinherit")
        server.createDatabase(name, "")
        name = server.databaseNameFor(name)
        execute(server.administrationDatabase, "revoke create on database ${name} from public")
        execute(server.administrationDatabase, "grant connect on database ${name} to ${role}")
        def administrator = connectedTo(name, "postgres")
        execute(administrator, "create schema app authorization ${role}")
        [administrator: administrator, migration: connectedTo(name, role), role: role]
    }

    private DataSource connectedTo(String database, String role) {
        server.getDatabase(role, database)
    }

    private static void installIn(DataSource database, String schema) {
        if (schema != "app") {
            execute(database, "create schema ${schema}")
        }
        // DB-SPECIFIC: PostgreSQL installs pg_trgm into the schema chosen by the test's DBA.
        execute(database, "create extension pg_trgm with schema ${schema}")
    }

    private static def migrateFirst(DataSource database) {
        Flyway.configure().dataSource(database).schemas("app")
                .locations("classpath:db/migration/common").target("1")
                .failOnMissingLocations(true).validateMigrationNaming(true).load().migrate()
    }

    private static void noInstallationRights(DataSource database) {
        // DB-SPECIFIC: PostgreSQL distinguishes role CREATEDB, superuser and a database's CREATE privilege.
        assert !query(database, "select rolsuper from pg_catalog.pg_roles where rolname = current_user")
        assert !query(database, "select rolcreatedb from pg_catalog.pg_roles where rolname = current_user")
        assert !query(database, "select has_database_privilege(current_database(), 'CREATE')")
    }

    private static void noBusinessObjects(DataSource database) {
        // DB-SPECIFIC: PostgreSQL's reg-object lookup returns null for an absent business object.
        assert query(database, "select to_regclass('app.subjects') is null")
        assert query(database, "select to_regtype('app.subject_kind') is null")
        assert query(database, "select to_regprocedure('app.search_fold(text)') is null")
    }

    private static String extensionSchema(DataSource database) {
        // DB-SPECIFIC: PostgreSQL records the installed extension's actual namespace.
        query(database, """
                select namespace.nspname from pg_catalog.pg_extension extension
                  join pg_catalog.pg_namespace namespace on namespace.oid = extension.extnamespace
                 where extension.extname = 'pg_trgm'
                """) as String
    }

    private static List<Object> extensionIdentity(DataSource database) {
        // DB-SPECIFIC: PostgreSQL's oid identifies the same extension across the migration.
        [query(database, "select oid from pg_catalog.pg_extension where extname = 'pg_trgm'"), extensionSchema(database)]
    }

    private static PSQLException sqlCauseOf(Throwable failure) {
        Throwable cause = failure
        while (!(cause instanceof PSQLException) && cause.cause != null) {
            cause = cause.cause
        }
        assert cause instanceof PSQLException
        cause as PSQLException
    }

    private static Object query(DataSource database, String sql) {
        database.connection.withCloseable { connection ->
            connection.createStatement().withCloseable { statement ->
                statement.executeQuery(sql).withCloseable { result ->
                    result.next() ? result.getObject(1) : null
                }
            }
        }
    }

    private static void execute(DataSource database, String sql) {
        database.connection.withCloseable { connection ->
            connection.createStatement().withCloseable { it.execute(sql) }
        }
    }
}
