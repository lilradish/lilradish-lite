package com.lilradish.lite.app.run

import static com.lilradish.lite.app.run.fixture.TicketWorkflow.GROUP
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.RUN
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARISE
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARISE_VERSION
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARY
import static com.lilradish.lite.testutil.library.LibraryStore.groupId

import com.lilradish.lite.app.run.fixture.TicketWorkflow
import com.lilradish.lite.domain.run.ProductionId
import com.lilradish.lite.domain.run.RunId
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.library.LibraryStore
import com.lilradish.lite.testutil.run.RunRows
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class RunBudgetIntegrationSpec extends Specification {

    static final String SYSTEM = RunRows.WORKFLOW_RUNNER

    static final String OTHER_RUN = "00000008-0000-4000-8000-000000000e31"

    @Shared
    @AutoCleanup
    TestPostgres server = TestPostgres.worker()

    @Shared
    int databasesMade = 0

    LibraryStore store

    def setupSpec() {
        LibraryStore.prepareBaseline(server)
    }

    def setup() {
        store = LibraryStore.forCase(server, "run_budget_" + (++databasesMade))
        TicketWorkflow.seed(store)
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                update workflow_steps set producer = 'model', producer_model = 'general', producer_mode = 'ordinary',
                                          reviewer_model = 'general', reviewer_mode = 'ordinary'
                 where workflow_step_id = ?::uuid
                """).params(SUMMARISE).update()
        TicketWorkflow.run(store)
        TicketWorkflow.run(store, OTHER_RUN, 2)
    }

    private String produced(String run, int number) {
        // DB-SPECIFIC: PostgreSQL ::uuid casts, RETURNING, ON CONFLICT.
        def runStep = store.session.sql("""
                insert into run_steps (run_id, entry_version_id, workflow_step_id, step_kind, pinned_version_id,
                                       pinned_kind, producer, reviewed_by_model, tries, created_by, created_by_kind)
                select ?::uuid, step.entry_version_id, step.workflow_step_id, step.kind, step.pinned_version_id,
                       step.pinned_kind, step.producer, step.reviewed_by_model, step.tries, ?::uuid, 'system'
                  from workflow_steps step
                 where step.workflow_step_id = ?::uuid
                on conflict (run_id, workflow_step_id) do update set run_id = excluded.run_id
                returning cast(run_step_id as text)
                """).params(run, SYSTEM, SUMMARISE).query(String).single()
        // DB-SPECIFIC: PostgreSQL ::uuid casts, RETURNING.
        def production = store.session.sql("""
                insert into productions (run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                                         reviewed_by_model, tries, pinned_version_id, try_number, producer, created_by)
                values (?::uuid, ?::uuid, ?::uuid, 'question', 'model', true, 2, ?::uuid, ?, 'model', ?::uuid)
                returning cast(production_id as text)
                """).params(runStep, run, run, SUMMARISE_VERSION, number, SYSTEM).query(String).single()
        def call = called(run, runStep, production, "produce", "came_back", 100, 40)
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
        store.session.sql("""
                update productions set model_call_id = ?::uuid, model_call_outcome = 'came_back', ended_at = now(),
                                       ended_by = ?::uuid, ended_by_kind = 'system'
                 where production_id = ?::uuid
                """).params(call, SYSTEM, production).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into production_values (production_id, run_step_kind, producer, pinned_version_id,
                                               declaration_field_id, field_standing, value)
                values (?::uuid, 'question', 'model', ?::uuid, ?::uuid, 'never', '"A printer fire."')
                """).params(production, SUMMARISE_VERSION, SUMMARY).update()
        production
    }

    private void reviewCalled(String run, String production, String outcome, long sent) {
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        def runStep = store.session.sql("select cast(run_step_id as text) from productions where production_id = ?::uuid")
                .params(production).query(String).single()
        called(run, runStep, production, "review", outcome, sent, outcome == "came_back" ? 10L : null)
    }

    private String called(String run, String runStep, String production, String purpose, String outcome, long sent,
                          Long cameBack) {
        // DB-SPECIFIC: PostgreSQL ::uuid casts, RETURNING, enum type model_call_purpose.
        def attempt = store.session.sql("""
                insert into run_step_send_attempts (run_step_id, run_id, run_step_kind, workflow_step_id, purpose, model,
                                                    mode, production_id, payload, created_by, created_by_kind)
                values (?::uuid, ?::uuid, 'question', ?::uuid, cast(? as model_call_purpose), 'general', 'ordinary',
                        ?::uuid, '{}', ?::uuid, 'system')
                returning cast(run_step_send_attempt_id as text)
                """).params(runStep, run, SUMMARISE, purpose, production, SYSTEM).query(String).single()
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now(), RETURNING, enum type model_call_purpose, enum type
        // model_call_outcome.
        store.session.sql("""
                insert into model_calls (run_id, root_run_id, purpose, run_step_send_attempt_id, run_step_id,
                                         production_id, model, mode, envelope_version, sent_count, came_back_count,
                                         outcome, answer, ended_at, created_by)
                values (?::uuid, ?::uuid, cast(? as model_call_purpose), ?::uuid, ?::uuid, ?::uuid, 'general',
                        'ordinary', 1, ?, ?, cast(? as model_call_outcome),
                        case when cast(? as text) = 'came_back' then '{}' end,
                        case when cast(? as text) is null then null else now() end, ?::uuid)
                returning cast(model_call_id as text)
                """).params(run, run, purpose, attempt, runStep, production, sent, cameBack, outcome, outcome, outcome,
                SYSTEM).query(String).single()
    }

    private static ProductionId productionId(String spelled) {
        new ProductionId(UUID.fromString(spelled))
    }

    private void countedByModel(String production) {
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                update model_calls set counted_by_model = true
                 where production_id = ?::uuid and purpose = 'produce'
                """).params(production).update()
    }

    def "the ceiling in force is read under a tree's lock only while the transaction that locked it lasts"() {
        given:
        def run = new RunId(UUID.fromString(RUN))
        def tree = new RunTree(store.session)

        expect: "inside it, what one reading of the store sees"
        store.transactions().execute {
            RunBudget.inForce(store.session, tree.lock(groupId(GROUP), run).orElseThrow(), run)
        } == RunBudget.inForceAsRead(store.session, run)

        when:
        def locked = store.transactions().execute { tree.lock(groupId(GROUP), run).orElseThrow() }
        RunBudget.inForce(store.session, locked, run)

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "Run tree ${RUN} was used outside the transaction that locked it" as String
    }

    def "what the calls about each try spent is read by the try, a call turned away counting for nothing"() {
        given:
        def first = produced(RUN, 1)
        reviewCalled(RUN, first, "turned_away", 500)
        def second = produced(RUN, 2)
        reviewCalled(RUN, second, "came_back", 30)
        def elsewhere = produced(OTHER_RUN, 1)
        RunRows.called(store, RUN, "came_back", 700, 70)

        when:
        def spent = RunBudget.spentByTryAsRead(store.session, new RunId(UUID.fromString(RUN)))

        then:
        spent == [(productionId(first)) : new RunBudget.Spend(100, 40, false, 1, 1),
                  (productionId(second)): new RunBudget.Spend(130, 50, false, 2, 2)]

        and: "the other run's try none of this run's"
        !spent.containsKey(productionId(elsewhere))
    }

    def "a call about a try not yet come back leaves how much came back for it unknown, and is counted at what it sent"() {
        given:
        def first = produced(RUN, 1)
        reviewCalled(RUN, first, null, 30)

        expect:
        RunBudget.spentByTryAsRead(store.session, new RunId(UUID.fromString(RUN))) ==
                [(productionId(first)): new RunBudget.Spend(130, 40, true, 2, 2)]
    }

    def "a call waiting after a turnaway spends nothing until it is actually resent"() {
        given:
        def first = produced(RUN, 1)
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        def runStep = store.session.sql("select run_step_id::text from productions where production_id = ?::uuid")
                .params(first).query(String).single()
        def waiting = called(RUN, runStep, first, "review", null, 30, null)
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into model_call_turnaways (model_call_id, said, created_by)
                values (?::uuid, 'Demo Sample busy', ?::uuid)
                """).params(waiting, SYSTEM).update()
        def run = new RunId(UUID.fromString(RUN))

        expect:
        RunBudget.spentByTryAsRead(store.session, run) ==
                [(productionId(first)): new RunBudget.Spend(100, 40, false, 1, 1)]
        RunBudget.spentAsRead(store.session, run, true) == new RunBudget.Spend(100, 40, false, 1, 1)

        when:
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
        store.session.sql("""
                update model_call_turnaways set resent_at = greatest(now(), created_at)
                 where model_call_id = ?::uuid
                """).params(waiting).update()

        then:
        RunBudget.spentByTryAsRead(store.session, run) ==
                [(productionId(first)): new RunBudget.Spend(130, 40, true, 2, 2)]
        RunBudget.spentAsRead(store.session, run, true) == new RunBudget.Spend(130, 40, true, 2, 2)
    }

    def "a try's spend and its run's say whether any of it is what this system measured rather than what the model counted"() {
        given:
        def first = produced(RUN, 1)
        if (modelCounted) {
            countedByModel(first)
        }
        if (reviewed != "not reviewed") {
            reviewCalled(RUN, first, reviewed == "review out" ? null : "turned_away", 30)
        }
        def run = new RunId(UUID.fromString(RUN))

        when:
        def byTry = RunBudget.spentByTryAsRead(store.session, run)
        def whole = RunBudget.spentAsRead(store.session, run, true)

        then:
        byTry == [(productionId(first)): new RunBudget.Spend(sent, 40, unknown, measured, total)]
        whole == new RunBudget.Spend(sent, 40, unknown, measured, total)

        where:
        modelCounted | reviewed       || sent | unknown | measured | total
        true         | "not reviewed" || 100  | false   | 0        | 1
        false        | "not reviewed" || 100  | false   | 1        | 1
        true         | "review out"   || 130  | true    | 1        | 2
        true         | "turned away"  || 100  | false   | 0        | 1
    }

    def "a run no call was made about spent nothing on any try"() {
        given:
        RunRows.called(store, RUN, "came_back", 700, 70)

        expect:
        RunBudget.spentByTryAsRead(store.session, new RunId(UUID.fromString(RUN))) == [:]
    }
}
