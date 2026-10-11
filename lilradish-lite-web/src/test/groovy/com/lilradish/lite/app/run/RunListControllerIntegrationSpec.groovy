package com.lilradish.lite.app.run

import static com.lilradish.lite.testutil.library.LibraryStore.groupId
import static org.mockito.ArgumentMatchers.any
import static org.mockito.BDDMockito.given
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get

import com.lilradish.lite.app.estate.EstateRoleGrants
import com.lilradish.lite.app.group.GroupRoles
import com.lilradish.lite.app.identification.UserIdentification
import com.lilradish.lite.app.run.fixture.StepRows
import com.lilradish.lite.domain.identity.EstateRole
import com.lilradish.lite.domain.identity.GroupPermission
import com.lilradish.lite.domain.identity.UserId
import com.lilradish.lite.domain.listing.ListCursor
import com.lilradish.lite.domain.listing.ListOrder
import com.lilradish.lite.domain.listing.ListPage
import com.lilradish.lite.domain.listing.ListPosition
import com.lilradish.lite.domain.listing.ListQuery
import com.lilradish.lite.domain.run.RunSortColumn
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.library.LibraryStore
import com.lilradish.lite.testutil.run.RunRows
import jakarta.servlet.http.HttpServletRequest
import javax.sql.DataSource
import org.libprunus.spring.error.ApiErrorAutoConfiguration
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
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

@WebMvcTest(RunListController)
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
@Import([RunList, RunSnapshots, com.lilradish.lite.app.search.SearchWays, com.lilradish.lite.app.codestep.CodeSteps, GroupRoles, Store])
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class RunListControllerIntegrationSpec extends Specification {

    static final String GROUP = "00000003-0000-4000-8000-000000001401"

    static final String OTHER_GROUP = "00000003-0000-4000-8000-000000001402"

    static final String PAGED_GROUP = "00000003-0000-4000-8000-000000001403"

    static final String ANN = "00000002-0000-4000-8000-000000001401"

    static final UserId ANN_USER = new UserId("001401")

    static final String CAT = "00000002-0000-4000-8000-000000001403"

    static final UserId CAT_USER = new UserId("001403")

    static final String HANDLE = "00000006-0000-4000-8000-000000001401"

    static final String HANDLE_VERSION = "00000007-0000-4000-8000-000000001401"

    static final String ELSEWHERE = "00000006-0000-4000-8000-000000001402"

    static final String ELSEWHERE_VERSION = "00000007-0000-4000-8000-000000001402"

    static final String PAGED = "00000006-0000-4000-8000-000000001403"

    static final String PAGED_VERSION = "00000007-0000-4000-8000-000000001403"

    static final String CATS_RUN = "00000008-0000-4000-8000-000000001401"

    static final String ANNS_RUN = "00000008-0000-4000-8000-000000001402"

    static final String OTHER_GROUPS_RUN = "00000008-0000-4000-8000-000000001403"

    static final JsonMapper JSON = JsonMapper.builder().build()

    @Autowired
    private MockMvc mockMvc

    @MockitoBean
    private UserIdentification identification

    @MockitoBean
    private EstateRoleGrants grants

    // Send the URI as written; a template would encode the percent signs again.
    private MvcResult reading(String address, UserId reader) {
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(reader))
        given(grants.heldBy(reader)).willReturn(EnumSet.allOf(EstateRole))
        mockMvc.perform(get(URI.create(address))).andReturn()
    }

    private static JsonNode document(MvcResult answered) {
        JSON.readTree(answered.response.contentAsString)
    }

    private static List<String> runIds(MvcResult answered) {
        document(answered).get("items").collect { it.get("runId").asString() }
    }

    private static List<Integer> numbers(MvcResult answered) {
        document(answered).get("items").collect { it.get("number").asInt() }
    }

    private static List<Object> apartFromWhere(MvcResult answered) {
        def document = JSON.convertValue(document(answered), Map)
        [answered.response.status, answered.response.contentType, document.findAll { it.key != "instance" }]
    }

    def "answers every run of the group newest first to one who may read every run, spelt as a page draws it"() {
        when:
        def answered = reading("/api/groups/${GROUP}/runs", ANN_USER)

        then:
        answered.response.status == 200
        document(answered) == JSON.readTree("""
                {"items":[
                  {"runId":"${ANNS_RUN}","number":2,"name":"Refund for Greno",
                   "workflow":{"name":"Handle a claim","version":1,"versionName":null},
                   "startedBy":{"userId":"001401","displayName":"Ann Example"},
                   "startedAt":"2026-09-25T09:00:00Z","state":"stopped"},
                  {"runId":"${CATS_RUN}","number":1,"name":"Claim  from Ari",
                   "workflow":{"name":"Handle a claim","version":1,"versionName":null},
                   "startedBy":{"userId":"001403"},
                   "startedAt":"2026-09-25T08:00:00Z","state":"running",
                   "at":"ask"}]}
                """ as String)
    }

    def "answers one who may read only their own runs with those and no other, without a reader label"() {
        when:
        def answered = reading("/api/groups/${GROUP}/runs?sort=run", CAT_USER)

        then:
        answered.response.status == 200
        runIds(answered) == [CATS_RUN]
        !runIds(answered).contains(ANNS_RUN)
        !runIds(answered).contains(OTHER_GROUPS_RUN)
        !document(answered).has("reading")
    }

    def "narrows the runs to those whose name or workflow holds the filter sent"() {
        when:
        def answered = reading("/api/groups/${GROUP}/runs?sort=run&filter=${filter}", ANN_USER)

        then:
        answered.response.status == 200
        runIds(answered) == listed

        where:
        filter              || listed
        "claim%20%20from"   || [CATS_RUN]
        "claim%20from"      || []
        "GRENO"             || [ANNS_RUN]
        "handle%C2%A0%20a"  || [CATS_RUN, ANNS_RUN]
        "zzz"               || []
    }

    def "a page's cursor sent back answers the next page, which ends the list"() {
        given:
        def first = reading("/api/groups/${PAGED_GROUP}/runs?sort=run", ANN_USER)
        def cursor = document(first).get("nextCursor").asString()

        when:
        def second = reading("/api/groups/${PAGED_GROUP}/runs?sort=run&cursor=${cursor}", ANN_USER)

        then:
        second.response.status == 200
        numbers(first) == (1..ListPage.SIZE).toList()
        numbers(second) == [ListPage.SIZE + 1]
        !document(second).has("nextCursor")
    }

    def "refuses a cursor minted reading every run to one who may read only their own, naming no run"() {
        given:
        def minted = document(reading("/api/groups/${PAGED_GROUP}/runs?sort=run", ANN_USER)).get("nextCursor")
                .asString()

        when:
        def answered = reading("/api/groups/${PAGED_GROUP}/runs?sort=run&cursor=${minted}", CAT_USER)

        then:
        answered.response.status == 400
        document(answered).get("code").asString() == "LIST_CURSOR_UNUSABLE"
        !document(answered).has("items")
        !answered.response.contentAsString.contains("Paged")
    }

    def "newest first, a cursor holding the furthest instant either way answers a page rather than failing: #started"() {
        given:
        def everyRun = RunList.readingOf([GroupPermission.READ_ALL_RUNS] as Set, groupId(GROUP), ANN_USER)
        def cursor = ListCursor.mint(everyRun.shape(),
                new ListQuery<>(everyRun.scope(), new ListOrder<>(RunSortColumn.STARTED, true), null),
                new ListPosition(started, 1L))

        when:
        def answered = reading("/api/groups/${GROUP}/runs?sort=-started&cursor=${cursor}", ANN_USER)

        then:
        answered.response.status == 200
        runIds(answered) == runs
        !document(answered).has("nextCursor")
        !document(answered).has("code")

        where:
        started        || runs
        Long.MAX_VALUE || [ANNS_RUN, CATS_RUN]
        Long.MIN_VALUE || []
    }

    def "every way of naming no group in view is answered alike"() {
        when:
        def answers = [OTHER_GROUP, "00000003-0000-4000-8000-000000001409", "not-a-group"].collect {
            reading("/api/groups/${it}/runs", ANN_USER)
        }

        then:
        answers*.response*.status.toSet() == [404] as Set
        answers.collect { apartFromWhere(it) }.toSet().size() == 1
        document(answers.first()).get("code").asString() == "GROUP_NOT_IN_VIEW"
    }

    def "refuses a column the runs do not sort by, naming no run: #sort"() {
        when:
        def answered = reading("/api/groups/${GROUP}/runs?sort=${sort}", ANN_USER)

        then:
        answered.response.status == 400
        document(answered).get("code").asString() == "LIST_SORT_UNUSABLE"
        !answered.response.contentAsString.contains("Refund for Greno")

        where:
        sort << ["cost", "where", "-where"]
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Store {

        @Bean(destroyMethod = "close")
        TestPostgres server() {
            TestPostgres.worker()
        }

        @Bean
        LibraryStore store(TestPostgres server) {
            LibraryStore.prepareBaseline(server)
            def store = LibraryStore.forCase(server, "run_list_answers")
            store.person(ANN, "001401", "Ann Example")
            // DB-SPECIFIC: PostgreSQL ::uuid casts and a native subject_kind label input.
            store.session.sql("insert into subjects (subject_id, kind, user_id, created_by) values (?::uuid, 'person', ?, ?::uuid)")
                    .params(CAT, "001403", LibraryStore.SEEDER).update()
            store.group(GROUP, "SUPPORT", "Customer support")
            store.group(OTHER_GROUP, "BILLING", "Billing")
            store.group(PAGED_GROUP, "PAGED", "Paged")
            store.member(GROUP, ANN, "overseer")
            store.member(GROUP, CAT, "operator")
            store.member(OTHER_GROUP, CAT, "owner")
            store.member(PAGED_GROUP, ANN, "overseer")
            store.member(PAGED_GROUP, CAT, "operator")
            RunRows.workflow(store, HANDLE, HANDLE_VERSION, GROUP, "Handle a claim", null, false)
            RunRows.workflow(store, ELSEWHERE, ELSEWHERE_VERSION, OTHER_GROUP, "Handle a claim", null, false)
            RunRows.workflow(store, PAGED, PAGED_VERSION, PAGED_GROUP, "Page through", null, false)
            StepRows.askingSomebody(store, HANDLE_VERSION, GROUP)
            RunRows.run(store, CATS_RUN, GROUP, 1, "Claim  from Ari", HANDLE, HANDLE_VERSION, CAT)
            RunRows.run(store, ANNS_RUN, GROUP, 2, "Refund for Greno", HANDLE, HANDLE_VERSION, ANN)
            RunRows.run(store, OTHER_GROUPS_RUN, OTHER_GROUP, 1, "Claim from Arlo", ELSEWHERE, ELSEWHERE_VERSION, CAT)
            RunRows.stopped(store, ANNS_RUN, ANN)
            [(CATS_RUN): "2026-09-25T08:00:00Z", (ANNS_RUN): "2026-09-25T09:00:00Z"].each { run, at ->
                // DB-SPECIFIC: PostgreSQL ::uuid casts and the timestamptz type name.
                store.session.sql("update runs set created_at = cast(? as timestamptz) where run_id = ?::uuid")
                        .params(at, run).update()
            }
            (1..ListPage.SIZE + 1).each { number ->
                RunRows.run(store, String.format("00000008-0000-4000-8000-%012d", 1410 + number), PAGED_GROUP,
                        number, "Paged ${number}", PAGED, PAGED_VERSION, ANN)
            }
            store
        }

        @Bean
        DataSource database(LibraryStore store) {
            store.database
        }

        @Bean
        JdbcClient session(LibraryStore store) {
            store.session
        }

        @Bean
        PlatformTransactionManager transactionManager(LibraryStore store) {
            store.transactionManager()
        }
    }
}
