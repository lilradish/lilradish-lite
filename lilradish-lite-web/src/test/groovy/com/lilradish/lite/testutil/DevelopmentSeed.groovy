package com.lilradish.lite.testutil

import java.util.function.Supplier
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.AnnotationConfigApplicationContext

/**
 * The development seed applied over the real baseline, and the development catalog bound as the application binds it,
 * for specs that hold the development source set to what the seed holds.
 */
final class DevelopmentSeed {

    /** Demanded rather than defaulted, for the reason {@link Baseline#MIGRATIONS} gives. */
    private static final String LOCATION = Objects.requireNonNull(System.getProperty("development-seed.location"),
            "development-seed.location was not set; the build names it to test")

    private DevelopmentSeed() {}

    /** The baseline is named too: its migrations are applied already, and validate refuses one it cannot resolve. */
    static void appliedTo(DataSource database) {
        Flyway.configure()
                .dataSource(database)
                .schemas("app")
                .locations("filesystem:" + Baseline.MIGRATIONS, "filesystem:" + LOCATION)
                .failOnMissingLocations(true)
                .validateMigrationNaming(true)
                .load()
                .migrate()
    }

    /** The development file's list alone, bound by the configuration the application scans for. */
    static ApplicationContextRunner catalogBinding() {
        new ApplicationContextRunner({
            def context = new AnnotationConfigApplicationContext()
            context.scan("com.lilradish.lite.app.model")
            context
        } as Supplier<ConfigurableApplicationContext>)
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.config.location=classpath:/config/application-local.yaml")
    }
}
