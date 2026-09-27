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
import org.springframework.http.HttpHeaders
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import spock.lang.Specification

/**
 * An Accept header nothing can parse, sent by a caller nobody identified, is answered as any other
 * request; the framework's warning about it, one per request, does not reach the log.
 */
@WebMvcTest(ActProbeController)
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
@Import(ActProbeController)
@GroupRolesStoodIn
class UnreadableAcceptLoggingIntegrationSpec extends Specification {

    /** Package-private in the framework, so named rather than referenced. */
    static final String WELCOME_PAGE = "org.springframework.boot.webmvc.autoconfigure.WelcomePageHandlerMapping"

    static final String UNREADABLE = "no media type at all"

    /** Answered by no handler method, so the framework's welcome page is asked about it first. */
    static final String CLIENT_ROUTE = "/system/people"

    /** The element the bundle mounts into, which is what makes an answer the application document. */
    static final String THE_DOCUMENT = 'id="root"'

    @Autowired
    private MockMvc mockMvc

    @MockitoBean
    private UserIdentification identification

    @MockitoBean
    private EstateRoleGrants grants

    private final Logger root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger

    private final Logger welcoming = LoggerFactory.getLogger(WELCOME_PAGE) as Logger

    private final ListAppender<ILoggingEvent> logged = new ListAppender<>()

    private Level configured

    def setup() {
        configured = welcoming.level
        logged.start()
        root.addAppender(logged)
    }

    def cleanup() {
        root.detachAppender(logged)
        welcoming.level = configured
    }

    private MvcResult asking(String address) {
        mockMvc.perform(get(address).header(HttpHeaders.ACCEPT, UNREADABLE)).andReturn()
    }

    private List<ILoggingEvent> heardFromTheWelcomePage() {
        logged.list.findAll { it.loggerName == WELCOME_PAGE }
    }

    def "an Accept header nothing can parse is answered as any other request, and leaves nothing in the log"() {
        when:
        def answered = asking(CLIENT_ROUTE)

        then:
        answered.response.status == 200
        answered.response.contentAsString.contains(THE_DOCUMENT)

        and:
        heardFromTheWelcomePage() == []
    }

    /** The feature above asserts an absence; at warning level the same request is heard. */
    def "the request the feature above sends reaches that logger, once"() {
        given:
        welcoming.level = Level.WARN

        when:
        asking(CLIENT_ROUTE)

        then:
        heardFromTheWelcomePage()*.level == [Level.WARN]
    }
}
