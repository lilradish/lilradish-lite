package com.lilradish.lite.app.library;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.lilradish.lite.app.group.GroupLists;
import com.lilradish.lite.app.listing.ListParameters;
import com.lilradish.lite.app.listing.PageAnswer;
import com.lilradish.lite.app.pool.PersonAnswer;
import com.lilradish.lite.domain.failure.RefusalCode;
import com.lilradish.lite.domain.listing.ListCursor;
import com.lilradish.lite.domain.listing.ListPage;
import com.lilradish.lite.domain.listing.ListPosition;
import com.lilradish.lite.domain.listing.ListQuery;
import com.lilradish.lite.domain.listing.ListShape;
import com.lilradish.lite.domain.registry.EntryAct;
import com.lilradish.lite.domain.registry.EntryId;
import com.lilradish.lite.domain.registry.EntryKind;
import com.lilradish.lite.domain.registry.EntryPurpose;
import com.lilradish.lite.domain.registry.EntryVersionId;
import com.lilradish.lite.domain.registry.LibrarySortColumn;
import com.lilradish.lite.domain.registry.VersionAct;
import com.lilradish.lite.web.ActAdmission;
import com.lilradish.lite.web.CallerAdmission;
import com.lilradish.lite.web.GroupMembershipRequired;
import com.lilradish.lite.web.JsonBody;
import com.lilradish.lite.web.MembershipReadOnlyPost;
import com.lilradish.lite.web.MintedIdentifiers;
import com.lilradish.lite.web.QueryParameters;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.error.ApiErrorException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

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

    static final String VERSION_NAME_MATCHES = ENTRIES + "/version-name-matches";

    private static final String MATCH_BODY_REFUSED =
            "This takes a JSON object holding text and version identifiers, and nothing else.";

    private static final Map<String, EntryKind> BY_SEGMENT = Arrays.stream(EntryKind.values())
            .collect(Collectors.toUnmodifiableMap(EntryKind::segment, Function.identity()));

    private static final String PARAMETER_REFUSED = "This entry takes no parameter.";

    private final Library library;

    LibraryController(Library library) {
        this.library = library;
    }

    @PostMapping(VERSION_NAME_MATCHES)
    @GroupMembershipRequired
    @MembershipReadOnlyPost
    VersionMatchesAnswer versionNameMatches(@PathVariable String kind, HttpServletRequest request) throws IOException {
        QueryParameters.requireNone(request, PARAMETER_REFUSED);
        JsonNode body = JsonBody.read(request, 1024 * 1024, MATCH_BODY_REFUSED);
        if (!body.isObject()
                || body.size() != 2
                || !body.path("text").isString()
                || !body.path("versions").isArray()) {
            throw matchBodyRefused();
        }
        List<EntryVersionId> candidates = new ArrayList<>();
        for (JsonNode candidate : body.path("versions")) {
            if (!candidate.isString()) {
                throw matchBodyRefused();
            }
            EntryVersionId version = MintedIdentifiers.read(candidate.asString())
                    .map(EntryVersionId::new)
                    .orElseThrow(LibraryController::matchBodyRefused);
            candidates.add(version);
        }
        return new VersionMatchesAnswer(library.versionNameMatches(
                ActAdmission.admittedGroup(request),
                kindAt(kind),
                candidates,
                body.path("text").asString(),
                CallerAdmission.callerOf(request)));
    }

    private static ApiErrorException matchBodyRefused() {
        return new ApiErrorException(RefusalCode.BODY_UNUSABLE, MATCH_BODY_REFUSED);
    }

    record VersionMatchesAnswer(List<UUID> versions) {}

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
                entry.stateActs() == null
                        ? null
                        : entry.stateActs().stream().map(EntryAct::published).toList(),
                entry.versions().stream().map(LibraryController::versionAnswer).toList());
    }

    private static EntryRowAnswer rowAnswer(Library.LibraryRow row) {
        EntryPurpose purpose = row.purpose();
        return new EntryRowAnswer(
                row.entryId().value(),
                row.name().value(),
                purpose == null ? null : purpose.value(),
                row.inService(),
                row.submitted(),
                row.stopped(),
                row.versionName() == null ? null : row.versionName().value());
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
                version.stateActs() == null
                        ? null
                        : version.stateActs().stream()
                                .map(VersionAct::published)
                                .toList(),
                pinsAnswer(version.pinnedBy()),
                version.versionName() == null ? null : version.versionName().value());
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
                        pin.number(),
                        pin.versionName() == null ? null : pin.versionName().value()))
                .toList();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record EntryRowAnswer(
            UUID entryId,
            String name,
            @Nullable String purpose,
            @Nullable Integer inService,
            boolean submitted,
            boolean stopped,

            @JsonInclude(JsonInclude.Include.ALWAYS) @Nullable
            String versionName) {}

    /**
     * @param purpose absent where the entry says nothing of what it is for
     * @param stopped absent where the entry may be run
     * @param stateActs state facts by their published spellings; null does not describe actions in this read
     * @param versions newest first
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record EntryAnswer(
            UUID entryId,
            String kind,
            String name,
            @Nullable String purpose,
            @Nullable StopAnswer stopped,

            @JsonInclude(JsonInclude.Include.ALWAYS) @Nullable
            List<String> stateActs,

            List<VersionAnswer> versions) {}

    /** @param at the instant it was stopped, as ISO 8601 */
    record StopAnswer(PersonAnswer by, String at) {}

    /**
     * @param revision what a write to its content sends back as the one it read
     * @param writers everyone who wrote it, in the order they first did; none only where a migration did
     * @param writtenByMigration whether a migration started it, which nobody here did
     * @param approval absent where it has not been put into service
     * @param stateActs state facts by their published spellings; null does not describe actions in this read
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

            @JsonInclude(JsonInclude.Include.ALWAYS) @Nullable
            List<String> stateActs,

            List<PinnedByAnswer> pinnedBy,

            @JsonInclude(JsonInclude.Include.ALWAYS) @Nullable
            String versionName) {}

    /** @param approver absent where a migration put it into service, which nobody did */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ApprovalAnswer(@Nullable PersonAnswer approver) {}

    /** @param versionId the version pinning it, which is in service */
    record PinnedByAnswer(
            UUID entryId,
            String kind,
            String name,
            UUID versionId,
            int number,
            @Nullable String versionName) {}
}
