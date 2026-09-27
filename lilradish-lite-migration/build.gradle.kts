// Directories of SQL and nothing else: no Java, no Spring, no application.yaml.
plugins { base }

// Two variants, asked for by what they hold, so nothing taking the baseline takes the fixture with it. Each is a
// directory; whether it lands on a classpath or at a filesystem location is the consumer's choice.
val migrations = Attribute.of("org.lilradish.lite.migrations", String::class.java)

dependencies.attributesSchema { attribute(migrations) }

val baseline = configurations.consumable("baseline") { attributes { attribute(migrations, "baseline") } }

val developmentSeed =
    configurations.consumable("developmentSeed") { attributes { attribute(migrations, "development-seed") } }

artifacts {
    add(baseline.name, layout.projectDirectory.dir("src/main/resources/db/migration/common"))
    add(developmentSeed.name, layout.projectDirectory.dir("src/main/resources/db/migration/dev"))
}
