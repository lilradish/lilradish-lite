package org.lilradish.lite;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * The guard on what this project's test classpath must not carry. The migrations belong to another
 * project and are handed to the specs here as a filesystem path; brought in as an ordinary
 * dependency instead, they land under {@code db/migration} where Spring Boot's Flyway
 * auto-configuration finds them without being asked and runs a PostgreSQL baseline into whatever
 * datasource this context has — and they bring a second {@code application.yaml} and a second
 * {@code @SpringBootApplication} with them.
 *
 * <p>This is the test that asks. A spec that starts the whole application for a reason of its own
 * reaches the same auto-configurations and asserts nothing about what the classpath carries, and the
 * auto-configuration test beside this one never loads this application at all. Without this test the
 * dependency can be put back and nothing reddens.
 *
 * <p>The emptiness is the assertion, which is why it is written down rather than left implicit: a
 * test that finds nothing is exactly what proves nothing is there, and deleting it as an assertion
 * about nothing is how the defect returns.
 */
// Named rather than imported: ApplicationStore is Groovy, compiled after this Java tree and against it.
@SpringBootTest(properties = "spring.main.sources=org.lilradish.lite.testutil.ApplicationStore")
class ApplicationContextIntegrationTest {

    @Test
    void theApplicationStartsWithEverythingItsOwnClasspathCarries() {
        // Reaching here is the assertion: a failed context start fails the test before it runs.
    }

    @Test
    void noMigrationReachesThisProjectOnTheClasspath() throws Exception {
        // Recursive, as the migrations sit in directories below db/migration and a single * stops at one level.
        Resource[] found = new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/**/*.sql");

        assertThat(found)
                .as("db/migration must stay off this test classpath; the specs are given a filesystem path")
                .isEmpty();
    }

    @Test
    void noMigrationRunnerProfileReachesThisProjectOnTheClasspath() throws Exception {
        Resource[] found = new PathMatchingResourcePatternResolver().getResources("classpath*:application-dev.yaml");

        assertThat(found)
                .as("the migration runner's dev profile must stay off this test classpath")
                .isEmpty();
    }
}
