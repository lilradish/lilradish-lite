package com.lilradish.lite.app.library;

import com.lilradish.lite.domain.failure.RefusalCode;
import com.lilradish.lite.domain.identity.GroupId;
import com.lilradish.lite.domain.identity.GroupPermission;
import com.lilradish.lite.domain.identity.UserId;
import com.lilradish.lite.domain.registry.EntryId;
import com.lilradish.lite.domain.registry.EntryKind;
import com.lilradish.lite.domain.registry.EntryVersionId;
import com.lilradish.lite.domain.registry.VersionName;
import com.lilradish.lite.web.ActAdmission;
import com.lilradish.lite.web.CallerAdmission;
import com.lilradish.lite.web.ChangeRequests;
import com.lilradish.lite.web.GroupPermissionRequired;
import com.lilradish.lite.web.JsonBody;
import com.lilradish.lite.web.MintedIdentifiers;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.error.ApiErrorException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Submitting a draft, withdrawing a submission, approving one and retiring a version in service, each
 * asked of a member holding the permission the act takes in the group the gate admitted them into, and
 * answered with the entry as the library reads it once the change has landed. Naming takes a name,
 * empty to clear it; the standing changes take no body, and none takes a parameter.
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

    static final String NAME = VERSION + "/name";

    private static final String NAME_BODY_REFUSED =
            "This takes a JSON object holding a version's name, empty for none, and nothing else.";

    private final VersionChanges versions;

    private final Library library;

    VersionChangesController(VersionChanges versions, Library library) {
        this.versions = versions;
        this.library = library;
    }

    @PutMapping(NAME)
    @GroupPermissionRequired(GroupPermission.AUTHOR_ENTRY)
    LibraryController.EntryAnswer name(
            @PathVariable String kind,
            @PathVariable String entryId,
            @PathVariable String versionId,
            HttpServletRequest request)
            throws IOException {
        Addressed addressed = address(kind, entryId, versionId, request);
        ChangeRequests.requireNoParameter(request);
        JsonNode body = JsonBody.read(request, 1024, NAME_BODY_REFUSED);
        if (!body.isObject() || body.size() != 1 || !body.path("name").isString()) {
            throw new ApiErrorException(RefusalCode.BODY_UNUSABLE, NAME_BODY_REFUSED);
        }
        @Nullable VersionName name = null;
        String typed = body.path("name").asString();
        if (!typed.isEmpty()) {
            try {
                name = new VersionName(typed);
            } catch (IllegalArgumentException refused) {
                throw LibraryRefusal.VERSION_NAME_UNUSABLE.raised(refused);
            }
        }
        versions.name(
                addressed.group(), addressed.kind(), addressed.entry(), addressed.version(), name, addressed.caller());
        return read(addressed);
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
        Addressed addressed = address(kind, entryId, versionId, request);
        ChangeRequests.requireNothingSent(request);
        return addressed;
    }

    private static Addressed address(String kind, String entryId, String versionId, HttpServletRequest request) {
        EntryKind addressedKind = LibraryController.kindAt(kind);
        EntryId entry = MintedIdentifiers.read(entryId)
                .map(EntryId::new)
                .orElseThrow(LibraryRefusal.VERSION_NOT_IN_VIEW::raised);
        EntryVersionId version = MintedIdentifiers.read(versionId)
                .map(EntryVersionId::new)
                .orElseThrow(LibraryRefusal.VERSION_NOT_IN_VIEW::raised);
        return new Addressed(
                ActAdmission.admittedGroup(request), addressedKind, entry, version, CallerAdmission.callerOf(request));
    }

    private LibraryController.EntryAnswer read(Addressed addressed) {
        return LibraryController.answer(
                library.entry(addressed.group(), addressed.kind(), addressed.entry(), addressed.caller()));
    }

    private record Addressed(GroupId group, EntryKind kind, EntryId entry, EntryVersionId version, UserId caller) {}
}
