package com.lilradish.lite.app.library;

import com.lilradish.lite.app.change.Author;
import com.lilradish.lite.domain.declaration.Field;
import com.lilradish.lite.domain.declaration.FieldShape;
import com.lilradish.lite.domain.identity.UserId;
import com.lilradish.lite.domain.search.SearchWay;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** A held way's answer shape written without replacing the standing of a field kept by name. */
final class QuestionSearchAnswers {

    // DB-SPECIFIC: the SQL constants below use PostgreSQL's native declaration_side, field_kind,
    // entry_kind and field_standing enums with their literals; LIMIT bounds the result to one kept field.
    private static final String KEPT = """
            select declaration_field_id from declaration_fields
             where entry_version_id = :version and side = 'gives' and parent_field_id is null and name = :name
             order by position, declaration_field_id limit 1
            """;

    // DB-SPECIFIC: PostgreSQL ANY compares against the bound uuid array.
    private static final String REMOVED = """
            delete from declaration_fields
             where entry_version_id = :version and side = 'gives'
               and not (declaration_field_id = any(cast(:kept as uuid[])))
            """;

    // DB-SPECIFIC: PostgreSQL now() supplies the transaction-start timestamp; GREATEST ignores a NULL
    // updated_at while retaining the other timestamps.
    private static final String UPDATED = """
            update declaration_fields
               set position = :position, kind = 'text', holds_many = false, text_limit = :limit,
                   text_lines = :lines, many_limit = null, term_list_version_id = null,
                   file_types = null, file_maximum_bytes = null, must_be_given = :required,
                   updated_at = greatest(now(), created_at, updated_at), updated_by = %s
             where declaration_field_id = :field and entry_version_id = :version and side = 'gives'
            """.formatted(Author.OF_CALLER);

    private static final String ADDED = """
            insert into declaration_fields (entry_version_id, entry_kind, side, position, name, kind,
                                            holds_many, text_limit, text_lines, must_be_given, standing, created_by)
            values (:version, 'question', 'gives', :position, :name, 'text', false, :limit, :lines,
                    :required, 'never', %s)
            """.formatted(Author.OF_CALLER);

    private QuestionSearchAnswers() {}

    static void write(JdbcClient database, OpenDraft draft, SearchWay way, UserId caller) {
        List<UUID> kept = new ArrayList<>();
        for (Field output : way.outputs()) {
            database.sql(KEPT)
                    .param("version", draft.version().value())
                    .param("name", output.name().value())
                    .query(UUID.class)
                    .optional()
                    .ifPresent(kept::add);
        }
        database.sql(REMOVED)
                .param("version", draft.version().value())
                .param("kept", kept.stream().map(UUID::toString).toArray(String[]::new))
                .update();
        for (int position = 0; position < way.outputs().size(); position++) {
            Field output = way.outputs().get(position);
            FieldShape.Text text = (FieldShape.Text) output.shape();
            UUID key = database.sql(KEPT)
                    .param("version", draft.version().value())
                    .param("name", output.name().value())
                    .query(UUID.class)
                    .optional()
                    .orElse(null);
            JdbcClient.StatementSpec writing = database.sql(key == null ? ADDED : UPDATED)
                    .param("version", draft.version().value())
                    .param("position", position)
                    .param("limit", text.longest())
                    .param("lines", text.lines().published())
                    .param("required", output.demand().mustBe())
                    .param("caller", caller.value());
            if (key == null) {
                writing.param("name", output.name().value());
            } else {
                writing.param("field", key);
            }
            if (writing.update() != 1) {
                throw new IllegalStateException("Question answer field was not written once");
            }
        }
    }
}
