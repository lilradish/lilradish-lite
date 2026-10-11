import java.util.Properties
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

plugins {
    alias(libs.plugins.libprunus.core.plugin)
}

// This module is used only by testImplementation and the build's private owner process.
dependencies {
    implementation(platform(libs.spring.boot.dependencies))
    implementation(enforcedPlatform(libs.embedded.postgres.binaries.bom))
    implementation(libs.embedded.postgres)
    implementation(libs.postgresql)
    implementation(libs.hikari.cp)
    // The driver's retained annotations name these classes even when compiling a test-only module.
    compileOnly(libs.checker.qual)
    testImplementation(gradleTestKit())
}

prunus {
    javaBuild {
        targetJavaVersion = 25
        instructionCoverageThreshold = 0.7
        lineCoverageThreshold = 0.7
        branchCoverageThreshold = 0.7
        spockEnabled = true
        licenseGateEnabled = true
        pitestEnabled = false
    }
}

// The build service runs this owner in a separate JVM, beyond ordinary test counters.
// Its behavior is covered by explicit postgresLifecycleTest and each build's actual use.
tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    classDirectories.setFrom(files(classDirectories.files.map { root ->
        fileTree(root) {
            exclude("com/lilradish/lite/testsupport/PostgresBuildOwner.class")
            exclude("com/lilradish/lite/testsupport/PostgresBuildOwner\$Owned.class")
        }
    }))
}

tasks.withType<PublishToMavenRepository>().configureEach { enabled = false }
tasks.withType<PublishToMavenLocal>().configureEach { enabled = false }

// Resolve this project's runtime under this project's task lock, not a different test project's lock.
val ownerLauncher = layout.buildDirectory.file("test-postgres/launcher.properties")
tasks.register("prepareTestPostgres") {
    val runtimeClasspath = sourceSets["main"].runtimeClasspath
    val javaVersion = project.extensions.getByType<JavaPluginExtension>().toolchain.languageVersion
    val javaLauncher = project.extensions.getByType<JavaToolchainService>().launcherFor {
        languageVersion.set(javaVersion)
    }
    val manifest = ownerLauncher
    dependsOn(tasks.named("classes"))
    inputs.files(runtimeClasspath)
    outputs.file(manifest)
    doLast {
        val destination = manifest.get().asFile
        destination.parentFile.mkdirs()
        val launch = Properties()
        launch.setProperty("java", javaLauncher.get().executablePath.asFile.absolutePath)
        launch.setProperty("classpath", runtimeClasspath.asPath)
        destination.outputStream().use { launch.store(it, "Private test owner launcher") }
        Files.setPosixFilePermissions(destination.toPath(), PosixFilePermissions.fromString("rw-------"))
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags("postgres-owner-lifecycle") }
}

// Explicit fault experiments own their isolated servers and never participate in ordinary build.
tasks.register<Test>("postgresLifecycleTest") {
    description = "Runs isolated PostgreSQL owner lifecycle fault experiments"
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("postgres-owner-lifecycle") }
    maxParallelForks = 1
    doFirst {
        systemProperty("lite.test.support.runtime", sourceSets["main"].runtimeClasspath.asPath)
        systemProperty("lite.test.gradle.build", rootProject.layout.projectDirectory.file("build.gradle.kts").asFile.absolutePath)
        systemProperty("lite.test.gradle.executable", gradle.gradleHomeDir!!.resolve("bin/gradle").absolutePath)
    }
}
