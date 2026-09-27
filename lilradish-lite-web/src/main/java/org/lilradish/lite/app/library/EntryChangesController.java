package org.lilradish.lite.app.library;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.error.ApiErrorException;
import org.lilradish.lite.domain.failure.RefusalCode;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.identity.GroupPermission;
import org.lilradish.lite.domain.identity.UserId;
import org.lilradish.lite.domain.registry.EntryId;
import org.lilradish.lite.domain.registry.EntryKind;
import org.lilradish.lite.domain.registry.EntryName;
import org.lilradish.lite.domain.registry.EntryPurpose;
import org.lilradish.lite.web.ActAdmission;
import org.lilradish.lite.web.CallerAdmission;
import org.lilradish.lite.web.ChangeRequests;
import org.lilradish.lite.web.GroupPermissionRequired;
import org.lilradish.lite.web.JsonBody;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

/**
 * Starting an entry, renaming it, stopping it being used and letting it go again, and starting its next
 * draft, each asked of a member holding the permission the act takes in the group the gate admitted them
 * into, and each answered with the entry as the library reads it once the change has landed. A draft
 * started is answered with no address of its own, no version being read on its own.
 *
 * <p>An entry is addressed as {@link LibraryController} addresses one, and an address naming no identifier
 * is refused before anything is read off the request. Starting and renaming take a body read as
 * {@link JsonBody} reads one: a name and what the entry is for, which is null where it says nothing. The
 * others take none, and no change takes a parameter.
 */
@RestController
final class EntryChangesController {

    static final String STOP = LibraryController.ENTRY + "/stop";

    static final String VERSIONS = LibraryController.ENTRY + "/versions";

    private static final String NAME = "name";

    private static final String PURPOSE = "purpose";

    /** Room for the longest name and purpose with every character escaped, and their members. */
    private static final int LARGEST_BODY = 8192;

    private static final String BODY_REFUSED =
            "This takes a JSON object holding an entry's name, and what it is for or null, and nothing else.";

    private final EntryChanges entries;

    private final VersionChanges versions;

    private final Library library;

    EntryChangesController(EntryChanges entries, VersionChanges versions, Library library) {
        this.entries = entries;
        this.versions = versions;
        this.library = library;
    }

    @PostMapping(LibraryController.ENTRIES)
    @GroupPermissionRequired(GroupPermission.AUTHOR_ENTRY)
    ResponseEntity<LibraryController.EntryAnswer> start(@PathVariable String kind, HttpServletRequest request)
            throws IOException {
        EntryKind started = LibraryController.kindAt(kind);
        GroupId group = ActAdmission.admittedGroup(request);
        ChangeRequests.requireNoParameter(request);
        Described described = describedIn(request);
        UserId caller = CallerAdmission.callerOf(request);
        EntryId entry = entries.start(group, started, described.name(), described.purpose(), caller);
        return ResponseEntity.created(UriComponentsBuilder.fromPath(LibraryController.ENTRY)
                        .buildAndExpand(group.value(), kind, entry.value())
                        .toUri())
                .body(read(group, started, entry, caller));
    }

    @PatchMapping(LibraryController.ENTRY)
    @GroupPermissionRequired(GroupPermission.AUTHOR_ENTRY)
    LibraryController.EntryAnswer rename(
            @PathVariable String kind, @PathVariable String entryId, HttpServletRequest request) throws IOException {
        EntryKind renamed = LibraryController.kindAt(kind);
        EntryId entry = LibraryController.entryAt(entryId);
        GroupId group = ActAdmission.admittedGroup(request);
        ChangeRequests.requireNoParameter(request);
        Described described = describedIn(request);
        UserId caller = CallerAdmission.callerOf(request);
        entries.rename(group, renamed, entry, described.name(), described.purpose(), caller);
        return read(group, renamed, entry, caller);
    }

    @PutMapping(STOP)
    @GroupPermissionRequired(GroupPermission.REVOKE_ENTRY)
    LibraryController.EntryAnswer stop(
            @PathVariable String kind, @PathVariable String entryId, HttpServletRequest request) throws IOException {
        EntryKind stopped = LibraryController.kindAt(kind);
        EntryId entry = LibraryController.entryAt(entryId);
        ChangeRequests.requireNothingSent(request);
        GroupId group = ActAdmission.admittedGroup(request);
        UserId caller = CallerAdmission.callerOf(request);
        entries.stop(group, stopped, entry, caller);
        return read(group, stopped, entry, caller);
    }

    @DeleteMapping(STOP)
    @GroupPermissionRequired(GroupPermission.REVOKE_ENTRY)
    LibraryController.EntryAnswer letGo(
            @PathVariable String kind, @PathVariable String entryId, HttpServletRequest request) throws IOException {
        EntryKind letGo = LibraryController.kindAt(kind);
        EntryId entry = LibraryController.entryAt(entryId);
        ChangeRequests.requireNothingSent(request);
        GroupId group = ActAdmission.admittedGroup(request);
        UserId caller = CallerAdmission.callerOf(request);
        entries.letGo(group, letGo, entry, caller);
        return read(group, letGo, entry, caller);
    }

    @PostMapping(VERSIONS)
    @GroupPermissionRequired(GroupPermission.AUTHOR_ENTRY)
    @ResponseStatus(HttpStatus.CREATED)
    LibraryController.EntryAnswer startDraft(
            @PathVariable String kind, @PathVariable String entryId, HttpServletRequest request) throws IOException {
        EntryKind drafted = LibraryController.kindAt(kind);
        EntryId entry = LibraryController.entryAt(entryId);
        ChangeRequests.requireNothingSent(request);
        GroupId group = ActAdmission.admittedGroup(request);
        UserId caller = CallerAdmission.callerOf(request);
        versions.startDraft(group, drafted, entry, caller);
        return read(group, drafted, entry, caller);
    }

    private LibraryController.EntryAnswer read(GroupId group, EntryKind kind, EntryId entry, UserId caller) {
        return LibraryController.answer(library.entry(group, kind, entry, caller));
    }

    /** Each member is refused under its own code once the body holds exactly the two, each of its type. */
    private static Described describedIn(HttpServletRequest request) throws IOException {
        JsonNode body = JsonBody.read(request, LARGEST_BODY, BODY_REFUSED);
        JsonNode purpose = body.path(PURPOSE);
        if (!body.isObject()
                || body.size() != 2
                || !body.path(NAME).isString()
                || !(purpose.isString() || purpose.isNull())) {
            throw new ApiErrorException(RefusalCode.BODY_UNUSABLE, BODY_REFUSED);
        }
        EntryName name;
        try {
            name = new EntryName(body.get(NAME).asString());
        } catch (IllegalArgumentException refused) {
            throw LibraryRefusal.ENTRY_NAME_UNUSABLE.raised(refused);
        }
        if (purpose.isNull()) {
            return new Described(name, null);
        }
        try {
            return new Described(name, new EntryPurpose(purpose.asString()));
        } catch (IllegalArgumentException refused) {
            throw LibraryRefusal.ENTRY_PURPOSE_UNUSABLE.raised(refused);
        }
    }

    /** A name, and what the entry is for, or none where it says nothing. */
    private record Described(EntryName name, @Nullable EntryPurpose purpose) {}
}
