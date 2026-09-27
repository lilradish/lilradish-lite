package org.lilradish.lite.migration

import static org.lilradish.lite.migration.fixture.SchemaKeys.*
import static org.lilradish.lite.migration.fixture.SchemaRows.*
import static org.lilradish.lite.migration.fixture.V10Cases.*
import static org.lilradish.lite.migration.fixture.V11Cases.*
import static org.lilradish.lite.migration.fixture.V12Cases.*
import static org.lilradish.lite.migration.fixture.V13Cases.*
import static org.lilradish.lite.migration.fixture.V8Cases.*
import static org.lilradish.lite.migration.fixture.V9Cases.*

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.sql.Connection
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import org.lilradish.lite.migration.fixture.V10Cases
import org.lilradish.lite.migration.fixture.V11Cases
import org.lilradish.lite.migration.fixture.V12Cases
import org.lilradish.lite.migration.fixture.V13Cases
import org.lilradish.lite.migration.fixture.V1Cases
import org.lilradish.lite.migration.fixture.V2Cases
import org.lilradish.lite.migration.fixture.V3Cases
import org.lilradish.lite.migration.fixture.V4Cases
import org.lilradish.lite.migration.fixture.V6Cases
import org.lilradish.lite.migration.fixture.V7Cases
import org.lilradish.lite.migration.fixture.V8Cases
import org.lilradish.lite.migration.fixture.V9Cases
import org.postgresql.util.PSQLException
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * What the baseline's rules refuse is proved here and nowhere else. Its migrations compile nothing and
 * are covered by nothing a unit test can reach, so what a constraint refuses is only known by asking a
 * server to refuse it — and the server asked is pinned to the version production runs, because a rule
 * proved on a different engine has been proved about a different engine.
 *
 * <p>A list of cases somebody wrote out is the migration copied into a second file and catches
 * nothing added after it. What makes this a check is that the population is discovered from the
 * catalogue and the cases are asserted to cover it exactly: a constraint added without a case here
 * reddens, and so does a case naming a constraint that was removed.
 *
 * <p>Each case asserts the name the refusal carries rather than that something was refused. Two
 * defences stand behind several of these rows — a check on what the row claims about a subject, and
 * a foreign key on whether that claim is true — and a case content with any exception stays green
 * when either is deleted.
 */
class SchemaConstraintIntegrationSpec extends Specification {

    static final List<String> FIXTURE = [*V1Cases.FIXTURE, *V2Cases.FIXTURE, *V3Cases.FIXTURE, *V4Cases.FIXTURE,
                                         *V6Cases.FIXTURE, *V7Cases.FIXTURE, *V8Cases.FIXTURE, *V9Cases.FIXTURE,
                                         *V10Cases.FIXTURE, *V11Cases.FIXTURE, *V12Cases.FIXTURE, *V13Cases.FIXTURE]

    static final List<Map<String, String>> CASES = [*V1Cases.CASES, *V2Cases.CASES, *V3Cases.CASES, *V4Cases.CASES,
                                                    *V6Cases.CASES, *V7Cases.CASES, *V8Cases.CASES, *V9Cases.CASES,
                                                    *V10Cases.CASES, *V11Cases.CASES, *V12Cases.CASES, *V13Cases.CASES]

    /**
     * A unique key whose columns are a proper superset of its table's primary key cannot be broken
     * without breaking that primary key first, so no row reaches it. It is here to be a foreign key
     * target and nothing else, which "the rules no case attacks are the ones no row can reach" derives rather
     * than takes on trust.
     */
    static final Set<String> REACHED_ONLY_AS_A_KEY_TARGET = [
        "bindings.bindings_consumer_source_unique",
        "bindings.bindings_source_step_unique",
        "declaration_fields.declaration_fields_parent_key_unique",
        "declaration_fields.declaration_fields_standing_unique",
        "declaration_fields.declaration_fields_threshold_unique",
        "entries.entries_group_unique",
        "entries.entries_kind_unique",
        "entry_versions.entry_versions_approved_unique",
        "entry_versions.entry_versions_entry_unique",
        "entry_versions.entry_versions_kind_unique",
        "model_calls.model_calls_outcome_unique",
        "model_calls.model_calls_production_outcome_unique",
        "model_calls.model_calls_run_purpose_unique",
        "production_values.production_values_needs_review_unique",
        "production_values.production_values_production_unique",
        "productions.productions_ender_yielded_unique",
        "productions.productions_pinned_unique",
        "productions.productions_root_unique",
        "productions.productions_run_step_unique",
        "productions.productions_step_producer_unique",
        "productions.productions_step_reviewed_yielded_unique",
        "productions.productions_step_yielded_unique",
        "productions.productions_yielded_unique",
        "review_decisions.review_decisions_outcome_unique",
        "reviews.reviews_decided_unique",
        "route_cases.route_cases_target_unique",
        "route_cases.route_cases_version_unique",
        "run_help_exchanges.run_help_exchanges_root_answered_unique",
        "run_step_failures.run_step_failures_production_purpose_unique",
        "run_step_holds.run_step_holds_run_reason_unique",
        "run_step_send_attempts.run_step_send_attempts_purpose_model_production_unique",
        "run_step_send_attempts.run_step_send_attempts_reviewed_too_long_unique",
        "run_step_send_attempts.run_step_send_attempts_scope_holds_payload_unique",
        "run_step_send_attempts.run_step_send_attempts_step_too_long_unique",
        "run_steps.run_steps_calls_a_model_unique",
        "run_steps.run_steps_pinned_unique",
        "run_steps.run_steps_producer_unique",
        "run_steps.run_steps_workflow_step_kind_unique",
        "runs.runs_depth_unique",
        "runs.runs_group_unique",
        "runs.runs_parent_workflow_step_unique",
        "runs.runs_root_unique",
        "runs.runs_version_unique",
        "soundness_checks.soundness_checks_outcome_unique",
        "subjects.subjects_kind_unique",
        "workflow_steps.workflow_steps_kind_unique",
        "workflow_steps.workflow_steps_pinned_unique",
        "workflow_steps.workflow_steps_producer_model_unique",
        "workflow_steps.workflow_steps_producer_unique",
        "workflow_steps.workflow_steps_reviewer_model_unique",
        "workflow_steps.workflow_steps_version_unique",
        "workflow_versions.workflow_versions_helper_unique",
    ] as Set

    /** Where a name is held to one shape: the table, how a row carrying one is written, and the rule refusing it. */
    static final List<List<String>> NAMED = [
        ["declaration_fields", setting("declaration_fields", "name = %s"), "declaration_fields_name_shape"],
        ["workflow_steps", setting("workflow_steps", "name = %s"), "workflow_steps_name_shape"],
        ["workflow_steps", insertInto("workflow_steps", A_STEP + [kind: "'entry'", pinned_version_id: "'${VERSION}'",
                pinned_kind: "'question'", producer: "'model'", producer_mode: "'ordinary'", producer_model: "%s"]),
         "workflow_steps_producer_model_shape"],
        ["workflow_steps", insertInto("workflow_steps", A_STEP + [kind: "'entry'", pinned_version_id: "'${VERSION}'",
                pinned_kind: "'question'", reviewer_mode: "'ordinary'", reviewer_model: "%s"]),
         "workflow_steps_reviewer_model_shape"],
        ["workflow_steps", insertInto("workflow_steps", A_STEP + [kind: "'entry'", pinned_version_id: "'${VERSION}'",
                pinned_kind: "'question'", producer: "'model'", producer_model: "'general'", producer_mode: "%s"]),
         "workflow_steps_producer_mode_shape"],
        ["workflow_steps", insertInto("workflow_steps", A_STEP + [kind: "'entry'", pinned_version_id: "'${VERSION}'",
                pinned_kind: "'question'", reviewer_model: "'general'", reviewer_mode: "%s"]),
         "workflow_steps_reviewer_mode_shape"],
        ["workflow_versions", setting("workflow_versions",
                "may_be_helped = true, helper_mode = 'ordinary', helper_model = %s"),
         "workflow_versions_helper_model_shape"],
        ["workflow_versions", setting("workflow_versions",
                "may_be_helped = true, helper_model = 'general', helper_mode = %s"),
         "workflow_versions_helper_mode_shape"],
        ["production_values", "update app.production_values set field_name = %s" +
                " where production_value_id = '${CODE_VALUE}'", "production_values_field_name_shape"],
    ]

    /**
     * Where a mode is carried under a key to the one a step or a version names: the table, how a row carrying one is
     * written, the rule refusing its shape, and the key refusing a name that is not the one named.
     */
    static final List<List<String>> MODE_CARRIED = [
        ["run_step_send_attempts", insertInto("run_step_send_attempts", A_SEND_ATTEMPT + [mode: "%s"]),
         "run_step_send_attempts_mode_shape", "run_step_send_attempts_producer_model_fk"],
        ["model_calls", insertInto("model_calls", A_HELP_CALL + [mode: "%s"]), "model_calls_mode_shape",
         "model_calls_helper_fk"],
    ]

    /** Each with whether it is a name; anything outside printable ASCII is written as a code point. */
    static final List<List<Object>> NAMES = [
        ["a", true],
        ["a" * 63, true],
        ["z9_", true],
        ["a" * 64, false],
        ["", false],
        ["A", false],
        ["aB", false],
        ["1a", false],
        ["_a", false],
        ["a-b", false],
        ["a b", false],
        ["a" + Character.toString(0x000A), false],
        [Character.toString(0x00E9), false],
        ["a" + Character.toString(0x0131), false],
        [Character.toString(0x212A), false],
        [Character.toString(0xFF41), false],
    ]

    static final List<List<String>> PATHS_IN_BINDINGS = [
        ["target_path = %s", "bindings_target_path_shape"],
        ["source_path = %s", "bindings_source_path_shape"],
    ]

    /** Each with whether it is a path; the longest is the longest held, one character short of refused. */
    static final List<List<Object>> PATHS = [
        ["a", true],
        ["a.b", true],
        ["details.order_reference", true],
        ["a" * 63 + ".b", true],
        ["a" + ".a" * 511, true],
        ["a" * 64 + ".b", false],
        ["", false],
        ["a.", false],
        [".a", false],
        ["a..b", false],
        ["a.B", false],
        ["a[0]", false],
        ["a.0", false],
        ["a/b", false],
        ["a. b", false],
    ]

    /** Each reason code gone wrong may be given for that is about a field of the code step. */
    static final List<String> ABOUT_A_FIELD = ["nothing_given", "not_its_kind", "too_long", "too_many", "not_a_term",
                                               "unkeepable", "too_long_to_keep", "takes_a_list_not_here",
                                               "gives_a_list_not_here", "takes_otherwise", "gives_otherwise"]

    /** Each reason about no field at all; the one left, a member nothing declares, may be about either. */
    static final List<String> ABOUT_NO_FIELD = ["gave_nothing", "failed_on_this_side", "said_nothing"]

    /** Where a try names a field by path: the column, and the rule holding it to a path's shape and length. */
    static final List<List<String>> PATHS_IN_PRODUCTIONS = [
        ["code_error_path", "productions_code_error_path_shape", "productions_code_error_path_bounded"],
        ["code_error_read_by_output", "productions_code_error_read_by_output_shape",
         "productions_code_error_read_by_output_bounded"],
    ]

    /** Where a group key is held to one shape: how a row carrying one is written, and the rule refusing it. */
    static final List<List<String>> GROUP_KEYED = [
        ["groups", "insert into app.groups (group_id, key, name, created_by)" +
                " values ('${SPARE_GROUP}', %s, 'Billing', '${STEWARD}')", "groups_key_shape"],
        ["code_step_publications", insertInto("code_step_publications", A_PUBLICATION + [group_key: "%s"]),
         "code_step_publications_group_key_shape"],
    ]

    /** Each with whether it is a group key; anything outside printable ASCII is written as a code point. */
    static final List<List<Object>> GROUP_KEYS = [
        ["AB", true],
        ["Z" * 16, true],
        ["A", false],
        ["Z" * 17, false],
        ["Ab", false],
        ["A1", false],
        ["A" + Character.toString(0x00C9), false],
        ["A" + Character.toString(0xFF21), false],
        ["A" + Character.toString(0x212A), false],
        ["A" + Character.toString(0x0130), false],
        ["A" + Character.toString(0x0131), false],
        ["A" + Character.toString(0x017F), false],
        ["AB" + Character.toString(0x000A), false],
    ]

    /** Keys no index leads, on purpose: no row they name is ever deleted, and what is read through them is the one
     * open row, which a partial unique index on the same column serves. */
    static final Set<String> READ_THROUGH_A_PARTIAL_INDEX = [
        "entry_stops.entry_stops_entry_fk",
        "entry_version_submissions.entry_version_submissions_version_fk",
        "group_members.group_members_group_fk",
    ] as Set

    /**
     * Keys no index leads, on purpose: no row they name is ever deleted or has the key they name changed, either of
     * which looks for them by a scan, and nothing reads back from it to them.
     */
    static final Set<String> NEVER_READ_BACK = [
        "production_inputs.production_inputs_run_fk",
        "production_inputs.production_inputs_source_production_fk",
        "production_inputs.production_inputs_source_root_fk",
        "production_inputs.production_inputs_source_run_step_fk",
        "production_inputs.production_inputs_step_fk",
        "reviews.reviews_hold_fk",
        "reviews.reviews_hold_run_fk",
        "reviews.reviews_too_long_attempt_fk",
        "run_step_failures.run_step_failures_produced_fk",
        "run_step_holds.run_step_holds_attempt_fk",
        "run_step_send_attempts.run_step_send_attempts_repeats_fk",
        "run_stops.run_stops_ceiling_run_fk",
        "runs.runs_parent_pinned_version_fk",
        "runs.runs_top_level_entry_fk",
        "soundness_counts.soundness_counts_group_fk",
    ] as Set

    static final Set<String> UNINDEXED_ON_PURPOSE = READ_THROUGH_A_PARTIAL_INDEX + NEVER_READ_BACK

    /** Keys a page reads rows by who wrote them through, each with an index of its own, partial or not. */
    static final Set<String> READ_BY_AUTHOR = [
        "runs.runs_author_kind_fk",
    ] as Set

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    /** One session for the suite. Every case is its own statement, and a failed statement in
     * autocommit leaves the session usable, so a connection per statement buys nothing. */
    @Shared
    @AutoCleanup
    Connection session

    def setupSpec() {
        DataSource database = server.postgresDatabase
        /* The location and the two flags are the runner's own, from its application.yaml. Without
         * them this path accepts what the deploy path refuses — a location holding nothing, or a
         * file whose name Flyway will not parse — and the build goes green over an artefact that
         * cannot start. The location is also what keeps the development fixture out of this server:
         * it sits under the same root, and a root is scanned recursively. */
        Flyway.configure()
                .dataSource(database)
                .schemas("app")
                .locations("classpath:db/migration/common")
                .failOnMissingLocations(true)
                .validateMigrationNaming(true)
                .load()
                .migrate()
        session = database.connection
        /* Committed before any fixture names them: a label added to a type that already exists cannot
         * be used in the transaction that added it. */
        [CODE_STEP, OTHER_CODE_STEP].each { execute("alter type app.code_step add value '${it}'") }
        FIXTURE.each { execute(it) }
    }

    def "every rule the schema enforces has a case that attacks it"() {
        given:
        def discovered = enforceableRules()

        expect:
        (CASES.collect { ruleOf(it) } as Set) + REACHED_ONLY_AS_A_KEY_TARGET == discovered

        and: "neither side is empty, so agreement cannot be two nothings matching"
        !discovered.isEmpty()
        discovered.size() == CASES.size() + REACHED_ONLY_AS_A_KEY_TARGET.size()

        and: "and no case is written twice, which would let one cover for a rule nothing attacks"
        CASES.collect { ruleOf(it) }.toSet().size() == CASES.size()
    }

    def "the rules no case attacks are the ones no row can reach"() {
        expect:
        REACHED_ONLY_AS_A_KEY_TARGET.every { subsumesThePrimaryKeyOf(it) }

        and: "asserted of something, rather than passing because the set is empty"
        !REACHED_ONLY_AS_A_KEY_TARGET.isEmpty()
    }

    def "#rule refuses the row it forbids, and nothing is written"() {
        given:
        def before = contentsOf(table)

        when:
        execute(statement)

        then:
        def refused = thrown(PSQLException)
        refused.serverErrorMessage.constraint == constraint

        and: "and the table is what it was, so no part of the refused act landed"
        contentsOf(table) == before

        where:
        [rule, constraint, table, statement] << CASES.collect { [ruleOf(it), it.constraint, it.table, it.statement] }
    }

    def "a membership closes with its time, who closed it and how, all three or none of them"() {
        when:
        def refusedBy = attempt("group_members",
                "update app.group_members set ${assignments} where group_member_id = '${HOLDING}'")

        then:
        refusedBy == refusal

        where:
        assignments                                                               || refusal
        "removed_at = now(), removed_by = '${STEWARD}', removal = 'role_taken'"   || null
        "removed_at = now(), removed_by = '${STEWARD}'"                           || "group_members_removed_together"
        "removed_by = '${STEWARD}', removal = 'removed_from_group'"               || "group_members_removed_together"
        "removal = 'removed_from_group'"                                          || "group_members_removed_together"
    }

    def "a role once closed may be held again"() {
        when:
        def refusedBy = attempt("group_members",
                "update app.group_members set removed_at = now(), removed_by = '${STEWARD}', removal = 'role_taken'" +
                        " where group_member_id = '${HOLDING}';" +
                        " insert into app.group_members (group_id, subject_id, role, created_by)" +
                        " values ('${GROUP}', '${STEWARD}', 'owner', '${STEWARD}')")

        then:
        refusedBy == null
    }

    def "a group name is held once, whatever case either was typed in"() {
        when:
        def refusedBy = attempt("groups", "insert into app.groups (group_id, key, name, created_by)" +
                " values ('${SPARE_GROUP}', 'BILLING', '${name}', '${STEWARD}')")

        then:
        refusedBy == refusal

        where:
        name                || refusal
        "customer support"  || "groups_name_unique"
        "Customer Support"  || "groups_name_unique"
        "Customer supports" || null
    }

    /** Folded in full, so a sharp s is the two letters it folds to, and one group name holds either spelling. */
    def "a group name is held once whichever way a sharp s is spelt"() {
        when:
        def refusedBy = attempt("groups", NAME_THE_OTHER_GROUP_STRASSE + "insert into app.groups (group_id, key," +
                " name, created_by) values ('${SPARE_GROUP}', 'BILLING', ${nameAsWritten}, '${STEWARD}')")

        then:
        refusedBy == refusal

        where:
        nameAsWritten                  || refusal
        "'STRASSE'"                    || "groups_name_unique"
        "'strasse'"                    || "groups_name_unique"
        "'STRA' || chr(7838) || 'E'"   || "groups_name_unique"
        "'Strase'"                     || null
    }

    def "an entry name is held once within its group and kind, whatever case either was typed in"() {
        when:
        def refusedBy = attempt("entries", "insert into app.entries (entry_id, group_id, kind, name, created_by)" +
                " values ('${SPARE_ENTRY}', '${group}', '${kind}', '${name}', '${STEWARD}')")

        then:
        refusedBy == refusal

        where:
        group       | kind       | name                  || refusal
        GROUP       | "question" | "customer complaint"  || "entries_name_unique"
        GROUP       | "workflow" | "Customer complaint"  || null
        OTHER_GROUP | "question" | "Customer complaint"  || null
        GROUP       | "question" | "Customer complaints" || null
    }

    def "an entry name is one to 128 characters"() {
        when:
        def refusedBy = attempt("entries", "insert into app.entries (entry_id, group_id, kind, name, created_by)" +
                " values ('${SPARE_ENTRY}', '${GROUP}', 'question', '${name}', '${STEWARD}')")

        then:
        refusedBy == refusal

        where:
        name      || refusal
        ""        || "entries_name_visible"
        "a"       || null
        "a" * 128 || null
    }

    def "a version is of the kind its entry is"() {
        when:
        def refusedBy = attempt("entry_versions", "insert into app.entry_versions (entry_id, entry_kind, number, created_by)" +
                " values ('${entry}', '${kind}', 3, '${STEWARD}')")

        then:
        refusedBy == refusal

        where:
        entry          | kind             || refusal
        LIST_ENTRY     | "reference_list" || null
        WORKFLOW_ENTRY | "workflow"       || null
        LIST_ENTRY     | "workflow"       || "entry_versions_entry_fk"
        WORKFLOW_ENTRY | "question"       || "entry_versions_entry_fk"
    }

    def "a version opens at revision one when none is named"() {
        when:
        def stored = readInside(OPEN_A_LIST_VERSION, "select revision::text || '|' || count(*) over ()" +
                " from app.entry_versions where entry_id = '${LIST_ENTRY}' and number = 3")

        then:
        stored == "1|1"
    }

    def "a version's revision is one or more, whether it is opened at one or a save moves it on"() {
        when:
        def refusedBy = attempt("entry_versions", statement)

        then:
        refusedBy == refusal

        where:
        statement                                                                                  || refusal
        OPEN_A_LIST_VERSION_AT_REVISION_ONE                                                        || null
        "update app.entry_versions set revision = 2 where entry_version_id = '${DRAFT}'"           || null
        "update app.entry_versions set revision = 1000 where entry_version_id = '${DRAFT}'"        || null
        "update app.entry_versions set revision = 0 where entry_version_id = '${DRAFT}'"           || "entry_versions_revision_positive"
        "update app.entry_versions set revision = -1 where entry_version_id = '${DRAFT}'"          || "entry_versions_revision_positive"
    }

    def "the seeder approves only a version it opened, and a person never one they opened"() {
        when:
        def refusedBy = attempt("entry_versions", "insert into app.entry_versions" +
                " (entry_id, entry_kind, number, created_by, approved_at, approved_by, approved_by_kind)" +
                " values ('${ENTRY}', 'question', 3, '${opener}', now(), '${approver}', '${kind}')")

        then:
        refusedBy == refusal

        where:
        opener  | approver | kind     || refusal
        SEEDER  | SEEDER   | "seeder" || null
        STEWARD | SEEDER   | "seeder" || "entry_versions_approver_is_opener_exactly_for_seeder"
        STEWARD | STEWARD  | "person" || "entry_versions_approver_is_opener_exactly_for_seeder"
        SEEDER  | STEWARD  | "person" || null
        MEMBER  | STEWARD  | "person" || null
    }

    def "the seeder approves a version only as it opens it"() {
        when:
        def refusedBy = attempt("entry_versions", "insert into app.entry_versions" +
                " (entry_id, entry_kind, number, created_at, created_by, approved_at, approved_by, approved_by_kind)" +
                " values ('${ENTRY}', 'question', 3, ${openedAt}, '${SEEDER}', now(), '${SEEDER}', 'seeder')")

        then:
        refusedBy == refusal

        where:
        openedAt                   || refusal
        "now()"                    || null
        "now() - interval '1 day'" || "entry_versions_seeder_approves_as_it_opens"
    }

    def "the seeder retires only a version it opened, and only as it opens it"() {
        when:
        def refusedBy = attempt("entry_versions", "insert into app.entry_versions (entry_id, entry_kind, number," +
                " created_at, created_by, approved_at, approved_by, approved_by_kind, retired_at, retired_by, retired_by_kind)" +
                " values ('${ENTRY}', 'question', 3, ${openedAt}, '${opener}', now(), '${approver}', '${approverKind}'," +
                " now(), '${SEEDER}', 'seeder')")

        then:
        refusedBy == refusal

        where:
        opener  | openedAt                   | approver | approverKind || refusal
        SEEDER  | "now()"                    | SEEDER   | "seeder"     || null
        STEWARD | "now()"                    | MEMBER   | "person"     || "entry_versions_seeder_retires_as_it_opens"
        SEEDER  | "now() - interval '1 day'" | STEWARD  | "person"     || "entry_versions_seeder_retires_as_it_opens"
    }

    def "a version is retired no earlier than it was approved"() {
        when:
        def refusedBy = attempt("entry_versions", "update app.entry_versions set retired_at = ${retiredAt}," +
                " retired_by = '${STEWARD}' where entry_version_id = '${VERSION}'")

        then:
        refusedBy == refusal

        where:
        retiredAt                                || refusal
        "approved_at"                            || null
        "approved_at - interval '1 microsecond'" || "entry_versions_retired_after_approved"
    }

    def "a version once approved lets the next draft of its entry open, and one only withdrawn does not"() {
        when:
        def refusedBy = attempt("entry_versions", "${first}insert into app.entry_versions" +
                " (entry_id, entry_kind, number, created_by) values ('${ENTRY}', 'question', 3, '${STEWARD}')")

        then:
        refusedBy == refusal

        where:
        first                   || refusal
        APPROVE_THE_DRAFT       || null
        WITHDRAW_THE_SUBMISSION || "entry_versions_one_unapproved"
    }

    def "a version whose submission was withdrawn may be submitted again"() {
        when:
        def refusedBy = attempt("entry_version_submissions", "${WITHDRAW_THE_SUBMISSION}" +
                "insert into app.entry_version_submissions (entry_version_id, created_by) values ('${DRAFT}', '${STEWARD}')")

        then:
        refusedBy == null
    }

    def "an entry whose stop was let go may be stopped again"() {
        when:
        def refusedBy = attempt("entry_stops",
                "update app.entry_stops set let_go_at = now(), let_go_by = '${STEWARD}' where entry_stop_id = '${STOP}';" +
                        " insert into app.entry_stops (entry_id, created_by) values ('${ENTRY}', '${STEWARD}')")

        then:
        refusedBy == null
    }

    def "every code step is named in lower-case English letters, digits and underscores, starting with a letter"() {
        when:
        def labels = queryForStrings("select enumlabel from pg_enum where enumtypid = 'app.code_step'::regtype")

        then:
        labels.every { it ==~ /[a-z][a-z0-9_]{0,62}/ }

        and: "judged over labels that exist, which here are those setupSpec added as a migration would"
        !labels.isEmpty()
    }

    def "a code step is published to one group key or to every group, and never to both or neither"() {
        when:
        def refusedBy = attempt("code_step_publications", insertInto("code_step_publications",
                A_PUBLICATION + [code_step: literal(OTHER_CODE_STEP), group_key: literal(key), every_group: everyGroup]))

        then:
        refusedBy == refusal

        where:
        key       | everyGroup || refusal
        "BILLING" | "false"    || null
        null      | "true"     || null
        "BILLING" | "true"     || "code_step_publications_exactly_one_form"
        null      | "false"    || "code_step_publications_exactly_one_form"
    }

    def "a code step is published to every group once, and to any number of keys beside that"() {
        when:
        def refusedBy = attempt("code_step_publications", insertInto("code_step_publications",
                A_PUBLICATION + [code_step: literal(codeStep), group_key: literal(key), every_group: everyGroup]))

        then:
        refusedBy == refusal

        where:
        codeStep        | key       | everyGroup || refusal
        CODE_STEP       | null      | "true"     || "code_step_publications_one_every_group"
        CODE_STEP       | "BILLING" | "false"    || null
        OTHER_CODE_STEP | null      | "true"     || null
    }

    def "a ceiling is at least one, and left empty where there is none"() {
        when:
        def refusedBy = attempt("workflow_versions", setting("workflow_versions", "ceiling = ${ceiling}"))

        then:
        refusedBy == refusal

        where:
        ceiling               || refusal
        "1"                   || null
        "9223372036854775807" || null
        "null"                || null
        "0"                   || "workflow_versions_ceiling_positive"
    }

    def "a helper model and mode are named only where the version's runs may be helped, and may be named later"() {
        when:
        def refusedBy = attempt("workflow_versions", setting("workflow_versions",
                "may_be_helped = ${allowed}, helper_model = ${literal(model)}, helper_mode = ${literal(mode)}"))

        then:
        refusedBy == refusal

        where:
        allowed | model     | mode       || refusal
        true    | "general" | "ordinary" || null
        true    | null      | null       || null
        false   | "general" | "ordinary" || "workflow_versions_helper_only_for_may_be_helped"
    }

    def "a helper model is named with its mode"() {
        when:
        def refusedBy = attempt("workflow_versions", setting("workflow_versions",
                "may_be_helped = true, helper_model = ${literal(model)}, helper_mode = ${literal(mode)}"))

        then:
        refusedBy == refusal

        where:
        model     | mode       || refusal
        "general" | "ordinary" || null
        null      | null       || null
        "general" | null       || "workflow_versions_helper_model_together"
        null      | "research" || "workflow_versions_helper_model_together"
    }

    def "a step names a version only where it runs an entry, and a code step only where it runs one"() {
        when:
        def refusedBy = attempt("workflow_steps", insertInto("workflow_steps", A_STEP + [kind: literal(kind),
                pinned_version_id: literal(pinned), pinned_kind: literal(pinnedKind), code_step: literal(codeStep),
                producer: literal(kind == "code_step" ? "code" : null)]))

        then:
        refusedBy == refusal

        where:
        kind        | pinned           | pinnedKind | codeStep  || refusal
        null        | null             | null       | null      || null
        "entry"     | null             | null       | null      || null
        "code_step" | null             | null       | null      || null
        "entry"     | VERSION          | "question" | null      || null
        "entry"     | LIST_VERSION     | null       | null      || "workflow_steps_pinned_together"
        "code_step" | null             | null       | CODE_STEP || null
        "route"     | null             | null       | null      || null
        null        | VERSION          | "question" | null      || "workflow_steps_pinned_only_for_entry"
        "route"     | VERSION          | "question" | null      || "workflow_steps_pinned_only_for_entry"
        null        | null             | null       | CODE_STEP || "workflow_steps_code_step_only_for_code_step"
        "entry"     | null             | null       | CODE_STEP || "workflow_steps_code_step_only_for_code_step"
    }

    def "a step never pins the version it is in"() {
        when:
        def refusedBy = attempt("workflow_steps", insertInto("workflow_steps", A_STEP + [kind: "'entry'",
                entry_version_id: literal(version), pinned_version_id: literal(WORKFLOW_VERSION), pinned_kind: "'workflow'"]))

        then:
        refusedBy == refusal

        where:
        version               || refusal
        NEXT_WORKFLOW_VERSION || null
        WORKFLOW_VERSION      || "workflow_steps_not_own_version"
    }

    def "what a step runs decides who may produce its values, and a workflow or a route has nobody produce or review"() {
        when:
        def refusedBy = attempt("workflow_steps", insertInto("workflow_steps", A_STEP + [kind: literal(kind),
                pinned_version_id: literal(pinned), pinned_kind: literal(pinnedKind), code_step: literal(codeStep),
                producer: literal(producer), producer_model: literal(producerModel), producer_mode: literal(producerMode),
                tries: literal(tries), reviewer_model: literal(reviewerModel), reviewer_mode: literal(reviewerMode)]))

        then:
        refusedBy == refusal

        where:
        kind        | pinned                | pinnedKind | codeStep  | producer | tries | reviewerModel || refusal
        "entry"     | VERSION               | "question" | null      | "model"  | 3     | null          || null
        "entry"     | VERSION               | "question" | null      | "person" | 3     | "general"     || null
        "entry"     | VERSION               | "question" | null      | null     | null  | null          || null
        "entry"     | VERSION               | "question" | null      | "code"   | 3     | null          || "workflow_steps_question_produced_by_model_or_person"
        "code_step" | null                  | null       | CODE_STEP | "code"   | 3     | "general"     || null
        "code_step" | null                  | null       | CODE_STEP | null     | null  | null          || null
        "code_step" | null                  | null       | CODE_STEP | "person" | 3     | null          || null
        "code_step" | null                  | null       | CODE_STEP | "model"  | 3     | null          || "workflow_steps_code_step_produced_by_code_or_person"
        "entry"     | NEXT_WORKFLOW_VERSION | "workflow" | null      | null     | null  | null          || null
        "entry"     | NEXT_WORKFLOW_VERSION | "workflow" | null      | "person" | null  | null          || "workflow_steps_workflow_or_route_unproduced"
        "entry"     | NEXT_WORKFLOW_VERSION | "workflow" | null      | null     | 3     | null          || "workflow_steps_workflow_or_route_unproduced"
        "entry"     | NEXT_WORKFLOW_VERSION | "workflow" | null      | null     | null  | "general"     || "workflow_steps_workflow_or_route_unproduced"
        "route"     | null                  | null       | null      | null     | null  | null          || null
        "route"     | null                  | null       | null      | "model"  | null  | null          || "workflow_steps_workflow_or_route_unproduced"
        "route"     | null                  | null       | null      | null     | 1     | null          || "workflow_steps_workflow_or_route_unproduced"
        "route"     | null                  | null       | null      | null     | null  | "general"     || "workflow_steps_workflow_or_route_unproduced"
        null        | null                  | null       | null      | "code"   | 3     | null          || null

        producerModel = producer == "model" ? "general" : null
        producerMode = producer == "model" ? "ordinary" : null
        reviewerMode = reviewerModel == null ? null : "research"
    }

    def "a producing model and its mode are named together, and only where a model produces"() {
        when:
        def refusedBy = attempt("workflow_steps", insertInto("workflow_steps", A_STEP + [kind: "'entry'",
                pinned_version_id: literal(VERSION), pinned_kind: "'question'", producer: literal(producer),
                producer_model: literal(producerModel), producer_mode: literal(producerMode)]))

        then:
        refusedBy == refusal

        where:
        producer | producerModel | producerMode || refusal
        "model"  | "general"     | "ordinary"   || null
        "model"  | "general"     | "research"   || null
        "model"  | null          | null         || null
        "model"  | "general"     | null         || "workflow_steps_producer_model_together"
        "model"  | null          | "ordinary"   || "workflow_steps_producer_model_together"
        "person" | null          | null         || null
        "person" | "general"     | null         || "workflow_steps_producer_model_only_for_model"
        "person" | null          | "ordinary"   || "workflow_steps_producer_model_only_for_model"
        null     | "general"     | "ordinary"   || "workflow_steps_producer_model_only_for_model"
    }

    def "a step is told what happened only where a model produces"() {
        when:
        def refusedBy = attempt("workflow_steps", insertInto("workflow_steps", A_STEP + [kind: "'entry'",
                pinned_version_id: literal(VERSION), pinned_kind: "'question'", producer: literal(producer),
                tells_what_happened: literal(tells)]))

        then:
        refusedBy == refusal

        where:
        producer | tells || refusal
        "model"  | true  || null
        "model"  | false || null
        "person" | false || null
        null     | false || null
        "person" | true  || "workflow_steps_told_only_for_model"
        null     | true  || "workflow_steps_told_only_for_model"
    }

    def "a step's tries are at least one, and left empty until they are chosen"() {
        when:
        def refusedBy = attempt("workflow_steps", insertInto("workflow_steps", A_STEP + [kind: "'entry'",
                pinned_version_id: literal(VERSION), pinned_kind: "'question'", producer: "'person'", tries: tries]))

        then:
        refusedBy == refusal

        where:
        tries  || refusal
        "1"    || null
        "null" || null
        "-1"   || "workflow_steps_tries_positive"
    }

    def "a reviewing model is named with its mode, whoever produces"() {
        when:
        def refusedBy = attempt("workflow_steps", insertInto("workflow_steps", A_STEP + [kind: "'entry'",
                pinned_version_id: literal(VERSION), pinned_kind: "'question'", producer: literal(producer),
                producer_model: literal(producer == "model" ? "general" : null),
                producer_mode: literal(producer == "model" ? "ordinary" : null),
                reviewer_model: literal(reviewerModel), reviewer_mode: literal(reviewerMode)]))

        then:
        refusedBy == refusal

        where:
        producer | reviewerModel | reviewerMode || refusal
        "model"  | null          | null         || null
        "model"  | "general"     | "research"   || null
        "model"  | "thorough"    | "ordinary"   || null
        "person" | "general"     | "ordinary"   || null
        "person" | "general"     | null         || "workflow_steps_reviewer_model_together"
        "person" | null          | "ordinary"   || "workflow_steps_reviewer_model_together"
    }

    def "a field, a step, a model and a mode are named in lower-case English letters, digits and underscores"() {
        when:
        def refusedBy = attempt(table, String.format(statement, literal(name)))

        then:
        refusedBy == (holds ? null : rule)

        where:
        [table, statement, rule, name, holds] << [NAMED, NAMES].combinations().collect { it.flatten() }
    }

    /** No name here is the one named, so a name reaching the key is a name the shape let through. */
    def "a mode an attempt or a call carries is refused for its shape before the key naming the mode is asked"() {
        when:
        def refusedBy = attempt(table, String.format(statement, literal(name)))

        then:
        refusedBy == (holds ? key : rule)

        where:
        [table, statement, rule, key, name, holds] << [MODE_CARRIED, NAMES].combinations().collect { it.flatten() }
    }

    def "a case is in the version its route is, and is held by a route"() {
        when:
        def refusedBy = attempt("route_cases", insertInto("route_cases",
                A_CASE + [entry_version_id: literal(version), workflow_step_id: literal(step),
                          target_version_id: literal(target)]))

        then:
        refusedBy == refusal

        where:
        version               | step  | target                || refusal
        WORKFLOW_VERSION      | ROUTE | NEXT_WORKFLOW_VERSION || null
        WORKFLOW_VERSION      | ROUTE | null                  || null
        NEXT_WORKFLOW_VERSION | ROUTE | WORKFLOW_VERSION      || "route_cases_step_fk"
        WORKFLOW_VERSION      | STEP  | NEXT_WORKFLOW_VERSION || "route_cases_route_fk"
        WORKFLOW_VERSION      | ROUTE | WORKFLOW_VERSION      || "route_cases_not_own_version"
    }

    def "a route has one fallback at most, and any number of cases with a term beside it"() {
        when:
        def refusedBy = attempt("route_cases", insertInto("route_cases", A_CASE + [term: literal(term)]))

        then:
        refusedBy == refusal

        where:
        term       || refusal
        null       || "route_cases_one_fallback"
        "Delivery" || null
        "Billing"  || null
    }

    def "a field belongs to one question or workflow version, or to one route, and a route's only give back"() {
        when:
        def refusedBy = attempt("declaration_fields", insertInto("declaration_fields", A_FIELD + [
                entry_version_id: literal(version), entry_kind: literal(kind), workflow_step_id: literal(step),
                side: literal(side)]))

        then:
        refusedBy == refusal

        where:
        version          | kind             | step  | side    || refusal
        VERSION          | "question"       | null  | "takes" || null
        WORKFLOW_VERSION | "workflow"       | null  | "takes" || null
        null             | null             | ROUTE | "gives" || null
        VERSION          | "question"       | ROUTE | "gives" || "declaration_fields_exactly_one_owner"
        null             | null             | null  | "takes" || "declaration_fields_exactly_one_owner"
        null             | "question"       | ROUTE | "gives" || "declaration_fields_version_together"
        LIST_VERSION     | "reference_list" | null  | "takes" || "declaration_fields_version_is_question_or_workflow"
        null             | null             | ROUTE | "takes" || "declaration_fields_route_only_for_gives"
    }

    def "a field is held by a field of the same owner, on the same side, that holds fields"() {
        when:
        def refusedBy = attempt("declaration_fields", insertInto("declaration_fields", A_FIELD + [
                entry_version_id: literal(version), entry_kind: literal(kind), side: literal(side),
                parent_field_id: literal(parent)]))

        then:
        refusedBy == refusal

        where:
        version          | kind       | side    | parent || refusal
        VERSION          | "question" | "gives" | PARENT || null
        VERSION          | "question" | "takes" | PARENT || "declaration_fields_parent_fk"
        WORKFLOW_VERSION | "workflow" | "gives" | PARENT || "declaration_fields_parent_fk"
        VERSION          | "question" | "takes" | FIELD  || "declaration_fields_parent_fk"
    }

    def "a length and a count are at least one"() {
        when:
        def refusedBy = attempt("declaration_fields", insertInto("declaration_fields", A_FIELD + [
                holds_many: "true", text_limit: literal(textLimit), many_limit: literal(manyLimit)]))

        then:
        refusedBy == refusal

        where:
        textLimit | manyLimit || refusal
        1         | 1         || null
        0         | 1         || "declaration_fields_text_limit_positive"
        1         | 0         || "declaration_fields_many_limit_positive"
    }

    def "a length, a count and a list are declared only on the fields they belong to, and may wait to be"() {
        when:
        def refusedBy = attempt("declaration_fields", insertInto("declaration_fields", A_FIELD + [
                kind: literal(kind), holds_many: many, text_limit: literal(textLimit), many_limit: literal(manyLimit),
                term_list_version_id: literal(list)]))

        then:
        refusedBy == refusal

        where:
        kind     | many    | textLimit | manyLimit | list         || refusal
        "text"   | "false" | 4000      | null      | null         || null
        "text"   | "false" | null      | null      | null         || null
        "text"   | "true"  | 4000      | 10        | null         || null
        "fields" | "true"  | null      | 3         | null         || null
        "term"   | "false" | null      | null      | LIST_VERSION || null
        "term"   | "false" | null      | null      | null         || null
        "number" | "false" | 4000      | null      | null         || "declaration_fields_text_limit_only_for_text"
        "text"   | "false" | null      | 10        | null         || "declaration_fields_many_limit_only_for_many"
        "text"   | "false" | null      | null      | LIST_VERSION || "declaration_fields_term_list_only_for_term"
    }

    def "every field, taken or given back, at every level, says whether it must be given"() {
        when:
        def refusedBy = attempt("declaration_fields", insertInto("declaration_fields", A_FIELD + [
                side: literal(side), parent_field_id: literal(parent), must_be_given: literal(must)]))

        then:
        refusedBy == refusal

        where:
        side    | parent | must  || refusal
        "takes" | null   | true  || null
        "takes" | null   | false || null
        "gives" | null   | true  || null
        "gives" | PARENT | false || null
        "takes" | null   | null  || "declaration_fields_must_be_given_on_every_field"
        "gives" | null   | null  || "declaration_fields_must_be_given_on_every_field"
        "gives" | PARENT | null  || "declaration_fields_must_be_given_on_every_field"
    }

    def "only what a question gives back, held by no other field, says what it takes to stand"() {
        when:
        def refusedBy = attempt("declaration_fields", insertInto("declaration_fields", A_FIELD + [
                entry_version_id: literal(version), entry_kind: literal(kind), side: literal(side),
                parent_field_id: literal(parent), standing: literal(standing), standing_threshold: literal(threshold)]))

        then:
        refusedBy == refusal

        where:
        version          | kind       | side    | parent | standing           | threshold || refusal
        VERSION          | "question" | "gives" | null   | "always"           | null      || null
        VERSION          | "question" | "gives" | null   | null               | null      || null
        VERSION          | "question" | "gives" | null   | "above_confidence" | null      || null
        VERSION          | "question" | "gives" | null   | "above_confidence" | 1         || null
        VERSION          | "question" | "gives" | null   | "above_confidence" | 100       || null
        WORKFLOW_VERSION | "workflow" | "gives" | null   | null               | null      || null
        VERSION          | "question" | "gives" | PARENT | "never"            | null      || "declaration_fields_standing_only_for_unheld_gives"
        VERSION          | "question" | "takes" | null   | "always"           | null      || "declaration_fields_standing_only_for_unheld_gives"
        WORKFLOW_VERSION | "workflow" | "gives" | null   | "always"           | null      || "declaration_fields_standing_only_for_a_question"
        VERSION          | "question" | "gives" | null   | "never"            | 50        || "declaration_fields_standing_threshold_only_for_above_confidence"
        VERSION          | "question" | "gives" | null   | null               | 50        || "declaration_fields_standing_threshold_only_for_above_confidence"
        VERSION          | "question" | "gives" | null   | "above_confidence" | 0         || "declaration_fields_standing_threshold_range"
        VERSION          | "question" | "gives" | null   | "above_confidence" | 101       || "declaration_fields_standing_threshold_range"
    }

    def "a binding fills a step's input, a case's or an output, and only a route's discriminator fills nothing"() {
        when:
        def refusedBy = attempt("bindings", insertInto("bindings", A_BINDING + [workflow_step_id: literal(step),
                route_case_id: literal(routeCase), target_path: literal(target), step_kind: literal(stepKind)]))

        then:
        refusedBy == refusal

        where:
        step  | routeCase  | target      | stepKind || refusal
        STEP  | null       | "channel"   | null     || null
        null  | ROUTE_CASE | "complaint" | null     || null
        null  | null       | "summary"   | null     || null
        STEP  | ROUTE_CASE | "channel"   | null     || "bindings_at_most_one_consumer"
        null  | ROUTE_CASE | null        | "route"  || "bindings_no_target_only_for_a_step"
        null  | null       | null        | "route"  || "bindings_no_target_only_for_a_step"
        ROUTE | null       | null        | null     || "bindings_step_kind_exactly_for_no_target"
        STEP  | null       | "channel"   | "route"  || "bindings_step_kind_exactly_for_no_target"
        STEP  | null       | null        | "entry"  || "bindings_step_kind_is_route"
        STEP  | null       | null        | "route"  || "bindings_route_fk"
        ROUTE | null       | null        | "route"  || "bindings_one_discriminator"
    }

    def "a binding and what it fills and reads from are in one workflow version"() {
        when:
        def refusedBy = attempt("bindings", insertInto("bindings", A_BINDING + [entry_version_id: literal(version),
                workflow_step_id: literal(step), route_case_id: literal(routeCase), constant: literal(constant),
                source_step_id: literal(sourceStep), source_path: literal(sourcePath)]))

        then:
        refusedBy == refusal

        where:
        version               | step | routeCase  | sourceStep | sourcePath | constant || refusal
        WORKFLOW_VERSION      | null | null       | STEP       | "summary"  | null     || null
        NEXT_WORKFLOW_VERSION | null | null       | null       | null       | '"x"'    || null
        NEXT_WORKFLOW_VERSION | STEP | null       | null       | null       | '"x"'    || "bindings_step_fk"
        NEXT_WORKFLOW_VERSION | null | ROUTE_CASE | null       | null       | '"x"'    || "bindings_route_case_fk"
        NEXT_WORKFLOW_VERSION | null | null       | STEP       | "summary"  | null     || "bindings_source_step_fk"
    }

    def "a path names fields by name, one after another, and never a place among many"() {
        when:
        def refusedBy = attempt("bindings", setting("bindings", String.format(assignment, literal(path))))

        then:
        refusedBy == (holds ? null : rule)

        where:
        [assignment, rule, path, holds] << [PATHS_IN_BINDINGS, PATHS].combinations().collect { it.flatten() }
    }

    def "a binding reads from one source: a constant, this workflow's input, or another step"() {
        when:
        def refusedBy = attempt("bindings", insertInto("bindings", A_BINDING + [
                source_step_id: literal(sourceStep), source_path: literal(sourcePath), constant: literal(constant)]))

        then:
        refusedBy == refusal

        where:
        sourceStep | sourcePath | constant || refusal
        null       | "channel"  | null     || null
        null       | null       | '"x"'    || null
        ROUTE      | "category" | null     || null
        null       | null       | null     || "bindings_exactly_one_source"
        null       | "channel"  | '"x"'    || "bindings_exactly_one_source"
        ROUTE      | null       | '"x"'    || "bindings_exactly_one_source"
        ROUTE      | null       | null     || "bindings_exactly_one_source"
        STEP       | "summary"  | null     || "bindings_not_from_itself"
    }

    def "a constant is at most 1048576 characters as written out, whatever shape holds it"() {
        when:
        def refusedBy = attempt("bindings", setting("bindings", "source_path = null, constant = ${constant}"))

        then:
        refusedBy == refusal

        where:
        constant                                                           || refusal
        "to_jsonb(repeat('a', 1048574))"                                   || null
        "(select jsonb_agg(chr(1)) from generate_series(1, 8192))"         || null
        "to_jsonb(repeat('a', 1048575))"                                   || "bindings_constant_bounded"
        "jsonb_build_object(repeat('k', 1100000), 1)"                      || "bindings_constant_bounded"
        "(select jsonb_agg(12345) from generate_series(1, 200000))"        || "bindings_constant_bounded"
    }

    def "a group key and the key a code step is published to are two to sixteen capital English letters"() {
        when:
        def refusedBy = attempt(table, String.format(statement, literal(key)))

        then:
        refusedBy == (holds ? null : rule)

        where:
        [table, statement, rule, key, holds] << [GROUP_KEYED, GROUP_KEYS].combinations().collect { it.flatten() }
    }

    def "text is held to its length"() {
        when:
        def refusedBy = attempt(table, setting(table, assignment))

        then:
        refusedBy == refusal

        where:
        table                     | assignment                        || refusal
        "reference_list_versions" | "note = repeat('a', 2048)"        || null
        "reference_list_versions" | "note = null"                     || null
        "reference_list_versions" | "note = ''"                       || "reference_list_versions_note_visible"
        "question_versions"       | "instruction = repeat('a', 8192)" || null
        "question_versions"       | "instruction = null"              || null
        "question_versions"       | "instruction = ''"                || "question_versions_instruction_visible"
        "reference_list_terms"    | "term = repeat('a', 128)"         || null
        "reference_list_terms"    | "term = ''"                       || "reference_list_terms_term_visible"
        "reference_list_terms"    | "meaning = repeat('a', 512)"      || null
        "reference_list_terms"    | "meaning = ''"                    || "reference_list_terms_meaning_visible"
        "route_cases"             | "term = repeat('a', 128)"         || null
        "entries"                 | "purpose = repeat('a', 512)"      || null
        "entries"                 | "purpose = 'a'"                   || null
        "entries"                 | "purpose = ''"                    || "entries_purpose_visible"
        "declaration_fields"      | "label = repeat('a', 128)"        || null
        "declaration_fields"      | "label = ''"                      || "declaration_fields_label_visible"
        "declaration_fields"      | "help = repeat('a', 512)"         || null
        "declaration_fields"      | "help = ''"                       || "declaration_fields_help_visible"
        "runs"                    | "name = repeat('a', 128)"         || null
        "runs"                    | "name = ''"                       || "runs_name_visible"
        "run_step_failures"       | "detail = repeat('a', 2048)"      || null
        "run_step_failures"       | "detail = ''"                     || "run_step_failures_detail_visible"
        "model_call_turnaways"    | "said = repeat('a', 2048)"        || null
        "model_call_turnaways"    | "said = null"                     || null
        "model_call_turnaways"    | "said = ''"                       || "model_call_turnaways_said_visible"
        "run_help_exchanges"      | "question = repeat('a', 2048)"    || null
        "run_help_exchanges"      | "question = ''"                   || "run_help_exchanges_question_visible"
        "run_help_exchanges"      | "answer = repeat('a', 8388608)"   || null
        "run_help_exchanges"      | "answer = null"                   || null
        "productions"             | "explanation = repeat('a', 2048)" || null
        "review_decisions"        | "explanation = repeat('a', 2048)" || null
        "review_decisions"        | "explanation = ''"                || "review_decisions_explanation_visible"
    }

    def "only prose holds a tab or a line feed, and nothing holds another control character"() {
        when:
        def refusedBy = attempt(table, setting(table, assignment))

        then:
        refusedBy == refusal

        where:
        table                     | assignment                                      || refusal
        "reference_list_versions" | "note = 'a' || chr(9) || chr(10) || 'b'"        || null
        "reference_list_versions" | "note = 'a' || chr(133) || 'b'"                 || "reference_list_versions_note_visible"
        "question_versions"       | "instruction = 'a' || chr(10) || chr(9) || 'b'" || null
        "question_versions"       | "instruction = 'a' || chr(8) || 'b'"            || "question_versions_instruction_visible"
        "question_versions"       | "instruction = 'a' || chr(11) || 'b'"           || "question_versions_instruction_visible"
        "reference_list_terms"    | "meaning = 'a' || chr(9) || 'b'"                || "reference_list_terms_meaning_visible"
        "route_cases"             | "term = 'Bill' || chr(9) || 'ing'"              || "route_cases_term_visible"
        "run_step_failures"       | "detail = 'a' || chr(9) || chr(10) || 'b'"      || null
        "run_step_failures"       | "detail = 'a' || chr(13) || 'b'"                || "run_step_failures_detail_visible"
        "entries"                 | "purpose = 'a' || chr(9) || 'b'"                || "entries_purpose_visible"
        "declaration_fields"      | "label = 'a' || chr(10) || 'b'"                 || "declaration_fields_label_visible"
        "declaration_fields"      | "help = 'a' || chr(10) || 'b'"                  || "declaration_fields_help_visible"
        "runs"                    | "name = 'a' || chr(9) || 'b'"                   || "runs_name_visible"
        "model_call_turnaways"    | "said = 'a' || chr(9) || chr(10) || 'b'"        || null
        "model_call_turnaways"    | "said = 'a' || chr(13) || 'b'"                  || "model_call_turnaways_said_visible"
        "run_help_exchanges"      | "question = 'a' || chr(10) || 'b'"              || null
        "run_help_exchanges"      | "answer = 'a' || chr(10) || chr(9) || 'b'"      || null
        "run_help_exchanges"      | "answer = 'a' || chr(133) || 'b'"              || "run_help_exchanges_answer_visible"
        "productions"             | "explanation = 'a' || chr(9) || chr(10) || 'b'" || null
        "productions"             | "explanation = 'a' || chr(8) || 'b'"            || "productions_explanation_visible"
        "review_decisions"        | "explanation = 'a' || chr(10) || 'b'"           || null
        "review_decisions"        | "explanation = 'a' || chr(127) || 'b'"          || "review_decisions_explanation_visible"
    }

    def "an ordered row may stand at position zero, and never before it"() {
        when:
        def refusedBy = attempt(table, "update app.${table} set position = ${position} where ${ROW_OF[table]}")

        then:
        refusedBy == (position < 0 ? "${table}_position_not_negative".toString() : null)

        where:
        [table, position] << [["reference_list_terms", "workflow_steps", "declaration_fields"], [0, -1]].combinations()
    }

    /**
     * Two rows trading places pass through a moment where both hold one position, so what is proved is
     * that the collision between the statements is let through and the order at the end is still held.
     */
    def "rows trade positions one statement at a time, and the positions are held unique when it ends"() {
        when:
        def refusedBy = attempt(table, "update app.${table} set position = 2 where ${key} = '${first}';" +
                " update app.${table} set position = 1 where ${key} = '${second}'")

        then:
        refusedBy == null

        where:
        table                  | key                      | first | second
        "reference_list_terms" | "reference_list_term_id" | TERM  | NEXT_TERM
        "workflow_steps"       | "workflow_step_id"       | STEP  | ROUTE
        "declaration_fields"   | "declaration_field_id"   | FIELD | SIBLING
    }

    def "a run at the top is named, started by a person and its own root, and a run beneath is none of them"() {
        when:
        def refusedBy = attempt("runs", insertInto("runs", (beneath ? A_SUB_RUN : A_RUN) + [name: literal(name),
                root_run_id: literal(root), created_by: literal(author), created_by_kind: literal(kind)]))

        then:
        refusedBy == refusal

        where:
        beneath | name     | root      | author          | kind     || refusal
        false   | "Refund" | SPARE_RUN | STEWARD         | "person" || null
        true    | null     | RUN       | WORKFLOW_RUNNER | "system" || null
        false   | null     | SPARE_RUN | STEWARD         | "person" || "runs_name_exactly_for_top_level"
        true    | "Refund" | RUN       | WORKFLOW_RUNNER | "system" || "runs_name_exactly_for_top_level"
        false   | "Refund" | RUN       | STEWARD         | "person" || "runs_own_root_exactly_for_top_level"
        true    | null     | SPARE_RUN | WORKFLOW_RUNNER | "system" || "runs_own_root_exactly_for_top_level"
        false   | "Refund" | SPARE_RUN | WORKFLOW_RUNNER | "system" || "runs_author_is_person_exactly_for_top_level"
        true    | null     | RUN       | STEWARD         | "person" || "runs_author_is_person_exactly_for_top_level"
    }

    def "a run at the top runs a workflow version of an entry its own group owns"() {
        when:
        def refusedBy = attempt("runs", insertInto("runs", A_RUN + [entry_id: literal(entry),
                entry_version_id: literal(version)]))

        then:
        refusedBy == refusal

        where:
        entry                | version                || refusal
        WORKFLOW_ENTRY       | WORKFLOW_VERSION       || null
        WORKFLOW_ENTRY       | NEXT_WORKFLOW_VERSION  || null
        OTHER_WORKFLOW_ENTRY | OTHER_WORKFLOW_VERSION || "runs_top_level_entry_fk"
        WORKFLOW_ENTRY       | OTHER_WORKFLOW_VERSION || "runs_version_entry_fk"
        ENTRY                | VERSION                || "runs_version_fk"
    }

    /** Whether a version retired may still be started is the application's: retiring is no key. */
    def "a run runs only a version that has been approved"() {
        when:
        def refusedBy = attempt("runs", first + insertInto("runs", A_RUN + [group_id: literal(OTHER_GROUP),
                entry_id: literal(OTHER_WORKFLOW_ENTRY), entry_version_id: literal(version)]))

        then:
        refusedBy == refusal

        where:
        first                   | version                || refusal
        ""                      | OTHER_WORKFLOW_VERSION || null
        ""                      | OTHER_WORKFLOW_DRAFT   || "runs_version_approved_fk"
        APPROVE_THE_OTHER_DRAFT | OTHER_WORKFLOW_DRAFT   || null
    }

    /**
     * A route's run beneath runs the version the case it took leads to, fallback or not, and that may be a
     * workflow another group owns; a version no case of that route leads to is refused, and a draft is refused
     * before any case is asked.
     */
    def "a run beneath a route runs the version the case it took leads to, and no other"() {
        when:
        def refusedBy = attempt("runs", "update app.runs set parent_route_case_id = ${literal(routeCase)}," +
                " entry_id = '${entry}', entry_version_id = '${version}' where run_id = '${SUB_RUN}'")

        then:
        refusedBy == refusal

        where:
        routeCase  | entry                | version                || refusal
        FALLBACK   | WORKFLOW_ENTRY       | NEXT_WORKFLOW_VERSION  || null
        OTHER_CASE | OTHER_WORKFLOW_ENTRY | OTHER_WORKFLOW_VERSION || null
        ROUTE_CASE | OTHER_WORKFLOW_ENTRY | OTHER_WORKFLOW_DRAFT   || "runs_version_approved_fk"
        ROUTE_CASE | OTHER_WORKFLOW_ENTRY | OTHER_WORKFLOW_VERSION || "runs_parent_route_case_fk"
        OTHER_CASE | WORKFLOW_ENTRY       | NEXT_WORKFLOW_VERSION  || "runs_parent_route_case_fk"
        null       | WORKFLOW_ENTRY       | NEXT_WORKFLOW_VERSION  || "runs_parent_route_case_exactly_for_route"
    }

    def "a run beneath is in its parent's tree and its root's group"() {
        when:
        def refusedBy = attempt("runs", insertInto("runs", A_SUB_RUN + [group_id: literal(group), root_run_id: literal(root)]))

        then:
        refusedBy == refusal

        where:
        group       | root      || refusal
        GROUP       | RUN       || null
        OTHER_GROUP | RUN       || "runs_root_fk"
        GROUP       | OTHER_RUN || "runs_parent_fk"
    }

    def "a run beneath is one deeper than its parent, so that no two runs are each the other's parent"() {
        when:
        def refusedBy = attempt("runs", statement)

        then:
        refusedBy == refusal

        where:
        statement                                                          || refusal
        insertInto("runs", A_SUB_RUN)                                      || null
        insertInto("runs", A_SUB_RUN + [depth: "2", parent_depth: "1"])    || "runs_parent_fk"
        insertInto("runs", A_SUB_RUN + [depth: "0", parent_depth: "-1"])   || "runs_depth_zero_exactly_for_top_level"
        TWO_RUNS_EACH_THE_OTHERS_PARENT                                    || "runs_parent_fk"
    }

    def "a run beneath is started by a step of its parent, named whole"() {
        when:
        def refusedBy = attempt("runs", insertInto("runs", A_SUB_RUN + [parent_run_id: literal(parent),
                parent_workflow_step_id: literal(workflowStep), depth: depth, parent_depth: parentDepth]))

        then:
        refusedBy == refusal

        where:
        parent  | workflowStep | depth | parentDepth || refusal
        RUN     | ESCALATE     | "1"   | "0"         || null
        SUB_RUN | ESCALATE     | "2"   | "1"         || "runs_parent_step_fk"
        RUN     | STEP         | "1"   | "0"         || "runs_parent_step_fk"
        RUN     | null         | "1"   | "0"         || "runs_parent_together"
    }

    def "a run beneath is started by a step that starts runs, and that step starts no other"() {
        when:
        def refusedBy = attempt("runs", insertInto("runs", A_SUB_RUN + [parent_run_step_id: literal(step),
                parent_run_step_kind: literal(kind), parent_workflow_step_id: literal(workflowStep),
                parent_route_case_id: literal(routeCase)]))

        then:
        refusedBy == refusal

        where:
        step              | kind       | workflowStep | routeCase  || refusal
        ESCALATE_RUN_STEP | "workflow" | ESCALATE     | null       || null
        RUN_STEP          | "question" | STEP         | null       || "runs_parent_step_is_workflow_or_route"
        ROUTE_RUN_STEP    | "route"    | ROUTE        | ROUTE_CASE || "runs_parent_step_unique"
    }

    def "a run beneath a step running a workflow runs the version it pins, and names a case only beneath a route"() {
        when:
        def refusedBy = attempt("runs", insertInto("runs", A_SUB_RUN + [parent_run_step_kind: literal(kind),
                parent_route_case_id: literal(routeCase), entry_version_id: literal(version)]))

        then:
        refusedBy == refusal

        where:
        kind       | routeCase  | version               || refusal
        "workflow" | null       | NEXT_WORKFLOW_VERSION || null
        "workflow" | null       | WORKFLOW_VERSION      || "runs_parent_pinned_version_fk"
        "workflow" | ROUTE_CASE | NEXT_WORKFLOW_VERSION || "runs_parent_route_case_exactly_for_route"
        "route"    | null       | NEXT_WORKFLOW_VERSION || "runs_parent_route_case_exactly_for_route"
    }

    def "only a run at the top is renamed"() {
        when:
        def refusedBy = attempt("runs", "update app.runs set name = ${literal(name)}, updated_at = now()," +
                " updated_by = '${STEWARD}' where run_id = '${run}'")

        then:
        refusedBy == refusal

        where:
        run     | name     || refusal
        RUN     | "Refund" || null
        SUB_RUN | null     || "runs_updated_only_for_top_level"
    }

    /** A run beneath holds no input of its own: what it takes is bound from the step that started it. */
    def "only a run at the top is started with something, an object at most 50331648 characters as the store writes it"() {
        when:
        def refusedBy = attempt("runs", "update app.runs set started_with = ${startedWith} where run_id = '${run}'")

        then:
        refusedBy == refusal

        where:
        run     | startedWith                                                          || refusal
        RUN     | "'{}'"                                                               || null
        RUN     | "jsonb_build_object('complaint', repeat(chr(1), 8388605) || 'a')"  || null
        RUN     | "jsonb_build_object('complaint', repeat(chr(1), 8388605) || 'aa')" || "runs_started_with_bounded"
        RUN     | "'[]'"                                                               || "runs_started_with_is_an_object"
        RUN     | "'\"It never came.\"'"                                               || "runs_started_with_is_an_object"
        RUN     | "null"                                                               || "runs_started_with_exactly_for_top_level"
        SUB_RUN | "'{}'"                                                               || "runs_started_with_exactly_for_top_level"
    }

    def "a step is run as a step of the version its run ran"() {
        when:
        def refusedBy = attempt("run_steps", insertInto("run_steps", A_RUN_STEP + [run_id: literal(run),
                entry_version_id: literal(version), workflow_step_id: literal(step), step_kind: literal(kind),
                pinned_version_id: literal(pinned), pinned_kind: literal(pinnedKind), producer: literal(producer),
                tries: literal(tries)]))

        then:
        refusedBy == refusal

        where:
        run       | version               | step     | kind        | pinned                | pinnedKind | producer | tries || refusal
        SUB_RUN   | NEXT_WORKFLOW_VERSION | SUB_STEP | "code_step" | null                  | null       | "code"   | 3     || null
        OTHER_RUN | WORKFLOW_VERSION      | STEP     | "entry"     | VERSION               | "question" | "model"  | 3     || null
        OTHER_RUN | WORKFLOW_VERSION      | ESCALATE | "entry"     | NEXT_WORKFLOW_VERSION | "workflow" | null     | null  || null
        OTHER_RUN | WORKFLOW_VERSION      | ROUTE    | "route"     | null                  | null       | null     | null  || null
        SUB_RUN   | NEXT_WORKFLOW_VERSION | STEP     | "entry"     | VERSION               | "question" | "model"  | 3     || "run_steps_workflow_step_fk"
        SUB_RUN   | WORKFLOW_VERSION      | STEP     | "entry"     | VERSION               | "question" | "model"  | 3     || "run_steps_run_fk"
    }

    def "a step is run once in each run"() {
        when:
        def refusedBy = attempt("run_steps", insertInto("run_steps", A_RUN_STEP + [run_id: literal(run),
                entry_version_id: literal(WORKFLOW_VERSION), workflow_step_id: literal(STEP), step_kind: "'entry'",
                pinned_version_id: literal(VERSION), pinned_kind: "'question'", producer: "'model'"]))

        then:
        refusedBy == refusal

        where:
        run       || refusal
        OTHER_RUN || null
        RUN       || "run_steps_one_per_workflow_step"
    }

    def "a step run is taken as what its step runs, pinning what that step pins, and only where it runs an entry"() {
        when:
        def refusedBy = attempt("run_steps", insertInto("run_steps", A_RUN_STEP + [run_id: literal(OTHER_RUN),
                entry_version_id: literal(WORKFLOW_VERSION), workflow_step_id: literal(step), step_kind: literal(kind),
                pinned_version_id: literal(pinned), pinned_kind: literal(pinnedKind), producer: literal(producer),
                tries: literal(tries)]))

        then:
        refusedBy == refusal

        where:
        step  | kind        | pinned                | pinnedKind | producer | tries || refusal
        STEP  | "entry"     | VERSION               | "question" | "model"  | 3     || null
        STEP  | "code_step" | null                  | null       | "model"  | 3     || "run_steps_step_kind_fk"
        STEP  | "entry"     | NEXT_WORKFLOW_VERSION | "workflow" | null     | null  || "run_steps_pinned_fk"
        STEP  | "entry"     | VERSION               | null       | null     | null  || "run_steps_pinned_together"
    }

    def "a step run pins a version exactly where its step runs an entry"() {
        when:
        def refusedBy = attempt("run_steps", insertInto("run_steps", A_RUN_STEP + [run_id: literal(OTHER_RUN),
                entry_version_id: literal(WORKFLOW_VERSION), workflow_step_id: literal(step), step_kind: literal(kind),
                pinned_version_id: literal(pinned), pinned_kind: literal(pinnedKind), producer: "null", tries: "null"]))

        then:
        refusedBy == refusal

        where:
        step  | kind    | pinned  | pinnedKind || refusal
        ROUTE | "route" | null    | null       || null
        STEP  | "entry" | null    | null       || "run_steps_pinned_exactly_for_entry"
        ROUTE | "route" | VERSION | "question" || "run_steps_pinned_exactly_for_entry"
    }

    def "a step run carries who produces for it, as the step says"() {
        when:
        def refusedBy = attempt("run_steps", insertInto("run_steps", A_RUN_STEP + [run_id: "'${OTHER_RUN}'",
                entry_version_id: "'${WORKFLOW_VERSION}'", workflow_step_id: literal(step), step_kind: literal(kind),
                pinned_version_id: literal(pinned), pinned_kind: literal(pinnedKind), producer: literal(producer),
                reviewed_by_model: reviewed, tries: literal(tries)]))

        then:
        refusedBy == refusal

        where:
        step  | kind    | pinned  | pinnedKind | producer | reviewed | tries || refusal
        STEP  | "entry" | VERSION | "question" | "model"  | "false"  | 3     || null
        ROUTE | "route" | null    | null       | null     | "false"  | null  || null
        STEP  | "entry" | VERSION | "question" | "person" | "false"  | 3     || "run_steps_producer_fk"
        STEP  | "entry" | VERSION | "question" | "model"  | "true"   | 3     || "run_steps_producer_fk"
        STEP  | "entry" | VERSION | "question" | null     | "false"  | null  || "run_steps_producer_exactly_for_question_or_code_step"
        ROUTE | "route" | null    | null       | "model"  | "false"  | 3     || "run_steps_producer_exactly_for_question_or_code_step"
    }

    def "a step run carries how many tries its step declares, as the step says"() {
        when:
        def refusedBy = attempt("run_steps", insertInto("run_steps", A_RUN_STEP + [run_id: literal(run),
                entry_version_id: literal(version), workflow_step_id: literal(step), step_kind: literal(kind),
                pinned_version_id: literal(pinned), pinned_kind: literal(pinnedKind), producer: literal(producer),
                tries: literal(tries)]))

        then:
        refusedBy == refusal

        where:
        run       | version               | step     | kind        | pinned  | pinnedKind | producer | tries || refusal
        OTHER_RUN | WORKFLOW_VERSION      | STEP     | "entry"     | VERSION | "question" | "model"  | 3     || null
        SUB_RUN   | NEXT_WORKFLOW_VERSION | SUB_STEP | "code_step" | null    | null       | "code"   | 3     || null
        OTHER_RUN | WORKFLOW_VERSION      | STEP     | "entry"     | VERSION | "question" | "model"  | 2     || "run_steps_producer_fk"
        SUB_RUN   | NEXT_WORKFLOW_VERSION | SUB_STEP | "code_step" | null    | null       | "code"   | 4     || "run_steps_producer_fk"
    }

    /** Held to the step only while somebody produces for it, since a key with a null column checks nothing. */
    def "a step run carries its tries exactly where somebody produces for it"() {
        when:
        def refusedBy = attempt("run_steps", insertInto("run_steps", A_RUN_STEP + [run_id: literal(OTHER_RUN),
                entry_version_id: literal(WORKFLOW_VERSION), workflow_step_id: literal(step), step_kind: literal(kind),
                pinned_version_id: literal(pinned), pinned_kind: literal(pinnedKind), producer: literal(producer),
                tries: literal(tries)]))

        then:
        refusedBy == refusal

        where:
        step  | kind    | pinned  | pinnedKind | producer | tries || refusal
        STEP  | "entry" | VERSION | "question" | "model"  | 3     || null
        ROUTE | "route" | null    | null       | null     | null  || null
        STEP  | "entry" | VERSION | "question" | "model"  | null  || "run_steps_producer_together"
        ROUTE | "route" | null    | null       | null     | 3     || "run_steps_producer_together"
    }

    def "a step run is reviewed by a model only where somebody produces for it"() {
        when:
        def refusedBy = attempt("run_steps", insertInto("run_steps", A_RUN_STEP + [run_id: "'${OTHER_RUN}'",
                entry_version_id: "'${WORKFLOW_VERSION}'", workflow_step_id: literal(ROUTE), step_kind: "'route'",
                producer: "null", tries: "null", reviewed_by_model: reviewed]))

        then:
        refusedBy == refusal

        where:
        reviewed || refusal
        "false"  || null
        "true"   || "run_steps_reviewed_by_model_only_for_a_producer"
    }

    /**
     * What a failure says of its step, what the step runs and whether it calls a model, is what the step is: the key
     * into the step admits no other claim, so a rule written against those columns is written against the step.
     */
    def "a failure claims its step calls a model only where the step does"() {
        when:
        def refusedBy = attempt("run_step_failures", insertInto("run_step_failures", A_FAILURE + [
                run_step_id: literal(step), run_step_kind: literal(kind), calls_a_model: calls, reason: literal(reason),
                production_id: literal(production), purpose: literal(purpose)]))

        then:
        refusedBy == refusal

        where:
        step           | kind       | calls   | reason               | production | purpose   || refusal
        RUN_STEP       | "question" | "true"  | "model_not_deployed" | PRODUCTION | "produce" || null
        ROUTE_RUN_STEP | "route"    | "false" | "unclaimed_value"    | null       | null      || null
        ROUTE_RUN_STEP | "route"    | "true"  | "unclaimed_value"    | null       | null      || "run_step_failures_step_fk"
    }

    /** The entry stopped may be the run's own workflow, which holds its next step whatever that step runs. */
    def "a step is held back on an entry stopped whatever it runs, while no other hold on it is unreleased"() {
        when:
        def refusedBy = attempt("run_step_holds", first + insertInto("run_step_holds", A_HOLD + [
                run_step_id: literal(step), run_id: literal(run), run_step_kind: literal(kind), calls_a_model: calls]))

        then:
        refusedBy == refusal

        where:
        first            | step                   | run      | kind        | calls   || refusal
        ""               | ESCALATE_RUN_STEP      | RUN      | "workflow"  | "false" || null
        RELEASE_THE_HOLD | RUN_STEP               | RUN      | "question"  | "true"  || null
        ""               | REVIEWED_CODE_RUN_STEP | CODE_RUN | "code_step" | "true"  || null
        ""               | ROUTE_RUN_STEP         | RUN      | "route"     | "false" || null
        ""               | RUN_STEP               | RUN      | "question"  | "true"  || "run_step_holds_one_unreleased"
    }

    def "a step is held back on length or on being turned away only where it calls a model"() {
        when:
        def refusedBy = attempt("run_step_holds", first + insertInto("run_step_holds", A_HOLD + [
                run_step_id: literal(step), run_id: literal(run), run_step_kind: literal(kind), calls_a_model: calls,
                reason: literal(reason), run_step_send_attempt_id: literal(sending)]))

        then:
        refusedBy == refusal

        where:
        first             | step              | run     | kind        | calls   | reason        | sending      || refusal
        RELEASE_THE_HOLD  | RUN_STEP          | RUN     | "question"  | "true"  | "too_long"    | LONG_ATTEMPT || null
        RELEASE_THE_HOLD  | RUN_STEP          | RUN     | "question"  | "true"  | "turned_away" | SEND_ATTEMPT || null
        RUN_THE_CODE_STEP | SPARE_RUN_STEP    | SUB_RUN | "code_step" | "false" | "too_long"    | LONG_ATTEMPT || "run_step_holds_too_long_or_turned_away_only_for_calls_a_model"
        RUN_THE_CODE_STEP | SPARE_RUN_STEP    | SUB_RUN | "code_step" | "false" | "turned_away" | SEND_ATTEMPT || "run_step_holds_too_long_or_turned_away_only_for_calls_a_model"
        ""                | ESCALATE_RUN_STEP | RUN     | "workflow"  | "false" | "turned_away" | SEND_ATTEMPT || "run_step_holds_too_long_or_turned_away_only_for_calls_a_model"
        ""                | ROUTE_RUN_STEP    | RUN     | "route"     | "false" | "too_long"    | LONG_ATTEMPT || "run_step_holds_too_long_or_turned_away_only_for_calls_a_model"
    }

    def "a step is held back on a code step the release does not hold only where it runs a code step"() {
        when:
        def refusedBy = attempt("run_step_holds", first + insertInto("run_step_holds", A_HOLD + [
                run_step_id: literal(step), run_id: literal(run), run_step_kind: literal(kind), calls_a_model: calls,
                reason: "'code_step_not_held'"]))

        then:
        refusedBy == refusal

        where:
        first             | step              | run     | kind        | calls   || refusal
        RUN_THE_CODE_STEP | SPARE_RUN_STEP    | SUB_RUN | "code_step" | "false" || null
        RELEASE_THE_HOLD  | RUN_STEP          | RUN     | "question"  | "true"  || "run_step_holds_code_step_not_held_only_for_code_step"
        ""                | ESCALATE_RUN_STEP | RUN     | "workflow"  | "false" || "run_step_holds_code_step_not_held_only_for_code_step"
        ""                | ROUTE_RUN_STEP    | RUN     | "route"     | "false" || "run_step_holds_code_step_not_held_only_for_code_step"
    }

    def "a step is held back on length or on being turned away naming the attempt that was, and on nothing else naming one"() {
        when:
        def refusedBy = attempt("run_step_holds", RELEASE_THE_HOLD + insertInto("run_step_holds", A_HOLD + [
                run_step_id: literal(RUN_STEP), run_step_kind: "'question'", calls_a_model: "true", reason: literal(reason),
                run_step_send_attempt_id: literal(sending)]))

        then:
        refusedBy == refusal

        where:
        reason          | sending      || refusal
        "entry_stopped" | null         || null
        "too_long"      | LONG_ATTEMPT || null
        "entry_stopped" | SEND_ATTEMPT || "run_step_holds_attempt_exactly_for_too_long_or_turned_away"
        "too_long"      | null         || "run_step_holds_attempt_exactly_for_too_long_or_turned_away"
        "turned_away"   | null         || "run_step_holds_attempt_exactly_for_too_long_or_turned_away"
    }

    /**
     * An attempt to review that cannot be sent leaves its production waiting on a person, and never holds the step
     * back; one too long was never sent, and so was never turned away.
     */
    def "a step is held back only for an attempt of its own to produce, too long on length and sent where turned away"() {
        when:
        def refusedBy = attempt("run_step_holds", first + insertInto("run_step_holds", A_HOLD + [
                run_step_id: literal(step), run_step_kind: "'question'", calls_a_model: "true", reason: literal(reason),
                run_step_send_attempt_id: literal(sending)]))

        then:
        refusedBy == refusal

        where:
        first            | step              | reason        | sending             || refusal
        RELEASE_THE_HOLD | RUN_STEP          | "too_long"    | LONG_ATTEMPT        || null
        RELEASE_THE_HOLD | RUN_STEP          | "turned_away" | SEND_ATTEMPT        || null
        RELEASE_THE_HOLD | RUN_STEP          | "too_long"    | SEND_ATTEMPT        || "run_step_holds_attempt_fk"
        RELEASE_THE_HOLD | RUN_STEP          | "turned_away" | LONG_ATTEMPT        || "run_step_holds_attempt_fk"
        ""               | REVIEWED_RUN_STEP | "too_long"    | HELD_REVIEW_ATTEMPT || "run_step_holds_attempt_fk"
        ""               | REVIEWED_RUN_STEP | "turned_away" | REVIEW_ATTEMPT      || "run_step_holds_attempt_fk"
        ""               | REVIEWED_RUN_STEP | "too_long"    | LONG_ATTEMPT        || "run_step_holds_attempt_fk"
    }

    def "a step fails for its length or its model only where it calls a model"() {
        when:
        def refusedBy = attempt("run_step_failures", first + insertInto("run_step_failures", A_FAILURE + [
                run_step_id: literal(step), run_id: literal(run), run_step_kind: literal(kind), calls_a_model: calls,
                reason: literal(reason), production_id: literal(production), purpose: literal(purpose)]))

        then:
        refusedBy == refusal

        where:
        first             | step                   | run      | kind        | calls   | reason               | production       | purpose   || refusal
        ""                | RUN_STEP               | RUN      | "question"  | "true"  | "uncuttable_length"  | PRODUCTION       | "produce" || null
        ""                | RUN_STEP               | RUN      | "question"  | "true"  | "model_not_deployed" | PRODUCTION       | "produce" || null
        ""                | REVIEWED_CODE_RUN_STEP | CODE_RUN | "code_step" | "true"  | "model_not_deployed" | CLOSED           | "review"  || null
        TRY_THE_CODE_STEP | SPARE_RUN_STEP         | SUB_RUN  | "code_step" | "false" | "model_not_deployed" | SPARE_PRODUCTION | "review"  || "run_step_failures_length_or_undeployed_only_for_calls_a_model"
        ""                | ROUTE_RUN_STEP         | RUN      | "route"     | "false" | "model_not_deployed" | PRODUCTION       | "produce" || "run_step_failures_length_or_undeployed_only_for_calls_a_model"
        ""                | ESCALATE_RUN_STEP      | RUN      | "workflow"  | "false" | "uncuttable_length"  | PRODUCTION       | "produce" || "run_step_failures_length_or_undeployed_only_for_calls_a_model"
    }

    /**
     * Try sending sends the try the step was on, for what it was being sent for: to produce, a model's; to review, one
     * a model reviews that gave something back. A value no case claimed is mended by no sending.
     */
    def "a step fails for its length or its model on a try it could send for that, and on none for a value no case claimed"() {
        when:
        def refusedBy = attempt("run_step_failures", insertInto("run_step_failures", A_FAILURE + [
                run_step_id: literal(step), run_id: literal(run), run_step_kind: literal(kind), calls_a_model: calls,
                reason: literal(reason), production_id: literal(production), purpose: literal(purpose)]))

        then:
        refusedBy == refusal

        where:
        step                   | run      | kind        | calls   | reason               | production      | purpose   || refusal
        RUN_STEP               | RUN      | "question"  | "true"  | "model_not_deployed" | PRODUCTION      | "produce" || null
        RUN_STEP               | RUN      | "question"  | "true"  | "uncuttable_length"  | PRODUCTION      | "produce" || null
        REVIEWED_RUN_STEP      | RUN      | "question"  | "true"  | "model_not_deployed" | REVIEWED_ANSWER | "review"  || null
        ROUTE_RUN_STEP         | RUN      | "route"     | "false" | "unclaimed_value"    | null            | null      || null
        RUN_STEP               | RUN      | "question"  | "true"  | "model_not_deployed" | null            | null      || "run_step_failures_production_exactly_for_length_or_undeployed"
        RUN_STEP               | RUN      | "question"  | "true"  | "uncuttable_length"  | null            | null      || "run_step_failures_production_exactly_for_length_or_undeployed"
        ROUTE_RUN_STEP         | RUN      | "route"     | "false" | "unclaimed_value"    | PRODUCTION      | "produce" || "run_step_failures_production_exactly_for_length_or_undeployed"
        RUN_STEP               | RUN      | "question"  | "true"  | "model_not_deployed" | PRODUCTION      | null      || "run_step_failures_purpose_together"
        RUN_STEP               | RUN      | "question"  | "true"  | "model_not_deployed" | PRODUCTION      | "help"    || "run_step_failures_purpose_is_produce_or_review"
        REVIEWED_RUN_STEP      | RUN      | "question"  | "true"  | "uncuttable_length"  | REVIEWED_ANSWER | "review"  || "run_step_failures_uncuttable_length_only_for_produce"
        RUN_STEP               | RUN      | "question"  | "true"  | "model_not_deployed" | REVIEWED_ANSWER | "produce" || "run_step_failures_produced_fk"
        RUN_STEP               | RUN      | "question"  | "true"  | "model_not_deployed" | ANSWERED        | "produce" || "run_step_failures_produced_fk"
        REVIEWED_CODE_RUN_STEP | CODE_RUN | "code_step" | "true"  | "model_not_deployed" | CLOSED          | "produce" || "run_step_failures_produced_fk"
        RUN_STEP               | RUN      | "question"  | "true"  | "model_not_deployed" | PRODUCTION      | "review"  || "run_step_failures_reviewed_fk"
        REVIEWED_RUN_STEP      | RUN      | "question"  | "true"  | "model_not_deployed" | PENDING         | "review"  || "run_step_failures_reviewed_fk"
    }

    def "a step fails because no case claimed a value only where it is a route"() {
        when:
        def refusedBy = attempt("run_step_failures", insertInto("run_step_failures", A_FAILURE + [
                run_step_id: literal(step), run_id: literal(RUN), run_step_kind: literal(kind), calls_a_model: calls,
                reason: "'unclaimed_value'", production_id: "null", purpose: "null"]))

        then:
        refusedBy == refusal

        where:
        step              | kind       | calls   || refusal
        ROUTE_RUN_STEP    | "route"    | "false" || null
        RUN_STEP          | "question" | "true"  || "run_step_failures_unclaimed_value_only_for_route"
        ESCALATE_RUN_STEP | "workflow" | "false" || "run_step_failures_unclaimed_value_only_for_route"
    }

    def "only a question or a code step is tried sending, and a code step only for a model to review"() {
        when:
        def refusedBy = attempt("run_step_send_attempts", insertInto("run_step_send_attempts", A_SEND_ATTEMPT + [
                run_step_id: literal(step), run_id: literal(run), run_step_kind: literal(kind),
                workflow_step_id: literal(workflowStep), purpose: literal(purpose), production_id: literal(production)]))

        then:
        refusedBy == refusal

        where:
        step                   | run      | kind        | workflowStep  | purpose   | production || refusal
        RUN_STEP               | RUN      | "question"  | STEP          | "produce" | PRODUCTION || null
        REVIEWED_CODE_RUN_STEP | CODE_RUN | "code_step" | REVIEWED_CODE | "review"  | CLOSED     || null
        REVIEWED_CODE_RUN_STEP | CODE_RUN | "code_step" | REVIEWED_CODE | "produce" | CLOSED     || "run_step_send_attempts_produce_only_for_question"
        ESCALATE_RUN_STEP      | RUN      | "workflow"  | ESCALATE      | "produce" | PRODUCTION || "run_step_send_attempts_only_for_question_or_code_step"
        ROUTE_RUN_STEP         | RUN      | "route"     | ROUTE         | "produce" | PRODUCTION || "run_step_send_attempts_only_for_question_or_code_step"
    }

    def "a run at the top is stopped by a person, or by a system subject naming the run in its tree whose ceiling was reached"() {
        when:
        def refusedBy = attempt("run_stops", insertInto("run_stops", A_RUN_STOP + [run_id: literal(run),
                root_run_id: literal(root), created_by: literal(author), created_by_kind: literal(kind),
                ceiling_run_id: literal(ceiling)]))

        then:
        refusedBy == refusal

        where:
        run     | root    | author          | kind     | ceiling   || refusal
        RUN     | RUN     | STEWARD         | "person" | null      || null
        RUN     | RUN     | WORKFLOW_RUNNER | "system" | RUN       || null
        RUN     | RUN     | WORKFLOW_RUNNER | "system" | SUB_RUN   || null
        SUB_RUN | SUB_RUN | STEWARD         | "person" | null      || "run_stops_run_fk"
        SUB_RUN | RUN     | STEWARD         | "person" | null      || "run_stops_run_is_root"
        RUN     | RUN     | WORKFLOW_RUNNER | "system" | OTHER_RUN || "run_stops_ceiling_run_fk"
        RUN     | RUN     | STEWARD         | "person" | SUB_RUN   || "run_stops_ceiling_exactly_for_system"
        RUN     | RUN     | WORKFLOW_RUNNER | "system" | null      || "run_stops_ceiling_exactly_for_system"
    }

    def "a run has one stop in force at most, and once opened again may be stopped again"() {
        when:
        def refusedBy = attempt("run_stops", "${first}insert into app.run_stops (run_id, root_run_id, created_by)" +
                " values ('${run}', '${run}', '${STEWARD}')")

        then:
        refusedBy == refusal

        where:
        first              | run       || refusal
        ""                 | RUN       || "run_stops_one_in_force"
        OPEN_THE_RUN_AGAIN | RUN       || null
        ""                 | OTHER_RUN || null
    }

    /** Order comes from the position, which is why two changes to one run never share one. */
    def "a change takes a position of one or more that no other change to its run holds"() {
        when:
        def refusedBy = attempt("run_ceiling_changes", insertInto("run_ceiling_changes", A_CHANGE + [
                run_id: literal(run), position: position]))

        then:
        refusedBy == refusal

        where:
        run       | position || refusal
        RUN       | "2"      || null
        OTHER_RUN | "1"      || null
        RUN       | "1"      || "run_ceiling_changes_position_unique"
        RUN       | "0"      || "run_ceiling_changes_position_positive"
        OTHER_RUN | "-1"     || "run_ceiling_changes_position_positive"
    }

    def "a change awaits approval only where it raises or takes away a ceiling there was"() {
        when:
        def refusedBy = attempt("run_ceiling_changes", insertInto("run_ceiling_changes", A_CHANGE + [
                run_id: literal(OTHER_RUN), from_ceiling: literal(from), to_ceiling: literal(to), awaits_approval: awaits]))

        then:
        refusedBy == refusal

        where:
        from | to   | awaits  || refusal
        1000 | 2000 | "true"  || null
        1000 | null | "true"  || null
        1000 | 500  | "false" || null
        1000 | 2000 | "false" || null
        null | 1000 | "false" || null
        null | 1000 | "true"  || "run_ceiling_changes_awaits_approval_only_for_a_raise"
        1000 | 500  | "true"  || "run_ceiling_changes_awaits_approval_only_for_a_raise"
        1000 | 999  | "true"  || "run_ceiling_changes_awaits_approval_only_for_a_raise"
    }

    def "a change's ceiling differs from the one before it, whether either is none or a number"() {
        when:
        def refusedBy = attempt("run_ceiling_changes", insertInto("run_ceiling_changes", A_CHANGE + [
                from_ceiling: literal(from), to_ceiling: literal(to)]))

        then:
        refusedBy == refusal

        where:
        from | to   || refusal
        null | 1000 || null
        1000 | null || null
        1000 | 1001 || null
        1000 | 1000 || "run_ceiling_changes_from_differs_from_to"
        null | null || "run_ceiling_changes_from_differs_from_to"
    }

    def "a change is decided only where it awaits"() {
        when:
        def refusedBy = attempt("run_ceiling_changes", insertInto("run_ceiling_changes", A_CHANGE + [
                run_id: literal(OTHER_RUN), to_ceiling: "2000", awaits_approval: awaits, outcome: literal(outcome),
                decided_at: decidedAt, decided_by: literal(decider)]))

        then:
        refusedBy == refusal

        where:
        awaits  | outcome     | decidedAt | decider || refusal
        "true"  | null        | "null"    | null    || null
        "true"  | "withdrawn" | "now()"   | STEWARD || null
        "false" | null        | "null"    | null    || null
        "false" | "withdrawn" | "now()"   | STEWARD || "run_ceiling_changes_outcome_only_for_awaiting"
    }

    def "a change is decided with its outcome, when and by whom, all three or none of them"() {
        when:
        def refusedBy = attempt("run_ceiling_changes", insertInto("run_ceiling_changes", A_CHANGE + [
                run_id: literal(OTHER_RUN), to_ceiling: "2000", awaits_approval: "true", outcome: literal(outcome),
                decided_at: decidedAt, decided_by: literal(decider)]))

        then:
        refusedBy == refusal

        where:
        outcome    | decidedAt | decider || refusal
        null       | "null"    | null    || null
        "approved" | "now()"   | MEMBER  || null
        "approved" | "null"    | null    || "run_ceiling_changes_decided_together"
        "approved" | "now()"   | null    || "run_ceiling_changes_decided_together"
        null       | "now()"   | MEMBER  || "run_ceiling_changes_decided_together"
    }

    def "a change is never approved or refused by who asked for it, and may be withdrawn by them"() {
        when:
        def refusedBy = attempt("run_ceiling_changes", insertInto("run_ceiling_changes", A_CHANGE + [
                run_id: literal(OTHER_RUN), to_ceiling: "2000", awaits_approval: "true", outcome: literal(outcome),
                decided_at: "now()", decided_by: literal(decider)]))

        then:
        refusedBy == refusal

        where:
        outcome     | decider || refusal
        "approved"  | MEMBER  || null
        "refused"   | MEMBER  || null
        "withdrawn" | STEWARD || null
        "withdrawn" | MEMBER  || null
        "approved"  | STEWARD || "run_ceiling_changes_decider_is_not_requester"
        "refused"   | STEWARD || "run_ceiling_changes_decider_is_not_requester"
    }

    def "a run has one change awaiting at most, and any number decided or never awaiting beside it"() {
        when:
        def refusedBy = attempt("run_ceiling_changes", insertInto("run_ceiling_changes", A_CHANGE + [
                to_ceiling: "3000", awaits_approval: awaits, outcome: literal(outcome), decided_at: decidedAt,
                decided_by: literal(decider)]))

        then:
        refusedBy == refusal

        where:
        awaits  | outcome     | decidedAt | decider || refusal
        "true"  | null        | "null"    | null    || "run_ceiling_changes_one_awaiting"
        "true"  | "refused"   | "now()"   | MEMBER  || null
        "true"  | "withdrawn" | "now()"   | MEMBER  || null
        "false" | null        | "null"    | null    || null
    }

    def "a change once decided lets the next one await"() {
        when:
        def refusedBy = attempt("run_ceiling_changes", "update app.run_ceiling_changes set outcome = '${outcome}'," +
                " decided_at = now(), decided_by = '${MEMBER}' where run_ceiling_change_id = '${CHANGE}'; " +
                insertInto("run_ceiling_changes", A_CHANGE + [to_ceiling: "3000", awaits_approval: "true"]))

        then:
        refusedBy == null

        where:
        outcome << ["approved", "refused", "withdrawn"]
    }

    def "a step has one unreleased hold at most, and one released lets it be held again"() {
        when:
        def refusedBy = attempt("run_step_holds", first + insertInto("run_step_holds", A_HOLD + [
                run_step_id: literal(step), run_step_kind: literal(kind), calls_a_model: calls, released_at: releasedAt,
                released_by: literal(releaser)]))

        then:
        refusedBy == refusal

        where:
        first            | step              | kind       | calls   | releasedAt | releaser        || refusal
        ""               | RUN_STEP          | "question" | "true"  | "null"     | null            || "run_step_holds_one_unreleased"
        ""               | RUN_STEP          | "question" | "true"  | "now()"    | WORKFLOW_RUNNER || null
        RELEASE_THE_HOLD | RUN_STEP          | "question" | "true"  | "null"     | null            || null
        ""               | ESCALATE_RUN_STEP | "workflow" | "false" | "null"     | null            || null
    }

    /** What a person does to have a hold released is recorded as their own act, never as the release. */
    def "a hold is released by a system subject and by nobody else"() {
        when:
        def refusedBy = attempt("run_step_holds", setting("run_step_holds",
                "released_at = now(), released_by = '${releaser}', released_by_kind = '${kind}'"))

        then:
        refusedBy == refusal

        where:
        releaser        | kind     || refusal
        WORKFLOW_RUNNER | "system" || null
        STEWARD         | "person" || "run_step_holds_releaser_is_system"
        SEEDER          | "seeder" || "run_step_holds_releaser_is_system"
    }

    def "what happens to a step is recorded against the run it is a step of, and as what that step runs"() {
        when:
        def refusedBy = attempt(table, insertInto(table, WHAT_HAPPENS_TO_A_STEP[table] + [run_id: literal(run)] +
                (claimsAnother ? CLAIMING_ANOTHER_KIND[table] : [:])))

        then:
        refusedBy == (run == RUN && !claimsAnother ? null : "${table}_step_fk".toString())

        where:
        [table, run, claimsAnother] << [WHAT_HAPPENS_TO_A_STEP.keySet().toList(), [RUN, SUB_RUN], [false, true]]
                .combinations()
    }

    def "an attempt at sending is a person's or a system subject's"() {
        when:
        def refusedBy = attempt("run_step_send_attempts", insertInto("run_step_send_attempts",
                A_SEND_ATTEMPT + [created_by: literal(author), created_by_kind: literal(kind)]))

        then:
        refusedBy == refusal

        where:
        author          | kind     || refusal
        STEWARD         | "person" || null
        WORKFLOW_RUNNER | "system" || null
        SEEDER          | "seeder" || "run_step_send_attempts_author_is_person_or_system"
    }

    def "an attempt at sending is made to produce or to review, and never for the helper"() {
        when:
        def refusedBy = attempt("run_step_send_attempts", insertInto("run_step_send_attempts",
                A_SEND_ATTEMPT + anAttempt(step, purpose, production)))

        then:
        refusedBy == refusal

        where:
        step              | purpose   | production      || refusal
        RUN_STEP          | "produce" | PRODUCTION      || null
        REVIEWED_RUN_STEP | "review"  | REVIEWED_ANSWER || null
        RUN_STEP          | "help"    | PRODUCTION      || "run_step_send_attempts_purpose_is_produce_or_review"
    }

    def "an attempt to produce names the model and mode the approved step names to produce with"() {
        when:
        def refusedBy = attempt("run_step_send_attempts", insertInto("run_step_send_attempts",
                A_SEND_ATTEMPT + [model: literal(model), mode: literal(mode)]))

        then:
        refusedBy == refusal

        where:
        model     | mode       || refusal
        "general" | "ordinary" || null
        "general" | "research" || "run_step_send_attempts_producer_model_fk"
        "other"   | "ordinary" || "run_step_send_attempts_producer_model_fk"
    }

    /** The word the store keeps for no mode is compared as any other, so a key carrying it is never switched off. */
    def "an attempt to produce with the model as it is runs is refused on a step naming it in a mode"() {
        when:
        def refusedBy = attempt("run_step_send_attempts", PRODUCE_IN_RESEARCH_BENEATH +
                insertInto("run_step_send_attempts", A_SEND_ATTEMPT + [run_step_id: literal(SPARE_RUN_STEP),
                        run_id: literal(SUB_RUN), workflow_step_id: literal(SPARE_STEP),
                        production_id: literal(SPARE_PRODUCTION), mode: literal(mode)]))

        then:
        refusedBy == refusal

        where:
        mode       || refusal
        "research" || null
        "ordinary" || "run_step_send_attempts_producer_model_fk"
    }

    def "an attempt to review names the model and mode the approved step names to review with, and a step that names one"() {
        when:
        def refusedBy = attempt("run_step_send_attempts", insertInto("run_step_send_attempts",
                A_SEND_ATTEMPT + anAttempt(step, "review", production) + [mode: literal(mode)]))

        then:
        refusedBy == refusal

        where:
        step                   | production      | mode       || refusal
        REVIEWED_RUN_STEP      | REVIEWED_ANSWER | "research" || null
        REVIEWED_RUN_STEP      | REVIEWED_ANSWER | "ordinary" || "run_step_send_attempts_reviewer_model_fk"
        RUN_STEP               | PRODUCTION      | "ordinary" || "run_step_send_attempts_reviewer_model_fk"
        REVIEWED_CODE_RUN_STEP | CLOSED          | "ordinary" || null
        REVIEWED_CODE_RUN_STEP | CLOSED          | "research" || "run_step_send_attempts_reviewer_model_fk"
    }

    def "an attempt to produce names a model's try of its own step"() {
        when:
        def refusedBy = attempt("run_step_send_attempts", insertInto("run_step_send_attempts",
                A_SEND_ATTEMPT + [production_id: literal(production)]))

        then:
        refusedBy == refusal

        where:
        production      || refusal
        PRODUCTION      || null
        ANSWERED        || "run_step_send_attempts_produced_fk"
        REVIEWED_ANSWER || "run_step_send_attempts_produced_fk"
    }

    def "an attempt to review names a try of its own step that gave something back"() {
        when:
        def refusedBy = attempt("run_step_send_attempts", insertInto("run_step_send_attempts",
                A_SEND_ATTEMPT + anAttempt(REVIEWED_RUN_STEP, "review", production)))

        then:
        refusedBy == refusal

        where:
        production      || refusal
        REVIEWED_ANSWER || null
        PENDING         || "run_step_send_attempts_reviewed_fk"
        PRODUCTION      || "run_step_send_attempts_reviewed_fk"
    }

    def "what would be sent is kept as it was, as JSON text of at most 8388608 characters"() {
        when:
        def refusedBy = attempt("run_step_send_attempts", insertInto("run_step_send_attempts",
                A_SEND_ATTEMPT + [payload: payload]))

        then:
        refusedBy == refusal

        where:
        payload                                                     || refusal
        "'{' || chr(9) || chr(10) || chr(13) || ' }'"               || null
        "'{\"a\": \"' || chr(127) || chr(133) || chr(159) || '\"}'" || null
        "'\"' || repeat('a', 8388606) || '\"'"                      || null
        "''"                                                        || "run_step_send_attempts_payload_is_json"
        "'{\"a\": \"' || chr(9) || '\"}'"                           || "run_step_send_attempts_payload_is_json"
        "'{' || chr(11) || '}'"                                     || "run_step_send_attempts_payload_is_json"
        "'{' || chr(31) || '}'"                                     || "run_step_send_attempts_payload_is_json"
        "'Summarise the complaint.'"                                || "run_step_send_attempts_payload_is_json"
        "'\"' || repeat('a', 8388607) || '\"'"                      || "run_step_send_attempts_payload_bounded"
    }

    def "an attempt holds what it would send, or names an attempt holding the same, and never both"() {
        when:
        def refusedBy = attempt("run_step_send_attempts", insertInto("run_step_send_attempts",
                A_SEND_ATTEMPT + [payload: payload, repeats_attempt_id: literal(repeated)]))

        then:
        refusedBy == refusal

        where:
        payload | repeated     || refusal
        "'{}'"  | null         || null
        "null"  | SEND_ATTEMPT || null
        "null"  | null         || "run_step_send_attempts_repeats_exactly_for_no_payload"
        "'{}'"  | SEND_ATTEMPT || "run_step_send_attempts_repeats_exactly_for_no_payload"
    }

    /**
     * Named directly, so what was sent is read from one row and never down a chain of repeats. What a step is sent to
     * produce is the same whichever try it is for; what a try is sent to be reviewed is that try's own.
     */
    def "an attempt repeats one for the same purpose that holds what it sent: of its step to produce, of its try to review"() {
        when:
        def refusedBy = attempt("run_step_send_attempts", first + insertInto("run_step_send_attempts",
                A_SEND_ATTEMPT + anAttempt(step, purpose, production) + [payload: "null",
                        repeats_attempt_id: literal(repeated)]))

        then:
        refusedBy == refusal

        where:
        first                           | step              | purpose   | production       | repeated       || refusal
        ""                              | RUN_STEP          | "produce" | PRODUCTION       | SEND_ATTEMPT   || null
        A_TRY_LEFT_WAITING_BY_A_FAILURE | RUN_STEP          | "produce" | SPARE_PRODUCTION | SEND_ATTEMPT   || null
        ""                              | REVIEWED_RUN_STEP | "review"  | REVIEWED_ANSWER  | REVIEW_ATTEMPT || null
        ANSWER_THE_WAITING_TRY          | REVIEWED_RUN_STEP | "review"  | PENDING          | REVIEW_ATTEMPT || "run_step_send_attempts_repeats_fk"
        ""                              | REVIEWED_RUN_STEP | "review"  | REVIEWED_ANSWER  | SEND_ATTEMPT   || "run_step_send_attempts_repeats_fk"
        ""                              | REVIEWED_RUN_STEP | "produce" | REVIEWED_ANSWER  | REVIEW_ATTEMPT || "run_step_send_attempts_repeats_fk"
        REPEAT_THE_SEND_ATTEMPT         | RUN_STEP          | "produce" | PRODUCTION       | SPARE_ATTEMPT  || "run_step_send_attempts_repeats_fk"
    }

    /** Once one is, a person reviews in the model's place, and no attempt at reviewing that try follows it. */
    def "a try has one attempt at reviewing it measured too long at most"() {
        when:
        def refusedBy = attempt("run_step_send_attempts", first + insertInto("run_step_send_attempts",
                A_SEND_ATTEMPT + anAttempt(REVIEWED_RUN_STEP, "review", production) + [too_long: "true",
                        created_by: literal(WORKFLOW_RUNNER), created_by_kind: "'system'"]))

        then:
        refusedBy == refusal

        where:
        first                  | production      || refusal
        ANSWER_THE_WAITING_TRY | PENDING         || null
        ""                     | REVIEWED_ANSWER || null
        ""                     | LONG_ANSWER     || "run_step_send_attempts_one_too_long_review"
    }

    /**
     * Too long is measured and never said: a person who wrote one would have the review handed to them. What a
     * person sends again after the call was turned away every time is measured afresh, and may be too long in turn.
     */
    def "an attempt at reviewing is measured too long by the system alone, whether or not it repeats one"() {
        when:
        def refusedBy = attempt("run_step_send_attempts", insertInto("run_step_send_attempts", A_SEND_ATTEMPT +
                anAttempt(REVIEWED_RUN_STEP, "review", REVIEWED_ANSWER) + [too_long: tooLong, payload: payload,
                        repeats_attempt_id: literal(repeated), created_by: literal(author), created_by_kind: literal(kind)]))

        then:
        refusedBy == refusal

        where:
        tooLong | payload | repeated       | author          | kind     || refusal
        "true"  | "'{}'"  | null           | WORKFLOW_RUNNER | "system" || null
        "true"  | "null"  | REVIEW_ATTEMPT | WORKFLOW_RUNNER | "system" || null
        "false" | "null"  | REVIEW_ATTEMPT | STEWARD         | "person" || null
        "true"  | "'{}'"  | null           | STEWARD         | "person" || "run_step_send_attempts_too_long_review_only_for_system"
        "true"  | "null"  | REVIEW_ATTEMPT | STEWARD         | "person" || "run_step_send_attempts_too_long_review_only_for_system"
    }

    /**
     * What a review would be sent is not built where the release no longer allows it: nothing is held of it and
     * nothing sent, so a person reviews as for one too long; a repeat is of something built, so none is unbuilt.
     */
    def "an attempt at reviewing is left unbuilt only as sending nothing and holding nothing"() {
        when:
        def refusedBy = attempt("run_step_send_attempts", insertInto("run_step_send_attempts", A_SEND_ATTEMPT +
                anAttempt(REVIEWED_RUN_STEP, "review", REVIEWED_ANSWER) + [too_long: tooLong, payload: payload,
                        unbuilt_reason: literal(unbuilt), repeats_attempt_id: literal(repeated),
                        created_by: literal(WORKFLOW_RUNNER), created_by_kind: "'system'"]))

        then:
        refusedBy == refusal

        where:
        tooLong | payload | unbuilt                    | repeated       || refusal
        "true"  | "null"  | "list_not_here"            | null           || null
        "true"  | "null"  | "takes_no_longer_declared" | null           || null
        "true"  | "null"  | "no_longer_declared"       | null           || null
        "false" | "null"  | "no_longer_declared"       | null           || "run_step_send_attempts_unbuilt_only_for_review_not_sent"
        "true"  | "'{}'"  | "no_longer_declared"       | null           || "run_step_send_attempts_unbuilt_only_for_review_not_sent"
        "true"  | "null"  | "no_longer_declared"       | REVIEW_ATTEMPT || "run_step_send_attempts_repeats_exactly_for_no_payload"
        "true"  | "null"  | null                       | null           || "run_step_send_attempts_repeats_exactly_for_no_payload"
    }

    /** The key a repeat names does not act on the row it names: changing what a repeat relies on is refused. */
    def "an attempt a repeat names keeps its purpose, and for reviewing keeps the try it reviews"() {
        when:
        def refusedBy = attempt("run_step_send_attempts", first + change)

        then:
        refusedBy == refusal

        where:
        first                     | change                                                                                                                     || refusal
        REPEAT_THE_REVIEW_ATTEMPT | "update app.run_step_send_attempts set production_id = '${LONG_ANSWER}' where run_step_send_attempt_id = '${REVIEW_ATTEMPT}'" || "run_step_send_attempts_repeats_fk"
        REPEAT_THE_SEND_ATTEMPT   | "update app.run_step_send_attempts set purpose = 'review' where run_step_send_attempt_id = '${SEND_ATTEMPT}'"               || "run_step_send_attempts_repeats_fk"
    }

    def "a try waiting on a model is tried sending again after its step failed"() {
        when:
        def refusedBy = attempt("model_calls", A_TRY_LEFT_WAITING_BY_A_FAILURE +
                insertInto("run_step_send_attempts", A_SEND_ATTEMPT + [run_step_send_attempt_id: literal(SPARE_NEXT_ATTEMPT),
                        production_id: literal(SPARE_PRODUCTION), payload: "null",
                        repeats_attempt_id: literal(SPARE_ATTEMPT), answers_failure_id: literal(SPARE_FAILURE)]) + "; " +
                insertInto("model_calls", A_PRODUCE_CALL + [run_step_send_attempt_id: literal(SPARE_NEXT_ATTEMPT),
                        production_id: literal(SPARE_PRODUCTION)]))

        then:
        refusedBy == null
    }

    /** A route's failure is on no try, and so is never answered. */
    def "a failure is answered once at most, by an attempt at sending the try it was on for what it failed sending"() {
        when:
        def refusedBy = attempt("run_step_send_attempts", first + insertInto("run_step_send_attempts",
                A_SEND_ATTEMPT + anAttempt(step, purpose, production) + [answers_failure_id: literal(failure)]))

        then:
        refusedBy == refusal

        where:
        first                           | step                   | purpose   | production       | failure       || refusal
        A_TRY_LEFT_WAITING_BY_A_FAILURE | RUN_STEP               | "produce" | SPARE_PRODUCTION | SPARE_FAILURE || null
        FAIL_TO_REVIEW_THE_ANSWER       | REVIEWED_RUN_STEP      | "review"  | REVIEWED_ANSWER  | SPARE_FAILURE || null
        FAIL_TO_REVIEW_THE_MODELS_TRY   | REVIEWED_RUN_STEP      | "review"  | SPARE_PRODUCTION | SPARE_FAILURE || null
        FAIL_TO_REVIEW_THE_MODELS_TRY   | REVIEWED_RUN_STEP      | "produce" | SPARE_PRODUCTION | SPARE_FAILURE || "run_step_send_attempts_failure_fk"
        FAIL_TO_REVIEW_THE_MODELS_TRY   | REVIEWED_RUN_STEP      | "review"  | REVIEWED_ANSWER  | SPARE_FAILURE || "run_step_send_attempts_failure_fk"
        A_TRY_LEFT_WAITING_BY_A_FAILURE | RUN_STEP               | "produce" | PRODUCTION       | SPARE_FAILURE || "run_step_send_attempts_failure_fk"
        A_TRY_LEFT_WAITING_BY_A_FAILURE | REVIEWED_RUN_STEP      | "review"  | REVIEWED_ANSWER  | SPARE_FAILURE || "run_step_send_attempts_failure_fk"
        ""                              | REVIEWED_CODE_RUN_STEP | "review"  | CLOSED           | UNDEPLOYED    || "run_step_send_attempts_failure_unique"
        ""                              | RUN_STEP               | "produce" | PRODUCTION       | FAILURE       || "run_step_send_attempts_failure_fk"
    }

    def "a call to produce or to review goes through an attempt made for that, on its step, to the same model and mode"() {
        when:
        def refusedBy = attempt("model_calls", first + insertInto("model_calls", A_PRODUCE_CALL + CAME_BACK + [
                purpose: literal(purpose), run_step_send_attempt_id: literal(SPARE_ATTEMPT), run_step_id: literal(step),
                mode: literal(mode), production_id: literal(production)]))

        then:
        refusedBy == refusal

        where:
        first     | purpose   | step              | mode       | production      || refusal
        PRODUCING | "produce" | RUN_STEP          | "ordinary" | PRODUCTION      || null
        REVIEWING | "review"  | REVIEWED_RUN_STEP | "research" | REVIEWED_ANSWER || null
        PRODUCING | "review"  | RUN_STEP          | "ordinary" | PRODUCTION      || "model_calls_attempt_fk"
        PRODUCING | "produce" | REVIEWED_RUN_STEP | "ordinary" | PRODUCTION      || "model_calls_attempt_fk"
        PRODUCING | "produce" | RUN_STEP          | "research" | PRODUCTION      || "model_calls_attempt_fk"
        REVIEWING | "review"  | REVIEWED_RUN_STEP | "ordinary" | REVIEWED_ANSWER || "model_calls_attempt_fk"
        REVIEWING | "review"  | REVIEWED_RUN_STEP | "research" | PENDING         || "model_calls_attempt_fk"
    }

    def "a step has one call out at a time, and a call turned away every time is no longer out"() {
        when:
        def refusedBy = attempt("model_calls", first + insertInto("model_calls", A_CODE_REVIEW_CALL + (ended ? CAME_BACK : [:]) +
                [run_step_send_attempt_id: literal(SPARE_ATTEMPT)]))

        then:
        refusedBy == refusal

        where:
        first                                        | ended || refusal
        REVIEWING_THE_CODE                           | true  || null
        TURN_AWAY_THE_CALL_OUT + REVIEWING_THE_CODE  | false || null
        REVIEWING_THE_CODE                           | false || "model_calls_one_unended_per_step"
    }

    def "a call to the helper is made only where the version at the top may be helped, to the model and mode it names"() {
        when:
        def refusedBy = attempt("model_calls", insertInto("model_calls", A_HELP_CALL + [run_id: literal(run),
                root_run_id: literal(run), root_version_id: literal(version), model: literal(model), mode: literal(mode)]))

        then:
        refusedBy == refusal

        where:
        run      | version               | model     | mode       || refusal
        CODE_RUN | NEXT_WORKFLOW_VERSION | "general" | "ordinary" || null
        RUN      | WORKFLOW_VERSION      | "general" | "ordinary" || "model_calls_helper_fk"
        CODE_RUN | NEXT_WORKFLOW_VERSION | "other"   | "ordinary" || "model_calls_helper_fk"
        CODE_RUN | NEXT_WORKFLOW_VERSION | "general" | "research" || "model_calls_helper_fk"
    }

    /** The word the store keeps for no mode is compared as any other, so a key carrying it is never switched off. */
    def "a call to the helper as it is runs is refused where the version names the helper in a mode"() {
        when:
        def refusedBy = attempt("model_calls", setting("workflow_versions",
                "may_be_helped = true, helper_model = 'general', helper_mode = 'research'") + "; " +
                insertInto("model_calls", A_HELP_CALL + [run_id: literal(RUN), root_run_id: literal(RUN),
                        root_version_id: literal(WORKFLOW_VERSION), mode: literal(mode)]))

        then:
        refusedBy == refusal

        where:
        mode       || refusal
        "research" || null
        "ordinary" || "model_calls_helper_fk"
    }

    def "a call says what came back, and how much, only where something did"() {
        when:
        def refusedBy = attempt("model_calls", insertInto("model_calls", A_HELP_CALL + [outcome: literal(outcome),
                came_back_count: literal(cameBack), answer: answer, error_detail: literal(detail), ended_at: endedAt]))

        then:
        refusedBy == refusal

        where:
        outcome             | cameBack | answer | detail       | endedAt || refusal
        null                | null     | "null" | null         | "null"  || null
        "came_back"         | 300      | "'{}'" | null         | "now()" || null
        "came_back"         | 0        | "''"   | null         | "now()" || null
        "nothing_came_back" | null     | "null" | null         | "now()" || null
        "turned_away"       | null     | "null" | null         | "now()" || null
        "nothing_came_back" | 300      | "null" | null         | "now()" || "model_calls_came_back_count_exactly_for_came_back"
        "errored"           | 300      | "null" | "Timed out." | "now()" || "model_calls_came_back_count_exactly_for_came_back"
        "turned_away"       | null     | "'{}'" | null         | "now()" || "model_calls_answer_exactly_for_came_back"
    }

    def "a call says what went wrong only where it went wrong"() {
        when:
        def refusedBy = attempt("model_calls", insertInto("model_calls", A_HELP_CALL + [outcome: literal(outcome),
                came_back_count: literal(cameBack), answer: answer, error_detail: literal(detail), ended_at: "now()"]))

        then:
        refusedBy == refusal

        where:
        outcome     | cameBack | answer | detail       || refusal
        "errored"   | null     | "null" | "Timed out." || null
        "came_back" | 300      | "'{}'" | null         || null
        "came_back" | 300      | "'{}'" | "Timed out." || "model_calls_error_detail_exactly_for_errored"
        "errored"   | null     | "null" | null         || "model_calls_error_detail_exactly_for_errored"
    }

    def "a call says what went wrong was cut only where there is something to cut"() {
        when:
        def refusedBy = attempt("model_calls", insertInto("model_calls", A_HELP_CALL + [outcome: literal(outcome),
                came_back_count: literal(cameBack), answer: answer, error_detail: literal(detail),
                error_detail_truncated: truncated, ended_at: "now()"]))

        then:
        refusedBy == refusal

        where:
        outcome     | cameBack | answer | detail       | truncated || refusal
        "errored"   | null     | "null" | "Timed out." | "false"   || null
        "errored"   | null     | "null" | "Timed out." | "true"    || null
        "came_back" | 300      | "'{}'" | null         | "true"    || "model_calls_truncated_only_for_error_detail"
    }

    def "a call says what is kept of its answer is not as it came back only where an answer came back"() {
        when:
        def refusedBy = attempt("model_calls", insertInto("model_calls", A_HELP_CALL + [outcome: literal(outcome),
                came_back_count: literal(cameBack), answer: answer, error_detail: literal(detail),
                answer_altered: altered, ended_at: "now()"]))

        then:
        refusedBy == refusal

        where:
        outcome             | cameBack | answer | detail       | altered || refusal
        "came_back"         | 300      | "'{}'" | null         | "true"  || null
        "came_back"         | 300      | "'{}'" | null         | "false" || null
        "errored"           | null     | "null" | "Timed out." | "true"  || "model_calls_answer_altered_only_for_answer"
        "nothing_came_back" | null     | "null" | null         | "true"  || "model_calls_answer_altered_only_for_answer"
    }

    def "a call says the model did its counting only where something came back"() {
        when:
        def refusedBy = attempt("model_calls", insertInto("model_calls", A_HELP_CALL + [outcome: literal(outcome),
                came_back_count: literal(cameBack), answer: answer, error_detail: literal(detail), ended_at: endedAt,
                counted_by_model: counted]))

        then:
        refusedBy == refusal

        where:
        outcome             | cameBack | answer | detail       | endedAt | counted || refusal
        "came_back"         | 300      | "'{}'" | null         | "now()" | "true"  || null
        "came_back"         | 300      | "'{}'" | null         | "now()" | "false" || null
        "errored"           | null     | "null" | "Timed out." | "now()" | "true"  || "model_calls_counted_by_model_only_for_came_back"
        "nothing_came_back" | null     | "null" | null         | "now()" | "true"  || "model_calls_counted_by_model_only_for_came_back"
        "turned_away"       | null     | "null" | null         | "now()" | "true"  || "model_calls_counted_by_model_only_for_came_back"
        null                | null     | "null" | null         | "null"  | "true"  || "model_calls_counted_by_model_only_for_came_back"
    }

    def "a call still out may say the model did its counting as it comes back, and not as it goes wrong"() {
        when:
        def refusedBy = attempt("model_calls", endTheSpareHelpCall(outcome, "true"))

        then:
        refusedBy == refusal

        where:
        outcome     || refusal
        "came_back" || null
        "errored"   || "model_calls_counted_by_model_only_for_came_back"
    }

    def "a turnaway keeps what the model said, and says it was cut only where it said something"() {
        when:
        def refusedBy = attempt("model_call_turnaways", insertInto("model_call_turnaways",
                A_TURNAWAY + [said: literal(said), said_truncated: truncated]))

        then:
        refusedBy == refusal

        where:
        said    | truncated || refusal
        "Busy." | "false"   || null
        "Busy." | "true"    || null
        null    | "false"   || null
        null    | "true"    || "model_call_turnaways_truncated_only_for_said"
    }

    def "a turnaway says what may be spent was used up only of a call that ended turned away, and carries that outcome"() {
        when:
        def refusedBy = attempt("model_call_turnaways", first + insertInto("model_call_turnaways", A_TURNAWAY + [
                model_call_id: literal(call), spent_up: spentUp, model_call_outcome: literal(carried)]))

        then:
        refusedBy == refusal

        where:
        first                  | call     | spentUp | carried       || refusal
        TURN_AWAY_THE_CALL_OUT | OUT_CALL | "true"  | "turned_away" || null
        TURN_AWAY_THE_CALL_OUT | OUT_CALL | "false" | null          || null
        ""                     | CALL     | "true"  | "turned_away" || "model_call_turnaways_model_call_outcome_fk"
        ""                     | OUT_CALL | "true"  | "turned_away" || "model_call_turnaways_model_call_outcome_fk"
        ""                     | CALL     | "true"  | "came_back"   || "model_call_turnaways_model_call_outcome_is_turned_away"
        TURN_AWAY_THE_CALL_OUT | OUT_CALL | "true"  | null          || "model_call_turnaways_model_call_outcome_exactly_for_spent_up"
        TURN_AWAY_THE_CALL_OUT | OUT_CALL | "false" | "turned_away" || "model_call_turnaways_model_call_outcome_exactly_for_spent_up"
    }

    def "a call has one turnaway saying what may be spent was used up, and any number of others after it"() {
        when:
        def refusedBy = attempt("model_call_turnaways", insertInto("model_call_turnaways", A_TURNAWAY + [
                model_call_id: literal(SPENT_CALL), spent_up: spentUp, model_call_outcome: literal(carried)]))

        then:
        refusedBy == refusal

        where:
        spentUp | carried       || refusal
        "false" | null          || null
        "true"  | "turned_away" || "model_call_turnaways_one_spent_up_per_call"
    }

    def "a call a turnaway says was spent up stays turned away, and one turned away only plainly may still change"() {
        when:
        def refusedBy = attempt("model_calls", first +
                "update app.model_calls set outcome = 'nothing_came_back' where model_call_id = '${call}'")

        then:
        refusedBy == refusal

        where:
        first                          | call       || refusal
        ""                             | SPENT_CALL || "model_call_turnaways_model_call_outcome_fk"
        TURN_AWAY_THE_CALL_OUT_PLAINLY | OUT_CALL   || null
    }

    def "a turnaway is sent again only once it came, and only where it did not spend its model up"() {
        when:
        def refusedBy = attempt("model_call_turnaways", "update app.model_call_turnaways set resent_at = ${resent}" +
                " where model_call_turnaway_id = '${turnaway}'")

        then:
        refusedBy == refusal

        where:
        turnaway       | resent                                || refusal
        TURNAWAY       | "created_at"                          || null
        TURNAWAY       | "created_at + interval '1 second'"    || null
        SPENT_TURNAWAY | "created_at"                          || "model_call_turnaways_resent_only_unspent"
    }

    def "a question to the helper is asked through a call to the helper about its own run"() {
        when:
        def refusedBy = attempt("run_help_exchanges", insertInto("run_help_exchanges", A_HELP + [run_id: literal(run),
                root_run_id: literal(run), model_call_id: literal(call)]))

        then:
        refusedBy == refusal

        where:
        run      | call            || refusal
        CODE_RUN | SPARE_HELP_CALL || null
        RUN      | SPARE_HELP_CALL || "run_help_exchanges_model_call_fk"
    }

    def "a question to the helper has an answer only where its call came back"() {
        when:
        def refusedBy = attempt("run_help_exchanges", first + insertInto("run_help_exchanges", A_HELP + [
                model_call_outcome: literal(claimed), answer: literal(answer)]))

        then:
        refusedBy == refusal

        where:
        first                            | claimed     | answer                    || refusal
        ""                               | null        | null                      || null
        endTheSpareHelpCall("came_back") | "came_back" | "It was the mail server." || null
        endTheSpareHelpCall("errored")   | "errored"   | null                      || null
        endTheSpareHelpCall("errored")   | "errored"   | "It was the mail server." || "run_help_exchanges_answer_only_for_came_back"
        endTheSpareHelpCall("errored")   | "came_back" | "It was the mail server." || "run_help_exchanges_model_call_outcome_fk"
    }

    def "a try is produced by whoever its step names, or by a person in their place"() {
        when:
        def refusedBy = attempt("productions", insertInto("productions", aTry(step, producedBy) + [
                try_number: literal(number)]))

        then:
        refusedBy == refusal

        where:
        step              | producedBy | number || refusal
        RUN_STEP          | "model"    | 3      || null
        RUN_STEP          | "person"   | 3      || null
        REVIEWED_RUN_STEP | "person"   | 3      || null
        CODE_RUN_STEP     | "code"     | 3      || null
        CODE_RUN_STEP     | "person"   | 3      || null
        REVIEWED_RUN_STEP | "code"     | 1      || "productions_producer_is_step_producer_or_person"
        CODE_RUN_STEP     | "model"    | 3      || "productions_producer_is_step_producer_or_person"
    }

    /** One try at a time beyond them is the application's; that nothing but a person asks for one is held here. */
    def "a try beyond what its step declares is asked for by a person and never by the system"() {
        when:
        def refusedBy = attempt("productions", insertInto("productions", aTry(step, producedBy) + [
                try_number: literal(number), created_by: literal(asker), created_by_kind: literal(kind)]))

        then:
        refusedBy == refusal

        where:
        step          | producedBy | number | asker           | kind     || refusal
        RUN_STEP      | "model"    | 3      | WORKFLOW_RUNNER | "system" || null
        RUN_STEP      | "model"    | 4      | MEMBER          | "person" || null
        CODE_RUN_STEP | "code"     | 4      | STEWARD         | "person" || null
        RUN_STEP      | "model"    | 4      | WORKFLOW_RUNNER | "system" || "productions_beyond_tries_only_for_person"
        CODE_RUN_STEP | "code"     | 4      | WORKFLOW_RUNNER | "system" || "productions_beyond_tries_only_for_person"
    }

    /** The first try of such a step is the only one code runs; a person answering in its place is a try like any other. */
    def "code that may not run again is never run for a value a second time, by itself or when asked"() {
        when:
        def refusedBy = attempt("productions", insertInto("productions", aTry(step, producedBy) + [
                try_number: literal(number), created_by: literal(asker), created_by_kind: literal(kind)]))

        then:
        refusedBy == refusal

        where:
        step                   | producedBy | number | asker           | kind     || refusal
        REVIEWED_CODE_RUN_STEP | "person"   | 2      | MEMBER          | "person" || null
        CODE_RUN_STEP          | "code"     | 3      | WORKFLOW_RUNNER | "system" || null
        REVIEWED_CODE_RUN_STEP | "code"     | 2      | WORKFLOW_RUNNER | "system" || "productions_code_again_only_for_may_run_again"
        REVIEWED_CODE_RUN_STEP | "code"     | 2      | STEWARD         | "person" || "productions_code_again_only_for_may_run_again"
    }

    /** Whoever answers it, a code step's try says what the release said then; nothing but code has anything to say. */
    def "a try says whether its code may run again exactly where its step runs a code step"() {
        when:
        def refusedBy = attempt("productions", insertInto("productions", aTry(step, producedBy) + [
                may_run_again: literal(again)]))

        then:
        refusedBy == refusal

        where:
        step          | producedBy | again   || refusal
        RUN_STEP      | "person"   | null    || null
        CODE_RUN_STEP | "person"   | "false" || null
        CODE_RUN_STEP | "code"     | "true"  || null
        RUN_STEP      | "person"   | "false" || "productions_may_run_again_exactly_for_code_step"
        RUN_STEP      | "model"    | "true"  || "productions_may_run_again_exactly_for_code_step"
        CODE_RUN_STEP | "person"   | null    || "productions_may_run_again_exactly_for_code_step"
    }

    def "a person's try, once answered, says why they said it, and nobody else's does"() {
        when:
        def refusedBy = attempt("productions", insertInto("productions", aTry(step, producedBy) + [
                explanation: literal(explanation)] + (ended ? [:] : [ended_at: "null", ended_by: "null"])))

        then:
        refusedBy == refusal

        where:
        step          | producedBy | ended | explanation || refusal
        RUN_STEP      | "person"   | true  | "Read it."  || null
        RUN_STEP      | "person"   | false | null        || null
        CODE_RUN_STEP | "code"     | false | null        || null
        RUN_STEP      | "model"    | false | null        || null
        RUN_STEP      | "person"   | false | "Read it."  || "productions_explanation_exactly_for_ended_by_person"
        CODE_RUN_STEP | "code"     | true  | "Read it."  || "productions_explanation_exactly_for_ended_by_person"
    }

    def "a step has one try waiting at most, whoever produces it, and one answered lets the next wait"() {
        when:
        def refusedBy = attempt("productions", first + insertInto("productions", aTry(step, producer) + [
                explanation: "null", ended_at: "null", ended_by: "null"]))

        then:
        refusedBy == refusal

        where:
        first                  | step              | producer || refusal
        ""                     | REVIEWED_RUN_STEP | "person" || "productions_one_unended"
        ""                     | REVIEWED_RUN_STEP | "model"  || "productions_one_unended"
        ANSWER_THE_WAITING_TRY | REVIEWED_RUN_STEP | "model"  || null
        ""                     | RUN_STEP          | "person" || null
    }

    def "a model's try ends as its call ended, and names that call once it has"() {
        when:
        def refusedBy = attempt("productions", aModelTryWithItsCall(called) + endTheModelTry(named, lost))

        then:
        refusedBy == refusal

        where:
        called              | named               | lost                || refusal
        "came_back"         | "came_back"         | null                || null
        "came_back"         | "came_back"         | "did_not_fit"       || null
        "errored"           | "errored"           | "errored"           || null
        "nothing_came_back" | "nothing_came_back" | "nothing_came_back" || null
        "errored"           | "errored"           | null                || "productions_lost_as_call_ended"
        "came_back"         | "came_back"         | "nothing_came_back" || "productions_lost_as_call_ended"
        "nothing_came_back" | "nothing_came_back" | "errored"           || "productions_lost_as_call_ended"
        "turned_away"       | "turned_away"       | null                || "productions_lost_as_call_ended"
        "errored"           | "came_back"         | null                || "productions_model_call_fk"
        null                | "came_back"         | null                || "productions_model_call_fk"
    }

    def "a model's try whose answer is not kept as it came back ends as one that did not fit"() {
        when:
        def refusedBy = attempt("productions", aModelTryWithItsCall("came_back") +
                "update app.model_calls set answer_altered = ${altered} where model_call_id = '${SPARE_CALL}'; " +
                "update app.productions set ended_at = now(), ended_by = '${WORKFLOW_RUNNER}'," +
                " model_call_id = '${SPARE_CALL}', model_call_outcome = 'came_back', call_answer_altered = ${carried}," +
                " lost_reason = ${literal(lost)}," +
                " did_not_fit_reason = ${literal(reason)} where production_id = '${SPARE_PRODUCTION}'")

        then:
        refusedBy == refusal

        where:
        altered | carried | lost          | reason                || refusal
        "true"  | "true"  | "did_not_fit" | "not_the_shape"       || null
        "true"  | "true"  | "did_not_fit" | "not_kept_as_it_came" || null
        "true"  | "true"  | "did_not_fit" | "cut_off"             || null
        "true"  | "true"  | "did_not_fit" | null                  || "productions_did_not_fit_reason_exactly_for_model_did_not_fit"
        "true"  | "true"  | null          | null                  || "productions_altered_answer_did_not_fit"
        "true"  | "false" | "did_not_fit" | "not_the_shape"       || "productions_model_call_fk"
        "false" | "true"  | "did_not_fit" | "not_the_shape"       || "productions_model_call_fk"
    }

    def "a try is lost only by a model or by code, and only once it has ended"() {
        when:
        def refusedBy = attempt("productions", insertInto("productions", aTry(step, producer) + [
                lost_reason: literal(lost), lost_detail: literal(detail), returned_by_code: literal(returned),
                did_not_fit_reason: literal(reason)] +
                (ended ? [:] : [ended_at: "null", ended_by: "null"])))

        then:
        refusedBy == refusal

        where:
        step          | producer | ended | lost                | detail      | returned | reason          || refusal
        CODE_RUN_STEP | "code"   | true  | "errored"           | "It broke." | null     | null            || null
        CODE_RUN_STEP | "code"   | true  | "errored"           | "It broke." | "{}"     | null            || null
        CODE_RUN_STEP | "code"   | true  | "nothing_came_back" | null        | null     | null            || null
        CODE_RUN_STEP | "code"   | true  | "did_not_fit"       | null        | "{}"     | null            || "productions_did_not_fit_only_for_model"
        CODE_RUN_STEP | "code"   | true  | "errored"           | "It broke." | null     | "not_the_shape" || "productions_did_not_fit_reason_exactly_for_model_did_not_fit"
        CODE_RUN_STEP | "code"   | false | "nothing_came_back" | null        | null     | null            || "productions_lost_only_for_ended"
        RUN_STEP      | "person" | true  | "nothing_came_back" | null        | null     | null            || "productions_lost_only_for_model_or_code"
    }

    def "a try says what went wrong, and that it was cut, only where code went wrong"() {
        when:
        def refusedBy = attempt("productions", insertInto("productions", aTry(CODE_RUN_STEP, "code") + [
                lost_reason: literal(lost), lost_detail: literal(detail), lost_detail_truncated: truncated]))

        then:
        refusedBy == refusal

        where:
        lost                | detail      | truncated || refusal
        "errored"           | "It broke." | "false"   || null
        "errored"           | "It broke." | "true"    || null
        "errored"           | null        | "false"   || "productions_lost_detail_exactly_where_code_threw"
        "nothing_came_back" | "It broke." | "false"   || "productions_lost_detail_exactly_where_code_threw"
        null                | null        | "true"    || "productions_truncated_only_for_lost_detail"
    }

    /** Counted as the store counts characters, a character outside the basic plane being one, as the code counts it. */
    def "what went wrong and what code gave back are bounded in characters, one past each bound refused"() {
        when:
        def refusedBy = attempt("productions", insertInto("productions", aTry(CODE_RUN_STEP, "code") + [
                lost_reason: "'errored'",
                lost_detail: detailCount == null ? "'It broke.'" : "repeat(chr(128512), ${detailCount})",
                returned_by_code: returnedCount == null ? "null" : "repeat(chr(128512), ${returnedCount})"]))

        then:
        refusedBy == refusal

        where:
        detailCount | returnedCount || refusal
        2048        | null          || null
        2049        | null          || "productions_lost_detail_bounded"
        null        | 8388608       || null
        null        | 8388609       || "productions_returned_by_code_bounded"
    }

    def "a model's try says nothing of what went wrong, which its call says"() {
        when:
        def refusedBy = attempt("productions", aModelTryWithItsCall("errored") + endTheModelTry("errored", "errored") +
                "update app.productions set lost_detail = ${literal(detail)} where production_id = '${SPARE_PRODUCTION}'")

        then:
        refusedBy == refusal

        where:
        detail      || refusal
        null        || null
        "It broke." || "productions_lost_detail_exactly_where_code_threw"
    }

    def "what code gave back is kept only beside code gone wrong, and code that threw keeps nothing"() {
        when:
        def refusedBy = attempt("productions", insertInto("productions", aTry(CODE_RUN_STEP, "code") + [
                lost_reason: literal(lost), lost_detail: literal(detail), returned_by_code: literal(returned)]))

        then:
        refusedBy == refusal

        where:
        lost                | detail      | returned            || refusal
        "errored"           | "It broke." | "{\"total\": 9999}" || null
        "errored"           | "It broke." | null                || null
        "nothing_came_back" | null        | "{\"total\": 9999}" || "productions_returned_by_code_only_for_code_errored"
        null                | null        | "{\"total\": 9999}" || "productions_returned_by_code_only_for_code_errored"
    }

    def "code gone wrong says why once: as this system's reason, or where it threw, in its own words"() {
        when:
        def refusedBy = attempt("productions", insertInto("productions", aTry(CODE_RUN_STEP, "code") + [
                lost_reason: literal(lost), code_error_reason: literal(reason), code_error_path: literal(path),
                lost_detail: literal(detail), returned_by_code: literal(returned)]))

        then:
        refusedBy == refusal

        where:
        lost                | reason         | path      | detail      | returned            || refusal
        "errored"           | "too_long"     | "receipt" | null        | "{\"total\": 9999}" || null
        "errored"           | "gave_nothing" | null      | null        | null                || null
        "errored"           | "said_nothing" | null      | null        | null                || null
        "errored"           | null           | null      | "It broke." | null                || null
        "errored"           | "said_nothing" | null      | "It broke." | null                || "productions_lost_detail_exactly_where_code_threw"
        "errored"           | "too_long"     | "receipt" | "It broke." | null                || "productions_lost_detail_exactly_where_code_threw"
        "errored"           | null           | null      | null        | null                || "productions_lost_detail_exactly_where_code_threw"
        "nothing_came_back" | "gave_nothing" | null      | null        | null                || "productions_code_error_reason_only_for_code_errored"
        null                | "gave_nothing" | null      | null        | null                || "productions_code_error_reason_only_for_code_errored"
    }

    def "a model's try carries no reason code went wrong for, what went wrong with it being on its call"() {
        when:
        def refusedBy = attempt("productions", aModelTryWithItsCall("errored") + endTheModelTry("errored", "errored") +
                "update app.productions set code_error_reason = ${literal(reason)}" +
                " where production_id = '${SPARE_PRODUCTION}'")

        then:
        refusedBy == refusal

        where:
        reason                || refusal
        null                  || null
        "failed_on_this_side" || "productions_code_error_reason_only_for_code_errored"
    }

    def "every reason code goes wrong for is about a field, about none, or about a member either way"() {
        expect:
        queryForStrings("select unnest(enum_range(null::app.code_error_reason))::text") ==
                (ABOUT_A_FIELD + ABOUT_NO_FIELD + ["not_declared"]) as Set

        and: "each in one list only, so none is judged twice or both ways"
        (ABOUT_A_FIELD + ABOUT_NO_FIELD).toSet().size() == ABOUT_A_FIELD.size() + ABOUT_NO_FIELD.size()
        !(ABOUT_A_FIELD + ABOUT_NO_FIELD).contains("not_declared")
    }

    /** What reads the field is named for the one reason that needs it, so it is not what refuses the row here. */
    def "#reason names the field it is about exactly where it is about one"() {
        when:
        def refusedBy = attempt("productions", insertInto("productions", A_CODE_FAULT + [
                code_error_reason: literal(reason), code_error_path: literal(path),
                code_error_read_by_step: literal(reason == "gives_otherwise" ? REVIEWED_CODE : null)]))

        then:
        refusedBy == refusal

        where:
        [reason, path] << [ABOUT_A_FIELD + ABOUT_NO_FIELD, ["lines.sku", null]].combinations()
        refusal = (path != null) == (reason in ABOUT_A_FIELD) ? null : "productions_code_error_path_as_its_reason_names"
    }

    def "what reads a field that no longer matches is named, once, exactly where that is why code went wrong"() {
        when:
        def refusedBy = attempt("productions", insertInto("productions", A_CODE_FAULT + [
                code_error_reason: literal(reason), code_error_path: literal(path),
                code_error_read_by_step: literal(step), code_error_read_by_output: literal(output)]))

        then:
        refusedBy == refusal

        where:
        reason                | path      | step          | output    || refusal
        "gives_otherwise"     | "receipt" | REVIEWED_CODE | null      || null
        "gives_otherwise"     | "receipt" | null          | "result"  || null
        "gives_otherwise"     | "receipt" | null          | null      || "productions_code_error_read_by_exactly_for_gives_otherwise"
        "gives_otherwise"     | "receipt" | REVIEWED_CODE | "result"  || "productions_code_error_read_by_exactly_for_gives_otherwise"
        "takes_otherwise"     | "receipt" | REVIEWED_CODE | null      || "productions_code_error_read_by_exactly_for_gives_otherwise"
        "gives_a_list_not_here" | "grade" | null          | "result"  || "productions_code_error_read_by_exactly_for_gives_otherwise"
        "failed_on_this_side" | null      | null          | "result"  || "productions_code_error_read_by_exactly_for_gives_otherwise"
        "gives_otherwise"     | "receipt" | ABSENT        | null      || "productions_code_error_read_by_step_fk"
    }

    /** At the first level nothing holds it, so the field it names is none. */
    def "a member nothing declares is named exactly where that is the reason, wherever it is held"() {
        when:
        def refusedBy = attempt("productions", insertInto("productions", A_CODE_FAULT + [
                code_error_reason: literal(reason), code_error_path: literal(path), code_error_member: literal(member)]))

        then:
        refusedBy == refusal

        where:
        reason         | path      | member  || refusal
        "not_declared" | null      | "extra" || null
        "not_declared" | "lines"   | "extra" || null
        "not_declared" | "lines"   | ""      || null
        "not_declared" | null      | null    || "productions_code_error_member_exactly_for_not_declared"
        "too_long"     | "receipt" | "extra" || "productions_code_error_member_exactly_for_not_declared"
        "gave_nothing" | null      | ""      || "productions_code_error_member_exactly_for_not_declared"
    }

    def "#column is a path of field names"() {
        when:
        def refusedBy = attempt("productions", insertInto("productions", A_CODE_FAULT_READ + [
                code_error_read_by_step: "null", code_error_read_by_output: "'result'", (column): literal(path)]))

        then:
        refusedBy == (holds ? null : shape)

        where:
        [column, shape, path, holds] << [PATHS_IN_PRODUCTIONS.collect { it.take(2) }, PATHS].combinations()
                .collect { it.flatten() }
    }

    /** Counted as the store counts characters; the longest held is the longest path any declaration holds. */
    def "#column is at most 1023 characters, one past refused"() {
        when:
        def refusedBy = attempt("productions", insertInto("productions", A_CODE_FAULT_READ + [
                code_error_read_by_step: "null", code_error_read_by_output: "'result'", (column): written]))

        then:
        refusedBy == (held ? null : bound)

        where:
        [column, bound, written, held] << [PATHS_IN_PRODUCTIONS.collect { [it[0], it[2]] },
                                           [["'a' || repeat('.a', 511)", true], ["'ab' || repeat('.a', 511)", false]]]
                .combinations().collect { it.flatten() }
    }

    /** The store holds the line to no control, a tab and a line feed among them; the rest one line refuses, cleaning takes out. */
    def "a member's name holds no control character, and at most 64 characters and the one marking a cut"() {
        when:
        def refusedBy = attempt("productions", insertInto("productions", A_CODE_FAULT + [
                code_error_reason: "'not_declared'", code_error_member: member]))

        then:
        refusedBy == refusal

        where:
        member                             || refusal
        "repeat('a', 64) || chr(8230)"     || null
        "repeat(chr(128512), 65)"          || null
        "'a' || chr(8203) || 'b'"          || null
        "'a b'"                            || null
        "repeat('a', 66)"                  || "productions_code_error_member_bounded"
        "'a' || chr(9) || 'b'"             || "productions_code_error_member_one_line"
        "'a' || chr(10) || 'b'"            || "productions_code_error_member_one_line"
        "'a' || chr(127) || 'b'"           || "productions_code_error_member_one_line"
        "'a' || chr(133) || 'b'"           || "productions_code_error_member_one_line"
    }

    def "a person's try names an answered question to the helper asked in its own tree, whoever asked it"() {
        when:
        def refusedBy = attempt("productions", first + insertInto("productions", aTry(step, "person") + [
                run_help_exchange_id: literal(exchange)]))

        then:
        refusedBy == refusal

        where:
        first                 | step          | exchange       || refusal
        ""                    | CODE_RUN_STEP | HELP           || null
        ""                    | RUN_STEP      | HELP           || "productions_help_exchange_fk"
        ASK_WITHOUT_AN_ANSWER | CODE_RUN_STEP | SPARE_EXCHANGE || "productions_help_exchange_fk"
    }

    def "only a person's try names a question to the helper"() {
        when:
        def refusedBy = attempt("productions", insertInto("productions", aTry(CODE_RUN_STEP, producer) + [
                run_help_exchange_id: literal(HELP)]))

        then:
        refusedBy == refusal

        where:
        producer || refusal
        "person" || null
        "code"   || "productions_help_exchange_only_for_person"
    }

    def "a question's value is of a field its pinned version gives back that nothing holds, standing as that field does"() {
        when:
        def refusedBy = attempt("production_values", insertInto("production_values", A_VALUE + [
                production_id: literal(production), declaration_field_id: literal(field), field_standing: literal(standing),
                standing_threshold: literal(threshold)]))

        then:
        refusedBy == refusal

        where:
        production      | field  | standing           | threshold || refusal
        ANSWERED        | ANSWER | "above_confidence" | 70        || null
        REVIEWED_ANSWER | PARENT | "never"            | null      || null
        REVIEWED_ANSWER | FIELD  | "never"            | null      || "production_values_field_fk"
        REVIEWED_ANSWER | PARENT | "always"           | null      || "production_values_field_fk"
        ANSWERED        | ANSWER | "above_confidence" | 99        || "production_values_threshold_fk"
    }

    def "a code step's value names its field, and stands as the release declared"() {
        when:
        def refusedBy = attempt("production_values", insertInto("production_values", A_CODE_VALUE + [
                declaration_field_id: literal(field), field_standing: literal(standing),
                standing_threshold: literal(threshold)]))

        then:
        refusedBy == refusal

        where:
        field  | standing           | threshold || refusal
        null   | "always"           | null      || null
        null   | "never"            | null      || null
        null   | "above_confidence" | 50        || null
        null   | "never"            | 50        || "production_values_threshold_exactly_for_above_confidence"
        ANSWER | "always"           | null      || "production_values_field_exactly_for_question"
    }

    def "a value is of a try that gave something back"() {
        when:
        def refusedBy = attempt("production_values", insertInto("production_values", value +
                [production_id: literal(production)]))

        then:
        refusedBy == refusal

        where:
        value        | production      || refusal
        A_VALUE      | ANSWERED        || null
        A_CODE_VALUE | CODE_PRODUCTION || null
        A_VALUE      | PENDING         || "production_values_production_fk"
        A_CODE_VALUE | CODE_FAILED     || "production_values_production_fk"
    }

    def "a value is at most 50331648 characters as the store writes it"() {
        when:
        def refusedBy = attempt("production_values", insertInto("production_values", A_VALUE + [value: value]))

        then:
        refusedBy == refusal

        where:
        value                                           || refusal
        "to_jsonb(repeat(chr(1), 8388607) || 'aaaa')"  || null
        "to_jsonb(repeat(chr(1), 8388607) || 'aaaaa')" || "production_values_value_bounded"
    }

    def "a model says how sure it is exactly where a value stands above a confidence, and a person and code never do"() {
        when:
        def refusedBy = attempt("production_values", first + insertInto("production_values", value + [
                production_id: literal(production), producer: literal(producer), declaration_field_id: literal(field),
                field_standing: literal(standing), standing_threshold: literal(threshold), confidence: literal(confidence)]))

        then:
        refusedBy == refusal

        where:
        first           | value        | production      | producer | field  | standing           | threshold | confidence || refusal
        DROP_SURE_VALUE | A_VALUE      | PRODUCTION      | "model"  | ANSWER | "above_confidence" | 70        | 80         || null
        ""              | A_VALUE      | REVIEWED_ANSWER | "person" | ANSWER | "above_confidence" | 70        | null       || null
        ""              | A_CODE_VALUE | CODE_PRODUCTION | "code"   | null   | "above_confidence" | 50        | null       || null
        DROP_SURE_VALUE | A_VALUE      | PRODUCTION      | "model"  | ANSWER | "above_confidence" | 70        | null       || "production_values_confidence_exactly_for_model_above_confidence"
        ""              | A_VALUE      | PRODUCTION      | "model"  | PARENT | "never"            | null      | 90         || "production_values_confidence_exactly_for_model_above_confidence"
        ""              | A_VALUE      | REVIEWED_ANSWER | "person" | ANSWER | "above_confidence" | 70        | 80         || "production_values_confidence_exactly_for_model_above_confidence"
        ""              | A_CODE_VALUE | CODE_PRODUCTION | "code"   | null   | "above_confidence" | 50        | 80         || "production_values_confidence_exactly_for_model_above_confidence"
    }

    /** At the threshold is enough; a person offers no confidence, and code none either. */
    def "a value needs a review where it stands never, or above a confidence it did not reach or did not offer"() {
        when:
        def needs = readInside(first + insertInto("production_values", value + [
                production_value_id: literal(SPARE_VALUE), production_id: literal(production), producer: literal(producer),
                declaration_field_id: literal(field), field_standing: literal(standing),
                standing_threshold: literal(threshold), confidence: literal(confidence)]),
                "select needs_review::text from app.production_values where production_value_id = '${SPARE_VALUE}'")

        then:
        needs == needsReview.toString()

        where:
        first           | value        | production      | producer | field  | standing           | threshold | confidence || needsReview
        DROP_SURE_VALUE | A_VALUE      | PRODUCTION      | "model"  | ANSWER | "above_confidence" | 70        | 80         || false
        DROP_SURE_VALUE | A_VALUE      | PRODUCTION      | "model"  | ANSWER | "above_confidence" | 70        | 70         || false
        DROP_SURE_VALUE | A_VALUE      | PRODUCTION      | "model"  | ANSWER | "above_confidence" | 70        | 69         || true
        ""              | A_VALUE      | REVIEWED_ANSWER | "person" | ANSWER | "above_confidence" | 70        | null       || true
        ""              | A_VALUE      | REVIEWED_ANSWER | "person" | PARENT | "never"            | null      | null       || true
        ""              | A_CODE_VALUE | CODE_PRODUCTION | "code"   | null   | "always"           | null      | null       || false
        ""              | A_CODE_VALUE | CODE_PRODUCTION | "code"   | null   | "never"            | null      | null       || true
        ""              | A_CODE_VALUE | CODE_PRODUCTION | "code"   | null   | "above_confidence" | 50        | null       || true
    }

    def "a value used is recorded against one try, or one run a route started, and never both or neither"() {
        when:
        def refusedBy = attempt("production_inputs", first + insertInto("production_inputs", use + [
                production_id: literal(production), branch_run_id: literal(branchRun)]))

        then:
        refusedBy == refusal

        where:
        first                 | use          | production      | branchRun || refusal
        ""                    | A_USE        | REVIEWED_ANSWER | null      || null
        DROP_THE_BRANCH_INPUT | A_BRANCH_USE | null            | SUB_RUN   || null
        ""                    | A_USE        | null            | null      || "production_inputs_exactly_one_owner"
        DROP_THE_BRANCH_INPUT | A_BRANCH_USE | REVIEWED_ANSWER | SUB_RUN   || "production_inputs_exactly_one_owner"
    }

    def "a try records only what its own step was given, in the run that step is in, and never a discriminator"() {
        when:
        def refusedBy = attempt("production_inputs", insertInto("production_inputs", AN_INPUT_USE + [
                production_id: literal(production), run_step_id: literal(runStep), workflow_step_id: literal(step),
                binding_id: literal(binding)]))

        then:
        refusedBy == refusal

        where:
        production      | runStep           | step     | binding       || refusal
        ANSWERED        | RUN_STEP          | STEP     | BINDING       || null
        REVIEWED_ANSWER | REVIEWED_RUN_STEP | REVIEWED | BINDING       || "production_inputs_binding_fk"
        ANSWERED        | RUN_STEP          | STEP     | DISCRIMINATOR || "production_inputs_binding_fk"
        REVIEWED_ANSWER | RUN_STEP          | STEP     | BINDING       || "production_inputs_try_fk"
        REVIEWED_ANSWER | REVIEWED_RUN_STEP | STEP     | BINDING       || "production_inputs_step_fk"
    }

    def "a run a route started records the value it was chosen by, through that route's discriminator alone"() {
        when:
        def refusedBy = attempt("production_inputs", first + insertInto("production_inputs", A_BRANCH_USE + [
                branch_run_id: literal(branchRun), binding_id: literal(binding)]))

        then:
        refusedBy == refusal

        where:
        first                                              | branchRun | binding       || refusal
        DROP_THE_BRANCH_INPUT                              | SUB_RUN   | DISCRIMINATOR || null
        DROP_THE_BRANCH_INPUT + BIND_AN_INPUT_OF_THE_ROUTE | SUB_RUN   | SPARE_BINDING || "production_inputs_binding_fk"
        START_A_RUN_BENEATH                                | SPARE_RUN | DISCRIMINATOR || "production_inputs_branch_run_fk"
        ""                                                 | OTHER_RUN | DISCRIMINATOR || "production_inputs_branch_run_fk"
    }

    def "a binding reading a step names the value it made, one reading the input may name none, and a constant none"() {
        when:
        def refusedBy = attempt("production_inputs", insertInto("production_inputs", use + [
                source_workflow_step_id: literal(sourceStep), source_run_step_id: literal(sourceRunStep),
                source_run_step_kind: literal(sourceKind), source_production_id: literal(production),
                source_production_value_id: literal(value)]))

        then:
        refusedBy == refusal

        where:
        use                             | sourceStep | sourceRunStep | sourceKind | production | value || refusal
        A_USE                           | STEP       | RUN_STEP      | "question" | PRODUCTION | VALUE || null
        AN_INPUT_USE                    | null       | null          | null       | null       | null  || null
        THE_CONSTANT_GIVEN_TO_THE_MODEL | null       | null          | null       | null       | null  || null
        A_USE                           | null       | null          | null       | null       | null  || "production_inputs_binding_fk"
        A_USE                           | STEP       | RUN_STEP      | "question" | null       | null  || "production_inputs_source_value_for_a_step"
        THE_CONSTANT_GIVEN_TO_THE_MODEL | null       | null          | null       | PRODUCTION | VALUE || "production_inputs_no_source_value_for_a_constant"
    }

    /** Only a run beneath another is started with what a step made; which run of its tree made it no key here says. */
    def "a run's input carries a value made in its own tree, and a run at the top is started with none"() {
        when:
        def refusedBy = attempt("production_inputs", first + insertInto("production_inputs", use + [
                source_production_id: literal(production), source_production_value_id: literal(value)]))

        then:
        refusedBy == refusal

        where:
        first              | use           | production      | value      || refusal
        WITH_A_TRY_BENEATH | A_USE_BENEATH | PRODUCTION      | VALUE      || null
        WITH_A_TRY_BENEATH | A_USE_BENEATH | null            | null       || null
        WITH_A_TRY_BENEATH | A_USE_BENEATH | CODE_PRODUCTION | CODE_VALUE || "production_inputs_source_root_fk"
        ""                 | AN_INPUT_USE  | null            | null       || null
        ""                 | AN_INPUT_USE  | PRODUCTION      | VALUE      || "production_inputs_no_source_value_for_a_top_level_input"
        ""                 | A_USE         | PRODUCTION      | VALUE      || null
    }

    def "a run's input is recorded against the tree the run is in"() {
        when:
        def refusedBy = attempt("production_inputs", insertInto("production_inputs", AN_INPUT_USE + [
                root_run_id: literal(root)]))

        then:
        refusedBy == refusal

        where:
        root      || refusal
        RUN       || null
        OTHER_RUN || "production_inputs_run_fk"
        SUB_RUN   || "production_inputs_run_fk"
    }

    def "a value a question or code step made is traced to a try of that step, in the run the value was used in"() {
        when:
        def refusedBy = attempt("production_inputs", insertInto("production_inputs", A_USE + [
                binding_id: literal(binding), source_workflow_step_id: literal(sourceStep),
                source_production_id: literal(production), source_production_value_id: literal(value)]))

        then:
        refusedBy == refusal

        where:
        binding           | sourceStep | production      | value          || refusal
        REVIEWED_BINDING  | STEP       | PRODUCTION      | VALUE          || null
        REVIEWED_BINDING  | STEP       | ANSWERED        | ANSWERED_VALUE || null
        REVIEWED_BINDING  | STEP       | PRODUCTION      | ANSWERED_VALUE || "production_inputs_source_value_fk"
        REVIEWED_BINDING  | STEP       | CODE_PRODUCTION | CODE_VALUE     || "production_inputs_source_production_fk"
        ESCALATED_BINDING | STEP       | PRODUCTION      | VALUE          || "production_inputs_source_step_fk"
        ESCALATED_BINDING | ESCALATE   | PRODUCTION      | VALUE          || "production_inputs_source_run_step_fk"
    }

    /** Which run beneath made the value no key here says, only that it is of the tree the value was used in. */
    def "a value a workflow or route gave back is traced beneath it within the tree, or to none at all"() {
        when:
        def refusedBy = attempt("production_inputs", first + insertInto("production_inputs", A_USE + [
                binding_id: literal(binding), source_workflow_step_id: literal(sourceStep),
                source_run_step_id: literal(sourceRunStep), source_run_step_kind: literal(sourceKind),
                source_production_id: literal(production), source_production_value_id: literal(value)]))

        then:
        refusedBy == refusal

        where:
        first                            | binding           | sourceStep | sourceRunStep     | sourceKind | production       | value       || refusal
        WITH_A_VALUE_ESCALATE_GAVE_BACK  | ESCALATED_BINDING | ESCALATE   | ESCALATE_RUN_STEP | "workflow" | SPARE_PRODUCTION | SPARE_VALUE || null
        WITH_A_VALUE_THE_ROUTE_GAVE_BACK | SPARE_BINDING     | ROUTE      | ROUTE_RUN_STEP    | "route"    | SPARE_PRODUCTION | SPARE_VALUE || null
        ""                               | ESCALATED_BINDING | ESCALATE   | ESCALATE_RUN_STEP | "workflow" | null             | null        || null
        ""                               | ESCALATED_BINDING | ESCALATE   | ESCALATE_RUN_STEP | "workflow" | CODE_PRODUCTION  | CODE_VALUE  || "production_inputs_source_root_fk"
        WITH_A_VALUE_THE_ROUTE_GAVE_BACK | SPARE_BINDING     | ROUTE      | ROUTE_RUN_STEP    | "route"    | CODE_PRODUCTION  | CODE_VALUE  || "production_inputs_source_root_fk"
        WITH_A_VALUE_ESCALATE_GAVE_BACK  | REVIEWED_BINDING  | STEP       | RUN_STEP          | "question" | SPARE_PRODUCTION | SPARE_VALUE || "production_inputs_source_production_fk"
    }

    def "a value is recorded once for each owner and binding, and the same binding again for another try"() {
        when:
        def refusedBy = attempt("production_inputs", first + insertInto("production_inputs", use))

        then:
        refusedBy == refusal

        where:
        first                 | use                             || refusal
        ""                    | THE_CONSTANT_GIVEN_TO_THE_MODEL || null
        DROP_THE_BRANCH_INPUT | A_BRANCH_USE                    || null
        ""                    | A_CONSTANT_USE                  || "production_inputs_try_binding_unique"
        ""                    | A_BRANCH_USE                    || "production_inputs_branch_binding_unique"
    }

    def "a binding a value was recorded through keeps what it fills and what it reads from"() {
        when:
        def refusedBy = attempt("bindings", "update app.bindings set ${assignments} where binding_id = '${binding}'")

        then:
        refusedBy == refusal

        where:
        binding          | assignments                                 || refusal
        BINDING          | "source_path = null, constant = '\"x\"'"    || null
        CONSTANT_BINDING | "constant = null, source_path = 'channel'"  || "production_inputs_binding_fk"
        DISCRIMINATOR    | "source_step_id = '${ESCALATE}'"            || "production_inputs_source_step_fk"
    }

    def "a person reviews where no model is named to, the model named where one is, and a person refuses for length either way"() {
        when:
        def refusedBy = attempt("reviews", first + insertInto("reviews", aReviewOf(production) + [
                created_by: literal(author), created_by_kind: literal(kind), model_call_id: literal(call),
                model_call_outcome: literal(outcome), run_step_hold_id: literal(hold), hold_run_id: literal(holdRun)]))

        then:
        refusedBy == refusal

        where:
        first                  | production      | author          | kind     | call        | outcome     | hold | holdRun || refusal
        DROP_THE_ANSWER_REVIEW | ANSWERED        | STEWARD         | "person" | null        | null        | null | null    || null
        ""                     | ANSWERED        | STEWARD         | "person" | null        | null        | HOLD | RUN     || null
        ""                     | REVIEWED_ANSWER | STEWARD         | "person" | null        | null        | HOLD | RUN     || null
        DROP_THE_MODEL_REVIEW  | REVIEWED_ANSWER | WORKFLOW_RUNNER | "system" | REVIEW_CALL | "came_back" | null | null    || null
        ""                     | REVIEWED_ANSWER | STEWARD         | "person" | null        | null        | null | null    || "reviews_author_is_system_exactly_for_model_on_review"
        ""                     | REVIEWED_ANSWER | WORKFLOW_RUNNER | "system" | REVIEW_CALL | "came_back" | HOLD | RUN     || "reviews_author_is_system_exactly_for_model_on_review"
        ""                     | ANSWERED        | WORKFLOW_RUNNER | "system" | REVIEW_CALL | "came_back" | null | null    || "reviews_author_is_system_exactly_for_model_on_review"
    }

    /** What the model would be sent being too long is no hold on the step: the production waits on a person instead. */
    def "a person reviews on a step a model reviews only in its place, where what it would be sent was too long"() {
        when:
        def refusedBy = attempt("reviews", insertInto("reviews", aReviewOf(LONG_ANSWER) + [
                created_by: literal(author), created_by_kind: literal(kind), model_call_id: literal(call),
                model_call_outcome: literal(outcome), too_long_attempt_id: literal(tooLong)]))

        then:
        refusedBy == refusal

        where:
        author          | kind     | call        | outcome     | tooLong             || refusal
        STEWARD         | "person" | null        | null        | HELD_REVIEW_ATTEMPT || null
        STEWARD         | "person" | null        | null        | null                || "reviews_author_is_system_exactly_for_model_on_review"
        WORKFLOW_RUNNER | "system" | REVIEW_CALL | "came_back" | HELD_REVIEW_ATTEMPT || "reviews_author_is_system_exactly_for_model_on_review"
    }

    def "a review in the model's place names an attempt to review that production which was too long to send"() {
        when:
        def refusedBy = attempt("reviews", first + insertInto("reviews", aReviewOf(production) + [
                too_long_attempt_id: literal(tooLong)]))

        then:
        refusedBy == refusal

        where:
        first                  | production      | tooLong             || refusal
        ""                     | LONG_ANSWER     | HELD_REVIEW_ATTEMPT || null
        DROP_THE_MODEL_REVIEW  | REVIEWED_ANSWER | HELD_REVIEW_ATTEMPT || "reviews_too_long_attempt_fk"
        DROP_THE_MODEL_REVIEW  | REVIEWED_ANSWER | REVIEW_ATTEMPT      || "reviews_too_long_attempt_fk"
        DROP_THE_MODEL_REVIEW  | REVIEWED_ANSWER | LONG_ATTEMPT        || "reviews_too_long_attempt_fk"
        DROP_THE_ANSWER_REVIEW | ANSWERED        | HELD_REVIEW_ATTEMPT || "reviews_too_long_attempt_fk"
    }

    /**
     * A review that did not fit, went wrong or never came back spent the try as one that was taken would have. The
     * rows refused here name a call no row holds, which the unique index refuses before any key is looked up.
     */
    def "a production is reviewed once, whoever reviewed it and whatever became of that, and refused for length beside"() {
        when:
        def refusedBy = attempt("reviews", first + insertInto("reviews", aReviewOf(production) + [
                created_by: literal(author), created_by_kind: literal(kind), model_call_id: literal(call),
                model_call_outcome: literal(outcome), run_step_hold_id: literal(hold), hold_run_id: literal(holdRun),
                too_long_attempt_id: literal(tooLong)]))

        then:
        refusedBy == refusal

        where:
        first                       | production      | author          | kind     | call        | outcome     | hold | holdRun | tooLong             || refusal
        DROP_THE_MODEL_REVIEW       | REVIEWED_ANSWER | WORKFLOW_RUNNER | "system" | REVIEW_CALL | "came_back" | null | null    | null                || null
        ""                          | REVIEWED_ANSWER | STEWARD         | "person" | null        | null        | HOLD | RUN     | null                || null
        ""                          | LONG_ANSWER     | STEWARD         | "person" | null        | null        | null | null    | HELD_REVIEW_ATTEMPT || null
        ""                          | REVIEWED_ANSWER | WORKFLOW_RUNNER | "system" | ABSENT      | "came_back" | null | null    | null                || "reviews_one_on_review"
        THE_MODEL_REVIEW_WENT_WRONG | REVIEWED_ANSWER | WORKFLOW_RUNNER | "system" | ABSENT      | "came_back" | null | null    | null                || "reviews_one_on_review"
        STEWARD_IN_THE_MODELS_PLACE | LONG_ANSWER     | WORKFLOW_RUNNER | "system" | ABSENT      | "came_back" | null | null    | null                || "reviews_one_on_review"
    }

    def "a person never reviews what they produced, and a model may review what it produced itself"() {
        when:
        def refusedBy = attempt("reviews", first + insertInto("reviews", aReviewOf(production) + [
                created_by: literal(author), created_by_kind: literal(kind), model_call_id: literal(call),
                model_call_outcome: literal(outcome), run_step_hold_id: literal(hold), hold_run_id: literal(holdRun),
                too_long_attempt_id: literal(tooLong)]))

        then:
        refusedBy == refusal

        where:
        first                       | production       | author          | kind     | call              | outcome     | hold | holdRun | tooLong             || refusal
        ""                          | CODE_PRODUCTION  | MEMBER          | "person" | null              | null        | null | null    | null                || null
        ""                          | ANSWERED         | MEMBER          | "person" | null              | null        | HOLD | RUN     | null                || null
        aModelTryAndItsReviewCall() | SPARE_PRODUCTION | WORKFLOW_RUNNER | "system" | SPARE_REVIEW_CALL | "came_back" | null | null    | null                || null
        ""                          | ANSWERED         | MEMBER          | "person" | null              | null        | null | null    | null                || "reviews_reviewer_is_not_producer"
        ""                          | LONG_ANSWER      | MEMBER          | "person" | null              | null        | null | null    | HELD_REVIEW_ATTEMPT || "reviews_reviewer_is_not_producer"
    }

    /** A refusal that says nothing of why came back and did not fit; only a call that went wrong makes a review so. */
    def "a model's review ends as its call did, and one that came back may still not fit"() {
        when:
        def refusedBy = attempt("reviews", first + insertInto("reviews",
                A_MODEL_REVIEW + [model_call_outcome: literal(outcome), lost_reason: literal(lost),
                                  did_not_fit_reason: literal(lost == "did_not_fit" ? "undecided" : null)]))

        then:
        refusedBy == refusal

        where:
        first                      | outcome     | lost                || refusal
        DROP_THE_MODEL_REVIEW      | "came_back" | null                || null
        DROP_THE_MODEL_REVIEW      | "came_back" | "did_not_fit"       || null
        THE_REVIEW_CALL_WENT_WRONG | "errored"   | "errored"           || null
        DROP_THE_MODEL_REVIEW      | "came_back" | "errored"           || "reviews_lost_as_call_ended"
        DROP_THE_MODEL_REVIEW      | "came_back" | "nothing_came_back" || "reviews_lost_as_call_ended"
        THE_REVIEW_CALL_WENT_WRONG | "errored"   | "did_not_fit"       || "reviews_lost_as_call_ended"
        THE_REVIEW_CALL_WENT_WRONG | "errored"   | null                || "reviews_lost_as_call_ended"
    }

    def "a model's review whose answer is not kept as it came back ends as one that did not fit"() {
        when:
        def refusedBy = attempt("reviews", DROP_THE_MODEL_REVIEW +
                "update app.model_calls set answer_altered = ${altered} where model_call_id = '${REVIEW_CALL}'; " +
                insertInto("reviews", A_MODEL_REVIEW + [call_answer_altered: carried, lost_reason: literal(lost),
                                                        did_not_fit_reason: literal(reason)]))

        then:
        refusedBy == refusal

        where:
        altered | carried | lost          | reason                || refusal
        "true"  | "true"  | "did_not_fit" | "not_kept_as_it_came" || null
        "true"  | "true"  | null          | null                  || "reviews_altered_answer_did_not_fit"
        "true"  | "false" | "did_not_fit" | "undecided"           || "reviews_model_call_fk"
        "false" | "true"  | "did_not_fit" | "not_kept_as_it_came" || "reviews_model_call_fk"
    }

    /** As a try's is: found once as what came back was read, and never read again from it. */
    def "a model's review says why it did not fit exactly where it did not, and that it was not kept only where it was not"() {
        when:
        def refusedBy = attempt("reviews", first +
                "update app.model_calls set answer_altered = ${altered} where model_call_id = '${REVIEW_CALL}'; " +
                insertInto("reviews", A_MODEL_REVIEW + [model_call_outcome: literal(outcome), call_answer_altered: altered,
                                                        lost_reason: literal(lost), did_not_fit_reason: literal(reason)]))

        then:
        refusedBy == refusal

        where:
        first                      | outcome     | altered | lost          | reason                || refusal
        DROP_THE_MODEL_REVIEW      | "came_back" | "false" | "did_not_fit" | "undecided"           || null
        DROP_THE_MODEL_REVIEW      | "came_back" | "true"  | "did_not_fit" | "words_missing"       || null
        DROP_THE_MODEL_REVIEW      | "came_back" | "true"  | "did_not_fit" | "not_kept_as_it_came" || null
        DROP_THE_MODEL_REVIEW      | "came_back" | "false" | "did_not_fit" | null                  || "reviews_did_not_fit_reason_exactly_for_did_not_fit"
        DROP_THE_MODEL_REVIEW      | "came_back" | "false" | null          | "undecided"           || "reviews_did_not_fit_reason_exactly_for_did_not_fit"
        THE_REVIEW_CALL_WENT_WRONG | "errored"   | "false" | "errored"     | "undecided"           || "reviews_did_not_fit_reason_exactly_for_did_not_fit"
        DROP_THE_MODEL_REVIEW      | "came_back" | "false" | "did_not_fit" | "not_kept_as_it_came" || "reviews_not_kept_only_when_altered"
    }

    def "a review is of a try that gave something back"() {
        when:
        def refusedBy = attempt("reviews", insertInto("reviews", aReviewOf(production)))

        then:
        refusedBy == refusal

        where:
        production      || refusal
        CODE_PRODUCTION || null
        CODE_FAILED     || "reviews_production_fk"
    }

    /** The hold may be in a run beneath the one that made the value, which is where a value started with is carried. */
    def "a refusal for length names a hold in the tree of the production it refuses"() {
        when:
        def refusedBy = attempt("reviews", first + insertInto("reviews", aReviewOf(production) + [
                run_step_hold_id: literal(hold), hold_run_id: literal(holdRun)]))

        then:
        refusedBy == refusal

        where:
        first                 | production      | hold       | holdRun || refusal
        ""                    | ANSWERED        | HOLD       | RUN     || null
        HOLD_THE_STEP_BENEATH | ANSWERED        | SPARE_HOLD | SUB_RUN || null
        HOLD_THE_STEP_BENEATH | CODE_PRODUCTION | SPARE_HOLD | SUB_RUN || "reviews_hold_run_fk"
        ""                    | ANSWERED        | HOLD       | SUB_RUN || "reviews_hold_fk"
    }

    def "a refusal for length names a hold on length and the run it is in, or neither"() {
        when:
        def refusedBy = attempt("reviews", DROP_THE_ANSWER_REVIEW + insertInto("reviews", aReviewOf(ANSWERED) + [
                run_step_hold_id: literal(hold), hold_run_id: literal(holdRun), hold_reason: literal(reason)]))

        then:
        refusedBy == refusal

        where:
        hold | holdRun | reason        || refusal
        null | null    | "too_long"    || null
        HOLD | RUN     | "too_long"    || null
        HOLD | null    | "too_long"    || "reviews_hold_together"
        null | RUN     | "too_long"    || "reviews_hold_together"
        HOLD | RUN     | "turned_away" || "reviews_hold_is_too_long"
    }

    def "a value is decided on review only where it needs a review, and refused whatever it needed where it is for length"() {
        when:
        def refusedBy = attempt("review_decisions", first + insertInto("review_decisions", A_DECISION + [
                review_id: literal(review), production_value_id: literal(value), production_id: literal(production),
                for_length: forLength, value_needs_review: needs, outcome: literal(outcome),
                explanation: literal(explanation), assured_in_review_id: "null"]))

        then:
        refusedBy == refusal

        where:
        first                        | review       | value          | production | forLength | needs   | outcome   | explanation         || refusal
        REVIEW_THE_ANSWER_AGAIN      | SPARE_REVIEW | ANSWERED_VALUE | ANSWERED   | "false"   | "true"  | "assured" | null                || null
        REVIEW_THE_ANSWER_AGAIN      | SPARE_REVIEW | ANSWERED_VALUE | ANSWERED   | "false"   | "true"  | "refused" | "Too long to send." || null
        REFUSE_PRODUCTION_FOR_LENGTH | SPARE_REVIEW | SURE_VALUE     | PRODUCTION | "true"    | "false" | "refused" | "Too long to send." || null
        ""                           | REVIEW       | SURE_VALUE     | PRODUCTION | "false"   | "false" | "assured" | null                || "review_decisions_on_review_only_for_needing_review"
        REFUSE_PRODUCTION_FOR_LENGTH | SPARE_REVIEW | SURE_VALUE     | PRODUCTION | "true"    | "false" | "assured" | null                || "review_decisions_for_length_only_for_refused"
    }

    def "only a refusal says why"() {
        when:
        def refusedBy = attempt("review_decisions", REVIEW_THE_ANSWER_AGAIN + insertInto("review_decisions", A_DECISION +
                ON_REVIEW + [review_id: literal(SPARE_REVIEW), outcome: literal(outcome), explanation: literal(explanation)]))

        then:
        refusedBy == refusal

        where:
        outcome   | explanation || refusal
        "assured" | null        || null
        "refused" | "No order." || null
        "assured" | "Fine."     || "review_decisions_explanation_exactly_for_refused"
        "refused" | null        || "review_decisions_explanation_exactly_for_refused"
    }

    def "a value decided is one of the production reviewed, needing a review or not as it does, by a review that decided"() {
        when:
        def refusedBy = attempt("review_decisions", first + insertInto("review_decisions", A_DECISION + ON_REVIEW + [
                review_id: literal(review), production_value_id: literal(value), production_id: literal(production),
                for_length: forLength, value_needs_review: needs]))

        then:
        refusedBy == refusal

        where:
        first                                              | review       | value          | production      | forLength | needs  || refusal
        A_VALUE_OF_REVIEWED_ANSWER + REVIEWED_BY_THE_MODEL | SPARE_REVIEW | SPARE_VALUE    | REVIEWED_ANSWER | "false"   | "true" || null
        A_VALUE_OF_REVIEWED_ANSWER                         | MODEL_REVIEW | SPARE_VALUE    | REVIEWED_ANSWER | "false"   | "true" || "review_decisions_review_fk"
        ""                                                 | REVIEW       | SURE_VALUE     | PRODUCTION      | "false"   | "true" || "review_decisions_value_fk"
    }

    def "a value that needed a review is refused for length only once a review has assured it"() {
        when:
        def refusedBy = attempt("review_decisions", first + insertInto("review_decisions", A_DECISION + [
                review_id: literal(review), production_value_id: literal(value), production_id: literal(production),
                value_needs_review: needs, assured_in_review_id: literal(assuredIn)]))

        then:
        refusedBy == refusal

        where:
        first                        | review        | value          | production | needs   | assuredIn     || refusal
        ""                           | LENGTH_REVIEW | ANSWERED_VALUE | ANSWERED   | "true"  | ANSWER_REVIEW || null
        REFUSE_PRODUCTION_FOR_LENGTH | SPARE_REVIEW  | SURE_VALUE     | PRODUCTION | "false" | null          || null
        ""                           | LENGTH_REVIEW | ANSWERED_VALUE | ANSWERED   | "true"  | null          || "review_decisions_assurance_exactly_for_length_needing_review"
        REFUSE_PRODUCTION_FOR_LENGTH | SPARE_REVIEW  | SURE_VALUE     | PRODUCTION | "false" | ANSWER_REVIEW || "review_decisions_assurance_exactly_for_length_needing_review"
        DROP_THE_ASSURANCE           | LENGTH_REVIEW | ANSWERED_VALUE | ANSWERED   | "true"  | ANSWER_REVIEW || "review_decisions_assurance_fk"
    }

    def "a value decided on review may still be refused for length, and a value refused is never refused again"() {
        when:
        def refusedBy = attempt("review_decisions", first + insertInto("review_decisions", A_DECISION + [
                review_id: literal(review), production_value_id: literal(value), production_id: literal(production),
                assured_in_review_id: literal(assuredIn)]))

        then:
        refusedBy == refusal

        where:
        first                        | review        | value          | production | assuredIn     || refusal
        ""                           | LENGTH_REVIEW | ANSWERED_VALUE | ANSWERED   | ANSWER_REVIEW || null
        REFUSE_PRODUCTION_FOR_LENGTH | SPARE_REVIEW  | VALUE          | PRODUCTION | REVIEW        || "review_decisions_one_refusal"
    }

    def "a check is started by a person and by nobody else"() {
        when:
        def refusedBy = attempt("soundness_checks", insertInto("soundness_checks", AN_ENDED_CHECK + [
                created_by: literal(author), created_by_kind: literal(kind)]))

        then:
        refusedBy == refusal

        where:
        author          | kind     || refusal
        MEMBER          | "person" || null
        WORKFLOW_RUNNER | "system" || "soundness_checks_author_is_person"
        SEEDER          | "seeder" || "soundness_checks_author_is_person"
        WORKFLOW_RUNNER | "person" || "soundness_checks_author_person_fk"
    }

    def "one check runs at a time, and one finished or cut short lets the next start"() {
        when:
        def refusedBy = attempt("soundness_checks", first + insertInto("soundness_checks", A_RUNNING_CHECK))

        then:
        refusedBy == refusal

        where:
        first                           || refusal
        ""                              || "soundness_checks_one_running"
        endTheRunningCheck("finished")  || null
        endTheRunningCheck("cut_short") || null
    }

    def "any number of checks ended stand beside the one running"() {
        when:
        def refusedBy = attempt("soundness_checks", insertInto("soundness_checks", AN_ENDED_CHECK + [
                outcome: literal(outcome)]))

        then:
        refusedBy == null

        where:
        outcome << ["finished", "cut_short"]
    }

    def "a group is counted once in each check, and again in the next"() {
        when:
        def refusedBy = attempt("soundness_counts", first + insertInto("soundness_counts", A_COUNT + [
                soundness_check_id: literal(check), group_id: literal(group)]))

        then:
        refusedBy == refusal

        where:
        first                          | check         | group       || refusal
        ""                             | CHECK         | GROUP       || "soundness_counts_pk"
        ""                             | CHECK         | OTHER_GROUP || null
        endTheRunningCheck("finished") | RUNNING_CHECK | GROUP       || null
    }

    def "only a finished check holds counts, so one running or cut short holds none"() {
        when:
        def refusedBy = attempt("soundness_counts", first + insertInto("soundness_counts", A_COUNT + [
                soundness_check_id: literal(check)]))

        then:
        refusedBy == refusal

        where:
        first                           | check         || refusal
        ""                              | CHECK         || null
        ""                              | RUNNING_CHECK || "soundness_counts_check_fk"
        endTheRunningCheck("cut_short") | RUNNING_CHECK || "soundness_counts_check_fk"
    }

    def "a check holding counts stays finished"() {
        when:
        def refusedBy = attempt("soundness_checks", first + "update app.soundness_checks" +
                " set outcome = ${literal(outcome)} where soundness_check_id = '${CHECK}'")

        then:
        refusedBy == "soundness_counts_check_fk"

        where:
        first                          | outcome
        ""                             | "cut_short"
        endTheRunningCheck("finished") | null
    }

    def "neither a check holding counts nor a group counted is deleted from under them"() {
        when:
        def refusedBy = attempt(table, statement)

        then:
        refusedBy == refusal

        where:
        table              | statement              || refusal
        "soundness_checks" | DELETE_THE_CHECK       || "soundness_counts_check_fk"
        "groups"           | DELETE_A_COUNTED_GROUP || "soundness_counts_group_fk"
    }

    def "a group's workflows and unsound are none or more, and its unsound never more than its workflows"() {
        when:
        def refusedBy = attempt("soundness_counts", insertInto("soundness_counts", A_COUNT + [
                workflows: workflows, unsound: unsound]))

        then:
        refusedBy == refusal

        where:
        workflows | unsound || refusal
        "0"       | "0"     || null
        "3"       | "0"     || null
        "3"       | "3"     || null
        "3"       | "4"     || "soundness_counts_unsound_at_most_workflows"
        "-1"      | "0"     || "soundness_counts_unsound_at_most_workflows"
        "0"       | "-1"    || "soundness_counts_unsound_not_negative"
        "-1"      | "-1"    || "soundness_counts_unsound_not_negative"
    }

    def "a group's currency changes to any three capital English letters, and to nothing else"() {
        when:
        def refusedBy = attempt("group_currencies", setting("group_currencies",
                "currency = ${literal(currency)}, updated_at = now(), updated_by = '${STEWARD}'"))

        then:
        refusedBy == refusal

        where:
        currency                           || refusal
        "AAA"                              || null
        "USD"                              || null
        "ZZZ"                              || null
        ""                                 || "group_currencies_currency_shape"
        "US"                               || "group_currencies_currency_shape"
        "USDX"                             || "group_currencies_currency_shape"
        "usd"                              || "group_currencies_currency_shape"
        "Usd"                              || "group_currencies_currency_shape"
        "US1"                              || "group_currencies_currency_shape"
        "US@"                              || "group_currencies_currency_shape"
        "US["                              || "group_currencies_currency_shape"
        " USD"                             || "group_currencies_currency_shape"
        "USD" + Character.toString(0x000A) || "group_currencies_currency_shape"
        "US" + Character.toString(0x00C9)  || "group_currencies_currency_shape"
        "US" + Character.toString(0xFF21)  || "group_currencies_currency_shape"
        "US" + Character.toString(0x212A)  || "group_currencies_currency_shape"
        "US" + Character.toString(0x0130)  || "group_currencies_currency_shape"
    }

    /** Every lookup of the rows naming a target reads a key's columns. Keys into subjects are exempt: no subject
     * is ever removed, and those a page reads rows through are held to an index of their own by "every key into
     * subjects a page reads rows through leads an index, partial or not". */
    def "every foreign key leads an index on the table holding it"() {
        when:
        def unindexed = queryForStrings("""
            select t.relname || '.' || con.conname
              from pg_constraint con
              join pg_class t on t.oid = con.conrelid
              join pg_attribute a on a.attrelid = con.conrelid and a.attnum = con.conkey[1]
             where con.connamespace = 'app'::regnamespace and con.contype = 'f'
               and con.confrelid <> 'app.subjects'::regclass
               and not exists (
                   select 1 from pg_index i
                    where i.indrelid = con.conrelid and i.indkey[0] = con.conkey[1]
                      and (i.indpred is null
                           or pg_get_expr(i.indpred, i.indrelid) = '(' || a.attname || ' IS NOT NULL)'))
            """)
        def keys = queryForStrings("""
            select t.relname || '.' || con.conname
              from pg_constraint con
              join pg_class t on t.oid = con.conrelid
             where con.connamespace = 'app'::regnamespace and con.contype = 'f'
               and con.confrelid <> 'app.subjects'::regclass
            """)

        then:
        unindexed == UNINDEXED_ON_PURPOSE

        and: "judged over keys an index does lead"
        !(keys - unindexed).isEmpty()
    }

    def "every key into subjects a page reads rows through leads an index, partial or not"() {
        when:
        def indexed = queryForStrings("""
            select t.relname || '.' || con.conname
              from pg_constraint con
              join pg_class t on t.oid = con.conrelid
             where con.connamespace = 'app'::regnamespace and con.contype = 'f'
               and con.confrelid = 'app.subjects'::regclass
               and exists (select 1 from pg_index i where i.indrelid = con.conrelid and i.indkey[0] = con.conkey[1])
            """)

        then:
        indexed.containsAll(READ_BY_AUTHOR)

        and: "asserted of something, rather than passing because the set is empty"
        !READ_BY_AUTHOR.isEmpty()
    }

    /**
     * Three sources, each of which the other two cannot see.
     *
     * <p>A partial unique index is not a constraint and has no row in pg_constraint at all, and here
     * those are what decide that a thing is current, open or unended once at most. A domain's
     * constraint has a row but no table, so an inner join on pg_class drops it while a row that breaks
     * it is still refused by name. A trigger is neither, and a constraint trigger has a pg_constraint row this deliberately
     * skips so that it is counted once, from pg_trigger, where the ordinary ones also are.
     *
     * <p>A deny-list rather than an allow-list, so a contype this was written before arrives here
     * rather than disappearing. Two are denied on purpose. 't' is the constraint trigger, counted
     * from the third source instead. 'n' is NOT NULL, which PostgreSQL 18 began recording here, and
     * which no case could cover in the way every case here works: a not-null violation carries no
     * constraint name for the refusal to be identified by, so a suite built on asserting that name
     * would have to assert something weaker for those alone.
     */
    private Set<String> enforceableRules() {
        queryForStrings("""
            select coalesce(t.relname, d.typname) || '.' || con.conname
              from pg_constraint con
              left join pg_class t on t.oid = con.conrelid
              left join pg_type d on d.oid = con.contypid
             where con.connamespace = 'app'::regnamespace
               and con.contype not in ('n', 't')
               and coalesce(t.relname, '') <> 'flyway_schema_history'
            union
            select t.relname || '.' || idx.relname
              from pg_index i
              join pg_class idx on idx.oid = i.indexrelid
              join pg_class t on t.oid = i.indrelid
              join pg_namespace n on n.oid = idx.relnamespace
             where n.nspname = 'app'
               and i.indisunique
               and t.relname <> 'flyway_schema_history'
               and not exists (select 1 from pg_constraint x where x.conindid = i.indexrelid)
            union
            select t.relname || '.' || tg.tgname
              from pg_trigger tg
              join pg_class t on t.oid = tg.tgrelid
              join pg_namespace n on n.oid = t.relnamespace
             where n.nspname = 'app'
               and not tg.tgisinternal
               and t.relname <> 'flyway_schema_history'
            """)
    }

    private boolean subsumesThePrimaryKeyOf(String rule) {
        def (table, constraint) = rule.split("\\.", 2)
        queryForStrings("""
            select 'subsumed'
              from pg_constraint u
              join pg_class t on t.oid = u.conrelid
              join pg_constraint p on p.conrelid = u.conrelid and p.contype = 'p'
             where u.connamespace = 'app'::regnamespace
               and t.relname = '${table}'
               and u.conname = '${constraint}'
               and u.contype = 'u'
               and p.conkey <@ u.conkey
               and p.conkey <> u.conkey
            """) == ["subsumed"] as Set
    }

    /**
     * The whole of a table rather than its cardinality, because half of these cases are updates and
     * an update that lands leaves the count alone. What it does not see is a write to any other
     * table — a trigger's or a cascade's — so it is a probe on the table the case names and not a
     * statement that nothing anywhere moved. Nor is it a guard against a partial write: every case
     * in CASES is one statement, and one statement is atomic.
     */
    private String contentsOf(String table) {
        queryForStrings("select coalesce(md5(string_agg(t::text, '|' order by t::text)), 'empty')" +
                " from app.${table} t").first()
    }

    /**
     * Run inside a transaction that is always rolled back, so a row admitted here is there for no later
     * case. Deferred rules are brought forward before it is judged, or they would be judged by nobody.
     */
    private String attempt(String table, String statement) {
        def before = contentsOf(table)
        session.autoCommit = false
        try {
            execute(statement)
            execute("set constraints all immediate")
            assert contentsOf(table) != before, "admitted, yet ${table} holds what it held before"
            null
        } catch (PSQLException refused) {
            def rule = refused.serverErrorMessage?.constraint
            if (rule == null) {
                throw refused
            }
            rule
        } finally {
            session.rollback()
            session.autoCommit = true
        }
    }

    /** What a query reads back of a statement run inside a transaction that is always rolled back. */
    private String readInside(String statement, String query) {
        session.autoCommit = false
        try {
            execute(statement)
            queryForStrings(query).first()
        } finally {
            session.rollback()
            session.autoCommit = true
        }
    }

    private void execute(String statement) {
        session.createStatement().withCloseable { it.execute(statement) }
    }

    private Set<String> queryForStrings(String query) {
        session.createStatement().withCloseable { statement ->
            statement.executeQuery(query).withCloseable { rows ->
                def found = [] as Set
                while (rows.next()) {
                    found << rows.getString(1)
                }
                found
            }
        }
    }

    /**
     * A constraint name is unique within its table and not within the schema, so two tables may each
     * carry an is_person. The name a refusal reports is the bare one; what a population is counted
     * in has to be the qualified one, or two rules share an entry and one of them goes untested.
     */
    private static String ruleOf(Map<String, String> attack) {
        "${attack.table}.${attack.constraint}"
    }
}
