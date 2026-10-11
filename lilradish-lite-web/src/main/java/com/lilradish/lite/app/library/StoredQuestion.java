package com.lilradish.lite.app.library;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.declaration.Asking;
import com.lilradish.lite.domain.declaration.DeclarationSide;
import com.lilradish.lite.domain.declaration.Instruction;
import com.lilradish.lite.domain.declaration.OfferedTerms;
import com.lilradish.lite.domain.identity.GroupId;
import com.lilradish.lite.domain.referencelist.ListNote;
import com.lilradish.lite.domain.referencelist.Term;
import com.lilradish.lite.domain.referencelist.TermMeaning;
import com.lilradish.lite.domain.registry.EntryKind;
import com.lilradish.lite.domain.registry.EntryVersionId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A question version's content as the store holds it, each field beside its key, read as
 * {@link StoredDeclarations} reads a declaration.
 *
 * @param instruction none where the version says nothing yet
 * @param drawing none where the version has no human layout
 * @param searchWay none where the question is not marked for searching
 */
record StoredQuestion(
        @Nullable Instruction instruction,
        @Nullable String drawing,
        @Nullable String searchWay,
        StoredDeclarations.Half takes,
        StoredDeclarations.Half gives) {

    private static final String INSTRUCTION =
            "select question.instruction, question.drawing, question.search_way from question_versions question where question.entry_version_id = :version";

    // DB-SPECIFIC: PostgreSQL ANY compared against a bound uuid array.
    private static final String INSTRUCTIONS = """
            select question.entry_version_id, question.instruction, question.search_way
              from question_versions question
             where question.entry_version_id = any(cast(:versions as uuid[]))""";

    // DB-SPECIFIC: PostgreSQL ANY compares against a bound uuid array.
    private static final String TERMS = """
            select term.entry_version_id, term.term, term.meaning
              from reference_list_terms term
              join entry_versions version on version.entry_version_id = term.entry_version_id
              join entries entry on entry.entry_id = version.entry_id
             where term.entry_version_id = any(cast(:lists as uuid[])) and entry.group_id = :group
             order by term.entry_version_id, term.position""";

    // DB-SPECIFIC: PostgreSQL ANY compares against a bound uuid array.
    private static final String NOTES = """
            select list.entry_version_id, list.note
              from reference_list_versions list
              join entry_versions version on version.entry_version_id = list.entry_version_id
              join entries entry on entry.entry_id = version.entry_id
             where list.entry_version_id = any(cast(:lists as uuid[])) and entry.group_id = :group""";

    StoredQuestion {
        requireNonNull(takes, "StoredQuestion takes must not be null");
        requireNonNull(gives, "StoredQuestion gives must not be null");
    }

    /** Inside the caller's transaction; none where the version holds no question content. */
    static Optional<StoredQuestion> read(JdbcClient database, EntryVersionId version) {
        List<QuestionText> said = database.sql(INSTRUCTION)
                .param("version", version.value())
                .query((result, number) -> new QuestionText(
                        result.getString("instruction"), result.getString("drawing"), result.getString("search_way")))
                .list();
        if (said.isEmpty()) {
            return Optional.empty();
        }
        StoredDeclarations.Halves halves =
                requireNonNull(StoredDeclarations.ofVersions(database, Map.of(version, EntryKind.QUESTION))
                        .get(version));
        QuestionText text = said.getFirst();
        return Optional.of(of(version, text.instruction(), text.drawing(), text.searchWay(), halves));
    }

    /**
     * Inside the caller's transaction: what a model is told of each of {@code questions}, from the halves read of
     * them already, in three reads however many there are. One holding no question content, or one submitting
     * would refuse, could not be told, and is left out; one that could, pinning a list the group does not hold,
     * fails as {@link #told} does.
     */
    static Map<EntryVersionId, Asking> askings(
            JdbcClient database, GroupId group, Map<EntryVersionId, StoredDeclarations.Halves> questions) {
        if (questions.isEmpty()) {
            return Map.of();
        }
        Map<EntryVersionId, StoredQuestion> read = readAll(database, questions);
        Set<EntryVersionId> lists = new LinkedHashSet<>();
        read.values().forEach(question -> lists.addAll(question.pinned()));
        Map<EntryVersionId, OfferedTerms> offered;
        try {
            offered = offeredIn(database, group, lists);
        } catch (IllegalArgumentException refused) {
            throw new IllegalStateException(
                    "A question a workflow asks pins a list holding words this system will not show", refused);
        }
        Map<EntryVersionId, Asking> askings = HashMap.newHashMap(read.size());
        read.forEach((version, question) -> {
            if (QuestionContentCheck.problemsOf(question, offered).isEmpty()) {
                askings.put(version, question.told(version, offered));
            }
        });
        return askings;
    }

    /** Shared questions read from their halves already held; a person's drawing is not loaded. */
    static Map<EntryVersionId, StoredQuestion> readAll(
            JdbcClient database, Map<EntryVersionId, StoredDeclarations.Halves> questions) {
        if (questions.isEmpty()) {
            return Map.of();
        }
        Map<EntryVersionId, StoredQuestion> read = HashMap.newHashMap(questions.size());
        database.sql(INSTRUCTIONS)
                .param("versions", PinnedVersions.spelled(questions.keySet()))
                .query(result -> {
                    EntryVersionId version = new EntryVersionId(result.getObject("entry_version_id", UUID.class));
                    read.put(
                            version,
                            of(
                                    version,
                                    result.getString("instruction"),
                                    null,
                                    result.getString("search_way"),
                                    requireNonNull(questions.get(version))));
                });
        return read;
    }

    /**
     * Inside the caller's transaction: what each list of the group's the version pins offers, in each list's
     * order. A list of another group's, or one holding no content of its kind, is missing.
     */
    Map<EntryVersionId, OfferedTerms> offered(JdbcClient database, GroupId group, EntryVersionId version) {
        return offered(database, group, version, pinned());
    }

    /** As {@link #offered(JdbcClient, GroupId, EntryVersionId)}, of the lists a version of any kind pins. */
    static Map<EntryVersionId, OfferedTerms> offered(
            JdbcClient database, GroupId group, EntryVersionId version, Collection<EntryVersionId> lists) {
        try {
            return offeredIn(database, group, lists);
        } catch (IllegalArgumentException refused) {
            throw new IllegalStateException(
                    "Version " + version.value() + " pins a list holding words this system will not show", refused);
        }
    }

    /** As {@link #offered(JdbcClient, GroupId, EntryVersionId, Collection)}, of lists several versions pin. */
    static Map<EntryVersionId, OfferedTerms> offered(
            JdbcClient database, GroupId group, Collection<EntryVersionId> lists) {
        try {
            return offeredIn(database, group, lists);
        } catch (IllegalArgumentException refused) {
            throw new IllegalStateException("A list pinned holds words this system will not show", refused);
        }
    }

    /** Every reference list version either half pins, each once, in the order the halves read. */
    Set<EntryVersionId> pinned() {
        Set<EntryVersionId> pinned = new LinkedHashSet<>();
        new StoredDeclarations.Halves(takes, gives).listsPinnedInto(pinned);
        return pinned;
    }

    StoredDeclarations.Half half(DeclarationSide side) {
        return side == DeclarationSide.TAKES ? takes : gives;
    }

    private static StoredQuestion of(
            EntryVersionId version,
            @Nullable String said,
            @Nullable String drawing,
            @Nullable String searchWay,
            StoredDeclarations.Halves halves) {
        try {
            return new StoredQuestion(
                    said == null ? null : new Instruction(said), drawing, searchWay, halves.takes(), halves.gives());
        } catch (IllegalArgumentException refused) {
            throw new IllegalStateException(
                    "Version " + version.value() + " holds an instruction this system will not read", refused);
        }
    }

    private record QuestionText(
            @Nullable String instruction,
            @Nullable String drawing,
            @Nullable String searchWay) {}

    private static Map<EntryVersionId, OfferedTerms> offeredIn(
            JdbcClient database, GroupId group, Collection<EntryVersionId> lists) {
        if (lists.isEmpty()) {
            return Map.of();
        }
        String[] spelled = PinnedVersions.spelled(lists);
        Map<UUID, List<OfferedTerms.Offered>> terms = new HashMap<>();
        database.sql(TERMS)
                .param("lists", spelled)
                .param("group", group.value())
                .query(result -> {
                    terms.computeIfAbsent(
                                    result.getObject("entry_version_id", UUID.class), ignored -> new ArrayList<>())
                            .add(new OfferedTerms.Offered(
                                    new Term(result.getString("term")), new TermMeaning(result.getString("meaning"))));
                });
        Map<EntryVersionId, OfferedTerms> offered = HashMap.newHashMap(lists.size());
        database.sql(NOTES)
                .param("lists", spelled)
                .param("group", group.value())
                .query(result -> {
                    UUID list = result.getObject("entry_version_id", UUID.class);
                    String note = result.getString("note");
                    offered.put(
                            new EntryVersionId(list),
                            new OfferedTerms(
                                    terms.getOrDefault(list, List.of()), note == null ? null : new ListNote(note)));
                });
        return offered;
    }

    /**
     * What a model is told of a question telling something, by what each list it pins offers, read from the
     * group's own. Drafts pin only those, so one missing is a store gone wrong, and fails.
     */
    Asking told(EntryVersionId version, Map<EntryVersionId, OfferedTerms> offered) {
        if (!offered.keySet().containsAll(pinned())) {
            throw new IllegalStateException(
                    "Question version " + version.value() + " pins a list this group does not hold");
        }
        return Asking.of(
                requireNonNull(instruction, "a question told to a model tells something"),
                takes.declaration(),
                gives.declaration(),
                offered);
    }
}
