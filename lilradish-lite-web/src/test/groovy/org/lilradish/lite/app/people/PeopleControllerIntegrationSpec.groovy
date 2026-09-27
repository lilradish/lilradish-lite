package org.lilradish.lite.app.people

import static org.mockito.ArgumentMatchers.any
import static org.mockito.ArgumentMatchers.anyInt
import static org.mockito.BDDMockito.given
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options

import jakarta.servlet.http.HttpServletRequest
import org.libprunus.spring.error.ApiErrorAutoConfiguration
import org.lilradish.lite.app.estate.EstateRoleGrants
import org.lilradish.lite.app.identification.UserIdentification
import org.lilradish.lite.domain.identity.EstateRole
import org.lilradish.lite.domain.identity.SubjectId
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.people.PeopleFound
import org.lilradish.lite.domain.people.PeopleSearch
import org.lilradish.lite.domain.people.PersonName
import org.lilradish.lite.testutil.GroupRolesStoodIn
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
 * What a search of the directory receives, assembled over a real dispatcher: which members
 * the answer carries, the status and shape of a refusal, and whether the answer may be kept. Read as
 * the raw document, because an absent member and a member holding null are two different contracts.
 *
 * <p>The store is replaced, being the boundary this crosses to a database, and so is who is calling.
 * What the store finds for a search is its own spec's question; this one asks what search the request
 * was turned into, and what the answer was turned into on the way out.
 */
@WebMvcTest(PeopleController)
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
@GroupRolesStoodIn
class PeopleControllerIntegrationSpec extends Specification {

    static final UserId READER = new UserId("000001")

    static final JsonMapper JSON = JsonMapper.builder().build()

    static final UUID POOLED = UUID.fromString("00000002-0000-4000-8000-000000000301")

    static final String SEARCH_REFUSED = "A search takes part of a name or a whole user number, on one line."

    static final String PARAMETER_REFUSED = "This search does not take that parameter."

    @Autowired
    private MockMvc mockMvc

    @MockitoBean
    private UserIdentification identification

    @MockitoBean
    private EstateRoleGrants grants

    @MockitoBean
    private People people

    private void arriving(Set<EstateRole> held) {
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(READER))
        given(grants.heldBy(READER)).willReturn(held)
    }

    /** Each pair a parameter sent once, so a name listed twice is a parameter sent twice. */
    private MvcResult searchingWith(List<List<String>> sent) {
        mockMvc.perform(sent.inject(get("/api/people")) { request, parameter -> request.param(parameter[0], parameter[1]) })
                .andReturn()
    }

    private static JsonNode documentOf(MvcResult answered) {
        JSON.readTree(answered.response.contentAsString)
    }

    private static List<String> membersOf(JsonNode node) {
        node.propertyNames().toList()
    }

    /**
     * Somebody in the pool carries the identifier that addresses them there, which is how they are
     * said to be in it, and somebody not in it carries no such member at all rather than one holding null.
     */
    def "answers a caller who may keep the pool with who a search finds, each saying whether they are in it now"() {
        given:
        arriving(EnumSet.of(EstateRole.STEWARD))
        given(people.search(new PeopleSearch("ada"), 20)).willReturn(new PeopleFound([
                new People.InDirectory(new UserId("000301"), new PersonName("Ada Lovelace"), new SubjectId(POOLED)),
                new People.InDirectory(new UserId("000302"), new PersonName("Ada Yonath"), null)], more))

        when:
        def answered = searchingWith([["search", "ada"]])
        def document = documentOf(answered)

        then:
        answered.response.status == 200
        membersOf(document) == ["items", "more"]
        document.get("more").asBoolean() == more

        and: "each found under the members the reader parses, an address only for somebody in the pool"
        membersOf(document.get("items").get(0)) == ["userId", "displayName", "subjectId"]
        membersOf(document.get("items").get(1)) == ["userId", "displayName"]
        document.get("items").get(0).get("subjectId").asString() == POOLED.toString()
        document.get("items").collect { it.get("userId").asString() } == ["000301", "000302"]
        document.get("items").collect { it.get("displayName").asString() } == ["Ada Lovelace", "Ada Yonath"]

        and: "nothing on it may be kept by anything between"
        answered.response.getHeader("Cache-Control") == "no-store"

        where:
        more << [false, true]
    }

    /** Neither null nor empty stands in for a name the directory does not hold. */
    def "answers somebody found with no name without any name member, in the pool or not"() {
        given:
        arriving(EnumSet.of(EstateRole.STEWARD))
        given(people.search(new PeopleSearch("000303"), 20)).willReturn(new PeopleFound([
                new People.InDirectory(new UserId("000303"), null, pooled == null ? null : new SubjectId(pooled))], false))

        when:
        def document = documentOf(searchingWith([["search", "000303"]]))

        then:
        membersOf(document.get("items").get(0)) == members
        document.get("items").get(0).get("userId").asString() == "000303"

        where:
        pooled || members
        null   || ["userId"]
        POOLED || ["userId", "subjectId"]
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

    /**
     * Refused before the store is asked anything, with the code saying which part of the request
     * could not be read, in a sentence fixed per code: nothing that was sent comes back.
     */
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
        [["search", Character.toString(0x3000)]]            || "PEOPLE_SEARCH_UNUSABLE" | SEARCH_REFUSED
        [["search", "Ada" + Character.toString(0x2028)]]    || "PEOPLE_SEARCH_UNUSABLE" | SEARCH_REFUSED
        [["search", "a" * 257]]                             || "PEOPLE_SEARCH_UNUSABLE" | SEARCH_REFUSED
        [["search", "ada"], ["search", "grace"]]            || "PEOPLE_SEARCH_UNUSABLE" | SEARCH_REFUSED
        [["q", "ada"]]                                      || "PARAMETER_UNKNOWN"      | PARAMETER_REFUSED
        [["search", "ada"], ["filter", "grace"]]            || "PARAMETER_UNKNOWN"      | PARAMETER_REFUSED
    }

    /**
     * Finding somebody in the directory is the first half of bringing them in, and only a caller who
     * keeps the pool is owed a look at it.
     */
    def "refuses a caller whose roles do not reach keeping the pool, before the store is asked anything"() {
        given:
        arriving(held)

        when:
        def answered = searchingWith([["search", "ada"]])

        then:
        answered.response.status == 403
        documentOf(answered).get("code").asString() == "ACT_NOT_PERMITTED"
        answered.response.getHeader("Cache-Control") == "no-store"

        and:
        Mockito.verifyNoInteractions(people)

        where:
        held << [EnumSet.of(EstateRole.WATCHER), EnumSet.noneOf(EstateRole)]
    }

    def "refuses a caller nobody identified as not signed in, before anything else is asked"() {
        given:
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.empty())

        when:
        def answered = searchingWith([["search", "ada"]])

        then:
        answered.response.status == 401
        documentOf(answered).get("code").asString() == "NOT_SIGNED_IN"

        and:
        Mockito.verifyNoInteractions(people)
        Mockito.verifyNoInteractions(grants)
    }

    def "answers OPTIONS with the methods the search takes, touching nothing"() {
        given:
        arriving(EnumSet.of(EstateRole.WATCHER))

        when:
        def answered = mockMvc.perform(options("/api/people")).andReturn()

        then:
        answered.response.status == 200
        answered.response.getHeader("Allow").split(",").collect { it.trim() } as Set == ["GET", "HEAD", "OPTIONS"] as Set

        and:
        Mockito.verifyNoInteractions(people)
    }

    /** Void, so the verification is what fails rather than the null a mock's answer would assert as. */
    private void storeAskedOnlyFor(PeopleSearch search) {
        Mockito.verify(people).search(search, 20)
        Mockito.verifyNoMoreInteractions(people)
    }
}
