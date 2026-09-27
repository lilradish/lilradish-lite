import com.github.gradle.node.npm.task.NpmTask
import java.lang.classfile.Attributes
import java.lang.classfile.ClassFile
import java.lang.classfile.constantpool.Utf8Entry
import java.util.zip.ZipInputStream
import org.springframework.boot.gradle.tasks.bundling.BootJar
import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
    alias(libs.plugins.libprunus.core.plugin)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.node.gradle)
}

// Resolved as a directory and handed to the tests as a path, deliberately not as a dependency.
// A project dependency would put db/migration on this project's test classpath, where Spring Boot's
// Flyway auto-configuration finds it without being asked and runs a PostgreSQL baseline into
// whatever datasource is there — and would bring a second application.yaml and a second
// @SpringBootApplication with it. Declared as a task input, so a changed migration still re-runs
// what is held level with it.
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
    baseline(project(":lilradish-lite-migration"))
    developmentSeed(project(":lilradish-lite-migration"))
    implementation(platform(libs.spring.boot.dependencies))
    implementation(platform(libs.libprunus.bom))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.libprunus.spring)
    implementation(libs.spring.boot.starter.jdbc)
    // DB-SPECIFIC: the driver the deployment connects through. On the runtime classpath and nowhere
    // else, because an embedded database here would be auto-configured wherever the connection
    // settings are absent — and a deployment that starts against an empty in-memory schema fails at
    // the first query instead of at startup.
    runtimeOnly(libs.postgresql)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    // Runtime-only because no source imports it: Spock loads it as a global extension off the
    // classpath, and without it a Specification carrying @WebMvcTest starts no context at all.
    testRuntimeOnly(libs.spock.spring)
    // Arrives anyway under the test starter; named here because the specs call it directly, which
    // is what @MockitoBean leaves them holding.
    testImplementation(libs.mockito.core)
    // What the schema's vocabularies are can only be read off a schema that exists, and the Java
    // types they are held level with are on this side alone. So the server is started here too.
    testImplementation(libs.spring.boot.starter.flyway)
    // DB-SPECIFIC: Flyway refuses a server it has no module for, so this names the engine the
    // baseline is written against rather than merely enabling an optimisation.
    testRuntimeOnly(libs.flyway.database.postgresql)
    testImplementation(enforcedPlatform(libs.embedded.postgres.binaries.bom))
    testImplementation(libs.embedded.postgres)
    testImplementation(libs.postgresql)
}

// Named to both runners rather than to `test` alone: PIT forks its own JVMs, and a spec that reads
// this would fail there while passing here — which PIT reports as a suite that was not green rather
// than as a missing argument.
val baselineLocation = baselineFiles.flatMap { it.elements }.map { "-Dbaseline.location=${it.single().asFile}" }

// The reader's half of two vocabularies is TypeScript, which no classpath carries and no compiler
// on this side reads. Handed over as a path for the same reason the baseline is, and named to both
// runners for the same reason too.
val frontendSources = layout.projectDirectory.dir("src/main/frontend")
val frontendLocation = "-Dfrontend.location=${frontendSources.asFile}"

tasks.named<Test>("test") {
    inputs.files(baselineFiles).withPropertyName("baseline")
    inputs.files(developmentSeedFiles).withPropertyName("developmentSeed")
    inputs.dir(frontendSources).withPropertyName("frontend")
    // Locals only: the provider below is stored in the configuration cache, and a script-level name would store the script.
    val baselineArgument = baselineLocation
    val developmentSeedArgument =
        developmentSeedFiles.flatMap { it.elements }.map { "-Ddevelopment-seed.location=${it.single().asFile}" }
    val frontendArgument = frontendLocation
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf(baselineArgument.get(), developmentSeedArgument.get(), frontendArgument)
    })
}

// The test tree as a whole outgrew the forked Groovy compiler's default 512m heap.
tasks.withType<GroovyCompile>().configureEach { groovyOptions.forkOptions.memoryMaximumSize = "1g" }

/** An object rather than script functions: what the configuration cache stores may not hold the script. */
object CompiledClasses {

    /**
     * Every top-level class compiled under [roots], by its internal name, with the text constants of its
     * own bytecode and of everything nested in it — the names it refers to among them.
     */
    fun under(roots: Set<FileSystemLocation>): Map<String, Set<String>> =
        roots.map { it.asFile }.flatMap { root ->
            root.walk()
                .filter { it.isFile && it.name.endsWith(".class") }
                .map { compiled ->
                    val name = compiled.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '/')
                    name.substringBefore('$') to ClassFile.of().parse(compiled.toPath()).constantPool()
                        .filterIsInstance<Utf8Entry>().map { it.stringValue() }
                }
                .toList()
        }.groupBy({ it.first }, { it.second }).mapValues { entry -> entry.value.flatten().toSet() }

    /** Whether any of [constants] names [internalName] itself, rather than a class whose name begins with it. */
    private fun namesClass(constants: Set<String>, internalName: String): Boolean = constants.any {
        it == internalName || it.startsWith(internalName + "\$") ||
            it.contains("L$internalName;") || it.contains("L$internalName\$")
    }

    /**
     * The classes in [compiled] naming any of [marks], each mark having to name one: a mark naming nothing
     * is an exclusion that reads as holding while it keeps nothing out.
     */
    fun naming(compiled: Map<String, Set<String>>, marks: List<String>): Set<String> = marks.flatMap { mark ->
        compiled.filterValues { namesClass(it, mark) }.keys.ifEmpty {
            throw GradleException("no compiled class names $mark, so what it marks is no longer kept from mutation testing")
        }
    }.toSet()

    /** [found] and every class in [compiled] naming one of it, until nothing more is added. */
    fun closedOver(compiled: Map<String, Set<String>>, found: Set<String>): Set<String> {
        val grown = found + compiled.filter { (name, constants) ->
            name !in found && found.any { namesClass(constants, it) }
        }.keys
        return if (grown == found) found else closedOver(compiled, grown)
    }

    fun globsFor(internalNames: Set<String>): Set<String> =
        internalNames.map { it.replace('/', '.') }.flatMap { listOf(it, "$it\$*") }.toSet()
}

// A spec starting a PostgreSQL server is left out, with every spec reaching one through another: a minion
// is killed rather than shut down, and the server it started outlives it.
pitest {
    jvmArgs.add(baselineLocation)
    jvmArgs.add(frontendLocation)
    excludedTestClasses.set(sourceSets["test"].output.classesDirs.elements.map { roots ->
        val compiled = CompiledClasses.under(roots)
        CompiledClasses.globsFor(CompiledClasses.closedOver(
            compiled, CompiledClasses.naming(compiled, listOf("io/zonky/test/db/postgres/embedded/EmbeddedPostgres"))))
    })
    // Assumes a class naming JdbcClient is exercised only by specs starting a server. Its pure logic goes
    // unmutated for now, accepted until a server shared from the Gradle process lets those specs run here.
    excludedClasses.set(sourceSets["main"].output.classesDirs.elements.map { roots ->
        CompiledClasses.globsFor(
            CompiledClasses.naming(CompiledClasses.under(roots), listOf("org/springframework/jdbc/core/simple/JdbcClient")))
    })
}

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
        pitestEnabled = true
        mutationThreshold = 50
    }
    // Build-time AOT bytecode rewriting; logRegistryClass names the @LogRegistry
    // class that declares which classes get method logging / toString rewriting.
    aot {
        enabled = true
        logRegistryClass = "org.lilradish.lite.AppLoggingConvention"
    }
}

// What stands in on a developer's own machine for who a caller is and for what a model answers is
// right for no deployment. Compiled as its own source set, which main cannot see: nothing above can
// come to depend on it, and it is in no output a deployment is built from. That is still a setting,
// and a setting is precisely what gets edited back for local convenience — so what the build holds
// to is the archives, read after they were written.
val developmentSources = sourceSets.create("dev") {
    compileClasspath += sourceSets["main"].output + configurations["compileClasspath"]
    runtimeClasspath += sourceSets["main"].output + configurations["runtimeClasspath"]
}

// The one place it is meant to run, and the one place it is held to its behaviour.
tasks.named<BootRun>("bootRun") { classpath += developmentSources.output }

sourceSets["test"].compileClasspath += developmentSources.output
sourceSets["test"].runtimeClasspath += developmentSources.output

// Neither is consumed: an application is deployed rather than depended on. Off rather than
// filtered, so turning one on is the deliberate act that would then have to reach the source set
// above — which no filter on these two does.
tasks.named("sourcesJar") { enabled = false }

tasks.named("javadocJar") { enabled = false }

val developmentCodeIsUnshipped = tasks.register("developmentCodeIsUnshipped") {
    group = "verification"
    description = "Fails if development code reaches a shipped archive, by name or by reference."
    // Locals only: a script-level name read in the action would store the script in the configuration cache.
    val bootJarArchive = files(tasks.named<BootJar>("bootJar").flatMap { it.archiveFile })
    val plainJarArchive = files(tasks.named<Jar>("jar").flatMap { it.archiveFile })
    val developmentOutput: FileCollection = developmentSources.output
    val standIns = listOf(
        "org/lilradish/lite/app/identification/UserIdentification",
        "org/lilradish/lite/domain/inference/ModelCalls",
        "org/lilradish/lite/domain/codestep/CodeStep")
    val marker = "org/lilradish/lite/development/DevelopmentOnly"
    inputs.files(bootJarArchive).withPropertyName("bootJar")
    inputs.files(plainJarArchive).withPropertyName("plainJar")
    inputs.files(developmentOutput).withPropertyName("developmentOutput")
    outputs.upToDateWhen { true }
    doLast {
        // Dev code is recognised by package: a copy placed in main under another package name is not caught.
        // Both spellings: bytecode and entries carry the slashed one; scan directives, YAML and AOT metadata the dotted.
        val developmentPackage = Regex("org[/.]lilradish[/.]lite[/.](?:\\w+[/.])*development[/.]")
        val seedName = Regex("R__dev_.*\\.sql")
        val nestedJarEntry = Regex("[^!]+!/BOOT-INF/lib/[^/]+\\.jar!/.+")
        fun isDevelopment(path: String): Boolean {
            val name = path.substringAfterLast('/')
            return developmentPackage.containsMatchIn(path) || path.contains("db/migration/dev/") ||
                name.startsWith("application-dev.") || seedName.matches(name)
        }

        // Held to the archive scan's own judgement, placed where a classes root would put it in an archive.
        val developmentFiles = developmentOutput.files.filter { it.isDirectory }.flatMap { root ->
            root.walk().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath to it }.toList()
        }
        val misplaced = developmentFiles.map { it.first }.filterNot { isDevelopment("BOOT-INF/classes/$it") }
        if (misplaced.isNotEmpty()) {
            throw GradleException(
                "built from src/dev where the archive scan would not refuse it: ${misplaced.joinToString()}")
        }
        // Asserted of a compiled class implementing each interface, not of a directory holding files: a
        // package-info alone would satisfy that, and with nothing real to keep out every archive passes.
        standIns.forEach { standIn ->
            val implemented = developmentFiles.filter { it.first.endsWith(".class") }.any { (_, compiledClass) ->
                ClassFile.of().parse(compiledClass.toPath()).interfaces().any { it.asInternalName() == standIn }
            }
            if (!implemented) {
                throw GradleException(
                    "no class compiled from src/dev implements $standIn, " +
                        "so this check proves nothing — and one moved into src/main would ship")
            }
        }
        // Not caught, and accepted: a class copied into src/main, out of a development package, with the marker
        // deleted — which main, not seeing the marker, needs before the copy compiles at all.
        val unmarked = developmentFiles.filter { (path, _) -> path.endsWith(".class") && '$' !in path }
            .filterNot { (_, compiledClass) ->
                ClassFile.of().parse(compiledClass.toPath()).findAttribute(Attributes.runtimeInvisibleAnnotations())
                    .map { attribute -> attribute.annotations().any { it.className().equalsString("L$marker;") } }
                    .orElse(false)
            }
            .map { it.first.removeSuffix(".class").replace('/', '.') }
        if (unmarked.isNotEmpty()) {
            throw GradleException("compiled from src/dev without @DevelopmentOnly: ${unmarked.joinToString()}")
        }

        // Every entry, nested archives included, and whether its own bytes name a development package.
        fun entriesWithin(within: String, bytes: ByteArray): List<Pair<String, Boolean>> =
            ZipInputStream(bytes.inputStream()).use { archive ->
                generateSequence { archive.nextEntry }
                    .flatMap { entry ->
                        val path = "$within!/${entry.name}"
                        val content = archive.readBytes()
                        if (entry.name.endsWith(".jar")) {
                            listOf(path to false) + entriesWithin(path, content)
                        } else {
                            val text = content.toString(Charsets.ISO_8859_1)
                            // A prefilter only: the regex alone decides.
                            listOf(path to (text.contains("lilradish") && developmentPackage.containsMatchIn(text)))
                        }
                    }
                    .toList()
            }

        val bootJar = bootJarArchive.singleFile
        val plainJar = plainJarArchive.singleFile
        val bootEntries = entriesWithin(bootJar.name, bootJar.readBytes())
        val plainEntries = entriesWithin(plainJar.name, plainJar.readBytes())
        // A walk that stopped early finds nothing to refuse, so it has to be seen reaching what every archive holds.
        val application = "org/lilradish/lite/BackendApplication.class"
        val reachedEverything = bootEntries.any { it.first == "${bootJar.name}!/BOOT-INF/classes/$application" } &&
            bootEntries.any { nestedJarEntry.matches(it.first) } &&
            plainEntries.any { it.first == "${plainJar.name}!/$application" }
        if (!reachedEverything) {
            throw GradleException("the walk over the archives did not reach the application and a nested jar, so it proves nothing")
        }
        val shipped = (bootEntries + plainEntries).filter { (path, names) -> names || isDevelopment(path) }.map { it.first }
        if (shipped.isNotEmpty()) {
            throw GradleException(
                "a shipped archive carries development code: ${shipped.joinToString()}")
        }
    }
}

// Named to assemble as well as to check and run after either archive: one built without the suite run is still
// one somebody deploys, and the whole of this guard is that it is read after the archive was written.
tasks.named("check") { dependsOn(developmentCodeIsUnshipped) }

tasks.named("assemble") { dependsOn(developmentCodeIsUnshipped) }

tasks.named("bootJar") { finalizedBy(developmentCodeIsUnshipped) }

tasks.named("jar") { finalizedBy(developmentCodeIsUnshipped) }

node {
    // Pin Node/npm so the frontend checks are reproducible regardless of what is on
    // the developer/CI PATH. Stay on the current 22.x LTS rather than an older patch:
    // jsdom requires ^22.22.2 and ESLint ^22.13.0, so a pin below those stops the
    // toolchain installing at all.
    version = "22.23.2"
    download = true
    npmInstallCommand = "ci"
    // Node distribution repo is declared centrally in settings.gradle.kts
    // (FAIL_ON_PROJECT_REPOS); null stops the plugin registering its own.
    distBaseUrl = null
}

// What `tsc -p tsconfig.json`, `vitest` and `vite build` each read: the tree and the configuration naming it.
fun NpmTask.dependsOnFrontendSources() {
    inputs.dir(frontendSources)
    inputs.files("package.json", "package-lock.json", "tsconfig.json", "vite.config.ts")
        .withPropertyName("frontendSources")
}

/** Prettier and ESLint are pointed at the tree and at the configuration files themselves. */
fun NpmTask.dependsOnEveryFrontendSource() {
    dependsOnFrontendSources()
    inputs.files("eslint.config.js").withPropertyName("everyFrontendSource")
}

// The gates own no output and produce nothing the jar needs, so they hang off
// `check` alone and stay off the build path entirely.
val frontendFormatCheck = tasks.register<NpmTask>("frontendFormatCheck") {
    group = "verification"
    description = "Checks formatting of the frontend sources with Prettier."
    dependsOn(tasks.named("npmInstall"))
    args = listOf("run", "format:check")
    dependsOnEveryFrontendSource()
    inputs.files(".prettierrc.json", ".prettierignore").withPropertyName("prettierConfig")
    outputs.upToDateWhen { true }
}

val frontendLint = tasks.register<NpmTask>("frontendLint") {
    group = "verification"
    description = "Lints the frontend with ESLint, including the React hook rules."
    dependsOn(tasks.named("npmInstall"))
    args = listOf("run", "lint")
    dependsOnEveryFrontendSource()
    outputs.upToDateWhen { true }
}

val frontendTypeCheck = tasks.register<NpmTask>("frontendTypeCheck") {
    group = "verification"
    description = "Type-checks the frontend with tsc."
    dependsOn(tasks.named("npmInstall"))
    args = listOf("run", "typecheck")
    dependsOnFrontendSources()
    outputs.upToDateWhen { true }
}

val frontendTest = tasks.register<NpmTask>("frontendTest") {
    group = "verification"
    description = "Runs the frontend unit tests with Vitest."
    dependsOn(tasks.named("npmInstall"))
    args = listOf("run", "test")
    dependsOnFrontendSources()
    outputs.upToDateWhen { true }
}

// This one is not a gate: what it writes is what the archive serves, so it owns an output and sits
// on the build path. `vite build` does not type-check — frontendTypeCheck is what does, and it is
// named to `check` rather than to this, so a bundle is never held up by it.
val frontendBundle = tasks.register<NpmTask>("frontendBundle") {
    group = "build"
    description = "Builds the frontend bundle the application archive serves."
    dependsOn(tasks.named("npmInstall"))
    args = listOf("run", "build")
    dependsOnFrontendSources()
    outputs.dir(layout.buildDirectory.dir("frontend")).withPropertyName("bundle")
}

// vite.config.ts's outDir ends in `static/`, the classpath directory ClientRouteFallback serves: written straight
// into that name rather than moved into it, the address asked for and the path in the archive are one string.
sourceSets["main"].resources.srcDir(frontendBundle)

val frontendFormat = tasks.register<NpmTask>("frontendFormat") {
    group = "formatting"
    description = "Formats the frontend sources with Prettier."
    dependsOn(tasks.named("npmInstall"))
    args = listOf("run", "format")
}

tasks.named("check") {
    dependsOn(frontendFormatCheck, frontendLint, frontendTypeCheck, frontendTest)
}

// `format` mirrors `check` on the write side: one entry point that applies
// Spotless (Java/Kotlin) and Prettier (the frontend sources).
tasks.register("format") {
    group = "formatting"
    description = "Applies Spotless (Java/Kotlin) and Prettier (the frontend sources)."
    dependsOn("spotlessApply", frontendFormat)
}
