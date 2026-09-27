// Declared here and applied in the projects that need them. A plugin each project resolves on its
// own lands in that project's classloader scope, and a build service shared between them — Spotless
// has one — is then two classes from two loaders and cannot be handed across. Naming them once here
// loads them into the scope both projects inherit.
plugins {
    alias(libs.plugins.libprunus.core.plugin) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.node.gradle) apply false
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
