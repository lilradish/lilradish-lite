package com.lilradish.lite.app.run

import static org.mockito.ArgumentMatchers.any
import static org.mockito.BDDMockito.given
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put

import com.lilradish.lite.app.estate.EstateRoleGrants
import com.lilradish.lite.app.group.GroupRoles
import com.lilradish.lite.app.identification.UserIdentification
import com.lilradish.lite.app.run.fixture.StepRows
import com.lilradish.lite.domain.identity.EstateRole
import com.lilradish.lite.domain.identity.UserId
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.codestep.CodeStepsHeld
import com.lilradish.lite.testutil.inference.ScriptedModelCalls
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
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.transaction.PlatformTransactionManager
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper

/** Real roles, tree locks, changes and engine share one embedded store behind MVC. */
@WebMvcTest([RunsController, RunChangesController])
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
@Import([GroupRoles, Store])
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class RunStateActsControllerStoreIntegrationSpec extends Specification {
    static final String GROUP = "00000003-0000-4000-8000-000000006062"
    static final String OPERATOR = "00000002-0000-4000-8000-000000006063"
    static final String APPROVER = "00000002-0000-4000-8000-000000006064"
    static final String OTHER_APPROVER = "00000002-0000-4000-8000-000000006065"
    static final UserId OPERATOR_USER = new UserId("006063")
    static final UserId APPROVER_USER = new UserId("006064")
    static final UserId OTHER_APPROVER_USER = new UserId("006065")
    static final String WORKFLOW = "00000006-0000-4000-8000-000000006062"
    static final String VERSION = "00000007-0000-4000-8000-000000006062"
    static final JsonMapper JSON = JsonMapper.builder().build()

    @Autowired private MockMvc mockMvc
    @Autowired private LibraryStore store
    @Autowired private EngineWiring wiring
    @MockitoBean private UserIdentification identification
    @MockitoBean private EstateRoleGrants grants

    private void calling(UserId caller) {
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(caller))
        given(grants.heldBy(caller)).willReturn(EnumSet.noneOf(EstateRole))
    }

    private String made(String starter = OPERATOR) {
        def run = UUID.randomUUID().toString()
        def number = store.session.sql("select count(*) from runs").query(Long).single().intValue() + 1
        RunRows.run(store, run, GROUP, number, "An imaginary claim", WORKFLOW, VERSION, starter)
        run
    }

    private static String address(String run) { "/api/groups/${GROUP}/runs/${run}" }

    private def reading(String run, UserId caller) {
        calling(caller)
        def answer = mockMvc.perform(get(address(run))).andReturn()
        assert answer.response.status == 200
        JSON.readTree(answer.response.contentAsString)
    }

    private def sending(UserId caller, MockHttpServletRequestBuilder request) {
        calling(caller)
        mockMvc.perform(request.header("Sec-Fetch-Site", "same-origin")).andReturn()
    }

    private List<String> runRows() {
        ["runs", "run_stops", "run_ceiling_changes"].collect { store.digestOf(it) }
    }

    def "the requester and another authorized reader receive identical state acts and public requester facts"() {
        given:
        def run = made()
        def raise = UUID.randomUUID().toString()
        RunRows.ceilingChanged(store, raise, run, 1000L, 2000L, true, OPERATOR, 0)
        def before = runRows()

        when:
        def requester = reading(run, OPERATOR_USER)
        def approver = reading(run, APPROVER_USER)

        then:
        requester == approver
        requester.get("stateActs").collect { it.asString() } as Set ==
                ["stop", "rename", "change_ceiling", "approve_raise", "refuse_raise", "withdraw_raise"] as Set
        requester.get("ceiling").get("waiting").get("askedBy").get("userId").asString() == OPERATOR_USER.value()
        runRows() == before

        when:
        def noPermission = sending(OPERATOR_USER, put(address(run) + "/ceiling-changes/${raise}/approval"))

        then:
        noPermission.response.status == 403
        JSON.readTree(noPermission.response.contentAsString).get("code").asString() == "ACT_NOT_PERMITTED"
        runRows() == before

        when:
        def notRequester = sending(APPROVER_USER, put(address(run) + "/ceiling-changes/${raise}/withdrawal"))

        then:
        notRequester.response.status == 403
        JSON.readTree(notRequester.response.contentAsString).get("code").asString() == "CEILING_RAISE_ASKED_BY_ANOTHER"
        runRows() == before
    }

    def "an approver cannot approve or refuse a raise they asked for despite their permission"() {
        given:
        def run = made(OTHER_APPROVER)
        def raise = UUID.randomUUID().toString()
        RunRows.ceilingChanged(store, raise, run, 1000L, 2000L, true, OTHER_APPROVER, 0)
        def before = runRows()

        expect:
        reading(run, OTHER_APPROVER_USER) == reading(run, APPROVER_USER)

        when:
        def refused = sending(OTHER_APPROVER_USER, put(address(run) + "/ceiling-changes/${raise}/${decision}"))

        then:
        refused.response.status == 403
        JSON.readTree(refused.response.contentAsString).get("code").asString() == "CEILING_RAISE_ASKED_BY_CALLER"
        runRows() == before

        where:
        decision << ["approval", "refusal"]
    }

    def "each successful run mutation publishes the same state facts as the following real read"() {
        given:
        def run = made()
        def raise = UUID.randomUUID().toString()
        if (action == "open") {
            RunRows.stopped(store, run, OPERATOR)
        }
        if (action in ["approval", "refusal", "withdrawal"]) {
            RunRows.ceilingChanged(store, raise, run, 1000L, 2000L, true, OPERATOR, 0)
        }
        def before = runRows()
        def url = address(run)
        def request = switch (action) {
            case "rename" -> patch(url).contentType(MediaType.APPLICATION_JSON).content('{"name":"An imaginary claim renamed"}')
            case "stop" -> put(url + "/stop")
            case "open" -> delete(url + "/stop")
            case "ceiling" -> patch(url + "/ceiling").contentType(MediaType.APPLICATION_JSON).content('{"ceiling":"500"}')
            default -> put(url + "/ceiling-changes/${raise}/${action}")
        }
        def caller = action in ["approval", "refusal"] ? APPROVER_USER : OPERATOR_USER

        when:
        def answer = sending(caller, request)
        wiring.drained()

        then:
        answer.response.status == 200
        JSON.readTree(answer.response.contentAsString) == reading(run, caller)
        reading(run, OPERATOR_USER) == reading(run, APPROVER_USER)
        runRows() != before

        and: "the database records the original mutation rather than merely drawing its result"
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        action != "rename" || store.texts("select name from runs where run_id = ?::uuid", run) == ["An imaginary claim renamed"]
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        action != "stop" || store.texts("select created_by::text from run_stops where run_id = ?::uuid", run) == [OPERATOR]
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        action != "open" || store.texts("select opened_again_by::text from run_stops where run_id = ?::uuid", run) == [OPERATOR]
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        action != "ceiling" || store.texts("select to_ceiling::text from run_ceiling_changes where run_id = ?::uuid", run) == ["500"]
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        !(action in ["approval", "refusal", "withdrawal"]) || store.texts("select outcome::text from run_ceiling_changes where run_id = ?::uuid", run) == [outcome]

        where:
        action       || outcome
        "rename"     || null
        "stop"       || null
        "open"       || null
        "ceiling"    || null
        "approval"   || "approved"
        "refusal"    || "refused"
        "withdrawal" || "withdrawn_by_requester"
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Store {
        @Bean(destroyMethod = "close") TestPostgres server() { TestPostgres.worker() }
        @Bean LibraryStore store(TestPostgres server) {
            LibraryStore.prepareBaseline(server)
            def store = LibraryStore.forCase(server, "run_state_answers")
            store.person(OPERATOR, "006063", "Ari Requester")
            store.person(APPROVER, "006064", "Greno Approver")
            store.person(OTHER_APPROVER, "006065", "Olive Requester")
            store.group(GROUP, "STATE", "Imaginary claims")
            store.member(GROUP, OPERATOR, "operator")
            store.member(GROUP, APPROVER, "overseer")
            store.member(GROUP, OTHER_APPROVER, "overseer")
            RunRows.workflow(store, WORKFLOW, VERSION, GROUP, "Handle an imaginary claim", 1000L, true)
            StepRows.askingSomebody(store, VERSION, GROUP)
            store
        }
        @Bean DataSource database(LibraryStore store) { store.database }
        @Bean JdbcClient session(LibraryStore store) { store.session }
        @Bean PlatformTransactionManager transactions(LibraryStore store) { store.transactionManager() }
        @Bean(destroyMethod = "close") EngineWiring wiring(LibraryStore store) {
            new EngineWiring(store, new ScriptedModelCalls(), CodeStepsHeld.NONE)
        }
        @Bean Runs runs(LibraryStore store, GroupRoles roles, EngineWiring wiring) {
            new Runs(store.session, roles, store.transactionManager(), wiring.snapshots)
        }
        @Bean RunChanges changes(LibraryStore store, GroupRoles roles, EngineWiring wiring) {
            new RunChanges(store.session, store.transactions(), roles, wiring.tree, wiring.snapshots, wiring.engine)
        }
    }
}
