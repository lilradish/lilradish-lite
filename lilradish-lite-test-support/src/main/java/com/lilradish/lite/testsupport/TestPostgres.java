package com.lilradish.lite.testsupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;

/** A test scope on the build's server. Closing a scope never stops a managed server. */
@org.jspecify.annotations.NullMarked
public final class TestPostgres implements AutoCloseable {
    public static final String DESCRIPTOR = "lite.test.postgres.descriptor";
    private static final AtomicLong NEXT_DATABASE = new AtomicLong();
    private static @org.jspecify.annotations.Nullable Endpoint endpoint;
    private static @org.jspecify.annotations.Nullable IllegalStateException endpointFailure;
    private static @org.jspecify.annotations.Nullable TestDatabase worker;
    private static boolean workerInUse;
    private final Endpoint owner;
    private final TestDatabase database;
    private final boolean temporary;
    private final java.util.Map<String, TestDatabase> additional = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, String> aliases = new java.util.HashMap<>();
    private @org.jspecify.annotations.Nullable String previousCase;
    private final java.util.Set<String> roles = new java.util.LinkedHashSet<>();
    private final java.util.List<Runnable> quiescenceChecks = new java.util.ArrayList<>();
    private boolean closed;
    private boolean closing;
    private int activeOperations;
    private volatile boolean baselineRestored;

    private TestPostgres(Endpoint owner, TestDatabase database, boolean temporary) {
        this.owner = owner;
        this.database = database;
        this.temporary = temporary;
    }

    public static synchronized TestPostgres worker() throws IOException, SQLException {
        Endpoint owned = endpoint();
        if (workerInUse) {
            throw new IllegalStateException("The previous worker database scope has not closed");
        }
        boolean restored = worker != null;
        if (worker == null) {
            worker = new TestDatabase(owned, workerName());
            worker.create("");
        } else {
            worker.restore();
        }
        workerInUse = true;
        TestPostgres scope = new TestPostgres(owned, worker, false);
        scope.baselineRestored = restored;
        return scope;
    }

    /** Empty or schema-changing cases have their own database, on the same build server. */
    public static synchronized TestPostgres isolated() throws IOException, SQLException {
        Endpoint owned = endpoint();
        TestDatabase isolated = new TestDatabase(owned, workerName() + "_case_" + NEXT_DATABASE.incrementAndGet());
        isolated.create("");
        return new TestPostgres(owned, isolated, true);
    }

    private static String workerName() {
        String task = System.getProperty("lite.test.task", "ide");
        String id = System.getProperty("org.gradle.test.worker", "local");
        return "test_" + Integer.toUnsignedString(task.hashCode(), 36) + "_" + id.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private static Endpoint endpoint() throws IOException, SQLException {
        IllegalStateException failed = endpointFailure;
        if (failed != null) {
            throw failed;
        }
        if (endpoint != null) {
            return endpoint;
        }
        String descriptor = System.getProperty(DESCRIPTOR);
        if (descriptor != null) {
            // Present but absent, malformed or stale descriptors must not trigger local startup.
            try {
                endpoint = managed(Path.of(descriptor));
                return endpoint;
            } catch (IOException | RuntimeException | SQLException failure) {
                endpoint = null;
                IllegalStateException rejected =
                        new IllegalStateException("The managed test database descriptor is invalid", failure);
                endpointFailure = rejected;
                throw rejected;
            }
        }
        Path directory = Files.createTempDirectory("lite-ide-postgres-");
        PostgresBuildOwner.Owned local = PostgresBuildOwner.start(directory, "lite-ide-" + UUID.randomUUID(), 32);
        endpoint = local.endpoint();
        Runtime.getRuntime()
                .addShutdownHook(new Thread(
                        () -> {
                            try {
                                local.close();
                            } catch (IOException failure) {
                                System.err.println("The IDE test database could not be closed");
                            }
                        },
                        "lite-ide-postgres-cleanup"));
        return endpoint;
    }

    static Endpoint managed(Path descriptor) throws IOException, SQLException {
        Properties values = new Properties();
        try (var input = Files.newInputStream(descriptor)) {
            values.load(input);
        }
        Endpoint expected = new Endpoint(
                Integer.parseInt(required(values, "port")),
                required(values, "generation"),
                Instant.parse(required(values, "started")));
        try (var connection = expected.dataSource("postgres", "postgres", "").getConnection()) {
            // Opening the verified physical connection is the startup proof.
            if (connection.isClosed()) {
                throw new SQLException("The managed test database connection is closed");
            }
        }
        return expected;
    }

    private static String required(Properties values, String key) {
        String value = Objects.requireNonNull(values.getProperty(key), "The test descriptor is incomplete");
        if (value.isBlank()) {
            throw new IllegalArgumentException("The test descriptor is incomplete");
        }
        return value;
    }

    public DataSource getPostgresDatabase() {
        return fromOpenScope(() -> database.dataSource("postgres", ""));
    }

    public DataSource getAdministrationDatabase() {
        return fromOpenScope(() -> owner.dataSource("postgres", "postgres", ""));
    }

    public DataSource getDatabase(String user, String name) {
        return fromOpenScope(() -> allocated(resolve(name)).dataSource(user, ""));
    }

    public DataSource dataSource(String name, String options) {
        return fromOpenScope(() -> allocated(resolve(name)).dataSource("postgres", options));
    }

    /** A pool's physical source retains its own limit until each old backend has really ended. */
    public DataSource physicalPoolDataSource(String name, String options, int maximumPhysical) {
        return fromOpenScope(() -> allocated(resolve(name)).physicalPoolSource("postgres", options, maximumPhysical));
    }

    private TestDatabase allocated(String actual) {
        if (actual.equals(database.name())) {
            return database;
        }
        for (TestDatabase extra : additional.values()) {
            if (extra.name().equals(actual)) {
                return extra;
            }
        }
        throw new IllegalArgumentException("The database is not allocated to this test scope");
    }

    /** A fixture must prove its background work has ended before its database can be reused. */
    public synchronized void registerQuiescence(Runnable check) {
        requireOpen();
        quiescenceChecks.add(Objects.requireNonNull(check, "The fixture termination check is required"));
    }

    private void requireQuiescence() {
        quiescenceChecks.forEach(Runnable::run);
    }

    private synchronized void requireOpen() {
        if (closed || closing) {
            throw new IllegalStateException("The test database scope is closed");
        }
    }

    private DataSource fromOpenScope(java.util.function.Supplier<DataSource> factory) {
        requireOpen();
        baselineRestored = false;
        DataSource source = factory.get();
        requireOpen();
        return source;
    }

    @FunctionalInterface
    private interface SqlWork {
        void run() throws SQLException;
    }

    private void whileOpen(SqlWork work) throws SQLException {
        synchronized (this) {
            requireOpen();
            activeOperations++;
        }
        try {
            work.run();
        } finally {
            synchronized (this) {
                activeOperations--;
            }
        }
    }

    public DataSource pooledDataSource(String name, String options) {
        return fromOpenScope(() -> allocated(resolve(name)).pooled(options));
    }

    /** A logical fixture name never identifies another worker's database. */
    public synchronized void aliasWorker(String fixtureName) {
        requireOpen();
        aliases.put(fixtureName, database.name());
    }

    public synchronized String databaseNameFor(String fixtureName) {
        requireOpen();
        return resolve(fixtureName);
    }

    public void createDatabase(String fixtureName, String locale) throws SQLException {
        whileOpen(() -> {
            if (aliases.containsKey(fixtureName)) {
                throw new IllegalArgumentException("The fixture database already exists in this scope");
            }
            TestDatabase isolated = new TestDatabase(owner, workerName() + "_case_" + NEXT_DATABASE.incrementAndGet());
            isolated.create(locale);
            additional.put(fixtureName, isolated);
            aliases.put(fixtureName, isolated.name());
        });
    }

    /** End the previous schema-sensitive case before allocating the next ordinary or isolated case. */
    public void beginLibraryCase(String fixtureName, boolean changesSchema) throws SQLException {
        whileOpen(() -> {
            requireQuiescence();
            String previous = previousCase;
            if (previous != null) {
                TestDatabase completed = Objects.requireNonNull(additional.get(previous));
                completed.assertQuiescent();
                completed.drop();
                additional.remove(previous);
                aliases.remove(previous);
                previousCase = null;
            }
            if (changesSchema) {
                createDatabase(fixtureName, "");
                previousCase = fixtureName;
            } else {
                if (baselineRestored) {
                    database.invalidate();
                    database.assertQuiescent();
                } else {
                    database.restore();
                }
                baselineRestored = false;
                aliasWorker(fixtureName);
            }
        });
    }

    public void verifyPool(com.zaxxer.hikari.HikariDataSource pool) throws SQLException {
        whileOpen(() -> {
            baselineRestored = false;
            org.postgresql.ds.PGSimpleDataSource physical = new org.postgresql.ds.PGSimpleDataSource();
            physical.setURL(Objects.requireNonNull(pool.getJdbcUrl(), "The pool must name its database"));
            physical.setUser(pool.getUsername());
            physical.setPassword(pool.getPassword());
            for (String name : pool.getDataSourceProperties().stringPropertyNames()) {
                physical.setProperty(name, pool.getDataSourceProperties().getProperty(name));
            }
            physical.setSslMode("disable");
            physical.setGssEncMode("disable");
            pool.setDataSource(allocated(Objects.requireNonNull(physical.getDatabaseName()))
                    .physicalPoolSource(physical, 4));
            pool.setMaximumPoolSize(4);
            pool.setMinimumIdle(0);
        });
    }

    public void createRole(String name, String attributes) throws SQLException {
        whileOpen(() -> {
            baselineRestored = false;
            // DB-SPECIFIC: PostgreSQL roles are cluster-wide, so each scope tracks its own unique names.
            try (var connection = getAdministrationDatabase().getConnection();
                    var statement = connection.createStatement()) {
                statement.execute("create role \"" + name.replace("\"", "\"\"") + "\" " + attributes);
                roles.add(name);
            }
        });
    }

    public DataSource dataSource(String options) {
        return fromOpenScope(() -> database.dataSource("postgres", options));
    }

    public String getDatabaseName() {
        return database.name();
    }

    public int getPort() {
        return owner.port();
    }

    /** Passed privately to child test JVMs so their own physical connections verify this build. */
    public String getGeneration() {
        return owner.generation();
    }

    public Instant getStarted() {
        return owner.started();
    }

    public synchronized String getJdbcUrl(String user, String name) {
        requireOpen();
        baselineRestored = false;
        return "jdbc:postgresql://localhost:" + owner.port() + "/" + resolve(name) + "?user=" + user
                + "&sslmode=disable&gssEncMode=disable";
    }

    private String resolve(String name) {
        return name.equals("postgres") ? database.name() : aliases.getOrDefault(name, name);
    }

    public synchronized java.sql.Connection verify(java.sql.Connection connection) throws SQLException {
        requireOpen();
        baselineRestored = false;
        return VerifiedDataSource.verify(connection, owner.generation(), owner.started());
    }

    public void rememberBaseline() throws SQLException {
        whileOpen(() -> {
            database.remember();
        });
    }

    /** The real migration result distinguishes a newly prepared baseline from a no-op validation. */
    public void prepareBaseline(java.util.function.IntSupplier migrate) throws SQLException {
        whileOpen(() -> {
            boolean restored = baselineRestored;
            baselineRestored = false;
            int changes = migrate.getAsInt();
            boolean firstCapture = database.remember();
            baselineRestored = firstCapture || (restored && changes == 0);
        });
    }

    /** Remember the ordinary worker immediately after migration, before a fixture adds its rows. */
    public void rememberBaseline(String fixtureName) throws SQLException {
        whileOpen(() -> {
            if (resolve(fixtureName).equals(database.name())) {
                database.remember();
            }
        });
    }

    public void restoreBaseline() throws SQLException {
        whileOpen(() -> {
            requireQuiescence();
            database.restore();
            baselineRestored = false;
        });
    }

    @Override
    public void close() throws SQLException {
        synchronized (this) {
            if (closed) {
                return;
            }
            requireOpen();
            if (activeOperations != 0) {
                throw new SQLException("A test database lifecycle operation is still in progress");
            }
            closing = true;
        }
        try {
            requireQuiescence();
            database.assertQuiescent();
            for (TestDatabase extra : additional.values()) {
                extra.assertQuiescent();
                extra.drop();
            }
            additional.clear();
            if (temporary) {
                database.drop();
            }
            // DB-SPECIFIC: PostgreSQL cluster-wide roles must be removed after their scope databases.
            try (var connection = owner.dataSource("postgres", "postgres", "").getConnection();
                    var statement = connection.createStatement()) {
                for (String role : roles) {
                    statement.execute("drop role if exists \"" + role.replace("\"", "\"\"") + "\"");
                }
            }
            if (!temporary) {
                database.invalidate();
                synchronized (TestPostgres.class) {
                    workerInUse = false;
                }
            }
            synchronized (this) {
                closed = true;
            }
        } finally {
            synchronized (this) {
                closing = false;
            }
        }
    }

    record Endpoint(
            int port,
            String generation,
            Instant started,
            java.util.concurrent.atomic.AtomicInteger unfinishedCloses,
            VerifiedDataSource.Capacity ordinaryCapacity) {
        Endpoint(int port, String generation, Instant started) {
            this(
                    port,
                    generation,
                    started,
                    new java.util.concurrent.atomic.AtomicInteger(),
                    new VerifiedDataSource.Capacity(16));
        }

        Endpoint {
            Objects.requireNonNull(unfinishedCloses, "The endpoint physical close state is required");
            Objects.requireNonNull(ordinaryCapacity, "The worker physical capacity is required");
            if (port <= 0 || port > 65535 || generation.isBlank()) {
                throw new IllegalArgumentException("The test database identity is invalid");
            }
        }

        DataSource dataSource(String user, String database, String options) {
            return new VerifiedDataSource(
                    physicalSource(user, database, options),
                    generation,
                    started,
                    () -> true,
                    null,
                    this,
                    null,
                    unfinishedCloses);
        }

        PGSimpleDataSource physicalSource(String user, String database, String options) {
            PGSimpleDataSource source = new PGSimpleDataSource();
            source.setURL("jdbc:postgresql://localhost:" + port + "/" + database + options);
            source.setUser(user);
            source.setSslMode("disable");
            source.setGssEncMode("disable");
            return source;
        }
    }
}
