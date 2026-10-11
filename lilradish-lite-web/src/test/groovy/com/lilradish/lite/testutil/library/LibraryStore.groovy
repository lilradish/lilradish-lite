package com.lilradish.lite.testutil.library

import com.lilradish.lite.app.change.ChangeTransactions
import com.lilradish.lite.app.codestep.CodeSteps
import com.lilradish.lite.app.codestep.ReadTextFile
import com.lilradish.lite.app.files.FileDeployment
import com.lilradish.lite.app.run.EngineExecutors
import com.lilradish.lite.domain.identity.GroupId
import com.lilradish.lite.domain.model.ModelCatalog
import com.lilradish.lite.domain.registry.EntryId
import com.lilradish.lite.domain.registry.EntryVersionId
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.Baseline
import com.lilradish.lite.testutil.codestep.SpecCodeStep
import java.sql.Connection
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.support.TransactionOperations
import org.springframework.util.unit.DataSize


final class LibraryStore {

    static final String SEEDER = "00000000-0000-4000-8000-000000000000"


    static final String FIRST_STEWARD = "00000001-0000-4000-8000-000000000001"

    static final ModelCatalog MODELS = DeployedModels.HELD

    static final FileDeployment FILES = new FileDeployment(DataSize.ofMegabytes(100), 100, DataSize.ofGigabytes(1))


    static final CodeSteps CODE_STEPS = new CodeSteps([SpecCodeStep.SEND_REPLY, new ReadTextFile()])

    final DataSource database

    final JdbcClient session

    private LibraryStore(DataSource database) {
        this.database = database
        this.session = JdbcClient.create(database)
    }

    static void prepareBaseline(TestPostgres server) {
        server.registerQuiescence { EngineExecutors.requireTerminated() }
        Baseline.prepareWorker(server)
    }

    static LibraryStore forCase(TestPostgres server, String name, boolean changesSchema = false) {
        server.beginLibraryCase(name, changesSchema)
        new LibraryStore(Baseline.appliedTo(server, name))
    }

    /** A schema-sensitive fixture needs a separate database with its requested locale. */
    static LibraryStore madeUnder(TestPostgres server, String name, String localeClause) {
        server.createDatabase(name, localeClause)
        new LibraryStore(Baseline.appliedTo(server, name))
    }

    /** The manager a deployment runs, which turns a failure at commit into what a failed statement is turned into. */
    JdbcTransactionManager transactionManager(DataSource over = database) {
        new JdbcTransactionManager(over)
    }

    TransactionOperations transactions(DataSource over = database) {
        new ChangeTransactions().readCommitted(transactionManager(over))
    }

    void person(String subject, String user, String displayName = "Somebody") {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        session.sql("""
                insert into subjects (subject_id, kind, user_id, display_name, created_by)
                values (?::uuid, 'person', ?, ?, ?::uuid)
                """).params(subject, user, displayName, SEEDER).update()
    }

    void group(String group, String key, String name) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        session.sql("insert into groups (group_id, key, name, created_by) values (?::uuid, ?, ?, ?::uuid)")
                .params(group, key, name, FIRST_STEWARD).update()
    }

    void member(String group, String subject, String role) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid, group_role.
        session.sql("""
                insert into group_members (group_id, subject_id, role, created_by)
                values (?::uuid, ?::uuid, ?::group_role, ?::uuid)
                """).params(group, subject, role, FIRST_STEWARD).update()
    }

    void entry(String entry, String group, String kind, String name, String purpose = null) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid, entry_kind.
        session.sql("""
                insert into entries (entry_id, group_id, kind, name, purpose, created_by)
                values (?::uuid, ?::uuid, ?::entry_kind, ?, ?, ?::uuid)
                """).params(entry, group, kind, name, purpose, SEEDER).update()
    }


    void version(String version, String entry, int number, String starter) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        session.sql("""
                insert into entry_versions (entry_version_id, entry_id, entry_kind, number, created_by)
                select ?::uuid, entry.entry_id, entry.kind, ?, ?::uuid from entries entry where entry.entry_id = ?::uuid
                """).params(version, number, starter, entry).update()
    }


    void seeded(String version, String entry, int number, boolean retired = false) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid; now(); subject_kind enum type.
        session.sql("""
                insert into entry_versions (entry_version_id, entry_id, entry_kind, number, created_by,
                                            approved_at, approved_by, approved_by_kind,
                                            retired_at, retired_by, retired_by_kind)
                select ?::uuid, entry.entry_id, entry.kind, ?, ?::uuid, now(), ?::uuid, 'seeder',
                       case when ? then now() end, case when ? then ?::uuid end,
                       cast(case when ? then 'seeder' else 'person' end as subject_kind)
                  from entries entry where entry.entry_id = ?::uuid
                """).params(version, number, SEEDER, SEEDER, retired, retired, SEEDER, retired, entry).update()
    }

    void submitted(String version, String by, String kind = "person") {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid, subject_kind.
        session.sql("""
                insert into entry_version_submissions (entry_version_id, created_by, created_by_kind)
                values (?::uuid, ?::uuid, ?::subject_kind)
                """).params(version, by, kind).update()
    }

    void approved(String version, String by) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid; now().
        session.sql("update entry_versions set approved_at = now(), approved_by = ?::uuid where entry_version_id = ?::uuid")
                .params(by, version).update()
    }

    void retired(String version, String by) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid; now().
        session.sql("update entry_versions set retired_at = now(), retired_by = ?::uuid where entry_version_id = ?::uuid")
                .params(by, version).update()
    }

    void writer(String version, String subject) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        session.sql("insert into entry_version_writers (entry_version_id, created_by) values (?::uuid, ?::uuid)")
                .params(version, subject).update()
    }

    void stopped(String entry, String by) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        session.sql("insert into entry_stops (entry_id, created_by) values (?::uuid, ?::uuid)").params(entry, by).update()
    }


    void content(String version, String kind, String said = null) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        def written = [workflow: "insert into workflow_versions (entry_version_id, created_by) values (?::uuid, ?::uuid)",
                       // DB-SPECIFIC: PostgreSQL :: casts to uuid.
                       question: "insert into question_versions (entry_version_id, created_by, instruction) values (?::uuid, ?::uuid, ?)",
                       // DB-SPECIFIC: PostgreSQL :: casts to uuid.
                       reference_list: "insert into reference_list_versions (entry_version_id, created_by, note) values (?::uuid, ?::uuid, ?)"][kind]
        def statement = session.sql(written).param(version).param(SEEDER)
        (kind == "workflow" ? statement : statement.param(said)).update()
    }


    void wholeQuestion(String version) {
        content(version, "question", "Say what the complaint is about.")
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        session.sql("""
                insert into declaration_fields (entry_version_id, entry_kind, side, position, name, kind, text_limit,
                                                must_be_given, standing, created_by)
                values (?::uuid, 'question', 'gives', 1, 'summary', 'text', 1000, true, 'always', ?::uuid)
                """).params(version, SEEDER).update()
    }


    void step(String step, String holder, int position, String pinned, String pinnedKind) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid, entry_kind.
        session.sql("""
                insert into workflow_steps (workflow_step_id, entry_version_id, position, name, kind,
                                            pinned_version_id, pinned_kind, created_by)
                values (?::uuid, ?::uuid, ?, ?, 'entry', ?::uuid, ?::entry_kind, ?::uuid)
                """).params(step, holder, position, "step_" + position, pinned, pinnedKind, SEEDER).update()
    }


    void routed(String step, String routeCase, String holder, int position, String target) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        session.sql("""
                insert into workflow_steps (workflow_step_id, entry_version_id, position, name, kind, created_by)
                values (?::uuid, ?::uuid, ?, ?, 'route', ?::uuid)
                """).params(step, holder, position, "route_" + position, SEEDER).update()
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        session.sql("""
                insert into route_cases (route_case_id, entry_version_id, workflow_step_id, term, target_version_id, created_by)
                values (?::uuid, ?::uuid, ?::uuid, null, ?::uuid, ?::uuid)
                """).params(routeCase, holder, step, target, SEEDER).update()
    }


    void termField(String field, String holder, String holderKind, String list, String side = "gives") {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid, entry_kind, declaration_side.
        session.sql("""
                insert into declaration_fields (declaration_field_id, entry_version_id, entry_kind, side, position,
                                                name, kind, term_list_version_id, must_be_given, created_by)
                values (?::uuid, ?::uuid, ?::entry_kind, ?::declaration_side, 1, 'category', 'term', ?::uuid, true,
                        ?::uuid)
                """).params(field, holder, holderKind, side, list, SEEDER).update()
    }


    Connection holding(String... statements) {
        def connection = database.connection
        connection.autoCommit = false
        statements.each { statement -> connection.createStatement().withCloseable { it.execute(statement) } }
        connection
    }


    Connection takingRoles(String group, String subject, String... given) {
        // DB-SPECIFIC: PostgreSQL SELECT FOR NO KEY UPDATE.
        holding(["select 1 from groups where group_id = '${group}' for no key update" as String,
                 // DB-SPECIFIC: PostgreSQL now().
                 """update group_members set removed_at = now(), removed_by = '${FIRST_STEWARD}', removal = 'role_taken',
                           removed_position = (select coalesce(max(closed.removed_position), 0) + 1
                                                 from group_members closed where closed.group_id = '${group}')
                     where group_id = '${group}' and subject_id = '${subject}' and removed_at is null""" as String] +
                given.collect { role ->
                    """insert into group_members (group_id, subject_id, role, created_by)
                       values ('${group}', '${subject}', '${role}', '${FIRST_STEWARD}')""" as String
                } as String[])
    }

    void untilWaiting(int sessions) {
        def deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        // DB-SPECIFIC: PostgreSQL pg_stat_activity; current_database().
        while (count("""
                select count(*) from pg_stat_activity
                 where datname = current_database() and wait_event_type = 'Lock'
                """) < sessions) {
            assert System.nanoTime() < deadline: "fewer than ${sessions} sessions ever waited on a lock"
            Thread.sleep(10)
        }
    }

    /** Only a change naming Read Committed then reads what it waited on. */
    void repeatableReadByDefault() {
        // DB-SPECIFIC: PostgreSQL current_database().
        def name = session.sql("select current_database()").query(String).single()
        // DB-SPECIFIC: PostgreSQL ALTER DATABASE settings.
        session.sql("alter database \"${name}\" set default_transaction_isolation = 'repeatable read'").update()
    }

    /** Read tables from the live catalog so new baseline tables enter state checks. */
    String contents(String... except) {
        // DB-SPECIFIC: PostgreSQL pg_tables; current_schema().
        def tables = session.sql("""
                select tablename from pg_tables
                 where schemaname = current_schema() and tablename <> 'flyway_schema_history'
                 order by tablename
                """).query(String).list()
        assert tables.containsAll(["entries", "entry_versions", "entry_version_writers", "group_members"])
        (tables - except.toList()).collect { digestOf(it) }.join(" ")
    }

    String digestOf(String table) {
        // DB-SPECIFIC: PostgreSQL :: casts to text; md5(), string_agg().
        session.sql("select coalesce(md5(string_agg(t::text, '|' order by t::text)), 'empty') from ${table} t")
                .query(String).single()
    }

    long count(String query, Object... parameters) {
        session.sql(query).params(parameters.toList()).query(Long).single()
    }

    List<String> texts(String query, Object... parameters) {
        session.sql(query).params(parameters.toList()).query(String).list()
    }

    static GroupId groupId(String spelled) {
        new GroupId(UUID.fromString(spelled))
    }

    static EntryId entryId(String spelled) {
        new EntryId(UUID.fromString(spelled))
    }

    static EntryVersionId versionId(String spelled) {
        new EntryVersionId(UUID.fromString(spelled))
    }


    static Future<Object> attempting(ExecutorService racing, Closure change) {
        racing.submit({
            try {
                change()
            } catch (Throwable thrown) {
                thrown
            }
        } as Callable<Object>)
    }

}
