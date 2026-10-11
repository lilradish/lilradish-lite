package com.lilradish.lite.testsupport;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Instant;
import java.util.Objects;
import java.util.logging.Logger;
import javax.sql.DataSource;

/** Verifies the owner before a new physical connection becomes available to a test or its pool. */
@org.jspecify.annotations.NullMarked
public final class VerifiedDataSource implements DataSource {

    private final DataSource delegate;
    private final String generation;
    private final Instant started;
    private final java.util.function.BooleanSupplier currentScope;
    private final Object scopeLock;
    private final java.util.concurrent.atomic.@org.jspecify.annotations.Nullable AtomicInteger connections;
    private final java.util.concurrent.@org.jspecify.annotations.Nullable Semaphore physicalSlots;
    private final java.util.concurrent.atomic.AtomicInteger unfinishedCloses;
    private final @org.jspecify.annotations.Nullable Capacity capacity;
    private final String database;
    private final java.util.concurrent.atomic.AtomicBoolean acquisitionFailed =
            new java.util.concurrent.atomic.AtomicBoolean();

    public VerifiedDataSource(DataSource delegate, String generation, Instant started) {
        this(delegate, generation, started, () -> true);
    }

    VerifiedDataSource(DataSource delegate, String generation, Instant started, int maximumPhysical) {
        this(delegate, generation, started, () -> true, null, new Object(), slots(maximumPhysical));
    }

    VerifiedDataSource(
            DataSource delegate, String generation, Instant started, java.util.function.BooleanSupplier currentScope) {
        this(delegate, generation, started, currentScope, null);
    }

    VerifiedDataSource(
            DataSource delegate,
            String generation,
            Instant started,
            java.util.function.BooleanSupplier currentScope,
            java.util.concurrent.atomic.@org.jspecify.annotations.Nullable AtomicInteger connections) {
        this(delegate, generation, started, currentScope, connections, new Object());
    }

    VerifiedDataSource(
            DataSource delegate,
            String generation,
            Instant started,
            java.util.function.BooleanSupplier currentScope,
            java.util.concurrent.atomic.@org.jspecify.annotations.Nullable AtomicInteger connections,
            Object scopeLock) {
        this(delegate, generation, started, currentScope, connections, scopeLock, null);
    }

    VerifiedDataSource(
            DataSource delegate,
            String generation,
            Instant started,
            java.util.function.BooleanSupplier currentScope,
            java.util.concurrent.atomic.@org.jspecify.annotations.Nullable AtomicInteger connections,
            Object scopeLock,
            java.util.concurrent.@org.jspecify.annotations.Nullable Semaphore physicalSlots) {
        this(
                delegate,
                generation,
                started,
                currentScope,
                connections,
                scopeLock,
                physicalSlots,
                new java.util.concurrent.atomic.AtomicInteger());
    }

    VerifiedDataSource(
            DataSource delegate,
            String generation,
            Instant started,
            java.util.function.BooleanSupplier currentScope,
            java.util.concurrent.atomic.@org.jspecify.annotations.Nullable AtomicInteger connections,
            Object scopeLock,
            java.util.concurrent.@org.jspecify.annotations.Nullable Semaphore physicalSlots,
            java.util.concurrent.atomic.AtomicInteger unfinishedCloses) {
        this(
                delegate,
                generation,
                started,
                currentScope,
                connections,
                scopeLock,
                physicalSlots,
                unfinishedCloses,
                null,
                "");
    }

    VerifiedDataSource(
            DataSource delegate,
            String generation,
            Instant started,
            java.util.function.BooleanSupplier currentScope,
            java.util.concurrent.atomic.@org.jspecify.annotations.Nullable AtomicInteger connections,
            Object scopeLock,
            java.util.concurrent.@org.jspecify.annotations.Nullable Semaphore physicalSlots,
            java.util.concurrent.atomic.AtomicInteger unfinishedCloses,
            @org.jspecify.annotations.Nullable Capacity capacity,
            String database) {
        this.delegate = Objects.requireNonNull(delegate, "delegate is required");
        this.generation = Objects.requireNonNull(generation, "generation is required");
        this.started = Objects.requireNonNull(started, "started is required");
        this.currentScope = Objects.requireNonNull(currentScope, "scope is required");
        this.connections = connections;
        this.scopeLock = Objects.requireNonNull(scopeLock, "scope lock is required");
        this.physicalSlots = physicalSlots;
        this.unfinishedCloses = Objects.requireNonNull(unfinishedCloses, "physical close state is required");
        this.capacity = capacity;
        this.database = Objects.requireNonNull(database, "The physical capacity database is required");
        if (generation.isBlank()) {
            throw new IllegalArgumentException("generation must not be blank");
        }
    }

    static java.util.concurrent.Semaphore slots(int maximumPhysical) {
        if (maximumPhysical <= 0) {
            throw new IllegalArgumentException("The physical pool limit must be positive");
        }
        return new java.util.concurrent.Semaphore(maximumPhysical);
    }

    @Override
    public Connection getConnection() throws SQLException {
        synchronized (scopeLock) {
            requireCurrentScope();
            acquirePhysicalSlot();
            var claim = acquireCapacity();
            try {
                return checked(delegate.getConnection(), claim);
            } catch (SQLException | RuntimeException failed) {
                blockFailedAcquisition();
                throw failed;
            }
        }
    }

    @Override
    public Connection getConnection(
            @org.jspecify.annotations.Nullable String username, @org.jspecify.annotations.Nullable String password)
            throws SQLException {
        synchronized (scopeLock) {
            requireCurrentScope();
            acquirePhysicalSlot();
            var claim = acquireCapacity();
            try {
                return checked(delegate.getConnection(username, password), claim);
            } catch (SQLException | RuntimeException failed) {
                blockFailedAcquisition();
                throw failed;
            }
        }
    }

    private void acquirePhysicalSlot() throws SQLException {
        var slots = physicalSlots;
        if (slots == null) {
            return;
        }
        try {
            if (!slots.tryAcquire(10, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new SQLException("The previous physical pool connection has not finished closing");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while waiting for a physical pool connection", failure);
        }
    }

    private @org.jspecify.annotations.Nullable Claim acquireCapacity() throws SQLException {
        var budget = capacity;
        if (budget == null) {
            return null;
        }
        try {
            return budget.acquire(database);
        } catch (SQLException failure) {
            // No physical open took place. Return only the separately acquired pool slot.
            var slots = physicalSlots;
            if (slots != null) {
                slots.release();
            }
            throw failure;
        }
    }

    private void requireCurrentScope() throws SQLException {
        if (!currentScope.getAsBoolean()) {
            throw new SQLException("The test datasource belongs to a completed worker scope");
        }
        if (unfinishedCloses.get() != 0) {
            throw new SQLException("An earlier physical test connection has not been proved closed");
        }
    }

    private void blockFailedAcquisition() {
        if (acquisitionFailed.compareAndSet(false, true)) {
            unfinishedCloses.incrementAndGet();
        }
    }

    private Connection checked(Connection connection, @org.jspecify.annotations.Nullable Claim claim)
            throws SQLException {
        Backend backend = backendOf(connection, generation, started);
        if (claim != null) {
            claim.verified();
        }
        var counter = connections;
        if (counter != null) {
            counter.incrementAndGet();
        }
        return new TrackedConnection(connection, counter, backend, physicalSlots, unfinishedCloses, claim);
    }

    /** Also used immediately after a test deliberately opens a raw driver connection. */
    public static Connection verify(Connection connection, String generation, Instant started) throws SQLException {
        return new TrackedConnection(
                connection,
                null,
                backendOf(connection, generation, started),
                null,
                new java.util.concurrent.atomic.AtomicInteger(),
                null);
    }

    /** A worker's physical open claims include closing backends and failed acquisitions. */
    static final class Capacity {
        private final int maximum;
        private int held;
        private final java.util.Map<String, java.util.List<Claim>> databases = new java.util.HashMap<>();

        Capacity(int maximum) {
            if (maximum <= 0) {
                throw new IllegalArgumentException("The worker physical connection limit must be positive");
            }
            this.maximum = maximum;
        }

        synchronized Claim acquire(String database) throws SQLException {
            if (held == maximum) {
                throw new SQLException("The worker's ordinary physical connection capacity is already in use");
            }
            var claim = new Claim(this, database);
            databases
                    .computeIfAbsent(database, ignored -> new java.util.ArrayList<>())
                    .add(claim);
            held++;
            return claim;
        }

        private synchronized void release(Claim claim) {
            if (!claim.released) {
                claim.released = true;
                held--;
                var belonging = Objects.requireNonNull(databases.get(claim.database));
                belonging.remove(claim);
                if (belonging.isEmpty()) {
                    databases.remove(claim.database);
                }
            }
        }

        private synchronized void verified(Claim claim) {
            claim.backendVerified = true;
        }

        // Database zero proves exit only for claims whose backend identity was actually established.
        // An authentication or earlier opening failure can have no database identity in pg_stat_activity.
        synchronized void recover(String database) throws SQLException {
            var belonging = databases.get(database);
            if (belonging != null) {
                if (belonging.stream().anyMatch(claim -> !claim.backendVerified)) {
                    throw new SQLException("An unverified physical opening has no provable backend exit");
                }
                for (var claim : java.util.List.copyOf(belonging)) {
                    release(claim);
                }
            }
        }
    }

    private static final class Claim {
        private final Capacity capacity;
        private final String database;
        private boolean released;
        private boolean backendVerified;

        private Claim(Capacity capacity, String database) {
            this.capacity = capacity;
            this.database = database;
        }

        void release() {
            capacity.release(this);
        }

        void verified() {
            capacity.verified(this);
        }
    }

    private static Backend backendOf(Connection connection, String generation, Instant started) throws SQLException {
        Objects.requireNonNull(connection, "connection is required");
        try {
            Objects.requireNonNull(generation, "generation is required");
            Objects.requireNonNull(started, "started is required");
            // DB-SPECIFIC: PostgreSQL cluster_name, postmaster identity and this physical backend PID.
            try (var statement = connection.createStatement();
                    var identity = statement.executeQuery(
                            "select current_setting('cluster_name'), pg_postmaster_start_time(), pg_backend_pid()")) {
                if (!identity.next()
                        || !generation.equals(identity.getString(1))
                        || !started.equals(identity.getTimestamp(2).toInstant())) {
                    throw new SQLException("The test database is not the instance owned by this build");
                }
                ProcessHandle backend = ProcessHandle.of(identity.getLong(3))
                        .filter(ProcessHandle::isAlive)
                        .orElseThrow(() -> new SQLException("The verified test backend process is not alive"));
                Instant birth = backend.info()
                        .startInstant()
                        .orElseThrow(() -> new SQLException("The verified test backend birth cannot be inspected"));
                if (identity.next()) {
                    throw new SQLException("The test database returned more than one physical backend identity");
                }
                return new Backend(backend, birth);
            }
        } catch (SQLException | RuntimeException failed) {
            try {
                connection.close();
            } catch (SQLException closeFailed) {
                failed.addSuppressed(closeFailed);
            }
            throw failed;
        }
    }

    private record Backend(ProcessHandle process, Instant birth) {
        void awaitExit() throws SQLException {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while (process.isAlive()) {
                var currentBirth = process.info().startInstant();
                if (currentBirth.isEmpty()) {
                    if (!process.isAlive()) {
                        return;
                    }
                    throw new SQLException("The closed test backend birth cannot be inspected");
                }
                if (!birth.equals(currentBirth.orElseThrow())) {
                    return;
                }
                if (Thread.currentThread().isInterrupted()) {
                    throw new SQLException(
                            "Interrupted while observing the closed test backend",
                            new InterruptedException("The physical close observation was interrupted"));
                }
                if (System.nanoTime() >= deadline) {
                    throw new SQLException("The closed test backend has not finished");
                }
                java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(1));
            }
        }
    }

    @Override
    public @org.jspecify.annotations.Nullable PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(@org.jspecify.annotations.Nullable PrintWriter writer) throws SQLException {
        delegate.setLogWriter(writer);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
        if (type.isInstance(this)) {
            return type.cast(this);
        }
        throw new SQLException("The verified datasource cannot expose its unverified delegate");
    }

    @Override
    public boolean isWrapperFor(Class<?> type) {
        return type.isInstance(this);
    }

    /** Explicit JDBC delegation keeps physical close accounting without reflective dispatch. */
    private static final class TrackedConnection implements Connection {
        private final Connection delegate;
        private final java.util.concurrent.atomic.@org.jspecify.annotations.Nullable AtomicInteger counter;
        private final Backend backend;
        private final java.util.concurrent.@org.jspecify.annotations.Nullable Semaphore physicalSlots;
        private final java.util.concurrent.atomic.AtomicBoolean closed =
                new java.util.concurrent.atomic.AtomicBoolean();
        private final java.util.concurrent.atomic.AtomicInteger unfinishedCloses;
        private final @org.jspecify.annotations.Nullable Claim claim;
        private final java.util.concurrent.atomic.AtomicBoolean failedClose =
                new java.util.concurrent.atomic.AtomicBoolean();

        private TrackedConnection(
                Connection delegate,
                java.util.concurrent.atomic.@org.jspecify.annotations.Nullable AtomicInteger counter,
                Backend backend,
                java.util.concurrent.@org.jspecify.annotations.Nullable Semaphore physicalSlots,
                java.util.concurrent.atomic.AtomicInteger unfinishedCloses,
                @org.jspecify.annotations.Nullable Claim claim) {
            this.delegate = delegate;
            this.counter = counter;
            this.backend = backend;
            this.physicalSlots = physicalSlots;
            this.unfinishedCloses = unfinishedCloses;
            this.claim = claim;
        }

        @Override
        public synchronized void close() throws SQLException {
            try {
                delegate.close();
                backend.awaitExit();
            } catch (SQLException | RuntimeException failed) {
                if (!closed.get() && failedClose.compareAndSet(false, true)) {
                    unfinishedCloses.incrementAndGet();
                }
                throw failed;
            }
            if (closed.compareAndSet(false, true)) {
                var admission = claim;
                if (admission != null) {
                    admission.release();
                }
                var tracked = counter;
                if (tracked != null) {
                    tracked.decrementAndGet();
                }
                var slots = physicalSlots;
                if (slots != null) {
                    slots.release();
                }
                if (failedClose.compareAndSet(true, false)) {
                    unfinishedCloses.decrementAndGet();
                }
            }
        }

        @Override
        public java.sql.Statement createStatement() throws SQLException {
            return delegate.createStatement();
        }

        @Override
        public java.sql.PreparedStatement prepareStatement(String sql) throws SQLException {
            return delegate.prepareStatement(sql);
        }

        @Override
        public java.sql.CallableStatement prepareCall(String sql) throws SQLException {
            return delegate.prepareCall(sql);
        }

        @Override
        public String nativeSQL(String sql) throws SQLException {
            return delegate.nativeSQL(sql);
        }

        @Override
        public void setAutoCommit(boolean autoCommit) throws SQLException {
            delegate.setAutoCommit(autoCommit);
        }

        @Override
        public boolean getAutoCommit() throws SQLException {
            return delegate.getAutoCommit();
        }

        @Override
        public void commit() throws SQLException {
            delegate.commit();
        }

        @Override
        public void rollback() throws SQLException {
            delegate.rollback();
        }

        @Override
        public boolean isClosed() throws SQLException {
            return delegate.isClosed();
        }

        @Override
        public java.sql.DatabaseMetaData getMetaData() throws SQLException {
            return delegate.getMetaData();
        }

        @Override
        public void setReadOnly(boolean readOnly) throws SQLException {
            delegate.setReadOnly(readOnly);
        }

        @Override
        public boolean isReadOnly() throws SQLException {
            return delegate.isReadOnly();
        }

        @Override
        public void setCatalog(@org.jspecify.annotations.Nullable String catalog) throws SQLException {
            delegate.setCatalog(catalog);
        }

        @Override
        public @org.jspecify.annotations.Nullable String getCatalog() throws SQLException {
            return delegate.getCatalog();
        }

        @Override
        public void setTransactionIsolation(int level) throws SQLException {
            delegate.setTransactionIsolation(level);
        }

        @Override
        public int getTransactionIsolation() throws SQLException {
            return delegate.getTransactionIsolation();
        }

        @Override
        public java.sql.@org.jspecify.annotations.Nullable SQLWarning getWarnings() throws SQLException {
            return delegate.getWarnings();
        }

        @Override
        public void clearWarnings() throws SQLException {
            delegate.clearWarnings();
        }

        @Override
        public java.sql.Statement createStatement(int type, int concurrency) throws SQLException {
            return delegate.createStatement(type, concurrency);
        }

        @Override
        public java.sql.PreparedStatement prepareStatement(String sql, int type, int concurrency) throws SQLException {
            return delegate.prepareStatement(sql, type, concurrency);
        }

        @Override
        public java.sql.CallableStatement prepareCall(String sql, int type, int concurrency) throws SQLException {
            return delegate.prepareCall(sql, type, concurrency);
        }

        @Override
        public java.util.Map<String, Class<?>> getTypeMap() throws SQLException {
            return delegate.getTypeMap();
        }

        @Override
        public void setTypeMap(java.util.Map<String, Class<?>> map) throws SQLException {
            delegate.setTypeMap(map);
        }

        @Override
        public void setHoldability(int holdability) throws SQLException {
            delegate.setHoldability(holdability);
        }

        @Override
        public int getHoldability() throws SQLException {
            return delegate.getHoldability();
        }

        @Override
        public java.sql.Savepoint setSavepoint() throws SQLException {
            return delegate.setSavepoint();
        }

        @Override
        public java.sql.Savepoint setSavepoint(String name) throws SQLException {
            return delegate.setSavepoint(name);
        }

        @Override
        public void rollback(java.sql.Savepoint savepoint) throws SQLException {
            delegate.rollback(savepoint);
        }

        @Override
        public void releaseSavepoint(java.sql.Savepoint savepoint) throws SQLException {
            delegate.releaseSavepoint(savepoint);
        }

        @Override
        public java.sql.Statement createStatement(int type, int concurrency, int holdability) throws SQLException {
            return delegate.createStatement(type, concurrency, holdability);
        }

        @Override
        public java.sql.PreparedStatement prepareStatement(String sql, int type, int concurrency, int holdability)
                throws SQLException {
            return delegate.prepareStatement(sql, type, concurrency, holdability);
        }

        @Override
        public java.sql.CallableStatement prepareCall(String sql, int type, int concurrency, int holdability)
                throws SQLException {
            return delegate.prepareCall(sql, type, concurrency, holdability);
        }

        @Override
        public java.sql.PreparedStatement prepareStatement(String sql, int keys) throws SQLException {
            return delegate.prepareStatement(sql, keys);
        }

        @Override
        public java.sql.PreparedStatement prepareStatement(String sql, int[] columns) throws SQLException {
            return delegate.prepareStatement(sql, columns);
        }

        @Override
        public java.sql.PreparedStatement prepareStatement(String sql, String[] columns) throws SQLException {
            return delegate.prepareStatement(sql, columns);
        }

        @Override
        public java.sql.Clob createClob() throws SQLException {
            return delegate.createClob();
        }

        @Override
        public java.sql.Blob createBlob() throws SQLException {
            return delegate.createBlob();
        }

        @Override
        public java.sql.NClob createNClob() throws SQLException {
            return delegate.createNClob();
        }

        @Override
        public java.sql.SQLXML createSQLXML() throws SQLException {
            return delegate.createSQLXML();
        }

        @Override
        public boolean isValid(int timeout) throws SQLException {
            return delegate.isValid(timeout);
        }

        @Override
        public void setClientInfo(String name, @org.jspecify.annotations.Nullable String value)
                throws java.sql.SQLClientInfoException {
            delegate.setClientInfo(name, value);
        }

        @Override
        public void setClientInfo(java.util.Properties properties) throws java.sql.SQLClientInfoException {
            delegate.setClientInfo(properties);
        }

        @Override
        public @org.jspecify.annotations.Nullable String getClientInfo(String name) throws SQLException {
            return delegate.getClientInfo(name);
        }

        @Override
        public java.util.Properties getClientInfo() throws SQLException {
            return delegate.getClientInfo();
        }

        @Override
        public java.sql.Array createArrayOf(String type, @org.jspecify.annotations.Nullable Object[] elements)
                throws SQLException {
            return delegate.createArrayOf(type, elements);
        }

        @Override
        public java.sql.Struct createStruct(String type, @org.jspecify.annotations.Nullable Object[] attributes)
                throws SQLException {
            return delegate.createStruct(type, attributes);
        }

        @Override
        public void setSchema(@org.jspecify.annotations.Nullable String schema) throws SQLException {
            delegate.setSchema(schema);
        }

        @Override
        public @org.jspecify.annotations.Nullable String getSchema() throws SQLException {
            return delegate.getSchema();
        }

        @Override
        public void abort(java.util.concurrent.Executor executor) throws SQLException {
            delegate.abort(executor);
        }

        @Override
        public void setNetworkTimeout(java.util.concurrent.Executor executor, int milliseconds) throws SQLException {
            delegate.setNetworkTimeout(executor, milliseconds);
        }

        @Override
        public int getNetworkTimeout() throws SQLException {
            return delegate.getNetworkTimeout();
        }

        @Override
        public <T> T unwrap(Class<T> type) throws SQLException {
            return delegate.unwrap(type);
        }

        @Override
        public boolean isWrapperFor(Class<?> type) throws SQLException {
            return delegate.isWrapperFor(type);
        }

        @Override
        public void beginRequest() throws SQLException {
            delegate.beginRequest();
        }

        @Override
        public void endRequest() throws SQLException {
            delegate.endRequest();
        }

        @Override
        public boolean setShardingKeyIfValid(java.sql.ShardingKey key, java.sql.ShardingKey superKey, int timeout)
                throws SQLException {
            return delegate.setShardingKeyIfValid(key, superKey, timeout);
        }

        @Override
        public boolean setShardingKeyIfValid(java.sql.ShardingKey key, int timeout) throws SQLException {
            return delegate.setShardingKeyIfValid(key, timeout);
        }

        @Override
        public void setShardingKey(java.sql.ShardingKey key, java.sql.ShardingKey superKey) throws SQLException {
            delegate.setShardingKey(key, superKey);
        }

        @Override
        public void setShardingKey(java.sql.ShardingKey key) throws SQLException {
            delegate.setShardingKey(key);
        }
    }
}
