package org.lilradish.lite.app.run

import static org.mockito.ArgumentMatchers.any
import static org.mockito.BDDMockito.given
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put

import jakarta.servlet.http.HttpServletRequest
import java.time.Instant
import org.libprunus.spring.error.ApiErrorAutoConfiguration
import org.lilradish.lite.app.estate.EstateRoleGrants
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.identification.UserIdentification
import org.lilradish.lite.domain.identity.EstateRole
import org.lilradish.lite.domain.identity.GroupId
import org.lilradish.lite.domain.identity.GroupRole
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.registry.EntryId
import org.lilradish.lite.domain.registry.EntryName
import org.lilradish.lite.domain.run.Ceiling
import org.lilradish.lite.domain.run.CeilingChangeId
import org.lilradish.lite.domain.run.RunAct
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.domain.run.RunName
import org.lilradish.lite.domain.run.RunState
import org.lilradish.lite.web.GroupPermissionRequired
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import spock.lang.Specification
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * What every act on a run is answered with, over a real dispatcher and a replaced store: which act of which run
 * the request became, the run read once it landed, and every refusal met before the store is asked.
 */
@WebMvcTest([RunsController, RunChangesController])
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
class RunChangesControllerIntegrationSpec extends Specification {

    static final UserId READER = new UserId("000001")

    static final JsonMapper JSON = JsonMapper.builder().build()

    static final UUID GROUP = UUID.fromString("00000003-0000-4000-8000-000000001301")

    static final UUID RUN = UUID.fromString("00000008-0000-4000-8000-000000001301")

    static final UUID RAISE = UUID.fromString("0000000b-0000-4000-8000-000000001301")

    static final String ADDRESS = "/api/groups/${GROUP}/runs/${RUN}"

    static final String RAISE_ADDRESS = "${ADDRESS}/ceiling-changes/${RAISE}"

    static final Runs.RunView READ = new Runs.RunView(new RunId(RUN), 1, new RunName("Claim from Ada"), null,
            new Runs.Workflow(new EntryId(UUID.fromString("00000006-0000-4000-8000-000000001301")),
                    new EntryName("Handle a claim"), 1),
            null, Instant.parse("2026-09-25T08:00:00Z"), JSON.readTree('{"claim":"Lost bag"}'), RunState.RUNNING, null,
            null, new RunBudget.Spend(0, 0, false, false),
            new Runs.CeilingHeld.Own(new RunBudget.InForce(null, false), null), EnumSet.of(RunAct.STOP))

    static final String CEILING_REFUSED =
            "This takes a JSON object holding a run's ceiling in digits, or null for none, and nothing else."

    static final String NAME_REFUSED = "This takes a JSON object holding a run's name, and nothing else."

    static final String CEILING_LIMIT =
            "A ceiling is a whole number from one to 9,007,199,254,740,991, written in digits, or none at all."

    @Autowired
    private MockMvc mockMvc

    @Autowired
    private RequestMappingHandlerMapping mappings

    @MockitoBean
    private UserIdentification identification

    @MockitoBean
    private EstateRoleGrants grants

    @MockitoBean
    private GroupRoles groupRoles

    @MockitoBean
    private Runs runs

    @MockitoBean
    private RunChanges changes

    private void holding(Set<GroupRole> held) {
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(READER))
        given(grants.heldBy(READER)).willReturn(EnumSet.allOf(EstateRole))
        given(groupRoles.heldBy(READER, new GroupId(GROUP))).willReturn(held)
        given(runs.run(new GroupId(GROUP), new RunId(RUN), READER)).willReturn(READ)
    }

    /** As this application's own pages send it, which a browser says of every request it makes. */
    private MvcResult sending(MockHttpServletRequestBuilder request) {
        mockMvc.perform(request.header("Sec-Fetch-Site", "same-origin")).andReturn()
    }

    private MvcResult sendingBody(MockHttpServletRequestBuilder request, String body) {
        sending(request.contentType(MediaType.APPLICATION_JSON).content(body))
    }

    private static JsonNode documentOf(MvcResult answered) {
        JSON.readTree(answered.response.contentAsString)
    }

    /** The one act asked, and only once it has landed the run read, which is what is answered. */
    private void actedOnlyBy(MvcResult answered, Closure<?> asked) {
        assert answered.response.status == 200
        assert documentOf(answered).get("runId").asString() == RUN.toString()
        assert documentOf(answered).get("acts").collect { it.asString() } == ["stop"]
        def order = Mockito.inOrder(changes, runs)
        asked(order.verify(changes))
        order.verify(runs).run(new GroupId(GROUP), new RunId(RUN), READER)
        Mockito.verifyNoMoreInteractions(changes, runs)
    }

    /**
     * Read off the mappings the dispatcher resolved, and held whole against the permission each act takes: a
     * handler guarded by another would let in whoever holds that one.
     */
    def "every act on a run is asked of the permission the act itself takes, and there are no others"() {
        given:
        def asked = mappings.handlerMethods.values()
                .findAll { it.beanType == RunChangesController }
                .collectEntries { [(it.method.name): it.getMethodAnnotation(GroupPermissionRequired).value()] }

        expect:
        asked == [stop         : RunAct.STOP.permission(),
                  openAgain    : RunAct.OPEN_AGAIN.permission(),
                  rename       : RunAct.RENAME.permission(),
                  changeCeiling: RunAct.CHANGE_CEILING.permission(),
                  approveRaise : RunAct.APPROVE_RAISE.permission(),
                  refuseRaise  : RunAct.REFUSE_RAISE.permission(),
                  withdrawRaise: RunAct.WITHDRAW_RAISE.permission()]
    }

    def "stops a run and opens it again, each answering with the run as it is then read"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))

        when:
        def answered = sending(method == "PUT" ? put(ADDRESS + "/stop") : delete(ADDRESS + "/stop"))

        then:
        actedOnlyBy(answered) { it."${act}"(new GroupId(GROUP), new RunId(RUN), READER) }

        where:
        method   || act
        "PUT"    || "stop"
        "DELETE" || "openAgain"
    }

    def "renames a run, answering with it as it is then read"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))

        when:
        def answered = sendingBody(patch(ADDRESS), '{"name":"Claim from Ada, again"}')

        then:
        actedOnlyBy(answered) {
            it.rename(new GroupId(GROUP), new RunId(RUN), new RunName("Claim from Ada, again"), READER)
        }
        documentOf(answered).get("startedWith") == READ.startedWith()
    }

    /** Digits in a string, so a count past what a double holds arrives as typed; null asks for none. */
    def "changes a run's ceiling to the count asked, or to none, answering with the run as it is then read"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))

        when:
        def answered = sendingBody(patch(ADDRESS + "/ceiling"), body)

        then:
        actedOnlyBy(answered) { it.changeCeiling(new GroupId(GROUP), new RunId(RUN), asked, READER) }

        where:
        body                                  || asked
        '{"ceiling":"5000"}'                  || new Ceiling(5000)
        '{"ceiling":"9007199254740991"}'      || new Ceiling(9007199254740991L)
        '{"ceiling":null}'                    || null
    }

    /** A raise is decided by its identifier, so nobody decides one they never saw. */
    def "decides a raise by its identifier, answering with the run as it is then read"() {
        given:
        holding(EnumSet.of(GroupRole.OVERSEER))

        when:
        def answered = sending(put("${RAISE_ADDRESS}/${segment}"))

        then:
        actedOnlyBy(answered) { it."${act}"(new GroupId(GROUP), new RunId(RUN), new CeilingChangeId(RAISE), READER) }

        where:
        segment      || act
        "approval"   || "approveRaise"
        "refusal"    || "refuseRaise"
        "withdrawal" || "withdrawRaise"
    }

    /** Each value is judged only once the body holds the one member taken, of a type it may be. */
    def "refuses a body it cannot read as a name or a ceiling, under the code of whatever is refused"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))

        when:
        def answered = sendingBody(patch(address == "ceiling" ? ADDRESS + "/ceiling" : ADDRESS), body)

        then:
        answered.response.status == 400
        documentOf(answered).get("code").asString() == code
        documentOf(answered).get("detail").asString() == sentence

        and: "no act asked, and no run read"
        Mockito.verifyNoInteractions(changes, runs)

        where:
        address   | body                                 || code                | sentence
        "ceiling" | "[]"                                 || "BODY_UNUSABLE"     | CEILING_REFUSED
        "ceiling" | "{}"                                 || "BODY_UNUSABLE"     | CEILING_REFUSED
        "ceiling" | '{"ceiling":5000}'                   || "BODY_UNUSABLE"     | CEILING_REFUSED
        "ceiling" | '{"ceiling":"5000","raise":true}'    || "BODY_UNUSABLE"     | CEILING_REFUSED
        "ceiling" | '{"ceiling":"0"}'                    || "CEILING_UNUSABLE"  | CEILING_LIMIT
        "ceiling" | '{"ceiling":"1e3"}'                  || "CEILING_UNUSABLE"  | CEILING_LIMIT
        "ceiling" | '{"ceiling":"9007199254740992"}'     || "CEILING_UNUSABLE"  | CEILING_LIMIT
        "name"    | "[]"                                 || "BODY_UNUSABLE"     | NAME_REFUSED
        "name"    | '{"name":null}'                      || "BODY_UNUSABLE"     | NAME_REFUSED
        "name"    | '{"name":"Claim","purpose":null}'    || "BODY_UNUSABLE"     | NAME_REFUSED
        "name"    | '{"name":""}'                        || "RUN_NAME_UNUSABLE" | "A run's name is one to 128 characters on one line, with something in it that shows."
        "name"    | '{"name":"' + "n" * 129 + '"}'       || "RUN_NAME_UNUSABLE" | "A run's name is one to 128 characters on one line, with something in it that shows."
    }

    /** Deciding a raise is approving an entry's kind of authority, which an operator does not hold. */
    def "refuses a member whose roles there do not reach deciding a raise, and never asks the store"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))

        when:
        def answered = sending(put("${RAISE_ADDRESS}/${segment}"))

        then:
        answered.response.status == 403
        documentOf(answered).get("code").asString() == "ACT_NOT_PERMITTED"
        Mockito.verifyNoInteractions(changes, runs)

        where:
        segment << ["approval", "refusal"]
    }

    /** An address naming no run, or no raise, is refused as one the store does not hold, before anything else is read. */
    def "refuses an act at an address naming no run or no raise, and never asks the store"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))

        when:
        def answered = sending(request)

        then:
        answered.response.status == status
        documentOf(answered).get("code").asString() == code
        Mockito.verifyNoInteractions(changes, runs)

        where:
        request                                                                                      || status | code
        put("/api/groups/${GROUP}/runs/not-a-run/stop")                                              || 404    | "RUN_NOT_IN_VIEW"
        delete("/api/groups/${GROUP}/runs/not-a-run/stop")                                           || 404    | "RUN_NOT_IN_VIEW"
        patch("/api/groups/${GROUP}/runs/not-a-run").contentType(MediaType.APPLICATION_JSON).content("{}") || 404 | "RUN_NOT_IN_VIEW"
        patch("/api/groups/${GROUP}/runs/not-a-run/ceiling").contentType(MediaType.APPLICATION_JSON)
                .content('{"ceiling":null}')                                                         || 404    | "RUN_NOT_IN_VIEW"
        put("/api/groups/${GROUP}/runs/not-a-run/ceiling-changes/${RAISE}/approval")                 || 404    | "RUN_NOT_IN_VIEW"
        put("${ADDRESS}/ceiling-changes/not-a-raise/approval")                                       || 409    | "CEILING_RAISE_NOT_WAITING"
        put("${ADDRESS}/ceiling-changes/not-a-raise/refusal")                                        || 409    | "CEILING_RAISE_NOT_WAITING"
        put("${ADDRESS}/ceiling-changes/not-a-raise/withdrawal")                                     || 409    | "CEILING_RAISE_NOT_WAITING"
    }

    def "refuses a body or a parameter an act does not take, and never asks the store"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))

        when:
        def answered = sending(request)

        then:
        answered.response.status == 400
        documentOf(answered).get("code").asString() == code
        Mockito.verifyNoInteractions(changes, runs)

        where:
        request                                                                                  || code
        put(ADDRESS + "/stop").contentType(MediaType.APPLICATION_JSON).content("{}")             || "BODY_UNUSABLE"
        delete(ADDRESS + "/stop").contentType(MediaType.APPLICATION_JSON).content("{}")          || "BODY_UNUSABLE"
        put("${RAISE_ADDRESS}/approval").contentType(MediaType.APPLICATION_JSON).content("{}")   || "BODY_UNUSABLE"
        put("${RAISE_ADDRESS}/refusal").contentType(MediaType.APPLICATION_JSON).content("{}")    || "BODY_UNUSABLE"
        put("${RAISE_ADDRESS}/withdrawal").contentType(MediaType.APPLICATION_JSON).content("{}") || "BODY_UNUSABLE"
        put(ADDRESS + "/stop").queryParam("until", "1")                                          || "PARAMETER_UNKNOWN"
        patch(ADDRESS + "/ceiling").queryParam("approve", "1").contentType(MediaType.APPLICATION_JSON)
                .content('{"ceiling":null}')                                                     || "PARAMETER_UNKNOWN"
        patch(ADDRESS).queryParam("group", "1").contentType(MediaType.APPLICATION_JSON)
                .content('{"name":"Claim"}')                                                     || "PARAMETER_UNKNOWN"
    }
}
