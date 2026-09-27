package org.lilradish.lite.web

import static java.nio.charset.StandardCharsets.UTF_8
import static org.mockito.ArgumentMatchers.any
import static org.mockito.BDDMockito.given
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options

import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServletRequest
import java.util.concurrent.atomic.AtomicInteger
import org.libprunus.spring.error.ApiErrorAutoConfiguration
import org.lilradish.lite.app.estate.EstateRoleGrants
import org.lilradish.lite.app.identification.UserIdentification
import org.lilradish.lite.domain.identity.EstateAct
import org.lilradish.lite.domain.identity.EstateRole
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.testutil.GroupRolesStoodIn
import org.lilradish.lite.web.fixture.ActProbeController
import org.lilradish.lite.web.fixture.NoMethodProbes
import org.lilradish.lite.web.fixture.UndeclaredProbeController
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.web.servlet.resource.NoResourceFoundException
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper

/**
 * What a handler asks of the caller who reached it, decided from the handler's own declaration and
 * never inside the handler. Exercised over a real dispatcher, because what is being checked is that
 * the declaration on the selected method is read at all — an interceptor nothing registered refuses
 * nobody, and that is invisible to a spec that calls the interceptor itself.
 *
 * <p>The addresses are the probes' rather than the shipped ones: what is asked here is the gate, and
 * a probe reaches it with no feature's store behind the handler to stand in for. The probe that
 * declares nothing is registered only here, the rule that this application ships no such handler
 * being held in a slice that does not import it.
 */
@WebMvcTest(ActProbeController)
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
@Import([ActProbeController, UndeclaredProbeController, NoMethodProbes])
@GroupRolesStoodIn
class ActAdmissionIntegrationSpec extends Specification {

    static final UserId READER = new UserId("000001")

    @Autowired
    private MockMvc mockMvc

    @MockitoBean
    private UserIdentification identification

    @MockitoBean
    private EstateRoleGrants grants

    @Autowired
    private AtomicInteger noMethodProbeRuns

    def setup() {
        noMethodProbeRuns.set(0)
    }

    private MvcResult asking(String address) {
        mockMvc.perform(get(address)).andReturn()
    }

    private static String codeIn(MvcResult answered) {
        JsonMapper.builder().build().readTree(answered.response.contentAsString).get("code").asString()
    }

    private void holding(Set<EstateRole> roles) {
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(READER))
        given(grants.heldBy(READER)).willReturn(roles)
    }

    /**
     * Written over every role rather than one of them, so which roles reach the act is read off the
     * estate's own table: a role added there that bundles it must carry its holder through here too.
     */
    def "lets a caller through to a handler asking an act they hold"() {
        given:
        holding(held)

        when:
        def answered = asking(ActProbeController.ASKING_AN_ACT)

        then:
        answered.response.status == 200
        answered.response.contentAsString == ActProbeController.ANSWERED

        where:
        held << EstateRole.values().findAll { it.acts().contains(EstateAct.KEEP_POOL) }.collect { EnumSet.of(it) }
    }

    /**
     * Refused as not permitted rather than as missing: whoever asked was identified and the address
     * exists, so reporting it absent would be a lie about which of the two failed.
     */
    def "refuses a caller who does not hold the act a handler asks, and the handler never runs"() {
        given:
        holding(held)

        when:
        def answered = asking(ActProbeController.ASKING_AN_ACT)

        then:
        answered.response.status == 403
        answered.response.contentType.startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
        codeIn(answered) == "ACT_NOT_PERMITTED"

        and: "so nothing the handler would have put on the wire is there"
        !answered.response.contentAsString.contains(ActProbeController.ANSWERED)

        and: "and no act is named, the published spelling being the reader's contract rather than a fact about them"
        EstateAct.values().every { !answered.response.contentAsString.contains(it.published()) }

        where:
        held << [EnumSet.noneOf(EstateRole), EnumSet.of(EstateRole.WATCHER)]
    }

    /**
     * The handler that asks nothing beyond being identified, which is what the standing's endpoint
     * is. What holds it apart from a handler nobody gated is that it says so.
     */
    def "lets a caller holding nothing through to a handler that asks no act, without asking the store"() {
        given:
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(READER))

        when:
        def answered = asking(ActProbeController.ASKING_NOTHING)

        then:
        answered.response.status == 200
        answered.response.contentAsString == ActProbeController.ANSWERED

        and: "the store never reached, a handler asking no act having nothing to look up"
        Mockito.verifyNoInteractions(grants)
    }

    /**
     * The gate closing on this application's own omission rather than on anybody's standing: the
     * handler declared nothing, so nothing says whether being identified was meant to be enough.
     */
    def "does not answer at a handler under the prefix that declares neither, however the caller stands"() {
        given:
        holding(EnumSet.allOf(EstateRole))

        when:
        def answered = asking(UndeclaredProbeController.DECLARING_NOTHING)

        then:
        answered.response.status == 500

        and: "a fault kept by no cache any more than an answer is"
        answered.response.getHeader("Cache-Control") == "no-store"

        and: "with the handler never run, and nothing named as the reason it was not"
        !answered.response.contentAsString.contains(UndeclaredProbeController.ANSWERED)
        !answered.response.contentAsString.contains("declaringNothing")
    }

    /**
     * Selected by a mapping of its own, which carries the gate as every mapping does: with nowhere to say
     * what it asks, a routing function is the same omission as a handler method that says nothing.
     */
    def "does not answer at a routing function under the prefix, however the caller stands"() {
        given:
        holding(EnumSet.allOf(EstateRole))

        when:
        def answered = asking(NoMethodProbes.ROUTED)

        then:
        answered.response.status == 500
        answered.resolvedException instanceof IllegalStateException
        answered.resolvedException.message.endsWith("answers under the prefix with no handler method to declare what it asks on")
        noMethodProbeRuns.get() == 0
        Mockito.verifyNoInteractions(grants)
    }

    /**
     * The same omission at a bean named by its address. The framework hands no advice a failure at such a
     * handler, so it reaches the container unanswered — failed before the handler ran, all the same.
     */
    def "fails a bean named by an address under the prefix before it runs, however the caller stands"() {
        given:
        holding(EnumSet.allOf(EstateRole))

        when:
        asking(NoMethodProbes.BEAN_NAMED)

        then:
        def escaped = thrown(ServletException)
        escaped.cause instanceof IllegalStateException
        escaped.cause.message.endsWith("answers under the prefix with no handler method to declare what it asks on")
        noMethodProbeRuns.get() == 0
        Mockito.verifyNoInteractions(grants)
    }

    /** Static handling serves the files it holds; under the prefix it is answered as missing first, a real file included. */
    def "leaves a file static handling holds under the prefix unserved, however the caller stands"() {
        given:
        holding(EnumSet.allOf(EstateRole))
        def held = new ClassPathResource(NoMethodProbes.SERVED_FILE_SOURCE)
        assert held.exists(), "no file for the static handling to serve, so its absence below would prove nothing"

        when:
        def answered = asking(NoMethodProbes.SERVED_FILE)

        then:
        answered.response.status == 404
        answered.resolvedException instanceof NoResourceFoundException
        answered.handler.locations.any { it instanceof ClassPathResource && it.path == "probe-files/" }
        !answered.response.contentAsString.contains(held.getContentAsString(UTF_8).trim())
        Mockito.verifyNoInteractions(grants)
    }

    /**
     * An address the static handling answers selects no method at all, so there is no declaration to
     * read there. Asserted rather than assumed: reading one off whatever was selected would fail the
     * document itself.
     */
    def "leaves an address no handler method answers alone"() {
        when:
        def answered = mockMvc.perform(get("/index.html").accept(MediaType.TEXT_HTML)).andReturn()

        then:
        answered.response.status == 200

        and:
        Mockito.verifyNoInteractions(grants)
    }

    /** The framework's own answer lists methods and runs nothing, so it asks no act of anybody. */
    def "lets a caller holding nothing ask which methods a handler asking an act takes"() {
        given:
        holding(EnumSet.noneOf(EstateRole))

        when:
        def answered = mockMvc.perform(options(ActProbeController.ASKING_AN_ACT)).andReturn()

        then:
        answered.response.status == 200
        answered.response.getHeader("Allow").split(",").collect { it.trim() } as Set == ["GET", "HEAD", "OPTIONS"] as Set

        and: "without the handler run or the store asked"
        !answered.response.contentAsString.contains(ActProbeController.ANSWERED)
        Mockito.verifyNoInteractions(grants)
    }
}
