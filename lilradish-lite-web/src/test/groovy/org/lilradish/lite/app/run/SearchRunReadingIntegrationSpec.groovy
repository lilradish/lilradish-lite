package org.lilradish.lite.app.run

import static org.lilradish.lite.app.run.fixture.TicketWorkflow.*
import static org.lilradish.lite.testutil.library.LibraryStore.SEEDER
import static org.lilradish.lite.testutil.run.RunRows.WORKFLOW_RUNNER
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import javax.sql.DataSource
import org.lilradish.lite.app.currency.GroupCurrencies
import org.lilradish.lite.app.group.GroupReach
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.run.fixture.StepRows
import org.lilradish.lite.app.run.fixture.TicketWorkflow
import org.lilradish.lite.app.search.SearchWays
import org.lilradish.lite.domain.identity.GroupId
import org.lilradish.lite.domain.identity.GroupPermission
import org.lilradish.lite.domain.identity.GroupRole
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.domain.run.WorkflowStepId
import org.lilradish.lite.testutil.CountingDataSource
import org.lilradish.lite.testutil.codestep.CodeStepsHeld
import org.lilradish.lite.testutil.library.DeployedModels
import org.lilradish.lite.testutil.library.LibraryStore
import org.lilradish.lite.testutil.run.RunRows
import org.lilradish.lite.web.ActAdmission
import org.lilradish.lite.web.CallerAdmission
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DelegatingDataSource
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import spock.mock.MockMakers
import tools.jackson.databind.json.JsonMapper

class SearchRunReadingIntegrationSpec extends Specification {
    static final String ENTRY = '00000006-0000-4000-8000-00000000ec01'
    static final String EDITION = '00000007-0000-4000-8000-00000000ec01'
    static final String STEP = '00000009-0000-4000-8000-00000000ec01'
    static final String RUN_STEP = '0000000e-0000-4000-8000-00000000ec01'
    static final String TRY = '0000000f-0000-4000-8000-00000000ec01'
    static final String READ = '0000000d-0000-4000-8000-00000000ec01'
    static final String QUESTION = 'Demo Sample question, as sent'

    @Shared @AutoCleanup EmbeddedPostgres server = EmbeddedPostgres.builder().start()
    @Shared int databasesMade
    LibraryStore store
    RunSteps reader

    def setupSpec() { LibraryStore.template(server) }

    def setup() {
        store = LibraryStore.copied(server, 'search_reading_' + (++databasesMade))
        TicketWorkflow.seed(store)
        StepRows.workflow(store, ENTRY, EDITION, GROUP, 'Demo Sample searching')
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: EDITION, ownerKind: 'workflow',
                              side: 'takes', position: 1, name: 'ticket', limit: 4000, mustBe: true])
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into workflow_steps (workflow_step_id, entry_version_id, position, name, kind,
                    search_way, producer, producer_model, producer_mode, tries, search_title_standing,
                    search_body_standing, search_references_standing, search_other_standing, created_by)
                values (?::uuid, ?::uuid, 1, 'look_up', 'search', 'web', 'model', 'general', 'ordinary', 1,
                    'always', 'always', 'never', 'always', ?::uuid)
                """).params(STEP, EDITION, SEEDER).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into search_question_parts (search_question_part_id, entry_version_id, workflow_step_id,
                    position, literal_text, source_path, created_by)
                values (?::uuid, ?::uuid, ?::uuid, 0, 'Demo Sample: ', null, ?::uuid)
                """).params(UUID.randomUUID().toString(), EDITION, STEP, SEEDER).update()
        StepRows.run(store, RUN, GROUP, 1, ENTRY, EDITION, CAT, '{"ticket":"Demo Sample input"}')
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into run_steps (run_step_id, run_id, entry_version_id, workflow_step_id, step_kind,
                    producer, reviewed_by_model, tries, created_by)
                values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'search', 'model', false, 1, ?::uuid)
                """).params(RUN_STEP, RUN, EDITION, STEP, WORKFLOW_RUNNER).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into productions (production_id, run_step_id, run_id, root_run_id, run_step_kind,
                    step_producer, reviewed_by_model, tries, producer, created_by)
                values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'search', 'model', false, 1, 'model', ?::uuid)
                """).params(TRY, RUN_STEP, RUN, RUN, WORKFLOW_RUNNER).update()
        reader = readerOver(store.session)
    }

    private RunSteps readerOver(session, database = store.database) {
        new RunSteps(session, new GroupRoles(session), new RunSnapshots(session, CodeStepsHeld.NONE,
                new SearchWays([DeployedModels.WEB])), store.transactionManager(database), Deployments.DEEP_ENOUGH,
                new GroupCurrencies(session, new org.springframework.transaction.support.TransactionTemplate(store.transactionManager(database)), new GroupRoles(session), DeployedModels.SEARCHING))
    }

    private String attempt(String question = QUESTION, boolean tooLong = false, String repeats = null, String production = TRY, String runStep = RUN_STEP, String planned = STEP) {
        // DB-SPECIFIC: PostgreSQL ::uuid casts, RETURNING.
        store.session.sql("""
                insert into run_step_send_attempts (run_step_id, run_id, run_step_kind, workflow_step_id,
                    purpose, model, mode, production_id, payload, search_question, too_long, repeats_attempt_id, created_by, created_by_kind)
                values (?::uuid, ?::uuid, 'search', ?::uuid, 'produce', 'general', 'ordinary', ?::uuid,
                    ?, ?, ?, ?::uuid, ?::uuid, 'system') returning cast(run_step_send_attempt_id as text)
                """).params(runStep, RUN, planned, production, repeats == null ? '{}' : null,
                repeats == null ? question : null, tooLong, repeats, WORKFLOW_RUNNER).query(String).single()
    }

    private String call(String attempt, String outcome = null, String production = TRY, String runStep = RUN_STEP) {
        // DB-SPECIFIC: PostgreSQL ::model_call_outcome/::text/::uuid casts, now(), RETURNING.
        store.session.sql("""
                insert into model_calls (run_id, root_run_id, purpose, run_step_send_attempt_id,
                    run_step_id, production_id, model, mode, envelope_version, sent_count, outcome,
                    ended_at, answer, error_detail, came_back_count, created_by)
                values (?::uuid, ?::uuid, 'produce', ?::uuid, ?::uuid, ?::uuid, 'general', 'ordinary', 3, 5,
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

    private void readPart(String id = READ, int position = 1, String path = 'ticket', String sourceStep = null) {
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into search_question_parts (search_question_part_id, entry_version_id, workflow_step_id,
                    position, source_step_id, source_path, created_by)
                values (?::uuid, ?::uuid, ?::uuid, ?, ?::uuid, ?, ?::uuid)
                """).params(id, EDITION, STEP, position, sourceStep, path, SEEDER).update()
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
                    run_step_kind, workflow_step_id, search_question_part_id, binding_reads_a_constant,
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
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into workflow_steps (workflow_step_id, entry_version_id, position, name, kind,
                    search_way, producer, producer_model, producer_mode, tries, search_title_standing,
                    search_body_standing, search_references_standing, search_other_standing, created_by)
                values (?::uuid, ?::uuid, 0, 'looked_up', 'search', 'web', 'model', 'general', 'ordinary', 1,
                    'always', 'always', 'always', 'always', ?::uuid)
                """).params(planned, EDITION, SEEDER).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into search_question_parts (entry_version_id, workflow_step_id, position, literal_text, created_by)
                values (?::uuid, ?::uuid, 0, 'Demo Sample earlier question', ?::uuid)
                """).params(EDITION, planned, SEEDER).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into run_steps (run_step_id, run_id, entry_version_id, workflow_step_id, step_kind,
                    producer, reviewed_by_model, tries, created_by)
                values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'search', 'model', false, 1, ?::uuid)
                """).params(held, RUN, EDITION, planned, WORKFLOW_RUNNER).update()
        // DB-SPECIFIC: PostgreSQL ::step_producer/::uuid casts.
        store.session.sql("""
                insert into productions (production_id, run_step_id, run_id, root_run_id, run_step_kind,
                    step_producer, reviewed_by_model, tries, producer, try_number, created_by)
                values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'search', 'model', false, 1, ?::step_producer, 1, ?::uuid)
                """).params(production, held, RUN, RUN, byModel ? 'model' : 'person', WORKFLOW_RUNNER).update()
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
        [planned: planned, held: held, production: production, value: value]
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
                    producer, reviewed_by_model, tries, created_by)
                values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'search', 'person', false, 1, ?::uuid)
                """).params(RUN_STEP, RUN, EDITION, STEP, WORKFLOW_RUNNER).update()
        if (tried) {
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("""
                    insert into productions (production_id, run_step_id, run_id, root_run_id, run_step_kind,
                        step_producer, reviewed_by_model, tries, producer, try_number, created_by, created_by_kind)
                    values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'search', 'person', false, 1, 'person', 1, ?::uuid, 'person')
                    """).params(TRY, RUN_STEP, RUN, RUN, CAT).update()
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
        readPart(READ, 1, 'tag', child.planned)
        if (tried) {
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("""
                    insert into production_inputs (production_id, run_id, root_run_id, run_step_id,
                        run_step_kind, workflow_step_id, search_question_part_id, binding_reads_a_constant,
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
        tag.terms().terms() == [new org.lilradish.lite.app.filling.FillFieldAnswer.TermAnswer('found', 'Demo Sample found')]
        tag.terms().note() == 'Demo Sample findings'
        tag.longest() == null
        tag.fields() == null
        !page.declarations().containsKey(child.childVersion)
        input.sourcePartId() == UUID.fromString(READ)
        input.value().toString() == '"found"'
        input.withheld() == null
        input.earlierShape() == null
        input.sources() == null
        input.from().kind() == 'constant'
        input.from().stepId() == null
        input.from().runId() == null
        page.step().takesFrom().first().from().stepId() == UUID.fromString(child.planned)
        page.step().takesFrom().first().from().path() == 'tag'
        if (tried) assert page.answering().searchQuestion() == 'Demo Sample: found'

        where:
        tried << [false, true]
    }

    def 'a search read keeps its planned child output declaration while each component names its real root input'() {
        given:
        answeringByPerson(tried)
        def child = childReport(routed)
        readPart(READ, 1, 'report', child.planned)
        if (tried) {
            // DB-SPECIFIC: PostgreSQL ::run_step_kind/::text/::uuid casts, RETURNING.
            def input = store.session.sql("""
                    insert into production_inputs (production_id, run_id, root_run_id, run_step_id,
                        run_step_kind, workflow_step_id, search_question_part_id, binding_reads_a_constant,
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
        planned.sourcePartId() == UUID.fromString(READ)
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
        input.sourcePartId() == planned.sourcePartId()
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
        page.wentInFrom() == (tried ? 'try' : 'not_yet_sent')
        if (tried) assert page.answering().searchQuestion() == 'Demo Sample: {"first":"Demo Sample input","later":"Demo Sample input"}'

        where:
        routed | tried || order
        false  | false || ['first', 'later']
        false  | true  || ['first', 'later']
        true   | false || ['later', 'first']
        true   | true  || ['later', 'first']
    }

    def 'a never sent read uses the sent spelling and names its real planned part'() {
        given:
        answeringByPerson(tried)
        readPart()
        // DB-SPECIFIC: PostgreSQL ::field_kind/::uuid casts.
        store.session.sql("update declaration_fields set kind = ?::field_kind, text_limit = case when ? = 'text' then 4000 else null end, text_lines = case when ? = 'text' then 'several' else null end, must_be_given = false where entry_version_id = ?::uuid and name = 'ticket'")
                .params(kind, kind, kind, EDITION).update()
        // DB-SPECIFIC: PostgreSQL ::jsonb/::uuid casts.
        store.session.sql('update runs set started_with = ?::jsonb where run_id = ?::uuid').params(written, RUN).update()
        if (tried) savedRead()

        when:
        def page = detail()

        then:
        if (tried) {
            assert page.answering().searchQuestion() == question
            assert page.answering().searchQuestionSent() == false
        } else {
            assert page.answering() == null
        }
        page.step().takesFrom()*.sourcePartId() == [UUID.fromString(READ)]
        page.step().takesFrom().first().from().kind() == 'run_input'
        page.step().takesFrom().first().from().path() == 'ticket'
        page.wentIn().first().sourcePartId() == UUID.fromString(READ)
        page.wentIn().first().from().runId() == UUID.fromString(RUN)
        page.wentIn().first().from().runNumber() == 1
        page.wentInFrom() == (tried ? 'try' : 'not_yet_sent')
        page.wentIn().first().value().toString() == value
        page.triesMade().every { it.search() == null }

        where:
        tried | kind     | written                               || question                       | value
        false | 'text'   | '{"ticket":"Demo Sample raw text"}'   || 'Demo Sample: Demo Sample raw text' | '"Demo Sample raw text"'
        true  | 'text'   | '{"ticket":"Demo Sample raw text"}'   || 'Demo Sample: Demo Sample raw text' | '"Demo Sample raw text"'
        false | 'number' | '{"ticket":12.5}'                     || 'Demo Sample: 12.5'             | '"12.5"'
        true  | 'yes_no' | '{"ticket":false}'                    || 'Demo Sample: false'            | '"false"'
        true  | 'date'   | '{"ticket":"2026-10-03"}'            || 'Demo Sample: "2026-10-03"'     | '"2026-10-03"'
        false | 'text'   | '{}'                                  || 'Demo Sample: '                 | 'null'
    }

    def 'an optional search output keeps its null row maker in live and historical reads and is empty in a sent question'() {
        given:
        answeringByPerson(tried)
        def made = earlierSearch(byModel)
        readPart(READ, 1, 'references', made.planned)
        if (tried) savedRead(READ, made.value)

        when:
        def page = detail()
        def input = page.wentIn().first()
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        def stored = store.session.sql('select value is null as empty, field_standing::text as standing from production_values where production_value_id = ?::uuid')
                .param(made.value).query().singleRow()

        then:
        stored.empty
        stored.standing == 'always'
        if (tried) {
            assert page.answering().searchQuestion() == 'Demo Sample: '
            assert page.answering().searchQuestionSent() == false
        } else {
            assert page.answering() == null
        }
        input.value().isNull()
        input.withheld() == null
        input.sourcePartId() == UUID.fromString(READ)
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
        if (tried) {
            assert hidden.answering().searchQuestionWithheld()
            assert hidden.answering().searchQuestion() == null
        } else {
            assert hidden.answering() == null
        }
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

    def 'a never sent term read puts the offered term into the question without JSON quoting'() {
        given:
        answeringByPerson()
        readPart()
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
        // DB-SPECIFIC: PostgreSQL ::jsonb/::uuid casts.
        store.session.sql('update runs set started_with = ?::jsonb where run_id = ?::uuid')
                .params('{"ticket":"Demo Sample term"}', RUN).update()
        savedRead()

        when:
        def page = detail()

        then:
        page.answering().searchQuestion() == 'Demo Sample: Demo Sample term'
        page.answering().searchQuestionSent() == false
        page.wentIn().first().value().asString() == 'Demo Sample term'
    }

    def 'a saved optional empty read keeps its original maker after the earlier search gives a value again'() {
        given:
        answeringByPerson()
        def original = earlierSearch(false)
        readPart(READ, 1, 'references', original.planned)
        savedRead(READ, original.value)
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, now(), RETURNING.
        def again = store.session.sql("""
                insert into productions (run_step_id, run_id, root_run_id, run_step_kind,
                    step_producer, reviewed_by_model, tries, producer, try_number, created_by, created_by_kind,
                    ended_at, ended_by, ended_by_kind, explanation)
                values (?::uuid, ?::uuid, ?::uuid, 'search', 'model', false, 1, 'person', 2, ?::uuid, 'person',
                    now(), ?::uuid, 'person', 'Demo Sample replacement') returning production_id::text
                """).params(original.held, RUN, RUN, CAT, CAT).query(String).single()
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
        page.answering().searchQuestion() == 'Demo Sample: Demo Sample replacement'
        page.wentIn().first().value().isNull()
        page.wentIn().first().from().stepId() == UUID.fromString(original.planned)
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        store.session.sql('select source_production_value_id::text from production_inputs where production_id = ?::uuid and search_question_part_id = ?::uuid')
                .params(TRY, READ).query(String).single() == original.value
    }

    def 'two reads of the same path keep separate stored part identities'() {
        given:
        def second = UUID.randomUUID().toString()
        readPart()
        readPart(second, 2)
        savedRead()
        savedRead(second)

        when:
        def page = detail()

        then:
        page.step().takesFrom()*.sourcePartId() == [UUID.fromString(READ), UUID.fromString(second)]
        page.wentIn()*.sourcePartId() == page.step().takesFrom()*.sourcePartId()
        page.wentIn()*.input() == ['ticket', 'ticket']
        page.wentIn()*.value()*.asString() == ['Demo Sample input', 'Demo Sample input']
        page.wentInFrom() == 'try'
    }

    def 'a search try rejects a missing read or an input attached to a literal'() {
        given:
        readPart()
        if (literalInput) {
            // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
            def literal = store.session.sql('select search_question_part_id::text from search_question_parts where workflow_step_id = ?::uuid and literal_text is not null')
                    .param(STEP).query(String).single()
            savedRead(literal)
        }

        when:
        detail()

        then:
        def fault = thrown(IllegalStateException)
        fault.message == expected

        where:
        literalInput || expected
        false        || 'A search try lacks a planned read part input'
        true         || 'A search try has an input outside its planned read parts'
    }

    def 'a search with an old missing component maker withholds its stored read in the single step response'() {
        given:
        def child = childReport(false)
        readPart(READ, 1, 'report', child.planned)
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
                    run_step_kind, workflow_step_id, search_question_part_id, binding_reads_a_constant,
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
        read.path('sourcePartId').asText() == READ
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
        def visible = detail().triesMade().first().search()
        SpyStatic(GroupReach, MockMakers.mockito)
        def permissions = EnumSet.copyOf(GroupRole.OPERATOR.permissions())
        permissions.remove(GroupPermission.READ_INFERENCE_CONTENT)
        GroupReach.reachedBy(_ as Set) >> permissions
        def withheld = detail(CAT_USER).triesMade().first().search()

        then:
        visible.question() == QUESTION
        visible.uses() == uses.toString()
        visible.usesCounted() == (said ? 'said' : 'at_most')
        visible.fee().currency() == 'USD'
        visible.fee().perUse() == '0.0100'
        visible.fee().amount() == amount
        visible.searchesUnsaid() == (outcome == 'came_back' ? true : null)
        withheld.withheld()
        withheld.question() == null
        withheld.searches() == null
        withheld.searchesUnsaid() == null
        withheld.fee() == visible.fee()
        withheld.uses() == visible.uses()

        where:
        outcome             | uses | said  | amount
        'came_back'         | 2L   | true  | '0.0200'
        'errored'           | 5L   | false | '0.0500'
        'nothing_came_back' | 5L   | false | '0.0500'
    }

    def 'an outgoing call has its question but no count or fee'() {
        given:
        call(attempt())
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql('update productions set try_number = 1 where production_id = ?::uuid').param(TRY).update()

        when:
        def search = detail().triesMade().first().search()

        then:
        search.question() == QUESTION
        search.uses() == null
        search.usesCounted() == null
        search.fee() == null
        search.noFee() == null
        search.searchesUnsaid() == null
    }

    def 'answering uses the latest truly sent question including a repeat, skipping too long attempts'() {
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
        page.answering().searchQuestion() == QUESTION
        page.answering().searchQuestionSent()
        page.step().runs().searchWay() == 'web'
        page.step().runs().tool() == 'web_search'
        page.declarations()['search:web'].gives()*.name() == ['title', 'body', 'references', 'other']

        when:
        SpyStatic(GroupReach, MockMakers.mockito)
        def permissions = EnumSet.copyOf(GroupRole.OPERATOR.permissions())
        permissions.remove(GroupPermission.READ_INFERENCE_CONTENT)
        GroupReach.reachedBy(_ as Set) >> permissions
        def hidden = detail(CAT_USER).answering()

        then:
        hidden.searchQuestionWithheld()
        hidden.searchQuestionSent() == null
    }

    def 'a never sent literal question is assembled by the shared sent spelling'() {
        given:
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql('delete from productions where production_id = ?::uuid').param(TRY).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql('delete from run_steps where run_step_id = ?::uuid').param(RUN_STEP).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("update workflow_steps set producer = 'person', producer_model = null, producer_mode = null where workflow_step_id = ?::uuid").param(STEP).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into run_steps (run_step_id, run_id, entry_version_id, workflow_step_id, step_kind,
                    producer, reviewed_by_model, tries, created_by)
                values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'search', 'person', false, 1, ?::uuid)
                """).params(RUN_STEP, RUN, EDITION, STEP, WORKFLOW_RUNNER).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into productions (production_id, run_step_id, run_id, root_run_id, run_step_kind,
                    step_producer, reviewed_by_model, tries, producer, try_number, created_by, created_by_kind)
                values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'search', 'person', false, 1, 'person', 1, ?::uuid, 'person')
                """).params(TRY, RUN_STEP, RUN, RUN, CAT).update()


        when:
        def answer = detail().answering()

        then:
        answer.searchQuestion() == 'Demo Sample: '
        answer.searchQuestionSent() == false
        answer.gives()*.name() == ['title', 'body', 'references', 'other']
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
        def search = detail().triesMade().first().search()

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
        def search = detail().triesMade().first().search()

        then:
        search.fee()?.currency() == currency
        search.fee()?.amount() == amount
        search.noFee()?.reason() == reason
        search.noFee()?.currency() == absentCurrency

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
        SpyStatic(GroupReach, MockMakers.mockito)
        def permissions = EnumSet.copyOf(GroupRole.OPERATOR.permissions())
        permissions.remove(GroupPermission.READ_INFERENCE_CONTENT)
        GroupReach.reachedBy(_ as Set) >> permissions

        when:
        def hidden = detail(CAT_USER, by)

        then:
        hidden.triesMade().first().search().withheld()
        hidden.triesMade().first().search().searches() == null
        hidden.triesMade().first().search().fee().amount() == '0.0200'
        statements.count { it.contains('from model_call_searches') } == 0
        statements.count { it.contains('holder.search_question') } == 0
        statements.count { it.contains('from group_currencies') } == 1

        when:
        statements.clear()
        def list = by.steps(new GroupId(UUID.fromString(GROUP)), new RunId(UUID.fromString(RUN)), CAT_USER)

        then:
        list.steps().size() == 1
        list.steps().first().where().searchQuestion() == null
        statements.count { it.contains('from model_call_searches') } == 0
        statements.count { it.contains('holder.search_question') } == 0
        statements.count { it.contains('from group_currencies') } == 0
    }

    def 'a held attempt question is detail-only, follows its holder, and is removed when released'() {
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
        page.step().where().searchQuestion() == 'Demo Sample held original'
        page.step().where().searchQuestionWithheld() == null
        page.triesMade().isEmpty()
        list.steps().first().where().searchQuestion() == null

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
        hidden.searchQuestion() == null
        hidden.searchQuestionWithheld()
        released.searchQuestion() == null
        released.searchQuestionWithheld() == null

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
        failure.errorCode() == org.lilradish.lite.domain.failure.RefusalCode.GROUP_NOT_IN_VIEW
        statements.count { it.contains('model_call_searches') || it.contains('holder.search_question') } == 0
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

    def 'search detail batches records prices and current currency across accepted tries'() {
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
                    producer, reviewed_by_model, tries, created_by)
                values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'search', 'model', false, ?, ?::uuid)
                """).params(RUN_STEP, RUN, EDITION, STEP, howMany, WORKFLOW_RUNNER).update()
        (1..howMany).each { number ->
            def production = number == 1 ? TRY : UUID.randomUUID().toString()
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("""
                    insert into productions (production_id, run_step_id, run_id, root_run_id,
                        run_step_kind, step_producer, reviewed_by_model, tries, producer, created_by)
                    values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'search', 'model', false, ?, 'model', ?::uuid)
                    """).params(production, RUN_STEP, RUN, RUN, howMany, WORKFLOW_RUNNER).update()
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
        tries.every { it.search().searches().size() == 1 }
        statements.count { it.contains('from model_call_searches') } == 1
        statements.count { it.contains('from model_call_tool_prices') } == 1
        statements.count { it.contains('from group_currencies') } == 1

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
        def search = wire.get('triesMade').get(0).get('search')

        then:
        search == json.readTree(expected)
        wire.get('step').get('runs') == json.readTree('{"kind":"search","searchWay":"web","tool":"web_search"}')
        wire.get('step').get('where').get('searchQuestionSent') == null
        wire.get('declarations').get('search:web') != null

        where:
        words || expected
        true  || '{"question":"Demo Sample question, as sent","searches":[{"found":""}],"uses":"2","usesCounted":"said","fee":{"currency":"USD","perUse":"0.0100","amount":"0.0200"}}'
        false || '{"withheld":true,"uses":"2","usesCounted":"said","fee":{"currency":"USD","perUse":"0.0100","amount":"0.0200"}}'
    }

}
