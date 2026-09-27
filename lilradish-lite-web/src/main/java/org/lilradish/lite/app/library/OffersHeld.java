package org.lilradish.lite.app.library;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.lilradish.lite.app.group.GroupRoles;
import org.lilradish.lite.domain.filling.FillField;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.identity.GroupPermission;
import org.lilradish.lite.domain.registry.EntryId;
import org.lilradish.lite.domain.registry.EntryKind;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A version a run is started of, held as offered until the start ends: its workflow, then the version, each held
 * for share, so a stop or a retirement waits on the start, and the start on either one in flight.
 */
public final class OffersHeld {

    /* Asked before the locks, so only a version offered is held, and again after them in a statement of its own:
    the one taking them reads other rows as they were before it waited. */
    private static final String OFFERED = """
            select entry.entry_id
              from entries entry
              join entry_versions version on version.entry_id = entry.entry_id
             where version.entry_version_id = :version and %s
            """.formatted(Offers.offered("entry", "version"));

    // DB-SPECIFIC: for share is PostgreSQL's.
    private static final String VERSION_HELD =
            "select 1 from entry_versions version where version.entry_version_id = :version for share";

    private OffersHeld() {}

    /**
     * Inside the caller's read-committed transaction, once the group is still reached: the version's workflow and
     * what it takes, or none where the group offers no such version now, whether or not it exists elsewhere.
     */
    public static Optional<Held> held(JdbcClient database, GroupRoles.StillReached reached, EntryVersionId version) {
        requireNonNull(reached, "OffersHeld reached must not be null");
        requireNonNull(version, "OffersHeld version must not be null");
        if (reached.permission() != GroupPermission.START_RUN) {
            throw new IllegalArgumentException(
                    "OffersHeld reached must be for starting a run, not " + reached.permission());
        }
        GroupId group = reached.group();
        Optional<EntryId> entry = offered(database, group, version);
        if (entry.isEmpty() || EntrySwitch.stoppedOnceHeld(database, group, entry.get())) {
            return Optional.empty();
        }
        database.sql(VERSION_HELD)
                .param("version", version.value())
                .query(Integer.class)
                .single();
        if (offered(database, group, version).isEmpty()) {
            return Optional.empty();
        }
        StoredDeclarations.Halves halves =
                requireNonNull(StoredDeclarations.ofVersions(database, Map.of(version, EntryKind.WORKFLOW))
                        .get(version));
        return Optional.of(new Held(
                entry.get(),
                Offers.takes(
                        database, group, version, halves.takes().declaration().fields())));
    }

    private static Optional<EntryId> offered(JdbcClient database, GroupId group, EntryVersionId version) {
        return database.sql(OFFERED)
                .param("version", version.value())
                .param("group", group.value())
                .query((result, number) -> new EntryId(result.getObject("entry_id", UUID.class)))
                .optional();
    }

    /** @param takes what whoever starts a run of it fills in, in declared order */
    public record Held(EntryId entry, List<FillField> takes) {

        public Held {
            requireNonNull(entry, "OffersHeld.Held entry must not be null");
            takes = List.copyOf(requireNonNull(takes, "OffersHeld.Held takes must not be null"));
        }
    }
}
