package org.lilradish.lite.app.library

import static org.mockito.ArgumentMatchers.any
import static org.mockito.ArgumentMatchers.eq
import static org.mockito.ArgumentMatchers.isNull
import static org.mockito.BDDMockito.given
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get

import jakarta.servlet.http.HttpServletRequest
import java.time.Instant
import org.libprunus.spring.error.ApiErrorAutoConfiguration
import org.lilradish.lite.app.estate.EstateRoleGrants
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.identification.UserIdentification
import org.lilradish.lite.app.pool.PersonRows
import org.lilradish.lite.domain.identity.EstateRole
import org.lilradish.lite.domain.identity.GroupId
import org.lilradish.lite.domain.identity.GroupPermission
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
import org.lilradish.lite.domain.registry.EntryAct
import org.lilradish.lite.domain.registry.EntryId
import org.lilradish.lite.domain.registry.EntryKind
import org.lilradish.lite.domain.registry.EntryName
import org.lilradish.lite.domain.registry.EntryPurpose
import org.lilradish.lite.domain.registry.EntryVersionId
import org.lilradish.lite.domain.registry.LibrarySortColumn
import org.lilradish.lite.domain.registry.VersionAct
import org.lilradish.lite.domain.registry.VersionStanding
import org.lilradish.lite.web.ActRequired
import org.lilradish.lite.web.GroupMembershipRequired
import org.lilradish.lite.web.GroupPermissionRequired
import org.lilradish.lite.web.NoActRequired
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import spock.lang.Specification
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * What a group's library and one entry of it are answered with, over a real dispatcher: what the gate asks
 * at every address of the library, the members each answer carries, which query of which kind in which
 * group a request was turned into, and every refusal met before the store is asked. Every controller of
 * the library is loaded.
 *
 * <p>The store is replaced, being the boundary this crosses to a database, and so is who is calling and
 * what they hold in the group. What the store reads is its own spec's question.
 */
@WebMvcTest([LibraryController, EntryChangesController, VersionChangesController])
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
class LibraryControllerIntegrationSpec extends Specification {

    static final UserId READER = new UserId("000001")

    static final JsonMapper JSON = JsonMapper.builder().build()

    static final UUID GROUP = UUID.fromString("00000003-0000-4000-8000-000000000c01")

    static final UUID ENTRY = UUID.fromString("00000006-0000-4000-8000-000000000c01")

    static final UUID HOLDER = UUID.fromString("00000006-0000-4000-8000-000000000c02")

    static final UUID DRAFT = UUID.fromString("00000007-0000-4000-8000-000000000c03")

    static final UUID IN_SERVICE = UUID.fromString("00000007-0000-4000-8000-000000000c02")

    static final UUID SEEDED = UUID.fromString("00000007-0000-4000-8000-000000000c01")

    static final UUID HOLDING = UUID.fromString("00000007-0000-4000-8000-000000000c09")

    static final PersonRows.Person ADA = new PersonRows.Person(
            new SubjectId(UUID.fromString("00000002-0000-4000-8000-000000000c01")), new UserId("000c01"),
            new PersonName("Ada Lovelace"))

    static final PersonRows.Person NAMELESS = new PersonRows.Person(
            new SubjectId(UUID.fromString("00000002-0000-4000-8000-000000000c02")), new UserId("000c02"), null)

    static final ListOrder<LibrarySortColumn> BY_NAME = new ListOrder<>(LibrarySortColumn.NAME, false)

    /** A space a keyboard may type that no name holds. */
    static final String WIDE_SPACE = Character.toString(0x3000)

    /** What the gate asks at each address of the library: membership alone, or the permission of the act. */
    static final Map<String, Object> ASKED = [
            entries   : GroupMembershipRequired,
            entry     : GroupMembershipRequired,
            start     : GroupPermission.AUTHOR_ENTRY,
            rename    : EntryAct.RENAME,
            stop      : EntryAct.STOP,
            letGo     : EntryAct.LET_GO,
            startDraft: EntryAct.START_DRAFT,
            submit    : VersionAct.SUBMIT,
            withdraw  : VersionAct.WITHDRAW,
            approve   : VersionAct.APPROVE,
            retire    : VersionAct.RETIRE,
    ]

    static final List<Class> LIBRARY = [LibraryController, EntryChangesController, VersionChangesController]

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
    private Library library

    @MockitoBean
    private EntryChanges entries

    @MockitoBean
    private VersionChanges versions

    private void holding(Set<GroupRole> held) {
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(READER))
        given(grants.heldBy(READER)).willReturn(EnumSet.allOf(EstateRole))
        given(groupRoles.heldBy(READER, new GroupId(GROUP))).willReturn(held)
    }

    private MvcResult listing(String segment, List<List<String>> sent) {
        mockMvc.perform(sent.inject(get("/api/groups/${GROUP}/${segment}")) { request, parameter ->
            request.param(parameter[0], parameter[1])
        }).andReturn()
    }

    private static JsonNode documentOf(MvcResult answered) {
        JSON.readTree(answered.response.contentAsString)
    }

    private static List<String> membersOf(JsonNode node) {
        node.propertyNames().toList()
    }

    private static ListQuery<LibrarySortColumn> within(ListOrder<LibrarySortColumn> order, String typed) {
        new ListQuery<>(GROUP.toString(), order, typed == null ? null : new ListFilter(typed))
    }

    private static Library.LibraryRow row(UUID entry, String name, Integer inService, boolean submitted,
                                          boolean stopped) {
        new Library.LibraryRow(new EntryId(entry), new EntryName(name), inService, submitted, stopped)
    }

    /** Void, so the verification is what fails rather than the null a mock's answer would assert as. */
    private void readOnlyBy(Closure<?> asked) {
        asked(Mockito.verify(library))
        Mockito.verifyNoMoreInteractions(library)
    }

    private void entryAskedFor(int times) {
        Mockito.verify(library, Mockito.times(times)).entry(any(GroupId), any(EntryKind), any(EntryId), any(UserId))
        Mockito.verifyNoMoreInteractions(library)
    }

    /** Every declaration a handler carries, so a table row of one pins that it carries exactly one. */
    private static List<Object> declaredBy(handler) {
        [handler.getMethodAnnotation(GroupMembershipRequired) == null ? null : GroupMembershipRequired,
         handler.getMethodAnnotation(GroupPermissionRequired)?.value(),
         handler.getMethodAnnotation(ActRequired)?.value(),
         handler.getMethodAnnotation(NoActRequired) == null ? null : NoActRequired].findAll { it != null }
    }

    private static List<Object> permissionOf(Object asked) {
        [asked instanceof EntryAct || asked instanceof VersionAct ? asked.permission() : asked]
    }

    /** The table is closed over the handlers the library maps, so one added without a row fails here. */
    def "every address of the library asks membership alone to read and the permission of the act to change"() {
        given:
        def handlers = mappings.handlerMethods.values().findAll { it.beanType in LIBRARY }

        expect:
        handlers.collectEntries { [(it.method.name): declaredBy(it)] } ==
                ASKED.collectEntries { name, asked -> [(name): permissionOf(asked)] }
    }

    /** Read by every role there, and never through what the caller holds in the estate. */
    def "answers anybody in the group with its entries of the kind addressed, each with what its row shows"() {
        given:
        holding(EnumSet.of(role))
        given(library.page(EntryKind.QUESTION, within(BY_NAME, null), null)).willReturn(new ListPage([
                row(ENTRY, "Triage", 2, true, false), row(HOLDER, "Waiting", null, false, true)], null))

        when:
        def answered = listing("questions", [])
        def document = documentOf(answered)

        then:
        answered.response.status == 200
        membersOf(document) == ["items"]
        document.get("items").collect { membersOf(it) } ==
                [["entryId", "name", "inService", "submitted", "stopped"], ["entryId", "name", "submitted", "stopped"]]
        document.get("items").collect { it.get("entryId").asString() } == [ENTRY.toString(), HOLDER.toString()]
        document.get("items").get(0).get("inService").asInt() == 2
        document.get("items").collect { [it.get("submitted").asBoolean(), it.get("stopped").asBoolean()] } ==
                [[true, false], [false, true]]

        and: "nothing on it may be kept by anything between"
        answered.response.getHeader("Cache-Control") == "no-store"

        and:
        Mockito.verifyNoInteractions(grants, entries, versions)

        where:
        role << GroupRole.values()
    }

    /** The kind is the address's, the order the one asked, and the filter spaced as a name is. */
    def "hands the store a query within the group admitted, of the kind addressed, in the order and with the filter asked"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))
        given(library.page(any(EntryKind), any(ListQuery), isNull())).willReturn(new ListPage([], null))

        when:
        def answered = listing(segment, sent)

        then:
        answered.response.status == 200

        and:
        readOnlyBy { it.page(kind, within(order, typed), null) }

        where:
        segment           | sent                                                          || kind                     | order                                               | typed
        "workflows"       | []                                                            || EntryKind.WORKFLOW       | BY_NAME                                             | null
        "reference-lists" | [["sort", "-inService"]]                                      || EntryKind.REFERENCE_LIST | new ListOrder<>(LibrarySortColumn.IN_SERVICE, true) | null
        "questions"       | [["sort", "submitted"], ["filter", "Tri" + WIDE_SPACE + "age"]] || EntryKind.QUESTION     | new ListOrder<>(LibrarySortColumn.SUBMITTED, false) | "Tri age"
    }

    /** A cursor names the kind it was minted for, so it pages through that kind's entries or through none. */
    def "hands out where the next page begins, and refuses it under any other kind"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))
        def ended = new ListPosition("Triage", "Triage")
        given(library.page(EntryKind.QUESTION, within(BY_NAME, null), null))
                .willReturn(new ListPage([row(ENTRY, "Triage", null, false, false)], ended))

        when:
        def cursor = documentOf(listing("questions", [])).get("nextCursor").asString()
        def elsewhere = listing("workflows", [["cursor", cursor]])

        then:
        cursor == ListCursor.mint(Library.listed(EntryKind.QUESTION), within(BY_NAME, null), ended)

        and:
        elsewhere.response.status == 400
        documentOf(elsewhere).get("code").asString() == "LIST_CURSOR_UNUSABLE"

        and: "the other kind's entries never read"
        readOnlyBy { it.page(EntryKind.QUESTION, within(BY_NAME, null), null) }
    }

    def "refuses a list it cannot read as asked, and never asks the store"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))

        when:
        def answered = listing("workflows", sent)

        then:
        answered.response.status == 400
        documentOf(answered).get("code").asString() == code

        and:
        Mockito.verifyNoInteractions(library)

        where:
        sent                   || code
        [["sort", "kind"]]     || "LIST_SORT_UNUSABLE"
        [["search", "tri"]]    || "PARAMETER_UNKNOWN"
        [["cursor", "nope"]]   || "LIST_CURSOR_UNUSABLE"
    }

    /** Membership is all reading asks, so holding nothing there is not being able to see the group at all. */
    def "refuses the library and its entries to anybody holding no role in the group, before the store is asked anything"() {
        given:
        holding(EnumSet.noneOf(GroupRole))

        when:
        def answered = mockMvc.perform(get(address)).andReturn()

        then:
        answered.response.status == 404
        documentOf(answered).get("code").asString() == "GROUP_NOT_IN_VIEW"

        and:
        Mockito.verifyNoInteractions(library)

        where:
        address << ["/api/groups/${GROUP}/workflows", "/api/groups/${GROUP}/workflows/${ENTRY}"]*.toString()
    }

    /**
     * Every member a reader draws the page from: a stop names who and when, a version put into service by
     * a migration carries an approval naming nobody, and every act by its published spelling.
     */
    def "answers an entry with its header, every version newest first, and what the caller may do to each"() {
        given:
        holding(EnumSet.of(GroupRole.OVERSEER))
        def stoppedAt = Instant.parse("2026-09-24T08:15:00Z")
        given(library.entry(new GroupId(GROUP), EntryKind.REFERENCE_LIST, new EntryId(ENTRY), READER)).willReturn(
                new Library.EntryView(new EntryId(ENTRY), EntryKind.REFERENCE_LIST, new EntryName("Regions"), null,
                        new Library.Stop(ADA, stoppedAt), EnumSet.of(EntryAct.RENAME, EntryAct.LET_GO), [
                        new Library.VersionView(new EntryVersionId(DRAFT), 3, 7, VersionStanding.DRAFT, [NAMELESS, ADA],
                                false, null, EnumSet.of(VersionAct.WRITE, VersionAct.SUBMIT), []),
                        new Library.VersionView(new EntryVersionId(IN_SERVICE), 2, 4, VersionStanding.IN_SERVICE, [ADA],
                                false, new Library.Approval.ByPerson(NAMELESS), EnumSet.of(VersionAct.RETIRE), [
                                new Library.PinnedBy(new EntryId(HOLDER), EntryKind.WORKFLOW, new EntryName("Handle"),
                                        new EntryVersionId(HOLDING), 4)]),
                        new Library.VersionView(new EntryVersionId(SEEDED), 1, 1, VersionStanding.RETIRED, [],
                                true, new Library.Approval.ByMigration(), EnumSet.noneOf(VersionAct), [])]))

        when:
        def answered = mockMvc.perform(get("/api/groups/${GROUP}/reference-lists/${ENTRY}")).andReturn()
        def document = documentOf(answered)
        def versions = document.get("versions")

        then:
        answered.response.status == 200
        membersOf(document) == ["entryId", "kind", "name", "stopped", "acts", "versions"]
        document.get("kind").asString() == "reference_list"
        document.get("name").asString() == "Regions"
        document.get("acts").collect { it.asString() } == ["rename", "let_go"]

        and: "a stop names who stopped it and when"
        document.get("stopped").get("at").asString() == "2026-09-24T08:15:00Z"
        membersOf(document.get("stopped").get("by")) == ["userId", "displayName"]
        document.get("stopped").get("by").get("userId").asString() == "000c01"

        and: "the versions newest first, each at its standing"
        versions.collect { it.get("versionId").asString() } == [DRAFT, IN_SERVICE, SEEDED]*.toString()
        versions.collect { it.get("number").asInt() } == [3, 2, 1]
        versions.collect { it.get("revision").asInt() } == [7, 4, 1]
        versions.collect { it.get("standing").asString() } == ["draft", "in_service", "retired"]
        versions.collect { version -> version.get("acts").collect { it.asString() } } ==
                [["write", "submit"], ["retire"], []]

        and: "everyone who wrote each, a name only where one is held"
        versions.get(0).get("writers").collect { it.get("userId").asString() } == ["000c02", "000c01"]
        membersOf(versions.get(0).get("writers").get(0)) == ["userId"]

        and: "a version a migration started says so, which none a person started does"
        versions.collect { it.get("writtenByMigration").asBoolean() } == [false, false, true]
        versions.get(2).get("writers").isEmpty()

        and: "an approval by a person names them, one by a migration names nobody, and a draft has none"
        membersOf(versions.get(0)) ==
                ["versionId", "number", "revision", "standing", "writers", "writtenByMigration", "acts", "pinnedBy"]
        versions.get(1).get("approval").get("approver").get("userId").asString() == "000c02"
        membersOf(versions.get(2).get("approval")) == []

        and: "every version in service pinning each, each named"
        versions.collect { it.get("pinnedBy").size() } == [0, 1, 0]
        membersOf(versions.get(1).get("pinnedBy").get(0)) == ["entryId", "kind", "name", "versionId", "number"]
        versions.get(1).get("pinnedBy").get(0).get("kind").asString() == "workflow"
        versions.get(1).get("pinnedBy").get(0).get("versionId").asString() == HOLDING.toString()
    }

    def "answers an entry saying what it is for where it says anything, and no stop where it may be run"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))
        given(library.entry(new GroupId(GROUP), EntryKind.WORKFLOW, new EntryId(ENTRY), READER)).willReturn(
                new Library.EntryView(new EntryId(ENTRY), EntryKind.WORKFLOW, new EntryName("Handle"),
                        new EntryPurpose("Handles a claim."), null, EnumSet.of(EntryAct.START_DRAFT), []))

        when:
        def document = documentOf(mockMvc.perform(get("/api/groups/${GROUP}/workflows/${ENTRY}")).andReturn())

        then:
        membersOf(document) == ["entryId", "kind", "name", "purpose", "acts", "versions"]
        document.get("purpose").asString() == "Handles a claim."
        document.get("versions").isEmpty()
    }

    /** An identifier that is none is refused as the store refuses one it does not hold, and never asked of it. */
    def "refuses an entry the store does not hold, and an address that names none, alike"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))
        given(library.entry(eq(new GroupId(GROUP)), eq(EntryKind.WORKFLOW), any(EntryId), eq(READER)))
                .willThrow(LibraryRefusal.ENTRY_NOT_IN_VIEW.raised())

        when:
        def answered = mockMvc.perform(get("/api/groups/${GROUP}/workflows/${addressed}")).andReturn()

        then:
        answered.response.status == 404
        documentOf(answered).get("code").asString() == "ENTRY_NOT_IN_VIEW"
        documentOf(answered).get("detail").asString() == "That entry is not in this group's library."

        and: "an address naming nothing never asked of the store"
        entryAskedFor(asked)

        where:
        addressed          || asked
        ENTRY.toString()   || 1
        "not-an-entry"     || 0
    }

    def "refuses a parameter an entry does not take, and never asks the store"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))

        when:
        def answered = mockMvc.perform(get("/api/groups/${GROUP}/workflows/${ENTRY}").queryParam("version", "1"))
                .andReturn()

        then:
        answered.response.status == 400
        documentOf(answered).get("code").asString() == "PARAMETER_UNKNOWN"

        and:
        Mockito.verifyNoInteractions(library)
    }

    /** Each kind is addressed as its page is, and the address takes no segment naming no kind. */
    def "each kind of entry answers under the one segment its page is at, and no other segment is taken"() {
        given:
        def alternatives = (LibraryController.ENTRIES =~ /\{kind:([^}]*)}/)[0][1].split(/\|/) as List

        expect:
        alternatives as Set == EntryKind.values()*.segment() as Set
        alternatives.size() == EntryKind.values().size()
        EntryKind.values().every { LibraryController.kindAt(it.segment()) == it }
    }

    def "a segment naming no kind is this application's own fault, and never answered as a kind"() {
        when:
        LibraryController.kindAt("members")

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "No kind of entry is addressed as members"
    }
}
