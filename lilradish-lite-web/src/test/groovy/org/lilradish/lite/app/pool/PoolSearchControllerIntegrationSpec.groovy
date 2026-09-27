package org.lilradish.lite.app.pool

import static org.mockito.ArgumentMatchers.any
import static org.mockito.ArgumentMatchers.anyInt
import static org.mockito.BDDMockito.given
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options

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
import org.lilradish.lite.domain.people.PeopleFound
import org.lilradish.lite.domain.people.PeopleSearch
import org.lilradish.lite.domain.people.PersonName
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import spock.lang.Specification
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * What a search of the pool receives, assembled over a real dispatcher: which members the answer
 * carries, the status and shape of a refusal, and whether the answer may be kept. Read as the raw
 * document, because an absent member and a member holding null are two different contracts.
 *
 * <p>The store is replaced, being the boundary this crosses to a database, and so is who is calling.
 * What the store finds is its own spec's question; this one asks what search the request was turned
 * into, and what the answer was turned into on the way out.
 */
@WebMvcTest(PoolSearchController)
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
class PoolSearchControllerIntegrationSpec extends Specification {

    static final UserId READER = new UserId("000001")

    static final JsonMapper JSON = JsonMapper.builder().build()

    static final UUID ADA = UUID.fromString("00000002-0000-4000-8000-000000000301")

    static final UUID NAMELESS = UUID.fromString("00000002-0000-4000-8000-000000000303")

    static final UUID GROUP = UUID.fromString("00000003-0000-4000-8000-000000000301")

    static final String SEARCH_REFUSED = "A search takes part of a name or a whole user number, on one line."

    static final String PARAMETER_REFUSED = "This search does not take that parameter."

    @Autowired
    private MockMvc mockMvc

    @MockitoBean
    private UserIdentification identification

    @MockitoBean
    private EstateRoleGrants grants

    @MockitoBean
    private GroupRoles groupRoles

    @MockitoBean
    private PoolPeople people

    private void arriving(Set<EstateRole> held) {
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(READER))
        given(grants.heldBy(READER)).willReturn(held)
    }

    /** Each pair a parameter sent once, so a name listed twice is a parameter sent twice. */
    private MvcResult searchingWith(List<List<String>> sent) {
        mockMvc.perform(sent.inject(get("/api/pool/search")) { request, parameter -> request.param(parameter[0], parameter[1]) })
                .andReturn()
    }

    private static JsonNode documentOf(MvcResult answered) {
        JSON.readTree(answered.response.contentAsString)
    }

    private static List<String> membersOf(JsonNode node) {
        node.propertyNames().toList()
    }

    /**
     * Each carries the identifier a group is created naming them by, and nothing about what they hold,
     * in the estate or in any group: the reader keeps the register, not the pool.
     */
    def "answers a caller who may keep the register of groups with who in the pool a search finds, and nothing they hold"() {
        given:
        arriving(EnumSet.of(EstateRole.STEWARD))
        given(people.search(new PeopleSearch("ada"), 20)).willReturn(new PeopleFound([
                new PoolPeople.InPool(new SubjectId(ADA), new UserId("000301"), new PersonName("Ada Lovelace")),
                new PoolPeople.InPool(new SubjectId(NAMELESS), new UserId("000303"), null)], more))

        when:
        def answered = searchingWith([["search", "ada"]])
        def document = documentOf(answered)

        then:
        answered.response.status == 200
        membersOf(document) == ["items", "more"]
        document.get("more").asBoolean() == more

        and: "each under the members the reader parses, a name only where one is held"
        membersOf(document.get("items").get(0)) == ["subjectId", "userId", "displayName"]
        membersOf(document.get("items").get(1)) == ["subjectId", "userId"]
        document.get("items").collect { it.get("subjectId").asString() } == [ADA.toString(), NAMELESS.toString()]
        document.get("items").collect { it.get("userId").asString() } == ["000301", "000303"]
        document.get("items").get(0).get("displayName").asString() == "Ada Lovelace"

        and: "no role of any kind named anywhere in it"
        (EstateRole.values()*.published() + GroupRole.values()*.name()*.toLowerCase(Locale.ROOT)).every {
            !answered.response.contentAsString.toLowerCase(Locale.ROOT).contains(it)
        }

        and: "nothing on it may be kept by anything between"
        answered.response.getHeader("Cache-Control") == "no-store"

        where:
        more << [false, true]
    }

    def "answers a search finding nobody with no items, which is an answer and not a refusal"() {
        given:
        arriving(EnumSet.of(EstateRole.STEWARD))
        given(people.search(any(PeopleSearch), anyInt())).willReturn(new PeopleFound([], false))

        when:
        def answered = searchingWith([["search", "nobody holds this"]])

        then:
        answered.response.status == 200
        documentOf(answered).get("items").isEmpty()
        !documentOf(answered).get("more").asBoolean()
    }

    /** Nothing sent is trimmed, folded or read as a pattern here: whatever was typed is the search. */
    def "hands the store exactly what was typed, and never more people than the most it shows"() {
        given:
        arriving(EnumSet.of(EstateRole.STEWARD))
        given(people.search(any(PeopleSearch), anyInt())).willReturn(new PeopleFound([], false))

        when:
        def answered = searchingWith([["search", typed]])

        then:
        answered.response.status == 200
        storeAskedOnlyFor(new PeopleSearch(typed))

        where:
        typed << [" Ada ", "000301", "%_\\", "山田" + Character.toString(0x3000) + "太郎"]
    }

    def "refuses a search it cannot read as asked, and never asks the store"() {
        given:
        arriving(EnumSet.of(EstateRole.STEWARD))

        when:
        def answered = searchingWith(sent)

        then:
        answered.response.status == 400
        answered.response.contentType.startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
        documentOf(answered).get("code").asString() == code
        documentOf(answered).get("detail").asString() == detail
        answered.response.getHeader("Cache-Control") == "no-store"

        and:
        Mockito.verifyNoInteractions(people)

        where:
        sent                                                || code                     | detail
        []                                                  || "PEOPLE_SEARCH_UNUSABLE" | SEARCH_REFUSED
        [["search", ""]]                                    || "PEOPLE_SEARCH_UNUSABLE" | SEARCH_REFUSED
        [["search", "   "]]                                 || "PEOPLE_SEARCH_UNUSABLE" | SEARCH_REFUSED
        [["search", "Ada" + Character.toString(0x2028)]]    || "PEOPLE_SEARCH_UNUSABLE" | SEARCH_REFUSED
        [["search", "a" * 257]]                             || "PEOPLE_SEARCH_UNUSABLE" | SEARCH_REFUSED
        [["search", "ada"], ["search", "grace"]]            || "PEOPLE_SEARCH_UNUSABLE" | SEARCH_REFUSED
        [["q", "ada"]]                                      || "PARAMETER_UNKNOWN"      | PARAMETER_REFUSED
        [["search", "ada"], ["filter", "grace"]]            || "PARAMETER_UNKNOWN"      | PARAMETER_REFUSED
    }

    /** The search belongs to creating a group, so the act that creates one is what reaches it. */
    def "refuses a caller whose roles do not reach the register of groups, before the store is asked anything"() {
        given:
        arriving(held)

        when:
        def answered = searchingWith([["search", "ada"]])

        then:
        answered.response.status == 403
        documentOf(answered).get("code").asString() == "ACT_NOT_PERMITTED"

        and:
        Mockito.verifyNoInteractions(people)

        where:
        held << [EnumSet.of(EstateRole.WATCHER), EnumSet.noneOf(EstateRole)]
    }

    def "answers OPTIONS with the methods the search takes, touching nothing"() {
        given:
        arriving(EnumSet.of(EstateRole.WATCHER))

        when:
        def answered = mockMvc.perform(options("/api/pool/search")).andReturn()

        then:
        answered.response.status == 200
        answered.response.getHeader("Allow").split(",").collect { it.trim() } as Set == ["GET", "HEAD", "OPTIONS"] as Set

        and:
        Mockito.verifyNoInteractions(people)
    }

    /**
     * Inside a group the search is a member's who may change its membership, whatever they hold in the
     * estate, and it is asked of the group the gate admitted them into.
     */
    def "answers a member who may change a group's membership with whom the pool's search finds outside that group"() {
        given:
        inGroup(EnumSet.of(GroupRole.OWNER))
        given(people.searchOutside(new GroupId(GROUP), new PeopleSearch("ada"), 20)).willReturn(new PeopleFound([
                new PoolPeople.InPool(new SubjectId(ADA), new UserId("000301"), new PersonName("Ada Lovelace"))], true))

        when:
        def answered = searchingIn(GROUP.toString(), [["search", "ada"]])
        def document = documentOf(answered)

        then:
        answered.response.status == 200
        membersOf(document) == ["items", "more"]
        document.get("more").asBoolean()
        document.get("items").collect { membersOf(it) } == [["subjectId", "userId", "displayName"]]
        document.get("items").get(0).get("subjectId").asString() == ADA.toString()

        and: "the pool's own search never asked, nor what the caller holds in the estate"
        storeAskedOnlyOutside(new GroupId(GROUP), new PeopleSearch("ada"))
        Mockito.verifyNoInteractions(grants)

        and:
        answered.response.getHeader("Cache-Control") == "no-store"
    }

    /** Refused before anything reaches the store, a search nobody may ask being no search at all. */
    def "refuses a search inside a group to anybody who may not change its membership there, and to anybody not in it"() {
        given:
        inGroup(held)

        when:
        def answered = searchingIn(GROUP.toString(), [["search", "ada"]])

        then:
        answered.response.status == status
        documentOf(answered).get("code").asString() == code

        and:
        Mockito.verifyNoInteractions(people)

        where:
        held                                               || status | code
        EnumSet.of(GroupRole.OPERATOR, GroupRole.OVERSEER) || 403    | "ACT_NOT_PERMITTED"
        EnumSet.noneOf(GroupRole)                          || 404    | "GROUP_NOT_IN_VIEW"
    }

    def "refuses a search inside a group it cannot read as asked, and never asks the store"() {
        given:
        inGroup(EnumSet.of(GroupRole.OWNER))

        when:
        def answered = searchingIn(GROUP.toString(), sent)

        then:
        answered.response.status == 400
        documentOf(answered).get("code").asString() == code

        and:
        Mockito.verifyNoInteractions(people)

        where:
        sent                                     || code
        []                                       || "PEOPLE_SEARCH_UNUSABLE"
        [["search", "ada"], ["search", "ada"]]   || "PEOPLE_SEARCH_UNUSABLE"
        [["search", "ada"], ["filter", "grace"]] || "PARAMETER_UNKNOWN"
    }

    /** Void, so the verification is what fails rather than the null a mock's answer would assert as. */
    private void storeAskedOnlyFor(PeopleSearch search) {
        Mockito.verify(people).search(search, 20)
        Mockito.verifyNoMoreInteractions(people)
    }

    private void storeAskedOnlyOutside(GroupId group, PeopleSearch search) {
        Mockito.verify(people).searchOutside(group, search, 20)
        Mockito.verifyNoMoreInteractions(people)
    }

    private void inGroup(Set<GroupRole> held) {
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(READER))
        given(grants.heldBy(READER)).willReturn(EnumSet.allOf(EstateRole))
        given(groupRoles.heldBy(READER, new GroupId(GROUP))).willReturn(held)
    }

    private MvcResult searchingIn(String group, List<List<String>> sent) {
        mockMvc.perform(sent.inject(get("/api/groups/" + group + "/pool/search")) { request, parameter ->
            request.param(parameter[0], parameter[1])
        }).andReturn()
    }
}
