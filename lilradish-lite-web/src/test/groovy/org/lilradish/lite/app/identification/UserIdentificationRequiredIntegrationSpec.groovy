package org.lilradish.lite.app.identification

import java.util.function.Supplier
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import spock.lang.Specification

/**
 * What a deployment is told when it carries nothing that can identify a caller. The archive is built
 * that way on purpose, so this is the ordinary outcome of deploying it unchanged rather than an
 * outlandish one — and what it has to leave behind is a sentence naming the missing thing, not a
 * report about whichever bean asked for one first.
 *
 * <p>The runner closes the context it started before returning, so what is read inside the consumer
 * is carried out as a value and asserted on afterwards: a context asked anything once it is closed
 * answers with a fault of its own, which would read as this refusal rather than as the reading.
 */
class UserIdentificationRequiredIntegrationSpec extends Specification {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(UserIdentificationRequired)

    def "refuses to start, naming the one thing no deployment is shipped with"() {
        when:
        Throwable refused = null
        contextRunner.run { context -> refused = context.startupFailure }

        then:
        refused != null
        refused.message.contains("carries no UserIdentification")
        refused.message.contains(UserIdentification.name)

        and: "the bean that would have asked for one is not what the failure is about"
        !refused.message.contains("StandingController")
    }

    /**
     * The other direction, so the refusal above is a reading of what is there rather than a refusal
     * this always makes: a deployment that supplies one is started and never hears from this.
     */
    def "starts where the deployment supplied one"() {
        given:
        UserIdentification supplied = { request -> Optional.empty() }

        when:
        Throwable refused = null
        int identifications = -1
        contextRunner.withBean(UserIdentification, { -> supplied } as Supplier).run { context ->
            refused = context.startupFailure
            identifications = context.getBeanNamesForType(UserIdentification).length
        }

        then:
        refused == null
        identifications == 1
    }
}
