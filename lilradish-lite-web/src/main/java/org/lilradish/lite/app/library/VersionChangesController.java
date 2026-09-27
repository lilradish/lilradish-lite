package org.lilradish.lite.app.library;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.identity.GroupPermission;
import org.lilradish.lite.domain.identity.UserId;
import org.lilradish.lite.domain.registry.EntryId;
import org.lilradish.lite.domain.registry.EntryKind;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.lilradish.lite.web.ActAdmission;
import org.lilradish.lite.web.CallerAdmission;
import org.lilradish.lite.web.ChangeRequests;
import org.lilradish.lite.web.GroupPermissionRequired;
import org.lilradish.lite.web.MintedIdentifiers;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Submitting a draft, withdrawing a submission, approving one and retiring a version in service, each
 * asked of a member holding the permission the act takes in the group the gate admitted them into, and
 * answered with the entry as the library reads it once the change has landed. None takes a body or a
 * parameter.
 *
 * <p>A version is addressed under its entry, and an address naming either as nothing in view, or naming
 * no identifier at all, is answered alike as no version in view. A refusal for pinning versions retired
 * since is answered as {@link RetiredPinsOutlet} answers it.
 */
@RestController
final class VersionChangesController {

    static final String VERSION = EntryChangesController.VERSIONS + "/{versionId}";

    static final String SUBMISSION = VERSION + "/submission";

    static final String APPROVAL = VERSION + "/approval";

    static final String RETIREMENT = VERSION + "/retirement";

    private final VersionChanges versions;

    private final Library library;

    VersionChangesController(VersionChanges versions, Library library) {
        this.versions = versions;
        this.library = library;
    }

    @PutMapping(SUBMISSION)
    @GroupPermissionRequired(GroupPermission.AUTHOR_ENTRY)
    LibraryController.EntryAnswer submit(
            @PathVariable String kind,
            @PathVariable String entryId,
            @PathVariable String versionId,
            HttpServletRequest request)
            throws IOException {
        Addressed addressed = addressed(kind, entryId, versionId, request);
        versions.submit(
                addressed.group(), addressed.kind(), addressed.entry(), addressed.version(), addressed.caller());
        return read(addressed);
    }

    @DeleteMapping(SUBMISSION)
    @GroupPermissionRequired(GroupPermission.AUTHOR_ENTRY)
    LibraryController.EntryAnswer withdraw(
            @PathVariable String kind,
            @PathVariable String entryId,
            @PathVariable String versionId,
            HttpServletRequest request)
            throws IOException {
        Addressed addressed = addressed(kind, entryId, versionId, request);
        versions.withdraw(
                addressed.group(), addressed.kind(), addressed.entry(), addressed.version(), addressed.caller());
        return read(addressed);
    }

    @PutMapping(APPROVAL)
    @GroupPermissionRequired(GroupPermission.APPROVE_ENTRY)
    LibraryController.EntryAnswer approve(
            @PathVariable String kind,
            @PathVariable String entryId,
            @PathVariable String versionId,
            HttpServletRequest request)
            throws IOException {
        Addressed addressed = addressed(kind, entryId, versionId, request);
        versions.approve(
                addressed.group(), addressed.kind(), addressed.entry(), addressed.version(), addressed.caller());
        return read(addressed);
    }

    @PutMapping(RETIREMENT)
    @GroupPermissionRequired(GroupPermission.REVOKE_ENTRY)
    LibraryController.EntryAnswer retire(
            @PathVariable String kind,
            @PathVariable String entryId,
            @PathVariable String versionId,
            HttpServletRequest request)
            throws IOException {
        Addressed addressed = addressed(kind, entryId, versionId, request);
        versions.retire(
                addressed.group(), addressed.kind(), addressed.entry(), addressed.version(), addressed.caller());
        return read(addressed);
    }

    private static Addressed addressed(String kind, String entryId, String versionId, HttpServletRequest request)
            throws IOException {
        EntryKind addressedKind = LibraryController.kindAt(kind);
        EntryId entry = MintedIdentifiers.read(entryId)
                .map(EntryId::new)
                .orElseThrow(LibraryRefusal.VERSION_NOT_IN_VIEW::raised);
        EntryVersionId version = MintedIdentifiers.read(versionId)
                .map(EntryVersionId::new)
                .orElseThrow(LibraryRefusal.VERSION_NOT_IN_VIEW::raised);
        ChangeRequests.requireNothingSent(request);
        return new Addressed(
                ActAdmission.admittedGroup(request), addressedKind, entry, version, CallerAdmission.callerOf(request));
    }

    private LibraryController.EntryAnswer read(Addressed addressed) {
        return LibraryController.answer(
                library.entry(addressed.group(), addressed.kind(), addressed.entry(), addressed.caller()));
    }

    private record Addressed(GroupId group, EntryKind kind, EntryId entry, EntryVersionId version, UserId caller) {}
}
