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
 * What a caller is told at an address nothing answers, asked on both sides of this application's
 * prefix. Assembled over a real dispatcher rather than against a resolver stood up by hand, because
 * the whole of what is under test is which handler the dispatcher selected, what path the resolution
 * handed that handler, and what the error outlet made of the refusal raised inside it — none of
 * which exists below one.
 *
 * <p>Read as members of the parsed document rather than as substrings of it. A body that stopped
 * carrying a sentence and a body carrying one that says nothing differ by a member, and a test
 * looking for words would call both of them clean.
 *
 * <p>Every address here is written onto the request line rather than handed to a builder. A builder
 * parses what it is given as a URI, where a leading doubled separator is an authority and not a
 * path — so {@code //api/x} arrives as {@code /x}, and the one spelling this file exists to pin
 * would be tested as an address that was never sent.
 *
 * <p>Held in both directions at the prefix. Outside it an unknown address is a reader's missing page
 * rather than a caller's missing operation, and the two answers it already has out there are correct
 * — so a refusal reaching either of them is the failure this guards against, not a stricter version
 * of the same fix.
 */
@WebMvcTest(StandingController)
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
@GroupRolesStoodIn
class UnknownAddressIntegrationSpec extends Specification {

    static final UserId STEWARD = new UserId("000001")

    static final String NAMES_NO_OPERATION = "/api/there-is-no-such"

    /**
     * The same address with the separator doubled. Nothing before the resolution can recognise it —
     * the request line still carries both separators and every judgement made off one reads false —
     * while the path a resolver is handed has had them collapsed out, which is why the refusal has
     * to be raised there and why this spelling is the one that proves it was.
     */
    static final String DOUBLED_SEPARATOR = "//api/there-is-no-such"

    /** The one word of the address a leak would have to spell, whichever member it came out under. */
    static final String SPELT_ONLY_IN_THE_ADDRESS = "there-is-no-such"

    /** The element the bundle mounts into, which is what makes an answer the application document. */
    static final String THE_DOCUMENT = 'id="root"'

    @Autowired
    private MockMvc mockMvc

    @MockitoBean
    private UserIdentification identification

    @MockitoBean
    private EstateRoleGrants grants

    @MockitoBean
    private Holdings holdings

    private MvcResult asking(String requestUri) {
        mockMvc.perform(get("/").with({ request -> request.setRequestURI(requestUri); request } as RequestPostProcessor))
                .andReturn()
    }

    private static Map<String, Object> problemIn(MvcResult answered) {
        JsonMapper.builder().build().readValue(answered.response.contentAsString, Map)
    }

    private static List<String> membersNamingTheAddress(MvcResult answered) {
        problemIn(answered).findAll { it.value.toString().contains(SPELT_ONLY_IN_THE_ADDRESS) }*.key.toSorted()
    }

    private void arriving(UserId user) {
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.ofNullable(user))
    }

    /**
     * The refusal itself. Before it existed the reply came from the static handling that claims every
     * address nothing else claims, and said so: a sentence naming the framework's own machinery,
     * which is a fact about how this deployment is assembled and nobody's to be handed.
     */
    def "refuses an address under the prefix that names no operation without saying what answered it"() {
        given:
        arriving(STEWARD)

        when:
        def answered = asking(written)

        then:
        answered.response.status == 404
        answered.response.contentType.startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
        problemIn(answered)["code"] == "NOT_FOUND"

        and: "carrying no sentence at all, rather than one worded to say nothing"
        problemIn(answered).keySet() == ["status", "title", "code", "instance"] as Set
        !answered.response.contentAsString.contains("static resource")

        and: "asked at the address as written, so the doubled spelling is not silently the plain one"
        problemIn(answered)["instance"] == written

        and: "and no page in its place, a 200 carrying markup reading as an operation that ran"
        !answered.response.contentAsString.contains(THE_DOCUMENT)

        where:
        written << [NAMES_NO_OPERATION, DOUBLED_SEPARATOR]
    }

    /** A missing address is the answer a cache would most readily keep on a guess. */
    def "refuses an address under the prefix that names no operation as nothing any cache may keep"() {
        given:
        arriving(STEWARD)

        when:
        def answered = asking(NAMES_NO_OPERATION)

        then:
        answered.response.status == 404
        answered.response.getHeader("Cache-Control") == "no-store"
    }

    /**
     * The address is the caller's own to begin with, but which member it comes back under decides
     * whether a guessed address can be told from a real one. Held level with the door's refusal at
     * the very same address: what this adds to what being refused there already says has to be
     * nothing.
     */
    def "says no more of the address than the door's own refusal at that address already says"() {
        given:
        arriving(STEWARD)

        when:
        def refused = asking(NAMES_NO_OPERATION)

        then:
        membersNamingTheAddress(refused) == ["instance"]

        when:
        arriving(null)
        def turnedAway = asking(NAMES_NO_OPERATION)

        then:
        turnedAway.response.status == 401
        membersNamingTheAddress(turnedAway) == ["instance"]
    }

    /**
     * The order the two refusals stand in. Whether anything answers an address is decided inside the
     * resolution and being identified is decided in front of the dispatcher, so a caller nothing
     * identified must never be told which of the addresses they guessed at exist.
     */
    def "refuses a caller nothing identified before anything decides whether an operation is there"() {
        given:
        arriving(null)

        when:
        def answered = asking(NAMES_NO_OPERATION)

        then:
        answered.response.status == 401
        problemIn(answered)["code"] == "NOT_SIGNED_IN"

        and: "with nothing said about what is and is not there, and the store never reached either"
        !answered.response.contentAsString.contains("static resource")
        Mockito.verifyNoInteractions(grants, holdings)
    }

    /** And an address under the prefix that does name an operation is still answered by it. */
    def "leaves an address under the prefix that names an operation answering for itself"() {
        given:
        arriving(STEWARD)

        when:
        def answered = asking("/api/standing")

        then:
        answered.response.status == 200
        answered.response.contentAsString.contains('"acts"')
        Mockito.mockingDetails(holdings).invocations.collect { it.getArgument(0) } == [STEWARD]
    }

    /**
     * The first direction this must not reach: an address only the loaded page routes is a screen the
     * reader was just on, and refused rather than handed the document it reads as a screen this
     * application does not have.
     */
    def "leaves an address only the loaded page routes answered with the document that routes it"() {
        when:
        def answered = asking("/system/people")

        then:
        answered.response.status == 200
        answered.response.contentAsString.contains(THE_DOCUMENT)
    }

    /**
     * A missing file is missing wherever it was asked for, including where the framework gives up on a
     * path before any resolver sees it, and the answer says so in the words an ordinary one does.
     */
    def "answers a missing file as missing and nothing more, however the path was written"() {
        given:
        arriving(STEWARD)

        when:
        def answered = asking(written)
        def ordinary = asking(NAMES_NO_OPERATION)

        then:
        answered.response.status == 404
        problemIn(answered)["code"] == "NOT_FOUND"
        problemIn(answered).keySet() == problemIn(ordinary).keySet()

        and: "no sentence naming the machinery or what it looked for"
        !problemIn(answered).containsKey("detail")
        !answered.response.contentAsString.contains("static resource")

        where:
        written << ["/assets/index-THERE-IS-NO-SUCH-BUILD.js", "/assets/nope.js", "/api/META-INF", "/api/%2e%2e/x"]
    }
}
