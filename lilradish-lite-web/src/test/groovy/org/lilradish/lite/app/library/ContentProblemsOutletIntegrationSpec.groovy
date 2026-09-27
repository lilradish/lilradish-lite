package org.lilradish.lite.app.library

import org.libprunus.spring.error.ApiErrorHandler
import org.lilradish.lite.domain.registry.EntryKind
import org.lilradish.lite.testutil.ApplicationStore
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.web.method.ControllerAdviceBean
import spock.lang.Specification

/**
 * The advice answering a refusal for what a version holds, in the order the dispatcher asks advice in. The
 * outlet answers every refusal a code carries, so an advice asked after it is never asked at all, and the
 * problems would leave the server as a bare refusal. Started whole, so every kind's check was found too.
 */
@SpringBootTest
@Import(ApplicationStore)
class ContentProblemsOutletIntegrationSpec extends Specification {

    @Autowired
    private ApplicationContext context

    def "the advice naming every place that does not hold is asked before the outlet answering every refusal"() {
        given:
        def found = ControllerAdviceBean.findAnnotatedBeans(context)
        def asked = found*.beanType
        def problems = found.find { it.beanType == ContentProblemsOutlet }
        def outlet = found.find { ApiErrorHandler.isAssignableFrom(it.beanType) }

        expect: "each found, and once, so their order is a reading"
        asked.count(ContentProblemsOutlet) == 1
        asked.count { ApiErrorHandler.isAssignableFrom(it) } == 1

        and:
        problems.order < outlet.order
        asked.indexOf(ContentProblemsOutlet) < asked.indexOf(outlet.beanType)

        and: "every kind's check found, which the checks refuse to start without"
        context.getBeansOfType(ContentCheck).values()*.kind().toSet() == EntryKind.values().toList().toSet()
    }
}
