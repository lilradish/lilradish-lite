package com.lilradish.lite.app.run

import static com.lilradish.lite.app.run.fixture.TicketWorkflow.*
import static com.lilradish.lite.testutil.library.LibraryStore.groupId
import static org.mockito.ArgumentMatchers.any
import static org.mockito.BDDMockito.given
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put

import com.lilradish.lite.app.currency.GroupCurrencies
import com.lilradish.lite.app.estate.EstateRoleGrants
import com.lilradish.lite.app.group.GroupRoles
import com.lilradish.lite.app.identification.UserIdentification
import com.lilradish.lite.app.run.fixture.StepRows
import com.lilradish.lite.app.run.fixture.TicketWorkflow
import com.lilradish.lite.domain.identity.EstateRole
import com.lilradish.lite.domain.identity.UserId
import com.lilradish.lite.domain.run.RunId
import com.lilradish.lite.domain.run.WorkflowStepId
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.ReadyServletStreams
import com.lilradish.lite.testutil.codestep.CodeStepsHeld
import com.lilradish.lite.testutil.inference.ScriptedModelCalls
import com.lilradish.lite.testutil.library.DeployedModels
import com.lilradish.lite.testutil.library.LibraryStore
import jakarta.servlet.http.HttpServletRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
import org.springframework.transaction.support.TransactionTemplate
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper

/** Real group-role and tree checks keep resource offers separate from write authorization. */
@WebMvcTest([RunStepsController, StepActsController])
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
@Import([GroupRoles, ReadyServletStreams, Store])
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class StepReadStateControllerIntegrationSpec extends Specification {
    static final JsonMapper JSON = JsonMapper.builder().build()
    @Autowired MockMvc mvc
    @Autowired LibraryStore store
    @Autowired EngineWiring wiring
    @Autowired ScriptedModelCalls modelCalls
    @MockitoBean UserIdentification identification
    @MockitoBean EstateRoleGrants grants
    private String readStep = SUMMARISE

    private void calling(UserId caller) {
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(caller))
        given(grants.heldBy(caller)).willReturn(EnumSet.noneOf(EstateRole))
    }

    private String made(String starter = CAT, boolean model = false, String workflowName = "Summarise an imaginary incident", boolean heldModel = false) {
        def run = UUID.randomUUID().toString()
        def workflow = WORKFLOW
        def version = VERSION
        readStep = SUMMARISE
        if (model) {
            workflow = UUID.randomUUID().toString()
            version = UUID.randomUUID().toString()
            readStep = UUID.randomUUID().toString()
            StepRows.workflow(store, workflow, version, GROUP, workflowName)
            StepRows.step(store, readStep, version, 1, "summarise", SUMMARISE_VERSION, "model", 2)
            StepRows.binding(store, UUID.randomUUID().toString(), version, readStep, "text", null, null,
                    '"An imaginary printer fire."')
            // DB-SPECIFIC: PostgreSQL ::uuid cast and step_producer enum label; select the actual model fixture before the engine starts.
            store.session.sql("update workflow_steps set producer = 'model', producer_model = ?, producer_mode = ? where workflow_step_id = ?::uuid")
                    .params(heldModel ? "general" : "not_held", heldModel ? "research" : "ordinary", readStep).update()
        }
        store.transactions().executeWithoutResult {
            def number = store.session.sql("select count(*) from runs").query(Long).single().intValue() + 1
            StepRows.run(store, run, GROUP, number, workflow, version, starter, model ? '{}' : STARTED_WITH)
            wiring.engine.planStarted(wiring.tree.lock(groupId(GROUP), new RunId(UUID.fromString(run))).orElseThrow())
        }
        wiring.drained()
        run
    }

    private String address(String run) { "/api/groups/${GROUP}/runs/${run}/steps/${readStep}" }

    private def send(UserId caller, MockHttpServletRequestBuilder request) {
        calling(caller)
        def result = mvc.perform(request.header("Sec-Fetch-Site", "same-origin")).andReturn()
        if (result.request.asyncStarted) {
            result.getAsyncResult(5000)
            result = mvc.perform(asyncDispatch(result)).andReturn()
        }
        result
    }

    private def read(String run, UserId caller) {
        calling(caller)
        def result = mvc.perform(get(address(run))).andReturn()
        assert result.response.status == 200
        JSON.readTree(result.response.contentAsString)
    }

    private List<String> rows() {
        ["productions", "production_values", "reviews", "review_decisions", "run_steps", "run_step_send_attempts", "run_step_failures"].collect { store.digestOf(it) }
    }

    private Runnable pauseEngine() {
        def pool = wiring.executor.@pool
        def entered = new CountDownLatch(pool.corePoolSize)
        def released = new CountDownLatch(1)
        def ready = false
        try {
            pool.corePoolSize.times {
                pool.execute {
                    entered.countDown()
                    if (!released.await(20, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("The test did not release the engine queue")
                    }
                }
            }
            assert entered.await(5, TimeUnit.SECONDS)
            ready = true
        } finally {
            if (!ready) released.countDown()
        }
        return { released.countDown() } as Runnable
    }

    def cleanup() {
        wiring.drained()
        // DB-SPECIFIC: PostgreSQL ::uuid casts and group_role enum labels.
        store.session.sql("update group_members set role = 'overseer' where group_id = ?::uuid and subject_id = ?::uuid")
                .params(GROUP, ANN).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts and group_role enum labels; restore the preparer's original role.
        store.session.sql("update group_members set role = 'operator' where group_id = ?::uuid and subject_id = ?::uuid")
                .params(GROUP, CAT).update()
    }

    def "the four real mutation entrances retain their writes and answer with the same resource as the following GET"() {
        given:
        def run = made(CAT, action == "sending")
        def url = address(run)
        if (action in ["ask", "review"]) {
            wiring.acts.answer(groupId(GROUP), new RunId(UUID.fromString(run)), new WorkflowStepId(UUID.fromString(SUMMARISE)),
                    1, CAT_USER, new com.lilradish.lite.domain.wire.JsonValue.JsonObject([new com.lilradish.lite.domain.wire.JsonValue.JsonMember("summary", new com.lilradish.lite.domain.wire.JsonValue.JsonString("An imaginary printer fire."))]), "Checked the imaginary incident.")
            wiring.drained()
            if (action == "ask") {
                wiring.acts.review(groupId(GROUP), new RunId(UUID.fromString(run)), new WorkflowStepId(UUID.fromString(SUMMARISE)),
                        1, ANN_USER, [summary: new StepActs.ReviewedRecord(com.lilradish.lite.domain.run.ReviewOutcome.REFUSED, "More detail needed.")])
                wiring.drained()
            }
        }
        def before = rows()
        def request = switch (action) {
            case "answer" -> put(url + "/tries/1/answer").contentType(MediaType.APPLICATION_JSON).content('{"values":{"summary":"An imaginary printer fire."},"why":"Checked the imaginary incident."}')
            case "ask" -> put(url + "/tries/2")
            case "review" -> put(url + "/tries/1/review").contentType(MediaType.APPLICATION_JSON).content('{"decisions":{"summary":{"outcome":"assured"}}}')
            default -> put(url + "/sending")
        }
        def caller = action == "review" ? ANN_USER : CAT_USER
        def resume = action == "sending" ? pauseEngine() : { } as Runnable

        when:
        def changed
        def following
        def forCat
        def forAnn
        try {
            changed = send(caller, request)
            following = read(run, caller)
            forCat = read(run, CAT_USER)
            forAnn = read(run, ANN_USER)
        } finally {
            resume.run()
        }
        wiring.drained()

        then:
        changed.response.status == 200
        JSON.readTree(changed.response.contentAsString) == following
        forCat == forAnn
        ["stateActs", "stateRefusals", "producedByUserId", "producedByKind"].every {
            following.get("step").has(it)
        }
        rows() != before

        and: "a separate database observer proves the action, not only its rendered answer"
        // DB-SPECIFIC: PostgreSQL ::uuid casts and enum label comparisons.
        action != "answer" || store.count("select count(*) from productions where run_id = ?::uuid and ended_by = ?::uuid and ended_by_kind = 'person'", run, CAT) == 1
        // DB-SPECIFIC: PostgreSQL ::uuid cast.
        action != "ask" || store.count("select count(*) from productions where run_id = ?::uuid and try_number = 2", run) == 1
        // DB-SPECIFIC: PostgreSQL ::uuid cast and review_outcome enum label comparison.
        action != "review" || store.count("select count(*) from reviews review join productions production on production.production_id = review.production_id join review_decisions decision on decision.review_id = review.review_id where production.run_id = ?::uuid and decision.outcome = 'assured'", run) == 1
        // DB-SPECIFIC: PostgreSQL ::uuid cast and run_step_failure_reason enum comparison; each retry checks the unavailable model again.
        action != "sending" || store.count("select count(*) from run_step_failures where run_id = ?::uuid and reason = 'model_not_deployed'", run) == 2

        where:
        action << ["answer", "ask", "review", "sending"]
    }

    def "a reader retains their own run after losing review, while real MVC refuses the write without changing the store"() {
        given:
        def run = made(ANN)
        // DB-SPECIFIC: PostgreSQL ::uuid casts and group_role enum labels; the preparer must read somebody else's run to answer it.
        store.session.sql("update group_members set role = 'overseer' where group_id = ?::uuid and subject_id = ?::uuid")
                .params(GROUP, CAT).update()
        wiring.acts.answer(groupId(GROUP), new RunId(UUID.fromString(run)), new WorkflowStepId(UUID.fromString(SUMMARISE)),
                1, CAT_USER, new com.lilradish.lite.domain.wire.JsonValue.JsonObject([new com.lilradish.lite.domain.wire.JsonValue.JsonMember("summary", new com.lilradish.lite.domain.wire.JsonValue.JsonString("An imaginary printer fire."))]), "Checked the imaginary incident.")
        wiring.drained()
        def before = read(run, ANN_USER)
        def written = rows()

        when:
        // DB-SPECIFIC: PostgreSQL ::uuid casts and group_role enum labels; the starter retains read of their own run.
        store.session.sql("update group_members set role = 'operator' where group_id = ?::uuid and subject_id = ?::uuid")
                .params(GROUP, ANN).update()

        then:
        read(run, ANN_USER) == before
        before.get("step").get("stateActs").collect { it.asString() } == ["review"]

        when:
        def rejected = send(ANN_USER, put(address(run) + "/tries/1/review").contentType(MediaType.APPLICATION_JSON).content('{"decisions":{"summary":{"outcome":"assured"}}}'))

        then:
        rejected.response.status == 403
        JSON.readTree(rejected.response.contentAsString).get("code").asString() == "ACT_NOT_PERMITTED"
        rows() == written
    }

    def "a manually answered model plan publishes the actual person to both readers and refuses that person's own review"() {
        given:
        2.times {
            modelCalls.answering(new com.lilradish.lite.domain.inference.CallOutcome.CameBack("{}", 10, 1, true, false, null, null))
        }
        def run = made(CAT, true, "Summarise an imaginary equipment check", true)
        def url = address(run)
        def before = read(run, CAT_USER)
        assert modelCalls.scriptsLeft() == 0
        def number = before.get("step").get("next").get("number").intValue()

        when:
        def answered = send(CAT_USER, put(url + "/tries/${number}/answer")
                .contentType(MediaType.APPLICATION_JSON)
                .content('{"values":{"summary":"The imaginary printer was checked."},"why":"Read the imaginary incident."}'))
        wiring.drained()
        def forCat = read(run, CAT_USER)
        def forAnn = read(run, ANN_USER)

        then:
        answered.response.status == 200
        forCat == forAnn
        forCat.get("step").get("producer").get("kind").asString() == "model"
        forCat.get("step").get("producedByKind").asString() == "person"
        forCat.get("step").get("producedByUserId").asString() == CAT_USER.value()
        forCat.get("step").get("stateActs").collect { it.asString() } == ["review"]
        forCat.get("step").get("stateRefusals").isEmpty()
        // DB-SPECIFIC: PostgreSQL ::uuid casts and step_producer/subject_kind enum labels; independently observe plan and actual production.
        store.count("select count(*) from workflow_steps where workflow_step_id = ?::uuid and producer = 'model'", readStep) == 1
        store.count("select count(*) from productions where run_id = ?::uuid and producer = 'person' and ended_by_kind = 'person' and ended_by = ?::uuid and try_number = ?", run, CAT, number) == 1

        when:
        // DB-SPECIFIC: PostgreSQL ::uuid casts and group_role enum labels; grant review to the real producer so the writer must test self-review, not missing permission.
        store.session.sql("update group_members set role = 'overseer' where group_id = ?::uuid and subject_id = ?::uuid")
                .params(GROUP, CAT).update()
        def written = rows()
        def rejected = send(CAT_USER, put(url + "/tries/${number}/review")
                .contentType(MediaType.APPLICATION_JSON).content('{"decisions":{"summary":{"outcome":"assured"}}}'))

        then:
        rejected.response.status == 403
        JSON.readTree(rejected.response.contentAsString).get("code").asString() == "REVIEW_OWN_PRODUCTION"
        rows() == written
        read(run, CAT_USER) == forAnn
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Store {
        @Bean(destroyMethod = "close") TestPostgres server() { TestPostgres.worker() }
        @Bean LibraryStore store(TestPostgres server) {
            LibraryStore.prepareBaseline(server)
            def store = LibraryStore.forCase(server, "step_state_mvc")
            TicketWorkflow.seed(store)
            store
        }
        @Bean DataSource database(LibraryStore store) { store.database }
        @Bean JdbcClient session(LibraryStore store) { store.session }
        @Bean PlatformTransactionManager transactions(LibraryStore store) { store.transactionManager() }
        @Bean ScriptedModelCalls modelCalls() { new ScriptedModelCalls() }
        @Bean(destroyMethod = "close") EngineWiring wiring(LibraryStore store, ScriptedModelCalls modelCalls) {
            new EngineWiring(store, modelCalls, CodeStepsHeld.NONE)
        }
        @Bean StepActs acts(EngineWiring wiring) { wiring.acts }
        @Bean RunSteps steps(LibraryStore store, GroupRoles roles, EngineWiring wiring) {
            new RunSteps(store.session, roles, wiring.snapshots, store.transactionManager(), Deployments.DEEP_ENOUGH,
                    new GroupCurrencies(store.session, new TransactionTemplate(store.transactionManager()), roles, DeployedModels.HELD), DeployedModels.HELD)
        }
    }
}
