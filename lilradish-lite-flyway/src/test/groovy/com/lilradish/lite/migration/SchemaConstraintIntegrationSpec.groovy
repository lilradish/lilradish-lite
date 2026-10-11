package com.lilradish.lite.migration

import static com.lilradish.lite.migration.fixture.SchemaKeys.VERSION
import static com.lilradish.lite.migration.fixture.SchemaRows.*
import static com.lilradish.lite.migration.fixture.StepAndDeclarationCases.*

import com.lilradish.lite.migration.fixture.EntryCases
import com.lilradish.lite.migration.fixture.EstateRoleCases
import com.lilradish.lite.migration.fixture.FileCases
import com.lilradish.lite.migration.fixture.GroupCases
import com.lilradish.lite.migration.fixture.GroupCurrencyCases
import com.lilradish.lite.migration.fixture.LibraryCases
import com.lilradish.lite.migration.fixture.PeopleCases
import com.lilradish.lite.migration.fixture.PoolCases
import com.lilradish.lite.migration.fixture.ProductionAndCallCases
import com.lilradish.lite.migration.fixture.RunCases
import com.lilradish.lite.migration.fixture.SchemaConstraintSpecification
import com.lilradish.lite.migration.fixture.SearchQuestionCases
import com.lilradish.lite.migration.fixture.SoundnessCases
import com.lilradish.lite.migration.fixture.SourceCodeCases
import com.lilradish.lite.migration.fixture.StepAndDeclarationCases
import com.lilradish.lite.migration.fixture.SubjectCases
import com.lilradish.lite.migration.fixture.WorkflowLabelCases
import org.postgresql.util.PSQLException

/**
 * What the baseline's named rules refuse is proved here. Its migrations compile nothing and
 * are covered by nothing a unit test can reach, so what a constraint refuses is only known by asking a
 * server to refuse it — and the server asked is pinned to the version production runs, because a rule
 * proved on a different engine has been proved about a different engine.
 *
 * <p>A list of cases somebody wrote out is the migration copied into a second file and catches
 * nothing added after it. What makes this a check is that the population is discovered from the
 * catalogue and the cases are asserted to cover it exactly: a constraint added without a case here
 * reddens, and so does a case naming a constraint that was removed. NOT NULL is checked separately
 * for file_contents, model_call_tool_uses and model_call_tool_prices, and for model_calls'
 * answer_truncated, declined_words_altered, declined_words_truncated, unreadable_response_altered,
 * unreadable_response_truncated and unreadable_response_stopped flags. Other required
 * columns are outside this coverage.
 *
 * <p>Each case asserts the name the refusal carries rather than that something was refused. Two
 * defences stand behind several of these rows — a check on what the row claims about a subject, and
 * a foreign key on whether that claim is true — and a case content with any exception stays green
 * when either is deleted.
 *
 * <p>The cases written for one part of the schema are in the SchemaConstraint specs beside this one, each
 * against a server of its own built as {@link SchemaConstraintSpecification} builds it.
 */
class SchemaConstraintIntegrationSpec extends SchemaConstraintSpecification {

    static final List<Map<String, String>> CASES = [*SubjectCases.CASES, *PoolCases.CASES, *EstateRoleCases.CASES, *GroupCases.CASES,
                                                    *PeopleCases.CASES, *EntryCases.CASES, *LibraryCases.CASES, *StepAndDeclarationCases.CASES,
                                                    *RunCases.CASES, *ProductionAndCallCases.CASES, *SoundnessCases.CASES, *GroupCurrencyCases.CASES, *FileCases.CASES,
                                                    *SourceCodeCases.CASES, *WorkflowLabelCases.CASES, *SearchQuestionCases.CASES,
                                                    [table: "entry_versions", constraint: "entry_versions_version_name_not_empty",
                                                     statement: "update app.entry_versions set version_name = '' where entry_version_id = '${VERSION}'"],
                                                    [table: "declaration_fields", constraint: "declaration_fields_text_lines_known",
                                                     statement: insertInto("declaration_fields", A_FIELD + [text_lines: "'later'"])],
                                                    [table: "declaration_fields", constraint: "declaration_fields_text_lines_only_for_text",
                                                     statement: insertInto("declaration_fields", A_FIELD + [kind: "'number'", text_lines: "'one'"])]]

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
        "production_inputs.production_inputs_root_unique",
        "production_values.production_values_needs_review_unique",
        "production_values.production_values_production_unique",
        "productions.productions_ender_yielded_unique",
        "productions.productions_errored_code_file_parent_unique",
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
        "run_stops.run_stops_ceiling_unique",
        "runs.runs_depth_unique",
        "runs.runs_group_unique",
        "runs.runs_parent_workflow_step_unique",
        "runs.runs_root_unique",
        "runs.runs_version_unique",
        "soundness_checks.soundness_checks_outcome_unique",
        "stored_files.stored_files_group_unique",
        "subjects.subjects_kind_unique",
        "workflow_steps.workflow_steps_kind_unique",
        "workflow_steps.workflow_steps_pinned_unique",
        "workflow_steps.workflow_steps_producer_model_unique",
        "workflow_steps.workflow_steps_producer_unique",
        "workflow_steps.workflow_steps_reviewer_model_unique",
        "workflow_steps.workflow_steps_version_unique",
        "workflow_versions.workflow_versions_helper_unique",
    ] as Set

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
        "run_stop_additional_ceilings.run_stop_additional_ceilings_ceiling_run_fk",
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

    def "every named rule the schema enforces has a case that attacks it"() {
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

    /** Every lookup of the rows naming a target reads a key's columns. Keys into subjects are exempt: no subject
     * is ever removed, and those a page reads rows through are held to an index of their own by "every key into
     * subjects a page reads rows through leads an index, partial or not". */
    def "every foreign key leads an index on the table holding it"() {
        when:
        // DB-SPECIFIC: ::, pg_constraint, pg_class, pg_attribute, regnamespace, regclass, pg_index and pg_get_expr use PostgreSQL's syntax and semantics.
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
        // DB-SPECIFIC: ::, pg_constraint, pg_class, regnamespace and regclass use PostgreSQL's syntax and semantics.
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
        // DB-SPECIFIC: ::, pg_constraint, pg_class, regnamespace, regclass and pg_index use PostgreSQL's syntax and semantics.
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
     * which cannot use the named-rule path: a not-null violation identifies its table and column,
     * not a constraint name. Separate required-column paths cover file_contents, model_call_tool_uses,
     * model_call_tool_prices and only model_calls' answer_truncated, declined_words_altered,
     * declined_words_truncated, unreadable_response_altered, unreadable_response_truncated and
     * unreadable_response_stopped flags.
     * Other required columns are outside this coverage.
     */
    private Set<String> enforceableRules() {
        // DB-SPECIFIC: ::, pg_constraint, pg_class, pg_type, regnamespace, pg_index, pg_namespace and pg_trigger use PostgreSQL's syntax and semantics.
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
        // DB-SPECIFIC: <@, ::, pg_constraint, pg_class and regnamespace use PostgreSQL's syntax and semantics.
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
     * A constraint name is unique within its table and not within the schema, so two tables may each
     * carry an is_person. The name a refusal reports is the bare one; what a population is counted
     * in has to be the qualified one, or two rules share an entry and one of them goes untested.
     */
    private static String ruleOf(Map<String, String> attack) {
        "${attack.table}.${attack.constraint}"
    }
}
