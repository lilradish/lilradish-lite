package org.lilradish.lite.app.library;

import static java.util.Objects.requireNonNull;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.identity.UserId;
import org.lilradish.lite.domain.registry.EntryKind;
import org.lilradish.lite.domain.registry.EntryName;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.lilradish.lite.domain.registry.VersionStanding;
import org.lilradish.lite.domain.wire.StoreLabels;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Versions of the group's as whatever pins one, or may pin one, names them: by the entry's name and the
 * version's number, never another group's, since nothing else may be pinned.
 */
final class PinnedVersions {

    // DB-SPECIFIC: a lateral join, limit, enum and array casts, any(…) and the collation named below are PostgreSQL's.
    private static final String PINNED = """
            select version.entry_version_id,
                   version.number,
                   entry.name,
                   newest.entry_version_id as newest_in_service,
                   newest.number as newest_number,
                   %s
              from entry_versions version
              join entries entry on entry.entry_id = version.entry_id
              left join lateral (select candidate.entry_version_id, candidate.number
                                   from entry_versions candidate
                                  where candidate.entry_id = version.entry_id and %s
                                  order by candidate.number desc
                                  limit 1) newest on true
             where version.entry_version_id = any(cast(:versions as uuid[]))
               and entry.group_id = :group
            """.formatted(VersionMarks.SELECTED, VersionMarks.inService("candidate"));

    private static final String IN_SERVICE = """
            select entry.name, version.entry_version_id, version.number
              from entry_versions version
              join entries entry on entry.entry_id = version.entry_id
             where entry.group_id = :group and entry.kind = cast(:kind as entry_kind) and %s
             order by entry.name collate "unicode", version.number desc
            """.formatted(VersionMarks.inService("version"));

    private PinnedVersions() {}

    /**
     * Inside the caller's transaction: each of {@code versions} the group holds, as a reader would name it.
     * Refused where the group holds none of one, which nothing it pins may be.
     */
    static Map<EntryVersionId, PinnedVersion> of(
            JdbcClient database, GroupId group, UserId caller, Collection<EntryVersionId> versions) {
        requireNonNull(versions, "PinnedVersions versions must not be null");
        if (versions.isEmpty()) {
            return Map.of();
        }
        Map<EntryVersionId, PinnedVersion> pins = HashMap.newHashMap(versions.size());
        database.sql(PINNED)
                .param("versions", spelled(versions))
                .param("group", group.value())
                .param("caller", caller.value())
                .query(result -> {
                    PinnedVersion pinned = pinned(result);
                    pins.put(pinned.version(), pinned);
                });
        if (!pins.keySet().containsAll(versions)) {
            // Ruled the store gone wrong: a read fails here; only the recheck of stored content names it a problem.
            throw new IllegalStateException("A version pins a version group " + group.value() + " does not hold");
        }
        return Map.copyOf(pins);
    }

    /** Each version's key as the store's uuid array reads it. */
    static String[] spelled(Collection<EntryVersionId> versions) {
        return versions.stream().map(version -> version.value().toString()).toArray(String[]::new);
    }

    /** Inside the caller's transaction: every version of the group's of that kind in service, which is all a pin may be to. */
    static List<OfferedVersion> inService(JdbcClient database, GroupId group, EntryKind kind) {
        return database.sql(IN_SERVICE)
                .param("group", group.value())
                .param("kind", StoreLabels.label(kind))
                .query((result, number) -> new OfferedVersion(
                        new EntryName(result.getString("name")),
                        new EntryVersionId(result.getObject("entry_version_id", UUID.class)),
                        result.getInt("number")))
                .list();
    }

    private static PinnedVersion pinned(ResultSet result) throws SQLException {
        EntryVersionId version = new EntryVersionId(result.getObject("entry_version_id", UUID.class));
        UUID newest = result.getObject("newest_in_service", UUID.class);
        return new PinnedVersion(
                new EntryName(result.getString("name")),
                version,
                result.getInt("number"),
                VersionMarks.standing(result, version),
                newest == null || newest.equals(version.value())
                        ? null
                        : new RetiredPinsRefusal.NumberedVersion(
                                new EntryVersionId(newest), result.getInt("newest_number")));
    }

    /** @param newer the newest of its entry in service, where that is another version; none where it is this one */
    record PinnedVersion(
            EntryName name,
            EntryVersionId version,
            int number,
            VersionStanding standing,
            RetiredPinsRefusal.@Nullable NumberedVersion newer) {}

    record OfferedVersion(EntryName name, EntryVersionId version, int number) {}
}
