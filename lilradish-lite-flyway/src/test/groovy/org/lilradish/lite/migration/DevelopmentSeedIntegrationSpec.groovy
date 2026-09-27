package org.lilradish.lite.migration

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.sql.Connection
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.PropertySource
import org.springframework.core.io.ClassPathResource
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
 * reason, and fails then. The last feature runs the files again itself rather than asking Flyway
 * to, because asking Flyway would only prove that Flyway skipped them.
 */
class DevelopmentSeedIntegrationSpec extends Specification {

    static final String SEEDER = "00000000-0000-4000-8000-000000000000"

    static final String GRACE = "00000001-0000-4000-8000-000000000102"

    static final String HANDLING = "00000007-0000-4000-8000-000000000104"

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    @AutoCleanup
    Connection session

    /** The dev profile's own locations, so what runs here is what a developer's run resolves to. */
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
        def runner = onlyDocumentOf("application.yaml")
        def dev = onlyDocumentOf("application-dev.yaml")
        locations = devProfileLocations(dev)
        assert !locations.isEmpty(), "the dev profile names no location, so nothing would be seeded"
        def flyway = Flyway.configure()
                .dataSource(database)
                .schemas("app")
                .locations(*locations.collect { "classpath:${it}" as String })
                .failOnMissingLocations(flywayFlag(dev, runner, "fail-on-missing-locations"))
                .validateMigrationNaming(flywayFlag(dev, runner, "validate-migration-naming"))
                .load()
        flyway.migrate()
        /* A script is named relative to whichever location it was found under, so each is looked
         * for under every one of them; one found nowhere is left null, and reddens at the assert. */
        fixtures = flyway.info().applied().findAll { it.repeatable && !it.type.synthetic }.collect { applied ->
            locations.collect { "${it}/${applied.script}" as String }.find { getClass().classLoader.getResource(it) != null }
        }
        assert !fixtures.isEmpty() && !fixtures.contains(null), "no repeatable migration Flyway applied can be read again"
        session = database.connection
        /* The fixture qualifies no name, as every migration here does, so the session it is run
         * again on has to put the same schema in front of those names that Flyway put there. */
        execute("set search_path to app")
        tables = valuesOf("""
                select relname from pg_class
                 where relnamespace = 'app'::regnamespace and relkind = 'r' and relname <> 'flyway_schema_history'
                """)
        assert !tables.isEmpty(), "the schema has no tables, so a second run would be judged on nothing"
    }

    def "the fixture leaves somebody holding each estate role and several holding none, all of them pooled"() {
        expect:
        rolesHeld() == [
            "000001": ["steward"] as Set,
            "000101": ["watcher"] as Set,
            "000102": [] as Set,
            "000103": [] as Set,
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

    /**
     * The library shows what the application would have let through, and it refuses a pin to anything not in
     * service. A pin is found as a key naming a version from a column of another name, so a new one is held too.
     */
    def "every version the library pins is in service"() {
        given:
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
    def "every run the fixture holds is at the top, of a version not retired, and started by a member of its group"() {
        when:
        def shown = valuesOf("""
                select case
                         when run.parent_run_id is null and version.retired_at is null
                              and exists (select 1 from group_members member
                                           where member.group_id = run.group_id and member.subject_id = run.created_by
                                             and member.removed_at is null) then 'shown'
                         else 'hidden'
                       end
                  from runs run
                  join entry_versions version on version.entry_version_id = run.entry_version_id
                """)

        then:
        shown as Set == ["shown"] as Set
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
    def "no row the seed writes names anybody but the seeder as having done anything, but Grace starting a run"() {
        given:
        def namingColumns = rowsOf("""
                select table_name, column_name from information_schema.columns
                 where table_schema = 'app' and column_name like '%\\_by' and table_name <> 'flyway_schema_history'
                """)

        when:
        def named = namingColumns.collectMany { table, column ->
            valuesOf("select distinct '${table}.${column} ' || ${column} from ${table} where ${column} <> '${SEEDER}'")
        }

        then: "a run at the top is refused the seeder, so its starter is a person"
        named == ["runs.created_by ${GRACE}" as String]

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

        and: "judged on tables holding something; left empty are those only a person writes, a run's once started and a check's"
        before.findAll { it.value == "empty" }.keySet() ==
                ["entry_stops", "entry_version_writers", "group_currencies",
                 "model_call_turnaways", "model_calls", "production_inputs", "production_values", "productions",
                 "review_decisions",
                 "reviews", "run_ceiling_changes", "run_help_exchanges", "run_step_failures", "run_step_holds",
                 "run_step_send_attempts", "run_steps", "run_stops", "soundness_checks",
                 "soundness_counts"] as Set
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
                    rows << [found.getString(1), found.getString(2)]
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
            statement.executeQuery("select coalesce(md5(string_agg(stored::text, '|' order by stored::text)), 'empty')" +
                    " from ${table} stored").withCloseable { found ->
                found.next()
                found.getString(1)
            }
        }
    }

    /** Every workflow version but handling's, whole, for the reason contentsOf gives. */
    private String otherWorkflowVersions() {
        valuesOf("""
                select md5(string_agg(stored::text, '|' order by stored::text))
                  from workflow_versions stored where stored.entry_version_id <> '${HANDLING}'
                """).first()
    }

    /** Read off the classpath a developer's run is given, so what configures Flyway here is what configures it there. */
    private static PropertySource<?> onlyDocumentOf(String file) {
        def documents = new YamlPropertySourceLoader().load(file, new ClassPathResource(file))
        assert documents.size() == 1, "${file} is not the one document the runner reads"
        documents.first()
    }

    /** The dev profile's value where it sets one and the runner's otherwise, which is how Boot resolves it. */
    private static boolean flywayFlag(PropertySource<?> dev, PropertySource<?> runner, String name) {
        def value = [dev, runner].findResult { it.getProperty("spring.flyway.${name}" as String) }
        assert value != null, "neither application-dev.yaml nor application.yaml sets spring.flyway.${name}"
        value.toString().toBoolean()
    }

    /** The dev profile's list alone: a list is replaced whole, so the runner's is never merged into it. */
    private static List<String> devProfileLocations(PropertySource<?> dev) {
        def found = []
        for (int index = 0; dev.containsProperty("spring.flyway.locations[${index}]" as String); index++) {
            def location = dev.getProperty("spring.flyway.locations[${index}]" as String).toString()
            assert location.startsWith("classpath:"), "${location} is not a location this spec can read files from"
            found << location.substring("classpath:".length())
        }
        found
    }

    /** Read off the classpath Flyway read it from, so the file run again is the file that was run. */
    private String asWritten(String fixture) {
        getClass().classLoader.getResourceAsStream(fixture).withCloseable { it.getText("UTF-8") }
    }

    private void execute(String statements) {
        session.createStatement().withCloseable { it.execute(statements) }
    }
}
