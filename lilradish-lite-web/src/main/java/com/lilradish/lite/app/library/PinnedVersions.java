package com.lilradish.lite.app.library;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.identity.GroupId;
import com.lilradish.lite.domain.identity.UserId;
import com.lilradish.lite.domain.registry.EntryName;
import com.lilradish.lite.domain.registry.EntryVersionId;
import com.lilradish.lite.domain.registry.VersionName;
import com.lilradish.lite.domain.registry.VersionStanding;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Versions are named by entry name and version number within their group; no other group's version may be pinned. */
final class PinnedVersions {

    // DB-SPECIFIC: PostgreSQL lateral joins, LIMIT, native enum-label comparisons and ANY over uuid[] arrays.
    private static final String PINNED = """
            select version.entry_version_id,
                   version.number,
                   version.version_name,
                   entry.name,
                   newest.entry_version_id as newest_in_service,
                   newest.number as newest_number,
                   newest.version_name as newest_name,
                   %s
              from entry_versions version
              join entries entry on entry.entry_id = version.entry_id
              left join lateral (select candidate.entry_version_id, candidate.number, candidate.version_name
                                   from entry_versions candidate
                                  where candidate.entry_id = version.entry_id and %s
                                  order by candidate.number desc
                                  limit 1) newest on true
             where version.entry_version_id = any(cast(:versions as uuid[]))
               and entry.group_id = :group
            """.formatted(VersionMarks.SELECTED, VersionMarks.inService("candidate"));

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
            // Reached only when stored data is inconsistent: a read fails here, and only a recheck of stored content
            // names it a problem.
            throw new IllegalStateException("A version pins a version group " + group.value() + " does not hold");
        }
        return Map.copyOf(pins);
    }

    /** Each version's key as the store's uuid array reads it. */
    static String[] spelled(Collection<EntryVersionId> versions) {
        return versions.stream().map(version -> version.value().toString()).toArray(String[]::new);
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
                                new EntryVersionId(newest),
                                result.getInt("newest_number"),
                                versionName(result.getString("newest_name"))),
                versionName(result.getString("version_name")));
    }

    private static @Nullable VersionName versionName(@Nullable String stored) {
        return stored == null ? null : new VersionName(stored);
    }

    /** @param newer the newest of its entry in service, where that is another version; none where it is this one */
    record PinnedVersion(
            EntryName name,
            EntryVersionId version,
            int number,
            VersionStanding standing,
            RetiredPinsRefusal.@Nullable NumberedVersion newer,
            @Nullable VersionName versionName) {}
}
