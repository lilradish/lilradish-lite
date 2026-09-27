package org.lilradish.lite.web

import static java.nio.charset.StandardCharsets.UTF_8
import static org.mockito.ArgumentMatchers.any
import static org.mockito.BDDMockito.given
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get

import jakarta.servlet.http.HttpServletRequest
import java.util.regex.Pattern
import org.libprunus.spring.error.ApiErrorAutoConfiguration
import org.lilradish.lite.app.estate.EstateRoleGrants
import org.lilradish.lite.app.identification.UserIdentification
import org.lilradish.lite.app.standing.Holdings
import org.lilradish.lite.app.standing.StandingController
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.testutil.EmittedNames
import org.lilradish.lite.testutil.GroupRolesStoodIn
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping
import org.springframework.web.servlet.resource.CachingResourceResolver
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler
import spock.lang.Specification

/**
 * What an address means to this application, asked of every kind of address there is: one the served
 * bundle has a file for, one only the loaded page knows how to route, one the application answers
 * itself, and one nothing answers at all.
 *
 * <p>The third is why this is worth assembling. The fallback claims the pattern the framework's own
 * static handling claims, so the way a served address and an answered one are told apart is the
 * whole of it — and either one swallowing the other is invisible to anything short of a dispatcher.
 *
 * <p>Two of these read the archive rather than a request. What the bundle is emitted into is named
 * once by the build and once by the handler that claims it, and neither can see the other; a request
 * for a path taken out of the emitted document is what holds those two level, because the answer
 * differs — held without asking again, or re-read every time — exactly when they have drifted apart.
 * The archive is read under the handler's own word for that directory rather than under a second
 * spelling of it, so a build emitting somewhere else leaves nothing there to judge.
 *
 * <p>The rule that names carry a hash is not that they usually do. A name kept for a year while the
 * bytes behind it can still change is one no later deploy can reach: the server cannot recall it,
 * and only a reader forcing a reload lets go of it. What a hash looks like is {@link EmittedNames}'s
 * to say, being held level with the build's own configuration.
 */
@WebMvcTest(StandingController)
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
@GroupRolesStoodIn
class ClientRouteIntegrationSpec extends Specification {

    /** The element the bundle mounts into, which is what makes the answer the application document. */
    static final String THE_DOCUMENT = 'id="root"'

    static final Pattern A_MODULE_THE_DOCUMENT_LOADS = ~/<script[^>]+src="([^"]+)"/

    @Autowired
    MockMvc mockMvc

    @Autowired
    @Qualifier("resourceHandlerMapping")
    HandlerMapping servedAddresses

    @MockitoBean
    UserIdentification identification

    @MockitoBean
    EstateRoleGrants grants

    @MockitoBean
    Holdings holdings

    /**
     * Asked as a reader following a link would ask, even of the addresses below that no reader
     * types: what decides each answer is the address, and a header saying what the asker would
     * accept must not be what any of them turns on.
     */
    private MvcResult asking(String address) {
        mockMvc.perform(get(address).accept(MediaType.TEXT_HTML)).andReturn()
    }

    /** The request line as a caller writes it, rather than as a builder would re-encode it. */
    private MvcResult askingRaw(String requestUri) {
        mockMvc.perform(get("/")
                .accept(MediaType.TEXT_HTML)
                .with({ request -> request.setRequestURI(requestUri); request } as RequestPostProcessor))
                .andReturn()
    }

    /** Fails rather than passing over an empty document: nothing to point at is nothing held level. */
    private String theModuleTheDocumentLoads() {
        def emitted = new ClassPathResource("static/index.html").getContentAsString(UTF_8)
        def found = A_MODULE_THE_DOCUMENT_LOADS.matcher(emitted)
        assert found.find(), "the emitted document loads no module, so there is nothing to hold level"
        found.group(1)
    }

    def "serves the document itself where the bundle has one"() {
        when:
        def answered = asking("/index.html")

        then:
        answered.response.status == 200
        answered.response.contentAsString.contains(THE_DOCUMENT)
    }

    /** The deployment's front door, answered alike whatever the asker says it accepts. */
    def "answers the root with the document itself, to be re-read before it is trusted"() {
        when:
        def answered = mockMvc.perform(get("/").accept(accepted)).andReturn()

        then:
        answered.response.status == 200
        answered.response.contentType.startsWith(MediaType.TEXT_HTML_VALUE)
        answered.response.contentAsString.contains(THE_DOCUMENT)
        answered.response.getHeader("Cache-Control") == "no-cache"

        and: "tagged by what it holds rather than by when the archive was written"
        answered.response.getHeader("ETag") ==~ /"[0-9a-f]{32}"/
        answered.response.getHeader("Last-Modified") == null

        where:
        accepted << [MediaType.TEXT_HTML, MediaType.APPLICATION_JSON]
    }

    def "answers the root as unchanged to a reader holding the document it would send"() {
        given:
        def held = mockMvc.perform(get("/")).andReturn().response.getHeader("ETag")

        when:
        def answered = mockMvc.perform(get("/").header("If-None-Match", held)).andReturn()

        then:
        answered.response.status == 304
        answered.response.contentAsByteArray.length == 0

        and: "revalidated again next time, never kept as though its name could not change"
        !answered.response.getHeader("Cache-Control").contains("immutable")
    }

    /** The same bytes wherever the document answers, so the same tag, and never a timestamp of the archive. */
    def "answers a client route with the document the root answers, under the same tag"() {
        given:
        def rootTag = mockMvc.perform(get("/")).andReturn().response.getHeader("ETag")

        when:
        def answered = asking(address)

        then:
        answered.response.status == 200
        answered.response.contentAsString.contains(THE_DOCUMENT)
        answered.response.contentType == "text/html;charset=UTF-8"
        answered.response.getHeader("ETag") == rootTag

        and:
        answered.response.getHeader("Last-Modified") == null

        where:
        address << ["/system/people", "/groups", "/anything"]
    }

    /** Only the document is tagged; a file of the bundle's own at the root is sent whole each time. */
    def "sends a file at the root other than the document with no tag and no timestamp"() {
        when:
        def answered = asking("/index.html")

        then:
        answered.response.status == 200
        answered.response.getHeader("Cache-Control") == "no-cache"

        and:
        answered.response.getHeader("ETag") == null
        answered.response.getHeader("Last-Modified") == null
    }

    def "answers a client route as unchanged to a reader holding the document"() {
        given:
        def held = mockMvc.perform(get("/")).andReturn().response.getHeader("ETag")

        when:
        def answered = mockMvc.perform(get("/groups").header("If-None-Match", held)).andReturn()

        then:
        answered.response.status == 304
        answered.response.contentAsByteArray.length == 0

        and:
        answered.response.getHeader("Last-Modified") == null
    }

    def "sends a reader holding some other document the one it has now"() {
        when:
        def answered = mockMvc.perform(get("/").header("If-None-Match", '"not-this-document"')).andReturn()

        then:
        answered.response.status == 200
        answered.response.contentAsString.contains(THE_DOCUMENT)
    }

    /**
     * The address a reader is left on after following a link, and the one they come back to from a
     * bookmark or a refresh. Nothing in the archive is filed under it, so without the fallback the
     * screen they were on a moment ago answers as a screen this application does not have.
     */
    def "answers an address only the loaded page routes with the document that routes it"() {
        when:
        def answered = asking("/system/people")

        then:
        answered.response.status == 200
        answered.response.contentType.startsWith(MediaType.TEXT_HTML_VALUE)
        answered.response.contentAsString.contains(THE_DOCUMENT)
    }

    /**
     * The direction the fallback must not reach. An address under the application's own prefix that
     * names no operation has to stay missing: answered with the document it would be a 200 carrying
     * markup, which a caller reads as an operation that ran.
     *
     * <p>Asked as somebody the door let through, that being the only way this direction is reached
     * at all — a caller nothing identified is turned away one layer out, and what they are told
     * is {@code CallerAdmissionIntegrationSpec}'s to hold.
     *
     * <p>Written the ways a caller can write it, and including the bare prefix, which names no
     * operation and is nobody's client route either. What this handler is handed is a path the
     * framework has already decoded and taken the doubled separators out of, so the judgement it
     * makes has to be made on that path rather than on a word compared against the front of it.
     */
    def "leaves an address under the application's own prefix missing rather than answering with the document"() {
        given:
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(new UserId("000001")))

        when:
        def answered = askingRaw(written)

        then:
        answered.response.status == 404
        !answered.response.contentAsString.contains(THE_DOCUMENT)

        where:
        written << ["/api/there-is-no-such-thing",
                    "/api",
                    "/%61pi/there-is-no-such-thing",
                    "//api/standing"]
    }

    /** And the endpoint that is there still answers itself rather than being served as a file. */
    def "leaves the application's own endpoint answering itself"() {
        given:
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(new UserId("000001")))

        when:
        def answered = mockMvc.perform(get("/api/standing")).andReturn()

        then:
        answered.response.status == 200
        answered.response.contentType.startsWith(MediaType.APPLICATION_JSON_VALUE)
        answered.response.contentAsString.contains('"acts"')

        and:
        !answered.response.contentAsString.contains(THE_DOCUMENT)
    }

    /**
     * The other direction the fallback must not reach, and the one a deploy produces on its own: a
     * page loaded before it asks afterwards for a module named by the build before. Answered with
     * the document, a browser is handed markup where it asked for a module and reports neither.
     */
    def "leaves a name from a build that is gone missing rather than answering with the document"() {
        when:
        def answered = asking("/assets/index-THERE-IS-NO-SUCH-BUILD.js")

        then:
        answered.response.status == 404
        !answered.response.contentAsString.contains(THE_DOCUMENT)
    }

    /**
     * Read off the archive rather than compared to a word written here: the two spellings of where
     * the bundle is emitted drift apart silently, every file still resolving.
     */
    def "what the emitted document points at is what is kept without asking again"() {
        given:
        def referenced = theModuleTheDocumentLoads()

        when:
        def answered = asking(referenced)

        then:
        answered.response.status == 200
        answered.response.getHeader("Cache-Control").contains("immutable")
    }

    /**
     * The one name that has to be re-read, because it is the one that says which of the others are
     * current. Stored all the same — unchanged, it answers 304 — which is the whole of the
     * difference between not caching it and not trusting it.
     */
    def "re-reads the document mapping those names to a build before trusting it"() {
        expect:
        asking(address).response.getHeader("Cache-Control").contains("no-cache")

        where:
        address << ["/index.html", "/system/people"]
    }

    /**
     * The other thing the two patterns must not share. The chain's own map has no bound and no
     * eviction, and the catch-all resolves every address there is, so kept answers are a heap
     * anybody who has not been identified can grow without end by asking for names nothing has.
     */
    def "keeps nothing for the pattern answering every address, while the names a build bounds are kept"() {
        given:
        def resolversUnder = { String pattern ->
            def served = (servedAddresses as SimpleUrlHandlerMapping).urlMap[pattern] as ResourceHttpRequestHandler
            served.resourceResolvers*.getClass()
        }

        expect:
        !resolversUnder("/**").contains(CachingResourceResolver)

        and: "while what the build emitted, being a set it bounds, is kept without asking again"
        resolversUnder("/" + ClientRouteFallback.HASHED_FILES + "**").contains(CachingResourceResolver)
    }

    /** The framework's own static handling is switched off, so no pattern of its stands beside these two. */
    def "serves files under this application's two patterns and none of the framework's"() {
        expect:
        (servedAddresses as SimpleUrlHandlerMapping).urlMap.keySet() == ["/" + ClientRouteFallback.HASHED_FILES + "**", "/**"] as Set
    }

    /** What the freshness above rests on, and the only assertion here that is not about a request. */
    def "every name the bundle emits carries a hash of what is in it"() {
        given: "getFile() spelt out, because the property of that name resolves to isFile()"
        def emitted = new ClassPathResource("static/" + ClientRouteFallback.HASHED_FILES).getFile().listFiles()

        expect: "something to judge, so an emptied directory cannot satisfy the rule below"
        emitted.length > 0

        and:
        emitted.findAll { !EmittedNames.HASHED.matcher(it.name).matches() }*.name == []
    }
}
