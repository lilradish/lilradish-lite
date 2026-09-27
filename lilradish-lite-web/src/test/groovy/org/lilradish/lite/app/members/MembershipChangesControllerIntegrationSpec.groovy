package org.lilradish.lite.app.members

import static org.mockito.ArgumentMatchers.any
import static org.mockito.BDDMockito.given
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put

import jakarta.servlet.http.HttpServletRequest
import org.libprunus.spring.error.ApiErrorAutoConfiguration
import org.lilradish.lite.app.estate.EstateRoleGrants
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.identification.UserIdentification
import org.lilradish.lite.domain.identity.EstateRole
import org.lilradish.lite.domain.identity.GroupId
import org.lilradish.lite.domain.identity.GroupRole
import org.lilradish.lite.domain.identity.SubjectId
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.people.PersonName
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import spock.lang.Specification
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * What each change to a group's membership is answered with, over a real dispatcher: the status, the
 * person as the group holds them afterwards, which change the request was turned into, and every refusal
 * a request meets before the store is asked anything.
 *
 * <p>The store is replaced, being the boundary this crosses to a database, and so is who is calling and
 * what they hold in the group. What a change does to the store is its own spec's question. Every request
 * says it came from this application's own pages, which is the door's question and is asked of it
 * elsewhere.
 */
@WebMvcTest([MembersController, MembershipChangesController])
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
class MembershipChangesControllerIntegrationSpec extends Specification {

    static final UserId READER = new UserId("000001")

    static final JsonMapper JSON = JsonMapper.builder().build()

    static final UUID GROUP = UUID.fromString("00000003-0000-4000-8000-000000000f01")

    static final UUID OLIVE = UUID.fromString("00000002-0000-4000-8000-000000000f01")

    static final Members.Member OLIVE_HOLDING = new Members.Member(new SubjectId(OLIVE), new UserId("000f01"),
            new PersonName("Olive Out"), EnumSet.of(GroupRole.OPERATOR, GroupRole.OVERSEER), EnumSet.noneOf(GroupRole),
            true)

    static final Members.Member OLIVE_HOLDING_NOTHING = new Members.Member(new SubjectId(OLIVE), new UserId("000f01"),
            new PersonName("Olive Out"), EnumSet.noneOf(GroupRole), EnumSet.noneOf(GroupRole), true)

    static final String BODY_REFUSED =
            "This takes a JSON object naming one person in the pool and one or more of this group's roles, each once."

    @Autowired
    private MockMvc mockMvc

    @MockitoBean
    private UserIdentification identification

    @MockitoBean
    private EstateRoleGrants grants

    @MockitoBean
    private GroupRoles groupRoles

    @MockitoBean
    private Members members

    @MockitoBean
    private MembershipChanges changes

    private void holding(Set<GroupRole> held) {
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(READER))
        given(grants.heldBy(READER)).willReturn(EnumSet.allOf(EstateRole))
        given(groupRoles.heldBy(READER, new GroupId(GROUP))).willReturn(held)
    }

    /** As this application's own pages send it, which a browser says of every request it makes. */
    private MvcResult sending(MockHttpServletRequestBuilder request) {
        mockMvc.perform(request.header("Sec-Fetch-Site", "same-origin")).andReturn()
    }

    private MvcResult bringing(String body) {
        sending(post("/api/groups/${GROUP}/members").contentType(MediaType.APPLICATION_JSON).content(body))
    }

    private static String roleAt(String person, String role) {
        "/api/groups/${GROUP}/members/${person}/roles/${role}"
    }

    private static JsonNode documentOf(MvcResult answered) {
        JSON.readTree(answered.response.contentAsString)
    }

    private static List<String> membersOf(JsonNode node) {
        node.propertyNames().toList()
    }

    /** Void, so the verification is what fails rather than the null a mock's answer would assert as. */
    private void changedOnlyBy(Closure<?> asked) {
        asked(Mockito.verify(changes))
        Mockito.verifyNoMoreInteractions(changes)
    }

    /** The members and values the group's own reading of somebody answers with, and no other. */
    private static void assertIsHowTheGroupHolds(JsonNode document, List<String> roles) {
        assert membersOf(document) == ["subjectId", "userId", "displayName", "roles", "lastChangingRoles", "removable"]
        assert document.get("subjectId").asString() == OLIVE.toString()
        assert document.get("roles").collect { it.asString() } as Set == roles as Set
        assert document.get("lastChangingRoles").isEmpty()
        assert document.get("removable").asBoolean()
    }

    /** Created, with the address the member is read at, and the change handed the group the gate admitted. */
    def "brings somebody in holding the roles named, answering with how the group now holds them and where"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))
        given(changes.bringIn(new GroupId(GROUP), new SubjectId(OLIVE), EnumSet.of(GroupRole.OPERATOR, GroupRole.OVERSEER),
                READER)).willReturn(OLIVE_HOLDING)

        when:
        def answered = bringing('{"subjectId":"' + OLIVE + '","roles":["overseer","operator"]}')

        then:
        answered.response.status == 201
        answered.response.getHeader("Location") == "/api/groups/${GROUP}/members/${OLIVE}" as String
        assertIsHowTheGroupHolds(documentOf(answered), ["operator", "overseer"])

        and:
        changedOnlyBy {
            it.bringIn(new GroupId(GROUP), new SubjectId(OLIVE), EnumSet.of(GroupRole.OPERATOR, GroupRole.OVERSEER), READER)
        }
    }

    /** No role, a role named twice or not at all, and anything the body does not take are all the body refused. */
    def "refuses a body bringing somebody in that does not name one person and one or more roles each once"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))

        when:
        def answered = bringing(body)

        then:
        answered.response.status == 400
        documentOf(answered).get("code").asString() == "BODY_UNUSABLE"
        documentOf(answered).get("detail").asString() == BODY_REFUSED

        and:
        Mockito.verifyNoInteractions(changes)

        where:
        body << [
                '{"subjectId":"' + OLIVE + '","roles":[]}',
                '{"subjectId":"' + OLIVE + '"}',
                '{"subjectId":"' + OLIVE + '","roles":["operator","operator"]}',
                '{"subjectId":"' + OLIVE + '","roles":["captain"]}',
                '{"subjectId":"' + OLIVE + '","roles":["OPERATOR"]}',
                '{"subjectId":"' + OLIVE + '","roles":[1]}',
                '{"subjectId":"' + OLIVE + '","roles":"operator"}',
                '{"subjectId":"' + OLIVE + '","roles":["operator"],"key":"PAYROLL"}',
                '{"subjectId":"not-a-person","roles":["operator"]}',
                '{"subjectId":7,"roles":["operator"]}',
                '{"roles":["operator"],"userId":"000f01"}',
                '["operator"]',
        ]
    }

    def "gives a role, answering with how the group now holds them"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))
        given(changes.give(new GroupId(GROUP), new SubjectId(OLIVE), GroupRole.OVERSEER, READER)).willReturn(OLIVE_HOLDING)

        when:
        def answered = sending(put(roleAt(OLIVE.toString(), "overseer")))

        then:
        answered.response.status == 200
        assertIsHowTheGroupHolds(documentOf(answered), ["operator", "overseer"])

        and:
        changedOnlyBy { it.give(new GroupId(GROUP), new SubjectId(OLIVE), GroupRole.OVERSEER, READER) }
    }

    /** The last role taken, they are answered holding nothing, which is what lets a role be given back. */
    def "takes a role, answering with how the group now holds them, holding nothing where it was the last"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))
        given(changes.take(new GroupId(GROUP), new SubjectId(OLIVE), GroupRole.OPERATOR, READER))
                .willReturn(OLIVE_HOLDING_NOTHING)

        when:
        def answered = sending(delete(roleAt(OLIVE.toString(), "operator")))

        then:
        answered.response.status == 200
        assertIsHowTheGroupHolds(documentOf(answered), [])

        and:
        changedOnlyBy { it.take(new GroupId(GROUP), new SubjectId(OLIVE), GroupRole.OPERATOR, READER) }
    }

    def "removes somebody from the group, answering with no content"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))

        when:
        def answered = sending(delete("/api/groups/${GROUP}/members/${OLIVE}"))

        then:
        answered.response.status == 204
        answered.response.contentAsString.isEmpty()

        and:
        changedOnlyBy { it.remove(new GroupId(GROUP), new SubjectId(OLIVE), READER) }
    }

    /** A role no role has names nothing here, whoever the address names, and is read before the person. */
    def "a role no role is spelt as addresses nothing, whoever the address names"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))

        when:
        def answered = sending(giving ? put(roleAt(person, role)) : delete(roleAt(person, role)))

        then:
        answered.response.status == 404
        documentOf(answered).get("code").asString() == "NOT_FOUND"

        and:
        Mockito.verifyNoInteractions(changes)

        where:
        giving | person           | role
        true   | OLIVE.toString() | "captain"
        false  | "not-a-person"   | "Owner"
    }

    /** Every change at a member's address is asked of a member, or of one taken back, so nobody is no member. */
    def "an address naming nobody is refused as no member, whichever change it asked"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))

        when:
        def answered = sending(asking(change, "not-a-person"))

        then:
        answered.response.status == 404
        documentOf(answered).get("code").asString() == "MEMBER_NOT_IN_VIEW"
        documentOf(answered).get("detail").asString() == "That person is not a member of this group."

        and:
        Mockito.verifyNoInteractions(changes)

        where:
        change << ["give", "take", "remove"]
    }

    def "a change taking nothing is refused a body or a parameter, before the store is asked anything"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))

        when:
        def answered = sending(sendingMore
                ? asking(change).queryParam("reason", "gone")
                : asking(change).contentType(MediaType.APPLICATION_JSON).content("{}"))

        then:
        answered.response.status == 400
        documentOf(answered).get("code").asString() == code

        and:
        Mockito.verifyNoInteractions(changes)

        where:
        change    | sendingMore || code
        "give"    | false       || "BODY_UNUSABLE"
        "take"    | false       || "BODY_UNUSABLE"
        "remove"  | false       || "BODY_UNUSABLE"
        "remove"  | true        || "PARAMETER_UNKNOWN"
        "bringIn" | true        || "PARAMETER_UNKNOWN"
    }

    /** A member who may not change it is refused for that; somebody holding nothing there sees no group. */
    def "refuses every change to anybody who may not change the membership, and never asks the store"() {
        given:
        holding(held)

        when:
        def answered = sending(asking(change))

        then:
        answered.response.status == status
        documentOf(answered).get("code").asString() == code

        and:
        Mockito.verifyNoInteractions(changes)

        where:
        [held, change] << [
                [EnumSet.of(GroupRole.OPERATOR, GroupRole.OVERSEER), EnumSet.noneOf(GroupRole)],
                ["bringIn", "give", "take", "remove"]
        ].combinations()
        status = held.isEmpty() ? 404 : 403
        code = held.isEmpty() ? "GROUP_NOT_IN_VIEW" : "ACT_NOT_PERMITTED"
    }

    /** Each change as the page asks it of somebody, built afresh for every request it is sent as. */
    private static MockHttpServletRequestBuilder asking(String change, String person = OLIVE.toString()) {
        switch (change) {
            case "bringIn":
                return post("/api/groups/${GROUP}/members").contentType(MediaType.APPLICATION_JSON)
                        .content('{"subjectId":"' + person + '","roles":["operator"]}')
            case "give":
                return put(roleAt(person, "operator"))
            case "take":
                return delete(roleAt(person, "operator"))
            default:
                return delete("/api/groups/${GROUP}/members/${person}")
        }
    }
}
