package com.lilradish.lite.testsupport

import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.TimeUnit
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import spock.lang.Specification
import spock.lang.Tag
import spock.lang.Timeout

/** Process experiments use their own bounded owner/directory, never the build's managed server. */
@Timeout(90)
@Tag("postgres-owner-lifecycle")
class PostgresBuildLifecycleIntegrationSpec extends Specification {
    def "backup waits for the same unpublished postmaster gated in its real starting state"() {
        given:
        def directory = Files.createTempDirectory("starting-owner-lifecycle-")
        def proof = directory.resolve("starting-proof.txt")
        def record = directory.resolve("ownership.properties")
        def diagnostics = directory.resolve("starting-private.log").toFile()
        def process = new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), StartingOwnerProbe.name, directory.toString())
                .redirectOutput(ProcessBuilder.Redirect.appendTo(diagnostics))
                .redirectError(ProcessBuilder.Redirect.appendTo(diagnostics)).start()
        awaitFile(process, proof)
        def observed = Files.readAllLines(proof)
        assert observed.size() == 3
        def postmaster = ProcessHandle.of(Long.parseLong(observed[0])).orElseThrow()
        def birth = Instant.parse(observed[1])
        assert observed[2] == "starting"
        def pidFile = Files.readAllLines(directory.resolve("postgres-data/postmaster.pid"))
        assert pidFile[7].trim() == "starting"
        assert postmaster.info().startInstant().orElseThrow() == birth
        def raw = new org.postgresql.ds.PGSimpleDataSource()
        raw.setServerNames(["localhost"] as String[])
        raw.setPortNumbers([Integer.parseInt(pidFile[3])] as int[])
        raw.setDatabaseName("postgres")
        raw.setUser("postgres")
        raw.setSslMode("disable")
        raw.setGssEncMode("disable")
        raw.setConnectTimeout(1)
        raw.setSocketTimeout(1)
        def executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        def cleanupThread = new java.util.concurrent.atomic.AtomicReference<Thread>()

        when:
        raw.connection

        then:
        def notReady = thrown(java.sql.SQLException)
        notReady.cause instanceof java.net.SocketTimeoutException
        !Files.exists(directory.resolve("endpoint.properties"))

        when:
        def ownerBirth = process.toHandle().info().startInstant().orElseThrow()
        assert process.toHandle().info().startInstant().orElseThrow() == ownerBirth
        process.destroyForcibly()
        assert process.waitFor(10, TimeUnit.SECONDS)
        def cleanup = executor.submit {
            cleanupThread.set(Thread.currentThread())
            PostgresBuildOwner.main(["--cleanup", record.toString()] as String[])
        }
        def deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        boolean retryObserved = false
        while (!retryObserved && System.nanoTime() < deadline) {
            def thread = cleanupThread.get()
            if (thread != null) {
                def info = java.lang.management.ManagementFactory.threadMXBean.getThreadInfo(thread.threadId(), 30)
                retryObserved = info != null && info.threadState == Thread.State.TIMED_WAITING &&
                        info.stackTrace.any { it.className == PostgresBuildOwner.name && it.methodName == "cleanupUnpublished" }
            }
            if (!retryObserved) java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
        }

        then:
        retryObserved
        !cleanup.done
        postmaster.alive && postmaster.info().startInstant().orElseThrow() == birth

        when:
        signal(postmaster, birth, "CONT")
        cleanup.get(25, TimeUnit.SECONDS)

        then:
        !postmaster.alive || postmaster.info().startInstant().orElse(null) != birth
        !Files.exists(directory.resolve("endpoint.properties"))

        cleanup:
        if (postmaster == null && Files.exists(directory.resolve("observed-postmaster.txt"))) {
            def saved = Files.readAllLines(directory.resolve("observed-postmaster.txt"))
            postmaster = ProcessHandle.of(Long.parseLong(saved[0])).orElse(null)
            birth = Instant.parse(saved[1])
        }
        if (postmaster?.alive && postmaster.info().startInstant().orElse(null) == birth) signal(postmaster, birth, "CONT")
        executor?.shutdownNow()
        assert executor == null || executor.awaitTermination(30, TimeUnit.SECONDS)
        finish(process)
        if (postmaster?.alive && postmaster.info().startInstant().orElse(null) == birth) {
            PostgresBuildOwner.main(["--cleanup", record.toString()] as String[])
        }
    }

    def "a startup receipt permits verified backup after a SQL-ready owner dies before publishing any endpoint"() {
        given:
        def directory = Files.createTempDirectory("unpublished-owner-lifecycle-")
        def proof = directory.resolve("postmaster-proof.txt")
        def record = directory.resolve("ownership.properties")
        def diagnostics = directory.resolve("unpublished-private.log").toFile()
        def process = new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), UnpublishedOwnerProbe.name, directory.toString())
                .redirectOutput(ProcessBuilder.Redirect.appendTo(diagnostics))
                .redirectError(ProcessBuilder.Redirect.appendTo(diagnostics)).start()
        awaitFile(process, proof)
        def values = Files.readAllLines(proof)
        assert values.size() == 2
        def postmaster = ProcessHandle.of(Long.parseLong(values[0])).orElseThrow()
        def birth = Instant.parse(values[1])
        def ownerBirth = process.toHandle().info().startInstant().orElseThrow()
        def starting = new Properties()
        Files.newInputStream(record).withCloseable { starting.load(it) }
        assert starting.getProperty("phase") == "starting"
        assert starting.getProperty("owner_pid") == process.pid().toString()
        assert Instant.parse(starting.getProperty("owner_birth")) == ownerBirth
        assert !Files.exists(directory.resolve("endpoint.properties"))

        when:
        assert process.toHandle().info().startInstant().orElseThrow() == ownerBirth
        process.destroyForcibly()
        assert process.waitFor(10, TimeUnit.SECONDS)
        assert postmaster.alive && postmaster.info().startInstant().orElseThrow() == birth
        def incorrect = directory.resolve("incorrect-starting.properties")
        def changed = new Properties()
        changed.putAll(starting)
        changed.setProperty("generation", "a different synthetic generation")
        Files.newOutputStream(incorrect).withCloseable { changed.store(it, "Private negative startup fixture") }
        PostgresBuildOwner.main(["--cleanup", incorrect.toString()] as String[])

        then:
        def refusal = thrown(IOException)
        refusal.message == "The unpublished postmaster does not match its private startup receipt"
        postmaster.alive
        postmaster.info().startInstant().orElseThrow() == birth

        when:
        PostgresBuildOwner.main(["--cleanup", record.toString()] as String[])

        then:
        !postmaster.alive || postmaster.info().startInstant().orElse(null) != birth
        !Files.exists(directory.resolve("endpoint.properties"))

        cleanup:
        finish(process)
        if (postmaster?.alive && postmaster.info().startInstant().orElse(null) == birth) {
            PostgresBuildOwner.main(["--cleanup", record.toString()] as String[])
        }
    }

    def "the verified backup stops our postmaster after its private owner JVM dies"() {
        given:
        def directory = Files.createTempDirectory("owner-backup-lifecycle-")
        def descriptor = directory.resolve("endpoint.properties")
        def process = launchOwner(descriptor, ProcessHandle.current())
        awaitDescriptor(process, descriptor)
        def endpoint = TestPostgres.managed(descriptor)
        def pid = postmasterPid(endpoint)
        def postmaster = ProcessHandle.of(pid).orElseThrow()
        def birth = postmaster.info().startInstant().orElseThrow()
        def owner = process.toHandle()
        def ownerBirth = owner.info().startInstant().orElseThrow()

        when:
        assert owner.info().startInstant().orElseThrow() == ownerBirth
        process.destroyForcibly()
        assert process.waitFor(10, TimeUnit.SECONDS)
        assert postmaster.alive && postmaster.info().startInstant().orElseThrow() == birth
        PostgresBuildOwner.main(["--cleanup", directory.resolve("ownership.properties").toString()] as String[])

        then:
        !postmaster.alive || postmaster.info().startInstant().orElse(null) != birth

        cleanup:
        finish(process)
        if (postmaster?.alive && postmaster.info().startInstant().orElse(null) == birth) {
            PostgresBuildOwner.main(["--cleanup", directory.resolve("ownership.properties").toString()] as String[])
        }
    }

    def "a backup with a changed recorded birth refuses to stop the still owned postmaster"() {
        given:
        def directory = Files.createTempDirectory("owner-backup-wrong-birth-")
        def descriptor = directory.resolve("endpoint.properties")
        def process = launchOwner(descriptor, ProcessHandle.current())
        awaitDescriptor(process, descriptor)
        def endpoint = TestPostgres.managed(descriptor)
        def pid = postmasterPid(endpoint)
        def recorded = new Properties()
        Files.newInputStream(directory.resolve("ownership.properties")).withCloseable { recorded.load(it) }
        recorded.setProperty("birth", Instant.EPOCH.toString())
        def incorrect = directory.resolve("incorrect-ownership.properties")
        Files.newOutputStream(incorrect).withCloseable { recorded.store(it, "Private negative ownership fixture") }

        when:
        PostgresBuildOwner.main(["--cleanup", incorrect.toString()] as String[])

        then:
        def failure = thrown(IOException)
        failure.message == "The recorded test postmaster identity changed before cleanup"
        ProcessHandle.of(pid).orElseThrow().alive
        endpoint.dataSource("postgres", "postgres", "").connection.withCloseable {
            !it.closed
        }

        cleanup:
        finish(process)
    }

    def "a real isolated Gradle #result build closes its one owned postmaster"() {
        given:
        def fixture = gradleFixture(result)
        def runner = GradleRunner.create().withProjectDir(fixture.toFile())
                .withArguments("ownershipProbe", "--offline")

        when:
        def execution = result == "successful" ? runner.build() : runner.buildAndFail()

        then:
        execution.task(":ownershipProbe").outcome == expected
        ownedPostmasterEnded(fixture.resolve("ready.txt"))

        where:
        result       | expected
        "successful" | TaskOutcome.SUCCESS
        "failed"     | TaskOutcome.FAILED
    }

    def "cancelling our real isolated Gradle requesting process closes its owned postmaster"() {
        given:
        def fixture = gradleFixture("cancelled")
        def diagnostics = Files.createTempFile(fixture, "gradle-private-", ".log").toFile()
        def child = new ProcessBuilder(Objects.requireNonNull(System.getProperty("lite.test.gradle.executable")),
                "ownershipProbe", "--offline", "--no-daemon").directory(fixture.toFile())
                .redirectOutput(ProcessBuilder.Redirect.appendTo(diagnostics))
                .redirectError(ProcessBuilder.Redirect.appendTo(diagnostics)).start()
        def handle = child.toHandle()
        def birth = handle.info().startInstant().orElseThrow()
        awaitFile(child, fixture.resolve("ready.txt"))
        def record = Files.readAllLines(fixture.resolve("ready.txt"))
        def postmaster = ProcessHandle.of(Long.parseLong(record[0])).orElseThrow()
        def postmasterBirth = Instant.parse(record[1])

        when:
        assert handle.info().startInstant().orElseThrow() == birth
        child.destroy()

        then:
        child.waitFor(30, TimeUnit.SECONDS)
        if (postmaster.alive && postmaster.info().startInstant().orElse(null) == postmasterBirth) {
            postmaster.onExit().get(30, TimeUnit.SECONDS)
        }
        ownedPostmasterEnded(fixture.resolve("ready.txt"))

        cleanup:
        if (child?.alive && child.toHandle().info().startInstant().orElse(null) == birth) {
            child.destroy()
            assert child.waitFor(30, TimeUnit.SECONDS)
        }
    }

    private static java.nio.file.Path gradleFixture(String outcome) {
        def fixture = Files.createTempDirectory("gradle-owner-lifecycle-")
        def original = Files.readString(java.nio.file.Path.of(Objects.requireNonNull(System.getProperty("lite.test.gradle.build"))))
        def first = original.indexOf("abstract class TestPostgresBuild")
        def last = original.indexOf("gradle.projectsEvaluated")
        assert first >= 0 && last > first
        def ownerService = original.substring(first, last)
        def launch = new Properties()
        launch.setProperty("java", java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString())
        launch.setProperty("classpath", Objects.requireNonNull(System.getProperty("lite.test.support.runtime")))
        Files.newOutputStream(fixture.resolve("launcher.properties")).withCloseable { launch.store(it, "Private fixture launcher") }
        Files.writeString(fixture.resolve("settings.gradle.kts"), 'rootProject.name = "isolated-owner-lifecycle"\n')
        def finish = outcome == "failed" ? 'throw GradleException("The fixture task intentionally failed")' :
                outcome == "cancelled" ? 'java.util.concurrent.CountDownLatch(1).await()' : ''
        Files.writeString(fixture.resolve("build.gradle.kts"), ownerService + '''
val ownerService = gradle.sharedServices.registerIfAbsent("testPostgres", TestPostgresBuild::class) {
    parameters.directory.set(layout.buildDirectory.dir("owned"))
    parameters.launcherManifest.set(layout.projectDirectory.file("launcher.properties"))
    parameters.connectionBudget.set(24)
}
tasks.register("ownershipProbe") {
    usesService(ownerService)
    doLast {
        val endpoint = ownerService.get().descriptor()
        val ownership = java.util.Properties()
        endpoint.parentFile.resolve("ownership.properties").inputStream().use { ownership.load(it) }
        layout.projectDirectory.file("ready.txt").asFile.writeText(
            ownership.getProperty("pid") + "\\n" + ownership.getProperty("birth") + "\\n")
        ''' + finish + '''
    }
}
''')
        fixture
    }

    private static boolean ownedPostmasterEnded(java.nio.file.Path receipt) {
        def values = Files.readAllLines(receipt)
        assert values.size() == 2
        def pid = Long.parseLong(values[0])
        def birth = Instant.parse(values[1])
        !ProcessHandle.of(pid).filter { it.alive && it.info().startInstant().orElse(null) == birth }.present
    }

    def "an IDE child without a descriptor reuses one server and worker database and stops its server on exit"() {
        given:
        def directory = Files.createTempDirectory("ide-fallback-lifecycle-")
        def receipt = directory.resolve("receipt.txt")
        def child = launchProbe(directory, receipt, null)
        awaitFile(child, receipt)
        def lines = Files.readAllLines(receipt)
        def postmaster = ProcessHandle.of(Long.parseLong(lines[1])).orElseThrow()
        def birth = postmaster.info().startInstant().orElseThrow()

        when:
        child.outputStream.close()

        then:
        lines[0] == "same-server-and-database"
        child.waitFor(20, TimeUnit.SECONDS)
        child.exitValue() == 0
        !postmaster.alive || postmaster.info().startInstant().orElse(null) != birth

        cleanup:
        finish(child)
    }

    def "the first worker entry rejects a present #kind descriptor without attempting IDE fallback"() {
        given:
        def directory = Files.createTempDirectory("invalid-managed-entry-")
        def descriptor = directory.resolve("endpoint.properties")
        def receipt = directory.resolve("receipt.txt")
        if (kind == "malformed") {
            Files.writeString(descriptor, "port=invalid\ngeneration=invalid\nstarted=invalid\n")
        }
        def child = launchProbe(directory, receipt, descriptor)

        expect:
        child.waitFor(20, TimeUnit.SECONDS)
        child.exitValue() == 0
        Files.readString(receipt) == expected
        Files.walk(directory).withCloseable { paths ->
            paths.noneMatch { it.fileName.toString().startsWith("lite-ide-postgres-") }
        }

        cleanup:
        finish(child)

        where:
        kind        | expected
        "missing"   | "refused:NoSuchFileException"
        "malformed" | "refused:IllegalStateException"
    }

    def "a server started before endpoint publication fails is stopped without publishing an endpoint"() {
        given:
        def directory = Files.createTempDirectory("owner-publication-failure-")
        def descriptor = directory.resolve("endpoint.properties")
        // The real atomic publication fails after startup; no production fault switch is used.
        Files.createDirectory(descriptor)
        Files.writeString(descriptor.resolve("keep.txt"), "a directory cannot be replaced by the endpoint file")
        def process = launchOwner(descriptor, ProcessHandle.current())

        expect:
        process.waitFor(60, TimeUnit.SECONDS)
        process.exitValue() != 0
        Files.isDirectory(descriptor)
        Files.list(directory).withCloseable { paths ->
            def diagnostic = paths.filter { it.fileName.toString().startsWith("postgres-diagnostics-") }.findFirst().orElseThrow()
            assert Files.readString(diagnostic).contains("database system is ready to accept connections")
            true
        }
        def identity = new Properties()
        Files.newInputStream(directory.resolve("ownership.properties")).withCloseable {
            identity.load(it)
            true
        }
        def pid = Long.parseLong(identity.getProperty("pid"))
        def birth = Instant.parse(identity.getProperty("birth"))
        !ProcessHandle.of(pid).filter { it.alive && it.info().startInstant().orElse(null) == birth }.present
        Files.walk(directory).withCloseable { paths -> paths.noneMatch { it.fileName.toString() == "postmaster.pid" } }

        cleanup:
        finish(process)
    }

    def "the owner cleans up after a normal stop or control pipe closure"() {
        given:
        def directory = Files.createTempDirectory("owner-lifecycle-")
        def descriptor = directory.resolve("endpoint.properties")
        def process = launchOwner(descriptor, ProcessHandle.current())
        def values = awaitDescriptor(process, descriptor)
        def endpoint = TestPostgres.managed(descriptor)
        def pid = postmasterPid(endpoint)

        when:
        if (normalStop) {
            process.outputStream.withWriter("UTF-8") { it.write("STOP\n") }
        } else {
            process.outputStream.close()
        }

        then:
        process.waitFor(20, TimeUnit.SECONDS)
        process.exitValue() == 0
        ProcessHandle.of(pid).map { it.alive }.orElse(false) == false
        !Files.exists(descriptor)

        cleanup:
        finish(process)

        where:
        normalStop << [true, false]
    }

    def "the database owner closes the server when its identified requesting process exits unexpectedly"() {
        given:
        def directory = Files.createTempDirectory("owner-departure-")
        def descriptor = directory.resolve("endpoint.properties")
        def requesting = new ProcessBuilder("sh", "-c", "read command").start()
        def process = launchOwner(descriptor, requesting.toHandle())
        awaitDescriptor(process, descriptor)
        def endpoint = TestPostgres.managed(descriptor)
        def pid = postmasterPid(endpoint)

        when:
        requesting.destroy()

        then:
        requesting.waitFor(5, TimeUnit.SECONDS)
        process.waitFor(20, TimeUnit.SECONDS)
        process.exitValue() == 0
        !ProcessHandle.of(pid).map { it.alive }.orElse(false)

        cleanup:
        finish(process)
        if (requesting?.alive) requesting.destroy()
    }

    def "startup rejects an owning PID with a different process birth without writing an endpoint"() {
        given:
        def directory = Files.createTempDirectory("owner-bad-birth-")
        def descriptor = directory.resolve("endpoint.properties")
        def process = launchOwner(descriptor, ProcessHandle.current(), Instant.EPOCH)

        expect:
        process.waitFor(10, TimeUnit.SECONDS)
        process.exitValue() != 0
        !Files.exists(descriptor)
        Files.list(directory).withCloseable { it.noneMatch { it.fileName.toString().startsWith("postgres-data-") } }

        cleanup:
        finish(process)
    }

    private static Process launchOwner(java.nio.file.Path descriptor, ProcessHandle requesting,
            Instant birth = requesting.info().startInstant().orElseThrow()) {
        def executable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString()
        def classpath = Objects.requireNonNull(System.getProperty("lite.test.support.runtime"))
        def output = descriptor.parent.resolve("owner-private.log").toFile()
        new ProcessBuilder(executable, "-cp", classpath, PostgresBuildOwner.name,
                descriptor.toString(), requesting.pid().toString(), birth.toString(), "24")
                .redirectOutput(ProcessBuilder.Redirect.appendTo(output))
                .redirectError(ProcessBuilder.Redirect.appendTo(output)).start()
    }

    private static Properties awaitDescriptor(Process process, java.nio.file.Path descriptor) {
        def deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        while (!Files.exists(descriptor)) {
            assert process.alive: "owner exited before publishing its verified endpoint"
            assert System.nanoTime() < deadline: "owner never published its verified endpoint"
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20))
        }
        def properties = new Properties()
        Files.newInputStream(descriptor).withCloseable { properties.load(it) }
        properties
    }

    private static long postmasterPid(TestPostgres.Endpoint endpoint) {
        // DB-SPECIFIC: PostgreSQL data_directory locates the owned postmaster PID file.
        endpoint.dataSource("postgres", "postgres", "").connection.withCloseable { connection ->
            connection.createStatement().withCloseable { statement ->
                statement.executeQuery("select current_setting('data_directory')").withCloseable { rows ->
                    assert rows.next()
                    Long.parseLong(Files.readAllLines(java.nio.file.Path.of(rows.getString(1), "postmaster.pid"))[0])
                }
            }
        }
    }

    private static void finish(Process process) {
        if (process?.alive) {
            process.outputStream.close()
            assert process.waitFor(20, TimeUnit.SECONDS): "owner did not finish its cleanup"
        }
    }

    private static Process launchProbe(java.nio.file.Path directory, java.nio.file.Path receipt,
            java.nio.file.Path descriptor) {
        def command = [java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                       "-Djava.io.tmpdir=" + directory]
        if (descriptor != null) command << "-D" + TestPostgres.DESCRIPTOR + "=" + descriptor
        command.addAll(["-cp", System.getProperty("java.class.path"), WorkerProbe.name, receipt.toString()])
        def output = Files.createTempFile(directory, "child-private-", ".log").toFile()
        new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.appendTo(output))
                .redirectError(ProcessBuilder.Redirect.appendTo(output)).start()
    }

    private static void awaitFile(Process process, java.nio.file.Path file) {
        def deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        while (!Files.exists(file)) {
            assert process.alive: "child exited before its lifecycle observation"
            assert System.nanoTime() < deadline: "child never wrote its lifecycle observation"
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20))
        }
    }

    static final class WorkerProbe {
        static void main(String[] arguments) {
            def receipt = java.nio.file.Path.of(arguments[0])
            boolean managed = System.getProperty(TestPostgres.DESCRIPTOR) != null
            try {
                def first = TestPostgres.worker()
                def port = first.port
                def name = first.databaseName
                // DB-SPECIFIC: PostgreSQL data_directory locates this IDE fallback's PID file.
                def pid = first.postgresDatabase.connection.withCloseable { connection ->
                    connection.createStatement().withCloseable { statement ->
                        statement.executeQuery("select current_setting('data_directory')").withCloseable { rows ->
                            assert rows.next()
                            Long.parseLong(Files.readAllLines(java.nio.file.Path.of(rows.getString(1), "postmaster.pid"))[0])
                        }
                    }
                }
                first.close()
                def second = TestPostgres.worker()
                assert second.port == port
                assert second.databaseName == name
                second.close()
                assert !managed: "an invalid managed descriptor was accepted"
                Files.writeString(receipt, "same-server-and-database\n" + pid + "\n")
                System.in.read()
            } catch (Exception failure) {
                assert managed: "IDE fallback failed"
                assert failure instanceof java.io.IOException ||
                        (failure instanceof IllegalStateException && failure.message == "The managed test database descriptor is invalid")
                def original = failure.cause instanceof java.io.IOException ? failure.cause : failure
                // A failed managed startup remains failed even if a later caller removes the property.
                System.clearProperty(TestPostgres.DESCRIPTOR)
                try {
                    TestPostgres.worker().withCloseable { }
                    throw new AssertionError("a rejected managed startup fell back on a later worker entry")
                } catch (IllegalStateException repeated) {
                    assert repeated.is(failure)
                }
                Files.writeString(receipt, "refused:" + original.class.simpleName)
            }
        }
    }

    static final class UnpublishedOwnerProbe {
        static void main(String[] arguments) {
            def directory = java.nio.file.Path.of(arguments[0])
            def data = Files.createDirectory(directory.resolve("postgres-data"))
            // This is the real start method and its starting receipt. No descriptor is published.
            PostgresBuildOwner.start(data, "lite-unpublished-" + UUID.randomUUID(), 24,
                    directory.resolve("ownership.properties")).withCloseable { server ->
                def pid = postmasterPid(server.endpoint())
                def birth = ProcessHandle.of(pid).orElseThrow().info().startInstant().orElseThrow()
                Files.writeString(directory.resolve("postmaster-proof.txt"), pid.toString() + "\n" + birth + "\n")
                System.in.read()
            }
        }
    }

    private static void signal(ProcessHandle process, Instant birth, String signal) {
        assert process.alive && process.info().startInstant().orElseThrow() == birth
        def command = new ProcessBuilder("kill", "-" + signal, "--", process.pid().toString()).start()
        assert command.waitFor(5, TimeUnit.SECONDS) && command.exitValue() == 0
    }

    static final class StartingOwnerProbe {
        static void main(String[] arguments) {
            def directory = java.nio.file.Path.of(arguments[0])
            def data = Files.createDirectory(directory.resolve("postgres-data"))
            // Start the signal helper before PostgreSQL; a shell builtin avoids a fork in the starting window.
            def stopGate = new ProcessBuilder("sh", "-c", '''
                IFS= read -r owned_pid || exit 2
                case "$owned_pid" in ''|*[!0-9]*) exit 2;; esac
                kill -STOP "$owned_pid" || exit 3
                printf 'stopped\n'
                ''').redirectErrorStream(true).start()
            def watcher = Thread.start {
                def deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
                def pid = data.resolve("postmaster.pid")
                ProcessHandle observed = null
                Instant birth = null
                while (System.nanoTime() < deadline) {
                    if (Files.exists(pid)) {
                        def values = Files.readAllLines(pid)
                        if (observed == null && !values.empty) {
                            observed = ProcessHandle.of(Long.parseLong(values[0])).orElseThrow()
                            birth = observed.info().startInstant().orElseThrow()
                            assert observed.info().commandLine().orElseThrow().contains(data.toString())
                            Files.writeString(directory.resolve("observed-postmaster.txt"), observed.pid().toString() + "\n" + birth + "\n")
                        }
                        if (values.size() >= 8 && values[7].trim() == "starting") {
                            assert observed != null && observed.pid() == Long.parseLong(values[0])
                            stopGate.outputStream.withWriter("UTF-8") { writer ->
                                writer.write(observed.pid().toString() + "\n")
                                writer.flush()
                            }
                            assert stopGate.inputStream.withReader("UTF-8") { it.readLine() } == "stopped"
                            assert stopGate.waitFor(5, TimeUnit.SECONDS) && stopGate.exitValue() == 0
                            assert observed.alive && observed.info().startInstant().orElseThrow() == birth
                            def state = Files.readAllLines(pid)[7].trim()
                            def temporary = directory.resolve("starting-proof.tmp")
                            Files.writeString(temporary, observed.pid().toString() + "\n" + birth + "\n" + state + "\n")
                            Files.move(temporary, directory.resolve("starting-proof.txt"), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                            return
                        }
                    }
                    java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MICROSECONDS.toNanos(100))
                }
                throw new AssertionError("The real postmaster starting state was not observed before SQL readiness")
            }
            try {
                PostgresBuildOwner.start(data, "lite-starting-" + UUID.randomUUID(), 305,
                        directory.resolve("ownership.properties")).withCloseable { server ->
                    watcher.join(45_000)
                    assert !watcher.alive
                    System.in.read()
                }
            } finally {
                stopGate.outputStream.close()
                stopGate.destroy()
                assert stopGate.waitFor(5, TimeUnit.SECONDS)
            }
        }
    }
}
