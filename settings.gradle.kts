pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // libprunus is built and installed locally, and nothing else is taken from there or looked for elsewhere.
        exclusiveContent {
            forRepository { mavenLocal() }
            filter { includeGroupAndSubgroups("org.libprunus") }
        }
    }
}

plugins {
    // Version catalogs are declared by this file, so they cannot be read from it;
    // this is the one place the aggregate's version lives.
    id("com.autonomousapps.build-health") version "3.19.1"
}

dependencyResolutionManagement {
    // Single source of truth for repositories: FAIL_ON_PROJECT_REPOS rejects any
    // declared in a project or convention plugin.
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        exclusiveContent {
            forRepository { mavenLocal() }
            filter { includeGroupAndSubgroups("org.libprunus") }
        }
        // node-gradle would self-register this repo, which FAIL_ON_PROJECT_REPOS
        // rejects; declare it centrally and set distBaseUrl = null in the web
        // project so the plugin resolves Node from here.
        exclusiveContent {
            forRepository {
                ivy {
                    name = "Node.js"
                    setUrl("https://nodejs.org/dist/")
                    patternLayout {
                        artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]")
                    }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("org.nodejs", "node") }
        }
    }
}

rootProject.name = "lilradish-lite"

include("lilradish-lite-migration")
include("lilradish-lite-web")
include("lilradish-lite-flyway")
