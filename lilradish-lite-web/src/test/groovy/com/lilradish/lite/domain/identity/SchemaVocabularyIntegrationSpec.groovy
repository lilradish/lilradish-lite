package com.lilradish.lite.domain.identity

import com.lilradish.lite.domain.codestep.CodeErrorReason
import com.lilradish.lite.domain.declaration.DeclarationSide
import com.lilradish.lite.domain.declaration.FieldKind
import com.lilradish.lite.domain.declaration.FieldStanding
import com.lilradish.lite.domain.identity.fixture.ModelCallLabelPins
import com.lilradish.lite.domain.identity.fixture.RegistryLabelPins
import com.lilradish.lite.domain.identity.fixture.RunLabelPins
import com.lilradish.lite.domain.identity.fixture.TryLabelPins
import com.lilradish.lite.domain.identity.fixture.WorkflowLabelPins
import com.lilradish.lite.domain.inference.DidNotFitReason
import com.lilradish.lite.domain.inference.ModelCallOutcome
import com.lilradish.lite.domain.inference.ModelCallPurpose
import com.lilradish.lite.domain.members.GroupMemberRemoval
import com.lilradish.lite.domain.registry.EntryKind
import com.lilradish.lite.domain.run.ReviewOutcome
import com.lilradish.lite.domain.run.ReviewUnbuiltReason
import com.lilradish.lite.domain.run.RunCeilingChangeOutcome
import com.lilradish.lite.domain.run.RunStepFailureReason
import com.lilradish.lite.domain.run.RunStepHoldReason
import com.lilradish.lite.domain.run.TryLostReason
import com.lilradish.lite.domain.workflow.StepKind
import com.lilradish.lite.domain.workflow.StepProducer
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.PgTrgm
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.Locale
import java.util.regex.Pattern
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * Every schema vocabulary must be named as owned or deliberately unowned, never both.
 * Java enums may remain unpersisted, so completeness is enforced only from schema to Java.
 */
class SchemaVocabularyIntegrationSpec extends Specification {

    /**
     * Doubled quotes must remain inside a label rather than ending it.
     * Schema qualifiers depend on the session's search path, so both forms must match.
     */
    static final Pattern CAST_TO_AN_ENUM = Pattern.compile(/'((?:[^']|'')*)'::(?:\w+\.)?(\w+)/)

    static final Map<String, Class<? extends Enum>> OWNERS = [
            "code_error_reason": CodeErrorReason,
            "declaration_side": DeclarationSide,
            "did_not_fit_reason": DidNotFitReason,
            "entry_kind": EntryKind,
            "estate_role": EstateRole,
            "field_kind": FieldKind,
            "field_standing": FieldStanding,
            "group_member_removal": GroupMemberRemoval,
            "group_role": GroupRole,
            "model_call_outcome": ModelCallOutcome,
            "model_call_purpose": ModelCallPurpose,
            "review_outcome": ReviewOutcome,
            "review_unbuilt_reason": ReviewUnbuiltReason,
            "run_ceiling_change_outcome": RunCeilingChangeOutcome,
            "run_step_failure_reason": RunStepFailureReason,
            "run_step_hold_reason": RunStepHoldReason,
            "step_kind": StepKind,
            "step_producer": StepProducer,
            "try_lost_reason": TryLostReason]

    /** Unowned vocabularies must be named explicitly so an owner cannot appear silently. */
    static final Set<String> UNOWNED_ON_PURPOSE = [
        "code_step",
        "run_step_kind",
        "soundness_check_outcome",
        "subject_kind",
    ] as Set

    /**
     * Pins must be explicit so changes between valid labels are caught as well as invalid labels.
     * A label's type is part of its identity.
     */
    static final Map<String, List<String>> PINNED_TO_A_LABEL = joined(RegistryLabelPins.PINS, WorkflowLabelPins.PINS,
            RunLabelPins.PINS, ModelCallLabelPins.PINS, TryLabelPins.PINS)

    @Shared
    @AutoCleanup
    TestPostgres server = TestPostgres.worker()

    @Shared
    @AutoCleanup
    Connection session

    @Shared
    List<Class<?>> javaEnums

    @Shared
    Map<String, List<String>> labels = [:]

    /**
     * Require the location before interpolation so an unset property cannot become a directory name.
     */
    static final String MIGRATIONS = Objects.requireNonNull(System.getProperty("baseline.location"),
            "baseline.location was not set; the build names it to test and to pitest")

    def setupSpec() {
        DataSource database = server.postgresDatabase
        PgTrgm.preparedIn(database)
        // Migrations stay off the test classpath to avoid unrequested Flyway auto-configuration.
        // Missing locations and invalid migration names must fail as they do at deployment.
        Flyway.configure()
                .dataSource(database)
                .schemas("app")
                .locations("filesystem:" + MIGRATIONS)
                .failOnMissingLocations(true)
                .validateMigrationNaming(true)
                .load()
                .migrate()
        session = database.connection
        javaEnums = walkForJavaEnums()
        enumTypesInSchema().each { labels[it] = readLabelsOf(it) }
    }

    def "every vocabulary the schema declares is either owned by a Java type or declared unowned"() {
        expect:
        labels.keySet() == OWNERS.keySet() + UNOWNED_ON_PURPOSE

        and: "and no type is in both lists, which would let an owner hide behind being unowned"
        OWNERS.keySet().disjoint(UNOWNED_ON_PURPOSE)

        and: "asserted of something, rather than passing because the schema declares nothing"
        !labels.isEmpty()
    }

    def "an owned vocabulary's labels are its owner's constants, in the same spelling and the same order"() {
        expect:
        labels[type] == owner.enumConstants.collect { it.name().toLowerCase(Locale.ROOT) }

        and: "and it is a set worth comparing rather than two empties agreeing"
        !labels[type].isEmpty()

        and: "and its owner is the type the naming convention names, which is what the unowned ones are held to"
        owner.simpleName == conventionalOwnerOf(type)

        where:
        type << OWNERS.keySet()
        owner << OWNERS.values()
    }

    /**
     * Unowned types have no agreed labels, so owner detection must use names even when labels disagree.
     */
    def "no Java type already exists for a vocabulary declared unowned"() {
        expect:
        javaEnums.every { it.simpleName != conventionalOwnerOf(type) }

        where:
        type << UNOWNED_ON_PURPOSE
    }

    def "every owner named here is a type the walk over the compiled classes actually finds"() {
        expect:
        javaEnums.containsAll(OWNERS.values())

        and: "and the walk reaches far more than the pairing does, which is why it is not the population"
        javaEnums.size() > OWNERS.size()
    }

    def "every label a rule, a default or a generated column pins is declared here, and is one its type has"() {
        given:
        def pinned = labelsPinnedInDefinitions()

        expect:
        pinned == PINNED_TO_A_LABEL

        and: "and each one names a type this schema has and a label that type carries"
        pinned.values().flatten().every { pin ->
            def (type, label) = pin.split(":", 2)
            labels.containsKey(type) && label in labels[type]
        }

        and: "asserted of something, rather than passing because the schema pins nothing"
        !pinned.isEmpty()
    }

    /** A key pinned in two areas would let one area's labels silently replace the other's. */
    private static Map<String, List<String>> joined(Map<String, List<String>>... areas) {
        Map<String, List<String>> joined = [:]
        areas.each { area ->
            area.each { key, pins ->
                if (joined.putIfAbsent(key, pins) != null) {
                    throw new IllegalStateException("${key} is pinned in more than one area")
                }
            }
        }
        joined
    }

    private static String conventionalOwnerOf(String type) {
        type.split("_").collect { it.capitalize() }.join()
    }

    /** By the type's own kind rather than through pg_enum, where a type declared with no label yet has no row. */
    private Set<String> enumTypesInSchema() {
        // DB-SPECIFIC: pg_type and ::regnamespace identify PostgreSQL's enum types in a schema.
        queryForRows("""
            select t.typname
              from pg_type t
             where t.typnamespace = 'app'::regnamespace and t.typtype = 'e'
            """).collect { it[0] } as Set
    }

    private List<String> readLabelsOf(String type) {
        // DB-SPECIFIC: pg_type, pg_enum, enumsortorder and ::regnamespace read PostgreSQL's enum declaration order.
        queryForRows("""
            select e.enumlabel
              from pg_type t
              join pg_enum e on e.enumtypid = t.oid
             where t.typnamespace = 'app'::regnamespace and t.typname = '${type}'
             order by e.enumsortorder
            """).collect { it[0] }
    }

    /**
     * Inspect stored casts, since column types cannot identify every enum pin.
     * Read the server's definitions, since it may rewrite migration expressions.
     */
    private Map<String, List<String>> labelsPinnedInDefinitions() {
        def types = labels.keySet()
        def found = [:]
        // DB-SPECIFIC: PostgreSQL's pg_* catalogs, ::regnamespace, pg_get_constraintdef() and pg_get_expr()
        // return stored check, index predicate, default and generated expressions.
        queryForRows("""
            select coalesce(t.relname, d.typname) || '.' || con.conname, pg_get_constraintdef(con.oid)
              from pg_constraint con
              left join pg_class t on t.oid = con.conrelid
              left join pg_type d on d.oid = con.contypid
             where con.connamespace = 'app'::regnamespace and con.contype = 'c'
            union all
            select t.relname || '.' || idx.relname, pg_get_expr(i.indpred, i.indrelid)
              from pg_index i
              join pg_class idx on idx.oid = i.indexrelid
              join pg_class t on t.oid = i.indrelid
              join pg_namespace n on n.oid = idx.relnamespace
             where n.nspname = 'app' and i.indpred is not null
            union all
            select t.relname || '.' || a.attname, pg_get_expr(d.adbin, d.adrelid)
              from pg_attrdef d
              join pg_class t on t.oid = d.adrelid
              join pg_attribute a on a.attrelid = d.adrelid and a.attnum = d.adnum
             where t.relnamespace = 'app'::regnamespace
            """).each { row ->
            def pins = []
            def casts = CAST_TO_AN_ENUM.matcher(row[1] as String)
            while (casts.find()) {
                def type = casts.group(2)
                if (type in types) {
                    pins << type + ":" + casts.group(1).replace("''", "'")
                }
            }
            if (!pins.isEmpty()) {
                found[row[0]] = pins.unique().toSorted()
            }
        }
        found
    }

    private static List<Class<?>> walkForJavaEnums() {
        def classes = Path.of(GroupRole.protectionDomain.codeSource.location.toURI())
        Files.walk(classes.resolve("com/lilradish/lite")).withCloseable { paths ->
            paths.filter { it.toString().endsWith(".class") }
                    .map { load(classes.relativize(it)) }
                    .filter { it.isEnum() }
                    .toList()
        }
    }

    private static Class<?> load(Path relative) {
        Class.forName(relative.toString().replace(".class", "").replace("/", "."), false,
                SchemaVocabularyIntegrationSpec.classLoader)
    }

    private List<List<String>> queryForRows(String query) {
        session.createStatement().withCloseable { statement ->
            statement.executeQuery(query).withCloseable { rows ->
                def found = []
                def columns = rows.metaData.columnCount
                while (rows.next()) {
                    found << (1..columns).collect { rows.getString(it) }
                }
                found
            }
        }
    }
}
