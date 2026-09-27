package org.lilradish.lite.app.run

import static org.lilradish.lite.testutil.library.LibraryStore.groupId
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
import org.lilradish.lite.app.run.fixture.StepRows
import org.lilradish.lite.domain.identity.EstateRole
import org.lilradish.lite.domain.identity.GroupPermission
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.listing.ListCursor
import org.lilradish.lite.domain.listing.ListOrder
import org.lilradish.lite.domain.listing.ListPage
import org.lilradish.lite.domain.listing.ListPosition
import org.lilradish.lite.domain.listing.ListQuery
import org.lilradish.lite.domain.run.RunSortColumn
import org.lilradish.lite.testutil.library.LibraryStore
import org.lilradish.lite.testutil.run.RunRows
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

/**
 * A group's runs as a member is answered them over a real dispatcher and a real store: which runs, spelt as a
 * page draws them, and every way of naming no group in view answered alike. Only who is calling is replaced.
 */
@WebMvcTest(RunListController)
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
@Import([RunList, RunSnapshots, org.lilradish.lite.app.codestep.CodeSteps, GroupRoles, Store])
class RunListControllerIntegrationSpec extends Specification {

    static final String GROUP = "00000003-0000-4000-8000-000000001401"

    static final String OTHER_GROUP = "00000003-0000-4000-8000-000000001402"

    /** More runs than a page holds, all Ann's. */
    static final String PAGED_GROUP = "00000003-0000-4000-8000-000000001403"

    /** An overseer here and in the paged group, and in no role in the other group. */
    static final String ANN = "00000002-0000-4000-8000-000000001401"

    static final UserId ANN_USER = new UserId("001401")

    /** An operator here and in the paged group, held with no name. */
    static final String CAT = "00000002-0000-4000-8000-000000001403"

    static final UserId CAT_USER = new UserId("001403")

    static final String HANDLE = "00000006-0000-4000-8000-000000001401"

    static final String HANDLE_VERSION = "00000007-0000-4000-8000-000000001401"

    static final String ELSEWHERE = "00000006-0000-4000-8000-000000001402"

    static final String ELSEWHERE_VERSION = "00000007-0000-4000-8000-000000001402"

    static final String PAGED = "00000006-0000-4000-8000-000000001403"

    static final String PAGED_VERSION = "00000007-0000-4000-8000-000000001403"

    /** Named with two spaces in a row, as its starter typed it. */
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

    /** Sent as written: a template would encode each percent sign again. */
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

    /** Everything an answer says, but the address it was sent to, which is each caller's own. */
    private static List<Object> apartFromWhere(MvcResult answered) {
        def document = JSON.convertValue(document(answered), Map)
        [answered.response.status, answered.response.contentType, document.findAll { it.key != "instance" }]
    }

    /** A stopped run is on no step, and says so by leaving it out rather than with a null. */
    def "answers every run of the group newest first to one who may read every run, spelt as a page draws it"() {
        when:
        def answered = reading("/api/groups/${GROUP}/runs", ANN_USER)

        then:
        answered.response.status == 200
        document(answered) == JSON.readTree("""
                {"items":[
                  {"runId":"${ANNS_RUN}","number":2,"name":"Refund for Grace",
                   "workflow":{"name":"Handle a claim","version":1},
                   "startedBy":{"userId":"001401","displayName":"Ann Example"},
                   "startedAt":"2026-09-25T09:00:00Z","lastHappenedAt":"2026-09-25T09:30:00Z","state":"stopped"},
                  {"runId":"${CATS_RUN}","number":1,"name":"Claim  from Ada",
                   "workflow":{"name":"Handle a claim","version":1},
                   "startedBy":{"userId":"001403"},
                   "startedAt":"2026-09-25T08:00:00Z","lastHappenedAt":"2026-09-25T08:00:00Z","state":"running",
                   "at":"ask"}],
                 "reading":"all"}
                """ as String)
    }

    def "answers one who may read only their own runs with those, and no other, saying it read their own"() {
        when:
        def answered = reading("/api/groups/${GROUP}/runs?sort=run", CAT_USER)

        then:
        answered.response.status == 200
        runIds(answered) == [CATS_RUN]
        !runIds(answered).contains(ANNS_RUN)
        !runIds(answered).contains(OTHER_GROUPS_RUN)
        document(answered).get("reading").asString() == "own"
    }

    /** A run's name is matched as typed, spaces and all; a workflow's once what was typed is spaced as names are. */
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
        "GRACE"             || [ANNS_RUN]
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

    /** A group the caller holds nothing in, one nobody holds, and an address that is no identifier. */
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

    /** Where a run is is worked out as it is read, so the runs never sort by it. */
    def "refuses a column the runs do not sort by, naming no run: #sort"() {
        when:
        def answered = reading("/api/groups/${GROUP}/runs?sort=${sort}", ANN_USER)

        then:
        answered.response.status == 400
        document(answered).get("code").asString() == "LIST_SORT_UNUSABLE"
        !answered.response.contentAsString.contains("Refund for Grace")

        where:
        sort << ["cost", "where", "-where"]
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
            def store = LibraryStore.copied(server, "run_list_answers")
            store.person(ANN, "001401", "Ann Example")
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
            RunRows.run(store, CATS_RUN, GROUP, 1, "Claim  from Ada", HANDLE, HANDLE_VERSION, CAT)
            RunRows.run(store, ANNS_RUN, GROUP, 2, "Refund for Grace", HANDLE, HANDLE_VERSION, ANN)
            RunRows.run(store, OTHER_GROUPS_RUN, OTHER_GROUP, 1, "Claim from Alan", ELSEWHERE, ELSEWHERE_VERSION, CAT)
            RunRows.stopped(store, ANNS_RUN, ANN)
            [(CATS_RUN): "2026-09-25T08:00:00Z", (ANNS_RUN): "2026-09-25T09:00:00Z"].each { run, at ->
                store.session.sql("update runs set created_at = cast(? as timestamptz) where run_id = ?::uuid")
                        .params(at, run).update()
            }
            store.session.sql("update run_stops set created_at = cast(? as timestamptz) where run_id = ?::uuid")
                    .params("2026-09-25T09:30:00Z", ANNS_RUN).update()
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
