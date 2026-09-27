package org.lilradish.lite.app.codestep

import java.time.Duration
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import spock.lang.Specification

/**
 * What the deployment says of its code steps, bound by the binder the application starts with. The runner closes
 * the context before returning, so what is read inside the consumer is carried out as a value and asserted on after.
 */
class CodeStepConfigurationIntegrationSpec extends Specification {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(CodeStepConfiguration)

    def "binds the longest a code step runs for as written"() {
        when:
        Throwable refused = null
        Duration bound = null
        contextRunner.withPropertyValues("lilradish.code-steps.longest-run=${written}").run { context ->
            refused = context.startupFailure
            bound = context.getBean(CodeStepDeployment).longestRun()
        }

        then:
        refused == null
        bound == expected

        where:
        written || expected
        "90s"   || Duration.ofSeconds(90)
        "PT2M"  || Duration.ofMinutes(2)
        "500ms" || Duration.ofMillis(500)
    }

    /** Loaded as the application loads it, so the bound it ships with is the one asserted. */
    def "binds a minute from the application's own settings where the deployment says nothing"() {
        when:
        Throwable refused = null
        Duration bound = null
        contextRunner.withInitializer(new ConfigDataApplicationContextInitializer()).run { context ->
            refused = context.startupFailure
            bound = context.getBean(CodeStepDeployment).longestRun()
        }

        then:
        refused == null
        bound == Duration.ofMinutes(1)
    }

    /** Lazy or not, the start is where a bound it cannot hold is found, never the first shutdown. */
    def "refuses to start where the longest run is missing or could not be waited out, naming the key"() {
        when:
        Throwable refused = null
        def runner = lazily
                ? contextRunner.withInitializer { it.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()) }
                : contextRunner
        runner.withPropertyValues(properties as String[]).run { context -> refused = context.startupFailure }

        then:
        refused != null
        messagesOf(refused).contains(message)

        where:
        properties                                     | lazily || message
        []                                             | false  || "lilradish.code-steps.longest-run must be set"
        []                                             | true   || "lilradish.code-steps.longest-run must be set"
        ["lilradish.code-steps.longest-run=0s"]        | false  || "lilradish.code-steps.longest-run must be positive: PT0S"
        ["lilradish.code-steps.longest-run=-1s"]       | true   || "lilradish.code-steps.longest-run must be positive: PT-1S"
        ["lilradish.code-steps.longest-run=25h"]       | false  || "lilradish.code-steps.longest-run must be at most PT24H: PT25H"
    }

    def "refuses to start on a setting under its key that it does not know, naming it"() {
        when:
        Throwable refused = null
        contextRunner.withPropertyValues("lilradish.code-steps.longest-run=1m", "lilradish.code-steps.longest-wait=1m")
                .run { context -> refused = context.startupFailure }

        then:
        refused != null
        messagesOf(refused).any { it.contains("lilradish.code-steps.longest-wait") }
    }

    private static List<String> messagesOf(Throwable failure) {
        List<String> messages = []
        for (Throwable cause = failure; cause != null; cause = cause.cause) {
            messages << String.valueOf(cause.message)
        }
        messages
    }
}
