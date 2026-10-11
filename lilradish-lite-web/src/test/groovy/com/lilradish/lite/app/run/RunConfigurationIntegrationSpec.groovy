package com.lilradish.lite.app.run

import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import spock.lang.Specification

/**
 * What the deployment says of runs, bound by the binder the application starts with. The runner closes the context
 * before returning, so what is read inside the consumer is carried out as a value and asserted on after.
 */
class RunConfigurationIntegrationSpec extends Specification {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(RunConfiguration)

    def "binds how deep runs may go as written, one included"() {
        when:
        Throwable refused = null
        Integer bound = null
        contextRunner.withPropertyValues("lilradish.runs.maximum-depth=${written}").run { context ->
            refused = context.startupFailure
            bound = context.getBean(RunDeployment).maximumDepth()
        }

        then:
        refused == null
        bound == expected

        where:
        written || expected
        "1"     || 1
        "2"     || 2
        "500"   || 500
    }

    /** Loaded as the application loads it, so the depth it ships with is the one asserted. */
    def "binds thirty-two from the application's own settings where the deployment says nothing"() {
        when:
        Throwable refused = null
        Integer bound = null
        contextRunner.withInitializer(new ConfigDataApplicationContextInitializer()).run { context ->
            refused = context.startupFailure
            bound = context.getBean(RunDeployment).maximumDepth()
        }

        then:
        refused == null
        bound == 32
    }

    /** Lazy or not, the start is where a depth it cannot hold is found, never the first run that reaches it. */
    def "refuses to start where how deep runs may go is missing or below one, naming the key"() {
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
        properties                            | lazily || message
        []                                    | false  || "lilradish.runs.maximum-depth must be set"
        []                                    | true   || "lilradish.runs.maximum-depth must be set"
        ["lilradish.runs.maximum-depth=0"]    | false  || "lilradish.runs.maximum-depth must be positive: 0"
        ["lilradish.runs.maximum-depth=-1"]   | true   || "lilradish.runs.maximum-depth must be positive: -1"
    }

    def "refuses to start on a setting under its key that it does not know, naming it"() {
        when:
        Throwable refused = null
        contextRunner.withPropertyValues("lilradish.runs.maximum-depth=3", "lilradish.runs.maximum-width=3")
                .run { context -> refused = context.startupFailure }

        then:
        refused != null
        messagesOf(refused).any { it.contains("lilradish.runs.maximum-width") }
    }

    private static List<String> messagesOf(Throwable failure) {
        List<String> messages = []
        for (Throwable cause = failure; cause != null; cause = cause.cause) {
            messages << String.valueOf(cause.message)
        }
        messages
    }
}
