package com.lilradish.lite.app.search.development

import com.lilradish.lite.app.inference.AnswerReader
import com.lilradish.lite.app.inference.EndpointJson
import com.lilradish.lite.app.inference.development.DevelopmentSearchConversion
import com.lilradish.lite.app.model.ModelCatalogConfiguration
import com.lilradish.lite.app.search.SearchWays
import com.lilradish.lite.domain.declaration.Declaration
import com.lilradish.lite.domain.declaration.DeclarationSide
import com.lilradish.lite.domain.declaration.Demands
import com.lilradish.lite.domain.declaration.FieldName
import com.lilradish.lite.domain.inference.ProductionAnswer
import com.lilradish.lite.domain.model.DeployedModel
import com.lilradish.lite.domain.model.ModelCatalog
import com.lilradish.lite.domain.search.SearchAnswer
import com.lilradish.lite.domain.search.SearchWay
import com.lilradish.lite.domain.text.Legibility
import com.lilradish.lite.domain.wire.JsonValue
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper

/**
 * The development way read beside the model catalog the development profile binds, so the tool it needs and the
 * tool the development model offers cannot drift apart unseen.
 */
class DevelopmentSearchWaysIntegrationSpec extends Specification {

    def 'the development profile releases one way whose tool a development model offers and whose answer it may give back'() {
        when:
        Map<String, SearchWay> released = null
        List<DeployedModel> offering = null
        developmentContext().run { context ->
            released = context.getBean(SearchWays).all()
            def catalog = context.getBean(ModelCatalog)
            offering = catalog.all().findAll { model ->
                catalog.tool(model.name(), released.values().first().requiredTool()).present
            }
        }

        then:
        released.keySet().toList() == ['sample_web_search']
        offering*.name()*.value() == ['sample_model', 'sample_reader']
        offering.every { model -> model.unitsOf(writtenOut(released.values().first())) <= model.cameBackPerCallLimit() }
    }

    def "the development stand-in's answer to '#question' fits the way the development profile releases, what it leaves empty kept as none"() {
        given:
        def json = JsonMapper.builder().build()
        def payload = json.writeValueAsString([envelope_version: 6, instruction: question, takes: [:]])
        def request = json.writeValueAsBytes([question: payload, tools: [[name: 'web_search', maximum: 3]]])
        def response = EndpointJson.READER.readTree(DevelopmentSearchConversion.replyTo(request))
        def answered = AnswerReader.read(response.get('answer').stringValue()) as JsonValue.JsonObject

        when:
        ProductionAnswer read = null
        developmentContext().run { context ->
            read = SearchAnswer.read(SearchAnswer.asked(context.getBean(SearchWays).all().values().first()), answered)
        }

        then:
        read == new ProductionAnswer.Produced(answered.members().collectEntries { member ->
            [(new FieldName(member.name())): showsNothing(member.value()) ? new JsonValue.JsonNull() : member.value()]
        }, [:])
        answered.members()*.value().any { showsNothing(it) }

        where:
        question << ['Find information about damaged parcel evidence', 'Find information about invoice correction evidence',
                     'Find information about inventory planning evidence']
    }

    private static boolean showsNothing(JsonValue value) {
        value instanceof JsonValue.JsonString && !Legibility.holdsSomethingVisibleInProse(value.value())
    }

    private static ApplicationContextRunner developmentContext() {
        new ApplicationContextRunner()
                .withUserConfiguration(ModelCatalogConfiguration, DevelopmentSearchWays, SearchWays)
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues('spring.profiles.active=local', 'spring.config.location=classpath:/config/')
    }

    private static long writtenOut(SearchWay way) {
        new Declaration(DeclarationSide.GIVES, Demands.ofWorkflow(DeclarationSide.GIVES), way.outputs())
                .longestWritten([:])
    }
}
