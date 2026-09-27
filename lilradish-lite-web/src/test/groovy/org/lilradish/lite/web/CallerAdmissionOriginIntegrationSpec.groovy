package org.lilradish.lite.web

import static org.mockito.ArgumentMatchers.any
import static org.mockito.BDDMockito.given
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import jakarta.servlet.http.HttpServletRequest
import org.libprunus.spring.error.ApiErrorAutoConfiguration
import org.lilradish.lite.app.estate.EstateRoleGrants
import org.lilradish.lite.app.identification.UserIdentification
import org.lilradish.lite.app.standing.Holdings
import org.lilradish.lite.app.standing.StandingController
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.testutil.GroupRolesStoodIn
import org.mockito.Mockito
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.RequestPostProcessor
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper

/**
 * Whether a request changing something came from this application's own pages, asked at the door of
 * every address under the prefix, over a real dispatcher.
 *
 * <p>The address asked is one that takes none of these methods, so a request the door lets through
 * is answered by the dispatcher saying so, and one it refuses is answered by the door: the two can be
 * told apart without any handler changing anything. The request is served as {@code http://localhost}
 * on the default port, which is the origin a request has to name to be this one's.
 */
@WebMvcTest(StandingController)
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
@GroupRolesStoodIn
class CallerAdmissionOriginIntegrationSpec extends Specification {

    static final UserId READER = new UserId("000001")

    static final String GUARDED = "/api/standing"

    static final List<String> NOT_SAFE = ["POST", "PUT", "DELETE", "PATCH", "get", "PROPFIND"]

    @Autowired
    private MockMvc mockMvc

    @MockitoBean
    private UserIdentification identification

    @MockitoBean
    private EstateRoleGrants grants

    @MockitoBean
    private Holdings holdings

    private final Logger door = LoggerFactory.getLogger(CallerAdmission) as Logger

    private final ListAppender<ILoggingEvent> logged = new ListAppender<>()

    def setup() {
        logged.start()
        door.addAppender(logged)
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(READER))
    }

    def cleanup() {
        door.detachAppender(logged)
    }

    private MvcResult sending(String method, String address, Map<String, String> headers) {
        MockHttpServletRequestBuilder sent = request(HttpMethod.valueOf(method), address)
        headers.each { name, value -> sent = sent.header(name, value) }
        mockMvc.perform(sent).andReturn()
    }

    /** The request line as a caller writes it, rather than as a builder would re-encode it. */
    private MvcResult sendingRaw(String method, String requestUri) {
        mockMvc.perform(request(HttpMethod.valueOf(method), "/")
                .with({ sent -> sent.setRequestURI(requestUri); sent } as RequestPostProcessor))
                .andReturn()
    }

    private static String codeIn(MvcResult answered) {
        JsonMapper.builder().build().readTree(answered.response.contentAsString).get("code").asString()
    }

    /**
     * What the browser says of who made the request is believed wherever it says it, and has to be
     * this origin. Where it says nothing, which it does over a connection not potentially
     * trustworthy, the origin it names has to be this request's own — scheme, host and port.
     */
    def "lets a request changing something through where it shows it came from this origin"() {
        when:
        def answered = sending(method, GUARDED, headers)

        then: "the dispatcher answering it, the door having let it by"
        answered.response.status == 405

        and: "after asking who was calling, which a request refused at the door never reaches"
        identificationAskedOnce()

        and: "and without a word logged about where it came from"
        logged.list.isEmpty()

        where:
        [method, headers] << [NOT_SAFE, [
                ["Sec-Fetch-Site": "same-origin"],
                ["Sec-Fetch-Site": "same-origin", "Origin": "http://elsewhere.test"],
                ["Origin": "http://localhost"],
                ["Origin": "http://localhost:80"],
        ]].combinations()
    }

    /**
     * Refused before anybody is identified, in a sentence fixed whatever was sent, and logged without
     * repeating what arrived. A site of the same registrable domain is not this origin, and neither
     * is a navigation nobody made from any page.
     */
    def "refuses a request changing something that does not show it came from this origin, before asking who sent it"() {
        when:
        def answered = sending(method, GUARDED, headers)

        then:
        answered.response.status == 403
        answered.response.contentType.startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
        codeIn(answered) == "ORIGIN_UNVERIFIED"
        answered.response.getHeader("Cache-Control") == "no-store"

        and: "nothing that arrived is handed back, and nobody was asked about"
        !answered.response.contentAsString.contains("elsewhere")
        Mockito.verifyNoInteractions(identification)
        Mockito.verifyNoInteractions(grants, holdings)

        and: "logged for whoever operates this, without what arrived"
        logged.list.findAll { it.level == Level.WARN }.size() == 1
        logged.list.every { !it.formattedMessage.contains("elsewhere") }

        where:
        [method, headers] << [NOT_SAFE, [
                [:],
                ["Sec-Fetch-Site": "same-site"],
                ["Sec-Fetch-Site": "cross-site"],
                ["Sec-Fetch-Site": "none"],
                ["Sec-Fetch-Site": "Same-Origin"],
                ["Sec-Fetch-Site": "cross-site", "Origin": "http://localhost"],
                ["Origin": "http://elsewhere.test"],
                ["Origin": "https://localhost"],
                ["Origin": "http://localhost:8080"],
                ["Origin": "http://localhost.elsewhere.test"],
                ["Origin": "null"],
                ["Origin": ""],
                ["Origin": "http://["],
                ["Origin": "http://local host"],
                ["Origin": "http://localhost:99999999999"],
                ["Origin": "localhost"],
        ]].combinations()
    }

    /** A safe method changes nothing, so where it came from is not asked. */
    def "lets a safe request through whatever it says of where it came from"() {
        when:
        def answered = sending(method, GUARDED, ["Sec-Fetch-Site": "cross-site", "Origin": "http://elsewhere.test"])

        then:
        answered.response.status == 200
        identificationAskedOnce()

        where:
        method << ["GET", "HEAD", "OPTIONS"]
    }

    /**
     * Asked of whatever the door judges to be under the prefix, which is the dispatcher's judgement:
     * an address spelt so as to hide the prefix from a comparison of the request line is still asked.
     */
    def "asks every address under the prefix, however it is spelt, and none outside it"() {
        when:
        def answered = sendingRaw("POST", written)

        then:
        (answered.response.status == 403 && codeIn(answered) == "ORIGIN_UNVERIFIED") == guarded

        where:
        written                    || guarded
        "/api/standing"            || true
        "/%61pi/standing"          || true
        "/api;x=y/standing"        || true
        "/api/there-is-no-such-thing" || true
        "/api"                     || true
        "/index.html"              || false
        "/system/people"           || false
    }

    /** The refusal turns on where a request came from, so it cannot tell anybody who is signed in. */
    def "refuses alike whether or not anybody is signed in"() {
        given:
        given(identification.identify(any(HttpServletRequest))).willReturn(signedIn ? Optional.of(READER) : Optional.empty())

        when:
        def answered = sending("POST", GUARDED, ["Origin": "http://elsewhere.test"])

        then:
        answered.response.status == 403
        codeIn(answered) == "ORIGIN_UNVERIFIED"

        where:
        signedIn << [true, false]
    }

    /**
     * A form another site sends is not read by anything before this door, so however it is written —
     * even as bytes no form decodes — it is this door's refusal that answers it, and nobody is asked for.
     */
    def "refuses a form sent from another site as it refuses any change, before reading it or asking who sent it"() {
        when:
        def answered = mockMvc.perform(request(HttpMethod.valueOf(method), GUARDED)
                .header("Origin", "http://elsewhere.test")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content(form)).andReturn()

        then:
        answered.response.status == 403
        codeIn(answered) == "ORIGIN_UNVERIFIED"
        !answered.response.contentAsString.contains("%zz")

        and:
        Mockito.verifyNoInteractions(identification)

        where:
        [method, form] << [["DELETE", "PUT", "PATCH", "POST"], ["a=%zz", "&", "a=1"]].combinations()
    }

    /** Past the door, being identified is asked as it always is. */
    def "asks who is calling once a request has shown where it came from"() {
        given:
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.empty())

        when:
        def answered = sending("POST", GUARDED, ["Origin": "http://localhost"])

        then:
        answered.response.status == 401
        codeIn(answered) == "NOT_SIGNED_IN"
    }

    /** Void, so the verification is what fails rather than the null a mock's answer would assert as. */
    private void identificationAskedOnce() {
        Mockito.verify(identification).identify(any(HttpServletRequest))
        Mockito.verifyNoMoreInteractions(identification)
    }
}
