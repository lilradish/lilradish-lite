package org.lilradish.lite.app.library;

import static java.util.Objects.requireNonNull;

import java.util.Optional;
import java.util.UUID;
import org.lilradish.lite.domain.declaration.DeclarationSide;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.registry.EntryKind;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.lilradish.lite.domain.wire.StoreLabels;
import org.springframework.jdbc.core.simple.JdbcClient;

/** A draft locked for writing until the change {@link Drafts} made on it ends, and asked nothing after. */
final class OpenDraft {

    /* For share: retiring the version waits until this change ends, and a run starting it does not. */
    // DB-SPECIFIC: for share of and an enum cast to text are PostgreSQL's.
    private static final String IN_SERVICE_HERE = """
            select cast(version.entry_kind as text)
              from entry_versions version
              join entries entry on entry.entry_id = version.entry_id
             where version.entry_version_id = :target
               and entry.group_id = :group
               and %s
               for share of version
            """.formatted(VersionMarks.inService("version"));

    /* No share lock: what is kept is kept whatever it stands at, and submitting reads each pin again. */
    // DB-SPECIFIC: enum casts are PostgreSQL's.
    private static final String FIELD_PINS_HERE = """
            select cast(target.entry_kind as text)
              from declaration_fields field
              left join workflow_steps owner on owner.workflow_step_id = field.workflow_step_id
              join entry_versions target on target.entry_version_id = field.term_list_version_id
              join entries entry on entry.entry_id = target.entry_id
             where field.declaration_field_id = :field
               and coalesce(field.entry_version_id, owner.entry_version_id) = :version
               and field.side = cast(:side as declaration_side)
               and field.term_list_version_id = :target
               and entry.group_id = :group
            """;

    private static final String STEP_PINS_HERE = """
            select cast(target.entry_kind as text)
              from workflow_steps step
              join entry_versions target on target.entry_version_id = step.pinned_version_id
              join entries entry on entry.entry_id = target.entry_id
             where step.workflow_step_id = :step
               and step.entry_version_id = :version
               and step.pinned_version_id = :target
               and entry.group_id = :group
            """;

    private static final String CASE_PINS_HERE = """
            select cast(target.entry_kind as text)
              from route_cases route
              join entry_versions target on target.entry_version_id = route.target_version_id
              join entries entry on entry.entry_id = target.entry_id
             where route.route_case_id = :routeCase
               and route.entry_version_id = :version
               and route.target_version_id = :target
               and entry.group_id = :group
            """;

    private final JdbcClient database;

    private final GroupId group;

    private final EntryVersionId version;

    private final EntryKind kind;

    private boolean closed;

    OpenDraft(JdbcClient database, GroupId group, EntryVersionId version, EntryKind kind) {
        this.database = database;
        this.group = group;
        this.version = version;
        this.kind = kind;
    }

    void close() {
        closed = true;
    }

    EntryVersionId version() {
        requireOpen();
        return version;
    }

    EntryKind kind() {
        requireOpen();
        return kind;
    }

    /**
     * A version of this group's in service, held in service while this change lasts; whose any other is, or
     * whether it exists at all, is not said apart.
     */
    PinnableVersion pinInService(EntryVersionId target) {
        requireNonNull(target, "OpenDraft target must not be null");
        requireOpen();
        return database.sql(IN_SERVICE_HERE)
                .param("target", target.value())
                .param("group", group.value())
                .query(String.class)
                .optional()
                .map(label -> new PinnableVersion(this, target, StoreLabels.parse(EntryKind.class, label)))
                .orElseThrow(LibraryRefusal.VERSION_NOT_PINNABLE::raised);
    }

    /**
     * A version of this group's the draft's field keyed so on that side pins already, the draft's own or one of
     * its route steps', kept whatever it now stands at; none where that field pins something else or is not this
     * draft's, which {@link #pinInService} finds.
     */
    Optional<PinnableVersion> pinnedAlready(DeclarationSide side, UUID field, EntryVersionId target) {
        requireNonNull(side, "OpenDraft side must not be null");
        requireNonNull(field, "OpenDraft field must not be null");
        return keptAt(
                database.sql(FIELD_PINS_HERE).param("field", field).param("side", StoreLabels.label(side)), target);
    }

    /** The same of the version the draft's step keyed so pins. */
    Optional<PinnableVersion> stepPinnedAlready(UUID step, EntryVersionId target) {
        requireNonNull(step, "OpenDraft step must not be null");
        return keptAt(database.sql(STEP_PINS_HERE).param("step", step), target);
    }

    /** The same of the workflow version the draft's route case keyed so leads to. */
    Optional<PinnableVersion> casePinnedAlready(UUID routeCase, EntryVersionId target) {
        requireNonNull(routeCase, "OpenDraft routeCase must not be null");
        return keptAt(database.sql(CASE_PINS_HERE).param("routeCase", routeCase), target);
    }

    private Optional<PinnableVersion> keptAt(JdbcClient.StatementSpec pinning, EntryVersionId target) {
        requireNonNull(target, "OpenDraft target must not be null");
        requireOpen();
        return pinning.param("target", target.value())
                .param("group", group.value())
                .param("version", version.value())
                .query(String.class)
                .optional()
                .map(label -> new PinnableVersion(this, target, StoreLabels.parse(EntryKind.class, label)));
    }

    /** Asked by whatever writes a pin, before it does: only this draft's own finding, while it is open, holds. */
    void requireIssued(PinnableVersion pinnable) {
        requireNonNull(pinnable, "OpenDraft pinnable version must not be null");
        requireOpen();
        if (pinnable.issuer != this) {
            throw new IllegalStateException("OpenDraft was handed a version another draft found pinnable");
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("OpenDraft was asked after the change writing it had ended");
        }
    }

    /**
     * A version {@link #pinInService} found pinnable, and the only thing a pin is written from; it holds only
     * inside the draft and the change that found it, which {@link #requireIssued} asks.
     */
    static final class PinnableVersion {

        private final OpenDraft issuer;

        private final EntryVersionId version;

        private final EntryKind kind;

        private PinnableVersion(OpenDraft issuer, EntryVersionId version, EntryKind kind) {
            this.issuer = issuer;
            this.version = version;
            this.kind = kind;
        }

        EntryVersionId version() {
            return version;
        }

        EntryKind kind() {
            return kind;
        }
    }
}
