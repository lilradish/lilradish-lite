package org.lilradish.lite.app.search.development

import org.lilradish.lite.app.inference.AnswerReader
import org.lilradish.lite.app.inference.development.DevelopmentSearchConversion
import org.lilradish.lite.app.model.ModelCatalogConfiguration
import org.lilradish.lite.app.search.SearchWays
import org.lilradish.lite.domain.declaration.Declaration
import org.lilradish.lite.domain.declaration.DeclarationSide
import org.lilradish.lite.domain.declaration.Demands
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.inference.ProductionAnswer
import org.lilradish.lite.domain.model.DeployedModel
import org.lilradish.lite.domain.model.ModelCatalog
import org.lilradish.lite.domain.search.SearchAnswer
import org.lilradish.lite.domain.search.SearchWay
import org.lilradish.lite.domain.text.Legibility
import org.lilradish.lite.domain.wire.JsonValue
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import spock.lang.Specification

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
        offering*.name()*.value() == ['sample_model']
        offering.every { model -> model.unitsOf(writtenOut(released.values().first())) <= model.cameBackPerCallLimit() }
    }

    def "the development stand-in's answer fits the way the development profile releases, what it leaves empty kept as none"() {
        given:
        def answered = AnswerReader.read(DevelopmentSearchConversion.ANSWER) as JsonValue.JsonObject

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
    }

    private static boolean showsNothing(JsonValue value) {
        value instanceof JsonValue.JsonString && !Legibility.holdsSomethingVisibleInProse(value.value())
    }

    private static ApplicationContextRunner developmentContext() {
        new ApplicationContextRunner()
                .withUserConfiguration(ModelCatalogConfiguration, DevelopmentSearchWays, SearchWays)
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues('spring.profiles.active=dev', 'spring.config.location=classpath:/config/')
    }

    private static long writtenOut(SearchWay way) {
        new Declaration(DeclarationSide.GIVES, Demands.ofWorkflow(DeclarationSide.GIVES), way.outputs())
                .longestWritten([:])
    }
}
