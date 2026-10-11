package com.lilradish.lite.app.codestep

import com.lilradish.lite.testutil.ApplicationStore
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Import
import spock.lang.Specification

/**
 * A veto nothing registers lets every release start, so its registration is asserted too. Started with lazy
 * initialisation switched on, since a veto made only when first asked for would never be asked for at all.
 */
@SpringBootTest(properties = ["spring.main.lazy-initialization=true", "lite.test.database.schema-sensitive=true"])
@Import(ApplicationStore)
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class CodeStepsVetoApplicationIntegrationSpec extends Specification {

    @Autowired
    private ConfigurableApplicationContext context

    def "the application starts with the veto made, lazy initialisation or not, over the code steps it holds"() {
        given:
        def names = context.getBeanNamesForType(CodeStepsVeto)

        expect: "made as the application started, before anything asked for it"
        names.size() == 1
        context.beanFactory.containsSingleton(names[0])

        and: "over the one gathering of code steps, holding the released file reader and development one"
        context.getBean(CodeStepsVeto).codeSteps.is(context.getBean(CodeSteps))
        context.getBean(CodeSteps).declarations().keySet() as List == ["read_text_file", "stamp_reference"]
    }
}
