package com.lilradish.lite.app.inference.development

import static com.lilradish.lite.testutil.inference.Askings.text

import com.lilradish.lite.app.inference.AnswerReader
import com.lilradish.lite.app.inference.EndpointJson
import com.lilradish.lite.app.inference.SearchObservation
import com.lilradish.lite.app.inference.ToolCallOutcome
import com.lilradish.lite.app.inference.ToolCallRequest
import com.lilradish.lite.app.inference.ToolModelCalls
import com.lilradish.lite.app.inference.ToolUseLimit
import com.lilradish.lite.app.library.QuestionsAsked
import com.lilradish.lite.app.model.ModelCatalogConfiguration
import com.lilradish.lite.domain.declaration.Asking
import com.lilradish.lite.domain.declaration.FieldName
import com.lilradish.lite.domain.declaration.Instruction
import com.lilradish.lite.domain.identity.GroupId
import com.lilradish.lite.domain.inference.CallOutcome
import com.lilradish.lite.domain.inference.CallProgress
import com.lilradish.lite.domain.inference.CallRequest
import com.lilradish.lite.domain.inference.Confidence
import com.lilradish.lite.domain.inference.ModelCallPurpose
import com.lilradish.lite.domain.inference.Payload
import com.lilradish.lite.domain.inference.ProductionAnswer
import com.lilradish.lite.domain.inference.ReviewAnswer
import com.lilradish.lite.domain.inference.Unwrapping
import com.lilradish.lite.domain.model.DeployedModel
import com.lilradish.lite.domain.model.DeployedTool
import com.lilradish.lite.domain.model.ModelCatalog
import com.lilradish.lite.domain.model.ModelName
import com.lilradish.lite.domain.model.ModelToolKind
import com.lilradish.lite.domain.model.ModelToolName
import com.lilradish.lite.domain.model.SentText
import com.lilradish.lite.domain.registry.EntryVersionId
import com.lilradish.lite.domain.search.SearchAnswer
import com.lilradish.lite.domain.search.SearchWay
import com.lilradish.lite.domain.wire.CanonicalJson
import com.lilradish.lite.domain.wire.JsonValue.JsonString
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.Baseline
import com.lilradish.lite.testutil.DevelopmentSeed
import com.lilradish.lite.testutil.inference.Json
import javax.sql.DataSource
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.support.JdbcTransactionManager
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/** Development seed data and the deployed catalog must agree. */
class DevelopmentModelCallsSeedIntegrationSpec extends Specification {

    static final String MODEL_STEP = "0000000c-0000-4000-8000-000000000101"

    static final SentText SENT = SentText.measure("answer in JSON", "the invoice")

    static final ModelToolName SEARCH = new ModelToolName("web_search")
    static final FieldName TOPIC = new FieldName("topic")
    static final Asking ASKING = new Asking(new Instruction("Find evidence for the fictional practice case."),
            [text("topic", 1000)], SearchAnswer.asked(new SearchWay("web", SEARCH, 120, 12000, 4000, 4000)))
    static final DeployedModel MODEL = new DeployedModel(new ModelName("sample_model"), [], 1000,
            BigDecimal.ONE, 1000, [], [new DeployedTool(SEARCH, ModelToolKind.WEB_SEARCH, 3, [])])

    @Shared
    @AutoCleanup
    TestPostgres server = TestPostgres.isolated()

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
        DevelopmentSeed.appliedTo(database)
        session = JdbcClient.create(database)
        // DB-SPECIFIC: PostgreSQL's uuid type.
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
        configurationSources[0].contains("config/application-local.yaml")
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
                 (new FieldName("details")) : Json.of([product: "Travel kettle, 1.2 litre (stand-in answer; no model was called)",
                                                       order_reference: "4417-2093"])],
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

    def "development search passes through configured route and both conversions with a topic-specific four-field answer"() {
        given:
        CallProgress progress = Mock()
        ToolCallOutcome returned = null
        Throwable refused = null
        def runner = new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.config.location=classpath:/config/application-local.yaml")
                .withUserConfiguration(DevelopmentCallConfiguration, ModelCatalogConfiguration)
                .withBean(DevelopmentSearchConversion)
                .withBean(DevelopmentToolModelCalls)

        when:
        runner.run { context ->
            refused = context.startupFailure
            if (refused == null) {
                def sent = SentText.measure("system", CanonicalJson.write(Payload.toProduce(ASKING,
                        [(TOPIC): new JsonString("Demo Sample question")], null)))
                returned = context.getBean(ToolModelCalls).call(new ToolCallRequest(
                        new CallRequest(context.getBean(ModelCatalog).find(MODEL.name()).orElseThrow(), null, ModelCallPurpose.PRODUCE, sent),
                        [new ToolUseLimit(SEARCH, maximum)]), progress)
            }
        }

        then:
        refused == null
        returned.outcome() == new CallOutcome.CameBack(
                returned.outcome().answer(),
                7, 9, true, false, null, null)
        returned.reportedUses() == [(SEARCH): reported]
        returned.observations() == ([new SearchObservation(SEARCH, "Demo Sample question",
                "## Invented source notes\nThe practice record concerns: Demo Sample question\n" +
                "[Imaginary source](https://example.com/practice)\nNo real policy or supplier is represented.")] +
                (reported == 2L ? [new SearchObservation(SEARCH, "Demo Sample question / evidence ledger",
                        "## Invented evidence ledger\nMira Cloudmere recorded the missing box photograph for: Demo Sample question\n" +
                        "[Imaginary ledger](https://example.org/evidence)\nThe fictional dispatch record remains open.")] : []))
        def answer = EndpointJson.READER.readTree(returned.outcome().answer())
        answer.properties().collect { it.key } as Set == ['title', 'body', 'references', 'other'] as Set
        answer.get('title').stringValue() == 'Imaginary Mooncrate: topic review'
        answer.get('body').stringValue().contains('Question received: Demo Sample question')
        answer.get('other').stringValue() == ''
        1 * progress.aboutToSend()
        0 * _

        where:
        maximum || reported
        3L      || 2L
        1L      || 1L
    }

    private CallOutcome.CameBack call(ModelCallPurpose purpose) {
        DeployedModel model = catalog.find(new ModelName(modelStep.producer_model as String)).orElseThrow()
        modelCalls.call(new CallRequest(model, null, purpose, SENT), progress) as CallOutcome.CameBack
    }

    private EntryVersionId question() {
        new EntryVersionId(UUID.fromString(modelStep.question as String))
    }


    private List<FieldName> waitingOnReview(ProductionAnswer.Produced produced) {
        // DB-SPECIFIC: PostgreSQL pg_attrdef/pg_attribute; pg_get_expr(); regclass type.
        String rule = session.sql("""
                select pg_get_expr(definition.adbin, definition.adrelid)
                  from pg_attrdef definition
                  join pg_attribute target on target.attrelid = definition.adrelid and target.attnum = definition.adnum
                 where definition.adrelid = cast('production_values' as regclass) and target.attname = 'needs_review'
                """).query(String).single()
        String confidences = CanonicalJson.write(Json.of(produced.confidences().collectEntries { name, confidence ->
            [(name.value()): confidence.percent()]
        }))
        // DB-SPECIFIC: PostgreSQL jsonb type; JSON ->> operator.
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

    private void bindDevelopmentCatalog() {
        DevelopmentSeed.catalogBinding().run { context ->
            catalog = context.getBean(ModelCatalog)
            configurationSources = context.environment.propertySources*.name
                    .findAll { it.startsWith("Config resource") }
        }
    }
}
