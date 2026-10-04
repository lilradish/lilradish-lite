package org.lilradish.lite.app.codestep.development

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import javax.sql.DataSource
import org.lilradish.lite.app.codestep.CodeSteps
import org.lilradish.lite.app.codestep.CodeStepsVeto
import org.lilradish.lite.app.library.ReleasedCodeSteps
import org.lilradish.lite.app.library.WorkflowContentCheck
import org.lilradish.lite.app.search.SearchWays
import org.lilradish.lite.domain.identity.GroupId
import org.lilradish.lite.domain.registry.EntryVersionId
import org.lilradish.lite.testutil.Baseline
import org.lilradish.lite.testutil.DevelopmentSeed
import org.lilradish.lite.testutil.codestep.SpecCodeStep
import org.lilradish.lite.testutil.library.DeployedModels
import org.lilradish.lite.testutil.library.LibraryStore
import org.springframework.jdbc.core.simple.JdbcClient
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/** Development seed data must agree with the code steps held by the release. */
class StampReferenceSeedIntegrationSpec extends Specification {

    static final GroupId SUPPORT = new GroupId(UUID.fromString("00000002-0000-4000-8000-000000000101"))

    static final EntryVersionId STAMPING = new EntryVersionId(UUID.fromString("00000007-0000-4000-8000-000000000108"))

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    JdbcClient session

    def setupSpec() {
        DataSource database = Baseline.appliedTo(server, "postgres")
        DevelopmentSeed.appliedTo(database)
        session = JdbcClient.create(database)
    }

    def "a release holding the development code step starts on the seed"() {
        when:
        new CodeStepsVeto(new CodeSteps([new StampReference()]), session, LibraryStore.FILES).afterSingletonsInstantiated()

        then:
        noExceptionThrown()
    }


    def "a release not holding the development code step is refused the start on the seed, naming it"() {
        when:
        new CodeStepsVeto(new CodeSteps([SpecCodeStep.SEND_REPLY]), session, LibraryStore.FILES).afterSingletonsInstantiated()

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "Code step stamp_reference is published, and this release does not hold it"
    }


    def "the seeded workflow running the development code step holds against what it declares"() {
        given:
        def check = new WorkflowContentCheck(session, DeployedModels.HELD,
                new ReleasedCodeSteps(new CodeSteps([new StampReference()])), LibraryStore.FILES, new SearchWays([]))

        expect:
        def problems = check.problemsIn(SUPPORT, STAMPING)
        problems.problems() == []
        problems.total() == 0
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        session.sql("select cast(code_step as text) from workflow_steps where entry_version_id = ?::uuid")
                .params(STAMPING.value().toString()).query(String).list() == ["stamp_reference"]
    }
}
