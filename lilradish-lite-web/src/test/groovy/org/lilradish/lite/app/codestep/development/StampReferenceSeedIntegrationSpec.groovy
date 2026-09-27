package org.lilradish.lite.app.codestep.development

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import org.lilradish.lite.app.codestep.CodeSteps
import org.lilradish.lite.app.codestep.CodeStepsVeto
import org.lilradish.lite.app.library.ReleasedCodeSteps
import org.lilradish.lite.app.library.WorkflowContentCheck
import org.lilradish.lite.domain.identity.GroupId
import org.lilradish.lite.domain.registry.EntryVersionId
import org.lilradish.lite.testutil.Baseline
import org.lilradish.lite.testutil.codestep.SpecCodeStep
import org.lilradish.lite.testutil.library.DeployedModels
import org.springframework.jdbc.core.simple.JdbcClient
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * The development code step held to what the development seed publishes and names, read off a real server running
 * the baseline and the seed, so neither can drift from the other unseen.
 */
class StampReferenceSeedIntegrationSpec extends Specification {

    static final String DEVELOPMENT_SEED = Objects.requireNonNull(System.getProperty("development-seed.location"),
            "development-seed.location was not set; the build names it to test")

    static final GroupId SUPPORT = new GroupId(UUID.fromString("00000002-0000-4000-8000-000000000101"))

    static final EntryVersionId STAMPING = new EntryVersionId(UUID.fromString("00000007-0000-4000-8000-000000000108"))

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    JdbcClient session

    def setupSpec() {
        DataSource database = Baseline.appliedTo(server, "postgres")
        Flyway.configure()
                .dataSource(database)
                .schemas("app")
                .locations("filesystem:" + Baseline.MIGRATIONS, "filesystem:" + DEVELOPMENT_SEED)
                .failOnMissingLocations(true)
                .validateMigrationNaming(true)
                .load()
                .migrate()
        session = JdbcClient.create(database)
    }

    def "a release holding the development code step starts on the seed"() {
        when:
        new CodeStepsVeto(new CodeSteps([new StampReference()]), session).afterSingletonsInstantiated()

        then:
        noExceptionThrown()
    }

    /** What the seed publishes is what the development source set holds, and nothing else would do. */
    def "a release not holding the development code step is refused the start on the seed, naming it"() {
        when:
        new CodeStepsVeto(new CodeSteps([SpecCodeStep.SEND_REPLY]), session).afterSingletonsInstantiated()

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "Code step stamp_reference is published, and this release does not hold it"
    }

    /** The seeded version naming the code step is whole against what the release declares of it. */
    def "the seeded workflow running the development code step holds against what it declares"() {
        given:
        def check = new WorkflowContentCheck(session, DeployedModels.HELD,
                new ReleasedCodeSteps(new CodeSteps([new StampReference()])))

        expect:
        check.problemsIn(SUPPORT, STAMPING) == []
        session.sql("select cast(code_step as text) from workflow_steps where entry_version_id = ?::uuid")
                .params(STAMPING.value().toString()).query(String).list() == ["stamp_reference"]
    }
}
