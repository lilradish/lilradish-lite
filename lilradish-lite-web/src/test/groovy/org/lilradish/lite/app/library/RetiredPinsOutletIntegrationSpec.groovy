package org.lilradish.lite.app.library

import org.libprunus.spring.error.ApiErrorHandler
import org.lilradish.lite.testutil.ApplicationStore
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.web.method.ControllerAdviceBean
import spock.lang.Specification

/**
 * The advice answering a refusal for pins retired since, in the order the dispatcher asks advice in. The
 * outlet answers every refusal a code carries, so an advice asked after it is never asked at all, and the
 * pins would leave the server as a bare refusal.
 */
@SpringBootTest
@Import(ApplicationStore)
class RetiredPinsOutletIntegrationSpec extends Specification {

    @Autowired
    private ApplicationContext context

    /**
     * Asked of the order each declares as well as of where each falls: two of one order fall as they were
     * found, which is an accident of scanning rather than anything declared.
     */
    def "the advice naming pins retired since is asked before the outlet answering every refusal"() {
        given:
        def found = ControllerAdviceBean.findAnnotatedBeans(context)
        def asked = found*.beanType
        def pins = found.find { it.beanType == RetiredPinsOutlet }
        def outlet = found.find { ApiErrorHandler.isAssignableFrom(it.beanType) }

        expect: "each found, and once, so their order is a reading"
        asked.count(RetiredPinsOutlet) == 1
        asked.count { ApiErrorHandler.isAssignableFrom(it) } == 1

        and:
        pins.order < outlet.order
        asked.indexOf(RetiredPinsOutlet) < asked.indexOf(outlet.beanType)
    }
}
