package org.lilradish.lite.app.groupregister

import static org.mockito.ArgumentMatchers.any
import static org.mockito.ArgumentMatchers.isNull
import static org.mockito.BDDMockito.given
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options

import jakarta.servlet.http.HttpServletRequest
import org.libprunus.spring.error.ApiErrorAutoConfiguration
import org.lilradish.lite.app.estate.EstateRoleGrants
import org.lilradish.lite.app.identification.UserIdentification
import org.lilradish.lite.domain.groupregister.GroupSortColumn
import org.lilradish.lite.domain.identity.EstateRole
import org.lilradish.lite.domain.identity.GroupId
import org.lilradish.lite.domain.identity.GroupKey
import org.lilradish.lite.domain.identity.GroupName
import org.lilradish.lite.domain.identity.GroupRole
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.listing.ListCursor
import org.lilradish.lite.domain.listing.ListFilter
import org.lilradish.lite.domain.listing.ListOrder
import org.lilradish.lite.domain.listing.ListPage
import org.lilradish.lite.domain.listing.ListPosition
import org.lilradish.lite.domain.listing.ListQuery
import org.lilradish.lite.testutil.GroupRolesStoodIn
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
 * What a keeper of the register receives, assembled over a real dispatcher: which members the answer
 * carries, the status and shape of a refusal, and whether an answer may be kept. Read as the raw
 * document throughout. Every controller of the register is loaded, so the methods an address is said
 * to take are all the methods it takes.
 *
 * <p>The store is replaced, being the boundary this crosses to a database, and so is who is calling.
 * What the store answers for a given query is its own spec's question; this one asks what query the
 * request was turned into, and what the answer was turned into on the way out.
 */
@WebMvcTest([GroupRegisterController, GroupChangesController])
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
@GroupRolesStoodIn
class GroupRegisterControllerIntegrationSpec extends Specification {

    static final UserId READER = new UserId("000001")

    static final JsonMapper JSON = JsonMapper.builder().build()

    static final String IDEOGRAPHIC_SPACE = Character.toString(0x3000)

    static final ListOrder<GroupSortColumn> BY_NAME = new ListOrder<>(GroupSortColumn.NAME, false)

    static final ListOrder<GroupSortColumn> BY_KEY = new ListOrder<>(GroupSortColumn.KEY, false)

    static final ListOrder<GroupSortColumn> BY_ADMINISTERED_DESCENDING =
            new ListOrder<>(GroupSortColumn.CAN_BE_ADMINISTERED, true)

    static final ListOrder<GroupSortColumn> BY_MEMBERS = new ListOrder<>(GroupSortColumn.MEMBER_COUNT, false)

    static final UUID PAYROLL_ID = UUID.fromString("00000003-0000-4000-8000-000000000701")

    static final UUID VOID_ID = UUID.fromString("00000003-0000-4000-8000-000000000704")

    static final GroupRegister.GroupRow PAYROLL = new GroupRegister.GroupRow(
            new GroupId(PAYROLL_ID), new GroupKey("PAYROLL"), new GroupName("Payroll"), true, 2)

    static final GroupRegister.GroupRow VOID = new GroupRegister.GroupRow(
            new GroupId(VOID_ID), new GroupKey("VOID"), new GroupName("Empty room"), false, 0)

    static final ListPosition ENDED_ON = new ListPosition("Payroll", "PAYROLL")

    static final String MINTED_FOR_THIS_QUERY = ListCursor.mint(GroupRegister.LISTED, query(BY_NAME, null), ENDED_ON)

    static final String MINTED_BY_ANOTHER_ORDER =
            ListCursor.mint(GroupRegister.LISTED, query(BY_KEY, null), new ListPosition("PAYROLL", "PAYROLL"))

    static final String MINTED_UNDER_ANOTHER_FILTER =
            ListCursor.mint(GroupRegister.LISTED, query(BY_NAME, new ListFilter("pay")), ENDED_ON)

    static final String PARAMETER_REFUSED = "This list does not take that parameter."

    static final String SORT_REFUSED = "This list is sorted by one sortable column at a time."

    static final String FILTER_REFUSED = "That filter is too long, or holds a character that cannot be searched for."

    static final String CURSOR_REFUSED = "That position does not belong to this query of the list."

    @Autowired
    private MockMvc mockMvc

    @MockitoBean
    private UserIdentification identification

    @MockitoBean
    private EstateRoleGrants grants

    @MockitoBean
    private GroupRegister register

    @MockitoBean
    private GroupChanges changes

    private void arriving(Set<EstateRole> held) {
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(READER))
        given(grants.heldBy(READER)).willReturn(held)
    }

    private MvcResult asking(MockHttpServletRequestBuilder request) {
        mockMvc.perform(request).andReturn()
    }

    /** Each pair a parameter sent once, so a name listed twice is a parameter sent twice. */
    private MvcResult askingForTheRegisterWith(List<List<String>> sent) {
        asking(sent.inject(get("/api/groups")) { request, parameter -> request.param(parameter[0], parameter[1]) })
    }

    private static JsonNode documentOf(MvcResult answered) {
        JSON.readTree(answered.response.contentAsString)
    }

    private static List<String> membersOf(JsonNode node) {
        node.propertyNames().toList()
    }

    /**
     * A row says whether a group can be administered and how many are in it, and never who: nobody in
     * it, none of their roles, and nobody who made it — a seeded group was made by nobody, and no row
     * carries a mark saying which were.
     */
    def "answers a caller who may keep the register with a page of it, each row a fact about a group that names nobody"() {
        given:
        arriving(EnumSet.of(EstateRole.STEWARD))
        given(register.page(query(BY_NAME, null), null)).willReturn(new ListPage<>([PAYROLL, VOID], null))

        when:
        def answered = asking(get("/api/groups"))
        def items = documentOf(answered).get("items")

        then:
        answered.response.status == 200
        membersOf(documentOf(answered)) == ["items"]

        and: "each row under the members the reader parses, and no other"
        items.every { membersOf(it) == ["groupId", "key", "name", "canBeAdministered", "memberCount"] }
        items.collect { it.get("groupId").asString() } == [PAYROLL_ID.toString(), VOID_ID.toString()]
        items.collect { it.get("key").asString() } == ["PAYROLL", "VOID"]
        items.collect { it.get("name").asString() } == ["Payroll", "Empty room"]
        items.collect { it.get("canBeAdministered").asBoolean() } == [true, false]
        items.collect { it.get("memberCount").asLong() } == [2L, 0L]

        and: "no role, no creator and no seeding named anywhere in it"
        GroupRole.values().every { !answered.response.contentAsString.toLowerCase(Locale.ROOT).contains(it.name().toLowerCase(Locale.ROOT)) }
        !answered.response.contentAsString.contains("created")
        !answered.response.contentAsString.contains("seed")

        and:
        answered.response.getHeader("Cache-Control") == "no-store"
    }

    def "tells a reader where the next page begins, under a cursor the same query goes on to honour"() {
        given:
        arriving(EnumSet.of(EstateRole.STEWARD))
        given(register.page(query(BY_NAME, null), null)).willReturn(new ListPage<>([VOID, PAYROLL], ENDED_ON))
        given(register.page(query(BY_NAME, null), ENDED_ON)).willReturn(new ListPage<>([], null))

        when:
        def cursor = documentOf(asking(get("/api/groups"))).get("nextCursor").asString()
        def followed = askingForTheRegisterWith([["cursor", cursor]])

        then:
        cursor == MINTED_FOR_THIS_QUERY
        followed.response.status == 200

        and: "the store asked for what follows exactly the group the first page ended on"
        storeAskedFor(query(BY_NAME, null), ENDED_ON)
    }

    /**
     * Nothing sent is trimmed, folded or read as a pattern here, only spaced as names are: a run of
     * whitespace is one space, at either end too, and all else reaches the store as itself.
     */
    def "turns what was sent into exactly the query it asked for, and asks the store for that"() {
        given:
        arriving(EnumSet.of(EstateRole.STEWARD))
        given(register.page(any(ListQuery), isNull())).willReturn(new ListPage<>([], null))

        when:
        def answered = askingForTheRegisterWith(sent)

        then:
        answered.response.status == 200
        storeAskedFor(asked, null)
        Mockito.verifyNoMoreInteractions(register)

        where:
        sent                                                        || asked
        []                                                          || query(BY_NAME, null)
        [["sort", "key"]]                                           || query(BY_KEY, null)
        [["sort", "-canBeAdministered"]]                            || query(BY_ADMINISTERED_DESCENDING, null)
        [["sort", "memberCount"], ["filter", " Pay%_\\ "]]          || query(BY_MEMBERS, new ListFilter(" Pay%_\\ "))
        [["filter", "Risk" + IDEOGRAPHIC_SPACE]]                    || query(BY_NAME, new ListFilter("Risk "))
        [["filter", "Risk" + IDEOGRAPHIC_SPACE + " Controls"]]      || query(BY_NAME, new ListFilter("Risk Controls"))
        [["filter", ""]]                                            || query(BY_NAME, null)
    }

    def "refuses a query it cannot read as asked, and never asks the store"() {
        given:
        arriving(EnumSet.of(EstateRole.STEWARD))

        when:
        def answered = askingForTheRegisterWith(sent)

        then:
        answered.response.status == 400
        answered.response.contentType.startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
        documentOf(answered).get("code").asString() == code
        documentOf(answered).get("detail").asString() == detail
        answered.response.getHeader("Cache-Control") == "no-store"

        and:
        Mockito.verifyNoInteractions(register)

        where:
        sent                                              || code                     | detail
        [["srot", "key"]]                                 || "PARAMETER_UNKNOWN"      | PARAMETER_REFUSED
        [["sort", "members"]]                             || "LIST_SORT_UNUSABLE"     | SORT_REFUSED
        [["sort", "key,name"]]                            || "LIST_SORT_UNUSABLE"     | SORT_REFUSED
        [["sort", ""]]                                    || "LIST_SORT_UNUSABLE"     | SORT_REFUSED
        [["filter", "Pay" + Character.toString(0x2028)]]  || "LIST_FILTER_UNUSABLE"   | FILTER_REFUSED
        [["filter", "a" * 257]]                           || "LIST_FILTER_UNUSABLE"   | FILTER_REFUSED
        [["filter", "a"], ["filter", "b"]]                || "LIST_FILTER_UNUSABLE"   | FILTER_REFUSED
        [["cursor", "not a cursor"]]                      || "LIST_CURSOR_UNUSABLE"   | CURSOR_REFUSED
        [["cursor", MINTED_BY_ANOTHER_ORDER]]             || "LIST_CURSOR_UNUSABLE"   | CURSOR_REFUSED
        [["cursor", MINTED_UNDER_ANOTHER_FILTER]]         || "LIST_CURSOR_UNUSABLE"   | CURSOR_REFUSED
    }

    def "refuses a caller whose roles do not reach the register, before the store is asked anything"() {
        given:
        arriving(held)

        when:
        def answered = asking(get("/api/groups"))

        then:
        answered.response.status == 403
        documentOf(answered).get("code").asString() == "ACT_NOT_PERMITTED"
        answered.response.getHeader("Cache-Control") == "no-store"

        and:
        Mockito.verifyNoInteractions(register)

        where:
        held << [EnumSet.of(EstateRole.WATCHER), EnumSet.noneOf(EstateRole)]
    }

    def "refuses a caller nobody identified as not signed in, before anything else is asked"() {
        given:
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.empty())

        when:
        def answered = asking(get("/api/groups"))

        then:
        answered.response.status == 401
        documentOf(answered).get("code").asString() == "NOT_SIGNED_IN"

        and:
        Mockito.verifyNoInteractions(register)
        Mockito.verifyNoInteractions(grants)
    }

    /** The register is read and a group created at one address; a group is renamed at its own, and never read there. */
    def "answers OPTIONS with the methods each address of the register takes, touching nothing"() {
        given:
        arriving(EnumSet.of(EstateRole.WATCHER))

        when:
        def answered = asking(options(address))

        then:
        answered.response.status == 200
        (answered.response.getHeader("Allow").split(",").collect { it.trim() } as Set) == allowed

        and:
        Mockito.verifyNoInteractions(register, changes)

        where:
        address                      || allowed
        "/api/groups"                || ["GET", "HEAD", "POST", "OPTIONS"] as Set
        "/api/groups/" + PAYROLL_ID  || ["PATCH", "OPTIONS"] as Set
    }

    private static ListQuery<GroupSortColumn> query(ListOrder<GroupSortColumn> order, ListFilter filter) {
        new ListQuery<>(GroupRegister.WHOLE_REGISTER, order, filter)
    }

    /** Void, so the verification is what fails rather than the null a mock's answer would assert as. */
    private void storeAskedFor(ListQuery<GroupSortColumn> query, ListPosition after) {
        Mockito.verify(register).page(query, after)
    }
}
