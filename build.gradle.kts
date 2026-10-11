// Declared here and applied in the projects that need them. A plugin each project resolves on its
// own lands in that project's classloader scope, and a build service shared between them — Spotless
// has one — is then two classes from two loaders and cannot be handed across. Naming them once here
// loads them into the scope both projects inherit.
plugins {
    alias(libs.plugins.libprunus.core.plugin) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.node.gradle) apply false
}

/** One private owner process for every test task participating in this Gradle build. */
abstract class TestPostgresBuild : BuildService<TestPostgresBuild.Parameters>, AutoCloseable {
    interface Parameters : BuildServiceParameters {
        val directory: DirectoryProperty
        val launcherManifest: RegularFileProperty
        val connectionBudget: Property<Int>
    }

    private var owner: Process? = null
    private var descriptor: File? = null
    private var ownership: File? = null
    private var diagnostics: File? = null

    @Synchronized
    fun descriptor(): File {
        descriptor?.let { return it }
        val directory = parameters.directory.get().asFile.toPath()
        java.nio.file.Files.createDirectories(directory)
        val path = java.nio.file.Files.createTempDirectory(directory, "build-")
        val endpoint = path.resolve("endpoint.properties")
        val diagnostics = path.resolve("owner.log")
        java.nio.file.Files.createFile(diagnostics)
        java.nio.file.Files.setPosixFilePermissions(diagnostics, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
        this.diagnostics = diagnostics.toFile()
        ownership = path.resolve("ownership.properties").toFile()
        val requestingProcess = ProcessHandle.current()
        val started = requestingProcess.info().startInstant().orElseThrow {
            GradleException("The test database build owner cannot be identified")
        }
        val launch = java.util.Properties()
        parameters.launcherManifest.get().asFile.inputStream().use { launch.load(it) }
        val process = ProcessBuilder(launch.getProperty("java"), "-cp", launch.getProperty("classpath"),
            "com.lilradish.lite.testsupport.PostgresBuildOwner", endpoint.toString(),
            requestingProcess.pid().toString(), started.toString(), parameters.connectionBudget.get().toString())
            .redirectOutput(ProcessBuilder.Redirect.appendTo(diagnostics.toFile()))
            .redirectError(ProcessBuilder.Redirect.appendTo(diagnostics.toFile()))
            .start()
        owner = process
        // The owner writes its descriptor only after exact directory/process/SQL identity checks.
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(45)
        while (!java.nio.file.Files.exists(endpoint)) {
            if (!process.isAlive) throw GradleException("The private test database owner failed during startup; its private log is retained")
            if (System.nanoTime() > deadline) {
                close()
                throw GradleException("The private test database owner did not become ready")
            }
            java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(20))
            if (Thread.currentThread().isInterrupted) {
                close()
                throw GradleException("Interrupted while starting the private test database owner")
            }
        }
        descriptor = endpoint.toFile()
        return endpoint.toFile()
    }

    @Synchronized
    override fun close() {
        val process = owner ?: return
        if (process.isAlive) {
            process.outputStream.bufferedWriter().use { it.write("STOP\n") }
            if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
                throw GradleException("The private test database owner did not finish cleanup")
            }
        }
        // Do not trust an owner's successful return or an unexpected JVM exit as the PG postcondition.
        val record = ownership
        if (record != null && record.exists()) {
            val launch = java.util.Properties()
            parameters.launcherManifest.get().asFile.inputStream().use { launch.load(it) }
            val cleanup = ProcessBuilder(launch.getProperty("java"), "-cp", launch.getProperty("classpath"),
                "com.lilradish.lite.testsupport.PostgresBuildOwner", "--cleanup", record.absolutePath)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(diagnostics!!))
                .redirectError(ProcessBuilder.Redirect.appendTo(diagnostics!!)).start()
            if (!cleanup.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) || cleanup.exitValue() != 0) {
                throw GradleException("The owned test postmaster's cleanup postcondition failed")
            }
        }
        if (process.exitValue() != 0) throw GradleException("The private test database owner reported a cleanup failure")
        owner = null
    }
}

gradle.projectsEvaluated {
    val support = project(":lilradish-lite-test-support")
    val tested = listOf(project(":lilradish-lite-web"), project(":lilradish-lite-flyway"), support)
    // Gradle bounds all participating Test forks by its global worker cap without realizing every task.
    val simultaneousWorkers = gradle.startParameter.maxWorkerCount
    // Per worker: sixteen shared ordinary physical claims, including pool/backend close tails,
    // plus one failed-close administration backend. Declaration-only executor construction is free.
    // The endpoint's shared failure barrier prevents another such backend from accumulating.
    // Pool, child and migration phases are smaller,
    // separate phases; physical pool permits include asynchronous close tails.
    // The upstream ten-second startup can leave at most 100 socket/SQL pairs whose server exit
    // is not acknowledged by that library. Retain this allowance even after publishing the owner.
    val startupTails = 100 * 2
    // An unpublished owner has no test workers. Its backup has at most 100 additional SQL probes,
    // plus the initial identity, PostgreSQL's three reserved slots and one owned sampler.
    val interruptedStartup = startupTails + 100 + 1 + 3 + 1
    // Ordinary global reserve: three PostgreSQL slots, two verified owner probes and one sampler.
    val budget = maxOf(simultaneousWorkers * (16 + 1) + 3 + 2 + 1 + startupTails, interruptedStartup)
    val service = gradle.sharedServices.registerIfAbsent("liteTestPostgres", TestPostgresBuild::class) {
        parameters.directory.set(layout.buildDirectory.dir("test-postgres"))
        parameters.launcherManifest.set(support.layout.buildDirectory.file("test-postgres/launcher.properties"))
        parameters.connectionBudget.set(budget)
    }
    tested.forEach { consumer ->
        consumer.tasks.withType<Test>().configureEach {
            // Inline mocks append to bootstrap search; test workers opt out of CDS.
            jvmArgs("-Xshare:off")
            if (consumer != support || name != "postgresLifecycleTest") {
                // One worker owns one reusable database; specifications remain serial within that worker.
                systemProperty("spock.parallel.enabled", "false")
                dependsOn(support.tasks.named("prepareTestPostgres"))
                usesService(service)
                doFirst {
                    systemProperty("lite.test.postgres.descriptor", service.get().descriptor().absolutePath)
                    systemProperty("lite.test.task", path)
                    if (consumer == support) {
                        systemProperty("lite.test.support.runtime", support.extensions.getByType<SourceSetContainer>()["main"].runtimeClasspath.asPath)
                        systemProperty("lite.test.gradle.build", rootProject.layout.projectDirectory.file("build.gradle.kts").asFile.absolutePath)
                    }
                }
            }
        }
    }
}

dependencyAnalysis {
    structure {
        // A module named by two bundles counts for whichever the plugin meets first, which here is webmvc; the core
        // ones stay in both, since named only in the bare starter's, web is advised to declare that starter too.
        bundle("spring-boot-starter") {
            primary(libs.spring.boot.starter)
            include("^org\\.springframework:spring-(core|beans|context)$")
            include("^org\\.springframework\\.boot:spring-boot(-autoconfigure)?$")
        }
        bundle("spring-boot-starter-webmvc") {
            primary(libs.spring.boot.starter.webmvc)
            include("^org\\.springframework:spring-(core|beans|context|web|webmvc)$")
            include("^org\\.springframework\\.boot:spring-boot(-autoconfigure|-webmvc|-web-server|-jackson|-http-converter|-servlet|-tomcat)?$")
            include("^org\\.apache\\.tomcat\\.embed:tomcat-embed-core$")
            include("^tools\\.jackson\\.core:jackson-(core|databind)$")
            include("^com\\.fasterxml\\.jackson\\.core:jackson-annotations$")
        }
        bundle("spring-boot-starter-jdbc") {
            primary(libs.spring.boot.starter.jdbc)
            include("^org\\.springframework:spring-(jdbc|tx)$")
            include("^org\\.springframework\\.boot:spring-boot-jdbc$")
        }
        bundle("spring-boot-starter-flyway") {
            primary(libs.spring.boot.starter.flyway)
            include("^org\\.flywaydb:flyway-core$")
            include("^org\\.springframework\\.boot:spring-boot-flyway$")
        }
        bundle("spring-boot-starter-webmvc-test") {
            primary(libs.spring.boot.starter.webmvc.test)
            include("^org\\.springframework:spring-test$")
            include("^org\\.springframework\\.boot:spring-boot-(test|test-autoconfigure|webmvc-test)$")
        }
        bundle("junit") { includeGroup("org.junit.jupiter") }
    }
}

// No toolchain repository is configured, so asked for download addresses the task would fail rather than write.
tasks.named<UpdateDaemonJvm>("updateDaemonJvm") { toolchainDownloadUrls.empty() }
