package com.lilradish.lite.app.run

import static com.lilradish.lite.app.run.fixture.TicketWorkflow.*
import static com.lilradish.lite.testutil.library.LibraryStore.groupId

import com.lilradish.lite.app.currency.GroupCurrencies
import com.lilradish.lite.app.group.GroupRoles
import com.lilradish.lite.app.library.ConstantJson
import com.lilradish.lite.app.run.fixture.StepRows
import com.lilradish.lite.app.run.fixture.TicketWorkflow
import com.lilradish.lite.app.search.SearchWays
import com.lilradish.lite.domain.model.DeployedModel
import com.lilradish.lite.domain.model.ModelCatalog
import com.lilradish.lite.domain.model.ModelName
import com.lilradish.lite.domain.run.RunId
import com.lilradish.lite.domain.run.RunStepHoldReason
import com.lilradish.lite.domain.run.WorkflowStepId
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.CountingDataSource
import com.lilradish.lite.testutil.codestep.CodeStepsHeld
import com.lilradish.lite.testutil.inference.ScriptedModelCalls
import com.lilradish.lite.testutil.library.DeployedModels
import com.lilradish.lite.testutil.library.LibraryStore
import org.libprunus.core.error.ApiErrorException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class StepReadStateIntegrationSpec extends Specification {
    @Shared @AutoCleanup TestPostgres server = TestPostgres.worker()
    @Shared int nextDatabase = 0
    LibraryStore store
    EngineWiring wiring
    RunSteps steps
    static final RunId RUN_ID = new RunId(UUID.fromString(RUN))
    static final WorkflowStepId STEP_ID = new WorkflowStepId(UUID.fromString(SUMMARISE))

    def setupSpec() { LibraryStore.prepareBaseline(server) }

    def setup() {
        store = LibraryStore.forCase(server, "step_read_state_" + (++nextDatabase))
        TicketWorkflow.seed(store)
        wiring = new EngineWiring(store, new ScriptedModelCalls(), CodeStepsHeld.NONE)
        def roles = new GroupRoles(store.session)
        steps = new RunSteps(store.session, roles, wiring.snapshots, store.transactionManager(), Deployments.DEEP_ENOUGH,
                new GroupCurrencies(store.session, new TransactionTemplate(store.transactionManager()), roles, DeployedModels.HELD), DeployedModels.HELD)
    }

    def cleanup() { wiring.close() }

    private void started(String starter = CAT) {
        store.transactions().executeWithoutResult {
            StepRows.run(store, RUN, GROUP, 1, WORKFLOW, VERSION, starter, STARTED_WITH)
            wiring.engine.planStarted(wiring.tree.lock(groupId(GROUP), RUN_ID).orElseThrow())
        }
        wiring.drained()
    }

    private void answered() {
        StepRows.answered(store, SUMMARISE, CAT, "Checked the imaginary incident.", [(SUMMARY): '"An imaginary printer fire."'])
    }

    private def read(def caller) { steps.step(groupId(GROUP), RUN_ID, STEP_ID, caller) }

    private List<String> writtenRows() {
        ["productions", "production_values", "reviews", "review_decisions", "run_steps"].collect { store.digestOf(it) }
    }

    def "one step read batches length refusals across every try and its answering preview"() {
        given:
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("update workflow_steps set tries = ? where workflow_step_id = ?::uuid")
                .params(refusals + 1, SUMMARISE).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts and step_producer enum label.
        store.session.sql("""
                update workflow_steps set producer = 'model', producer_model = 'general', producer_mode = 'ordinary'
                 where workflow_step_id = ?::uuid
                """).param(CONFIRM).update()
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: SUMMARISE_VERSION,
                              ownerKind: 'question', side: 'gives', position: 2, name: 'resolution',
                              limit: 1000, mustBe: true, standing: 'never'])
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: CONFIRM_VERSION,
                              ownerKind: 'question', side: 'takes', position: 2, name: 'resolution',
                              limit: 1000, mustBe: true])
        StepRows.binding(store, UUID.randomUUID().toString(), VERSION, CONFIRM,
                'resolution', SUMMARISE, 'resolution')
        def models = new ModelCatalog([new DeployedModel(new ModelName('general'), [], 600, 4.0G, 4_000, [], [])])
        wiring.close()
        wiring = new EngineWiring(store, new ScriptedModelCalls(), CodeStepsHeld.NONE, models)
        started(ANN)
        def targetId = new WorkflowStepId(UUID.fromString(CONFIRM))
        def summaries = []
        def reasons = []
        for (int number = 1; number <= Math.max(1, refusals); number++) {
            def summary = 'Account ' + number + ': ' + 'a' * 980
            summaries << summary
            wiring.acts.answer(groupId(GROUP), RUN_ID, STEP_ID, number, ANN_USER,
                    ConstantJson.sent('{"summary":"' + summary + '","resolution":"Checked account ' + number + '."}'),
                    'Checked the imaginary incident again.')
            wiring.drained()
            wiring.assure(groupId(GROUP), RUN_ID, STEP_ID, number, BEN_USER, ['summary', 'resolution'] as Set)
            wiring.drained()
            def context = wiring.snapshots.inTreeAsRead(groupId(GROUP), RUN_ID)
            def held = context.run().step(targetId).orElseThrow()
            assert held.hold().reason() == RunStepHoldReason.TOO_LONG
            if (refusals > 0) {
                def source = context.run().step(STEP_ID).orElseThrow()
                def value = source.tries().find { it.number() == number }.values().find { it.field() == 'summary' }.id()
                def hold = new RunReadDetails(store.session, context).lengthHold(held)
                wiring.acts.refuseForLength(groupId(GROUP), RUN_ID, targetId, ANN_USER, hold, value)
                wiring.drained()
                def after = wiring.snapshots.inTreeAsRead(groupId(GROUP), RUN_ID)
                reasons << after.run().step(STEP_ID).orElseThrow().tries().find { it.number() == number }
                        .reviews().find { it.forLength() }.decisionOn(value).orElseThrow().why()
            }
        }
        def before = writtenRows()
        def statements = []
        def counting = new CountingDataSource(store.database, statements)
        def session = JdbcClient.create(counting)
        def manager = store.transactionManager(counting)
        def roles = new GroupRoles(session)
        def counted = new RunSteps(session, roles, new RunSnapshots(session, CodeStepsHeld.NONE, new SearchWays([])),
                manager, Deployments.DEEP_ENOUGH,
                new GroupCurrencies(session, new TransactionTemplate(manager), roles, models), models)

        when:
        def answer = counted.step(groupId(GROUP), RUN_ID, STEP_ID, ANN_USER)

        then:
        statements.count { it.contains('select decision.production_value_id, run.run_id') } == (refusals == 0 ? 0 : 1)
        answer.triesMade()*.number() == (1..(refusals == 0 ? 1 : refusals + 1)).toList()
        def produced = answer.triesMade().findAll { !it.values().isEmpty() }
        produced.size() == Math.max(1, refusals)
        produced.collect { it.values()*.field() } == [['summary', 'resolution']] * produced.size()
        produced.collect { it.values()[0].value().asString() } == summaries
        !produced.any { it.values().any { value -> value.withheld() == Boolean.TRUE } }
        if (refusals > 0) {
            assert produced*.ended() == ['refused_for_length'] * refusals
            assert produced.collect { it.values()[0].decision().why() } == reasons
            assert produced.collect { it.values()[0].decision().lengthHeld().stepId() } == [UUID.fromString(CONFIRM)] * refusals
            assert produced.collect { it.values()[0].decision().lengthHeld().run().runId() } == [UUID.fromString(RUN)] * refusals
            assert produced.every { it.values()[1].decision().outcome() == 'assured' && it.values()[1].decision().lengthHeld() == null }
            assert answer.answering().lastRefused().number() == refusals
            assert answer.answering().lastRefused().values() == produced.last().values()
            assert answer.triesMade().last().values() == []
        } else {
            assert produced[0].ended() == 'stands'
            assert answer.answering() == null
        }
        writtenRows() == before

        where:
        refusals << [0, 1, 4]
    }

    def "two authorized readers receive the same waiting resource while permission and self review remain write refusals"() {
        given:
        started()
        answered()
        def before = writtenRows()

        when:
        def operator = read(CAT_USER)
        def reviewer = read(ANN_USER)

        then:
        operator == reviewer
        operator.step().stateActs() == ["review"]
        operator.step().stateRefusals() == []
        operator.step().producedByUserId() == CAT_USER.value()
        operator.step().producedByKind() == "person"
        operator.step().wentIn() == reviewer.step().wentIn()
        writtenRows() == before

        when:
        wiring.assure(groupId(GROUP), RUN_ID, STEP_ID, 1, CAT_USER, ["summary"] as Set)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode().toString() == "ACT_NOT_PERMITTED"
        writtenRows() == before

        when:
        // DB-SPECIFIC: PostgreSQL ::uuid cast and group_role enum label; change only the producer's role, not the production.
        store.session.sql("update group_members set role = 'overseer' where group_id = ?::uuid and subject_id = ?::uuid")
                .params(GROUP, CAT).update()
        wiring.assure(groupId(GROUP), RUN_ID, STEP_ID, 1, CAT_USER, ["summary"] as Set)

        then:
        def self = thrown(ApiErrorException)
        self.errorCode().toString() == "REVIEW_OWN_PRODUCTION"
        read(CAT_USER) == reviewer
        writtenRows() == before
    }

    def "revoking review while retaining read of the reader's own run changes no public resource and permits no write"() {
        given:
        started(ANN)
        answered()
        def before = read(ANN_USER)
        def rows = writtenRows()

        when:
        // DB-SPECIFIC: PostgreSQL ::uuid cast and group_role enum label; an operator still reads the run they started.
        store.session.sql("update group_members set role = 'operator' where group_id = ?::uuid and subject_id = ?::uuid")
                .params(GROUP, ANN).update()

        then:
        read(ANN_USER) == before
        read(ANN_USER).step().stateActs() == ["review"]

        when:
        wiring.assure(groupId(GROUP), RUN_ID, STEP_ID, 1, ANN_USER, ["summary"] as Set)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode().toString() == "ACT_NOT_PERMITTED"
        writtenRows() == rows
    }

    def "an unrelated nonmember cannot read either public step entrance"() {
        given:
        started()
        def before = writtenRows()

        when:
        steps.steps(groupId(GROUP), RUN_ID, DAN_USER)

        then:
        thrown(ApiErrorException)
        writtenRows() == before

        when:
        read(DAN_USER)

        then:
        thrown(ApiErrorException)
        writtenRows() == before
    }
}
