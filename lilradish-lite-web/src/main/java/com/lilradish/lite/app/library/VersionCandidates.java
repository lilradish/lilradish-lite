package com.lilradish.lite.app.library;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.lilradish.lite.app.group.GroupReach;
import com.lilradish.lite.app.group.GroupRoles;
import com.lilradish.lite.app.listing.SearchFolding;
import com.lilradish.lite.app.search.SearchWays;
import com.lilradish.lite.domain.identity.GroupId;
import com.lilradish.lite.domain.identity.UserId;
import com.lilradish.lite.domain.registry.EntryKind;
import com.lilradish.lite.domain.registry.EntryName;
import com.lilradish.lite.domain.registry.EntryVersionId;
import com.lilradish.lite.domain.registry.VersionName;
import com.lilradish.lite.domain.search.QuestionSearchShape;
import com.lilradish.lite.domain.search.SearchWay;
import com.lilradish.lite.domain.wire.StoreLabels;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Bounded candidates and independent pin details, each read within one current membership snapshot. */
@Component
public final class VersionCandidates {

    // DB-SPECIFIC: PostgreSQL enum casts, escaped LIKE/search_fold, LIMIT and unicode collation.
    private static final String CANDIDATES = """
            select version.entry_version_id, version.number, version.version_name, entry.name,
                   question.search_way
              from entry_versions version
              join entries entry on entry.entry_id = version.entry_id
              left join question_versions question on question.entry_version_id = version.entry_version_id
             where entry.group_id = :group and entry.kind = cast(:kind as entry_kind)
               and %s and (%s or %s or version.number = :number)
               and (:way is null or question.search_way = :way)
             order by entry.name_folded collate "unicode", version.number desc
             limit 21
            """.formatted(
                    VersionMarks.inService("version"),
                    SearchFolding.holds("entry.name_folded", ":typed"),
                    SearchFolding.holds("search_fold(version.version_name)", ":typed"));

    // DB-SPECIFIC: PostgreSQL enum-to-text cast.
    private static final String KIND = """
            select cast(entry.kind as text) as kind
              from entry_versions version
              join entries entry on entry.entry_id = version.entry_id
             where entry.group_id = :group and version.entry_version_id = :version
            """;

    private final JdbcClient database;
    private final GroupRoles roles;
    private final SearchWays ways;
    private final TransactionTemplate snapshot;

    VersionCandidates(
            JdbcClient database, GroupRoles roles, SearchWays ways, PlatformTransactionManager transactionManager) {
        this.database = database;
        this.roles = roles;
        this.ways = ways;
        this.snapshot = new TransactionTemplate(transactionManager);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.snapshot.setReadOnly(true);
    }

    Page candidates(GroupId group, EntryKind kind, String typed, @Nullable String searchWay, UserId caller) {
        return requireNonNull(snapshot.execute(ignored -> {
            GroupReach.requireMember(roles.heldBy(caller, group));
            if (typed.isEmpty()) {
                return new Page(List.of(), false);
            }
            if (searchWay != null && kind != EntryKind.QUESTION) {
                throw LibraryRefusal.VERSION_NOT_IN_VIEW.raised();
            }
            List<Metadata> found = database.sql(CANDIDATES)
                    .param("group", group.value())
                    .param("kind", StoreLabels.label(kind))
                    .param("typed", typed)
                    .param("number", Library.versionNumber(typed), java.sql.Types.INTEGER)
                    .param("way", searchWay, java.sql.Types.VARCHAR)
                    .query((result, row) -> new Metadata(
                            new EntryVersionId(result.getObject("entry_version_id", UUID.class)),
                            new EntryName(result.getString("name")),
                            result.getInt("number"),
                            versionName(result.getString("version_name")),
                            result.getString("search_way")))
                    .list();
            List<Metadata> visible = found.subList(0, Math.min(20, found.size()));
            Map<EntryVersionId, StoredQuestion> questions = Map.of();
            if (kind == EntryKind.QUESTION && !visible.isEmpty()) {
                Map<EntryVersionId, EntryKind> ids = new HashMap<>();
                visible.forEach(version -> ids.put(version.id(), kind));
                questions = StoredQuestion.readAll(database, StoredDeclarations.ofVersions(database, ids));
            }
            Map<EntryVersionId, StoredQuestion> read = questions;
            List<Candidate> answers = visible.stream()
                    .map(version -> new Candidate(
                            version.name().value(),
                            version.id().value(),
                            version.number(),
                            version.versionName() == null
                                    ? null
                                    : version.versionName().value(),
                            kind == EntryKind.QUESTION ? version.searchWay() : null,
                            kind == EntryKind.QUESTION ? matches(requireNonNull(read.get(version.id()))) : null))
                    .toList();
            return new Page(answers, found.size() > 20);
        }));
    }

    Detail detail(GroupId group, EntryVersionId version, UserId caller) {
        return requireNonNull(snapshot.execute(ignored -> {
            GroupReach.requireMember(roles.heldBy(caller, group));
            EntryKind kind = database.sql(KIND)
                    .param("group", group.value())
                    .param("version", version.value())
                    .query((result, row) -> StoreLabels.parse(EntryKind.class, result.getString("kind")))
                    .optional()
                    .orElseThrow(LibraryRefusal.VERSION_NOT_IN_VIEW::raised);
            Set<EntryVersionId> lists = new LinkedHashSet<>();
            StoredDeclarations.Halves halves = null;
            StoredQuestion question = null;
            if (kind == EntryKind.REFERENCE_LIST) {
                lists.add(version);
            } else {
                halves = requireNonNull(StoredDeclarations.ofGroupVersions(database, group, List.of(version))
                        .get(version));
                halves.listsPinnedInto(lists);
                if (kind == EntryKind.QUESTION) {
                    question = requireNonNull(StoredQuestion.readAll(database, Map.of(version, halves))
                            .get(version));
                }
            }
            Map<EntryVersionId, com.lilradish.lite.domain.declaration.OfferedTerms> offered =
                    StoredQuestion.offered(database, group, lists);
            if (!offered.keySet().containsAll(lists)) {
                throw new IllegalStateException("A version's declared reference list is not held by its group");
            }
            Set<EntryVersionId> named = new LinkedHashSet<>(lists);
            named.add(version);
            Map<EntryVersionId, PinnedVersions.PinnedVersion> pins = PinnedVersions.of(database, group, caller, named);
            Map<UUID, List<String>> terms = new HashMap<>();
            offered.forEach((id, values) -> terms.put(
                    id.value(),
                    values.terms().stream().map(term -> term.term().value()).toList()));
            return new Detail(
                    kind.published(),
                    DeclarationAnswers.pinned(requireNonNull(pins.get(version))),
                    halves == null ? null : DeclarationAnswers.of(halves.takes(), pins),
                    halves == null ? null : DeclarationAnswers.of(halves.gives(), pins),
                    Map.copyOf(terms),
                    question == null ? null : question.searchWay(),
                    question == null ? null : matches(question));
        }));
    }

    private boolean matches(StoredQuestion question) {
        String marker = question.searchWay();
        SearchWay way = marker == null ? null : ways.find(marker).orElse(null);
        return way != null
                && QuestionSearchShape.matches(
                        way, question.gives().declaration().fields());
    }

    private static @Nullable VersionName versionName(@Nullable String stored) {
        return stored == null ? null : new VersionName(stored);
    }

    private record Metadata(
            EntryVersionId id,
            EntryName name,
            int number,
            @Nullable VersionName versionName,
            @Nullable String searchWay) {}

    record Page(List<Candidate> items, boolean more) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Candidate(
            String name,
            UUID versionId,
            int number,

            @JsonInclude(JsonInclude.Include.ALWAYS) @Nullable
            String versionName,

            @Nullable String searchWay,
            @Nullable Boolean searchMatches) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Detail(
            String kind,
            DeclarationAnswers.PinnedAnswer version,
            @Nullable List<DeclarationAnswers.FieldAnswer> takes,
            @Nullable List<DeclarationAnswers.FieldAnswer> gives,
            Map<UUID, List<String>> terms,
            @Nullable String searchWay,
            @Nullable Boolean searchMatches) {}
}
