package org.lilradish.lite.app.standing

import static org.mockito.ArgumentMatchers.any

import jakarta.servlet.http.HttpServletRequest
import org.libprunus.spring.error.ApiErrorAutoConfiguration
import org.lilradish.lite.app.estate.EstateRoleGrants
import org.lilradish.lite.app.identification.UserIdentification
import org.lilradish.lite.domain.identity.EstateRole
import org.lilradish.lite.domain.identity.GroupId
import org.lilradish.lite.domain.identity.GroupKey
import org.lilradish.lite.domain.identity.GroupName
import org.lilradish.lite.domain.identity.GroupRole
import org.lilradish.lite.domain.identity.HumanPrincipal
import org.lilradish.lite.domain.identity.Scope
import org.lilradish.lite.domain.identity.SubjectId
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.testutil.GroupRolesStoodIn
import org.mockito.BDDMockito
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import spock.lang.Specification
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * The standing as a caller receives it over the real dispatcher, serialiser and error outlet, with the
 * store and the identity provider replaced; sets are compared whole, never by containment.
 */
@WebMvcTest(StandingController)
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
@GroupRolesStoodIn
class StandingEndpointIntegrationSpec extends Specification {

    static final UserId STEWARD = new UserId("000001")

    static final SubjectId READER = new SubjectId(UUID.fromString("00000002-0000-4000-8000-000000000801"))

    static final GroupId PAYROLL = new GroupId(UUID.fromString("00000003-0000-4000-8000-000000000801"))

    static final GroupId TRIAGE = new GroupId(UUID.fromString("00000003-0000-4000-8000-000000000802"))

    static final List<String> AN_OPERATOR_REACHES =
            ["read_membership", "start_run", "answer_step", "read_own_runs", "author_entry", "read_inference_content"]

    static final List<String> EVERY_PERMISSION = ["read_membership", "change_membership", "start_run",
                                                  "read_own_runs", "read_all_runs", "read_inference_content",
                                                  "answer_step", "review_at_gate", "author_entry",
                                                  "approve_entry", "revoke_entry"]

    @Autowired
    private MockMvc mockMvc

    @MockitoBean
    private UserIdentification identification

    @MockitoBean
    private EstateRoleGrants grants

    @MockitoBean
    private Holdings holdings

    private static JsonNode answerIn(MvcResult answered) {
        JsonMapper.builder().build().readTree(answered.response.contentAsString)
    }

    private static Set<String> actsIn(MvcResult answered) {
        answerIn(answered).get("acts").collect { it.asString() } as Set
    }

    /** Each group as it arrived, in the order it arrived, its permissions read as a set. */
    private static List<Map<String, Object>> groupsIn(MvcResult answered) {
        answerIn(answered).get("groups").collect { group ->
            [groupId    : group.get("groupId").asString(),
             key        : group.get("key").asString(),
             name       : group.get("name").asString(),
             permissions: group.get("permissions").collect { it.asString() } as Set]
        }
    }

    private static String codeIn(MvcResult answered) {
        answerIn(answered).get("code").asString()
    }

    private MvcResult asking() {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/standing")).andReturn()
    }

    private void arriving(UserId user) {
        BDDMockito.given(identification.identify(any(HttpServletRequest))).willReturn(Optional.ofNullable(user))
    }

    /** Each role alone and both together, so neither adds up to the other out here either. */
    def "tells a reader every act the roles they hold reach under the member the reader parses"() {
        given:
        arriving(STEWARD)
        BDDMockito.given(holdings.heldBy(STEWARD)).willReturn(EstateHolding.of(held as EstateRole[]))

        when:
        MvcResult answered = asking()

        then:
        answered.response.status == 200
        actsIn(answered) == reached as Set

        and: "and no group is named for somebody the estate alone knows, whatever they hold in it"
        groupsIn(answered) == []

        where:
        held                                     || reached
        [EstateRole.STEWARD]                     || ["keep_pool", "grant_estate_role", "keep_group_register"]
        [EstateRole.WATCHER]                     || ["check_soundness", "read_measurements"]
        [EstateRole.STEWARD, EstateRole.WATCHER] || ["keep_pool", "grant_estate_role", "keep_group_register",
                                                     "check_soundness", "read_measurements"]
    }

    /** What several roles in one group fold to is their union, and the roles themselves never cross. */
    def "names each group the reader is in, in the order read, with its key and what the roles held there reach"() {
        given:
        arriving(STEWARD)
        def principal = HumanPrincipal.of(READER, [] as Set, [
                (new Scope.Group(PAYROLL)): [GroupRole.OPERATOR] as Set,
                (new Scope.Group(TRIAGE)) : [GroupRole.OPERATOR, GroupRole.OWNER] as Set])
        BDDMockito.given(holdings.heldBy(STEWARD)).willReturn(Optional.of(new Holdings.Held(principal, [
                new Holdings.GroupInView(TRIAGE, new GroupKey("TRIAGE"), new GroupName("Triage")),
                new Holdings.GroupInView(PAYROLL, new GroupKey("PAYROLL"), new GroupName("Payroll"))])))

        when:
        MvcResult answered = asking()

        then:
        answered.response.status == 200
        groupsIn(answered) == [
                [groupId: TRIAGE.value().toString(), key: "TRIAGE", name: "Triage",
                 permissions: EVERY_PERMISSION as Set],
                [groupId: PAYROLL.value().toString(), key: "PAYROLL", name: "Payroll",
                 permissions: AN_OPERATOR_REACHES as Set]]

        and: "with nothing of the estate claimed for somebody holding nothing there"
        actsIn(answered).isEmpty()

        and: "and no member but those four on any group, a role among them"
        answerIn(answered).get("groups").every {
            it.propertyNames() as Set == ["groupId", "key", "name", "permissions"] as Set
        }
        !answered.response.contentAsString.contains("operator")
        !answered.response.contentAsString.contains("owner")
    }

    /** A 200 saying nothing of freshness may be kept on a guess, and nothing on this path sends Authorization. */
    def "forbids every cache from keeping an answer decided for one reader"() {
        given:
        arriving(STEWARD)
        BDDMockito.given(holdings.heldBy(STEWARD)).willReturn(EstateHolding.of(EstateRole.STEWARD))

        when:
        MvcResult answered = asking()

        then:
        answered.response.getHeader("Cache-Control") == "no-store"

        and: "and no second, weaker statement of freshness beside it to be believed instead"
        answered.response.getHeaderValue("Expires") == null
    }

    /** A reader the store answers nothing for is answered, not refused and not reported missing. */
    def "answers a reader the store holds nothing for with an empty standing rather than refusing them"() {
        given:
        arriving(STEWARD)
        BDDMockito.given(holdings.heldBy(STEWARD)).willReturn(EstateHolding.of())

        when:
        MvcResult answered = asking()

        then:
        answered.response.status == 200
        actsIn(answered).isEmpty()
        groupsIn(answered).isEmpty()

        and: "and nothing was refused, so no code on this response says which of the two it was"
        !answered.response.contentAsString.contains("code")
    }

    /** A group whose stored key or name will not show fails the whole standing: accepted, and loud. */
    def "answers a standing the store holds a group this system will not show in as the server's own failure"() {
        given:
        arriving(STEWARD)
        BDDMockito.given(holdings.heldBy(STEWARD)).willThrow(new IllegalStateException(
                "Group ${PAYROLL.value()} holds a key or a name this system will not show"))

        when:
        MvcResult answered = asking()

        then:
        answered.response.status == 500
        codeIn(answered) == "INTERNAL"

        and: "with neither list, nor the group, on the wire"
        !answered.response.contentAsString.contains("acts")
        !answered.response.contentAsString.contains(PAYROLL.value().toString())
    }

    /** Unauthenticated rather than a bad request, and asked before the store is, so nobody unknown is a query. */
    def "refuses a caller nothing identified as not signed in, without asking the store"() {
        given:
        arriving(null)

        when:
        MvcResult answered = asking()

        then:
        answered.response.status == 401
        answered.response.contentType.startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
        codeIn(answered) == "NOT_SIGNED_IN"

        and: "with nothing answered about what could be reached"
        !answered.response.contentAsString.contains("acts")
        !answered.response.contentAsString.contains("groups")

        and: "and the store was never reached at all, not merely never asked this one question"
        Mockito.verifyNoInteractions(holdings, grants)
    }
}
