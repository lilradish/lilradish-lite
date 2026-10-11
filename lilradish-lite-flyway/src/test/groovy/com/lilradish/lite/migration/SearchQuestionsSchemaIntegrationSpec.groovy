package com.lilradish.lite.migration

import static com.lilradish.lite.migration.fixture.RunCases.SEARCH_PRODUCTION
import static com.lilradish.lite.migration.fixture.RunCases.SEARCH_RUN_STEP
import static com.lilradish.lite.migration.fixture.SchemaKeys.*
import static com.lilradish.lite.migration.fixture.StepAndDeclarationCases.SEARCH_STEP

import com.lilradish.lite.migration.fixture.EntryCases
import com.lilradish.lite.migration.fixture.EstateRoleCases
import com.lilradish.lite.migration.fixture.GroupCases
import com.lilradish.lite.migration.fixture.LibraryCases
import com.lilradish.lite.migration.fixture.PeopleCases
import com.lilradish.lite.migration.fixture.PgTrgm
import com.lilradish.lite.migration.fixture.PoolCases
import com.lilradish.lite.migration.fixture.SubjectCases
import com.lilradish.lite.testsupport.TestPostgres
import java.sql.Connection
import org.flywaydb.core.Flyway
import org.postgresql.util.PSQLException
import spock.lang.AutoCleanup
import spock.lang.Specification

class SearchQuestionsSchemaIntegrationSpec extends Specification {

    private static final String INPUT = "0000001c-0000-4000-8000-000000000071"
    private static final String FAILURE = "00000015-0000-4000-8000-000000000071"

    @AutoCleanup
    TestPostgres server = TestPostgres.isolated()

    @AutoCleanup
    Connection session

    def setup() {
        PgTrgm.preparedIn(server.postgresDatabase)
        session = server.postgresDatabase.connection
        def migrated = migration().migrate()
        assert migrated.success
        assert migrated.migrationsExecuted > 0
        // DB-SPECIFIC: PostgreSQL ALTER TYPE ADD VALUE extends a native enum.
        execute("alter type app.code_step add value '${CODE_STEP}'")
        [*SubjectCases.FIXTURE, *PoolCases.FIXTURE, *EstateRoleCases.FIXTURE, *GroupCases.FIXTURE,
         *PeopleCases.FIXTURE, *EntryCases.FIXTURE, *LibraryCases.FIXTURE].each { execute(it) }
    }

    def "the current baseline holds a search marker, pins, ordinary input and separate failure ending"() {
        given:
        searching()
        reaching()

        when:
        execute("update app.question_versions set search_way = 'web' where entry_version_id = '${VERSION}'")
        execute("insert into app.bindings (binding_id, entry_version_id, workflow_step_id, target_path, source_path, created_by)" +
                " values ('${BINDING}', '${WORKFLOW_VERSION}', '${SEARCH_STEP}', 'complaint', 'complaint', '${STEWARD}')")
        execute(input(BINDING))
        // DB-SPECIFIC: PostgreSQL native run_step_kind and run_step_failure_reason enum inputs.
        execute("insert into app.run_step_failures (run_step_failure_id, run_step_id, run_id, run_step_kind, calls_a_model," +
                " reason, detail, created_by) values ('${FAILURE}', '${SEARCH_RUN_STEP}', '${RUN}', 'search', true," +
                " 'search_question_no_longer_matches', 'Find evidence · Version 1', '${WORKFLOW_RUNNER}')")
        execute("update app.run_step_failures set resolved_at = created_at, resolved_by = '${STEWARD}'" +
                " where run_step_failure_id = '${FAILURE}'")

        then:
        migration().info().pending().length == 0
        number("select count(*) from app.flyway_schema_history where version is not null and success") > 0
        number("select count(*) from information_schema.tables where table_schema = 'app' and table_name = 'search_question_parts'") == 0
        number("select count(*) from information_schema.columns where table_schema = 'app' and" +
                " ((table_name = 'production_inputs' and column_name = 'search_question_part_id')" +
                " or (table_name = 'run_step_send_attempts' and column_name = 'search_question'))") == 0
        word("select is_nullable from information_schema.columns where table_schema = 'app'" +
                " and table_name = 'production_inputs' and column_name = 'binding_id'") == "NO"
        word("select is_nullable from information_schema.columns where table_schema = 'app'" +
                " and table_name = 'run_step_failures' and column_name = 'resolved_by_kind'") == "NO"
        word("select search_way from app.question_versions where entry_version_id = '${VERSION}'") == "web"
        // DB-SPECIFIC: The following four queries use PostgreSQL ::text casts.
        word("select pinned_version_id::text from app.workflow_steps where workflow_step_id = '${SEARCH_STEP}'") == VERSION
        word("select pinned_version_id::text from app.run_steps where run_step_id = '${SEARCH_RUN_STEP}'") == VERSION
        word("select pinned_version_id::text from app.productions where production_id = '${SEARCH_PRODUCTION}'") == VERSION
        word("select binding_id::text from app.production_inputs where production_input_id = '${INPUT}'") == BINDING
        number("select count(*) from app.run_step_failures where run_step_failure_id = '${FAILURE}'" +
                " and resolved_at = created_at and resolved_by = '${STEWARD}' and production_id is null and purpose is null") == 1
        number("select count(*) from app.run_step_send_attempts") == 0
        number("select count(*) from app.model_calls") == 0
        number("select count(*) from app.productions") == 1
    }

    def "a search input without its ordinary binding is refused without changing recorded rows"() {
        given:
        searching()
        reaching()
        def before = contents("production_inputs")
        def productionsBefore = contents("productions")
        assert number("select count(*) from app.production_inputs") == 0
        assert number("select count(*) from app.productions") == 1

        when:
        execute(input(null))

        then:
        def refused = thrown(PSQLException)
        refused.SQLState == "23502"
        refused.serverErrorMessage.table == "production_inputs"
        refused.serverErrorMessage.column == "binding_id"
        contents("production_inputs") == before
        contents("productions") == productionsBefore
        number("select count(*) from app.production_inputs") == 0
        number("select count(*) from app.model_calls") == 0
        number("select count(*) from app.run_step_send_attempts") == 0
    }

    private Flyway migration() {
        Flyway.configure().dataSource(server.postgresDatabase).schemas("app")
                .locations("classpath:db/migration/common").failOnMissingLocations(true).validateMigrationNaming(true).load()
    }

    private void searching() {
        // DB-SPECIFIC: PostgreSQL native step_kind, entry_kind and step_producer enum inputs.
        execute("insert into app.workflow_steps (workflow_step_id, entry_version_id, position, name, kind, search_way," +
                " pinned_version_id, pinned_kind, producer, producer_model, producer_mode, tries, tells_what_happened, created_by)" +
                " values ('${SEARCH_STEP}', '${WORKFLOW_VERSION}', 0, 'find_evidence', 'search', 'web'," +
                " '${VERSION}', 'question', 'model', 'general', 'ordinary', 1, true, '${STEWARD}')")
    }

    private void reaching() {
        // DB-SPECIFIC: This runs fixture supplies PostgreSQL jsonb input.
        execute("insert into app.runs (run_id, group_id, number, name, entry_id, entry_version_id, root_run_id, depth," +
                " started_with, created_by) values ('${RUN}', '${GROUP}', 1, 'Fictional shipment evidence', '${WORKFLOW_ENTRY}', '${WORKFLOW_VERSION}'," +
                " '${RUN}', 0, '{\"complaint\":\"The fictional shipment arrived late.\"}', '${STEWARD}')")
        // DB-SPECIFIC: PostgreSQL native step_kind, entry_kind and step_producer enum inputs.
        execute("insert into app.run_steps (run_step_id, run_id, entry_version_id, workflow_step_id, step_kind," +
                " pinned_version_id, pinned_kind, producer, reviewed_by_model, tries, created_by) values" +
                " ('${SEARCH_RUN_STEP}', '${RUN}', '${WORKFLOW_VERSION}', '${SEARCH_STEP}', 'search'," +
                " '${VERSION}', 'question', 'model', false, 1, '${WORKFLOW_RUNNER}')")
        // DB-SPECIFIC: PostgreSQL native run_step_kind and step_producer enum inputs.
        execute("insert into app.productions (production_id, run_step_id, run_id, root_run_id, run_step_kind," +
                " step_producer, reviewed_by_model, tries, pinned_version_id, try_number, producer, created_by) values" +
                " ('${SEARCH_PRODUCTION}', '${SEARCH_RUN_STEP}', '${RUN}', '${RUN}', 'search', 'model', false, 1," +
                " '${VERSION}', 1, 'model', '${WORKFLOW_RUNNER}')")
    }

    private static String input(String binding) {
        // DB-SPECIFIC: This production_inputs fixture supplies PostgreSQL native run_step_kind enum input.
        def supplied = binding == null ? "null" : "'${binding}'"
        "insert into app.production_inputs (production_input_id, production_id, run_id, root_run_id, run_step_id," +
                " run_step_kind, workflow_step_id, binding_id, binding_reads_a_constant) values ('${INPUT}'," +
                " '${SEARCH_PRODUCTION}', '${RUN}', '${RUN}', '${SEARCH_RUN_STEP}', 'search', '${SEARCH_STEP}', ${supplied}, false)"
    }

    private String contents(String table) {
        // DB-SPECIFIC: PostgreSQL row-to-text casts, string_agg() and md5().
        word("select coalesce(md5(string_agg(t::text, '|' order by t::text)), 'empty') from app.${table} t")
    }

    private long number(String sql) {
        Long.parseLong(word(sql))
    }

    private String word(String sql) {
        session.createStatement().withCloseable { statement ->
            statement.executeQuery(sql).withCloseable { rows ->
                assert rows.next()
                rows.getString(1)
            }
        }
    }

    private void execute(String sql) {
        session.createStatement().withCloseable { it.execute(sql) }
    }
}
