import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.springframework.boot.gradle.tasks.bundling.BootJar
import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
    alias(libs.plugins.libprunus.core.plugin)
    alias(libs.plugins.spring.boot)
}

// The baseline is another project's, and this is the project that runs it — so here, and only here,
// it does belong on a classpath, at the location Flyway looks in.
val migrations = Attribute.of("org.lilradish.lite.migrations", String::class.java)

val baseline = configurations.dependencyScope("baseline")
val baselineFiles = configurations.resolvable("baselineFiles") {
    extendsFrom(baseline.get())
    attributes { attribute(migrations, "baseline") }
}

val developmentSeed = configurations.dependencyScope("developmentSeed")
val developmentSeedFiles = configurations.resolvable("developmentSeedFiles") {
    extendsFrom(developmentSeed.get())
    attributes { attribute(migrations, "development-seed") }
}

dependencies {
    attributesSchema { attribute(migrations) }
    implementation(platform(libs.spring.boot.dependencies))
    implementation(platform(libs.libprunus.bom))
    // Named although the Flyway starter brings it: the runner starts Spring itself.
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.flyway)
    // Supplies prunus.flyway.command; nothing in this project compiles against it.
    runtimeOnly(libs.libprunus.flyway)
    // DB-SPECIFIC: swap together with the two aliases in libs.versions.toml.
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)
    baseline(project(":lilradish-lite-migration"))
    developmentSeed(project(":lilradish-lite-migration"))
    // enforcedPlatform rather than platform: this pins which server binary is downloaded, and a
    // suggestion that another version may win is the one thing it must not be.
    testImplementation(enforcedPlatform(libs.embedded.postgres.binaries.bom))
    testImplementation(libs.embedded.postgres)
    // Compiled against, not merely present: what a refusal names is read off the driver's own error
    // model, and a test that only knows something threw cannot tell two defences apart.
    testImplementation(libs.postgresql)
}


// The runner can halt before its shutdown hooks; the Gradle task outlives that JVM.
abstract class StopTaskPostgres : DefaultTask() {
    @get:Internal abstract val postgresDirectory: DirectoryProperty
    @get:Internal abstract val ownerTask: Property<String>

    private data class Postmaster(val process: ProcessHandle, val data: java.nio.file.Path, val started: java.time.Instant)

    private fun awaitExit(process: ProcessHandle, deadline: Long) {
        if (process.isAlive) {
            process.onExit().get((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS)
        }
    }

    private fun postmaster(process: ProcessHandle, root: java.nio.file.Path, deadline: Long): Postmaster? {
        if (!process.isAlive) return null
        val information = process.info()
        if (information.command().map { File(it).name != "postgres" }.orElse(true)) return null
        val arguments = information.arguments().orElseThrow { GradleException("Cannot inspect a PostgreSQL process while finishing ${ownerTask.get()}") }
        val directoryArgument = arguments.indexOf("-D")
        if (directoryArgument < 0 || directoryArgument + 1 >= arguments.size) return null
        val data = File(arguments[directoryArgument + 1]).canonicalFile.toPath()
        if (!data.startsWith(root)) return null
        val pid = try {
            Files.readAllLines(data.resolve("postmaster.pid")).first().toLong()
        } catch (failure: NoSuchFileException) {
            // PostgreSQL removes its pid file before the process finishes exiting.
            awaitExit(process, deadline)
            return null
        }
        if (pid != process.pid()) return null
        return Postmaster(process, data, information.startInstant().orElseThrow { GradleException("Cannot identify a PostgreSQL process while finishing ${ownerTask.get()}") })
    }

    private fun postmasters(root: java.nio.file.Path, deadline: Long, failures: MutableList<Exception>): List<Postmaster> =
        ProcessHandle.allProcesses().use { processes ->
            processes.map { process ->
                try {
                    postmaster(process, root, deadline)
                } catch (failure: Exception) {
                    if (failure is InterruptedException) throw failure
                    failures.add(failure)
                    null
                }
            }.filter { it != null }.map { it!! }.toList()
        }

    @TaskAction
    fun stop() {
        val root = postgresDirectory.get().asFile.canonicalFile.toPath()
        val failures = mutableListOf<Exception>()
        val inspectionDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        val servers = postmasters(root, inspectionDeadline, failures)
        logger.warn("{} has {} PostgreSQL postmasters left at task completion", ownerTask.get(), servers.size)
        servers.forEach { server ->
            try {
                val current = postmaster(server.process, root, inspectionDeadline)
                if (current == null) {
                    if (server.process.isAlive) throw GradleException("PostgreSQL identity cannot be confirmed while finishing ${ownerTask.get()}")
                } else {
                    if (current.data != server.data || current.started != server.started) throw GradleException("PostgreSQL identity changed while finishing ${ownerTask.get()}")
                    val signal = ProcessBuilder("kill", "-INT", "--", server.process.pid().toString()).start()
                    if (!signal.waitFor(5, TimeUnit.SECONDS) || signal.exitValue() != 0) {
                        if (server.process.isAlive) throw GradleException("Cannot stop a PostgreSQL process while finishing ${ownerTask.get()}")
                    }
                }
            } catch (failure: Exception) {
                if (failure is InterruptedException) throw failure
                failures.add(failure)
            }
        }
        // Share one exit budget after attempting every confirmed target.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        servers.forEach { server ->
            try {
                awaitExit(server.process, deadline)
            } catch (failure: Exception) {
                if (failure is InterruptedException) throw failure
                failures.add(failure)
            }
        }
        val remaining = postmasters(root, System.nanoTime() + TimeUnit.SECONDS.toNanos(1), failures).size
        logger.lifecycle("{} PostgreSQL postmasters remaining: {}", ownerTask.get(), remaining)
        if (remaining != 0 || failures.isNotEmpty()) {
            val failure = GradleException("Cannot finish PostgreSQL cleanup for ${ownerTask.get()}: $remaining postmasters remain, ${failures.size} failures")
            failures.forEach { failure.addSuppressed(it) }
            throw failure
        }
    }
}

val testPostgresDirectory = layout.buildDirectory.dir("tmp/test").get().asFile
val stopTestPostgres = tasks.register<StopTaskPostgres>("stopTestPostgres") {
    postgresDirectory.set(testPostgresDirectory)
    ownerTask.set(":lilradish-lite-flyway:test")
}

tasks.named<Test>("test") {
    if (providers.gradleProperty("skipDatabaseTests").isPresent) {
        onlyIf { task ->
            task.logger.lifecycle("{} is skipped because skipDatabaseTests is present", task.path)
            false
        }
    }
    val postgresDirectory = testPostgresDirectory
    systemProperty("java.io.tmpdir", postgresDirectory.absolutePath)
    doFirst { postgresDirectory.toPath().let { Files.createDirectories(it) } }
    finalizedBy(stopTestPostgres)
}

tasks.named<ProcessResources>("processResources") { from(baselineFiles) { into("db/migration/common") } }

// The fixture and the profile naming it seed whatever database the runner is pointed at: kept in a source set
// no archive is built from, and held by the check below, which reads the archives after they were written.
val developmentSources = sourceSets.create("dev")

tasks.named<ProcessResources>("processDevResources") { from(developmentSeedFiles) { into("db/migration/dev") } }

tasks.named<BootRun>("bootRun") { classpath += developmentSources.output }

sourceSets["test"].runtimeClasspath += developmentSources.output

val developmentSeedIsUnshipped = tasks.register("developmentSeedIsUnshipped") {
    group = "verification"
    description = "Fails if the development seed or the dev profile reaches a shipped archive."
    // Locals only: a script-level name read in the action would store the script in the configuration cache.
    val bootJarArchive = files(tasks.named<BootJar>("bootJar").flatMap { it.archiveFile })
    val plainJarArchive = files(tasks.named<Jar>("jar").flatMap { it.archiveFile })
    val development: FileCollection = developmentSources.output
    inputs.files(bootJarArchive).withPropertyName("bootJar")
    inputs.files(plainJarArchive).withPropertyName("plainJar")
    inputs.files(development).withPropertyName("development")
    outputs.upToDateWhen { true }
    doLast {
        // Every entry, nested archives included: a dependency is a jar inside the jar.
        fun entriesWithin(within: String, bytes: ByteArray): List<String> =
            ZipInputStream(bytes.inputStream()).use { archive ->
                generateSequence { archive.nextEntry }
                    .flatMap { entry ->
                        val path = "$within!/${entry.name}"
                        if (entry.name.endsWith(".jar")) listOf(path) + entriesWithin(path, archive.readBytes())
                        else listOf(path)
                    }
                    .toList()
            }

        val seedNames = development.asFileTree.matching { include("db/migration/dev/*.sql") }.files.map { it.name }
        val profiles = development.asFileTree.matching { include("application-dev.yaml") }
        if (seedNames.isEmpty() || profiles.isEmpty) {
            throw GradleException(
                "the dev source set carries no development seed or no application-dev.yaml, so this check proves " +
                    "nothing — and either one moved into src/main would ship")
        }
        val seedName = Regex("R__dev_.*\\.sql")
        fun isDevelopment(path: String): Boolean {
            val name = path.substringAfterLast('/')
            return path.contains("db/migration/dev/") || name.startsWith("application-dev.") ||
                name in seedNames || seedName.matches(name)
        }

        val bootJar = bootJarArchive.singleFile
        val plainJar = plainJarArchive.singleFile
        val bootEntries = entriesWithin(bootJar.name, bootJar.readBytes())
        val plainEntries = entriesWithin(plainJar.name, plainJar.readBytes())
        // A walk that stopped early finds nothing to refuse, so it has to be seen reaching what every archive holds.
        val reachedEverything = "${bootJar.name}!/BOOT-INF/classes/db/migration/common/V1__subject.sql" in bootEntries &&
            bootEntries.any { Regex("[^!]+!/BOOT-INF/lib/[^/]+\\.jar!/.+").matches(it) } &&
            "${plainJar.name}!/db/migration/common/V1__subject.sql" in plainEntries
        if (!reachedEverything) {
            throw GradleException("the walk over the archives did not reach the baseline and a nested jar, so it proves nothing")
        }
        val shipped = (bootEntries + plainEntries).filter(::isDevelopment)
        if (shipped.isNotEmpty()) {
            throw GradleException("a shipped archive carries the development seed or profile: ${shipped.joinToString()}")
        }
    }
}

// Named to assemble as well as to check, and run after either archive, for the reason the web project's guard gives.
tasks.named("check") { dependsOn(developmentSeedIsUnshipped) }

tasks.named("assemble") { dependsOn(developmentSeedIsUnshipped) }

tasks.named("bootJar") { finalizedBy(developmentSeedIsUnshipped) }

tasks.named("jar") { finalizedBy(developmentSeedIsUnshipped) }

prunus {
    // libprunus toolchain + quality gates (JaCoCo coverage, PIT mutation). Tune per
    // project, but keep them strict enough to matter — tests grow with the code.
    javaBuild {
        targetJavaVersion = 25
        instructionCoverageThreshold = 0.7
        lineCoverageThreshold = 0.7
        branchCoverageThreshold = 0.7
        spockEnabled = true
        licenseGateEnabled = true
        // Nothing to mutate: the logic lives in libprunus-flyway, mutation-tested there.
        pitestEnabled = false
    }
}

// The entry point starts Spring and hands over to libprunus-flyway, so a ratio taken over it
// measures whether some test happened to load a main method. It is the denominator that is wrong
// rather than the number: what this project has to get right is its migrations, and no bytecode
// counter can see one — SchemaConstraintIntegrationSpec is what holds those, against a real server.
//
// Excluded by class rather than by package, so the next class written here is counted. Lowering the
// threshold instead would switch the gate off for good and leave nothing to remind anybody.
tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    classDirectories.setFrom(files(classDirectories.files.map { root ->
        fileTree(root) { exclude("org/lilradish/lite/migration/MigrationApplication.class") }
    }))
}
