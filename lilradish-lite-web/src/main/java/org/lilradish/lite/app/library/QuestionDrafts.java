package org.lilradish.lite.app.library;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.app.change.Author;
import org.lilradish.lite.domain.declaration.Declaration;
import org.lilradish.lite.domain.declaration.Instruction;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.identity.UserId;
import org.lilradish.lite.domain.registry.EntryId;
import org.lilradish.lite.domain.registry.EntryKind;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * A question draft's instruction and either half, written through {@link Drafts} and answered with the version as
 * the same change then reads it, so the answer is never another writer's.
 */
@Component
final class QuestionDrafts {

    // DB-SPECIFIC: now(), greatest and an enum cast are PostgreSQL's.
    /* now() is when this transaction began, which can be before the row it waited on the lock for was made,
     * or last changed; greatest passes over an updated_at still null. */
    private static final String INSTRUCTION = """
            update question_versions
               set instruction = :instruction, updated_at = greatest(now(), created_at, updated_at), updated_by = %s
             where entry_version_id = :version
            """.formatted(Author.OF_CALLER);

    private final JdbcClient database;

    private final Drafts drafts;

    private final Questions questions;

    QuestionDrafts(JdbcClient database, Drafts drafts, Questions questions) {
        this.database = database;
        this.drafts = drafts;
        this.questions = questions;
    }

    /** Saying nothing is holding no instruction yet, which submitting refuses. */
    Questions.QuestionView instruct(
            GroupId group,
            EntryId entry,
            EntryVersionId version,
            UserId caller,
            int seenRevision,
            @Nullable Instruction said) {
        return drafts.write(group, EntryKind.QUESTION, entry, version, caller, seenRevision, draft -> {
            int written = database.sql(INSTRUCTION)
                    .param("instruction", said == null ? null : said.value())
                    .param("version", draft.version().value())
                    .param("caller", caller.value())
                    .update();
            if (written != 1) {
                throw new IllegalStateException("Question version " + version.value() + " holds no question content");
            }
            return questions.viewIn(group, draft.version(), caller);
        });
    }

    /**
     * The half written whole in its declared order in place of what it held; {@code readAs} is the key each field
     * was read under, in {@link DeclarationBody.Sent}'s order, none for one added.
     */
    Questions.QuestionView declare(
            GroupId group,
            EntryId entry,
            EntryVersionId version,
            UserId caller,
            int seenRevision,
            Declaration half,
            List<@Nullable UUID> readAs) {
        requireNonNull(half, "QuestionDrafts half must not be null");
        requireNonNull(readAs, "QuestionDrafts keys read must not be null");
        return drafts.write(group, EntryKind.QUESTION, entry, version, caller, seenRevision, draft -> {
            FieldWriting.halfRewritten(database, draft, half, readAs, caller);
            return questions.viewIn(group, draft.version(), caller);
        });
    }
}
