package org.lilradish.lite.web

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.libprunus.spring.error.ApiErrorAutoConfiguration
import org.lilradish.lite.app.estate.EstateRoleGrants
import org.lilradish.lite.app.identification.UserIdentification
import org.lilradish.lite.testutil.GroupRolesStoodIn
import org.lilradish.lite.web.fixture.ActProbeController
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.web.servlet.resource.ResourceHandlerUtils
import spock.lang.Specification

/**
 * A path the framework refuses stays refused; its warning, which quotes the path escaped for controls
 * alone, does not reach the log.
 */
@WebMvcTest(ActProbeController)
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
@Import(ActProbeController)
@GroupRolesStoodIn
class RefusedResourcePathLoggingIntegrationSpec extends Specification {

    static final String LINE_SEPARATOR = Character.toString(0x2028)

    static final String RIGHT_TO_LEFT_OVERRIDE = Character.toString(0x202E)

    /** The element the bundle mounts into, which is what makes an answer the application document. */
    static final String THE_DOCUMENT = 'id="root"'

    @Autowired
    private MockMvc mockMvc

    @MockitoBean
    private UserIdentification identification

    @MockitoBean
    private EstateRoleGrants grants

    private final Logger root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger

    private final Logger refusing = LoggerFactory.getLogger(ResourceHandlerUtils) as Logger

    private final ListAppender<ILoggingEvent> logged = new ListAppender<>()

    private Level configured

    def setup() {
        configured = refusing.level
        logged.start()
        root.addAppender(logged)
    }

    def cleanup() {
        root.detachAppender(logged)
        refusing.level = configured
    }

    private MvcResult asking(String requestUri) {
        mockMvc.perform(get("/").with({ request -> request.setRequestURI(requestUri); request } as RequestPostProcessor))
                .andReturn()
    }

    private List<ILoggingEvent> heardFromTheRefusal() {
        logged.list.findAll { it.loggerName == ResourceHandlerUtils.name }
    }

    def "a path the framework refuses is still refused, and leaves nothing of what the caller wrote in the log"() {
        when:
        def answered = asking(written)

        then:
        answered.response.status == 404
        !answered.response.contentAsString.contains(THE_DOCUMENT)

        and: "no line from the logger noting the refusal, and none anywhere carrying what the caller wrote"
        heardFromTheRefusal() == []
        logged.list.findAll { it.formattedMessage.contains(smuggled) } == []

        where:
        written                        || smuggled
        "/a/%2e%2e/x%E2%80%A8FAKE"     || LINE_SEPARATOR
        "/x%E2%80%A8/%6Deta-inf/a"     || LINE_SEPARATOR
        "/x%E2%80%A8/meta-inf/a"       || LINE_SEPARATOR
        "/url%3A/x%E2%80%AEFAKE"       || RIGHT_TO_LEFT_OVERRIDE
    }

    /**
     * The feature above asserts an absence. Each of these paths does reach the logger, and at warning
     * level its line carries what the caller wrote, decoded.
     */
    def "each path the feature above asks reaches that logger, whose warning carries what the caller wrote"() {
        given:
        refusing.level = Level.WARN

        when:
        def answered = asking(written)

        then:
        answered.response.status == 404
        heardFromTheRefusal()*.level == [Level.WARN]
        heardFromTheRefusal().first().formattedMessage.contains(smuggled)

        where:
        written                        || smuggled
        "/a/%2e%2e/x%E2%80%A8FAKE"     || LINE_SEPARATOR
        "/x%E2%80%A8/%6Deta-inf/a"     || LINE_SEPARATOR
        "/url%3A/x%E2%80%AEFAKE"       || RIGHT_TO_LEFT_OVERRIDE
    }

    /** Silenced by its level, not by an appender that hears nothing from it. */
    def "that logger is heard at error level and at no level below it"() {
        when:
        refusing.error("heard")

        then:
        heardFromTheRefusal()*.formattedMessage == ["heard"]

        and:
        refusing.effectiveLevel == Level.ERROR
    }
}
