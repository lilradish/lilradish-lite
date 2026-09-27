package org.lilradish.lite.web

import static org.mockito.ArgumentMatchers.any
import static org.mockito.BDDMockito.given
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get

import jakarta.servlet.http.HttpServletRequest
import org.libprunus.spring.error.ApiErrorAutoConfiguration
import org.lilradish.lite.app.estate.EstateRoleGrants
import org.lilradish.lite.app.identification.UserIdentification
import org.lilradish.lite.app.standing.Holdings
import org.lilradish.lite.app.standing.StandingController
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.testutil.GroupRolesStoodIn
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.RequestPostProcessor
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper

/**
 * The door, asked of every kind of address there is — one this application answers, one under its
 * prefix that it answers nothing at all for, and the document and files that are nobody's to be
 * identified for.
 *
 * <p>The second of those is why this runs over a real dispatcher rather than against a filter stood
 * up by hand. An address under the prefix that names no operation is matched by no handler, so what
 * would otherwise answer it is the static handling that claims every other address — and whether
 * this door stands in front of that or behind it is invisible to anything short of the real chain.
 */
@WebMvcTest(StandingController)
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
@GroupRolesStoodIn
class CallerAdmissionIntegrationSpec extends Specification {

    static final UserId STEWARD = new UserId("000001")

    static final String NAMES_NO_OPERATION = "/api/there-is-no-such-thing"

    @Autowired
    private MockMvc mockMvc

    @MockitoBean
    private UserIdentification identification

    @MockitoBean
    private EstateRoleGrants grants

    @MockitoBean
    private Holdings holdings

    private MvcResult asking(String address) {
        mockMvc.perform(get(address)).andReturn()
    }

    /** As a reader following a link asks, which is how the document and the files it loads arrive. */
    private MvcResult askingForThePage(String address) {
        mockMvc.perform(get(address).accept(MediaType.TEXT_HTML)).andReturn()
    }

    /** The request line as a caller writes it, rather than as a builder would re-encode it. */
    private MvcResult askingRaw(String requestUri) {
        mockMvc.perform(get("/").with({ request -> request.setRequestURI(requestUri); request } as RequestPostProcessor))
                .andReturn()
    }

    private static String memberIn(MvcResult answered, String member) {
        JsonMapper.builder().build().readTree(answered.response.contentAsString).get(member).asString()
    }

    private void arriving(UserId user) {
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.ofNullable(user))
    }

    /**
     * The whole reason this is a filter: without this door the reply here would be the static
     * handling's, which names the framework's own machinery and hands back the path that was sent.
     */
    def "refuses a caller nothing identified at an address under the prefix that names no operation"() {
        given:
        arriving(null)

        when:
        def answered = asking(NAMES_NO_OPERATION)

        then:
        answered.response.status == 401
        answered.response.contentType.startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
        memberIn(answered, "code") == "NOT_SIGNED_IN"

        and: "told in this application's own words rather than in the machinery's that would have answered"
        memberIn(answered, "detail") == "Nobody is signed in."
        !answered.response.contentAsString.contains("static resource")
    }

    /**
     * The whole of what the door judges, written the ways a caller can write it. The container hands
     * back the request line undecoded and with its path parameters still on it, while a handler is
     * selected from a path that has had both taken off — so every spelling below reaches the same
     * handler the plain one does, and a door reading the request line would guard none of them.
     *
     * <p>The bare prefix is here too: it names no operation, so nothing under it answers, and what
     * must not happen is the static handling reading it as an address of its own.
     */
    def "refuses a caller nothing identified however an address under the prefix was written"() {
        given:
        arriving(null)

        when:
        def answered = askingRaw(written)

        then:
        answered.response.status == 401
        memberIn(answered, "code") == "NOT_SIGNED_IN"

        and: "with the store never reached and no page handed back in place of the refusal"
        Mockito.verifyNoInteractions(grants, holdings)
        !answered.response.contentAsString.contains("<html")

        where:
        written << ["/api/standing",
                    "/%61pi/standing",
                    "/api;x=y/standing",
                    "/api/standing;x=y",
                    "/%61pi/there-is-no-such-thing",
                    "/api"]
    }

    /** And a handler that is there is refused before it runs, rather than refusing for itself. */
    def "refuses a caller nothing identified at a handler under the prefix, before the handler runs"() {
        given:
        arriving(null)

        when:
        def answered = asking("/api/standing")

        then:
        answered.response.status == 401
        memberIn(answered, "code") == "NOT_SIGNED_IN"

        and: "with nothing answered about what could be reached, and the store never reached either"
        !answered.response.contentAsString.contains("acts")
        Mockito.verifyNoInteractions(grants, holdings)
    }

    /**
     * The direction the door must not reach. The document and the files it loads are what a reader
     * is handed before anything has identified them, so asking who is calling out there is both a
     * refusal nobody could satisfy and work done on every asset of every page load.
     */
    def "lets the document, its files and the addresses only the loaded page routes through unasked"() {
        expect:
        askingForThePage(address).response.status == 200

        and: "without the question having been put at all, rather than put and answered leniently"
        Mockito.verifyNoInteractions(identification)

        where:
        address << ["/", "/index.html", "/system/people"]
    }

    /** What the handler is handed is what the door resolved, rather than a second resolution of its own. */
    def "hands the handler the caller it let through"() {
        given:
        arriving(STEWARD)

        when:
        def answered = asking("/api/standing")

        then:
        answered.response.status == 200
        answered.response.contentAsString.contains('"acts"')

        and: "the store asked about that user once, and about nobody else"
        Mockito.mockingDetails(holdings).invocations.collect { it.getArgument(0) } == [STEWARD]
        Mockito.verifyNoInteractions(grants)
    }
}
