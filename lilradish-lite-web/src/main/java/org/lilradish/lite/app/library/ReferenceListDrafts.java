package org.lilradish.lite.app.library;

import static java.util.Objects.requireNonNull;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.app.change.Author;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.identity.UserId;
import org.lilradish.lite.domain.referencelist.ListNote;
import org.lilradish.lite.domain.referencelist.Term;
import org.lilradish.lite.domain.referencelist.TermMeaning;
import org.lilradish.lite.domain.registry.EntryId;
import org.lilradish.lite.domain.registry.EntryKind;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * A reference list draft's note and its terms, one change at a time, each written through {@link Drafts} and
 * answered with the draft as that change left it. A term is found by its key within this draft alone, so a key
 * of any other says nothing of where it is.
 */
@Component
final class ReferenceListDrafts {

    // DB-SPECIFIC: now(), greatest and returning are PostgreSQL's.
    private static final String AUTHOR = Author.OF_CALLER;

    /* now() is when this transaction began, which can be before the row it waited on the lock for was made,
     * or last changed; greatest passes over an updated_at still null. */
    private static final String NOTE = """
            update reference_list_versions
               set note = :note, updated_at = greatest(now(), created_at, updated_at), updated_by = %s
             where entry_version_id = :version
            """.formatted(AUTHOR);

    /* Places run from 1 without a gap, which every change here keeps them to. */
    private static final String ADDED = """
            insert into reference_list_terms (entry_version_id, position, term, meaning, created_by)
            select :version, coalesce(max(term.position), 0) + 1, :term, :meaning, %s
              from reference_list_terms term
             where term.entry_version_id = :version
            """.formatted(AUTHOR);

    /* Counted after the revision seen is claimed, so it counts what that revision holds: two adds from one
     * reading never both reach it, the revision refusing the second. */
    private static final String HELD =
            "select count(*) from reference_list_terms term where term.entry_version_id = :version";

    private static final String EDITED = """
            update reference_list_terms
               set term = :term, meaning = :meaning,
                   updated_at = greatest(now(), created_at, updated_at), updated_by = %s
             where reference_list_term_id = :termId and entry_version_id = :version
            """.formatted(AUTHOR);

    private static final String REMOVED = """
            delete from reference_list_terms
             where reference_list_term_id = :termId and entry_version_id = :version
            returning position
            """;

    private static final String CLOSED_UP = """
            update reference_list_terms
               set position = position - 1, updated_at = greatest(now(), created_at, updated_at), updated_by = %s
             where entry_version_id = :version and position > :removed
            """.formatted(AUTHOR);

    private static final String PLACED = """
            select term.position
              from reference_list_terms term
             where term.reference_list_term_id = :termId and term.entry_version_id = :version
            """;

    /* Two terms change places in one statement; a place past either end is held by none, so one row changes. */
    private static final String SWAPPED = """
            update reference_list_terms
               set position = case position when :from then :to else :from end,
                   updated_at = greatest(now(), created_at, updated_at), updated_by = %s
             where entry_version_id = :version and position in (:from, :to)
            """.formatted(AUTHOR);

    private final JdbcClient database;

    private final Drafts drafts;

    private final ReferenceLists lists;

    ReferenceListDrafts(JdbcClient database, Drafts drafts, ReferenceLists lists) {
        this.database = database;
        this.drafts = drafts;
        this.lists = lists;
    }

    /** Which way a term moves, one place at a time. */
    enum Way {
        UP(-1),
        DOWN(1);

        private final int step;

        Way(int step) {
            this.step = step;
        }
    }

    /** Saying nothing is holding no note, which a list may. */
    ReferenceLists.ListView note(
            GroupId group,
            EntryId entry,
            EntryVersionId version,
            UserId caller,
            int seenRevision,
            @Nullable ListNote note) {
        return drafts.write(group, EntryKind.REFERENCE_LIST, entry, version, caller, seenRevision, draft -> {
            int written = database.sql(NOTE)
                    .param("note", note == null ? null : note.value())
                    .param("version", draft.version().value())
                    .param("caller", caller.value())
                    .update();
            if (written != 1) {
                throw new IllegalStateException(
                        "Reference list version " + version.value() + " holds no reference list content");
            }
            return lists.viewIn(draft.version());
        });
    }

    /** After every term the draft holds, refused where it holds as many as a list holds already. */
    ReferenceLists.ListView add(
            GroupId group,
            EntryId entry,
            EntryVersionId version,
            UserId caller,
            int seenRevision,
            Term term,
            TermMeaning meaning) {
        requireNonNull(term, "ReferenceListDrafts term must not be null");
        requireNonNull(meaning, "ReferenceListDrafts meaning must not be null");
        return drafts.write(group, EntryKind.REFERENCE_LIST, entry, version, caller, seenRevision, draft -> {
            int held = database.sql(HELD)
                    .param("version", draft.version().value())
                    .query(Integer.class)
                    .single();
            if (held >= Term.MOST_IN_A_LIST) {
                throw LibraryRefusal.LIST_TOO_LARGE.raised();
            }
            database.sql(ADDED)
                    .param("version", draft.version().value())
                    .param("term", term.value())
                    .param("meaning", meaning.value())
                    .param("caller", caller.value())
                    .update();
            return lists.viewIn(draft.version());
        });
    }

    ReferenceLists.ListView edit(
            GroupId group,
            EntryId entry,
            EntryVersionId version,
            UserId caller,
            int seenRevision,
            UUID termId,
            Term term,
            TermMeaning meaning) {
        requireNonNull(termId, "ReferenceListDrafts term key must not be null");
        requireNonNull(term, "ReferenceListDrafts term must not be null");
        requireNonNull(meaning, "ReferenceListDrafts meaning must not be null");
        return drafts.write(group, EntryKind.REFERENCE_LIST, entry, version, caller, seenRevision, draft -> {
            int written = database.sql(EDITED)
                    .param("term", term.value())
                    .param("meaning", meaning.value())
                    .param("caller", caller.value())
                    .param("termId", termId)
                    .param("version", draft.version().value())
                    .update();
            if (written != 1) {
                throw LibraryRefusal.TERM_NOT_IN_VIEW.raised();
            }
            return lists.viewIn(draft.version());
        });
    }

    /** Every term after it moves up a place, so none is left between. */
    ReferenceLists.ListView remove(
            GroupId group, EntryId entry, EntryVersionId version, UserId caller, int seenRevision, UUID termId) {
        requireNonNull(termId, "ReferenceListDrafts term key must not be null");
        return drafts.write(group, EntryKind.REFERENCE_LIST, entry, version, caller, seenRevision, draft -> {
            int removed = database.sql(REMOVED)
                    .param("termId", termId)
                    .param("version", draft.version().value())
                    .query(Integer.class)
                    .optional()
                    .orElseThrow(LibraryRefusal.TERM_NOT_IN_VIEW::raised);
            database.sql(CLOSED_UP)
                    .param("caller", caller.value())
                    .param("version", draft.version().value())
                    .param("removed", removed)
                    .update();
            return lists.viewIn(draft.version());
        });
    }

    /** Changes places with the term beside it that way; the first moves no higher, the last no lower. */
    ReferenceLists.ListView move(
            GroupId group,
            EntryId entry,
            EntryVersionId version,
            UserId caller,
            int seenRevision,
            UUID termId,
            Way way) {
        requireNonNull(termId, "ReferenceListDrafts term key must not be null");
        requireNonNull(way, "ReferenceListDrafts way must not be null");
        return drafts.write(group, EntryKind.REFERENCE_LIST, entry, version, caller, seenRevision, draft -> {
            int from = database.sql(PLACED)
                    .param("termId", termId)
                    .param("version", draft.version().value())
                    .query(Integer.class)
                    .optional()
                    .orElseThrow(LibraryRefusal.TERM_NOT_IN_VIEW::raised);
            int swapped = database.sql(SWAPPED)
                    .param("from", from)
                    .param("to", from + way.step)
                    .param("caller", caller.value())
                    .param("version", draft.version().value())
                    .update();
            if (swapped != 2) {
                throw LibraryRefusal.TERM_AT_END.raised();
            }
            return lists.viewIn(draft.version());
        });
    }
}
