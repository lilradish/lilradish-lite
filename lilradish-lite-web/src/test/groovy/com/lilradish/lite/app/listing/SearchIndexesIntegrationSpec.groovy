package com.lilradish.lite.app.listing

import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.Baseline
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.postgresql.util.PSQLException
import org.springframework.jdbc.core.simple.JdbcClient
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper

class SearchIndexesIntegrationSpec extends Specification {

    private static final Map<String, List<String>> EXPECTED_TARGETS = [
            people_display_name_folded_trigrams: ['people', 'display_name_folded'],
            subjects_display_name_folded_trigrams: ['subjects', 'display_name_folded'],
            subjects_user_id_folded_trigrams: ['subjects', 'user_id_folded'],
            groups_name_folded_trigrams: ['groups', 'name_folded'],
            entries_name_folded_trigrams: ['entries', 'name_folded']]

    @Shared
    @AutoCleanup
    TestPostgres server = TestPostgres.isolated()

    def "the full migration uses the extension-owned GIN opclass in #schema under a restricted application owner"() {
        given:
        def targetVersion = migrationStages().target
        def target = prepared(schema)
        def extensionBefore = extensionIdentity(target.administrator)
        def factsBefore = factsOf(target)

        when:
        def migrated = migrate(target.migration, targetVersion.toString())
        def indexes = indexesIn(target.administrator)

        then:
        migrated.migrationsExecuted == targetVersion
        indexes*.index_name.toSet() == [
                'people_display_name_folded_trigrams', 'subjects_display_name_folded_trigrams',
                'subjects_user_id_folded_trigrams', 'groups_name_folded_trigrams',
                'entries_name_folded_trigrams'] as Set
        indexes.size() == 5
        indexes.collectEntries { [it.index_name, [it.table_name, it.column_name]] } == EXPECTED_TARGETS
        indexes.every { it.table_schema == 'app' && it.indnkeyatts == 1 && it.indnatts == 1 }
        indexes.every { it.indisvalid && it.indisready && it.amname == 'gin' &&
                it.opcname == 'gin_trgm_ops' && it.extension_name == 'pg_trgm' && it.type_name == 'text' }
        indexes*.opclass_schema.toSet() == [schema == 'app' ? 'app' : 'Demo Sample extension'] as Set
        extensionIdentity(target.administrator) == extensionBefore
        restricted(target.migration)
        save('full-' + (schema == 'app' ? 'app' : 'quoted'), factsBefore, factsOf(target), null)

        where:
        schema << ['app', '"Demo Sample extension"']
    }

    def "the actual upgrade builds indexes over #size existing directory and subject rows"() {
        given:
        def previousVersion = migrationStages().previous
        def targetVersion = migrationStages().target
        def target = prepared('app')
        assert migrate(target.migration, previousVersion.toString()).migrationsExecuted == previousVersion
        def session = JdbcClient.create(target.migration)
        // DB-SPECIFIC: PostgreSQL generate_series, ::uuid/::text casts and subject_kind labels populate existing rows;
        // text || non-string converts the generated integer to text.
        assert session.sql("""
                insert into app.people (user_id, display_name)
                select 'S4-' || lpad(number::text, 6, '0'), 'Mira Mockvale ' || number
                  from generate_series(1, ?) number
                """).param(size).update() == size
        assert session.sql("""
                insert into app.subjects (subject_id, kind, user_id, display_name, created_by)
                select ('00000009-0000-4000-9000-' || lpad(number::text, 12, '0'))::uuid,
                       'person', 'S4-' || lpad(number::text, 6, '0'), 'Mira Mockvale ' || number,
                       (select subject_id from app.subjects where kind = 'seeder')
                  from generate_series(1, ?) number
                """).param(size).update() == size
        // The ucs_basic collation gives user identifiers a stable code-point order.
        def peopleBefore = session.sql('select * from app.people order by user_id collate ucs_basic')
                .query().listOfRows()
        def subjectsBefore = session.sql('select * from app.subjects order by subject_id').query().listOfRows()
        def before = factsOf(target)
        long started = System.nanoTime()

        when:
        def migrated = migrate(target.migration, targetVersion.toString())
        long elapsed = System.nanoTime() - started
        def after = factsOf(target)
        // The ucs_basic collation gives user identifiers a stable code-point order.
        def peopleAfter = session.sql('select * from app.people order by user_id collate ucs_basic')
                .query().listOfRows()
        def subjectsAfter = session.sql('select * from app.subjects order by subject_id').query().listOfRows()

        then:
        migrated.migrationsExecuted == targetVersion - previousVersion
        !peopleBefore.empty
        !subjectsBefore.empty
        peopleAfter.size() == peopleBefore.size()
        subjectsAfter.size() == subjectsBefore.size()
        peopleAfter == peopleBefore
        subjectsAfter == subjectsBefore
        indexesIn(target.administrator).size() == 5
        after.indexes.collectEntries { [it.index_name, [it.table_name, it.column_name]] } == EXPECTED_TARGETS
        after.indexes.every { it.table_schema == 'app' && it.indnkeyatts == 1 && it.indnatts == 1 }
        indexesIn(target.administrator).every { it.indisvalid && it.indisready && it.amname == 'gin' &&
                it.opcname == 'gin_trgm_ops' && it.extension_name == 'pg_trgm' }
        session.sql("select count(*) from app.people where user_id like 'S4-%'").query(Long).single() == size
        session.sql("select count(*) from app.subjects where user_id like 'S4-%'").query(Long).single() == size
        before.extension == after.extension
        restricted(target.migration)
        save('populated-upgrade-' + size, before, after, null,
                [existingPeople: size, existingSubjects: size, migrationNanos: elapsed])

        where:
        size << [300, 6000]
    }

    def "an upgrade checks pg_trgm itself even after the original baseline succeeded"() {
        given:
        def target = prepared('app')
        assert migrate(target.migration, migrationStages().previous.toString()).migrationsExecuted == migrationStages().previous
        // DB-SPECIFIC: PostgreSQL DROP EXTENSION removes the extension without dropping the application schema.
        execute(target.administrator, 'drop extension pg_trgm')
        assert extensionIdentity(target.administrator) == []
        def factsBefore = factsOf(target)

        when:
        migrate(target.migration, migrationStages().target.toString())

        then:
        def failure = thrown(FlywayException)
        sqlCauseOf(failure).SQLState == 'P0001'
        failure.message.contains('pg_trgm must be installed by the database administrator before migrations are run')
        indexesIn(target.administrator).empty
        extensionIdentity(target.administrator) == []
        JdbcClient.create(target.administrator).sql('select count(*) from app.subjects').query(Long).single() == 3L
        save('missing-extension', factsBefore, factsOf(target), sqlCauseOf(failure))
    }

    def "an upgrade checks the extension schema's current USAGE privilege without granting it"() {
        given:
        def target = prepared('"Demo Sample extension"')
        assert migrate(target.migration, migrationStages().previous.toString()).migrationsExecuted == migrationStages().previous
        execute(target.administrator, 'revoke usage on schema "Demo Sample extension" from ' + target.role)
        def before = extensionIdentity(target.administrator)
        def factsBefore = factsOf(target)

        when:
        migrate(target.migration, migrationStages().target.toString())

        then:
        def failure = thrown(FlywayException)
        sqlCauseOf(failure).SQLState == 'P0001'
        failure.message.contains('The migration role requires USAGE on the schema containing pg_trgm')
        indexesIn(target.administrator).empty
        extensionIdentity(target.administrator) == before
        // DB-SPECIFIC: PostgreSQL has_schema_privilege reads the current schema USAGE privilege.
        !JdbcClient.create(target.migration).sql("select has_schema_privilege('Demo Sample extension', 'USAGE')")
                .query(Boolean).single()
        save('missing-usage', factsBefore, factsOf(target), sqlCauseOf(failure))
    }

    def "an upgrade cannot quietly skip indexes when its role no longer owns a target table"() {
        given:
        def target = prepared('app')
        assert migrate(target.migration, migrationStages().previous.toString()).migrationsExecuted == migrationStages().previous
        // DB-SPECIFIC: PostgreSQL requires table ownership for CREATE INDEX, beyond schema USAGE.
        execute(target.administrator, 'alter table app.people owner to postgres')
        def before = extensionIdentity(target.administrator)
        def factsBefore = factsOf(target)

        when:
        migrate(target.migration, migrationStages().target.toString())

        then:
        def failure = thrown(FlywayException)
        sqlCauseOf(failure).SQLState == '42501'
        failure.message.contains('must be owner of table people')
        indexesIn(target.administrator).empty
        extensionIdentity(target.administrator) == before
        restricted(target.migration)
        save('changed-table-owner', factsBefore, factsOf(target), sqlCauseOf(failure))
    }

    private Map prepared(String schema) {
        def suffix = UUID.randomUUID().toString().replace('-', '')
        def role = 'search_owner_' + suffix
        def name = 'search_indexes_' + suffix
        // DB-SPECIFIC: PostgreSQL role attributes and extension schema placement require separate capabilities.
        server.createRole(role, "login nosuperuser nocreatedb nocreaterole noinherit")
        server.createDatabase(name, "")
        name = server.databaseNameFor(name)
        execute(server.administrationDatabase, "revoke create on database ${name} from public")
        execute(server.administrationDatabase, "grant connect on database ${name} to ${role}")
        def administrator = connected(name, 'postgres')
        execute(administrator, "create schema app authorization ${role}")
        if (schema != 'app') execute(administrator, "create schema ${schema}")
        execute(administrator, "create extension pg_trgm with schema ${schema}")
        execute(administrator, "grant usage on schema ${schema} to ${role}")
        def migration = connected(name, role)
        assert restricted(migration)
        [administrator: administrator, migration: migration, role: role]
    }

    private DataSource connected(String name, String role) {
        server.getDatabase(role, name)
    }

    private static def migrate(DataSource database, String target) {
        Flyway.configure().dataSource(database).schemas('app')
                .locations('filesystem:' + Baseline.MIGRATIONS).target(target)
                .failOnMissingLocations(true).validateMigrationNaming(true).load().migrate()
    }

    private static Map<String, Integer> migrationStages() {
        def files = new File(Baseline.MIGRATIONS).listFiles()
        assert files != null
        def indexed = files.findAll { it.name ==~ /V\d+__search_trigrams\.sql/ }
        assert indexed.size() == 1
        def target = Integer.parseInt(indexed.first().name.substring(1).split('__')[0])
        def versions = files.findAll { it.name ==~ /V\d+__.*\.sql/ }
                .collect { Integer.parseInt(it.name.substring(1).split('__')[0]) }
        assert !versions.empty
        def preceding = versions.findAll { it < target }.max()
        assert preceding != null
        [previous: preceding, target: target]
    }

    private static boolean restricted(DataSource database) {
        // DB-SPECIFIC: real PostgreSQL role attributes and current-database CREATE privilege.
        def role = JdbcClient.create(database).sql("""
                select not rolsuper and not rolcreatedb and not rolcreaterole
                       and not has_database_privilege(current_database(), 'CREATE') as restricted
                  from pg_catalog.pg_roles where rolname = current_user
                """).query(Boolean).single()
        assert role
        role
    }

    private static List<Map> indexesIn(DataSource database) {
        // DB-SPECIFIC: PostgreSQL pg_index, indkey, pg_attribute and extension catalogs identify index targets and GIN opclasses; regclass/regnamespace casts resolve OIDs.
        JdbcClient.create(database).sql("""
                select relation.relname as index_name, index.indisvalid, index.indisready,
                       index.indnkeyatts, index.indnatts, target.relname as table_name,
                       table_namespace.nspname as table_schema, attribute.attname as column_name,
                       method.amname, opclass.opcname, namespace.nspname as opclass_schema,
                       extension.extname as extension_name, type.typname as type_name
                  from pg_catalog.pg_index index
                  join pg_catalog.pg_class relation on relation.oid = index.indexrelid
                  join pg_catalog.pg_class target on target.oid = index.indrelid
                  join pg_catalog.pg_namespace table_namespace on table_namespace.oid = target.relnamespace
                  left join pg_catalog.pg_attribute attribute
                    on attribute.attrelid = index.indrelid and attribute.attnum = index.indkey[0]
                  join pg_catalog.pg_am method on method.oid = relation.relam
                  join pg_catalog.pg_opclass opclass on opclass.oid = index.indclass[0]
                  join pg_catalog.pg_namespace namespace on namespace.oid = opclass.opcnamespace
                  join pg_catalog.pg_type type on type.oid = opclass.opcintype
                  join pg_catalog.pg_depend member
                    on member.classid = 'pg_catalog.pg_opclass'::pg_catalog.regclass
                   and member.objid = opclass.oid and member.deptype = 'e'
                   and member.refclassid = 'pg_catalog.pg_extension'::pg_catalog.regclass
                  join pg_catalog.pg_extension extension on extension.oid = member.refobjid
                 where relation.relnamespace = 'app'::regnamespace
                   and relation.relname like '%!_trigrams' escape '!'
                 order by relation.relname
                """).query().listOfRows()
    }

    private static List extensionIdentity(DataSource database) {
        // DB-SPECIFIC: PostgreSQL pg_extension records extension and namespace OIDs.
        JdbcClient.create(database).sql("""
                select extension.oid, extension.extnamespace, extension.extversion
                  from pg_catalog.pg_extension extension where extension.extname = 'pg_trgm'
                """).query { row, number -> [row.getLong(1), row.getLong(2), row.getString(3)] }.list()
    }

    private static PSQLException sqlCauseOf(Throwable failed) {
        def cause = failed
        while (!(cause instanceof PSQLException) && cause.cause != null) cause = cause.cause
        assert cause instanceof PSQLException
        cause as PSQLException
    }

    private static Map factsOf(Map target) {
        def administrator = JdbcClient.create(target.administrator)
        // DB-SPECIFIC: PostgreSQL catalogs, regnamespace/regclass casts, to_regclass, pg_get_userbyid and schema/database privilege functions expose ownership and migration capabilities.
        def tables = administrator.sql("""
                select relation.relname, pg_catalog.pg_get_userbyid(relation.relowner) as owner
                  from pg_catalog.pg_class relation
                 where relation.relnamespace = 'app'::pg_catalog.regnamespace and relation.relkind = 'r'
                 order by relation.relname
                """).query().listOfRows()
        def extension = administrator.sql("""
                select extension.oid, extension.extversion, namespace.nspname,
                       opclass.opcname, method.amname, member.deptype::text as membership
                  from pg_catalog.pg_extension extension
                  join pg_catalog.pg_namespace namespace on namespace.oid = extension.extnamespace
                  left join pg_catalog.pg_depend member
                    on member.refclassid = 'pg_catalog.pg_extension'::pg_catalog.regclass
                   and member.refobjid = extension.oid and member.deptype = 'e'
                   and member.classid = 'pg_catalog.pg_opclass'::pg_catalog.regclass
                  left join pg_catalog.pg_opclass opclass on opclass.oid = member.objid
                  left join pg_catalog.pg_am method on method.oid = opclass.opcmethod
                 where extension.extname = 'pg_trgm'
                 order by opclass.opcname
                """).query().listOfRows()
        def historyPresent = administrator.sql("select to_regclass('app.flyway_schema_history') is not null")
                .query(Boolean).single()
        def history = historyPresent ? administrator.sql("""
                select installed_rank, version, script, success from app.flyway_schema_history order by installed_rank
                """).query().listOfRows() : []
        def privileges = JdbcClient.create(target.migration).sql("""
                select current_user as role, not rolsuper as not_superuser,
                       not rolcreatedb as not_database_creator, not rolcreaterole as not_role_creator,
                       has_database_privilege(current_database(), 'CREATE') as database_create,
                       has_schema_privilege('app', 'USAGE') as application_usage
                  from pg_catalog.pg_roles where rolname = current_user
                """).query().listOfRows().first()
        [tables: tables, extension: extension, indexes: indexesIn(target.administrator),
         history: history, privileges: privileges]
    }

    private static void save(String name, Map before, Map after, PSQLException failure, Map cost = [:]) {
        def directory = System.getenv('SE2_QUERY_PLANS')
        if (directory == null) return
        def destination = new File(directory, 'migration-facts')
        destination.mkdirs()
        new File(destination, name + '.json').text = JsonMapper.builder().build().writeValueAsString([
                before: before, after: after, cost: cost,
                failure: failure == null ? null : [sqlState: failure.SQLState, message: failure.message]
        ]) + '\n'
    }

    private static void execute(DataSource database, String sql) {
        database.connection.withCloseable { connection ->
            connection.createStatement().withCloseable { it.execute(sql) }
        }
    }
}
