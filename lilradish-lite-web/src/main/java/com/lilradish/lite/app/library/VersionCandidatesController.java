package com.lilradish.lite.app.library;

import com.lilradish.lite.app.listing.ListParameters;
import com.lilradish.lite.domain.failure.RefusalCode;
import com.lilradish.lite.domain.registry.EntryVersionId;
import com.lilradish.lite.web.ActAdmission;
import com.lilradish.lite.web.CallerAdmission;
import com.lilradish.lite.web.GroupMembershipRequired;
import com.lilradish.lite.web.MintedIdentifiers;
import com.lilradish.lite.web.QueryParameters;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Searches versions without loading their collection, and reads one actual group-scoped pin independently. */
@RestController
final class VersionCandidatesController {

    static final String CANDIDATES = LibraryController.ENTRIES + "/versions";
    static final String DETAIL = ActAdmission.IN_A_GROUP + "/versions/{versionId}";
    private final VersionCandidates versions;

    VersionCandidatesController(VersionCandidates versions) {
        this.versions = versions;
    }

    @GetMapping(CANDIDATES)
    @GroupMembershipRequired
    VersionCandidates.Page candidates(@PathVariable String kind, HttpServletRequest request) {
        Map<String, String[]> sent =
                QueryParameters.sent(request, Set.of("filter", "searchWay"), ListParameters.PARAMETER_REFUSED);
        String typed = QueryParameters.soleValue(
                sent, "filter", RefusalCode.PARAMETER_UNKNOWN, ListParameters.PARAMETER_REFUSED);
        String way = QueryParameters.soleValue(
                sent, "searchWay", RefusalCode.PARAMETER_UNKNOWN, ListParameters.PARAMETER_REFUSED);
        return versions.candidates(
                ActAdmission.admittedGroup(request),
                LibraryController.kindAt(kind),
                typed == null ? "" : typed,
                way,
                CallerAdmission.callerOf(request));
    }

    @GetMapping(DETAIL)
    @GroupMembershipRequired
    VersionCandidates.Detail detail(@PathVariable String versionId, HttpServletRequest request) {
        QueryParameters.requireNone(request, ListParameters.PARAMETER_REFUSED);
        EntryVersionId version = MintedIdentifiers.read(versionId)
                .map(EntryVersionId::new)
                .orElseThrow(LibraryRefusal.VERSION_NOT_IN_VIEW::raised);
        return versions.detail(ActAdmission.admittedGroup(request), version, CallerAdmission.callerOf(request));
    }
}
