package com.lilradish.lite.testsupport;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** A test-only process whose lifetime belongs to one Gradle build, rather than to a test worker. */
@org.jspecify.annotations.NullMarked
public final class PostgresBuildOwner {

    private PostgresBuildOwner() {}

    public static void main(String[] arguments) throws Exception {
        if (arguments.length == 2 && arguments[0].equals("--cleanup")) {
            cleanup(Path.of(arguments[1]));
            return;
        }
        if (arguments.length != 4) {
            throw new IllegalArgumentException(
                    "The owner requires a descriptor, owning process identity and connection budget");
        }
        Path descriptor = Path.of(arguments[0]).toAbsolutePath().normalize();
        long ownerPid = Long.parseLong(arguments[1]);
        Instant ownerStarted = Instant.parse(arguments[2]);
        int connections = Integer.parseInt(arguments[3]);
        ProcessHandle owner = ProcessHandle.of(ownerPid)
                .filter(process -> process.info()
                        .startInstant()
                        .filter(ownerStarted::equals)
                        .isPresent())
                .orElseThrow(() ->
                        new IllegalStateException("The build owner is no longer the process that requested startup"));
        Files.createDirectories(descriptor.getParent());
        Path data = Files.createTempDirectory(descriptor.getParent(), "postgres-data-");
        String generation = "lite-test-" + UUID.randomUUID();
        try (Owned server = start(
                data,
                generation,
                connections,
                java.util.Objects.requireNonNull(descriptor.getParent(), "The descriptor must have an owner directory")
                        .resolve("ownership.properties"))) {
            server.writeDescriptor(descriptor);
            owner.onExit().thenRun(() -> {
                int status = 0;
                try {
                    closeDepartedOwner(server);
                    Files.deleteIfExists(descriptor);
                } catch (Exception failure) {
                    System.err.println("The database owned by the completed build could not be closed");
                    status = 1;
                } finally {
                    System.exit(status);
                }
            });
            System.out.println("TEST_POSTGRES_READY");
            System.out.flush();
            try (var control = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String command = control.readLine();
                if (command != null && !command.equals("STOP")) {
                    throw new IllegalStateException("Unexpected test database owner control command");
                }
            }
        } finally {
            Files.deleteIfExists(descriptor);
        }
    }

    private static void closeDepartedOwner(Owned server) throws IOException {
        server.close();
    }

    /** The Gradle service can stop its ready or unpublished server after the private owner JVM died. */
    private static void cleanup(Path ownership) throws IOException, SQLException {
        Properties values = new Properties();
        try (var input = Files.newInputStream(ownership)) {
            values.load(input);
        }
        Path directory = Path.of(Objects.requireNonNull(values.getProperty("directory")));
        if ("starting".equals(values.getProperty("phase"))) {
            cleanupUnpublished(values, directory);
            return;
        }
        long pid = Long.parseLong(Objects.requireNonNull(values.getProperty("pid")));
        Instant birth = Instant.parse(Objects.requireNonNull(values.getProperty("birth")));
        if (!Files.exists(directory.resolve("postmaster.pid"))) {
            if (ProcessHandle.of(pid)
                    .filter(ProcessHandle::isAlive)
                    .filter(process ->
                            process.info().startInstant().filter(birth::equals).isPresent())
                    .isPresent()) {
                throw new IOException("The owned live postmaster has no verifiable pid file");
            }
            return;
        }
        ProcessHandle process = postmaster(directory);
        if (process.pid() != pid
                || process.info().startInstant().filter(birth::equals).isEmpty()) {
            throw new IOException("The recorded test postmaster identity changed before cleanup");
        }
        TestPostgres.Endpoint expected = new TestPostgres.Endpoint(
                Integer.parseInt(Objects.requireNonNull(values.getProperty("port"))),
                Objects.requireNonNull(values.getProperty("generation")),
                Instant.parse(Objects.requireNonNull(values.getProperty("started"))));
        // DB-SPECIFIC: PostgreSQL's data_directory must match the private receipt and postmaster PID file.
        try (var connection = expected.dataSource("postgres", "postgres", "").getConnection();
                var statement = connection.createStatement();
                var identity = statement.executeQuery("select current_setting('data_directory')")) {
            if (!identity.next()
                    || !Path.of(identity.getString(1)).toRealPath().equals(directory.toRealPath())
                    || identity.next()) {
                throw new IOException("The recorded test database directory does not match its SQL identity");
            }
        }
        stop(process);
    }

    private static void cleanupUnpublished(Properties receipt, Path directory) throws IOException, SQLException {
        long ownerPid = Long.parseLong(Objects.requireNonNull(receipt.getProperty("owner_pid")));
        Instant ownerBirth = Instant.parse(Objects.requireNonNull(receipt.getProperty("owner_birth")));
        if (ProcessHandle.of(ownerPid)
                .filter(ProcessHandle::isAlive)
                .filter(process ->
                        process.info().startInstant().filter(ownerBirth::equals).isPresent())
                .isPresent()) {
            throw new IOException("The unpublished test database still belongs to a live startup owner");
        }
        if (!Files.exists(directory.resolve("postmaster.pid"))) {
            // A missing PID file cannot prove that an interrupted startup has no surviving child.
            throw new IOException("The interrupted test database startup has no verifiable postmaster identity");
        }
        ProcessHandle process = postmaster(directory);
        Instant birth = process.info()
                .startInstant()
                .orElseThrow(() -> new IOException("The unpublished postmaster birth identity cannot be inspected"));
        Instant requested = Instant.parse(Objects.requireNonNull(receipt.getProperty("requested")));
        if (birth.isBefore(requested)) {
            throw new IOException("The unpublished postmaster predates its private startup receipt");
        }
        var pidFile = Files.readAllLines(directory.resolve("postmaster.pid"), StandardCharsets.UTF_8);
        if (pidFile.size() < 4) {
            throw new IOException("The unpublished postmaster PID file is incomplete");
        }
        int port = Integer.parseInt(pidFile.get(3));
        String generation = Objects.requireNonNull(receipt.getProperty("generation"));
        var source = new org.postgresql.ds.PGSimpleDataSource();
        source.setServerNames(new String[] {"localhost"});
        source.setPortNumbers(new int[] {port});
        source.setDatabaseName("postgres");
        source.setUser("postgres");
        source.setConnectTimeout(1);
        source.setSocketTimeout(1);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        SQLException unavailable = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            ProcessHandle current = postmaster(directory);
            if (current.pid() != process.pid()
                    || current.info().startInstant().filter(birth::equals).isEmpty()) {
                throw new IOException("The unpublished postmaster identity changed while awaiting SQL startup");
            }
            try {
                verifyUnpublished(source, directory, generation, requested, birth, pidFile);
                unavailable = null;
                break;
            } catch (SQLException notReady) {
                unavailable = notReady;
                if (System.nanoTime() >= deadline) {
                    break;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while verifying an unpublished test postmaster", interrupted);
                }
            }
        }
        if (unavailable != null) {
            throw new IOException(
                    "The unpublished postmaster did not expose a verifiable SQL identity in time", unavailable);
        }
        ProcessHandle confirmed = postmaster(directory);
        if (confirmed.pid() != process.pid()
                || confirmed.info().startInstant().filter(birth::equals).isEmpty()) {
            throw new IOException("The unpublished postmaster identity changed before cleanup");
        }
        stop(process);
    }

    private static void verifyUnpublished(
            org.postgresql.ds.PGSimpleDataSource source,
            Path directory,
            String generation,
            Instant requested,
            Instant birth,
            java.util.List<String> pidFile)
            throws IOException, SQLException {
        // DB-SPECIFIC: PostgreSQL startup identity binds an unpublished process to this receipt.
        try (var connection = source.getConnection();
                var statement = connection.createStatement();
                var identity = statement.executeQuery(
                        "select current_setting('data_directory'), current_setting('cluster_name'), pg_postmaster_start_time()")) {
            if (!identity.next()
                    || !Path.of(identity.getString(1)).toRealPath().equals(directory.toRealPath())
                    || !generation.equals(identity.getString(2))) {
                throw new IOException("The unpublished postmaster does not match its private startup receipt");
            }
            Instant started = identity.getTimestamp(3).toInstant();
            // The PID file records MyStartTime; SQL records the later PgStartTime. PostgreSQL
            // does not promise they have the same second, so verify their startup order instead.
            long pidStarted = Long.parseLong(pidFile.get(2));
            if (started.isBefore(requested)
                    || started.isBefore(birth)
                    || pidStarted < requested.getEpochSecond()
                    || pidStarted > started.getEpochSecond()
                    || identity.next()) {
                throw new IOException("The unpublished postmaster's SQL startup identity is inconsistent");
            }
        }
    }

    static Owned start(Path directory, String generation, int connections) throws IOException, SQLException {
        Path receipts = Files.createTempDirectory(directory.toAbsolutePath().getParent(), "postgres-ownership-");
        Files.setPosixFilePermissions(receipts, PosixFilePermissions.fromString("rwx------"));
        return start(directory, generation, connections, receipts.resolve("ownership.properties"));
    }

    static Owned start(Path directory, String generation, int connections, Path receipt)
            throws IOException, SQLException {
        Objects.requireNonNull(directory, "directory is required");
        Objects.requireNonNull(generation, "generation is required");
        if (connections < 8) {
            throw new IllegalArgumentException(
                    "The test connection budget must include administration and reserved slots");
        }
        Properties startup = new Properties();
        startup.setProperty("phase", "starting");
        startup.setProperty("directory", directory.toRealPath().toString());
        startup.setProperty("generation", generation);
        startup.setProperty("owner_pid", Long.toString(ProcessHandle.current().pid()));
        startup.setProperty(
                "owner_birth",
                ProcessHandle.current()
                        .info()
                        .startInstant()
                        .orElseThrow(() -> new IOException("The private startup owner birth cannot be inspected"))
                        .toString());
        startup.setProperty("requested", Instant.now().toString());
        // The receipt exists before EmbeddedPostgres can create a postmaster, not merely before publication.
        try (var output = Files.newOutputStream(receipt, java.nio.file.StandardOpenOption.CREATE_NEW)) {
            Files.setPosixFilePermissions(receipt, PosixFilePermissions.fromString("rw-------"));
            startup.store(output, "Private unpublished test postmaster ownership");
        }
        Path diagnostics = Files.createTempFile(directory.getParent(), "postgres-diagnostics-", ".log");
        Files.setPosixFilePermissions(diagnostics, PosixFilePermissions.fromString("rw-------"));
        EmbeddedPostgres postgres;
        try {
            postgres = EmbeddedPostgres.builder()
                    .setDataDirectory(directory)
                    .setPGStartupWait(java.time.Duration.ofSeconds(10))
                    .setRegisterShutdownHook(false)
                    .setConnectConfig("sslmode", "disable")
                    .setConnectConfig("gssEncMode", "disable")
                    .setServerConfig("cluster_name", generation)
                    .setServerConfig("max_connections", Integer.toString(connections))
                    .setOutputRedirector(ProcessBuilder.Redirect.appendTo(diagnostics.toFile()))
                    .setErrorRedirector(ProcessBuilder.Redirect.appendTo(diagnostics.toFile()))
                    .start();
        } catch (IOException | RuntimeException failure) {
            if (Files.exists(directory.resolve("postmaster.pid"))) {
                try {
                    stop(postmaster(directory));
                } catch (IOException | RuntimeException closeFailed) {
                    failure.addSuppressed(closeFailed);
                }
            }
            throw failure;
        }
        try {
            Path actualDirectory;
            Instant actualStarted;
            // DB-SPECIFIC: PostgreSQL data_directory, cluster_name and postmaster startup time.
            try (var connection = postgres.getPostgresDatabase().getConnection();
                    var statement = connection.createStatement()) {
                try (var identity = statement.executeQuery(
                        "select current_setting('data_directory'), current_setting('cluster_name'), pg_postmaster_start_time()")) {
                    if (!identity.next()) {
                        throw new IllegalStateException("The test database did not return its startup identity");
                    }
                    actualDirectory = Path.of(identity.getString(1)).toRealPath();
                    if (!actualDirectory.equals(directory.toRealPath()) || !generation.equals(identity.getString(2))) {
                        throw new IllegalStateException(
                                "The newly started test database is not the requested instance");
                    }
                    actualStarted = identity.getTimestamp(3).toInstant();
                    if (identity.next()) {
                        throw new IllegalStateException("The test database returned more than one startup identity");
                    }
                }
                VerifiedDataSource.verify(connection, generation, actualStarted).close();
            }
            ProcessHandle postmaster = postmaster(directory);
            Instant processStarted = postmaster
                    .info()
                    .startInstant()
                    .orElseThrow(
                            () -> new IllegalStateException("The test postmaster birth identity cannot be inspected"));
            return new Owned(postgres, actualDirectory, generation, actualStarted, postmaster, processStarted, receipt);
        } catch (IOException | SQLException | RuntimeException failure) {
            try {
                if (Files.exists(directory.resolve("postmaster.pid"))) {
                    stop(postmaster(directory));
                }
                postgres.close();
            } catch (IOException | RuntimeException closeFailed) {
                failure.addSuppressed(closeFailed);
            }
            throw failure;
        }
    }

    private static void stop(ProcessHandle process) throws IOException {
        try {
            Process signal = new ProcessBuilder("kill", "-INT", "--", Long.toString(process.pid())).start();
            if (!signal.waitFor(5, TimeUnit.SECONDS) || signal.exitValue() != 0) {
                throw new IOException("The owned test postmaster could not be signalled");
            }
            if (process.isAlive()) {
                process.onExit().get(10, TimeUnit.SECONDS);
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while stopping the owned test postmaster", failure);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            throw new IOException("The owned test postmaster did not stop", failure);
        }
    }

    private static ProcessHandle postmaster(Path directory) throws IOException {
        var lines = Files.readAllLines(directory.resolve("postmaster.pid"), StandardCharsets.UTF_8);
        if (lines.size() < 2 || !Path.of(lines.get(1)).toRealPath().equals(directory.toRealPath())) {
            throw new IllegalStateException("The postmaster pid file is not for this test directory");
        }
        ProcessHandle process = ProcessHandle.of(Long.parseLong(lines.getFirst()))
                .filter(ProcessHandle::isAlive)
                .orElseThrow(() -> new IllegalStateException("The test postmaster is not alive"));
        String command = process.info()
                .command()
                .orElseThrow(() -> new IllegalStateException("The test postmaster command cannot be inspected"));
        if (!Path.of(command).getFileName().toString().equals("postgres")) {
            throw new IllegalStateException("The test pid does not identify a PostgreSQL postmaster");
        }
        String[] arguments = process.info()
                .arguments()
                .orElseThrow(() -> new IllegalStateException("The test postmaster directory cannot be inspected"));
        boolean matches = false;
        for (int index = 0; index + 1 < arguments.length; index++) {
            if (arguments[index].equals("-D")
                    && Path.of(arguments[index + 1]).toRealPath().equals(directory.toRealPath())) {
                matches = true;
            }
        }
        if (!matches) {
            throw new IllegalStateException("The test postmaster does not own the requested data directory");
        }
        return process;
    }

    static final class Owned implements AutoCloseable {
        private final EmbeddedPostgres postgres;
        private final Path directory;
        private final String generation;
        private final Instant started;
        private final ProcessHandle postmaster;
        private final Instant processStarted;
        private final Path receipt;
        private boolean closed;

        Owned(
                EmbeddedPostgres postgres,
                Path directory,
                String generation,
                Instant started,
                ProcessHandle postmaster,
                Instant processStarted,
                Path receipt) {
            this.postgres = postgres;
            this.directory = directory;
            this.generation = generation;
            this.started = started;
            this.postmaster = postmaster;
            this.processStarted = processStarted;
            this.receipt = receipt;
        }

        void writeDescriptor(Path descriptor) throws IOException {
            Path parent = java.util.Objects.requireNonNull(
                    descriptor.getParent(), "The test database descriptor must have a parent directory");
            var properties = new Properties();
            properties.setProperty("generation", generation);
            properties.setProperty("started", started.toString());
            properties.setProperty("port", Integer.toString(postgres.getPort()));
            Properties ownership = new Properties();
            ownership.putAll(properties);
            ownership.setProperty("directory", directory.toString());
            ownership.setProperty("pid", Long.toString(postmaster.pid()));
            ownership.setProperty("birth", processStarted.toString());
            ownership.setProperty("phase", "ready");
            Path record = parent.resolve("ownership.properties");
            Path temporaryReceipt = Files.createTempFile(parent, "ownership-", ".tmp");
            try (var output = Files.newOutputStream(temporaryReceipt)) {
                Files.setPosixFilePermissions(temporaryReceipt, PosixFilePermissions.fromString("rw-------"));
                ownership.store(output, "Private verified postmaster ownership");
            }
            try {
                if (!receipt.equals(record) && Files.exists(record)) {
                    throw new IOException("A different ownership record already exists at the descriptor path");
                }
                Files.move(
                        temporaryReceipt, record, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporaryReceipt);
            }
            Path temporary = Files.createTempFile(parent, "descriptor-", ".tmp");
            try {
                Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
                try (var output = Files.newOutputStream(temporary)) {
                    properties.store(output, "Private test database build descriptor");
                }
                Files.move(temporary, descriptor, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }

        TestPostgres.Endpoint endpoint() {
            return new TestPostgres.Endpoint(postgres.getPort(), generation, started);
        }

        @Override
        public synchronized void close() throws IOException {
            if (!closed) {
                if (Files.exists(directory.resolve("postmaster.pid"))) {
                    ProcessHandle current = postmaster(directory);
                    if (current.pid() != postmaster.pid()
                            || current.info()
                                    .startInstant()
                                    .filter(processStarted::equals)
                                    .isEmpty()) {
                        throw new IllegalStateException("The test postmaster identity changed before shutdown");
                    }
                    try {
                        Process signal = new ProcessBuilder("kill", "-INT", "--", Long.toString(current.pid())).start();
                        if (!signal.waitFor(5, TimeUnit.SECONDS) || signal.exitValue() != 0) {
                            throw new IOException("The owned test postmaster could not be signalled");
                        }
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted while signalling the owned test postmaster", failure);
                    }
                } else if (postmaster.isAlive()) {
                    throw new IOException("The live test postmaster has no verifiable pid file");
                }
                try {
                    if (postmaster.isAlive()) {
                        postmaster.onExit().get(10, TimeUnit.SECONDS);
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while closing the owned test postmaster", failure);
                } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
                    throw new IOException("The owned test postmaster did not stop", failure);
                }
                // The library may swallow pg_ctl failures; it is used only after independent exit proof.
                postgres.close();
                closed = true;
            }
        }
    }
}
