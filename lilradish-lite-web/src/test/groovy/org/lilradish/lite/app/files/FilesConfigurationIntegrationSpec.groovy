package org.lilradish.lite.app.files

import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import spock.lang.Specification

class FilesConfigurationIntegrationSpec extends Specification {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(FilesConfiguration)

    def "binds all three file limits from the application's deployment settings"() {
        when:
        Throwable failure = null
        Long size = null
        Integer count = null
        Long total = null
        runner.withInitializer(new ConfigDataApplicationContextInitializer()).run { context ->
            failure = context.startupFailure
            if (failure == null) {
                size = context.getBean(FileDeployment).maximumSize().toBytes()
                count = context.getBean(FileDeployment).maximumPerField()
                total = context.getBean(FileDeployment).maximumTotalSize().toBytes()
            }
        }

        then:
        failure == null
        size == 104_857_600L
        count == 100
        total == 1_073_741_824L
    }

    def "refuses missing, nonpositive or inconsistent file limits during eager or lazy startup"() {
        when:
        Throwable failure = null
        def configured = lazy
                ? runner.withInitializer { it.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()) }
                : runner
        configured.withPropertyValues(properties as String[]).run { context -> failure = context.startupFailure }

        then:
        failure != null
        messages(failure).any { it.contains(key) }

        where:
        properties                                                                                                                         | lazy  || key
        []                                                                                                                                 | false || "lilradish.files.maximum-size"
        ["lilradish.files.maximum-size=1MB"]                                                                                              | true  || "lilradish.files.maximum-per-field"
        ["lilradish.files.maximum-size=1MB", "lilradish.files.maximum-per-field=1"]                                                       | false || "lilradish.files.maximum-total-size"
        ["lilradish.files.maximum-size=0B", "lilradish.files.maximum-per-field=1", "lilradish.files.maximum-total-size=1GB"]              | false || "lilradish.files.maximum-size"
        ["lilradish.files.maximum-size=1MB", "lilradish.files.maximum-per-field=0", "lilradish.files.maximum-total-size=1GB"]             | true  || "lilradish.files.maximum-per-field"
        ["lilradish.files.maximum-size=1MB", "lilradish.files.maximum-per-field=1", "lilradish.files.maximum-total-size=0B"]              | true  || "lilradish.files.maximum-total-size"
        ["lilradish.files.maximum-size=100MB", "lilradish.files.maximum-per-field=1", "lilradish.files.maximum-total-size=99MB"]         | false || "lilradish.files.maximum-total-size"
        ["lilradish.files.maximum-size=9007199254740992B", "lilradish.files.maximum-per-field=1", "lilradish.files.maximum-total-size=9007199254740992B"] | false || "lilradish.files.maximum-size"
        ["lilradish.files.maximum-size=1B", "lilradish.files.maximum-per-field=1", "lilradish.files.maximum-total-size=9007199254740992B"] | true || "lilradish.files.maximum-total-size"
    }

    def "accepts a total file size equal to the single-file size at eager and lazy startup"() {
        when:
        Throwable failure = null
        def configured = lazy
                ? runner.withInitializer { it.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()) }
                : runner
        configured.withPropertyValues("lilradish.files.maximum-size=100MB",
                "lilradish.files.maximum-per-field=1", "lilradish.files.maximum-total-size=100MB")
                .run { context -> failure = context.startupFailure }

        then:
        failure == null

        where:
        lazy << [false, true]
    }

    def "binds the largest byte limit without rounding it at startup"() {
        when:
        Throwable failure = null
        Long size = null
        Long total = null
        runner.withPropertyValues("lilradish.files.maximum-size=9007199254740991B",
                "lilradish.files.maximum-per-field=1", "lilradish.files.maximum-total-size=9007199254740991B")
                .run { context ->
                    failure = context.startupFailure
                    if (failure == null) {
                        size = context.getBean(FileDeployment).maximumSize().toBytes()
                        total = context.getBean(FileDeployment).maximumTotalSize().toBytes()
                    }
                }

        then:
        failure == null
        size == 9_007_199_254_740_991L
        total == size
    }

    def "refuses a setting under the file key that it does not know"() {
        when:
        Throwable failure = null
        runner.withPropertyValues("lilradish.files.maximum-size=1MB", "lilradish.files.maximum-per-field=1",
                "lilradish.files.maximum-total-size=1GB", "lilradish.files.unknown=1").run { context -> failure = context.startupFailure }

        then:
        failure != null
        messages(failure).any { it.contains("lilradish.files.unknown") }
    }

    private static List<String> messages(Throwable failure) {
        List<String> all = []
        for (Throwable cause = failure; cause != null; cause = cause.cause) {
            all << String.valueOf(cause.message)
        }
        all
    }
}
