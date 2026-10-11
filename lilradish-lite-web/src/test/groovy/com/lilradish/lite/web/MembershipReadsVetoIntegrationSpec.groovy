package com.lilradish.lite.web

import com.lilradish.lite.testutil.ApplicationStore
import com.lilradish.lite.web.fixture.GroupProbeController
import com.lilradish.lite.web.fixture.MembershipChangeProbeController
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestComponent
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import spock.lang.Specification

/** A veto nothing registers passes every mapping unasked, so its registration is asserted too. The runner closes
 * its context before returning, so what the consumer reads is carried out as a value and asserted afterwards. */
@SpringBootTest
@Import(ApplicationStore)
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class MembershipReadsVetoIntegrationSpec extends Specification {

    @TestComponent
    @RestController
    static class BodyReadProbe {

        @PostMapping("/membership-read-only-post-probe")
        @GroupMembershipRequired
        @MembershipReadOnlyPost
        String readingBody() { "read" }
    }

    @Autowired
    private ApplicationContext context

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(WebMvcAutoConfiguration, DispatcherServletAutoConfiguration,
                    HttpMessageConvertersAutoConfiguration, PropertyPlaceholderAutoConfiguration))
            .withUserConfiguration(MembershipReadsVeto)

    /** Started, so every handler this application ships passed the veto on the way up. */
    def "the application starts with the veto standing over the mappings the dispatcher routes by"() {
        given:
        def vetoes = context.getBeansOfType(MembershipReadsVeto)
        def mappings = context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping)

        expect:
        vetoes.size() == 1
        vetoes.values().first().mappings.is(mappings)

        and: "over mappings holding a handler asking membership alone, so what passed is a reading"
        mappings.handlerMethods.values().any {
            it.beanType.simpleName == "LibraryController" && it.method.name == "entries"
        }
    }

    def "refuses to start while membership alone guards a change, naming the handler"() {
        when:
        Throwable refused = null
        contextRunner.withUserConfiguration(MembershipChangeProbeController).run { started ->
            refused = started.startupFailure
        }

        then:
        refused instanceof IllegalStateException
        refused.message.contains("changingOnMembership")
        refused.message.contains("asks membership alone of something other than a read")
    }

    def "starts where membership alone guards only reads"() {
        when:
        Throwable refused = null
        Set<String> mapped = []
        contextRunner.withUserConfiguration(GroupProbeController).run { started ->
            refused = started.startupFailure
            mapped = started.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping)
                    .handlerMethods.values()*.method*.name as Set
        }

        then:
        refused == null
        mapped == ["changingMembership", "inTheGroup"] as Set
    }

    def "starts with a sole marked membership POST registered by the real dispatcher"() {
        when:
        Throwable refused = null
        Set<String> mapped = []
        contextRunner.withUserConfiguration(BodyReadProbe).run { started ->
            refused = started.startupFailure
            if (refused == null) {
                mapped = started.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping)
                        .handlerMethods.values()*.method*.name as Set
            }
        }

        then:
        refused == null
        mapped == ["readingBody"] as Set
    }
}
