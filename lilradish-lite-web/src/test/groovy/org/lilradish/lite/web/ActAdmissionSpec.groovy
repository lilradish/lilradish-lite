package org.lilradish.lite.web

import jakarta.servlet.FilterChain
import org.libprunus.core.error.ApiErrorException
import org.lilradish.lite.app.estate.EstateRoleGrants
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.identification.UserIdentification
import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.identity.EstateAct
import org.lilradish.lite.domain.identity.EstateRole
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.observability.Correlation
import org.lilradish.lite.web.fixture.ActProbeController
import org.lilradish.lite.web.fixture.GroupProbeController
import org.lilradish.lite.web.fixture.MisdeclaredProbeController
import org.lilradish.lite.web.fixture.UndeclaredProbeController
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.HttpRequestHandler
import org.springframework.web.cors.PreFlightRequestHandler
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerExceptionResolver
import org.springframework.web.servlet.mvc.ParameterizableViewController
import org.springframework.web.servlet.resource.NoResourceFoundException
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler
import spock.lang.Specification

/**
 * The two things the gate must not take from the door on trust: that a caller was admitted at all,
 * and that the address is one the door guards. Both are unobservable over a dispatcher — the door
 * stands in front of it and turns away exactly the requests that would show either — so the gate is
 * asked here directly, with the selected handler handed to it the way the dispatcher hands one.
 *
 * <p>What admits a caller is the door itself rather than an attribute written here by hand. The
 * name that attribute is kept under is the door's own business, and a spec that wrote one would
 * pass against a door that had stopped writing it.
 */
class ActAdmissionSpec extends Specification {

    static final UserId STEWARD = new UserId("000001")

    static final HandlerMethod ASKING_AN_ACT = handlerFor(ActProbeController, "askingAnAct")

    static final HandlerMethod ASKING_NOTHING = handlerFor(ActProbeController, "askingNothing")

    static final HandlerMethod DECLARING_NOTHING = handlerFor(UndeclaredProbeController, "declaringNothing")

    /** A handler that is no method, as a bean named by its address or a routing function's handler is. */
    static final HttpRequestHandler NO_METHOD = { asked, answer -> } as HttpRequestHandler

    static final String UNDER_THE_PREFIX = CallerAdmission.THIS_APPLICATION_ANSWERS + "/there-is-no-such"

    private EstateRoleGrants grants = Mock()

    private GroupRoles groupRoles = Mock()

    private HandlerExceptionResolver refusals = Stub()

    private ActAdmission gate = new ActAdmission(grants, groupRoles)

    def cleanup() {
        Correlation.clear()
        MDC.clear()
    }

    private static HandlerMethod handlerFor(Class<?> controller, String method) {
        new HandlerMethod(controller.getDeclaredConstructor().newInstance(),
                controller.declaredMethods.find { it.name == method })
    }

    private MockHttpServletRequest admitting(String address, UserId caller) {
        def request = new MockHttpServletRequest("GET", address)
        def door = new CallerAdmission({ asked -> Optional.of(caller) } as UserIdentification, refusals)
        door.doFilter(request, new MockHttpServletResponse(), { asked, answer -> } as FilterChain)
        request
    }

    /** A handler asking for the group admitted on a request nothing admitted it on is this application's fault. */
    def "a group asked for on a request the gate admitted into none is a fault, never a group"() {
        when:
        ActAdmission.admittedGroup(new MockHttpServletRequest("GET", ActProbeController.ASKING_AN_ACT))

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "No group was admitted for this request"
    }

    /**
     * The gate reads the address as well as the handler. A method mapped both inside the prefix and
     * outside it is one handler, so what decides is where the request came to rather than what the
     * mapping list happens to hold.
     */
    def "leaves a handler alone at an address the door does not guard, nothing out there having a caller"() {
        given:
        def request = new MockHttpServletRequest("GET", "/index.html")

        when:
        def carriedOn = gate.preHandle(request, new MockHttpServletResponse(), handler)

        then:
        carriedOn

        and:
        0 * grants._

        where:
        handler << [ASKING_AN_ACT, NO_METHOD, new ResourceHttpRequestHandler()]
    }

    /**
     * Asked of the handler that declares an act and of the one that declares it asks none, because
     * what is being held is that neither declaration is what decides this.
     */
    def "refuses a caller nothing admitted, whatever the handler it reached declared"() {
        given:
        def request = new MockHttpServletRequest("GET", address)

        when:
        gate.preHandle(request, new MockHttpServletResponse(), handler)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.NOT_SIGNED_IN

        and: "with the store never reached, an unadmitted caller being nobody to look up"
        0 * grants._

        where:
        address                            | handler
        ActProbeController.ASKING_AN_ACT   | ASKING_AN_ACT
        ActProbeController.ASKING_NOTHING  | ASKING_NOTHING
        UNDER_THE_PREFIX                   | NO_METHOD
    }

    /** A preflight runs nothing, so nothing is asked of whoever sent it. */
    def "lets a preflight under the prefix through without asking the store anything"() {
        given:
        def request = admitting(UNDER_THE_PREFIX, STEWARD)

        when:
        def carriedOn = gate.preHandle(
                request, new MockHttpServletResponse(), { asked, answer -> } as PreFlightRequestHandler)

        then:
        carriedOn

        and:
        0 * grants._
        0 * groupRoles._
    }

    /** The static handling claims every address nothing else does; under the prefix it serves nothing, a file included. */
    def "answers the static handling under the prefix as missing before it can serve anything"() {
        given:
        def request = admitting(UNDER_THE_PREFIX, STEWARD)

        when:
        gate.preHandle(request, new MockHttpServletResponse(), new ResourceHttpRequestHandler())

        then:
        def missing = thrown(NoResourceFoundException)
        missing.statusCode.value() == 404

        and:
        0 * grants._
        0 * groupRoles._
    }

    /** Nowhere to declare what it asks is the same omission as declaring nothing, and fails the same way. */
    def "fails anything under the prefix that is no handler method, having nowhere to declare what it asks"() {
        given:
        def request = admitting(UNDER_THE_PREFIX, STEWARD)
        def response = new MockHttpServletResponse()

        when:
        gate.preHandle(request, response, handler)

        then:
        def failed = thrown(IllegalStateException)
        failed.message.endsWith("answers under the prefix with no handler method to declare what it asks on")

        and: "with nothing answered and nothing looked up on the way"
        !response.committed
        response.status == 200
        0 * grants._
        0 * groupRoles._

        where:
        handler << [NO_METHOD, new ParameterizableViewController()]
    }

    /** Only the framework's own OPTIONS answer runs nothing; a handler of ours answering OPTIONS is asked. */
    def "asks its act of a handler of this application's own that answers OPTIONS"() {
        given:
        def request = admitting(ActProbeController.ASKING_AN_ACT, STEWARD)
        request.method = "OPTIONS"

        when:
        gate.preHandle(request, new MockHttpServletResponse(), ASKING_AN_ACT)

        then:
        1 * grants.heldBy(STEWARD) >> EnumSet.of(EstateRole.WATCHER)

        and:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.ACT_NOT_PERMITTED

        and: "the store asked that and nothing else"
        0 * _
    }

    /**
     * The shape a handler nobody gated has. It is this application's own omission rather than
     * anybody's refusal, so it carries no published code and nothing about it reaches the caller.
     */
    def "fails a handler under the prefix that declares neither what it asks nor that it asks nothing"() {
        given:
        def request = admitting(UndeclaredProbeController.DECLARING_NOTHING, STEWARD)
        def response = new MockHttpServletResponse()

        when:
        gate.preHandle(request, response, DECLARING_NOTHING)

        then:
        def failed = thrown(IllegalStateException)
        failed.message.contains("declaringNothing")

        and: "with nothing answered and nothing looked up on the way"
        !response.committed
        response.status == 200
        0 * grants._
        0 * groupRoles._
    }

    /** Two declarations leave which of them decides unsaid, which is the same omission as none. */
    def "fails a handler under the prefix that declares more than one thing it asks"() {
        given:
        def request = admitting(address.replace("{groupId}", "00000003-0000-4000-8000-000000000001"), STEWARD)
        def response = new MockHttpServletResponse()

        when:
        gate.preHandle(request, response, handlerFor(MisdeclaredProbeController, method))

        then:
        def failed = thrown(IllegalStateException)
        failed.message.contains(method)

        and:
        !response.committed
        response.status == 200
        0 * grants._
        0 * groupRoles._

        where:
        address                                               | method
        MisdeclaredProbeController.DECLARING_TWO              | "declaringTwo"
        MisdeclaredProbeController.MEMBERSHIP_AND_PERMISSION  | "membershipAndPermission"
    }

    def "lets an admitted caller through to a handler asking an act their roles reach"() {
        given:
        def request = admitting(ActProbeController.ASKING_AN_ACT, STEWARD)

        when:
        def carriedOn = gate.preHandle(request, new MockHttpServletResponse(), ASKING_AN_ACT)

        then:
        1 * grants.heldBy(STEWARD) >> EnumSet.of(EstateRole.STEWARD)

        and:
        carriedOn
    }

    def "refuses an admitted caller at a handler asking an act no role of theirs reaches"() {
        given:
        def request = admitting(ActProbeController.ASKING_AN_ACT, STEWARD)

        when:
        gate.preHandle(request, new MockHttpServletResponse(), ASKING_AN_ACT)

        then:
        1 * grants.heldBy(STEWARD) >> EnumSet.of(EstateRole.WATCHER)

        and:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.ACT_NOT_PERMITTED

        and: "told what kind of thing they may not do, and never which act was asked of them"
        EstateAct.values().every { !refused.message.contains(it.published()) }
        EstateAct.values().every { !refused.message.contains(it.name()) }
    }

    def "asks nothing of the store for a handler that declares it asks no act"() {
        given:
        def request = admitting(ActProbeController.ASKING_NOTHING, STEWARD)

        when:
        def carriedOn = gate.preHandle(request, new MockHttpServletResponse(), ASKING_NOTHING)

        then:
        carriedOn

        and:
        0 * grants._
    }

    /** Each of the four things a handler may ask counts once; none and any two are alike the omission. */
    def "tells a handler declaring exactly one thing it asks from one declaring none or more"() {
        expect:
        ActAdmission.declaresExactlyOne(handlerFor(controller, method)) == exactlyOne

        where:
        controller                  | method                     || exactlyOne
        ActProbeController          | "askingAnAct"              || true
        GroupProbeController        | "changingMembership"       || true
        GroupProbeController        | "inTheGroup"               || true
        ActProbeController          | "askingNothing"            || true
        UndeclaredProbeController   | "declaringNothing"         || false
        MisdeclaredProbeController  | "declaringTwo"             || false
        MisdeclaredProbeController  | "membershipAndPermission"  || false
    }

    /** Named by its method, so the one start refuses on and the one a request fails on read alike. */
    def "names the handler that declared not exactly one thing it asks"() {
        when:
        def failure = ActAdmission.misdeclared(handlerFor(controller, method))

        then:
        failure.message == handlerFor(controller, method).method.toString() + " declares not exactly one of the" +
                " act it asks, the permission it asks in a group, membership of a group, or that it asks none"

        where:
        controller                  | method
        UndeclaredProbeController   | "declaringNothing"
        MisdeclaredProbeController  | "declaringTwo"
    }

    /**
     * The group is read off what the dispatcher matched the address by, and an address that named none
     * left nothing there: this application's own mistake, never a refusal of the caller.
     */
    def "fails a handler asking anything in a group at an address naming no group"() {
        given:
        def request = admitting(address, STEWARD)
        def response = new MockHttpServletResponse()

        when:
        gate.preHandle(request, response, handlerFor(MisdeclaredProbeController, method))

        then:
        def failed = thrown(IllegalStateException)
        failed.message.contains(method)

        and:
        !response.committed
        response.status == 200
        0 * grants._
        0 * groupRoles._

        where:
        address                                            | method
        MisdeclaredProbeController.IN_NO_GROUP             | "inNoGroup"
        MisdeclaredProbeController.MEMBERSHIP_IN_NO_GROUP  | "membershipInNoGroup"
    }
}
