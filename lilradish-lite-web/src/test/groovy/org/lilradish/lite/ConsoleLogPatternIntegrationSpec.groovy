package org.lilradish.lite

import static java.nio.charset.StandardCharsets.UTF_8

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.core.OutputStreamAppender
import org.slf4j.Logger
import spock.lang.Requires
import spock.lang.Specification
import spock.util.environment.RestoreSystemProperties

/** A configured pattern arrives as the System property the logging system sets before this file is read. Read by a
 * logging context of its own, and held against Boot's own console configuration so a drifting copy is red here. */
@RestoreSystemProperties
@Requires({ !env.CONSOLE_LOG_PATTERN })
class ConsoleLogPatternIntegrationSpec extends Specification {

    static final String PATTERN_PROPERTY = "CONSOLE_LOG_PATTERN"

    static final String CONFIGURED = "%level %m%n"

    static final String BOOT_CONSOLE_ALONE = """<configuration>
        <include resource="org/springframework/boot/logging/logback/defaults.xml"/>
        <include resource="org/springframework/boot/logging/logback/console-appender.xml"/>
        <root level="INFO"><appender-ref ref="CONSOLE"/></root>
    </configuration>"""

    private final List<LoggerContext> contexts = []

    def cleanup() {
        contexts*.stop()
    }

    private String consolePattern(InputStream configuration) {
        def context = new LoggerContext()
        contexts << context
        def configurator = new JoranConfigurator()
        configurator.context = context
        configuration.withCloseable { configurator.doConfigure(it) }
        def console = context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("CONSOLE") as OutputStreamAppender
        (console.encoder as PatternLayoutEncoder).pattern
    }

    private String ours() {
        consolePattern(getClass().getResourceAsStream("/logback-spring.xml"))
    }

    private String bootsOwn() {
        consolePattern(new ByteArrayInputStream(BOOT_CONSOLE_ALONE.getBytes(UTF_8)))
    }

    def "writes with the pattern the configuration sets for the console, where one is set"() {
        given:
        System.setProperty(PATTERN_PROPERTY, CONFIGURED)

        expect:
        ours() == CONFIGURED
    }

    def "writes Boot's own console pattern with each line's key-value pairs after its message, where none is set"() {
        given:
        System.clearProperty(PATTERN_PROPERTY)

        when:
        def boots = bootsOwn()

        then:
        ours() == boots.replace(" %m%n", " %m %kvp%n")

        and: "Boot's pattern ends its message where the pairs go exactly once, so the two are not equal by accident"
        boots.count(" %m%n") == 1
        !boots.contains("%kvp")
    }
}
