package org.lilradish.lite.app.library;

import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.registry.ContentProblemCode;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The code steps a group may name, read once for whatever asks it: each published to the group whose declaration,
 * as this release holds it, pins no list of another group's; one that does is as though it were not published here.
 * Of those the release holds, each list pinned that is not here or not in service is named by why.
 *
 * @param nameable in name order
 * @param listsUnserved by code step, each reason once, in the order {@link ContentProblemCode} declares them
 */
record CodeStepsHere(Set<String> nameable, Map<String, List<ContentProblemCode>> listsUnserved) {

    /** What a group that names no code step needs read of them, which is nothing. */
    static final CodeStepsHere NONE = new CodeStepsHere(Set.of(), Map.of());

    // DB-SPECIFIC: an enum cast to text is PostgreSQL's.
    private static final String PUBLISHED = """
            select distinct cast(publication.code_step as text) as code_step
              from code_step_publications publication
             where publication.every_group
                or publication.group_key = (select owner.key from groups owner where owner.group_id = :group)
             order by code_step
            """;

    /* Every group's version is read, but only to say whether it is this group's: nothing else of it leaves here. */
    // DB-SPECIFIC: array casts and any(…) are PostgreSQL's.
    private static final String LISTS = """
            select version.entry_version_id,
                   entry.group_id = :group as ours,
                   %s as in_service,
                   version.retired_at is not null as retired
              from entry_versions version
              join entries entry on entry.entry_id = version.entry_id
             where version.entry_version_id = any(cast(:lists as uuid[]))
            """.formatted(VersionMarks.inService("version"));

    CodeStepsHere {
        nameable = Collections.unmodifiableSet(
                new LinkedHashSet<>(requireNonNull(nameable, "CodeStepsHere nameable must not be null")));
        Map<String, List<ContentProblemCode>> copied = new HashMap<>();
        requireNonNull(listsUnserved, "CodeStepsHere listsUnserved must not be null")
                .forEach((name, reasons) -> copied.put(name, List.copyOf(reasons)));
        listsUnserved = Map.copyOf(copied);
    }

    /** Inside the caller's transaction: two statements, however many code steps and lists there are. */
    static CodeStepsHere read(JdbcClient database, GroupId group, ReleasedCodeSteps released) {
        List<String> published = database.sql(PUBLISHED)
                .param("group", group.value())
                .query((result, number) -> requireNonNull(result.getString("code_step")))
                .list();
        Map<String, Set<EntryVersionId>> pinnedBy = new LinkedHashMap<>();
        Set<EntryVersionId> lists = new LinkedHashSet<>();
        for (String name : published) {
            StoredDeclarations.Halves declared = released.of(name);
            if (declared != null) {
                Set<EntryVersionId> pinned = new LinkedHashSet<>();
                declared.listsPinnedInto(pinned);
                pinnedBy.put(name, pinned);
                lists.addAll(pinned);
            }
        }
        Map<EntryVersionId, ListRow> rows = HashMap.newHashMap(lists.size());
        if (!lists.isEmpty()) {
            database.sql(LISTS)
                    .param("group", group.value())
                    .param("lists", PinnedVersions.spelled(lists))
                    .query(result -> {
                        rows.put(
                                new EntryVersionId(result.getObject("entry_version_id", UUID.class)),
                                new ListRow(
                                        result.getBoolean("ours"),
                                        result.getBoolean("in_service"),
                                        result.getBoolean("retired")));
                    });
        }
        Set<String> nameable = new LinkedHashSet<>();
        Map<String, List<ContentProblemCode>> listsUnserved = new HashMap<>();
        for (String name : published) {
            Set<EntryVersionId> pinned = pinnedBy.getOrDefault(name, Set.of());
            if (pinned.stream().anyMatch(list -> rows.get(list) instanceof ListRow row && !row.ours())) {
                continue;
            }
            nameable.add(name);
            Set<ContentProblemCode> unserved = EnumSet.noneOf(ContentProblemCode.class);
            for (EntryVersionId list : pinned) {
                ListRow row = rows.get(list);
                if (row == null) {
                    unserved.add(ContentProblemCode.CODE_STEP_LIST_MISSING);
                } else if (row.retired()) {
                    unserved.add(ContentProblemCode.CODE_STEP_LIST_RETIRED);
                } else if (!row.inService()) {
                    unserved.add(ContentProblemCode.CODE_STEP_LIST_NOT_YET_IN_SERVICE);
                }
            }
            if (!unserved.isEmpty()) {
                listsUnserved.put(name, List.copyOf(unserved));
            }
        }
        return new CodeStepsHere(nameable, listsUnserved);
    }

    /** Whether it may be named here, and every list it pins is in service; none other is offered to be chosen. */
    boolean offered(String name) {
        return nameable.contains(name) && !listsUnserved.containsKey(name);
    }

    private record ListRow(boolean ours, boolean inService, boolean retired) {}
}
