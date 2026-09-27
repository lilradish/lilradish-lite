package org.lilradish.lite.app.members

import static org.mockito.ArgumentMatchers.any
import static org.mockito.ArgumentMatchers.eq
import static org.mockito.ArgumentMatchers.isNull
import static org.mockito.BDDMockito.given
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get

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
import org.lilradish.lite.domain.listing.ListCursor
import org.lilradish.lite.domain.listing.ListFilter
import org.lilradish.lite.domain.listing.ListOrder
import org.lilradish.lite.domain.listing.ListPage
import org.lilradish.lite.domain.listing.ListPosition
import org.lilradish.lite.domain.listing.ListQuery
import org.lilradish.lite.domain.members.MemberSortColumn
import org.lilradish.lite.domain.people.PersonName
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import spock.lang.Specification
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * What a group's members and one member of it are answered with, over a real dispatcher: the members
 * each answer carries, which query of which group the request was turned into, and every refusal met
 * before the store is asked. Every controller of the members is loaded.
 *
 * <p>The store is replaced, being the boundary this crosses to a database, and so is who is calling and
 * what they hold in the group. What the store reads is its own spec's question.
 */
@WebMvcTest([MembersController, MembershipChangesController])
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
class MembersControllerIntegrationSpec extends Specification {

    static final UserId READER = new UserId("000001")

    static final JsonMapper JSON = JsonMapper.builder().build()

    static final UUID GROUP = UUID.fromString("00000003-0000-4000-8000-000000000e01")

    static final UUID OTHER_GROUP = UUID.fromString("00000003-0000-4000-8000-000000000e02")

    static final UUID ADA = UUID.fromString("00000002-0000-4000-8000-000000000e01")

    static final UUID NAMELESS = UUID.fromString("00000002-0000-4000-8000-000000000e02")

    static final ListOrder<MemberSortColumn> BY_USER = new ListOrder<>(MemberSortColumn.USER_ID, false)

    static final Members.MemberRow ADA_ROW = new Members.MemberRow(new SubjectId(ADA), new UserId("000e01"),
            new PersonName("Ada Lovelace"), EnumSet.of(GroupRole.OPERATOR, GroupRole.OWNER))

    static final Members.MemberRow NAMELESS_ROW =
            new Members.MemberRow(new SubjectId(NAMELESS), new UserId("000e02"), null, EnumSet.of(GroupRole.OVERSEER))

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

    private MvcResult listing(UUID group, List<List<String>> sent) {
        mockMvc.perform(sent.inject(get("/api/groups/" + group + "/members")) { request, parameter ->
            request.param(parameter[0], parameter[1])
        }).andReturn()
    }

    private static JsonNode documentOf(MvcResult answered) {
        JSON.readTree(answered.response.contentAsString)
    }

    private static List<String> membersOf(JsonNode node) {
        node.propertyNames().toList()
    }

    private static ListQuery<MemberSortColumn> within(UUID group, ListOrder<MemberSortColumn> order, String typed) {
        new ListQuery<>(group.toString(), order, typed == null ? null : new ListFilter(typed))
    }

    /** Read by every role that may see the members, and never through what the caller holds in the estate. */
    def "answers a member who may see the members with them, each with every role they hold there and a name only where one is held"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))
        given(members.page(within(GROUP, BY_USER, null), null)).willReturn(new ListPage([ADA_ROW, NAMELESS_ROW], null))

        when:
        def answered = listing(GROUP, [])
        def document = documentOf(answered)

        then:
        answered.response.status == 200
        membersOf(document) == ["items"]
        document.get("items").collect { membersOf(it) } ==
                [["subjectId", "userId", "displayName", "roles"], ["subjectId", "userId", "roles"]]
        document.get("items").collect { it.get("subjectId").asString() } == [ADA.toString(), NAMELESS.toString()]
        document.get("items").get(0).get("displayName").asString() == "Ada Lovelace"

        and: "each role under the spelling it is published by, every one of them and nothing standing in"
        document.get("items").collect { item -> item.get("roles").collect { it.asString() } as Set } ==
                [["operator", "owner"] as Set, ["overseer"] as Set]

        and: "nothing on it may be kept by anything between"
        answered.response.getHeader("Cache-Control") == "no-store"

        and:
        Mockito.verifyNoInteractions(grants)
    }

    /** The query is the group admitted, and the filter spaced as a name is before anything is bound to it. */
    def "hands the store a query within the group admitted, in the order and with the filter asked"() {
        given:
        holding(EnumSet.of(GroupRole.OVERSEER))
        given(members.page(any(ListQuery), isNull())).willReturn(new ListPage([], null))

        when:
        def answered = listing(GROUP, sent)

        then:
        answered.response.status == 200

        and:
        readOnlyBy { it.page(within(GROUP, order, typed), null) }

        where:
        sent                                                           || order                                                | typed
        []                                                             || BY_USER                                              | null
        [["sort", "-displayName"]]                                     || new ListOrder<>(MemberSortColumn.DISPLAY_NAME, true) | null
        [["filter", "Ada" + Character.toString(0x3000) + "Lovelace"]]  || BY_USER                                              | "Ada Lovelace"
    }

    /** A cursor names the group it was minted in, so it pages through that group or through none. */
    def "hands out where the next page begins, and refuses it within any other group"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))
        given(groupRoles.heldBy(READER, new GroupId(OTHER_GROUP))).willReturn(EnumSet.of(GroupRole.OWNER))
        def ended = new ListPosition("000e01", "000e01")
        given(members.page(within(GROUP, BY_USER, null), null)).willReturn(new ListPage([ADA_ROW], ended))

        when:
        def cursor = documentOf(listing(GROUP, [])).get("nextCursor").asString()
        def elsewhere = listing(OTHER_GROUP, [["cursor", cursor]])

        then:
        cursor == ListCursor.mint(Members.LISTED, within(GROUP, BY_USER, null), ended)

        and:
        elsewhere.response.status == 400
        documentOf(elsewhere).get("code").asString() == "LIST_CURSOR_UNUSABLE"

        and: "the other group's members never read"
        readOnlyBy { it.page(within(GROUP, BY_USER, null), null) }
    }

    def "refuses a list it cannot read as asked, and never asks the store"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))

        when:
        def answered = listing(GROUP, sent)

        then:
        answered.response.status == 400
        documentOf(answered).get("code").asString() == code

        and:
        Mockito.verifyNoInteractions(members)

        where:
        sent                  || code
        [["sort", "roles"]]   || "LIST_SORT_UNUSABLE"
        [["sort", "groups"]]  || "LIST_SORT_UNUSABLE"
        [["search", "ada"]]   || "PARAMETER_UNKNOWN"
        [["cursor", "nope"]]  || "LIST_CURSOR_UNUSABLE"
    }

    /** A group the caller holds nothing in is not refused for a permission: it is not there to them. */
    def "refuses the members to anybody holding no role in the group, before the store is asked anything"() {
        given:
        holding(EnumSet.noneOf(GroupRole))

        when:
        def answered = listing(GROUP, [])

        then:
        answered.response.status == 404
        documentOf(answered).get("code").asString() == "GROUP_NOT_IN_VIEW"

        and:
        Mockito.verifyNoInteractions(members)
    }

    def "answers a member of the group with what the group holds of them, the roles no other could change membership without among it"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))
        given(members.member(new GroupId(GROUP), new SubjectId(ADA))).willReturn(Optional.of(new Members.Member(
                new SubjectId(ADA), new UserId("000e01"), new PersonName("Ada Lovelace"),
                EnumSet.of(GroupRole.OPERATOR, GroupRole.OWNER), EnumSet.of(GroupRole.OWNER), false)))

        when:
        def answered = mockMvc.perform(get("/api/groups/${GROUP}/members/${ADA}")).andReturn()
        def document = documentOf(answered)

        then:
        answered.response.status == 200
        membersOf(document) == ["subjectId", "userId", "displayName", "roles", "lastChangingRoles", "removable"]
        document.get("roles").collect { it.asString() } as Set == ["operator", "owner"] as Set
        document.get("lastChangingRoles").collect { it.asString() } == ["owner"]
        document.get("userId").asString() == "000e01"

        and: "not offered for removal, the one who may change the membership"
        !document.get("removable").asBoolean()
    }

    /** An identifier that is none, and somebody the group does not hold now, are one refusal. */
    def "refuses a member the group does not hold, and an address that names nobody, alike"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))

        when:
        def answered = mockMvc.perform(get("/api/groups/${GROUP}/members/${addressed}")).andReturn()

        then:
        answered.response.status == 404
        documentOf(answered).get("code").asString() == "MEMBER_NOT_IN_VIEW"
        documentOf(answered).get("detail").asString() == "That person is not a member of this group."

        and: "an address naming nobody never asked of the store"
        memberAskedFor(asked)

        where:
        addressed        || asked
        ADA.toString()   || 1
        "not-a-person"   || 0
    }

    /** Void, so the verification is what fails rather than the null a mock's answer would assert as. */
    private void readOnlyBy(Closure<?> asked) {
        asked(Mockito.verify(members))
        Mockito.verifyNoMoreInteractions(members)
    }

    private void memberAskedFor(int times) {
        Mockito.verify(members, Mockito.times(times)).member(eq(new GroupId(GROUP)), any(SubjectId))
        Mockito.verifyNoMoreInteractions(members)
    }
}
