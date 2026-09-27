package org.lilradish.lite.app.pool

import static org.mockito.ArgumentMatchers.any
import static org.mockito.ArgumentMatchers.isNull
import static org.mockito.BDDMockito.given
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options

import jakarta.servlet.http.HttpServletRequest
import org.libprunus.spring.error.ApiErrorAutoConfiguration
import org.lilradish.lite.app.estate.EstateRoleGrants
import org.lilradish.lite.app.identification.UserIdentification
import org.lilradish.lite.domain.identity.EstateRole
import org.lilradish.lite.domain.identity.GroupName
import org.lilradish.lite.domain.identity.GroupRole
import org.lilradish.lite.domain.identity.SubjectId
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.listing.ListCursor
import org.lilradish.lite.domain.listing.ListFilter
import org.lilradish.lite.domain.listing.ListOrder
import org.lilradish.lite.domain.listing.ListPage
import org.lilradish.lite.domain.listing.ListPosition
import org.lilradish.lite.domain.listing.ListQuery
import org.lilradish.lite.domain.people.PersonName
import org.lilradish.lite.domain.pool.PoolSortColumn
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
 * What a steward's reader receives, assembled over a real dispatcher: which members the answer
 * carries and which it leaves out are decided by whatever serialises it, the status and the shape of
 * a refusal by the error outlet, and whether an answer may be stored by a header nothing below the
 * dispatcher writes. Read as the raw document throughout, because an absent member and a member
 * holding null parse alike into most things that read them and are two different contracts.
 *
 * <p>The store is replaced, being the boundary this crosses to a database, and so is who is calling,
 * for the reason {@code StandingEndpointIntegrationSpec} gives. What the store answers for a given
 * query is its own spec's question; this one asks what query the request was turned into, and what
 * the answer was turned into on the way out.
 */
@WebMvcTest(PoolPeopleController)
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
@GroupRolesStoodIn
class PoolPeopleControllerIntegrationSpec extends Specification {

    static final UserId READER = new UserId("000001")

    static final JsonMapper JSON = JsonMapper.builder().build()

    static final ListOrder<PoolSortColumn> BY_USER = new ListOrder<>(PoolSortColumn.USER_ID, false)

    static final ListOrder<PoolSortColumn> BY_NAME = new ListOrder<>(PoolSortColumn.DISPLAY_NAME, false)

    static final ListOrder<PoolSortColumn> BY_COUNT_DESCENDING = new ListOrder<>(PoolSortColumn.GROUP_COUNT, true)

    static final String IDEOGRAPHIC_SPACE = Character.toString(0x3000)

    static final UUID NAMED_SUBJECT = UUID.fromString("00000002-0000-4000-8000-000000000140")

    static final UUID NAMELESS_SUBJECT = UUID.fromString("00000002-0000-4000-8000-000000000130")

    static final PoolPeople.PoolPerson NAMED = new PoolPeople.PoolPerson(new SubjectId(NAMED_SUBJECT),
            new UserId("000140"), new PersonName("Grace Hopper"), EnumSet.allOf(EstateRole), 2)

    static final PoolPeople.PoolPerson NAMELESS = new PoolPeople.PoolPerson(new SubjectId(NAMELESS_SUBJECT),
            new UserId("000130"), null, EnumSet.noneOf(EstateRole), 0)

    static final ListPosition ENDED_ON = new ListPosition("000130", "000130")

    static final String MINTED_BY_ANOTHER_ORDER =
            ListCursor.mint(PoolPeople.LISTED, query(BY_NAME, null), new ListPosition(null, "000130"))

    static final String MINTED_UNDER_ANOTHER_FILTER =
            ListCursor.mint(PoolPeople.LISTED, query(BY_USER, new ListFilter("grace")), ENDED_ON)

    static final String MINTED_WITHIN_ANOTHER_SCOPE =
            ListCursor.mint(PoolPeople.LISTED, new ListQuery<>("group 7", BY_USER, null), ENDED_ON)

    static final String MINTED_FOR_THIS_QUERY = ListCursor.mint(PoolPeople.LISTED, query(BY_USER, null), ENDED_ON)

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
    private PoolPeople people

    private void arriving(Set<EstateRole> held) {
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(READER))
        given(grants.heldBy(READER)).willReturn(held)
    }

    private MvcResult asking(MockHttpServletRequestBuilder request) {
        mockMvc.perform(request).andReturn()
    }

    /** Each pair a parameter sent once, so a name listed twice is a parameter sent twice. */
    private MvcResult askingForThePoolWith(List<List<String>> sent) {
        asking(sent.inject(get("/api/pool/people")) { request, parameter -> request.param(parameter[0], parameter[1]) })
    }

    private static JsonNode documentOf(MvcResult answered) {
        JSON.readTree(answered.response.contentAsString)
    }

    private static List<String> membersOf(JsonNode node) {
        node.propertyNames().toList()
    }

    /**
     * The row without a name has no member for it rather than a null one, and the last page has no
     * cursor rather than a null one: a reader comparing against absence would otherwise find a name
     * of "null" and a further page at the end of every list.
     */
    def "answers a caller who may keep the pool with a page of it, leaving out every member that has nothing to say"() {
        given:
        arriving(EnumSet.of(EstateRole.STEWARD))
        given(people.page(query(BY_USER, null), null))
                .willReturn(new ListPage<>([NAMED, NAMELESS], null))

        when:
        def answered = asking(get("/api/pool/people"))
        def items = documentOf(answered).get("items")

        then:
        answered.response.status == 200
        membersOf(documentOf(answered)) == ["items"]
        !answered.response.contentAsString.contains("nextCursor")

        and: "each row under the members the reader parses, a name only where one is held"
        membersOf(items.get(0)) == ["subjectId", "userId", "displayName", "estateRoles", "groupCount"]
        membersOf(items.get(1)) == ["subjectId", "userId", "estateRoles", "groupCount"]

        and:
        items.get(0).get("subjectId").asString() == NAMED_SUBJECT.toString()
        items.get(0).get("userId").asString() == "000140"
        items.get(0).get("displayName").asString() == "Grace Hopper"
        items.get(0).get("estateRoles").collect { it.asString() } == ["steward", "watcher"]
        items.get(0).get("groupCount").asLong() == 2
        items.get(1).get("estateRoles").isEmpty()
        items.get(1).get("groupCount").asLong() == 0

        and: "and nothing on it may be kept by anything between"
        answered.response.getHeader("Cache-Control") == "no-store"
    }

    def "tells a reader where the next page begins, under a cursor the same query goes on to honour"() {
        given:
        arriving(EnumSet.of(EstateRole.STEWARD))
        given(people.page(query(BY_USER, null), null))
                .willReturn(new ListPage<>([NAMED, NAMELESS], ENDED_ON))
        given(people.page(query(BY_USER, null), ENDED_ON))
                .willReturn(new ListPage<>([], null))

        when:
        def cursor = documentOf(asking(get("/api/pool/people"))).get("nextCursor").asString()
        def followed = askingForThePoolWith([["cursor", cursor]])

        then:
        cursor == MINTED_FOR_THIS_QUERY
        followed.response.status == 200

        and: "the store asked for what follows exactly the row the first page ended on"
        storeAskedFor(query(BY_USER, null), ENDED_ON)
    }

    /**
     * Nothing sent is trimmed, folded or read as a pattern here, only spaced as names are: a run of
     * whitespace is one space, at either end too, and all else reaches the store as itself.
     */
    def "turns what was sent into exactly the query it asked for, and asks the store for that"() {
        given:
        arriving(EnumSet.of(EstateRole.STEWARD))
        given(people.page(any(ListQuery), isNull())).willReturn(new ListPage<>([], null))

        when:
        def answered = askingForThePoolWith(sent)

        then:
        answered.response.status == 200
        storeAskedFor(asked, null)

        where:
        sent                                               || asked
        []                                                 || query(BY_USER, null)
        [["sort", "-groupCount"]]                          || query(BY_COUNT_DESCENDING, null)
        [["sort", "displayName"], ["filter", " Gr%_\\ "]]  || query(BY_NAME, new ListFilter(" Gr%_\\ "))
        [["filter", "山田" + IDEOGRAPHIC_SPACE + "太郎"]]      || query(BY_USER, new ListFilter("山田 太郎"))
        [["filter", "ADA" + IDEOGRAPHIC_SPACE + " "]]      || query(BY_USER, new ListFilter("ADA "))
        [["filter", ""]]                                   || query(BY_USER, null)
    }

    /**
     * Refused before the store is asked anything, with the code saying which part of the request
     * could not be read. Nothing sent is handed back: the sentence is fixed per code, whatever
     * arrived, and the answer may not be kept any more than a page may.
     */
    def "refuses a query it cannot read as asked, and never asks the store"() {
        given:
        arriving(EnumSet.of(EstateRole.STEWARD))

        when:
        def answered = askingForThePoolWith(sent)

        then:
        answered.response.status == 400
        answered.response.contentType.startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
        documentOf(answered).get("code").asString() == code
        answered.response.getHeader("Cache-Control") == "no-store"

        and: "and nothing that was sent comes back in what refused it"
        documentOf(answered).get("detail").asString() == detail

        and:
        Mockito.verifyNoInteractions(people)

        where:
        sent                                              || code                     | detail
        [["srot", "-groupCount"]]                         || "PARAMETER_UNKNOWN"      | PARAMETER_REFUSED
        [["page[size]", "10"], ["sort", "userId"]]        || "PARAMETER_UNKNOWN"      | PARAMETER_REFUSED
        [["sort", "estateRoles"]]                         || "LIST_SORT_UNUSABLE"     | SORT_REFUSED
        [["sort", "userId,displayName"]]                  || "LIST_SORT_UNUSABLE"     | SORT_REFUSED
        [["sort", ""]]                                    || "LIST_SORT_UNUSABLE"     | SORT_REFUSED
        [["sort", "userId"], ["sort", "userId"]]          || "LIST_SORT_UNUSABLE"     | SORT_REFUSED
        [["filter", "Ada" + Character.toString(0x2028)]]  || "LIST_FILTER_UNUSABLE"   | FILTER_REFUSED
        [["filter", "a" * 257]]                           || "LIST_FILTER_UNUSABLE"   | FILTER_REFUSED
        [["filter", "a"], ["filter", "b"]]                || "LIST_FILTER_UNUSABLE"   | FILTER_REFUSED
        [["sort", "estateRoles"], ["filter", "a"], ["filter", "b"]] || "LIST_SORT_UNUSABLE" | SORT_REFUSED
        [["cursor", "not a cursor"]]                      || "LIST_CURSOR_UNUSABLE"   | CURSOR_REFUSED
        [["cursor", ""]]                                  || "LIST_CURSOR_UNUSABLE"   | CURSOR_REFUSED
        [["cursor", MINTED_BY_ANOTHER_ORDER]]             || "LIST_CURSOR_UNUSABLE"   | CURSOR_REFUSED
        [["cursor", MINTED_UNDER_ANOTHER_FILTER]]         || "LIST_CURSOR_UNUSABLE"   | CURSOR_REFUSED
        [["cursor", MINTED_WITHIN_ANOTHER_SCOPE]]         || "LIST_CURSOR_UNUSABLE"   | CURSOR_REFUSED
        [["cursor", MINTED_FOR_THIS_QUERY], ["cursor", MINTED_FOR_THIS_QUERY]] || "LIST_CURSOR_UNUSABLE" | CURSOR_REFUSED
    }

    /** Some names are written with it, so a filter carrying one is asked for, not refused. */
    def "passes a filter holding a joiner through to the store as typed"() {
        given:
        arriving(EnumSet.of(EstateRole.STEWARD))
        def typed = "Mi" + Character.toString(0x200C) + "tra"
        given(people.page(any(ListQuery), isNull())).willReturn(new ListPage<>([], null))

        when:
        def answered = askingForThePoolWith([["filter", typed]])

        then:
        answered.response.status == 200
        storeAskedFor(query(BY_USER, new ListFilter(typed)), null)
    }

    /**
     * The reader holding only the other role is the case the separation exists for: the estate's
     * roles never add up to one another, so reading what it measures reaches nobody's list of people.
     */
    def "refuses a caller whose roles do not reach the pool, before the store is asked anything"() {
        given:
        arriving(held)

        when:
        def answered = asking(get(address))

        then:
        answered.response.status == 403
        documentOf(answered).get("code").asString() == "ACT_NOT_PERMITTED"
        answered.response.getHeader("Cache-Control") == "no-store"

        and:
        Mockito.verifyNoInteractions(people)

        where:
        [held, address] << [[EnumSet.of(EstateRole.WATCHER), EnumSet.noneOf(EstateRole)],
                            ["/api/pool/people", "/api/pool/people/" + NAMED_SUBJECT]].combinations()
    }

    /**
     * Asking which methods an address takes asks no act, so it is answered alike whatever the caller
     * holds, and truthfully: the methods this address answers, and nothing the store holds. Somebody
     * nobody identified is still refused at the door.
     */
    def "answers OPTIONS with the methods an address takes to whoever is identified, touching nothing"() {
        given:
        given(identification.identify(any(HttpServletRequest)))
                .willReturn(held == null ? Optional.empty() : Optional.of(READER))
        given(grants.heldBy(READER)).willReturn(held ?: EnumSet.noneOf(EstateRole))

        when:
        def answered = asking(options(address))

        then:
        answered.response.status == status
        answered.response.getHeader("Cache-Control") == "no-store"

        and: "past a missing address the servlet names methods on an answer already committed, which a mock keeps and a socket does not"
        status == 404 || (answered.response.getHeader("Allow")?.split(",")?.collect { it.trim() } as Set) == allowed

        and:
        Mockito.verifyNoInteractions(people)

        where:
        held                           | address                                 || status | allowed
        EnumSet.of(EstateRole.STEWARD) | "/api/pool/people"                      || 200    | ["GET", "HEAD", "OPTIONS"] as Set
        EnumSet.of(EstateRole.STEWARD) | "/api/pool/people/" + NAMED_SUBJECT     || 200    | ["GET", "HEAD", "OPTIONS"] as Set
        EnumSet.of(EstateRole.STEWARD) | "/api/no-such-thing"                    || 404    | null
        EnumSet.of(EstateRole.WATCHER) | "/api/pool/people"                      || 200    | ["GET", "HEAD", "OPTIONS"] as Set
        EnumSet.of(EstateRole.WATCHER) | "/api/pool/people/" + NAMED_SUBJECT     || 200    | ["GET", "HEAD", "OPTIONS"] as Set
        EnumSet.of(EstateRole.WATCHER) | "/api/no-such-thing"                    || 404    | null
        null                           | "/api/pool/people"                      || 401    | null
        null                           | "/api/pool/people/" + NAMED_SUBJECT     || 401    | null
    }

    /** A preflight is OPTIONS too, and no origin is let in by it: this application names none. */
    def "answers a cross-origin preflight without letting the origin in, and without faulting"() {
        given:
        arriving(EnumSet.of(EstateRole.WATCHER))

        when:
        def answered = asking(options("/api/pool/people")
                .header("Origin", "https://elsewhere.test")
                .header("Access-Control-Request-Method", "GET"))

        then:
        answered.response.status < 500
        answered.response.getHeader("Access-Control-Allow-Origin") == null
        answered.response.getHeader("Cache-Control") == "no-store"

        and:
        Mockito.verifyNoInteractions(people)
    }

    def "refuses a caller nobody identified as not signed in, before anything else is asked"() {
        given:
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.empty())

        when:
        def answered = asking(get(address))

        then:
        answered.response.status == 401
        documentOf(answered).get("code").asString() == "NOT_SIGNED_IN"
        answered.response.getHeader("Cache-Control") == "no-store"

        and:
        Mockito.verifyNoInteractions(people)
        Mockito.verifyNoInteractions(grants)

        where:
        address << ["/api/pool/people", "/api/pool/people/" + NAMED_SUBJECT]
    }

    def "answers a caller who may keep the pool with one person: what they hold across the estate, the groups they are in by name, and whether a migration seeded them"() {
        given:
        arriving(EnumSet.of(EstateRole.STEWARD))
        given(people.person(new SubjectId(NAMED_SUBJECT))).willReturn(Optional.of(new PoolPeople.PoolPersonPanel(
                new SubjectId(NAMED_SUBJECT), new UserId("000140"), new PersonName("Grace Hopper"), EnumSet.of(EstateRole.WATCHER),
                EnumSet.noneOf(EstateRole), [new GroupName("Payroll"), new GroupName("finance")], true)))

        when:
        def answered = asking(get("/api/pool/people/" + NAMED_SUBJECT))
        def document = documentOf(answered)

        then:
        answered.response.status == 200
        membersOf(document) == ["subjectId", "userId", "displayName", "estateRoles", "lastGrantingRoles", "groups", "seeded"]
        document.get("subjectId").asString() == NAMED_SUBJECT.toString()
        document.get("userId").asString() == "000140"
        document.get("displayName").asString() == "Grace Hopper"
        document.get("estateRoles").collect { it.asString() } == ["watcher"]
        document.get("lastGrantingRoles").isEmpty()
        document.get("groups").collect { it.asString() } == ["Payroll", "finance"]
        document.get("seeded").asBoolean()

        and: "no role inside a group named anywhere in it, under any member"
        GroupRole.values().every { !answered.response.contentAsString.toLowerCase(Locale.ROOT).contains(it.name().toLowerCase(Locale.ROOT)) }

        and: "nor who made the row, a seeded one having been made by nobody"
        !answered.response.contentAsString.contains("created")

        and:
        answered.response.getHeader("Cache-Control") == "no-store"
    }

    def "answers for somebody no name is held for without a name member, and says when nobody seeded them"() {
        given:
        arriving(EnumSet.of(EstateRole.STEWARD))
        given(people.person(new SubjectId(NAMELESS_SUBJECT))).willReturn(Optional.of(new PoolPeople.PoolPersonPanel(
                new SubjectId(NAMELESS_SUBJECT), new UserId("000130"), null, EnumSet.noneOf(EstateRole),
                EnumSet.noneOf(EstateRole), [], false)))

        when:
        def document = documentOf(asking(get("/api/pool/people/" + NAMELESS_SUBJECT)))

        then:
        membersOf(document) == ["subjectId", "userId", "estateRoles", "lastGrantingRoles", "groups", "seeded"]
        !document.get("seeded").asBoolean()
        document.get("groups").isEmpty()
    }

    /** Spelt as the roles held are, so the page can hold one against the other. */
    def "answers which of the roles somebody holds nobody else in the pool could grant without, by their published spelling"() {
        given:
        arriving(EnumSet.of(EstateRole.STEWARD))
        given(people.person(new SubjectId(NAMED_SUBJECT))).willReturn(Optional.of(new PoolPeople.PoolPersonPanel(
                new SubjectId(NAMED_SUBJECT), new UserId("000140"), new PersonName("Grace Hopper"),
                EnumSet.of(EstateRole.STEWARD, EstateRole.WATCHER), EnumSet.of(EstateRole.STEWARD), [], false)))

        when:
        def document = documentOf(asking(get("/api/pool/people/" + NAMED_SUBJECT)))

        then:
        document.get("lastGrantingRoles").collect { it.asString() } == ["steward"]

        and: "and every role held is still listed as held"
        document.get("estateRoles").collect { it.asString() } == ["steward", "watcher"]
    }

    /** Clients and stores write an identifier in either case, and both are the one identifier. */
    def "reads an identifier in either case as the one identifier it is"() {
        given:
        arriving(EnumSet.of(EstateRole.STEWARD))
        def lettered = "0000000a-000b-4000-8000-00000000000c"
        given(people.person(new SubjectId(UUID.fromString(lettered)))).willReturn(Optional.of(new PoolPeople.PoolPersonPanel(
                new SubjectId(UUID.fromString(lettered)), new UserId("000140"), null, EnumSet.noneOf(EstateRole),
                EnumSet.noneOf(EstateRole), [], false)))

        expect:
        asking(get("/api/pool/people/" + spelt)).response.status == 200

        where:
        spelt << ["0000000a-000b-4000-8000-00000000000c", "0000000A-000B-4000-8000-00000000000C"]
    }

    /**
     * A segment that is not an identifier in its one well-formed shape is never put to the store,
     * and is answered exactly as one the store knows nothing of — the shorter spellings the runtime
     * would read as the very identifier the store answers for among them.
     */
    def "answers every address naming nobody in view alike, whether or not the store was asked"() {
        given:
        arriving(EnumSet.of(EstateRole.STEWARD))
        def lettered = "0000000a-000b-4000-8000-00000000000c"
        def unknown = "00000009-0000-4000-8000-000000000009"
        given(people.person(any(SubjectId))).willReturn(Optional.empty())
        given(people.person(new SubjectId(UUID.fromString(lettered)))).willReturn(Optional.of(new PoolPeople.PoolPersonPanel(
                new SubjectId(UUID.fromString(lettered)), new UserId("000140"), null, EnumSet.noneOf(EstateRole),
                EnumSet.noneOf(EstateRole), [], false)))

        when:
        def answers = [unknown, "not-an-identifier", "a-b-4000-8000-c", lettered + "0",
                       "+000000a-000b-4000-8000-00000000000c"].collect { address ->
            def answered = asking(get("/api/pool/people/" + address))
            [answered.response.status, answered.response.contentType, answered.response.getHeader("Cache-Control"),
             answered.response.contentAsString.replace("/api/pool/people/" + address, "/api/pool/people/{subjectId}")]
        }

        then:
        answers.toSet().size() == 1
        answers.first()[0] == 404
        answers.first()[2] == "no-store"
        JSON.readTree(answers.first()[3] as String).get("code").asString() == "PERSON_NOT_IN_VIEW"

        and: "the store asked only about the one address that was an identifier"
        storeAskedOnlyAbout(new SubjectId(UUID.fromString(unknown)))
    }

    private static ListQuery<PoolSortColumn> query(ListOrder<PoolSortColumn> order, ListFilter filter) {
        new ListQuery<>(PoolPeople.WHOLE_POOL, order, filter)
    }

    /** Void, so the verification is what fails rather than the null a mock's answer would assert as. */
    private void storeAskedFor(ListQuery<PoolSortColumn> query, ListPosition after) {
        Mockito.verify(people).page(query, after)
    }

    private void storeAskedOnlyAbout(SubjectId subject) {
        Mockito.verify(people).person(subject)
        Mockito.verifyNoMoreInteractions(people)
    }
}
