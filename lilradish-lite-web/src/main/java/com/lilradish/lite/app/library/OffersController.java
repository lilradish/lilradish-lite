package com.lilradish.lite.app.library;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.lilradish.lite.app.filling.FillFieldAnswer;
import com.lilradish.lite.app.group.GroupLists;
import com.lilradish.lite.app.listing.ListParameters;
import com.lilradish.lite.app.listing.PageAnswer;
import com.lilradish.lite.domain.identity.GroupPermission;
import com.lilradish.lite.domain.listing.ListCursor;
import com.lilradish.lite.domain.listing.ListPage;
import com.lilradish.lite.domain.listing.ListPosition;
import com.lilradish.lite.domain.listing.ListQuery;
import com.lilradish.lite.domain.registry.EntryPurpose;
import com.lilradish.lite.web.ActAdmission;
import com.lilradish.lite.web.GroupPermissionRequired;
import com.lilradish.lite.web.QueryParameters;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the group the gate admitted the caller into may start a run of, answered only to one who may start one:
 * each version in service with every field it takes and the terms each offers, so no list is read to start it.
 */
@RestController
final class OffersController {

    static final String OFFERED = ActAdmission.IN_A_GROUP + "/offered-workflows";

    private static final Set<String> PARAMETERS = Set.of("filter", "cursor");

    private final Offers offers;

    OffersController(Offers offers) {
        this.offers = offers;
    }

    @GetMapping(OFFERED)
    @GroupPermissionRequired(GroupPermission.START_RUN)
    PageAnswer<WorkflowAnswer> offered(HttpServletRequest request) {
        Map<String, String[]> sent = QueryParameters.sent(request, PARAMETERS, ListParameters.PARAMETER_REFUSED);
        var shape = Offers.listed();
        ListQuery<Offers.Column> query = ListParameters.spacedAsNames(
                ListParameters.query(shape, GroupLists.within(ActAdmission.admittedGroup(request)), sent));
        ListPosition after = ListParameters.after(shape, query, sent);
        ListPage<Offers.Offered> page = offers.offeredIn(query, after);
        ListPosition next = page.next();
        return new PageAnswer<>(
                page.rows().stream().map(OffersController::answer).toList(),
                next == null ? null : ListCursor.mint(shape, query, next));
    }

    private static WorkflowAnswer answer(Offers.Offered offered) {
        EntryPurpose purpose = offered.purpose();
        return new WorkflowAnswer(
                offered.entryId().value(),
                offered.name().value(),
                purpose == null ? null : purpose.value(),
                offered.versions().stream()
                        .map(version -> new VersionAnswer(
                                version.version().value(),
                                version.number(),
                                FillFieldAnswer.of(version.takes()),
                                version.versionName() == null
                                        ? null
                                        : version.versionName().value()))
                        .toList());
    }

    /**
     * A purpose saying nothing of what it is for is left out.
     *
     * @param versions newest first
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record WorkflowAnswer(
            UUID entryId, String name, @Nullable String purpose, List<VersionAnswer> versions) {}

    /** @param takes in declared order */
    record VersionAnswer(
            UUID versionId,
            int number,
            List<FillFieldAnswer> takes,
            @Nullable String versionName) {}
}
