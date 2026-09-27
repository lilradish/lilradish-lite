package org.lilradish.lite.app.library;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.app.group.GroupLists;
import org.lilradish.lite.app.listing.ListParameters;
import org.lilradish.lite.app.listing.PageAnswer;
import org.lilradish.lite.app.pool.PersonAnswer;
import org.lilradish.lite.domain.listing.ListCursor;
import org.lilradish.lite.domain.listing.ListPage;
import org.lilradish.lite.domain.listing.ListPosition;
import org.lilradish.lite.domain.listing.ListQuery;
import org.lilradish.lite.domain.listing.ListShape;
import org.lilradish.lite.domain.registry.EntryAct;
import org.lilradish.lite.domain.registry.EntryId;
import org.lilradish.lite.domain.registry.EntryKind;
import org.lilradish.lite.domain.registry.EntryPurpose;
import org.lilradish.lite.domain.registry.LibrarySortColumn;
import org.lilradish.lite.domain.registry.VersionAct;
import org.lilradish.lite.web.ActAdmission;
import org.lilradish.lite.web.CallerAdmission;
import org.lilradish.lite.web.GroupMembershipRequired;
import org.lilradish.lite.web.MintedIdentifiers;
import org.lilradish.lite.web.QueryParameters;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * A group's entries of one kind one page at a time, and one entry with every version of it, answered to
 * anybody holding a role in the group the gate admitted them into, which is all reading its library asks.
 *
 * <p>Each kind answers under an address of its own, spelt as the page listing it is. An entry is
 * addressed by the identifier this system minted for it, under its own kind and its own group: an entry
 * of another kind, another group's, one nobody holds and anything that is no identifier are answered
 * alike, as no entry in view.
 *
 * <p>An entry is answered with what the caller may do to it and to each version as the store now stands,
 * which is what a page draws its controls from rather than working it out again.
 */
@RestController
final class LibraryController {

    private static final String KIND = "kind";

    /* The alternatives are each kind's segment, which a spec holds level with EntryKind's. */
    static final String ENTRIES = ActAdmission.IN_A_GROUP + "/{" + KIND + ":workflows|questions|reference-lists}";

    static final String ENTRY = ENTRIES + "/{entryId}";

    private static final Map<String, EntryKind> BY_SEGMENT = Arrays.stream(EntryKind.values())
            .collect(Collectors.toUnmodifiableMap(EntryKind::segment, Function.identity()));

    private static final String PARAMETER_REFUSED = "This entry takes no parameter.";

    private final Library library;

    LibraryController(Library library) {
        this.library = library;
    }

    @GetMapping(ENTRIES)
    @GroupMembershipRequired
    PageAnswer<EntryRowAnswer> entries(@PathVariable String kind, HttpServletRequest request) {
        EntryKind listed = kindAt(kind);
        ListShape<LibrarySortColumn> shape = Library.listed(listed);
        Map<String, String[]> sent =
                QueryParameters.sent(request, ListParameters.TAKEN, ListParameters.PARAMETER_REFUSED);
        ListQuery<LibrarySortColumn> query = ListParameters.spacedAsNames(
                ListParameters.query(shape, GroupLists.within(ActAdmission.admittedGroup(request)), sent));
        ListPosition after = ListParameters.after(shape, query, sent);
        ListPage<Library.LibraryRow> page = library.page(listed, query, after);
        ListPosition next = page.next();
        return new PageAnswer<>(
                page.rows().stream().map(LibraryController::rowAnswer).toList(),
                next == null ? null : ListCursor.mint(shape, query, next));
    }

    @GetMapping(ENTRY)
    @GroupMembershipRequired
    EntryAnswer entry(@PathVariable String kind, @PathVariable String entryId, HttpServletRequest request) {
        EntryKind addressed = kindAt(kind);
        EntryId entry = entryAt(entryId);
        QueryParameters.requireNone(request, PARAMETER_REFUSED);
        return answer(library.entry(
                ActAdmission.admittedGroup(request), addressed, entry, CallerAdmission.callerOf(request)));
    }

    /** Only ever asked of a segment the dispatcher matched, so one naming no kind is this class's own fault. */
    static EntryKind kindAt(String segment) {
        EntryKind kind = BY_SEGMENT.get(segment);
        if (kind == null) {
            throw new IllegalStateException("No kind of entry is addressed as " + segment);
        }
        return kind;
    }

    static EntryId entryAt(String spelled) {
        return MintedIdentifiers.read(spelled).map(EntryId::new).orElseThrow(LibraryRefusal.ENTRY_NOT_IN_VIEW::raised);
    }

    /** The entry as the library now reads it, which is what every change to it answers with too. */
    static EntryAnswer answer(Library.EntryView entry) {
        EntryPurpose purpose = entry.purpose();
        Library.Stop stop = entry.stop();
        return new EntryAnswer(
                entry.entryId().value(),
                entry.kind().published(),
                entry.name().value(),
                purpose == null ? null : purpose.value(),
                stop == null
                        ? null
                        : new StopAnswer(PersonAnswer.of(stop.by()), stop.at().toString()),
                entry.acts().stream().map(EntryAct::published).toList(),
                entry.versions().stream().map(LibraryController::versionAnswer).toList());
    }

    private static EntryRowAnswer rowAnswer(Library.LibraryRow row) {
        return new EntryRowAnswer(
                row.entryId().value(), row.name().value(), row.inService(), row.submitted(), row.stopped());
    }

    private static VersionAnswer versionAnswer(Library.VersionView version) {
        return new VersionAnswer(
                version.versionId().value(),
                version.number(),
                version.revision(),
                version.standing().published(),
                version.writers().stream().map(PersonAnswer::of).toList(),
                version.startedByMigration(),
                approvalAnswer(version.approval()),
                version.acts().stream().map(VersionAct::published).toList(),
                pinsAnswer(version.pinnedBy()));
    }

    private static @Nullable ApprovalAnswer approvalAnswer(Library.@Nullable Approval approval) {
        return switch (approval) {
            case null -> null;
            case Library.Approval.ByPerson byPerson -> new ApprovalAnswer(PersonAnswer.of(byPerson.approver()));
            case Library.Approval.ByMigration ignored -> new ApprovalAnswer(null);
        };
    }

    private static List<PinnedByAnswer> pinsAnswer(Collection<Library.PinnedBy> pins) {
        return pins.stream()
                .map(pin -> new PinnedByAnswer(
                        pin.entryId().value(),
                        pin.kind().published(),
                        pin.name().value(),
                        pin.versionId().value(),
                        pin.number()))
                .toList();
    }

    /** @param inService the number of the newest version in service, absent where none is */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record EntryRowAnswer(
            UUID entryId, String name, @Nullable Integer inService, boolean submitted, boolean stopped) {}

    /**
     * @param purpose absent where the entry says nothing of what it is for
     * @param stopped absent where the entry may be run
     * @param acts what the caller may do to the entry as a whole, by their published spellings
     * @param versions newest first
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record EntryAnswer(
            UUID entryId,
            String kind,
            String name,
            @Nullable String purpose,
            @Nullable StopAnswer stopped,
            List<String> acts,
            List<VersionAnswer> versions) {}

    /** @param at the instant it was stopped, as ISO 8601 */
    record StopAnswer(PersonAnswer by, String at) {}

    /**
     * @param revision what a write to its content sends back as the one it read
     * @param writers everyone who wrote it, in the order they first did; none only where a migration did
     * @param writtenByMigration whether a migration started it, which nobody here did
     * @param approval absent where it has not been put into service
     * @param acts what the caller may do to it as it stands, by their published spellings
     * @param pinnedBy every version in service pinning it, each named
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record VersionAnswer(
            UUID versionId,
            int number,
            int revision,
            String standing,
            List<PersonAnswer> writers,
            boolean writtenByMigration,
            @Nullable ApprovalAnswer approval,
            List<String> acts,
            List<PinnedByAnswer> pinnedBy) {}

    /** @param approver absent where a migration put it into service, which nobody did */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ApprovalAnswer(@Nullable PersonAnswer approver) {}

    /** @param versionId the version pinning it, which is in service */
    record PinnedByAnswer(UUID entryId, String kind, String name, UUID versionId, int number) {}
}
