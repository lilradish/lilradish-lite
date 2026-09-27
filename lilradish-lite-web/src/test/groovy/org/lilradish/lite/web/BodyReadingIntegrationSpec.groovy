package org.lilradish.lite.web

import jakarta.servlet.Filter
import jakarta.servlet.MultipartConfigElement
import org.lilradish.lite.testutil.ApplicationStore
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.web.filter.FormContentFilter
import org.springframework.web.multipart.MultipartResolver
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import spock.lang.Specification

/**
 * What reads a request's body before the handler it was sent to, asked of the application as it
 * starts rather than of a slice. A form filter reads a whole form body ahead of every filter of this
 * application's own, the door included, and a multipart resolver reads a whole multipart body ahead of
 * the gate; each is on unless configured off, so a setting dropped from the configuration would bring
 * it back without any request here failing for it.
 */
@SpringBootTest
@Import(ApplicationStore)
class BodyReadingIntegrationSpec extends Specification {

    @Autowired
    private ApplicationContext context

    def "nothing reads a form or a multipart body before the handler it was sent to"() {
        expect:
        context.getBeansOfType(FormContentFilter).isEmpty()
        context.getBeansOfType(MultipartResolver).isEmpty()
        context.getBeansOfType(MultipartConfigElement).isEmpty()

        and: "asked of a context holding this application's own filters, so the absence is a reading"
        context.getBeansOfType(Filter).values().any { it instanceof CallerAdmission }
    }

    /** Imported by the door and scanned besides, and registered once all the same: two would each keep a turn. */
    def "the application holds exactly one turn a large body is read in"() {
        expect:
        context.getBeansOfType(LargeBodyAdmission).size() == 1
        context.getBeansOfType(CallerAdmission).size() == 1
    }

    /** Started, so every handler this application ships passed the veto on the way up. */
    def "the application starts with the veto on handing any handler its body standing over every mapping"() {
        given:
        def vetoes = context.getBeansOfType(BoundBodyVeto)
        def mappings = context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping)

        expect:
        vetoes.size() == 1

        and: "over mappings holding the handler that takes a body, so what passed is a reading"
        mappings.handlerMethods.values().any {
            it.beanType.simpleName == "PoolChangesController" && it.method.name == "bringIn"
        }
    }
}
