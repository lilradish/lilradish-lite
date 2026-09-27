package org.lilradish.lite.app.library;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.lilradish.lite.domain.declaration.Declaration;
import org.lilradish.lite.domain.declaration.DeclarationSide;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.identity.UserId;
import org.lilradish.lite.domain.registry.EntryId;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.lilradish.lite.web.ActAdmission;
import org.lilradish.lite.web.CallerAdmission;
import org.lilradish.lite.web.MintedIdentifiers;

/**
 * The version of one kind's entry a request to its content names, in the group admitted and asked by the caller
 * admitted; an identifier this system never mints names no version in view.
 */
record VersionAddress(GroupId group, EntryId entry, EntryVersionId version, UserId caller) {

    static final String PARAMETER_REFUSED = "This version takes no parameter.";

    /**
     * Room for the most fields a half holds, each at every bound with every character escaped as a pair, and the
     * revision beside them.
     */
    static final int LARGEST_HALF = Declaration.MOST_FIELDS * 8448 + 64;

    private static final Map<String, DeclarationSide> SIDES = Arrays.stream(DeclarationSide.values())
            .collect(Collectors.toUnmodifiableMap(DeclarationSide::published, Function.identity()));

    static VersionAddress of(String entryId, String versionId, HttpServletRequest request) {
        EntryId entry = MintedIdentifiers.read(entryId)
                .map(EntryId::new)
                .orElseThrow(LibraryRefusal.VERSION_NOT_IN_VIEW::raised);
        EntryVersionId version = MintedIdentifiers.read(versionId)
                .map(EntryVersionId::new)
                .orElseThrow(LibraryRefusal.VERSION_NOT_IN_VIEW::raised);
        return new VersionAddress(
                ActAdmission.admittedGroup(request), entry, version, CallerAdmission.callerOf(request));
    }

    /** The half a path segment the mapping already held to the published spellings names. */
    static DeclarationSide sideAt(String side) {
        DeclarationSide declared = SIDES.get(side);
        if (declared == null) {
            throw new IllegalStateException("No half of a declaration is addressed as " + side);
        }
        return declared;
    }
}
