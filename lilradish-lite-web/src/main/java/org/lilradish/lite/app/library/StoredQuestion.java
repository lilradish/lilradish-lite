package org.lilradish.lite.app.library;

import static java.util.Objects.requireNonNull;

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
import org.lilradish.lite.domain.declaration.Asking;
import org.lilradish.lite.domain.declaration.DeclarationSide;
import org.lilradish.lite.domain.declaration.Instruction;
import org.lilradish.lite.domain.declaration.OfferedTerms;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.referencelist.ListNote;
import org.lilradish.lite.domain.referencelist.Term;
import org.lilradish.lite.domain.referencelist.TermMeaning;
import org.lilradish.lite.domain.registry.EntryKind;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A question version's content as the store holds it, each field beside its key, read as
 * {@link StoredDeclarations} reads a declaration.
 *
 * @param instruction none where the version says nothing yet
 */
record StoredQuestion(@Nullable Instruction instruction, StoredDeclarations.Half takes, StoredDeclarations.Half gives) {

    private static final String INSTRUCTION =
            "select question.instruction from question_versions question where question.entry_version_id = :version";

    private static final String INSTRUCTIONS = """
            select question.entry_version_id, question.instruction
              from question_versions question
             where question.entry_version_id = any(cast(:versions as uuid[]))""";

    private static final String TERMS = """
            select term.entry_version_id, term.term, term.meaning
              from reference_list_terms term
              join entry_versions version on version.entry_version_id = term.entry_version_id
              join entries entry on entry.entry_id = version.entry_id
             where term.entry_version_id = any(cast(:lists as uuid[])) and entry.group_id = :group
             order by term.entry_version_id, term.position""";

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
        List<Optional<String>> said = database.sql(INSTRUCTION)
                .param("version", version.value())
                .query((result, number) -> Optional.ofNullable(result.getString("instruction")))
                .list();
        if (said.isEmpty()) {
            return Optional.empty();
        }
        StoredDeclarations.Halves halves =
                requireNonNull(StoredDeclarations.ofVersions(database, Map.of(version, EntryKind.QUESTION))
                        .get(version));
        return Optional.of(of(version, said.getFirst().orElse(null), halves));
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

    /**
     * Inside the caller's transaction: each of {@code questions} holding question content, from the halves read
     * of them already, in one read however many there are; one holding none is left out.
     */
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
                            of(version, result.getString("instruction"), requireNonNull(questions.get(version))));
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

    private static StoredQuestion of(EntryVersionId version, @Nullable String said, StoredDeclarations.Halves halves) {
        try {
            return new StoredQuestion(said == null ? null : new Instruction(said), halves.takes(), halves.gives());
        } catch (IllegalArgumentException refused) {
            throw new IllegalStateException(
                    "Version " + version.value() + " holds an instruction this system will not read", refused);
        }
    }

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
