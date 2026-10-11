package com.lilradish.lite.migration.fixture

import javax.sql.DataSource

/** DBA preparation of a test database, before any application migration is run. */
final class PgTrgm {

    private PgTrgm() {}

    static void preparedIn(DataSource database) {
        database.connection.withCloseable { connection ->
            connection.createStatement().withCloseable { statement ->
                // DB-SPECIFIC: PostgreSQL records each extension's actual schema and its USAGE privilege.
                statement.executeQuery("""
                        select has_schema_privilege(namespace.oid, 'USAGE') as usable
                          from pg_catalog.pg_extension extension
                          join pg_catalog.pg_namespace namespace on namespace.oid = extension.extnamespace
                         where extension.extname = 'pg_trgm'
                        """).withCloseable { extension ->
                    if (extension.next()) {
                        if (!extension.getBoolean("usable")) {
                            throw new IllegalStateException("The test database role needs USAGE on the pg_trgm schema")
                        }
                        return
                    }
                    // DB-SPECIFIC: PostgreSQL installs an extension in the current database only.
                    connection.createStatement().withCloseable { installing ->
                        installing.execute("create schema if not exists app")
                        installing.execute("create extension pg_trgm with schema app")
                    }
                }
            }
        }
    }
}
