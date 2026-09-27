package org.lilradish.lite.web

import org.lilradish.lite.testutil.ApplicationStore
import org.lilradish.lite.web.fixture.ActProbeController
import org.lilradish.lite.web.fixture.NoMethodProbes
import org.lilradish.lite.web.fixture.UndeclaredProbeController
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.server.PathContainer
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.handler.AbstractHandlerMapping
import org.springframework.web.servlet.handler.BeanNameUrlHandlerMapping
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import spock.lang.Specification

/** A veto nothing registers passes every mapping unasked, so its registration is asserted too. The runner closes
 * its context before returning, so what the consumer reads is carried out as a value and asserted afterwards. */
@SpringBootTest
@Import(ApplicationStore)
class AskDeclarationVetoIntegrationSpec extends Specification {

    @Autowired
    private ApplicationContext context

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(WebMvcAutoConfiguration, DispatcherServletAutoConfiguration,
                    HttpMessageConvertersAutoConfiguration, PropertyPlaceholderAutoConfiguration))
            .withUserConfiguration(AskDeclarationVeto)

    /** Started, so every handler this application ships passed the veto on the way up. */
    def "the application starts with the veto standing over the mappings the dispatcher routes by"() {
        given:
        def vetoes = context.getBeansOfType(AskDeclarationVeto)
        def mappings = context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping)
        def beanNameMappings = context.getBean("beanNameHandlerMapping", BeanNameUrlHandlerMapping)

        expect:
        vetoes.size() == 1
        vetoes.values().first().mappings.is(mappings)
        vetoes.values().first().beanNameMappings.is(beanNameMappings)

        and: "over mappings holding handlers under the prefix, so what passed is a reading"
        mappings.handlerMethods.keySet().any { mapped ->
            mapped.patternValues.any { CallerAdmission.answersThisApplication(PathContainer.parsePath(it)) }
        }
    }

    def "refuses to start while a handler under the prefix declares nothing it asks, naming the handler"() {
        when:
        Throwable refused = null
        contextRunner.withUserConfiguration(UndeclaredProbeController).run { started -> refused = started.startupFailure }

        then:
        refused instanceof IllegalStateException
        refused.message.contains("declaringNothing")
        refused.message.contains("declares not exactly one of the act it asks")
    }

    def "refuses to start while a bean is named by an address under the prefix"() {
        when:
        Throwable refused = null
        contextRunner.withUserConfiguration(NoMethodProbes).run { started -> refused = started.startupFailure }

        then:
        refused instanceof IllegalStateException
        refused.message.endsWith("answers under the prefix with no handler method to declare what it asks on")
    }

    def "starts where every handler under the prefix declares exactly one thing it asks"() {
        when:
        Throwable refused = null
        Set<String> mapped = []
        contextRunner.withUserConfiguration(ActProbeController).run { started ->
            refused = started.startupFailure
            mapped = started.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping)
                    .handlerMethods.values()*.method*.name as Set
        }

        then:
        refused == null
        mapped == ["askingAnAct", "askingNothing"] as Set
    }

    /**
     * The veto reads two mappings, and the gate is what holds every other one. A mapping carrying no gate would
     * answer under the prefix with nothing asked, so each has to carry it, and only once.
     */
    def "every mapping the dispatcher routes by carries the gate exactly once"() {
        given:
        def gate = context.getBean(ActAdmission)
        def mappings = context.getBeansOfType(HandlerMapping)

        expect:
        mappings.keySet().containsAll(["requestMappingHandlerMapping", "routerFunctionMapping",
                                       "beanNameHandlerMapping", "resourceHandlerMapping"])
        mappings.values().every { mapping ->
            mapping instanceof AbstractHandlerMapping && mapping.adaptedInterceptors.count { it.is(gate) } == 1
        }
    }
}
