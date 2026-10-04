package org.lilradish.lite.app.library

import static org.mockito.ArgumentMatchers.any
import static org.mockito.BDDMockito.given
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import jakarta.servlet.http.HttpServletRequest
import javax.sql.DataSource
import org.libprunus.spring.error.ApiErrorAutoConfiguration
import org.lilradish.lite.app.estate.EstateRoleGrants
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.identification.UserIdentification
import org.lilradish.lite.domain.identity.EstateRole
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.listing.ListCursor
import org.lilradish.lite.domain.listing.ListPosition
import org.lilradish.lite.domain.listing.ListQuery
import org.lilradish.lite.domain.registry.EntryKind
import org.lilradish.lite.testutil.library.LibraryStore
import org.lilradish.lite.testutil.library.TakingWorkflow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.transaction.PlatformTransactionManager
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper

/**
 * What a group may start a run of, answered over a real dispatcher and a real store: which workflows, and every
 * way of naming no group in view answered alike. Only who is calling is replaced.
 */
@WebMvcTest(OffersController)
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
@Import([Offers, GroupRoles, Store])
class OffersControllerIntegrationSpec extends Specification {

    static final String GROUP = "00000003-0000-4000-8000-000000001501"

    static final String OTHER_GROUP = "00000003-0000-4000-8000-000000001502"

    static final String ANN = "00000002-0000-4000-8000-000000001501"

    static final UserId ANN_USER = new UserId("001501")

    static final String HANDLE = "00000006-0000-4000-8000-000000001501"

    static final String REFUND = "00000006-0000-4000-8000-000000001502"

    static final String ARCHIVE = "00000006-0000-4000-8000-000000001503"

    static final String ESCALATE = "00000006-0000-4000-8000-000000001504"

    static final String SORT = "00000006-0000-4000-8000-000000001505"

    static final String ELSEWHERE = "00000006-0000-4000-8000-000000001506"

    static final String RETIRED = "00000006-0000-4000-8000-000000001507"

    static final String RESUMED = "00000006-0000-4000-8000-000000001508"

    static final String TAKE = "00000006-0000-4000-8000-000000001509"

    static final String PICK = "00000006-0000-4000-8000-000000001511"

    static final JsonMapper JSON = JsonMapper.builder().build()

    @Autowired
    private MockMvc mockMvc

    @Autowired
    private LibraryStore store

    @MockitoBean
    private UserIdentification identification

    @MockitoBean
    private EstateRoleGrants grants

    def setup() {
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(ANN_USER))
        given(grants.heldBy(ANN_USER)).willReturn(EnumSet.allOf(EstateRole))
    }

    private MvcResult reading(String address) {
        mockMvc.perform(get(address)).andReturn()
    }

    private static String version(int number) {
        String.format("00000007-0000-4000-8000-%012d", 1500 + number)
    }

    private static String id(int kind, int number) {
        String.format("000000%02d-0000-4000-8000-%012d", kind, number)
    }

    private void newGroup(String group, String key) {
        store.group(group, key, "Demo Sample ${key}")
        store.member(group, ANN, "operator")
    }

    private void offer(String group, int number, String name, String purpose = null) {
        def entry = id(6, number)
        store.entry(entry, group, "workflow", name, purpose)
        store.seeded(id(7, number), entry, 1)
    }

    private MvcResult filtered(String group, String filter) {
        mockMvc.perform(get("/api/groups/{group}/offered-workflows", group).param("filter", filter)).andReturn()
    }

    /** Everything an answer says, but the address it was sent to, which is each caller's own. */
    private static List<Object> apartFromWhere(MvcResult answered) {
        def document = JSON.convertValue(JSON.readTree(answered.response.contentAsString), Map)
        [answered.response.status, answered.response.contentType, document.findAll { it.key != "instance" }]
    }

    def "offers the group's workflows by name, each with what it is for where it says so, and what takes nothing"() {
        when:
        def answered = reading("/api/groups/${GROUP}/offered-workflows")

        then:
        answered.response.status == 200
        def workflows = JSON.readTree(answered.response.contentAsString).get("items")
        workflows.findAll { !(it.get("entryId").asString() in [TAKE, PICK]) } == JSON.readTree("""
                [{"entryId":"${HANDLE}","name":"Handle a claim","purpose":"Sorts a claim out.",
                  "versions":[{"versionId":"${version(2)}","number":2,"takes":[]},
                              {"versionId":"${version(1)}","number":1,"takes":[]}]},
                 {"entryId":"${REFUND}","name":"Pay a refund",
                  "versions":[{"versionId":"${version(5)}","number":1,"takes":[]}]},
                 {"entryId":"${RESUMED}","name":"Resume a claim",
                  "versions":[{"versionId":"${version(11)}","number":1,"takes":[]}]}]
                """ as String).toList()
        workflows*.get("name")*.asString() ==
                ["Handle a claim", "Pay a refund", "Pick a remedy", "Resume a claim", "Take a claim"]
    }

    /** Only a version in service is offered, so every field's limit and list is chosen and read as it stands. */
    def "offers each version with every field it takes as whoever starts it fills it in, the terms of its list inline"() {
        when:
        def workflows = JSON.readTree(reading("/api/groups/${GROUP}/offered-workflows").response.contentAsString)
                .get("items")

        then:
        workflows.find { it.get("entryId").asString() == TAKE }.get("versions") == JSON.readTree("""
                [{"versionId":"${version(12)}","number":1,"takes":[
                  {"name":"complaint","label":"The complaint","help":"In the customer's words.","kind":"text",
                   "longest":20,"mustBeGiven":true,"lines":"several"},
                  {"name":"amount","kind":"number","mustBeGiven":false},
                  {"name":"category","kind":"term","mustBeGiven":true,
                   "terms":{"terms":[{"term":"late","meaning":"It came after the day promised."},
                                     {"term":"damaged","meaning":"It came broken."}],
                            "note":"Pick the one the customer names first."}},
                  {"name":"tags","kind":"text","longest":10,"most":2,"mustBeGiven":false,"lines":"several"},
                  {"name":"contact","kind":"fields","mustBeGiven":false,"fields":[
                    {"name":"email","kind":"text","longest":50,"mustBeGiven":true,"lines":"several"},
                    {"name":"phone","kind":"text","longest":20,"mustBeGiven":false,"lines":"several"}]}]}]
                """ as String)

        and: "and the list itself is not offered, being no workflow"
        !workflows*.get("name")*.asString().contains("Kinds of complaint")
    }

    /** Every version's lists are read at once, so each field must still be handed its own list's terms alone. */
    def "offers each version of a workflow the terms of the list it pins and of no list another version pins"() {
        when:
        def workflows = JSON.readTree(reading("/api/groups/${GROUP}/offered-workflows").response.contentAsString)
                .get("items")

        then:
        workflows.find { it.get("entryId").asString() == PICK }.get("versions").collect { offered ->
            [offered.get("versionId").asString(),
             offered.get("takes").collect { field -> field.get("terms").get("terms")*.get("term")*.asString() }]
        } == [[version(15), [["refund", "repair"]]], [version(14), [["late", "damaged"]]]]
    }

    def "offers a workflow of the group only while a version of it is in service and nothing stops it: #entry"() {
        when:
        def offered = JSON.readTree(reading("/api/groups/${GROUP}/offered-workflows").response.contentAsString)
                .get("items").collect { it.get("entryId").asString() }

        then:
        offered.contains(entryId) == isOffered

        where:
        entry                                   | entryId   || isOffered
        "versions in service beside others"     | HANDLE    || true
        "a stop let go"                         | RESUMED   || true
        "a draft alone"                         | ARCHIVE   || false
        "a retired version alone"               | RETIRED   || false
        "a stop in force"                       | ESCALATE  || false
        "a question rather than a workflow"     | SORT      || false
        "another group's workflow"              | ELSEWHERE || false
    }

    /** A group the caller holds nothing in, one nobody holds, and an address that is no identifier. */
    def "every way of naming no group in view is answered alike, offering nothing"() {
        when:
        def answers = [OTHER_GROUP, "00000003-0000-4000-8000-000000001509", "not-a-group"].collect {
            reading("/api/groups/${it}/offered-workflows")
        }

        then:
        answers*.response*.status.toSet() == [404] as Set
        answers.collect { apartFromWhere(it) }.toSet().size() == 1
        JSON.readTree(answers.first().response.contentAsString).get("code").asString() == "GROUP_NOT_IN_VIEW"
        answers.every { !it.response.contentAsString.contains("Handle a claim") }
    }

    def "refuses a parameter, which what may be started does not take, offering nothing"() {
        when:
        def answered = reading("/api/groups/${GROUP}/offered-workflows?entry=${HANDLE}")

        then:
        answered.response.status == 400
        JSON.readTree(answered.response.contentAsString).get("code").asString() == "PARAMETER_UNKNOWN"
        !answered.response.contentAsString.contains("Handle a claim")
    }

    def "pages only offered workflows with a fixed size and no missing or repeated entry"() {
        given:
        def group = id(3, groupNumber)
        newGroup(group, key)
        (0..<count).each { index ->
            offer(group, first + index, "Demo Sample ${String.format('%03d', index)}")
        }
        if (count > 0) {
            store.seeded(id(7, 9000 + groupNumber), id(6, first), 2)
        }

        when:
        def pages = []
        def cursor = null
        while (pages.size() < 4) {
            def address = "/api/groups/${group}/offered-workflows" + (cursor == null ? "" : "?cursor=${cursor}")
            def response = reading(address)
            pages.add([response.response.status, JSON.readTree(response.response.contentAsString)])
            cursor = pages.last()[1].get("nextCursor")?.asString()
            if (cursor == null) {
                break
            }
        }

        then:
        pages*.getAt(0) == [200] * expectedSizes.size()
        pages*.getAt(1)*.get("items")*.size() == expectedSizes
        pages.collectMany { page -> page[1].get("items")*.get("entryId")*.asString() } ==
                (0..<count).collect { index -> id(6, first + index) }
        (count == 0 || pages[0][1].get("items").get(0).get("versions")*.get("number")*.asInt() == [2, 1])
        pages.findAll { page -> page != pages.last() }.every { page -> page[1].has("nextCursor") }
        !pages.last()[1].has("nextCursor")
        cursor == null

        where:
        groupNumber | key       | first | count || expectedSizes
        2001        | "PAGEZERO"| 2001  | 0     || [0]
        2002        | "PAGEFIFTY"| 2101 | 50    || [50]
        2003        | "PAGEEXTRA"| 2201 | 51    || [50, 1]
        2004        | "PAGEMANY"| 2301  | 115   || [50, 50, 15]
    }

    def "filters the whole offered set before paging and never matches purpose alone"() {
        given:
        def group = id(3, 2010)
        newGroup(group, "MATCHING")
        (0..<60).each { index ->
            offer(group, 3000 + index, "Demo Sample ${String.format('%03d', index)}",
                    index == 0 ? "Demo Sample target" : null)
        }
        (0..<51).each { index ->
            offer(group, 3100 + index, "Demo Sample Target ${String.format('%03d', index)}")
        }

        when:
        def first = JSON.readTree(filtered(group, "TARGET").response.contentAsString)
        def second = JSON.readTree(reading(
                "/api/groups/${group}/offered-workflows?filter=TARGET&cursor=${first.get('nextCursor').asString()}")
                .response.contentAsString)

        then:
        first.get("items").size() == 50
        second.get("items").size() == 1
        first.get("items")*.get("entryId")*.asString() + second.get("items")*.get("entryId")*.asString() ==
                (0..<51).collect { index -> id(6, 3100 + index) }
        !second.has("nextCursor")
        !first.toString().contains(id(6, 3000))
    }

    def "filtering never offers an unavailable or out-of-group entry"() {
        when:
        def response = filtered(GROUP, typed)

        then:
        response.response.status == 200
        JSON.readTree(response.response.contentAsString).get("items")*.get("entryId")*.asString() == expected

        where:
        typed        || expected
        "Handle"     || [HANDLE]
        "Archive"    || []
        "Retired"    || []
        "Escalate"   || []
        "Sort a claim" || []
        "Elsewhere"  || []
    }

    def "name filtering treats punctuation literally and uses the library's Unicode fold"() {
        given:
        def group = id(3, 2020)
        newGroup(group, "LITERALS")
        offer(group, 4001, "Demo Sample 100% done")
        offer(group, 4002, "Demo Sample under_score")
        offer(group, 4003, "Demo Sample back\\slash")
        offer(group, 4004, "Demo Sample Straße")
        offer(group, 4005, "Demo Sample Café")

        when:
        def answers = ["%", "_", "\\", "STRASSE", "café", "Cafe", ""].collect { typed -> filtered(group, typed) }
        def documents = answers.collect { answer -> JSON.readTree(answer.response.contentAsString) }

        then:
        answers*.response*.status == [200] * 7
        documents.collect { document -> document.get("items")*.get("entryId")*.asString() } == [
                [4001], [4002], [4003], [4004], [4005], [], [4001, 4003, 4005, 4004, 4002]
        ].collect { row -> row.collect { id(6, it) } }
        documents.every { document -> !document.has("nextCursor") }
    }

    def "a cursor belongs to this group and filter and no other list"() {
        given:
        def group = id(3, 2030)
        def other = id(3, 2031)
        newGroup(group, "CURSORA")
        newGroup(other, "CURSORB")
        (0..<51).each { index -> offer(group, 5000 + index, "Demo Sample Card ${String.format('%03d', index)}") }
        offer(other, 5100, "Demo Sample Elsewhere")
        def first = JSON.readTree(filtered(group, "Card").response.contentAsString)
        def cursor = first.get("nextCursor").asString()
        def library = Library.listed(EntryKind.WORKFLOW)
        def libraryQuery = new ListQuery<>(group, library.order(null), null)
        def anotherList = ListCursor.mint(library, libraryQuery, new ListPosition("Demo Sample", "Demo Sample"))

        when:
        def answers = [filtered(group, "Elsewhere"),
                       reading("/api/groups/${group}/offered-workflows?filter=Elsewhere&cursor=${cursor}"),
                       reading("/api/groups/${other}/offered-workflows?filter=Card&cursor=${cursor}"),
                       reading("/api/groups/${group}/offered-workflows?filter=Card&cursor=${anotherList}"),
                       reading("/api/groups/${group}/offered-workflows?filter=Card&cursor=nope")]

        then:
        first.get("items").size() == 50
        answers[0].response.status == 200
        JSON.readTree(answers[0].response.contentAsString).get("items").isEmpty()
        answers.tail()*.response*.status == [400] * 4
        answers.tail().every { answer ->
            JSON.readTree(answer.response.contentAsString).get("code").asString() == "LIST_CURSOR_UNUSABLE"
        }
    }

    def "refuses unknown or malformed listing parameters without offering entries"() {
        when:
        def answer = mockMvc.perform(get("/api/groups/${GROUP}/offered-workflows").param(name, values as String[]))
                .andReturn()

        then:
        answer.response.status == 400
        JSON.readTree(answer.response.contentAsString).get("code").asString() == code
        !answer.response.contentAsString.contains("Handle a claim")

        where:
        name     | values             || code
        "sort"   | ["name"]           || "PARAMETER_UNKNOWN"
        "limit"  | ["1"]              || "PARAMETER_UNKNOWN"
        "entry"  | [HANDLE]           || "PARAMETER_UNKNOWN"
        "filter" | ["one", "two"]    || "LIST_FILTER_UNUSABLE"
        "filter" | ["x" * 257]       || "LIST_FILTER_UNUSABLE"
        "filter" | ["one\ntwo"]       || "LIST_FILTER_UNUSABLE"
        "cursor" | ["one", "two"]    || "LIST_CURSOR_UNUSABLE"
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Store {

        @Bean(destroyMethod = "close")
        EmbeddedPostgres server() {
            EmbeddedPostgres.builder().start()
        }

        @Bean
        LibraryStore store(EmbeddedPostgres server) {
            LibraryStore.template(server)
            def store = LibraryStore.copied(server, "offers_answers")
            store.person(ANN, "001501", "Ann Example")
            store.group(GROUP, "SUPPORT", "Customer support")
            store.group(OTHER_GROUP, "BILLING", "Billing")
            store.member(GROUP, ANN, "operator")
            store.entry(HANDLE, GROUP, "workflow", "Handle a claim", "Sorts a claim out.")
            store.seeded(version(1), HANDLE, 1)
            store.seeded(version(2), HANDLE, 2)
            store.seeded(version(3), HANDLE, 3, true)
            store.version(version(4), HANDLE, 4, ANN)
            store.entry(REFUND, GROUP, "workflow", "Pay a refund")
            store.seeded(version(5), REFUND, 1)
            store.entry(ARCHIVE, GROUP, "workflow", "Archive")
            store.version(version(6), ARCHIVE, 1, ANN)
            store.entry(ESCALATE, GROUP, "workflow", "Escalate")
            store.seeded(version(7), ESCALATE, 1)
            store.stopped(ESCALATE, ANN)
            store.entry(SORT, GROUP, "question", "Sort a claim")
            store.seeded(version(8), SORT, 1)
            store.entry(ELSEWHERE, OTHER_GROUP, "workflow", "Elsewhere")
            store.seeded(version(9), ELSEWHERE, 1)
            store.entry(RETIRED, GROUP, "workflow", "Retired")
            store.seeded(version(10), RETIRED, 1, true)
            store.entry(RESUMED, GROUP, "workflow", "Resume a claim")
            store.seeded(version(11), RESUMED, 1)
            store.stopped(RESUMED, ANN)
            store.session.sql("update entry_stops set let_go_at = now(), let_go_by = ?::uuid where entry_id = ?::uuid")
                    .params(ANN, RESUMED).update()
            TakingWorkflow.list(store, "00000006-0000-4000-8000-000000001510", version(13), GROUP)
            TakingWorkflow.workflow(store, TAKE, version(12), GROUP, "Take a claim")
            TakingWorkflow.takes(store, version(12), version(13))
            store.entry("00000006-0000-4000-8000-000000001512", GROUP, "reference_list", "Kinds of remedy")
            store.seeded(version(16), "00000006-0000-4000-8000-000000001512", 1)
            store.content(version(16), "reference_list", "Pick what the customer asks for.")
            ["refund", "repair"].eachWithIndex { term, position ->
                store.session.sql("""
                        insert into reference_list_terms (entry_version_id, position, term, meaning, created_by)
                        values (?::uuid, ?, ?, 'Asked for.', ?::uuid)
                        """).params(version(16), position, term, LibraryStore.SEEDER).update()
            }
            store.entry(PICK, GROUP, "workflow", "Pick a remedy")
            [[version(14), 1, version(13)], [version(15), 2, version(16)]].each { pinning ->
                store.seeded(pinning[0], PICK, pinning[1])
                store.content(pinning[0], "workflow")
                TakingWorkflow.field(store, pinning[0], 1, "choice", "term", [list: pinning[2]])
            }
            store
        }

        @Bean
        DataSource database(LibraryStore store) {
            store.database
        }

        @Bean
        PlatformTransactionManager transactionManager(LibraryStore store) {
            store.transactionManager()
        }

        @Bean
        JdbcClient session(LibraryStore store) {
            store.session
        }
    }
}
