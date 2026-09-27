package org.lilradish.lite.web

import static org.mockito.ArgumentMatchers.any
import static org.mockito.BDDMockito.given
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get

import jakarta.servlet.http.HttpServletRequest
import org.libprunus.spring.error.ApiErrorAutoConfiguration
import org.lilradish.lite.app.estate.EstateRoleGrants
import org.lilradish.lite.app.identification.UserIdentification
import org.lilradish.lite.domain.identity.EstateRole
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.testutil.GroupRolesStoodIn
import org.lilradish.lite.web.fixture.ActProbeController
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper

/**
 * Where the turn stands in the chain the application registers: behind the door, which is the only thing that
 * says whether anybody was admitted, and ahead of the gate and the handler. Either side is invisible to a
 * filter stood up by hand, which would be ordered by whoever wrote the spec.
 */
@WebMvcTest(ActProbeController)
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
@Import(ActProbeController)
@GroupRolesStoodIn
class LargeBodyAdmissionIntegrationSpec extends Specification {

    static final UserId STEWARD = new UserId("000001")

    static final byte[] PAST_A_MEBIBYTE = new byte[1024 * 1024 + 1]

    @Autowired
    private MockMvc mockMvc

    @Autowired
    private LargeBodyAdmission admission

    @Autowired
    private ApplicationContext context

    @MockitoBean
    private UserIdentification identification

    @MockitoBean
    private EstateRoleGrants grants

    private MvcResult askingWithALargeBody(String address) {
        mockMvc.perform(get(address).content(PAST_A_MEBIBYTE)).andReturn()
    }

    private void arriving(UserId user) {
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.ofNullable(user))
    }

    private int turnsFree() {
        admission.turn.turn.availablePermits()
    }

    /** Imported by the door rather than scanned, which a slice does not do for plain configuration. */
    def "a slice holding the door holds exactly one turn"() {
        expect:
        context.getBeansOfType(LargeBodyAdmission).size() == 1
        context.getBeansOfType(CallerAdmission).size() == 1
    }

    /** The turn is held here, so a request that reached it would wait out the whole wait and be refused as busy. */
    def "a caller nothing identified is refused at the door with a large body, never waiting on the turn"() {
        given:
        arriving(null)
        admission.turn.take()

        when:
        def answered = askingWithALargeBody(ActProbeController.ASKING_NOTHING)

        then:
        answered.response.status == 401
        JsonMapper.builder().build().readTree(answered.response.contentAsString).get("code").asString() ==
                "NOT_SIGNED_IN"

        and: "the turn still held where it was taken, and the handler never run"
        turnsFree() == 0
        !answered.response.contentAsString.contains(ActProbeController.ANSWERED)

        cleanup:
        admission.turn.give()
    }

    /** The gate asks the estate's grants at dispatch, so what that asking sees is what the gate and handler see. */
    def "an admitted caller's large body is let past the gate and handled holding the turn, free again once answered"() {
        given:
        arriving(STEWARD)
        def turnsFreeAtTheGate = []
        given(grants.heldBy(STEWARD)).willAnswer { asked ->
            turnsFreeAtTheGate << turnsFree()
            EstateRole.values() as Set
        }

        when:
        def answered = askingWithALargeBody(ActProbeController.ASKING_AN_ACT)

        then:
        answered.response.status == 200
        answered.response.contentAsString == ActProbeController.ANSWERED
        turnsFreeAtTheGate == [0]

        and:
        turnsFree() == 1
        Mockito.mockingDetails(grants).invocations.size() == 1
    }

    /** An interrupt is what refuses the wait here, the wait itself being far too long to run out in a test. */
    def "an admitted caller's large body refused its turn is answered busy, to be sent again, and never reaches the gate"() {
        given:
        arriving(STEWARD)
        Thread.currentThread().interrupt()

        when:
        def answered = askingWithALargeBody(ActProbeController.ASKING_AN_ACT)

        then:
        answered.response.status == 503
        JsonMapper.builder().build().readTree(answered.response.contentAsString).get("code").asString() ==
                "SERVICE_BUSY"
        answered.response.getHeader("Retry-After") == "30"

        and: "the gate never asked, the handler never run, and the turn left free"
        Mockito.mockingDetails(grants).invocations.isEmpty()
        !answered.response.contentAsString.contains(ActProbeController.ANSWERED)
        turnsFree() == 1

        cleanup:
        Thread.interrupted()
    }

    def "an admitted caller's GET of no declared length is let past the gate and handled with the turn left free"() {
        given:
        arriving(STEWARD)
        def turnsFreeAtTheGate = []
        given(grants.heldBy(STEWARD)).willAnswer { asked ->
            turnsFreeAtTheGate << turnsFree()
            EstateRole.values() as Set
        }

        when:
        def answered = mockMvc.perform(get(ActProbeController.ASKING_AN_ACT)).andReturn()

        then:
        answered.response.status == 200
        answered.response.contentAsString == ActProbeController.ANSWERED
        turnsFreeAtTheGate == [1]

        and:
        turnsFree() == 1
    }
}
