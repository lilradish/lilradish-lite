package org.lilradish.lite.app.inference.development

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.util.function.Supplier
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import org.lilradish.lite.app.inference.AnswerReader
import org.lilradish.lite.app.library.QuestionsAsked
import org.lilradish.lite.domain.declaration.Asking
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.identity.GroupId
import org.lilradish.lite.domain.inference.CallOutcome
import org.lilradish.lite.domain.inference.CallProgress
import org.lilradish.lite.domain.inference.CallRequest
import org.lilradish.lite.domain.inference.Confidence
import org.lilradish.lite.domain.inference.ModelCallPurpose
import org.lilradish.lite.domain.inference.ProductionAnswer
import org.lilradish.lite.domain.inference.ReviewAnswer
import org.lilradish.lite.domain.inference.Unwrapping
import org.lilradish.lite.domain.model.DeployedModel
import org.lilradish.lite.domain.model.ModelCatalog
import org.lilradish.lite.domain.model.ModelName
import org.lilradish.lite.domain.model.SentText
import org.lilradish.lite.domain.registry.EntryVersionId
import org.lilradish.lite.domain.wire.CanonicalJson
import org.lilradish.lite.testutil.Baseline
import org.lilradish.lite.testutil.inference.Json
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.support.JdbcTransactionManager
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * The stand-in's fixed answers held to what the development seed and the development catalog really hold,
 * read off a real server running the baseline and the seed, so neither can drift from the answers unseen.
 */
class DevelopmentModelCallsSeedIntegrationSpec extends Specification {

    static final String MODEL_STEP = "0000000c-0000-4000-8000-000000000101"

    static final String DEVELOPMENT_SEED = Objects.requireNonNull(System.getProperty("development-seed.location"),
            "development-seed.location was not set; the build names it to test")

    static final SentText SENT = SentText.measure("answer in JSON", "the invoice")

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    JdbcClient session

    @Shared
    Map<String, Object> modelStep

    @Shared
    Asking asking

    @Shared
    ModelCatalog catalog

    @Shared
    List<String> configurationSources

    DevelopmentModelCalls modelCalls = new DevelopmentModelCalls()

    CallProgress progress = Stub()

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
        modelStep = session.sql("""
                select cast(step.pinned_version_id as text) as question, step.producer_model, workflow.helper_model,
                       cast(entry.group_id as text) as group_id
                  from workflow_steps step
                  join workflow_versions workflow on workflow.entry_version_id = step.entry_version_id
                  join entry_versions version on version.entry_version_id = step.entry_version_id
                  join entries entry on entry.entry_id = version.entry_id
                 where step.workflow_step_id = cast(? as uuid)
                """).params(MODEL_STEP).query().singleRow()
        new ApplicationContextRunner()
                .withBean(QuestionsAsked, session, new JdbcTransactionManager(database))
                .run { context ->
                    asking = context.getBean(QuestionsAsked).of(
                            new GroupId(UUID.fromString(modelStep.group_id as String)), question())
                }
        bindDevelopmentCatalog()
    }

    def "the development catalog is bound from the development file alone"() {
        expect:
        configurationSources.size() == 1
        configurationSources[0].contains("config/application-dev.yaml")
    }

    def "every model the seeded workflow names is deployed in the development catalog"() {
        expect:
        [modelStep.producer_model, modelStep.helper_model].every {
            it != null && catalog.find(new ModelName(it as String)).present
        }
        !catalog.find(new ModelName("no_such_model")).present
    }

    def "what producing is answered with fits the question the seeded model step pins, whole and sure of its term"() {
        when:
        def outcome = call(ModelCallPurpose.PRODUCE)
        def produced = Unwrapping.production(asking.gives(), AnswerReader.read(outcome.answer()))

        then:
        !outcome.cutOff()
        produced == new ProductionAnswer.Produced(
                [(new FieldName("category")): Json.of("Billing"),
                 (new FieldName("details")) : Json.of([product: "A development stand-in produced this; no model was called.",
                                                       order_reference: null])],
                [(new FieldName("category")): new Confidence(90)])
    }

    def "what reviewing is answered with decides exactly what the produced answer leaves to review, and assures it"() {
        given:
        def produced = Unwrapping.production(asking.gives(), AnswerReader.read(call(ModelCallPurpose.PRODUCE).answer()))
        def deciding = waitingOnReview(produced as ProductionAnswer.Produced)

        when:
        def outcome = call(ModelCallPurpose.REVIEW)
        def reviewed = Unwrapping.review(deciding, AnswerReader.read(outcome.answer()))

        then:
        !outcome.cutOff()
        deciding == [new FieldName("details")]
        reviewed == new ReviewAnswer.Reviewed([(new FieldName("details")): new ReviewAnswer.Assured()])
    }

    private CallOutcome.CameBack call(ModelCallPurpose purpose) {
        DeployedModel model = catalog.find(new ModelName(modelStep.producer_model as String)).orElseThrow()
        modelCalls.call(new CallRequest(model, null, purpose, SENT), progress) as CallOutcome.CameBack
    }

    private EntryVersionId question() {
        new EntryVersionId(UUID.fromString(modelStep.question as String))
    }

    /** The store's own rule for a produced value waiting on review, read off the column and applied to each field. */
    private List<FieldName> waitingOnReview(ProductionAnswer.Produced produced) {
        String rule = session.sql("""
                select pg_get_expr(definition.adbin, definition.adrelid)
                  from pg_attrdef definition
                  join pg_attribute target on target.attrelid = definition.adrelid and target.attnum = definition.adnum
                 where definition.adrelid = cast('production_values' as regclass) and target.attname = 'needs_review'
                """).query(String).single()
        String confidences = CanonicalJson.write(Json.of(produced.confidences().collectEntries { name, confidence ->
            [(name.value()): confidence.percent()]
        }))
        session.sql("""
                select field.name
                  from declaration_fields field
                 cross join lateral (
                        select ${rule} as needs_review
                          from (select field.standing as field_standing, field.standing_threshold as standing_threshold,
                                       cast(cast(? as jsonb) ->> field.name as integer) as confidence) as production_values
                       ) as judged
                 where field.entry_version_id = cast(? as uuid) and field.side = 'gives' and field.parent_field_id is null
                   and judged.needs_review
                 order by field.position
                """.toString()).params(confidences, modelStep.question).query(String).list().collect { new FieldName(it) }
    }

    /** The development file's list alone, bound by the configuration the application scans for. */
    private void bindDevelopmentCatalog() {
        new ApplicationContextRunner({
            def context = new AnnotationConfigApplicationContext()
            context.scan("org.lilradish.lite.app.model")
            context
        } as Supplier<ConfigurableApplicationContext>)
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.config.location=classpath:/config/application-dev.yaml")
                .run { context ->
                    catalog = context.getBean(ModelCatalog)
                    configurationSources = context.environment.propertySources*.name
                            .findAll { it.startsWith("Config resource") }
                }
    }
}
