package com.lilradish.lite.app.library;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.app.change.Author;
import com.lilradish.lite.app.search.SearchWays;
import com.lilradish.lite.domain.declaration.Declaration;
import com.lilradish.lite.domain.declaration.DeclarationSide;
import com.lilradish.lite.domain.declaration.Field;
import com.lilradish.lite.domain.declaration.FieldName;
import com.lilradish.lite.domain.declaration.FieldShape;
import com.lilradish.lite.domain.declaration.InputReferences;
import com.lilradish.lite.domain.declaration.Instruction;
import com.lilradish.lite.domain.failure.RefusalCode;
import com.lilradish.lite.domain.identity.GroupId;
import com.lilradish.lite.domain.identity.UserId;
import com.lilradish.lite.domain.registry.EntryId;
import com.lilradish.lite.domain.registry.EntryKind;
import com.lilradish.lite.domain.registry.EntryVersionId;
import com.lilradish.lite.domain.search.QuestionSearchShape;
import com.lilradish.lite.domain.search.SearchWay;
import com.lilradish.lite.domain.text.CharacterStanding;
import com.lilradish.lite.domain.text.Legibility;
import com.lilradish.lite.domain.text.ProseRefusal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.error.ApiErrorException;
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

    private static final String DRAWING = """
            update question_versions
               set drawing = :drawing, updated_at = greatest(now(), created_at, updated_at), updated_by = %s
             where entry_version_id = :version
            """.formatted(Author.OF_CALLER);

    private static final String SEARCHING = """
            update question_versions
               set search_way = :way, updated_at = greatest(now(), created_at, updated_at), updated_by = %s
             where entry_version_id = :version
            """.formatted(Author.OF_CALLER);

    private final JdbcClient database;

    private final Drafts drafts;

    private final Questions questions;

    private final SearchWays searchWays;

    QuestionDrafts(JdbcClient database, Drafts drafts, Questions questions, SearchWays searchWays) {
        this.database = database;
        this.drafts = drafts;
        this.questions = questions;
        this.searchWays = searchWays;
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
            if (said != null) {
                StoredQuestion stored = stored(draft);
                instructionFits(said.value(), stored.takes().declaration());
            }
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

    Questions.QuestionView draw(
            GroupId group,
            EntryId entry,
            EntryVersionId version,
            UserId caller,
            int seenRevision,
            @Nullable String drawing) {
        return drafts.write(group, EntryKind.QUESTION, entry, version, caller, seenRevision, draft -> {
            int written = database.sql(DRAWING)
                    .param("drawing", drawing)
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
            if (half.side() == DeclarationSide.TAKES) {
                StoredQuestion stored = stored(draft);
                Map<FieldName, FieldName> renamed = renamed(stored.takes(), half, readAs);
                Instruction instruction = stored.instruction();
                HashSet<String> mentioned = new HashSet<>();
                if (instruction != null) {
                    mentioned.addAll(InputReferences.names(instruction.value()));
                }
                if (stored.drawing() != null) {
                    mentioned.addAll(InputReferences.drawingNames(stored.drawing()).stream()
                            .map(FieldName::value)
                            .toList());
                }
                List<FieldName> removed = stored.takes().declaration().fields().stream()
                        .map(Field::name)
                        .filter(name -> !renamed.containsKey(name) && mentioned.contains(name.value()))
                        .toList();
                if (!removed.isEmpty()) {
                    throw LibraryRefusal.stillMentioned(removed);
                }
                if (instruction != null) {
                    String next = InputReferences.renamed(instruction.value(), renamed);
                    instructionOf(next);
                    instructionFits(next, half);
                    if (!next.equals(instruction.value())) {
                        database.sql(INSTRUCTION)
                                .param("instruction", next)
                                .param("version", draft.version().value())
                                .param("caller", caller.value())
                                .update();
                    }
                }
                String drawing = stored.drawing();
                if (drawing != null) {
                    String next = InputReferences.renamed(drawing, renamed);
                    drawingOf(next);
                    if (!next.equals(drawing)) {
                        database.sql(DRAWING)
                                .param("drawing", next)
                                .param("version", draft.version().value())
                                .param("caller", caller.value())
                                .update();
                    }
                }
            }
            if (half.side() == DeclarationSide.GIVES) {
                StoredQuestion stored = stored(draft);
                if (stored.searchWay() != null) {
                    SearchWay way =
                            searchWays.find(stored.searchWay()).orElseThrow(LibraryRefusal.SEARCH_WAY_NOT_HELD::raised);
                    if (!QuestionSearchShape.matches(way, half.fields())) {
                        throw LibraryRefusal.SEARCH_QUESTION_SHAPE_LOCKED.raisedForSearchWay(way);
                    }
                }
            }
            FieldWriting.halfRewritten(database, draft, half, readAs, caller);
            return questions.viewIn(group, draft.version(), caller);
        });
    }

    static Instruction instructionOf(String said) {
        ProseRefusal refusal = Instruction.refusalOf(said);
        if (refusal == null) {
            return new Instruction(said);
        }
        throw LibraryRefusal.refusingProse(refusal, LibraryRefusal.INSTRUCTION_UNUSABLE);
    }

    static String drawingOf(String said) {
        if (said.contains("\r\n")) {
            throw LibraryRefusal.PROSE_LINE_BREAK_CRLF.raised();
        }
        try {
            for (int at = 0; at < said.length(); ) {
                int point = said.codePointAt(at);
                CharacterStanding standing = CharacterStanding.of(point);
                if (standing == CharacterStanding.OTHER_CONTROL
                        || standing == CharacterStanding.LINE_OR_PARAGRAPH_SEPARATOR
                        || standing == CharacterStanding.SURROGATE) {
                    throw new IllegalArgumentException("Drawing holds a character that prose cannot hold");
                }
                at += Character.charCount(point);
            }
            Legibility.requireSomethingVisibleInProse(said, "Drawing");
            Legibility.requireWithinMaximumLength(said, 8192, "Drawing");
        } catch (IllegalArgumentException refused) {
            throw LibraryRefusal.DRAWING_UNUSABLE.raised();
        }
        if (DrawingDepth.tooDeep(said)) {
            throw LibraryRefusal.DRAWING_TOO_DEEP.raised();
        }
        return said;
    }

    private static void instructionFits(String instruction, Declaration takes) {
        List<String> unknown = InputReferences.unknown(
                instruction, takes.fields().stream().map(Field::name).toList());
        if (!unknown.isEmpty()) {
            throw LibraryRefusal.refusingInputs(unknown);
        }
    }

    private static Map<FieldName, FieldName> renamed(
            StoredDeclarations.Half before, Declaration after, List<@Nullable UUID> readAs) {
        Map<UUID, FieldName> top = new HashMap<>();
        List<Field> fields = before.declaration().fields();
        for (int at = 0; at < fields.size(); at++) {
            top.put(before.keys().get(at).id(), fields.get(at).name());
        }
        HashSet<UUID> seen = new HashSet<>();
        Map<FieldName, FieldName> renamed = new HashMap<>();
        Iterator<@Nullable UUID> keys = readAs.iterator();
        renamedIn(after.fields(), keys, seen, top, renamed, true);
        if (keys.hasNext()) {
            throw new IllegalArgumentException("FieldWriting was handed more keys read than fields");
        }
        return renamed;
    }

    private static void renamedIn(
            List<Field> fields,
            Iterator<@Nullable UUID> keys,
            HashSet<UUID> seen,
            Map<UUID, FieldName> top,
            Map<FieldName, FieldName> renamed,
            boolean first) {
        for (Field field : fields) {
            if (!keys.hasNext()) {
                throw new IllegalArgumentException("FieldWriting was handed fewer keys read than fields");
            }
            UUID key = keys.next();
            if (key != null) {
                if (!seen.add(key)) {
                    throw new ApiErrorException(RefusalCode.BODY_UNUSABLE, DeclarationBody.REFUSED);
                }
                FieldName old = top.get(key);
                if (first && old != null) {
                    renamed.put(old, field.name());
                }
            }
            if (field.shape() instanceof FieldShape.Nested nested) {
                renamedIn(nested.fields(), keys, seen, top, renamed, false);
            }
        }
    }

    Questions.QuestionView searching(
            GroupId group,
            EntryId entry,
            EntryVersionId version,
            UserId caller,
            int seenRevision,
            @Nullable String namedWay) {
        return drafts.writeIf(
                group,
                EntryKind.QUESTION,
                entry,
                version,
                caller,
                seenRevision,
                draft -> {
                    StoredQuestion stored = stored(draft);
                    if (namedWay == null) {
                        return stored.searchWay() != null;
                    }
                    SearchWay way = searchWays.find(namedWay).orElseThrow(LibraryRefusal.SEARCH_WAY_NOT_HELD::raised);
                    return !Objects.equals(namedWay, stored.searchWay())
                            || !QuestionSearchShape.matches(
                                    way, stored.gives().declaration().fields());
                },
                draft -> {
                    if (namedWay != null) {
                        QuestionSearchAnswers.write(
                                database,
                                draft,
                                searchWays.find(namedWay).orElseThrow(LibraryRefusal.SEARCH_WAY_NOT_HELD::raised),
                                caller);
                    }
                    if (database.sql(SEARCHING)
                                    .param("version", draft.version().value())
                                    .param("way", namedWay)
                                    .param("caller", caller.value())
                                    .update()
                            != 1) {
                        throw new IllegalStateException("Question draft holds no question content");
                    }
                    return questions.viewIn(group, draft.version(), caller);
                },
                draft -> questions.viewIn(group, draft.version(), caller));
    }

    private StoredQuestion stored(OpenDraft draft) {
        return StoredQuestion.read(database, draft.version())
                .orElseThrow(() -> new IllegalStateException("Question draft holds no question content"));
    }
}
