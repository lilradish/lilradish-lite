package com.lilradish.lite.migration

import com.lilradish.lite.migration.fixture.PgTrgm
import com.lilradish.lite.testsupport.TestPostgres
import java.sql.Connection
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * The population a developer points a browser at. It is reached by a location the deployed
 * configuration does not name, so nothing on the deploy path can fail over it — which is the same
 * as saying nothing on the deploy path would report it broken either.
 *
 * <p>What it has to keep is that running it again changes nothing, and that is the half that rots
 * silently. Flyway re-applies a repeatable migration only when its own bytes change, so a statement
 * that cannot survive a second run stays green until somebody edits the file for an unrelated
 * reason, and fails then. The repeatability feature runs the files again itself rather than asking Flyway
 * to, because asking Flyway would only prove that Flyway skipped them.
 */
class DevelopmentSeedIntegrationSpec extends Specification {

    static final String SEEDER = "00000000-0000-4000-8000-000000000000"

    static final String SYSTEM = "00000000-0000-4000-8000-000000000001"

    static final String GRACE = "00000001-0000-4000-8000-000000000102"

    static final String ALAN = "00000001-0000-4000-8000-000000000103"

    static final String HANDLING = "00000007-0000-4000-8000-000000000104"

    @Shared
    @AutoCleanup
    TestPostgres server = TestPostgres.isolated()

    @Shared
    @AutoCleanup
    Connection session

    /** The local profile's own locations, so what runs here is what a developer's run resolves to. */
    @Shared
    List<String> locations

    /** Every repeatable migration Flyway applied, in the order it applied them, so a file added is run again too. */
    @Shared
    List<String> fixtures

    /** Every table in the schema, so a second run is judged on all of them rather than on those somebody listed. */
    @Shared
    List<String> tables

    def setupSpec() {
        DataSource database = server.postgresDatabase
        PgTrgm.preparedIn(database)
        def local = profileConfiguration("local")
        locations = local.locations
        assert !locations.isEmpty(), "the local profile names no location, so nothing would be seeded"
        def flyway = Flyway.configure()
                .dataSource(database)
                .schemas("app")
                .locations(*locations.collect { "classpath:${it}" as String })
                .failOnMissingLocations(local.failOnMissingLocations)
                .validateMigrationNaming(local.validateMigrationNaming)
                .load()
        flyway.migrate()
        /* A script is named relative to whichever location it was found under, so each is looked
         * for under every one of them; one found nowhere is left null, and reddens at the assert. */
        fixtures = flyway.info().applied().findAll { it.repeatable && !it.type.synthetic }.collect { applied ->
            locations.collect { "${it}/${applied.script}" as String }.find { getClass().classLoader.getResource(it) != null }
        }
        assert !fixtures.isEmpty() && !fixtures.contains(null), "no repeatable migration Flyway applied can be read again"
        session = database.connection
        /* The fixture qualifies no name, so the session it is run again on has to put the same
         * schema in front of those names that Flyway put there. */
        execute("set search_path to app")
        // DB-SPECIFIC: ::, pg_class and regnamespace use PostgreSQL's syntax and semantics.
        tables = valuesOf("""
                select relname from pg_class
                 where relnamespace = 'app'::regnamespace and relkind = 'r' and relname <> 'flyway_schema_history'
                """)
        assert !tables.isEmpty(), "the schema has no tables, so a second run would be judged on nothing"
    }

    def '#profile alone resolves seed locations and migrates a populated demonstration database'() {
        given:
        def configuration = profileConfiguration(profile)
        server.createDatabase("${profile}_profile", "")
        def database = server.getDatabase('postgres', "${profile}_profile")
        PgTrgm.preparedIn(database)
        def flyway = Flyway.configure()
                .dataSource(database)
                .schemas('app')
                .locations(*configuration.locations.collect { "classpath:${it}" as String })
                .failOnMissingLocations(configuration.failOnMissingLocations)
                .validateMigrationNaming(configuration.validateMigrationNaming)
                .load()

        when:
        def migrated = flyway.migrate()
        def seeded = flyway.info().applied().findAll { it.repeatable && !it.type.synthetic }*.script
        def people = []
        database.connection.withCloseable { connection ->
            connection.createStatement().withCloseable { statement ->
                statement.executeQuery('select user_id from app.people order by user_id').withCloseable { found ->
                    while (found.next()) {
                        people << found.getString(1)
                    }
                }
            }
        }

        then:
        configuration.profiles == [profile]
        configuration.sources.any { it.contains("application-${profile}.yaml") }
        configuration.locations == ['db/migration/common', 'db/migration/local']
        migrated.success
        seeded.toSet() == fixtures.collect { it.tokenize('/').last() }.toSet()
        people.containsAll(['000101', '000102', '000103'])
        !people.contains('000001')
        !people.contains('999999')

        where:
        profile << ['local', 'dev']
    }

    def "the fixture leaves somebody holding each estate role and several holding none, all of them pooled"() {
        expect:
        rolesHeld() == [
            "000001": ["steward"] as Set,
            "000101": ["watcher"] as Set,
            "000102": ["steward"] as Set,
            "000103": ["watcher"] as Set,
            "000104": [] as Set,
            "000105": [] as Set,
            "000106": [] as Set,
        ]
    }

    /**
     * Everybody pooled with a name is in the directory under that same name, so what a search offers
     * and what the pool shows agree. The one exception is somebody still pooled after leaving the
     * directory, which is a state the pool has to be read in too.
     */
    def "the directory holds everybody the fixture pools under the name the pool holds, but for one who left it"() {
        when:
        def pooled = namesOf("""
                select person.user_id, person.display_name
                  from pool_members stay
                  join subjects person on person.subject_id = stay.subject_id
                 where stay.removed_at is null and person.display_name is not null
                """)
        def listed = namesOf("select user_id, display_name from people")

        then:
        pooled.every { listed[it.key] == it.value }

        and: "the one who left is pooled and nowhere in the directory"
        rolesHeld().containsKey("000106")
        !listed.containsKey("000106")

        and: "over a pool and a directory both holding people, several of them in the directory alone"
        pooled.size() == 5
        (listed.keySet() - rolesHeld().keySet()).size() > pooled.size()
    }

    /**
     * What a search is tried against: several names sharing a part, so one part finds more than one
     * person, and names a reader types outside ASCII — an accented letter, an ideographic space.
     */
    def "the directory holds names a part of which finds several people, and names outside ASCII"() {
        when:
        def listed = namesOf("select user_id, display_name from people").values()

        then:
        listed.findAll { it.contains("Ada") }.size() > 1
        listed.findAll { it.contains("山田") }.size() > 1
        listed.any { it.contains(Character.toString(0x3000)) }
        listed.any { it.contains(Character.toString(0xC9)) }
    }

    /**
     * Standing is derived and never stored, so this derives it as a page would: retired over in
     * service, in service over waiting on a submission not withdrawn, and a draft otherwise.
     */
    def "the library holds a version at every standing"() {
        when:
        def held = valuesOf("""
                select case
                         when version.retired_at is not null then 'retired'
                         when version.approved_at is not null then 'in service'
                         when exists (select 1 from entry_version_submissions submission
                                       where submission.entry_version_id = version.entry_version_id
                                         and submission.withdrawn_at is null) then 'submitted'
                         else 'draft'
                       end
                  from entry_versions version
                """)

        then:
        held as Set == ["draft", "submitted", "in service", "retired"] as Set
    }

    def "both fictional groups have members and a shared person, and the code publication reaches both"() {
        expect:
        rowsOf("""select held.key, held.name, person.user_id, member.role
            from groups held join group_members member using (group_id)
            join subjects person using (subject_id)
            where member.removed_at is null order by held.key, person.user_id""") == [
            ['BILLING', 'Imaginary Mooncrate billing', '000102', 'owner'],
            ['BILLING', 'Imaginary Mooncrate billing', '000105', 'overseer'],
            ['SUPPORT', 'Customer support', '000102', 'owner'],
            ['SUPPORT', 'Customer support', '000103', 'overseer'],
        ]
        valuesOf("select group_key from code_step_publications where code_step = 'stamp_reference' order by group_key") ==
                ['BILLING', 'SUPPORT']
    }

    def "each selector kind has named versions, with a complete reference-list draft beside its in-service version"() {
        expect:
        // DB-SPECIFIC: PostgreSQL converts the native entry_kind enum to text for this ordering.
        rowsOf("""select entry.kind, version.number, version.version_name
            from entry_versions version join entries entry using (entry_id)
            where entry.entry_id in ('00000006-0000-4000-8000-000000000101',
                                    '00000006-0000-4000-8000-000000000102',
                                    '00000006-0000-4000-8000-000000000103')
            order by cast(entry.kind as text), version.number""") == [
            ['question', '1', 'Original complaint summary'],
            ['question', '2', 'Category and order reference'],
            ['question', '3', 'Missing order reference guidance'],
            ['question', '4', 'Evidence-aware complaint categories'],
            ['reference_list', '1', 'Current complaint categories'],
            ['reference_list', '2', 'Remedy guidance draft'],
            ['workflow', '1', 'Customer support routing'],
            ['workflow', '2', 'Next support workflow draft'],
        ]
        rowsOf("""select term.position, term.term, term.meaning from reference_list_terms term
            where term.entry_version_id = '00000007-0000-4000-8000-000000000113' order by term.position""") == [
            ['1', 'Billing', 'Check the disputed charge or refund before proposing a remedy.'],
            ['2', 'Delivery', 'Check the fictional dispatch record and ask for a box photograph.'],
            ['3', 'Product fault', 'Ask what failed during ordinary use before offering a replacement.'],
        ]
    }

    def "the editable support draft pins the older of two named in-service questions without moving historical pins"() {
        expect:
        rowsOf("""select version.number, version.version_name from entry_versions version
            where version.entry_id = '00000006-0000-4000-8000-000000000101'
              and version.approved_at is not null and version.retired_at is null order by version.number""") == [
            ['2', 'Category and order reference'], ['4', 'Evidence-aware complaint categories'],
        ]
        rowsOf("""select draft.version_name, step.name, pinned.version_name
            from entry_versions draft join workflow_steps step using (entry_version_id)
            join entry_versions pinned on pinned.entry_version_id = step.pinned_version_id
            where draft.entry_version_id = '00000007-0000-4000-8000-000000000105'
              and draft.approved_at is null and draft.retired_at is null""") ==
                [['Next support workflow draft', 'summarise', 'Category and order reference']]
        valuesOf("""select pinned_version_id from workflow_steps
            where workflow_step_id = '0000000c-0000-4000-8000-000000000101'""") ==
                ['00000007-0000-4000-8000-000000000102']
    }

    /**
     * The library shows what the application would have let through, and it refuses a pin to anything not in
     * service. A pin is found as a key naming a version from a column of another name, so a new one is held too.
     */
    def "every version the library pins is in service"() {
        given:
        // DB-SPECIFIC: ::, pg_constraint, pg_class, pg_attribute, regnamespace and regclass use PostgreSQL's syntax and semantics.
        def pins = rowsOf("""
                select pinning_table.relname, pinning_column.attname
                  from pg_constraint pinning_key
                  join pg_class pinning_table on pinning_table.oid = pinning_key.conrelid
                  join pg_attribute pinning_column
                    on pinning_column.attrelid = pinning_key.conrelid and pinning_column.attnum = pinning_key.conkey[1]
                 where pinning_key.connamespace = 'app'::regnamespace and pinning_key.contype = 'f'
                   and pinning_key.confrelid = 'app.entry_versions'::regclass
                   and pinning_column.attname <> 'entry_version_id'
                """)

        when:
        def standings = pins.collectMany { table, column ->
            valuesOf("""
                select case
                         when pinned.retired_at is not null then 'retired'
                         when pinned.approved_at is not null then 'in service'
                         else 'not yet approved'
                       end
                  from ${table} pinning
                  join entry_versions pinned on pinned.entry_version_id = pinning.${column}
                """)
        }

        then:
        standings as Set == ["in service"] as Set

        and: "judged over pins of every kind there is, each of which the library holds"
        !pins.isEmpty()
        pins.every { table, column -> valuesOf("select count(${column}) from ${table}").first() != "0" }
    }

    /**
     * A run page has something to show only to somebody in the run's group, and the schema lets a run at the
     * top name a retired version and anybody as its starter, so both are held here.
     */
    def "every run at the top the fixture holds is of a version not retired, and started by a member of its group"() {
        when:
        def shown = valuesOf("""
                select case
                         when version.retired_at is null
                              and exists (select 1 from group_members member
                                           where member.group_id = run.group_id and member.subject_id = run.created_by
                                             and member.removed_at is null) then 'shown'
                         else 'hidden'
                       end
                  from runs run
                  join entry_versions version on version.entry_version_id = run.entry_version_id
                 where run.parent_run_id is null
                """)

        then:
        shown as Set == ["shown"] as Set

        and: "judged over every run at the top, of which there are several"
        shown.size() > 1
    }

    /** A list of runs is narrowed by workflow and by who started it, and dates a run by the day it started. */
    def "the runs at the top differ in #listedBy, so a list of them has something to narrow and to date"() {
        when:
        def listed = valuesOf("""
                select ${listing}
                  from runs run
                  join entries entry on entry.entry_id = run.entry_id
                  join subjects person on person.subject_id = run.created_by
                 where run.parent_run_id is null
                """)

        then:
        listed as Set == held as Set
        // DB-SPECIFIC: PostgreSQL timestamptz literal and extract(day from interval).

        where:
        listedBy           | listing                                              || held
        "workflow"         | "entry.name"                                         || ["Handle a complaint", "Escalate a complaint", "Stamp a complaint", "Research a fictional claim from files", "Imaginary Mooncrate settlement review", "Replace a damaged delivery"]
        "starter"          | "person.display_name"                                || ["Grace Starling", "Alan Cloudmere"]
        "whole days begun" | "least(extract(day from timestamptz '2000-01-10 00:00:00+00' - run.created_at), 2)" || ["0", "1", "2"]
    }

    def "the runs are numbered from one without a gap, each after every run started before it"() {
        when:
        def numbers = valuesOf("select number from runs order by number")
        def byStart = valuesOf("select number from runs order by created_at")

        then:
        numbers == (1..numbers.size()).collect { it as String }
        byStart == numbers

        and: "judged over runs beneath as well as at the top"
        (valuesOf("select count(*) from runs where parent_run_id is not null").first() as int) > 0
    }

    def "a run beneath was started by its route on the case the category it was given names"() {
        when:
        // DB-SPECIFIC: #>> uses PostgreSQL's syntax and semantics.
        def beneath = valuesOf("""
                select concat_ws(' ', run.number, above.number, routed.term, given.value #>> '{}')
                  from runs run
                  join runs above on above.run_id = run.parent_run_id
                  join route_cases routed on routed.route_case_id = run.parent_route_case_id
                  join production_inputs taken on taken.branch_run_id = run.run_id
                  join production_values given on given.production_value_id = taken.source_production_value_id
                 order by run.number
                """)

        then:
        beneath == ["3 2 Billing Billing", "9 8 Billing Billing"]
        valuesOf("select count(*) from runs where parent_route_case_id is not null") == [beneath.size() as String]
    }

    def "a run beneath a step running a workflow runs the version that step pins and names no case"() {
        when:
        // DB-SPECIFIC: PostgreSQL run_step_kind enum label selects the runs started beneath a step running a workflow.
        def beneath = valuesOf("""
                select concat_ws(' ', run.number, above.number, step.name, entry.name)
                  from runs run
                  join runs above on above.run_id = run.parent_run_id
                  join workflow_steps step on step.workflow_step_id = run.parent_workflow_step_id
                  join entries entry on entry.entry_id = run.entry_id
                 where run.parent_run_step_kind = 'workflow' and run.parent_route_case_id is null
                   and step.pinned_version_id = run.entry_version_id
                 order by run.number
                """)
        def routed = valuesOf("select count(*) from runs where parent_route_case_id is not null").first() as int

        then:
        beneath == ["18 17 escalate Escalate a complaint"]

        and: "every run beneath is either one a route started or one of these"
        valuesOf("select count(*) from runs where parent_run_id is not null") == [(routed + beneath.size()) as String]
        routed > 0
    }

    def "a run the fixture holds has a ceiling whose raise waits on approval, and none raised yet"() {
        when:
        def approvals = valuesOf("""
                select version.raise_needs_approval
                  from runs run
                  join workflow_versions version on version.entry_version_id = run.entry_version_id
                 where version.ceiling is not null
                """)

        then:
        approvals.contains("t")
        valuesOf("select count(*) from run_ceiling_changes") == ["0"]
    }

    /** A database seeded before the flag was set takes it on a second run, unless somebody has edited the row since. */
    def "running the library seed again sets handling's raise approval on a row left without it, but not on an edited one"() {
        given:
        // DB-SPECIFIC: now() uses PostgreSQL's syntax and semantics.
        def othersBefore = otherWorkflowVersions()
        execute("""
                update workflow_versions
                   set raise_needs_approval = false, updated_at = ${editedBy == null ? "null" : "now()"},
                       updated_by = ${editedBy == null ? "null" : "'${editedBy}'"}
                 where entry_version_id = '${HANDLING}'
                """)

        when:
        execute(asWritten(fixtures.find { it.endsWith("/R__dev_03_library.sql") }))

        then:
        valuesOf("select raise_needs_approval from workflow_versions where entry_version_id = '${HANDLING}'") == [raised]
        otherWorkflowVersions() == othersBefore

        and: "judged over versions there are"
        othersBefore != null

        cleanup:
        execute("""
                update workflow_versions set raise_needs_approval = true, updated_at = null, updated_by = null
                 where entry_version_id = '${HANDLING}'
                """)

        where:
        editedBy || raised
        null     || "t"
        GRACE    || "f"
    }

    /**
     * Every column naming who did something, found by its name rather than listed, so one added later
     * is held to this as well. Flyway's own history names the database role that ran it, not a subject.
     */
    def "no row the seed writes names anybody but the seeder as having done anything, but in a run's tree"() {
        given:
        def namingColumns = rowsOf("""
                select table_name, column_name from information_schema.columns
                 where table_schema = 'app' and column_name like '%\\_by' and table_name <> 'flyway_schema_history'
                """)

        when:
        def named = namingColumns.collectMany { table, column ->
            valuesOf("select distinct '${table}.${column} ' || ${column} from ${table} where ${column} <> '${SEEDER}'")
        }

        then: "a run at the top is refused the seeder, so a person starts it, reviews it and stops it, and the system does the rest"
        named as Set == ["runs.created_by ${GRACE}", "runs.created_by ${ALAN}", "runs.created_by ${SYSTEM}",
                         "run_steps.created_by ${SYSTEM}",
                         "productions.created_by ${SYSTEM}", "productions.created_by ${GRACE}", "productions.ended_by ${SYSTEM}",
                         "productions.ended_by ${ALAN}",
                         "run_step_send_attempts.created_by ${SYSTEM}", "model_calls.created_by ${SYSTEM}",
                         "reviews.created_by ${GRACE}", "reviews.created_by ${ALAN}", "reviews.created_by ${SYSTEM}",
                         "reviews.production_ended_by ${SYSTEM}", "reviews.production_ended_by ${ALAN}",
                         "run_stops.created_by ${GRACE}"].collect { it as String } as Set

        and: "judged over columns that exist, among them the one every table has"
        namingColumns.any { it[1] == "created_by" }
    }

    /** A row written again with what it already held is no change; a row added or altered is. */
    def "running the fixtures a second time changes nothing they hold"() {
        given:
        def before = tables.collectEntries { [it, contentsOf(it)] }

        when:
        fixtures.each { execute(asWritten(it)) }

        then:
        tables.collectEntries { [it, contentsOf(it)] } == before

        and: "judged on tables holding something; left empty are those only a person writes, what no seeded run came to, and a check's"
        before.findAll { it.value == "empty" }.keySet() ==
                ["entry_stops", "entry_version_writers", "group_currencies",
                 "model_call_turnaways",
                 "production_input_parts",
                 "run_ceiling_changes", "run_help_exchanges", "run_step_failures", "run_step_holds",
                 "run_stop_additional_ceilings", "soundness_checks", "soundness_counts",
                 "errored_code_returned_files"] as Set
    }

    def "every attachment run holds nonempty bytes matching its input metadata and its own group"() {
        when:
        // DB-SPECIFIC: ->, ->>, uuid and octet_length() use PostgreSQL's syntax and semantics.
        def attachments = rowsOf("""
                select run.number,
                       case when file.byte_size = octet_length(content.content) and file.byte_size > 0
                             and file.file_id = cast(given.value ->> 'id' as uuid)
                             and file.original_name = given.value ->> 'name'

                             and file.byte_size = cast(given.value ->> 'size' as bigint)
                             and holder.group_id = run.group_id and file.group_id = run.group_id
                            then 'consistent' else 'inconsistent' end
                  from runs run
                  cross join lateral jsonb_array_elements(run.started_with -> 'attachment') given(value)
                  left join run_file_holders holder on holder.run_id = run.run_id and holder.file_id = cast(given.value ->> 'id' as uuid)
                  left join stored_files file on file.file_id = holder.file_id
                  left join file_contents content on content.file_id = file.file_id
                 where run.started_with -> 'attachment' is not null or holder.run_id is not null
                 order by run.number
                """)

        then:
        attachments.every { it[1] == "consistent" }
        attachments.collect { it[0] }.containsAll(["13", "14", "15"])

        and: "every file is stored, kept and held once, by the run it came with"
        valuesOf("select count(*) from stored_files") == [attachments.size() as String]
        valuesOf("select count(distinct file_id) from run_file_holders") == [attachments.size() as String]
    }

    def "the stopped research preserves its call before the stop, and all seeded dates are fixed in the past"() {
        expect:
        // DB-SPECIFIC: PostgreSQL interval, timestamptz and boolean casts to text.
        valuesOf("""select cast(sent.created_at < call.ended_at and call.ended_at = production.ended_at and production.ended_at < stop.created_at as text)
            from run_stops stop join runs run using (run_id)
            join model_calls call using (run_id)
            join run_step_send_attempts sent using (run_step_send_attempt_id)
            join productions production on production.production_id = call.production_id
            where run.number = 13""") == ['true']
        valuesOf("select count(*) from runs where created_at >= timestamptz '2000-01-03' and created_at < timestamptz '2000-01-11'") == ['18']
        valuesOf("select count(*) from groups where created_at = timestamptz '2000-01-01'") == ['2']
        valuesOf("select count(*) from group_members where created_at = timestamptz '2000-01-01'") == ['4']
    }

    def "repeating the research fixture leaves another group's same-number run untouched"() {
        given:
        session.autoCommit = false
        // DB-SPECIFIC: PostgreSQL timestamptz literal and INSERT SELECT.
        execute("""insert into groups (group_id, key, name, created_at, created_by)
            values ('00000002-0000-4000-8000-000000000e55', 'PRACTICE', 'Invented practice group',
                timestamptz '2000-01-01', '${SEEDER}');
            insert into entries (entry_id, group_id, kind, name, created_by)
            values ('00000006-0000-4000-8000-000000000e55', '00000002-0000-4000-8000-000000000e55',
                'workflow', 'Invented unrelated workflow', '${SEEDER}');
            insert into entry_versions (entry_version_id, entry_id, entry_kind, number,
                created_by, approved_at, approved_by, approved_by_kind)
            values ('00000007-0000-4000-8000-000000000e55', '00000006-0000-4000-8000-000000000e55',
                'workflow', 1, '${SEEDER}', now(), '${SEEDER}', 'seeder');
            insert into workflow_versions (entry_version_id, created_by)
            values ('00000007-0000-4000-8000-000000000e55', '${SEEDER}');
            insert into runs (run_id, group_id, number, name, entry_id, entry_version_id, root_run_id,
                depth, started_with, created_at, created_by, created_by_kind)
            select '0000000f-0000-4000-8000-000000000e55', '00000002-0000-4000-8000-000000000e55',
                13, 'Invented unrelated complaint', '00000006-0000-4000-8000-000000000e55',
                '00000007-0000-4000-8000-000000000e55',
                '0000000f-0000-4000-8000-000000000e55', 0, started_with, created_at, created_by, created_by_kind
            from runs where number = 1""")
        def before = tables.collectEntries { [(it): contentsOf(it)] }
        def research = fixtures.find { it.endsWith('R__dev_04_runs_02_attachment_research.sql') }
        assert research != null

        when:
        execute(asWritten(research))

        then:
        tables.collectEntries { [(it): contentsOf(it)] } == before
        valuesOf("select count(*) from run_steps where run_id = '0000000f-0000-4000-8000-000000000e55'") == ['0']

        cleanup:
        session.rollback()
        session.autoCommit = true
    }

    private List<String> valuesOf(String query) {
        def values = []
        session.createStatement().withCloseable { statement ->
            statement.executeQuery(query).withCloseable { found ->
                while (found.next()) {
                    values << found.getString(1)
                }
            }
        }
        values
    }

    private List<List<String>> rowsOf(String query) {
        def rows = []
        session.createStatement().withCloseable { statement ->
            statement.executeQuery(query).withCloseable { found ->
                while (found.next()) {
                    rows << (1..found.metaData.columnCount).collect { found.getString(it) }
                }
            }
        }
        rows
    }

    private Map<String, String> namesOf(String query) {
        def named = [:]
        session.createStatement().withCloseable { statement ->
            statement.executeQuery(query).withCloseable { found ->
                while (found.next()) {
                    named[found.getString(1)] = found.getString(2)
                }
            }
        }
        named
    }

    /**
     * Everybody currently in the pool against every estate role they currently hold. A left join
     * rather than two queries, so somebody pooled and holding nothing is a key carrying an empty
     * set rather than a key that is absent — which is the difference between the third thing a reader
     * can be and a gap in the fixture.
     */
    private Map<String, Set<String>> rolesHeld() {
        def held = [:]
        session.createStatement().withCloseable { statement ->
            statement.executeQuery("""
                select person.user_id, holding.role
                  from pool_members stay
                  join subjects person on person.subject_id = stay.subject_id
                  left join estate_role_grants holding
                         on holding.subject_id = stay.subject_id and holding.removed_at is null
                 where stay.removed_at is null
                """).withCloseable { found ->
                while (found.next()) {
                    def roles = held.computeIfAbsent(found.getString(1)) { [] as Set }
                    def role = found.getString(2)
                    if (role != null) {
                        roles << role
                    }
                }
            }
        }
        held
    }

    /**
     * The whole of a table rather than how many rows it has. A second run that replaced a row in
     * place would leave a count alone, and created_at is inside this digest — which no re-insert
     * could reproduce even if it wrote the same columns.
     */
    private String contentsOf(String table) {
        session.createStatement().withCloseable { statement ->
            // DB-SPECIFIC: ::, string_agg(), md5() and text use PostgreSQL's syntax and semantics.
            statement.executeQuery("select coalesce(md5(string_agg(stored::text, '|' order by stored::text)), 'empty')" +
                    " from ${table} stored").withCloseable { found ->
                found.next()
                found.getString(1)
            }
        }
    }

    /** Every workflow version but handling's, whole, for the reason contentsOf gives. */
    private String otherWorkflowVersions() {
        // DB-SPECIFIC: ::, string_agg(), md5() and text use PostgreSQL's syntax and semantics.
        valuesOf("""
                select md5(string_agg(stored::text, '|' order by stored::text))
                  from workflow_versions stored where stored.entry_version_id <> '${HANDLING}'
                """).first()
    }

    private static Map profileConfiguration(String profile) {
        def environment = new StandardEnvironment()
        environment.propertySources.addFirst(new MapPropertySource('profile selection', [
            'spring.profiles.active': profile,
            'spring.config.location': 'classpath:/',
        ]))
        ConfigDataEnvironmentPostProcessor.applyTo(environment)
        def locations = []
        for (int index = 0; environment.containsProperty("spring.flyway.locations[${index}]"); index++) {
            def location = environment.getProperty("spring.flyway.locations[${index}]")
            assert location.startsWith('classpath:')
            locations << location.substring('classpath:'.length())
        }
        [profiles: environment.activeProfiles.toList(), sources: environment.propertySources*.name,
         locations: locations,
         failOnMissingLocations: environment.getRequiredProperty('spring.flyway.fail-on-missing-locations', Boolean),
         validateMigrationNaming: environment.getRequiredProperty('spring.flyway.validate-migration-naming', Boolean)]
    }

    /** Read off the classpath Flyway read it from, so the file run again is the file that was run. */
    private String asWritten(String fixture) {
        getClass().classLoader.getResourceAsStream(fixture).withCloseable { it.getText("UTF-8") }
    }

    private void execute(String statements) {
        session.createStatement().withCloseable { it.execute(statements) }
    }
}
