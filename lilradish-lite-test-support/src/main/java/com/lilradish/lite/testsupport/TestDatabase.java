package com.lilradish.lite.testsupport;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.postgresql.PGConnection;

/** Reuses a worker's schema while restoring the real baseline rows between ordinary scopes. */
@org.jspecify.annotations.NullMarked
public final class TestDatabase {
    private final TestPostgres.Endpoint endpoint;
    private final String name;
    private @org.jspecify.annotations.Nullable List<Rows> baseline;
    private List<String> baselineSettings = List.of();
    private long epoch;
    private final java.util.concurrent.atomic.AtomicInteger physicalConnections =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger unfinishedCloses =
            new java.util.concurrent.atomic.AtomicInteger();
    private final List<com.zaxxer.hikari.HikariDataSource> pools = new ArrayList<>();

    TestDatabase(TestPostgres.Endpoint endpoint, String name) {
        this.endpoint = endpoint;
        this.name = name;
    }

    String name() {
        return name;
    }

    void create(String locale) throws SQLException {
        // DB-SPECIFIC: PostgreSQL template0, UTF8 encoding and optional locale-provider clauses.
        administer("create database " + quote(name) + " template template0 encoding 'UTF8' " + locale);
    }

    void drop() throws SQLException {
        administer("drop database " + quote(name));
    }

    private void administer(String sql) throws SQLException {
        try (var connection = endpoint.dataSource("postgres", "postgres", "").getConnection();
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    synchronized DataSource dataSource(String user, String options) {
        long captured = epoch;
        DataSource source = endpoint.physicalSource(user, name, options);
        return new VerifiedDataSource(
                source,
                endpoint.generation(),
                endpoint.started(),
                () -> captured == epoch,
                physicalConnections,
                this,
                null,
                unfinishedCloses,
                endpoint.ordinaryCapacity(),
                name);
    }

    synchronized DataSource physicalPoolSource(String user, String options, int maximumPhysical) {
        return physicalPoolSource(endpoint.physicalSource(user, name, options), maximumPhysical);
    }

    synchronized DataSource physicalPoolSource(DataSource physical, int maximumPhysical) {
        long captured = epoch;
        return new VerifiedDataSource(
                physical,
                endpoint.generation(),
                endpoint.started(),
                () -> captured == epoch,
                physicalConnections,
                this,
                VerifiedDataSource.slots(maximumPhysical),
                unfinishedCloses,
                endpoint.ordinaryCapacity(),
                name);
    }

    synchronized void invalidate() {
        epoch++;
    }

    DataSource pooled(String options) {
        com.zaxxer.hikari.HikariConfig configuration = new com.zaxxer.hikari.HikariConfig();
        configuration.setDataSource(physicalPoolSource("postgres", options, 4));
        configuration.setMaximumPoolSize(4);
        configuration.setMinimumIdle(0);
        configuration.setPoolName("worker-test-" + name);
        com.zaxxer.hikari.HikariDataSource pool = new com.zaxxer.hikari.HikariDataSource(configuration);
        pools.add(pool);
        return pool;
    }

    void assertQuiescent() throws SQLException {
        for (com.zaxxer.hikari.HikariDataSource pool : pools) {
            if (!pool.isClosed() && pool.getHikariPoolMXBean().getActiveConnections() != 0) {
                throw new SQLException("Connections from the previous test scope are still borrowed");
            }
        }
        pools.forEach(com.zaxxer.hikari.HikariDataSource::close);
        pools.clear();
        if (physicalConnections.get() != 0) {
            throw new SQLException("Connections from the previous test scope are still open");
        }
        // JDBC close sends termination before the backend has necessarily left pg_stat_activity.
        // Only locally closed connections may await that observed server-side termination.
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        // DB-SPECIFIC: PostgreSQL pg_stat_activity observes backend termination for this database.
        try (var connection = endpoint.dataSource("postgres", "postgres", "").getConnection();
                var statement =
                        connection.prepareStatement("select count(*) from pg_stat_activity where datname = ?")) {
            statement.setString(1, name);
            while (true) {
                try (var rows = statement.executeQuery()) {
                    rows.next();
                    if (rows.getInt(1) == 0) {
                        endpoint.ordinaryCapacity().recover(name);
                        return;
                    }
                }
                if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) {
                    throw new SQLException("Closed test connections have not finished on the server");
                }
                java.util.concurrent.locks.LockSupport.parkNanos(
                        java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(10));
            }
        }
    }

    boolean remember() throws SQLException {
        if (baseline != null) {
            return false;
        }
        List<Rows> remembered = new ArrayList<>();
        try (var connection = endpoint.dataSource("postgres", name, "").getConnection();
                var statement = connection.createStatement();
                var tables = statement.executeQuery("""
                        select table_name from information_schema.tables
                        where table_schema = 'app' and table_type = 'BASE TABLE'
                          and table_name <> 'flyway_schema_history' order by table_name
                        """)) {
            while (tables.next()) {
                String table = tables.getString(1);
                List<String> columns = new ArrayList<>();
                try (var query = connection.prepareStatement("""
                        select column_name from information_schema.columns
                        where table_schema = 'app' and table_name = ? and is_generated = 'NEVER'
                        order by ordinal_position
                        """)) {
                    query.setString(1, table);
                    try (var fields = query.executeQuery()) {
                        while (fields.next()) {
                            columns.add(quote(fields.getString(1)));
                        }
                    }
                }
                String target = "app." + quote(table) + " (" + String.join(",", columns) + ")";
                StringWriter content = new StringWriter();
                try {
                    // DB-SPECIFIC: PostgreSQL COPY captures real rows through the driver's CopyManager.
                    connection
                            .unwrap(PGConnection.class)
                            .getCopyAPI()
                            .copyOut("copy " + target + " to stdout", content);
                } catch (IOException failure) {
                    throw new SQLException("The test baseline rows could not be captured", failure);
                }
                remembered.add(new Rows(table, target, content.toString()));
            }
        }
        if (remembered.isEmpty()) {
            throw new SQLException("The worker baseline contains no application tables");
        }
        List<String> settings = new ArrayList<>();
        // DB-SPECIFIC: PostgreSQL database-level settings are stored in pg_db_role_setting.
        try (var connection = endpoint.dataSource("postgres", "postgres", "").getConnection();
                var statement = connection.prepareStatement("""
                        select unnest(setconfig) from pg_db_role_setting
                        where setrole = 0 and setdatabase = (select oid from pg_database where datname = ?)
                        """)) {
            statement.setString(1, name);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    settings.add(rows.getString(1));
                }
            }
        }
        baselineSettings = List.copyOf(settings);
        baseline = restorationOrder(remembered);
        return true;
    }

    private List<Rows> restorationOrder(List<Rows> rows) throws SQLException {
        Map<String, Set<String>> parents = new HashMap<>();
        for (Rows row : rows) {
            parents.put(row.table(), new HashSet<>());
        }
        Set<String> populated = new HashSet<>();
        for (Rows row : rows) {
            if (!row.content().isEmpty()) {
                populated.add(row.table());
            }
        }
        // Only deferred foreign keys can wait for the other table's COPY. A self-FK is checked
        // against the complete single-table COPY statement and does not order two tables.
        // DB-SPECIFIC: PostgreSQL pg_constraint/class/namespace describe immediate FK dependencies.
        try (var connection = endpoint.dataSource("postgres", name, "").getConnection();
                var statement = connection.createStatement();
                var keys = statement.executeQuery("""
                        select child.relname, parent.relname from pg_constraint key
                        join pg_class child on child.oid = key.conrelid
                        join pg_namespace child_schema on child_schema.oid = child.relnamespace
                        join pg_class parent on parent.oid = key.confrelid
                        join pg_namespace parent_schema on parent_schema.oid = parent.relnamespace
                        where key.contype = 'f' and not key.condeferrable
                          and child_schema.nspname = 'app' and parent_schema.nspname = 'app'
                          and child.oid <> parent.oid
                        """)) {
            while (keys.next()) {
                String child = keys.getString(1);
                String parent = keys.getString(2);
                if (populated.contains(child) && populated.contains(parent)) {
                    java.util.Objects.requireNonNull(parents.get(child)).add(parent);
                }
            }
        }
        List<Rows> ordered = new ArrayList<>();
        Set<String> restored = new HashSet<>();
        while (ordered.size() < rows.size()) {
            boolean progress = false;
            for (Rows row : rows) {
                if (!restored.contains(row.table())
                        && restored.containsAll(java.util.Objects.requireNonNull(parents.get(row.table())))) {
                    ordered.add(row);
                    restored.add(row.table());
                    progress = true;
                }
            }
            if (!progress) {
                throw new SQLException("The baseline has a non-deferrable foreign-key cycle between tables");
            }
        }
        return List.copyOf(ordered);
    }

    synchronized void restore() throws SQLException {
        // Invalidate earlier sources before inspecting or changing any reused database state.
        // New physical acquisitions share this monitor, so no earlier source can slip past reset.
        invalidate();
        assertQuiescent();
        unfinishedCloses.set(0);
        if (baseline == null) {
            return;
        }
        // Restore the database's captured settings before opening the next fixture connection.
        // ALTER DATABASE changes survive row rollback and otherwise leak into the next class.
        // DB-SPECIFIC: PostgreSQL set_config and SET FROM CURRENT preserve raw GUC list values;
        // SET ... TO a single quoted string would quote a search_path list a second time.
        try (var connection = endpoint.dataSource("postgres", "postgres", "").getConnection();
                var statement = connection.createStatement();
                var settingValue = connection.prepareStatement("select set_config(?, ?, false)")) {
            statement.execute("alter database " + quote(name) + " reset all");
            for (String setting : baselineSettings) {
                int separator = setting.indexOf('=');
                if (separator <= 0) {
                    throw new SQLException("The remembered database setting has no name");
                }
                String settingName = setting.substring(0, separator);
                settingValue.setString(1, settingName);
                settingValue.setString(2, setting.substring(separator + 1));
                try (var value = settingValue.executeQuery()) {
                    if (!value.next() || value.next()) {
                        throw new SQLException("The remembered database setting could not be applied");
                    }
                }
                statement.execute("alter database " + quote(name) + " set " + quote(settingName) + " from current");
            }
        }
        try (var connection = endpoint.dataSource("postgres", name, "").getConnection();
                var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                // DB-SPECIFIC: PostgreSQL deferred constraints and transactional COPY preserve baseline atomicity.
                statement.execute("set constraints all deferred");
                statement.execute("truncate "
                        + baseline.stream()
                                .map(rows ->
                                        rows.target().substring(0, rows.target().indexOf(" (")))
                                .collect(java.util.stream.Collectors.joining(",")));
                for (Rows rows : baseline) {
                    connection
                            .unwrap(PGConnection.class)
                            .getCopyAPI()
                            .copyIn("copy " + rows.target() + " from stdin", new StringReader(rows.content()));
                }
                connection.commit();
            } catch (SQLException | IOException failure) {
                connection.rollback();
                throw new SQLException("The worker baseline could not be restored", failure);
            }
        }
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private record Rows(String table, String target, String content) {}
}
