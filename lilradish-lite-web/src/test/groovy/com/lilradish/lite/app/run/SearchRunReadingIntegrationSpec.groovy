package com.lilradish.lite.app.run

import static com.lilradish.lite.app.run.fixture.TicketWorkflow.*
import static com.lilradish.lite.testutil.library.LibraryStore.SEEDER
import static com.lilradish.lite.testutil.run.RunRows.WORKFLOW_RUNNER
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get

import com.lilradish.lite.app.currency.GroupCurrencies
import com.lilradish.lite.app.group.GroupReach
import com.lilradish.lite.app.group.GroupRoles
import com.lilradish.lite.app.run.fixture.StepRows
import com.lilradish.lite.app.run.fixture.TicketWorkflow
import com.lilradish.lite.app.search.SearchWays
import com.lilradish.lite.domain.identity.GroupId
import com.lilradish.lite.domain.identity.GroupPermission
import com.lilradish.lite.domain.identity.GroupRole
import com.lilradish.lite.domain.run.RunId
import com.lilradish.lite.domain.run.WorkflowStepId
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.CountingDataSource
import com.lilradish.lite.testutil.codestep.CodeStepsHeld
import com.lilradish.lite.testutil.library.DeployedModels
import com.lilradish.lite.testutil.library.LibraryStore
import com.lilradish.lite.testutil.run.RunRows
import com.lilradish.lite.web.ActAdmission
import com.lilradish.lite.web.CallerAdmission
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import javax.sql.DataSource
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DelegatingDataSource
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import spock.mock.MockMakers
import tools.jackson.databind.json.JsonMapper

class SearchRunReadingIntegrationSpec extends Specification {

    // DB-SPECIFIC: the SQL fixtures and predicates below input/compare PostgreSQL native enum labels.
    static final String ENTRY = '00000006-0000-4000-8000-00000000ec01'
    static final String EDITION = '00000007-0000-4000-8000-00000000ec01'
    static final String STEP = '00000009-0000-4000-8000-00000000ec01'
    static final String RUN_STEP = '0000000e-0000-4000-8000-00000000ec01'
    static final String TRY = '0000000f-0000-4000-8000-00000000ec01'
    static final String READ = '0000000d-0000-4000-8000-00000000ec01'
    static final String PIN_ENTRY = '00000006-0000-4000-8000-00000000ec02'
    static final String PIN_VERSION = '00000007-0000-4000-8000-00000000ec02'
    static final String QUESTION = 'Demo Sample question, as sent'

    @Shared @AutoCleanup TestPostgres server = TestPostgres.worker()
    @Shared int databasesMade
    LibraryStore store
    RunSteps reader

    def setupSpec() { LibraryStore.prepareBaseline(server) }

    def setup() {
        store = LibraryStore.forCase(server, 'search_reading_' + (++databasesMade))
        TicketWorkflow.seed(store)
        StepRows.workflow(store, ENTRY, EDITION, GROUP, 'Demo Sample searching')
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: EDITION, ownerKind: 'workflow',
                              side: 'takes', position: 1, name: 'ticket', limit: 4000, mustBe: true])
        pinQuestion(PIN_ENTRY, PIN_VERSION, 'Fictional source finder')
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into workflow_steps (workflow_step_id, entry_version_id, position, name, kind,
                    search_way, producer, producer_model, producer_mode, tries, search_title_standing,
                    search_body_standing, search_references_standing, search_other_standing, pinned_version_id, pinned_kind, created_by)
                values (?::uuid, ?::uuid, 1, 'look_up', 'search', 'web', 'model', 'general', 'ordinary', 1,
                    'always', 'always', 'never', 'always', ?::uuid, 'question', ?::uuid)
                """).params(STEP, EDITION, PIN_VERSION, SEEDER).update()
        StepRows.run(store, RUN, GROUP, 1, ENTRY, EDITION, CAT, '{"ticket":"Demo Sample input"}')
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into run_steps (run_step_id, run_id, entry_version_id, workflow_step_id, step_kind,
                    producer, reviewed_by_model, tries, pinned_version_id, pinned_kind, created_by)
                values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'search', 'model', false, 1, ?::uuid, 'question', ?::uuid)
                """).params(RUN_STEP, RUN, EDITION, STEP, PIN_VERSION, WORKFLOW_RUNNER).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into productions (production_id, run_step_id, run_id, root_run_id, run_step_kind,
                    step_producer, reviewed_by_model, tries, pinned_version_id, producer, created_by)
                values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'search', 'model', false, 1, ?::uuid, 'model', ?::uuid)
                """).params(TRY, RUN_STEP, RUN, RUN, PIN_VERSION, WORKFLOW_RUNNER).update()
        reader = readerOver(store.session)
    }

    private RunSteps readerOver(session, database = store.database) {
        new RunSteps(session, new GroupRoles(session), new RunSnapshots(session, CodeStepsHeld.NONE,
                new SearchWays([DeployedModels.WEB])), store.transactionManager(database), Deployments.DEEP_ENOUGH,
                new GroupCurrencies(session, new org.springframework.transaction.support.TransactionTemplate(store.transactionManager(database)), new GroupRoles(session), DeployedModels.SEARCHING), DeployedModels.SEARCHING)
    }

    private String attempt(String question = QUESTION, boolean tooLong = false, String repeats = null, String production = TRY, String runStep = RUN_STEP, String planned = STEP) {
        // DB-SPECIFIC: PostgreSQL ::uuid casts, RETURNING.
        store.session.sql("""
                insert into run_step_send_attempts (run_step_id, run_id, run_step_kind, workflow_step_id,
                    purpose, model, mode, production_id, payload, too_long, repeats_attempt_id, created_by, created_by_kind)
                values (?::uuid, ?::uuid, 'search', ?::uuid, 'produce', 'general', 'ordinary', ?::uuid,
                    ?, ?, ?::uuid, ?::uuid, 'system') returning cast(run_step_send_attempt_id as text)
                """).params(runStep, RUN, planned, production, repeats == null ? JsonMapper.builder().build().writeValueAsString([envelope_version: 5, instruction: question, takes: [:]]) : null,
                tooLong, repeats, WORKFLOW_RUNNER).query(String).single()
    }

    private String call(String attempt, String outcome = null, String production = TRY, String runStep = RUN_STEP) {
        // DB-SPECIFIC: PostgreSQL ::model_call_outcome/::text/::uuid casts, now(), RETURNING.
        store.session.sql("""
                insert into model_calls (run_id, root_run_id, purpose, run_step_send_attempt_id,
                    run_step_id, production_id, model, mode, envelope_version, sent_count, outcome,
                    ended_at, answer, error_detail, came_back_count, created_by)
                values (?::uuid, ?::uuid, 'produce', ?::uuid, ?::uuid, ?::uuid, 'general', 'ordinary', 5, 5,
                    ?::model_call_outcome, case when ?::text is null then null else now() end, ?, ?, ?, ?::uuid)
                returning cast(model_call_id as text)
                """).params(RUN, RUN, attempt, runStep, production, outcome, outcome, outcome == 'came_back' ? '{}' : null,
                outcome == 'errored' ? 'Demo Sample error' : null, outcome == 'came_back' ? 0L : null, WORKFLOW_RUNNER).query(String).single()
    }

    private String counted(String outcome = 'came_back', long uses = 2, boolean said = true, String production = TRY, int number = 1) {
        def sent = call(attempt(QUESTION, false, null, production), outcome, production)
        // DB-SPECIFIC: PostgreSQL ::did_not_fit_reason/::model_call_outcome/::try_lost_reason/::uuid casts,
        // now().
        store.session.sql("""
                update productions set try_number = ?, model_call_id = ?::uuid, model_call_outcome = ?::model_call_outcome,
                    ended_at = now(), ended_by = ?::uuid, lost_reason = ?::try_lost_reason, did_not_fit_reason = ?::did_not_fit_reason
                 where production_id = ?::uuid
                """).params(number, sent, outcome, WORKFLOW_RUNNER, outcome == 'came_back' ? 'did_not_fit' : outcome, outcome == 'came_back' ? 'not_the_shape' : null, production).update()
        // DB-SPECIFIC: PostgreSQL ::model_call_outcome/::uuid casts.
        store.session.sql("""
                insert into model_call_tool_uses (model_call_id, run_id, model_call_outcome, tool, most_uses, uses, uses_said)
                values (?::uuid, ?::uuid, ?::model_call_outcome, 'web_search', 5, ?, ?)
                """).params(sent, RUN, outcome, uses, said).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("insert into model_call_tool_prices (model_call_id, currency, per_use) values (?::uuid, 'USD', 0.0100)")
                .param(sent).update()
        sent
    }

    private StepAnswers.StepAnswer detail(caller = ANN_USER, RunSteps by = reader) {
        by.step(new GroupId(UUID.fromString(GROUP)), new RunId(UUID.fromString(RUN)),
                new WorkflowStepId(UUID.fromString(STEP)), caller)
    }

    private StepAnswers.TryDetailsRecord material(int number = 1, caller = ANN_USER, RunSteps by = reader) {
        by.tryDetails(new GroupId(UUID.fromString(GROUP)), new RunId(UUID.fromString(RUN)),
                new WorkflowStepId(UUID.fromString(STEP)), number, caller)
    }


    // DB-SPECIFIC: SQL in this method uses PostgreSQL :: casts.
    private void pinQuestion(String entry, String version, String name, String instruction = QUESTION) {
        StepRows.question(store, entry, version, GROUP, name, instruction)
        store.session.sql("update question_versions set search_way = 'web' where entry_version_id = ?::uuid")
                .param(version).update()
        DeployedModels.WEB.outputs().eachWithIndex { output, index ->
            StepRows.field(store, [id: UUID.randomUUID().toString(), owner: version, ownerKind: 'question',
                                  side: 'gives', position: index, name: output.name().value(),
                                  limit: ((com.lilradish.lite.domain.declaration.FieldShape.Text) output.shape()).longest(),
                                  mustBe: output.demand().mustBe(), standing: 'never'])
        }
    }

    // DB-SPECIFIC: SQL in this method uses PostgreSQL :: casts.
    private String copyInput(String source, String parent, int position, String name) {
        def id = UUID.randomUUID().toString()
        store.session.sql("""
                insert into declaration_fields (declaration_field_id, entry_version_id, entry_kind, side,
                    parent_field_id, position, name, label, help, kind, holds_many, text_limit, text_lines,
                    many_limit, must_be_given, term_list_version_id, created_by)
                select ?::uuid, ?::uuid, 'question', 'takes', ?::uuid, ?, ?, label, help, kind,
                       holds_many, text_limit, text_lines, many_limit, must_be_given, term_list_version_id, ?::uuid
                  from declaration_fields where declaration_field_id = ?::uuid
                """).params(id, PIN_VERSION, parent, position, name, SEEDER, source).update()
        store.session.sql("""
                select declaration_field_id::text as id, position, name from declaration_fields
                 where parent_field_id = ?::uuid order by position
                """).param(source).query().listOfRows().each { child ->
            copyInput(child.id, id, child.position as int, child.name)
        }
        id
    }

    // DB-SPECIFIC: SQL in this method uses PostgreSQL :: casts.
    private void readBinding(String id = READ, int position = 1, String path = 'ticket',
                             String sourceStep = null, String target = null) {
        def name = target ?: path
        def fields = sourceStep == null
                ? store.session.sql("""
                        select declaration_field_id::text from declaration_fields
                         where entry_version_id = ?::uuid and side = 'takes' and parent_field_id is null and name = ?
                        """).params(EDITION, path).query(String).list()
                : store.session.sql("""
                        select field.declaration_field_id::text from declaration_fields field
                          join workflow_steps step on step.workflow_step_id = ?::uuid
                         where field.side = 'gives' and field.parent_field_id is null and field.name = ?
                           and (field.entry_version_id = step.pinned_version_id or field.workflow_step_id = step.workflow_step_id)
                        """).params(sourceStep, path).query(String).list()
        assert fields.size() == 1
        copyInput(fields.first(), null, position, name)
        StepRows.binding(store, id, EDITION, STEP, name, sourceStep, path)
    }

    // DB-SPECIFIC: SQL in this method uses PostgreSQL ::uuid casts, jsonb and now().
    private void yieldPersonInputs() {
        store.session.sql("""
                update productions set ended_at = now(), ended_by = ?::uuid, ended_by_kind = 'person',
                       explanation = 'Fictional findings checked.'
                 where production_id = ?::uuid and producer = 'person'
                """).params(CAT, TRY).update()
        ['title', 'body', 'references', 'other'].each { name ->
            store.session.sql("""
                    insert into production_values (production_id, run_step_kind, producer, field_name, field_standing, value)
                    values (?::uuid, 'search', 'person', ?, 'always', cast(? as jsonb))
                    """).params(TRY, name, name in ['title', 'body'] ? '"Fictional finding"' : null).update()
        }
    }

    private void savedRead(String id = READ, String source = null) {
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        def maker = source == null ? [:] : store.session.sql("""
                select made.production_id::text as production, made.run_step_id::text as step,
                       held.workflow_step_id::text as planned, held.kind::text as kind
                  from production_values value join productions made using (production_id)
                  join run_steps held using (run_step_id) where value.production_value_id = ?::uuid
                """).param(source).query().singleRow()
        // DB-SPECIFIC: PostgreSQL ::run_step_kind/::uuid casts.
        store.session.sql("""
                insert into production_inputs (production_id, run_id, root_run_id, run_step_id,
                    run_step_kind, workflow_step_id, binding_id, binding_reads_a_constant,
                    source_production_value_id, source_production_id, source_workflow_step_id,
                    source_run_step_id, source_run_step_kind)
                values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'search', ?::uuid, ?::uuid, false,
                        ?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::run_step_kind)
                """).params(TRY, RUN, RUN, RUN_STEP, STEP, id, source, maker.production, maker.planned,
                maker.step, maker.kind).update()
    }

    private Map earlierSearch(boolean byModel) {
        def planned = UUID.randomUUID().toString()
        def held = UUID.randomUUID().toString()
        def production = UUID.randomUUID().toString()
        def pin = UUID.randomUUID().toString()
        pinQuestion(UUID.randomUUID().toString(), pin, 'Fictional earlier source ' + planned, 'Find earlier fictional evidence.')
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into workflow_steps (workflow_step_id, entry_version_id, position, name, kind,
                    search_way, producer, producer_model, producer_mode, tries, search_title_standing,
                    search_body_standing, search_references_standing, search_other_standing, pinned_version_id, pinned_kind, created_by)
                values (?::uuid, ?::uuid, 0, 'looked_up', 'search', 'web', 'model', 'general', 'ordinary', 1,
                    'always', 'always', 'always', 'always', ?::uuid, 'question', ?::uuid)
                """).params(planned, EDITION, pin, SEEDER).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into run_steps (run_step_id, run_id, entry_version_id, workflow_step_id, step_kind,
                    producer, reviewed_by_model, tries, pinned_version_id, pinned_kind, created_by)
                values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'search', 'model', false, 1, ?::uuid, 'question', ?::uuid)
                """).params(held, RUN, EDITION, planned, pin, WORKFLOW_RUNNER).update()
        // DB-SPECIFIC: PostgreSQL ::step_producer/::uuid casts.
        store.session.sql("""
                insert into productions (production_id, run_step_id, run_id, root_run_id, run_step_kind,
                    step_producer, reviewed_by_model, tries, pinned_version_id, producer, try_number, created_by)
                values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'search', 'model', false, 1, ?::uuid, ?::step_producer, 1, ?::uuid)
                """).params(production, held, RUN, RUN, pin, byModel ? 'model' : 'person', WORKFLOW_RUNNER).update()
        if (byModel) {
            def sent = call(attempt('Demo Sample earlier question', false, null, production, held, planned), 'came_back', production, held)
            // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
            store.session.sql("update productions set model_call_id = ?::uuid, model_call_outcome = 'came_back', ended_at = now(), ended_by = ?::uuid where production_id = ?::uuid")
                    .params(sent, WORKFLOW_RUNNER, production).update()
        } else {
            // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
            store.session.sql("update productions set ended_at = now(), ended_by = ?::uuid, ended_by_kind = 'person', explanation = 'Demo Sample' where production_id = ?::uuid")
                    .params(CAT, production).update()
        }
        ['title', 'body', 'references', 'other'].each { field ->
            // DB-SPECIFIC: PostgreSQL ::jsonb/::step_producer/::uuid casts.
            store.session.sql("""
                    insert into production_values (production_id, run_step_kind, producer, field_name, field_standing, value)
                    values (?::uuid, 'search', ?::step_producer, ?, 'always', ?::jsonb)
                    """).params(production, byModel ? 'model' : 'person', field,
                    field in ['title', 'body'] ? '"Demo Sample output"' : null).update()
        }
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        def value = store.session.sql("select production_value_id::text from production_values where production_id = ?::uuid and field_name = 'references'")
                .param(production).query(String).single()
        [planned: planned, held: held, pin: pin, production: production, value: value]
    }

    private void answeringByPerson(boolean tried = true) {
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql('delete from productions where production_id = ?::uuid').param(TRY).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql('delete from run_steps where run_step_id = ?::uuid').param(RUN_STEP).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("update workflow_steps set producer = 'person', producer_model = null, producer_mode = null where workflow_step_id = ?::uuid").param(STEP).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into run_steps (run_step_id, run_id, entry_version_id, workflow_step_id, step_kind,
                    producer, reviewed_by_model, tries, pinned_version_id, pinned_kind, created_by)
                values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'search', 'person', false, 1, ?::uuid, 'question', ?::uuid)
                """).params(RUN_STEP, RUN, EDITION, STEP, PIN_VERSION, WORKFLOW_RUNNER).update()
        if (tried) {
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("""
                    insert into productions (production_id, run_step_id, run_id, root_run_id, run_step_kind,
                        step_producer, reviewed_by_model, tries, pinned_version_id, producer, try_number, created_by, created_by_kind)
                    values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'search', 'person', false, 1, ?::uuid, 'person', 1, ?::uuid, 'person')
                    """).params(TRY, RUN_STEP, RUN, RUN, PIN_VERSION, CAT).update()
        } else {
            RunRows.stopped(store, RUN, CAT)
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("""
                    insert into run_step_holds (run_step_id, run_id, run_step_kind, calls_a_model, reason, created_by)
                    values (?::uuid, ?::uuid, 'search', false, 'entry_stopped', ?::uuid)
                    """).params(RUN_STEP, RUN, WORKFLOW_RUNNER).update()
        }
    }

    private Map childReport(boolean routed) {
        def childEntry = UUID.randomUUID().toString()
        def childVersion = UUID.randomUUID().toString()
        def childRun = UUID.randomUUID().toString()
        def planned = UUID.randomUUID().toString()
        def held = UUID.randomUUID().toString()
        StepRows.workflow(store, childEntry, childVersion, GROUP, 'Demo Sample child')
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: childVersion, ownerKind: 'workflow',
                              side: 'takes', position: 1, name: 'item', limit: 4000, mustBe: true])
        def report = UUID.randomUUID().toString()
        StepRows.field(store, [id: report, owner: childVersion, ownerKind: 'workflow',
                              side: 'gives', position: 1, name: 'report', kind: 'fields', mustBe: true])
        ['first', 'later'].eachWithIndex { name, index ->
            def field = UUID.randomUUID().toString()
            StepRows.field(store, [id: field, owner: childVersion, ownerKind: 'workflow', side: 'gives',
                                  parent: report, position: index + 1, name: name, limit: 4000, mustBe: true])
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("update declaration_fields set label = ? where declaration_field_id = ?::uuid")
                    .params('Demo Sample child ' + name, field).update()
            StepRows.binding(store, UUID.randomUUID().toString(), childVersion, null, 'report.' + name, null, 'item')
        }
        if (!routed) {
            RunRows.beneath(store, childRun, RUN, GROUP, 2, childEntry, childVersion, planned, held, 0)
            StepRows.binding(store, UUID.randomUUID().toString(), EDITION, planned, 'item', null, 'ticket')
        } else {
            def terms = UUID.randomUUID().toString()
            def termsVersion = UUID.randomUUID().toString()
            store.entry(terms, GROUP, 'reference_list', 'Demo Sample directions')
            store.seeded(termsVersion, terms, 1)
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("insert into reference_list_versions (entry_version_id, note, created_by) values (?::uuid, 'Demo Sample directions', ?::uuid)")
                    .params(termsVersion, SEEDER).update()
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("insert into reference_list_terms (entry_version_id, position, term, meaning, created_by) values (?::uuid, 1, 'go', 'Demo Sample go', ?::uuid)")
                    .params(termsVersion, SEEDER).update()
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("""
                    insert into declaration_fields (entry_version_id, entry_kind, side, position,
                        name, kind, term_list_version_id, must_be_given, created_by)
                    values (?::uuid, 'workflow', 'takes', 2, 'category', 'term', ?::uuid, true, ?::uuid)
                    """).params(EDITION, termsVersion, SEEDER).update()
            // DB-SPECIFIC: PostgreSQL ::jsonb/::uuid casts, jsonb || concatenation.
            store.session.sql('update runs set started_with = started_with || ?::jsonb where run_id = ?::uuid')
                    .params('{"category":"go"}', RUN).update()
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("insert into workflow_steps (workflow_step_id, entry_version_id, position, name, kind, created_by) values (?::uuid, ?::uuid, 0, 'demo_sample_route', 'route', ?::uuid)")
                    .params(planned, EDITION, SEEDER).update()
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("insert into bindings (entry_version_id, workflow_step_id, step_kind, source_path, created_by) values (?::uuid, ?::uuid, 'route', 'category', ?::uuid)")
                    .params(EDITION, planned, SEEDER).update()
            def routeCase = UUID.randomUUID().toString()
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("insert into route_cases (route_case_id, entry_version_id, workflow_step_id, term, target_version_id, created_by) values (?::uuid, ?::uuid, ?::uuid, 'go', ?::uuid, ?::uuid)")
                    .params(routeCase, EDITION, planned, childVersion, SEEDER).update()
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("insert into bindings (entry_version_id, route_case_id, target_path, source_path, created_by) values (?::uuid, ?::uuid, 'item', 'ticket', ?::uuid)")
                    .params(EDITION, routeCase, SEEDER).update()
            def routeReport = UUID.randomUUID().toString()
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("insert into declaration_fields (declaration_field_id, workflow_step_id, side, position, name, label, kind, must_be_given, created_by) values (?::uuid, ?::uuid, 'gives', 1, 'report', 'Demo Sample route report', 'fields', true, ?::uuid)")
                    .params(routeReport, planned, SEEDER).update()
            ['later', 'first'].eachWithIndex { name, index ->
                // DB-SPECIFIC: PostgreSQL ::uuid casts.
                store.session.sql("""
                        insert into declaration_fields (workflow_step_id, side, parent_field_id, position,
                            name, label, kind, text_limit, text_lines, must_be_given, created_by)
                        values (?::uuid, 'gives', ?::uuid, ?, ?, ?, 'text', 8000, 'several', true, ?::uuid)
                        """).params(planned, routeReport, index + 1, name, 'Demo Sample route ' + name, SEEDER).update()
            }
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("insert into run_steps (run_step_id, run_id, entry_version_id, workflow_step_id, step_kind, reviewed_by_model, created_by) values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'route', false, ?::uuid)")
                    .params(held, RUN, EDITION, planned, WORKFLOW_RUNNER).update()
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("""
                    insert into runs (run_id, group_id, number, entry_id, entry_version_id, parent_run_id,
                        parent_run_step_id, parent_run_step_kind, parent_workflow_step_id, parent_route_case_id,
                        root_run_id, depth, parent_depth, created_by, created_by_kind)
                    values (?::uuid, ?::uuid, 2, ?::uuid, ?::uuid, ?::uuid, ?::uuid, 'route', ?::uuid, ?::uuid,
                            ?::uuid, 1, 0, ?::uuid, 'system')
                    """).params(childRun, GROUP, childEntry, childVersion, RUN, held, planned, routeCase, RUN, WORKFLOW_RUNNER).update()
        }
        [planned: planned, held: held, childVersion: childVersion, childRun: childRun]
    }

    def 'a route term read uses the list its planned output pins even when the workflow itself pins no such list'() {
        given:
        answeringByPerson(tried)
        def child = childReport(true)
        def list = UUID.randomUUID().toString()
        def version = UUID.randomUUID().toString()
        store.entry(list, GROUP, 'reference_list', 'Demo Sample findings')
        store.seeded(version, list, 1)
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("insert into reference_list_versions (entry_version_id, note, created_by) values (?::uuid, 'Demo Sample findings', ?::uuid)")
                .params(version, SEEDER).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("insert into reference_list_terms (entry_version_id, position, term, meaning, created_by) values (?::uuid, 1, 'found', 'Demo Sample found', ?::uuid)")
                .params(version, SEEDER).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into declaration_fields (entry_version_id, entry_kind, side, position, name,
                    label, kind, term_list_version_id, must_be_given, created_by)
                values (?::uuid, 'workflow', 'gives', 2, 'tag', 'Demo Sample child tag', 'term', ?::uuid, true, ?::uuid)
                """).params(child.childVersion, version, SEEDER).update()
        StepRows.binding(store, UUID.randomUUID().toString(), child.childVersion, null, 'tag', null, null, '"found"')
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into declaration_fields (workflow_step_id, side, position, name,
                    label, kind, term_list_version_id, must_be_given, created_by)
                values (?::uuid, 'gives', 2, 'tag', 'Demo Sample route tag', 'term', ?::uuid, true, ?::uuid)
                """).params(child.planned, version, SEEDER).update()
        readBinding(READ, 1, 'tag', child.planned)
        if (tried) {
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("""
                    insert into production_inputs (production_id, run_id, root_run_id, run_step_id,
                        run_step_kind, workflow_step_id, binding_id, binding_reads_a_constant,
                        source_workflow_step_id, source_run_step_id, source_run_step_kind)
                    values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'search', ?::uuid, ?::uuid, false,
                            ?::uuid, ?::uuid, 'route')
                    """).params(TRY, RUN, RUN, RUN_STEP, STEP, READ, child.planned, child.held).update()
        }

        when:
        def page = detail()
        def input = page.wentIn().first()
        def tag = page.declarations()['route:' + child.planned].gives().find { it.name() == 'tag' }

        then:
        tag.kind() == 'term'
        tag.label() == 'Demo Sample route tag'
        tag.terms().terms() == [new com.lilradish.lite.app.filling.FillFieldAnswer.TermAnswer('found', 'Demo Sample found')]
        tag.terms().note() == 'Demo Sample findings'
        tag.longest() == null
        tag.fields() == null
        !page.declarations().containsKey(child.childVersion)
        input.input() == 'tag'
        input.value().toString() == '"found"'
        input.withheld() == null
        input.earlierShape() == null
        input.sources() == null
        input.from().kind() == 'constant'
        input.from().stepId() == null
        input.from().runId() == UUID.fromString(child.childRun)
        input.from().runNumber() == 2
        page.step().takesFrom().first().from().stepId() == UUID.fromString(child.planned)
        page.step().takesFrom().first().from().path() == 'tag'
        if (tried) assert page.answering().instruction() == QUESTION
        page.wentInFrom() == 'not_yet_sent'

        where:
        tried << [false, true]
    }

    def 'a search read keeps its planned child output declaration while each component names its real root input'() {
        given:
        answeringByPerson(tried)
        def child = childReport(routed)
        readBinding(READ, 1, 'report', child.planned)
        if (tried) {
            // DB-SPECIFIC: PostgreSQL ::run_step_kind/::text/::uuid casts, RETURNING.
            def input = store.session.sql("""
                    insert into production_inputs (production_id, run_id, root_run_id, run_step_id,
                        run_step_kind, workflow_step_id, binding_id, binding_reads_a_constant,
                        source_workflow_step_id, source_run_step_id, source_run_step_kind)
                    values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'search', ?::uuid, ?::uuid, false,
                            ?::uuid, ?::uuid, ?::run_step_kind) returning production_input_id::text
                    """).params(TRY, RUN, RUN, RUN_STEP, STEP, READ, child.planned, child.held,
                    routed ? 'route' : 'workflow').query(String).single()
            ['first', 'later'].each { name ->
                // DB-SPECIFIC: PostgreSQL ::uuid casts.
                store.session.sql('insert into production_input_parts (production_input_id, root_run_id, target_path) values (?::uuid, ?::uuid, ?)')
                        .params(input, RUN, name).update()
            }
        }

        when:
        def page = detail()
        def input = page.wentIn().first()
        def planned = page.step().takesFrom().first()
        def declaration = page.declarations()[routed ? 'route:' + child.planned : child.childVersion]

        then:
        planned.input() == 'report'
        planned.from().kind() == 'step'
        planned.from().stepId() == UUID.fromString(child.planned)
        planned.from().path() == 'report'
        planned.from().versionId() == (routed ? null : UUID.fromString(child.childVersion))
        planned.from().codeStep() == null
        planned.from().searchWay() == null
        declaration.gives()*.name() == ['report']
        declaration.gives().first().fields()*.name() == order
        declaration.gives().first().fields()*.label() == order.collect { 'Demo Sample ' + (routed ? 'route ' : 'child ') + it }
        declaration.gives().first().fields()*.longest() == [routed ? 8000 : 4000] * 2
        if (routed) {
            assert declaration.takes() == []
            assert !page.declarations().containsKey(child.childVersion)
        } else {
            assert declaration.takes()*.name() == ['item']
            assert !page.declarations().containsKey('route:' + child.planned)
        }
        input.input() == planned.input()
        input.from() == null
        input.withheld() == null
        input.earlierShape() == null
        input.sources()*.targetPath() == order
        input.sources()*.from()*.kind() == ['run_input', 'run_input']
        input.sources()*.from()*.path() == ['ticket', 'ticket']
        input.sources()*.from()*.runId() == [UUID.fromString(RUN)] * 2
        input.sources()*.from()*.runNumber() == [1, 1]
        !input.sources().any { it.from().stepId() != null }
        input.value().toString() == (routed ? '{"later":"Demo Sample input","first":"Demo Sample input"}' : '{"first":"Demo Sample input","later":"Demo Sample input"}')
        page.wentInFrom() == 'not_yet_sent'
        if (tried) assert page.answering().instruction() == QUESTION

        where:
        routed | tried || order
        false  | false || ['first', 'later']
        false  | true  || ['first', 'later']
        true   | false || ['later', 'first']
        true   | true  || ['later', 'first']
    }

    def 'an open person try reads typed inputs live and names their planned target'() {
        given:
        answeringByPerson(tried)
        // DB-SPECIFIC: PostgreSQL ::field_kind/::uuid casts.
        store.session.sql("update declaration_fields set kind = ?::field_kind, text_limit = case when ? = 'text' then 4000 else null end, text_lines = case when ? = 'text' then 'several' else null end, must_be_given = false where entry_version_id = ?::uuid and name = 'ticket'")
                .params(kind, kind, kind, EDITION).update()
        readBinding()
        // DB-SPECIFIC: PostgreSQL ::jsonb/::uuid casts.
        store.session.sql('update runs set started_with = ?::jsonb where run_id = ?::uuid').params(written, RUN).update()
        if (tried) savedRead()

        when:
        def page = detail()

        then:
        if (tried) {
            assert page.answering().instruction() == QUESTION
        } else {
            assert page.answering() == null
        }
        page.step().takesFrom()*.input() == ['ticket']
        page.step().takesFrom().first().from().kind() == 'run_input'
        page.step().takesFrom().first().from().path() == 'ticket'
        page.wentIn().first().input() == 'ticket'
        page.wentIn().first().from().runId() == UUID.fromString(RUN)
        page.wentIn().first().from().runNumber() == 1
        page.wentInFrom() == 'not_yet_sent'
        page.wentIn().first().value().toString() == value
        page.triesMade().every { it.search() == null }

        where:
        tried | kind     | written                               || value
        false | 'text'   | '{"ticket":"Demo Sample raw text"}'   || '"Demo Sample raw text"'
        true  | 'text'   | '{"ticket":"Demo Sample raw text"}'   || '"Demo Sample raw text"'
        false | 'number' | '{"ticket":12.5}'                     || '"12.5"'
        true  | 'yes_no' | '{"ticket":false}'                    || '"false"'
        true  | 'date'   | '{"ticket":"2026-10-03"}'            || '"2026-10-03"'
        false | 'text'   | '{}'                                  || 'null'
    }

    def 'an optional search output keeps its null row maker in live and historical inputs'() {
        given:
        answeringByPerson(tried)
        def made = earlierSearch(byModel)
        readBinding(READ, 1, 'references', made.planned)
        if (tried) {
            savedRead(READ, made.value)
            yieldPersonInputs()
        }

        when:
        def page = detail()
        def input = page.wentIn().first()
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        def stored = store.session.sql('select value is null as empty, field_standing::text as standing from production_values where production_value_id = ?::uuid')
                .param(made.value).query().singleRow()

        then:
        stored.empty
        stored.standing == 'always'
        page.answering() == null
        page.wentInFrom() == (tried ? 'try' : 'not_yet_sent')
        input.value().isNull()
        input.withheld() == null
        input.input() == 'references'
        input.from().kind() == 'step'
        input.from().path() == 'references'
        input.from().stepId() == UUID.fromString(made.planned)
        input.from().searchWay() == 'web'
        input.from().runId() == UUID.fromString(RUN)
        input.from().runNumber() == 1

        when:
        SpyStatic(GroupReach, MockMakers.mockito)
        def permissions = EnumSet.copyOf(GroupRole.OPERATOR.permissions())
        permissions.remove(GroupPermission.READ_INFERENCE_CONTENT)
        GroupReach.reachedBy(_ as Set) >> permissions
        def hidden = detail(CAT_USER)

        then:
        hidden.answering() == null
        hidden.wentIn().first().withheld() == (byModel ? true : null)
        if (byModel) assert hidden.wentIn().first().value() == null
        else assert hidden.wentIn().first().value().isNull()
        hidden.wentIn().first().from() == input.from()

        where:
        tried | byModel
        false | false
        true  | false
        false | true
        true  | true
    }

    def 'a never sent input keeps its offered term separate from the pinned instruction'() {
        given:
        answeringByPerson()
        def list = UUID.randomUUID().toString()
        def version = UUID.randomUUID().toString()
        store.entry(list, GROUP, 'reference_list', 'Demo Sample terms')
        store.seeded(version, list, 1)
        store.content(version, 'reference_list', 'Demo Sample terms')
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("insert into reference_list_terms (entry_version_id, position, term, meaning, created_by) values (?::uuid, 0, 'Demo Sample term', 'Demo Sample meaning', ?::uuid)")
                .params(version, SEEDER).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("update declaration_fields set kind = 'term', text_limit = null, text_lines = null, term_list_version_id = ?::uuid where entry_version_id = ?::uuid and name = 'ticket'")
                .params(version, EDITION).update()
        readBinding()
        // DB-SPECIFIC: PostgreSQL ::jsonb/::uuid casts.
        store.session.sql('update runs set started_with = ?::jsonb where run_id = ?::uuid')
                .params('{"ticket":"Demo Sample term"}', RUN).update()
        savedRead()

        when:
        def page = detail()

        then:
        page.answering().instruction() == QUESTION
        page.wentInFrom() == 'not_yet_sent'
        page.wentIn().first().value().asString() == 'Demo Sample term'
    }

    def 'a saved optional empty read keeps its original maker after the earlier search gives a value again'() {
        given:
        answeringByPerson()
        def original = earlierSearch(false)
        readBinding(READ, 1, 'references', original.planned)
        savedRead(READ, original.value)
        yieldPersonInputs()
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, now(), RETURNING.
        def again = store.session.sql("""
                insert into productions (run_step_id, run_id, root_run_id, run_step_kind,
                    step_producer, reviewed_by_model, tries, pinned_version_id, producer, try_number, created_by, created_by_kind,
                    ended_at, ended_by, ended_by_kind, explanation)
                values (?::uuid, ?::uuid, ?::uuid, 'search', 'model', false, 1, ?::uuid, 'person', 2, ?::uuid, 'person',
                    now(), ?::uuid, 'person', 'Demo Sample replacement') returning production_id::text
                """).params(original.held, RUN, RUN, original.pin, CAT, CAT).query(String).single()
        ['title', 'body', 'references', 'other'].each { field ->
            // DB-SPECIFIC: PostgreSQL ::jsonb/::uuid casts.
            store.session.sql("""
                    insert into production_values (production_id, run_step_kind, producer, field_name, field_standing, value)
                    values (?::uuid, 'search', 'person', ?, 'always', '"Demo Sample replacement"'::jsonb)
                    """).params(again, field).update()
        }

        when:
        def page = detail()

        then:
        page.answering() == null
        page.wentInFrom() == 'try'
        page.wentIn().first().value().isNull()
        page.wentIn().first().from().stepId() == UUID.fromString(original.planned)
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        store.session.sql('select source_production_value_id::text from production_inputs where production_id = ?::uuid and binding_id = ?::uuid')
                .params(TRY, READ).query(String).single() == original.value
    }

    // DB-SPECIFIC: SQL in this feature uses PostgreSQL :: casts.
    def 'two targets reading the same path keep separate stored binding identities'() {
        given:
        def second = '0000000d-0000-4000-8000-00000000ec02'
        readBinding(READ, 1, 'ticket', null, 'first_evidence')
        readBinding(second, 2, 'ticket', null, 'second_evidence')
        savedRead()
        savedRead(second)
        call(attempt())

        when:
        def page = detail()

        then:
        page.step().takesFrom()*.input() == ['first_evidence', 'second_evidence']
        page.wentIn()*.input() == page.step().takesFrom()*.input()
        page.wentIn()*.input() == ['first_evidence', 'second_evidence']
        page.wentIn()*.value()*.asString() == ['Demo Sample input', 'Demo Sample input']
        page.wentInFrom() == 'try'
        store.session.sql('select binding_id::text from production_inputs where production_id = ?::uuid order by binding_id')
                .param(TRY).query(String).list() == [READ, second]
    }

    def 'a sent search try requires its binding read and the store rejects a read belonging to another step'() {
        given:
        readBinding()
        String binding = null
        if (foreignInput) {
            def other = earlierSearch(false)
            binding = UUID.randomUUID().toString()
            StepRows.field(store, [id: UUID.randomUUID().toString(), owner: other.pin, ownerKind: 'question',
                                  side: 'takes', position: 1, name: 'ticket', limit: 4000, mustBe: true])
            StepRows.binding(store, binding, EDITION, other.planned, 'ticket', null, 'ticket')
        }
        call(attempt())
        def before = store.contents()

        when:
        if (foreignInput) savedRead(binding)
        else detail()

        then:
        def fault = thrown(RuntimeException)
        if (foreignInput) {
            assert fault instanceof org.springframework.dao.DataIntegrityViolationException
            assert fault.rootCause.getSQLState() == '23503'
            assert fault.rootCause.serverErrorMessage.constraint == 'production_inputs_binding_fk'
        } else {
            assert fault instanceof IllegalStateException
            assert fault.message == 'Try ' + TRY + ' has no input for binding ' + READ
        }
        store.contents() == before

        where:
        foreignInput << [false, true]
    }

    def 'a search with an old missing component maker withholds its stored read in the single step response'() {
        given:
        def child = childReport(false)
        readBinding(READ, 1, 'report', child.planned)
        def makerStep = UUID.randomUUID().toString()
        def answer = StepRows.askingSomebody(store, child.childVersion, GROUP, makerStep)
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        def questionVersion = store.session.sql('select pinned_version_id::text from workflow_steps where workflow_step_id = ?::uuid')
                .param(makerStep).query(String).single()
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, RETURNING.
        def heldStep = store.session.sql('''
                insert into run_steps (run_id, entry_version_id, workflow_step_id, step_kind,
                    pinned_version_id, pinned_kind, producer, reviewed_by_model, tries, created_by, created_by_kind)
                values (?::uuid, ?::uuid, ?::uuid, 'entry', ?::uuid, 'question', 'person', false, 1,
                    ?::uuid, 'system') returning run_step_id::text
                ''').params(child.childRun, child.childVersion, makerStep, questionVersion, WORKFLOW_RUNNER)
                .query(String).single()
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, now(), RETURNING.
        def made = store.session.sql('''
                insert into productions (run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                    reviewed_by_model, tries, pinned_version_id, try_number, producer,
                    created_by, created_by_kind, ended_at, ended_by, ended_by_kind, explanation)
                values (?::uuid, ?::uuid, ?::uuid, 'question', 'person', false, 1, ?::uuid, 1, 'person',
                    ?::uuid, 'system', now(), ?::uuid, 'person', 'Demo Sample')
                returning production_id::text
                ''').params(heldStep, child.childRun, RUN, questionVersion, WORKFLOW_RUNNER, CAT)
                .query(String).single()
        // DB-SPECIFIC: PostgreSQL ::jsonb/::uuid casts.
        store.session.sql('''
                insert into production_values (production_id, run_step_kind, producer, pinned_version_id,
                    declaration_field_id, field_standing, value)
                values (?::uuid, 'question', 'person', ?::uuid, ?::uuid, 'always', '"Demo Sample answer"'::jsonb)
                ''').params(made, questionVersion, answer).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        assert store.session.sql('''
                update bindings set source_step_id = ?::uuid, source_path = 'answer'
                 where entry_version_id = ?::uuid and workflow_step_id is null and target_path = 'report.later'
                ''').params(makerStep, child.childVersion).update() == 1
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, RETURNING.
        def input = store.session.sql('''
                insert into production_inputs (production_id, run_id, root_run_id, run_step_id,
                    run_step_kind, workflow_step_id, binding_id, binding_reads_a_constant,
                    source_workflow_step_id, source_run_step_id, source_run_step_kind)
                values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'search', ?::uuid, ?::uuid, false,
                    ?::uuid, ?::uuid, 'workflow') returning production_input_id::text
                ''').params(TRY, RUN, RUN, RUN_STEP, STEP, READ, child.planned, child.held)
                .query(String).single()
        ['first', 'later'].each { name ->
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql('''
                    insert into production_input_parts (production_input_id, root_run_id, target_path)
                    values (?::uuid, ?::uuid, ?)
                    ''').params(input, RUN, name).update()
        }

        call(attempt())

        when:
        def response = MockMvcBuilders.standaloneSetup(new RunStepsController(reader)).build()
                .perform(get("/api/groups/${GROUP}/runs/${RUN}/steps/${STEP}")
                        .requestAttr(CallerAdmission.class.name + '.caller', ANN_USER)
                        .requestAttr(ActAdmission.class.name + '.group', new GroupId(UUID.fromString(GROUP))))
                .andReturn().response
        def json = JsonMapper.builder().build().readTree(response.contentAsString)
        def read = json.path('wentIn').get(0)

        then:
        response.status == 200
        json.path('wentInFrom').asText() == 'try'
        read.path('input').asText() == 'report'
        !read.has('sourcePartId')
        read.path('from').path('kind').asText() == 'step'
        read.path('from').path('path').asText() == 'report'
        read.path('from').path('stepId').asText() == child.planned
        read.path('withheld').asBoolean()
        !read.has('value')
        !read.has('earlierShape')
        !read.has('withheldParts')
    }

    def 'accepted ended calls keep exact decimal fees and counts when their words are withheld'() {
        given:
        counted(outcome, uses, said)

        when:
        def visible = material().search()
        def visibleSummary = detail().triesMade().first().search()
        SpyStatic(GroupReach, MockMakers.mockito)
        def permissions = EnumSet.copyOf(GroupRole.OPERATOR.permissions())
        permissions.remove(GroupPermission.READ_INFERENCE_CONTENT)
        GroupReach.reachedBy(_ as Set) >> permissions
        def withheld = material(1, CAT_USER).search()
        def withheldSummary = detail(CAT_USER).triesMade().first().search()

        then:
        visible.uses() == uses.toString()
        visible.usesCounted() == (said ? 'said' : 'at_most')
        visible.fee().currency() == 'USD'
        visible.fee().perUse() == '0.0100'
        visible.fee().amount() == amount
        visible.searchesUnsaid() == (outcome == 'came_back' ? true : null)
        withheld.withheld()
        withheld.searches() == null
        withheld.searchesUnsaid() == null
        withheld.fee() == visible.fee()
        withheld.uses() == visible.uses()
        visibleSummary.fee() == visible.fee()
        visibleSummary.uses() == visible.uses()
        withheldSummary == visibleSummary

        where:
        outcome             | uses | said  | amount
        'came_back'         | 2L   | true  | '0.0200'
        'errored'           | 5L   | false | '0.0500'
        'nothing_came_back' | 5L   | false | '0.0500'
    }

    def 'an outgoing call reads stored inputs without a count or fee'() {
        given:
        call(attempt())
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql('update productions set try_number = 1 where production_id = ?::uuid').param(TRY).update()

        when:
        def page = detail()
        def search = material().search()

        then:
        page.wentInFrom() == 'try'
        page.wentIn() == []
        search.uses() == null
        search.usesCounted() == null
        search.fee() == null
        search.noFee() == null
        search.searchesUnsaid() == null
        detail().triesMade().first().search() == new StepAnswers.SearchCountAnswer(null, null, null, null)
    }

    def 'accepted calls and repeats keep sent provenance despite a newer unsent attempt'() {
        given:
        def original = attempt()
        call(original, 'turned_away')
        call(attempt(null, false, original), 'turned_away')
        def accepted = call(attempt(null, false, original), 'came_back')
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
        store.session.sql("""
                update productions set try_number = 1, model_call_id = ?::uuid, model_call_outcome = 'came_back',
                    ended_at = now(), ended_by = ?::uuid, lost_reason = 'did_not_fit', did_not_fit_reason = 'not_the_shape'
                 where production_id = ?::uuid
                """).params(accepted, WORKFLOW_RUNNER, TRY).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into model_call_tool_uses (model_call_id, run_id, model_call_outcome, tool, most_uses, uses, uses_said)
                values (?::uuid, ?::uuid, 'came_back', 'web_search', 5, 5, false)
                """).params(accepted, RUN).update()
        attempt('Demo Sample too long, never sent', true)

        when:
        def page = detail()

        then:
        page.answering().instruction() == QUESTION
        page.answering().number() == 2
        page.answering().beyond()
        page.wentInFrom() == 'try'
        page.wentIn() == []
        page.step().runs().searchWay() == 'web'
        page.step().runs().tool() == 'web_search'
        page.declarations()['search:web'].gives()*.name() == ['title', 'body', 'references', 'other']

        when:
        SpyStatic(GroupReach, MockMakers.mockito)
        def permissions = EnumSet.copyOf(GroupRole.OPERATOR.permissions())
        permissions.remove(GroupPermission.READ_INFERENCE_CONTENT)
        GroupReach.reachedBy(_ as Set) >> permissions
        def hidden = detail(CAT_USER)
        def hiddenMaterial = material(1, CAT_USER).search()

        then:
        hiddenMaterial.withheld()
        hiddenMaterial.searches() == null
        hidden.triesMade().first().search().uses() == '5'
        hidden.answering().instruction() == QUESTION
        hidden.wentInFrom() == 'try'
        hidden.step().runs().versionId() == UUID.fromString(PIN_VERSION)
    }

    // DB-SPECIFIC: SQL in this feature uses PostgreSQL :: casts.
    def 'a person answers a pinned search instruction with its drawing and no inputs'() {
        given:
        answeringByPerson()
        if (drawing != null) {
            store.session.sql('update question_versions set drawing = ? where entry_version_id = ?::uuid')
                    .params(drawing, PIN_VERSION).update()
        }

        when:
        def page = detail()

        then:
        page.answering().instruction() == QUESTION
        page.answering().gives()*.name() == ['title', 'body', 'references', 'other']
        page.drawing() == drawing
        page.wentIn() == []
        page.wentInFrom() == 'not_yet_sent'
        page.step().runs().entryId() == UUID.fromString(PIN_ENTRY)
        page.step().runs().versionId() == UUID.fromString(PIN_VERSION)
        page.declarations()[PIN_VERSION].takes() == []

        where:
        drawing << [null, '## Fictional search inputs']
    }

    def 'search records keep omitted halves separate from empty text and keep call-wide marks'() {
        given:
        def sent = counted()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into model_call_searches (model_call_id, position, searched_for, found)
                values (?::uuid, 1, 'Demo Sample sought', null), (?::uuid, 2, null, '')
                """).params(sent, sent).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                update model_call_tool_uses set searches_cut = true, searches_not_as_it_came = true,
                    searches_left_out = 3 where model_call_id = ?::uuid
                """).param(sent).update()

        when:
        def search = material().search()

        then:
        search.searches() == [new StepAnswers.SearchedAnswer('Demo Sample sought', null),
                              new StepAnswers.SearchedAnswer(null, '')]
        search.searchesUnsaid() == null
        search.searchesCut()
        search.searchesNotAsItCame()
        search.searchesLeftOut() == '3'
    }

    def 'current group currency chooses among the prices saved for this call without rounding'() {
        given:
        def sent = counted()
        if (chosen != null) {
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql('insert into group_currencies (group_id, currency, created_by) values (?::uuid, ?, ?::uuid)')
                    .params(GROUP, chosen, ANN).update()
        }
        if (twoPrices) {
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("insert into model_call_tool_prices (model_call_id, currency, per_use) values (?::uuid, 'EUR', 0.03000)")
                    .param(sent).update()
        }
        if (noPrices) {
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql('delete from model_call_tool_prices where model_call_id = ?::uuid').param(sent).update()
        }

        when:
        def tries = detail().triesMade()
        def original = material(1).search()

        then:
        !tries.empty
        tries.size() == 1
        def search = tries.first().search()
        search.fee()?.currency() == currency
        search.fee()?.amount() == amount
        search.noFee()?.reason() == reason
        search.noFee()?.currency() == absentCurrency
        original.uses() == search.uses()
        original.usesCounted() == search.usesCounted()
        original.fee() == search.fee()
        original.noFee() == search.noFee()

        where:
        chosen | twoPrices | noPrices || currency | amount    | reason            | absentCurrency
        null   | false     | false    || 'USD'    | '0.0200'  | null              | null
        'USD'  | true      | false    || 'USD'    | '0.0200'  | null              | null
        'EUR'  | true      | false    || 'EUR'    | '0.06000' | null              | null
        'EUR'  | false     | false    || null     | null      | 'not_in_currency' | 'EUR'
        null   | true      | false    || null     | null      | 'many_currencies' | null
        'USD'  | false     | true     || null     | null      | 'no_price'        | null
    }

    def 'withheld content is never queried and a list read fetches no search body or current currency'() {
        given:
        def sent = counted()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("insert into model_call_searches (model_call_id, position, searched_for, found) values (?::uuid, 1, ?, ?)")
                .params(sent, 'Demo Sample sought', 'Demo Sample found' * 10000).update()
        def statements = []
        def counted = new CountingDataSource(store.database, statements)
        def by = readerOver(JdbcClient.create(counted), counted)
        def visible = material(1, ANN_USER, by).search()
        assert visible.searches() == [new StepAnswers.SearchedAnswer('Demo Sample sought', 'Demo Sample found' * 10000)]
        assert statements.count { it.contains('from model_call_searches') } == 1
        statements.clear()
        SpyStatic(GroupReach, MockMakers.mockito)
        def permissions = EnumSet.copyOf(GroupRole.OPERATOR.permissions())
        permissions.remove(GroupPermission.READ_INFERENCE_CONTENT)
        GroupReach.reachedBy(_ as Set) >> permissions

        when:
        def hidden = detail(CAT_USER, by)
        def hiddenMaterial = material(1, CAT_USER, by).search()

        then:
        hiddenMaterial.withheld()
        hiddenMaterial.searches() == null
        hidden.triesMade().first().search().fee().amount() == '0.0200'
        statements.count { it.contains('from model_call_searches') } == 0
        statements.count { it.contains('holder.search_question') } == 0
        statements.count { it.contains('from group_currencies') } == 2
        !statements.isEmpty()
        statements.count { it.contains('payload') || it.contains('call.answer') } == 0

        when:
        statements.clear()
        def list = by.steps(new GroupId(UUID.fromString(GROUP)), new RunId(UUID.fromString(RUN)), CAT_USER)

        then:
        list.steps().size() == 1
        list.steps().first().state() == hidden.step().state()
        list.steps().first().where() == hidden.step().where()
        statements.count { it.contains('from model_call_searches') } == 0
        statements.count { it.contains('holder.search_question') } == 0
        statements.count { it.contains('from group_currencies') } == 0
    }

    def 'a held attempt keeps its reason and live or sent provenance until released'() {
        given:
        def original = attempt('Demo Sample held original', tooLong)
        def held = original
        if (!tooLong) {
            call(original, 'turned_away')
            held = attempt(null, false, original)
            call(held, 'turned_away')
        }
        // DB-SPECIFIC: PostgreSQL ::run_step_hold_reason/::uuid casts.
        store.session.sql("""
                insert into run_step_holds (run_step_id, run_id, run_step_kind, calls_a_model, reason,
                    run_step_send_attempt_id, created_by)
                values (?::uuid, ?::uuid, 'search', true, ?::run_step_hold_reason, ?::uuid, ?::uuid)
                """).params(RUN_STEP, RUN, reason, held, WORKFLOW_RUNNER).update()

        when:
        def page = detail()
        def list = reader.steps(new GroupId(UUID.fromString(GROUP)), new RunId(UUID.fromString(RUN)), ANN_USER)

        then:
        page.step().where().reason() == reason
        page.wentInFrom() == (tooLong ? 'not_yet_sent' : 'try')
        page.wentIn() == []
        page.triesMade().isEmpty()
        list.steps().first().where().reason() == reason

        when:
        SpyStatic(GroupReach, MockMakers.mockito)
        def permissions = EnumSet.copyOf(GroupRole.OPERATOR.permissions())
        permissions.remove(GroupPermission.READ_INFERENCE_CONTENT)
        GroupReach.reachedBy(_ as Set) >> permissions
        def hidden = detail(CAT_USER).step().where()
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
        store.session.sql("update run_step_holds set released_at = now(), released_by = ?::uuid where run_step_id = ?::uuid")
                .params(WORKFLOW_RUNNER, RUN_STEP).update()
        def released = detail(CAT_USER).step().where()

        then:
        hidden.reason() == reason
        released?.reason() == null

        where:
        reason        | tooLong
        'too_long'    | true
        'turned_away' | false
    }

    def 'run scope is checked before any search query for a nonmember'() {
        given:
        counted()
        def statements = []
        def counted = new CountingDataSource(store.database, statements)
        def by = readerOver(JdbcClient.create(counted), counted)

        when:
        detail(DAN_USER, by)

        then:
        def failure = thrown(org.libprunus.core.error.ApiErrorException)
        failure.errorCode() == com.lilradish.lite.domain.failure.RefusalCode.GROUP_NOT_IN_VIEW
        !statements.isEmpty()
        statements.count { it.contains('model_call_searches') || it.contains('payload') || it.contains('call.answer') } == 0
    }

    def 'a tool failure reads its saved tool and the producing model without claiming a review'() {
        given:
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql('update workflow_steps set producer_mode = ? where workflow_step_id = ?::uuid')
                .params(mode, STEP).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into run_step_failures (run_step_id, run_id, run_step_kind, calls_a_model,
                    reason, production_id, purpose, tool, created_by)
                values (?::uuid, ?::uuid, 'search', true, 'tool_not_offered', ?::uuid, 'produce',
                    'prior_search', ?::uuid)
                """).params(RUN_STEP, RUN, TRY, WORKFLOW_RUNNER).update()

        when:
        def page = detail()
        def where = page.step().where()

        then:
        where.kind() == 'failed'
        where.reason() == 'tool_not_offered'
        where.tool() == 'prior_search'
        where.model() == 'general'
        where.mode() == expectedMode
        where.reviewing() == null
        page.step().runs().tool() == 'web_search'

        where:
        mode       || expectedMode
        'ordinary' || null
        'research' || 'research'
    }

    def 'search summaries batch prices and currency while numbered materials read only their own records'() {
        given:
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql('delete from productions where production_id = ?::uuid').param(TRY).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql('delete from run_steps where run_step_id = ?::uuid').param(RUN_STEP).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql('update workflow_steps set tries = ? where workflow_step_id = ?::uuid')
                .params(howMany, STEP).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into run_steps (run_step_id, run_id, entry_version_id, workflow_step_id, step_kind,
                    producer, reviewed_by_model, tries, pinned_version_id, pinned_kind, created_by)
                values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'search', 'model', false, ?, ?::uuid, 'question', ?::uuid)
                """).params(RUN_STEP, RUN, EDITION, STEP, howMany, PIN_VERSION, WORKFLOW_RUNNER).update()
        (1..howMany).each { number ->
            def production = number == 1 ? TRY : UUID.randomUUID().toString()
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("""
                    insert into productions (production_id, run_step_id, run_id, root_run_id,
                        run_step_kind, step_producer, reviewed_by_model, tries, pinned_version_id, producer, created_by)
                    values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'search', 'model', false, ?, ?::uuid, 'model', ?::uuid)
                    """).params(production, RUN_STEP, RUN, RUN, howMany, PIN_VERSION, WORKFLOW_RUNNER).update()
            def sent = counted('came_back', number as long, true, production, number)
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("insert into model_call_searches (model_call_id, position, searched_for, found) values (?::uuid, 1, ?, null)")
                    .params(sent, 'Demo Sample question ' + number).update()
        }
        def statements = []
        def counted = new CountingDataSource(store.database, statements)
        def by = readerOver(JdbcClient.create(counted), counted)

        when:
        def tries = detail(ANN_USER, by).triesMade()

        then:
        tries.size() == howMany
        tries*.search()*.uses().toSet() == (1..howMany).collect { it.toString() }.toSet()
        statements.count { it.contains('from model_call_searches') } == 0
        statements.count { it.contains('from model_call_tool_prices') } == 1
        statements.count { it.contains('from group_currencies') } == 1

        when:
        statements.clear()
        def materials = tries.collect { material(it.number(), ANN_USER, by) }

        then:
        materials.every { it.search().searches().size() == 1 }
        materials.every { it.search().searches().first().searchedFor() == 'Demo Sample question ' + it.number() }
        statements.count { it.contains('from model_call_searches') } == howMany

        where:
        howMany << [1, 8]
    }

    def 'a concurrent currency choice cannot split the read page from its repeatable snapshot'() {
        given:
        def sent = counted()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("insert into model_call_tool_prices (model_call_id, currency, per_use) values (?::uuid, 'EUR', 0.03000)")
                .param(sent).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("insert into group_currencies (group_id, currency, created_by) values (?::uuid, 'USD', ?::uuid)")
                .params(GROUP, ANN).update()
        def connectionSettings = []
        def changing = new CurrencyChangeDataSource(store.database, connectionSettings, {
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("update group_currencies set currency = 'EUR' where group_id = ?::uuid")
                    .param(GROUP).update()
        } as Runnable)
        def by = readerOver(JdbcClient.create(changing), changing)

        when:
        def fee = detail(ANN_USER, by).triesMade().first().search().fee()

        then:
        fee.currency() == 'USD'
        fee.amount() == '0.0200'
        connectionSettings == [[true, Connection.TRANSACTION_REPEATABLE_READ]]
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql('select currency from group_currencies where group_id = ?::uuid')
                .param(GROUP).query(String).single() == 'EUR'
    }

    static final class CurrencyChangeDataSource extends DelegatingDataSource {
        final List settings
        final Runnable change
        boolean changed

        CurrencyChangeDataSource(DataSource target, List settings, Runnable change) {
            super(target)
            this.settings = settings
            this.change = change
        }

        @Override
        Connection getConnection() {
            new CurrencyChangeConnection(super.getConnection(), this)
        }
    }

    static final class CurrencyChangeConnection implements Connection {
        @Delegate final Connection connection
        final CurrencyChangeDataSource source

        CurrencyChangeConnection(Connection connection, CurrencyChangeDataSource source) {
            this.connection = connection
            this.source = source
        }

        PreparedStatement prepareStatement(String sql) {
            def statement = connection.prepareStatement(sql)
            sql.contains('join group_members holding')
                    ? new CurrencyChangeStatement(statement, connection, source) : statement
        }
    }

    static final class CurrencyChangeStatement implements PreparedStatement {
        @Delegate final PreparedStatement statement
        final Connection connection
        final CurrencyChangeDataSource source

        CurrencyChangeStatement(PreparedStatement statement, Connection connection, CurrencyChangeDataSource source) {
            this.statement = statement
            this.connection = connection
            this.source = source
        }

        ResultSet executeQuery() {
            def rows = statement.executeQuery()
            if (!source.changed) {
                source.changed = true
                source.settings << [connection.isReadOnly(), connection.transactionIsolation]
                source.change.run()
            }
            rows
        }
    }

    def 'serialized search wire omits withheld words and preserves string counts fees and an empty record half'() {
        given:
        def sent = counted()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("insert into model_call_searches (model_call_id, position, searched_for, found) values (?::uuid, 1, null, '')")
                .param(sent).update()
        if (!words) {
            SpyStatic(GroupReach, MockMakers.mockito)
            def permissions = EnumSet.copyOf(GroupRole.OPERATOR.permissions())
            permissions.remove(GroupPermission.READ_INFERENCE_CONTENT)
            GroupReach.reachedBy(_ as Set) >> permissions
        }
        def json = JsonMapper.builder().build()

        when:
        def wire = json.readTree(json.writeValueAsString(detail(words ? ANN_USER : CAT_USER)))
        def search = json.readTree(json.writeValueAsString(material(1, words ? ANN_USER : CAT_USER))).get('search')

        then:
        search == json.readTree(expected)
        wire.get('triesMade').get(0).get('search') == json.readTree('{"uses":"2","usesCounted":"said","fee":{"currency":"USD","perUse":"0.0100","amount":"0.0200"}}')
        wire.get('step').get('runs') == json.readTree('{"kind":"search","entryId":"' + PIN_ENTRY + '","name":"Fictional source finder","version":1,"versionId":"' + PIN_VERSION + '","versionName":null,"searchWay":"web","tool":"web_search"}')
        wire.get('declarations').get(PIN_VERSION) != null
        wire.get('step').get('where').get('searchQuestionSent') == null
        wire.get('declarations').get('search:web') != null

        where:
        words || expected
        true  || '{"searches":[{"found":""}],"uses":"2","usesCounted":"said","fee":{"currency":"USD","perUse":"0.0100","amount":"0.0200"}}'
        false || '{"withheld":true,"uses":"2","usesCounted":"said","fee":{"currency":"USD","perUse":"0.0100","amount":"0.0200"}}'
    }

}
