package org.lilradish.lite.app.library;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.app.change.Author;
import org.lilradish.lite.domain.identity.UserId;
import org.lilradish.lite.domain.registry.EntryKind;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * What a draft of each kind starts holding: a copy of the version it starts from, or nothing where it is
 * the entry's first. A kind's own rows are copied here beside its version's as that kind comes to hold them.
 */
final class StartedContent {

    private static final String AUTHOR = Author.OF_CALLER;

    private static final String WORKFLOW_EMPTY =
            "insert into workflow_versions (entry_version_id, created_by) values (:started, %s)".formatted(AUTHOR);

    private static final String QUESTION_EMPTY =
            "insert into question_versions (entry_version_id, created_by) values (:started, %s)".formatted(AUTHOR);

    private static final String REFERENCE_LIST_EMPTY =
            "insert into reference_list_versions (entry_version_id, created_by) values (:started, %s)"
                    .formatted(AUTHOR);

    private static final String WORKFLOW_COPIED = """
            insert into workflow_versions (entry_version_id, ceiling, keeps_own_ceiling, raise_needs_approval,
                                           may_be_helped, helper_model, helper_mode, created_by)
            select :started, source.ceiling, source.keeps_own_ceiling, source.raise_needs_approval,
                   source.may_be_helped, source.helper_model, source.helper_mode, %s
              from workflow_versions source
             where source.entry_version_id = :source
            """.formatted(AUTHOR);

    private static final String QUESTION_COPIED = """
            insert into question_versions (entry_version_id, instruction, created_by)
            select :started, source.instruction, %s from question_versions source where source.entry_version_id = :source
            """.formatted(AUTHOR);

    private static final String REFERENCE_LIST_COPIED = """
            insert into reference_list_versions (entry_version_id, note, created_by)
            select :started, source.note, %s from reference_list_versions source where source.entry_version_id = :source
            """.formatted(AUTHOR);

    /* Materialized, so each field's new key is drawn once and read alike as its own and as its fields' parent. */
    // DB-SPECIFIC: uuidv7() and materialized are PostgreSQL's.
    private static final String VERSION_FIELDS_COPIED = """
            with source as materialized (
                select field.*, uuidv7() as copied_id
                  from declaration_fields field
                 where field.entry_version_id = :source)
            insert into declaration_fields (declaration_field_id, entry_version_id, entry_kind, side, parent_field_id,
                                            position, name, label, help, kind, holds_many, text_limit, many_limit,
                                            term_list_version_id, must_be_given, standing, standing_threshold,
                                            created_by)
            select source.copied_id, :started, source.entry_kind, source.side, parent.copied_id,
                   source.position, source.name, source.label, source.help, source.kind, source.holds_many,
                   source.text_limit, source.many_limit, source.term_list_version_id, source.must_be_given,
                   source.standing, source.standing_threshold, %s
              from source
              left join source parent on parent.declaration_field_id = source.parent_field_id
            """.formatted(AUTHOR);

    // DB-SPECIFIC: uuidv7(), materialized and data-modifying common table expressions are PostgreSQL's, and so is
    // a volatile function drawn after the sort below it: cases and bindings read back in key order keep theirs.
    private static final String WORKFLOW_STEPS_COPIED = """
            with steps as materialized (
                     select step.*, uuidv7() as copied_id
                       from workflow_steps step
                      where step.entry_version_id = :source
                      order by step.position, step.workflow_step_id),
                 cases as materialized (
                     select route.*, uuidv7() as copied_id
                       from route_cases route
                      where route.entry_version_id = :source
                      order by route.route_case_id),
                 fields as materialized (
                     select field.*, uuidv7() as copied_id
                       from declaration_fields field
                      where field.workflow_step_id in (select steps.workflow_step_id from steps)),
                 steps_copied as (
                     insert into workflow_steps (workflow_step_id, entry_version_id, position, name, kind,
                                                 pinned_version_id, pinned_kind, code_step, producer, producer_model,
                                                 producer_mode, tries, reviewer_model, reviewer_mode,
                                                 tells_what_happened, created_by)
                     select steps.copied_id, :started, steps.position, steps.name, steps.kind, steps.pinned_version_id,
                            steps.pinned_kind, steps.code_step, steps.producer, steps.producer_model,
                            steps.producer_mode, steps.tries, steps.reviewer_model, steps.reviewer_mode,
                            steps.tells_what_happened, %1$s
                       from steps),
                 cases_copied as (
                     insert into route_cases (route_case_id, entry_version_id, workflow_step_id, term,
                                              target_version_id, created_by)
                     select cases.copied_id, :started, steps.copied_id, cases.term, cases.target_version_id, %1$s
                       from cases
                       join steps on steps.workflow_step_id = cases.workflow_step_id),
                 fields_copied as (
                     insert into declaration_fields (declaration_field_id, workflow_step_id, side, parent_field_id,
                                                     position, name, label, help, kind, holds_many, text_limit,
                                                     many_limit, term_list_version_id, must_be_given, created_by)
                     select fields.copied_id, steps.copied_id, fields.side, parent.copied_id, fields.position,
                            fields.name, fields.label, fields.help, fields.kind, fields.holds_many, fields.text_limit,
                            fields.many_limit, fields.term_list_version_id, fields.must_be_given, %1$s
                       from fields
                       join steps on steps.workflow_step_id = fields.workflow_step_id
                       left join fields parent on parent.declaration_field_id = fields.parent_field_id)
            insert into bindings (entry_version_id, workflow_step_id, step_kind, route_case_id, target_path,
                                  source_step_id, source_path, constant, created_by)
            select :started, consumer.copied_id, binding.step_kind, routed.copied_id, binding.target_path,
                   source.copied_id, binding.source_path, binding.constant, %1$s
              from bindings binding
              left join steps consumer on consumer.workflow_step_id = binding.workflow_step_id
              left join cases routed on routed.route_case_id = binding.route_case_id
              left join steps source on source.workflow_step_id = binding.source_step_id
             where binding.entry_version_id = :source
             order by binding.binding_id
            """.formatted(AUTHOR);

    /* No row names a term by its key, so each copy draws a key of its own from the column's default. */
    private static final String REFERENCE_LIST_TERMS_COPIED = """
            insert into reference_list_terms (entry_version_id, position, term, meaning, created_by)
            select :started, source.position, source.term, source.meaning, %s
              from reference_list_terms source
             where source.entry_version_id = :source
            """.formatted(AUTHOR);

    private StartedContent() {}

    /** Inside the caller's transaction; a version to start from that holds no content of its kind fails loud. */
    static void started(
            JdbcClient database,
            EntryKind kind,
            EntryVersionId started,
            @Nullable EntryVersionId source,
            UserId caller) {
        JdbcClient.StatementSpec statement = source == null
                ? database.sql(empty(kind))
                : database.sql(copied(kind)).param("source", source.value());
        int written = statement
                .param("started", started.value())
                .param("caller", caller.value())
                .update();
        if (written != 1) {
            throw new IllegalStateException("Version " + started.value() + " was started from "
                    + (source == null ? "nothing" : source.value().toString()) + " and holds no content of its kind");
        }
        if (source == null) {
            return;
        }
        for (String copied : rowsCopied(kind)) {
            database.sql(copied)
                    .param("source", source.value())
                    .param("started", started.value())
                    .param("caller", caller.value())
                    .update();
        }
    }

    /** The statements copying the rows a kind holds beside its version's own, in the order they run. */
    private static List<String> rowsCopied(EntryKind kind) {
        return switch (kind) {
            case WORKFLOW -> List.of(VERSION_FIELDS_COPIED, WORKFLOW_STEPS_COPIED);
            case QUESTION -> List.of(VERSION_FIELDS_COPIED);
            case REFERENCE_LIST -> List.of(REFERENCE_LIST_TERMS_COPIED);
        };
    }

    private static String empty(EntryKind kind) {
        return switch (kind) {
            case WORKFLOW -> WORKFLOW_EMPTY;
            case QUESTION -> QUESTION_EMPTY;
            case REFERENCE_LIST -> REFERENCE_LIST_EMPTY;
        };
    }

    private static String copied(EntryKind kind) {
        return switch (kind) {
            case WORKFLOW -> WORKFLOW_COPIED;
            case QUESTION -> QUESTION_COPIED;
            case REFERENCE_LIST -> REFERENCE_LIST_COPIED;
        };
    }
}
