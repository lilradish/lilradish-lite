package org.lilradish.lite.app.codestep

import org.lilradish.lite.testutil.ApplicationStore
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Import
import spock.lang.Specification

/**
 * A veto nothing registers lets every release start, so its registration is asserted too. Started with lazy
 * initialisation switched on, since a veto made only when first asked for would never be asked for at all.
 */
@SpringBootTest(properties = "spring.main.lazy-initialization=true")
@Import(ApplicationStore)
class CodeStepsVetoApplicationIntegrationSpec extends Specification {

    @Autowired
    private ConfigurableApplicationContext context

    def "the application starts with the veto made, lazy initialisation or not, over the code steps it holds"() {
        given:
        def names = context.getBeanNamesForType(CodeStepsVeto)

        expect: "made as the application started, before anything asked for it"
        names.size() == 1
        context.beanFactory.containsSingleton(names[0])

        and: "over the one gathering of code steps, holding the development one"
        context.getBean(CodeStepsVeto).codeSteps.is(context.getBean(CodeSteps))
        context.getBean(CodeSteps).declarations().keySet() as List == ["stamp_reference"]
    }
}
