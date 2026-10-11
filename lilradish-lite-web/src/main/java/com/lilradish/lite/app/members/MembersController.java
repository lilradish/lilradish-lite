package com.lilradish.lite.app.members;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.lilradish.lite.app.group.GroupLists;
import com.lilradish.lite.app.listing.ListParameters;
import com.lilradish.lite.app.listing.PageAnswer;
import com.lilradish.lite.domain.identity.GroupId;
import com.lilradish.lite.domain.identity.GroupPermission;
import com.lilradish.lite.domain.identity.GroupRole;
import com.lilradish.lite.domain.identity.SubjectId;
import com.lilradish.lite.domain.listing.ListCursor;
import com.lilradish.lite.domain.listing.ListPage;
import com.lilradish.lite.domain.listing.ListPosition;
import com.lilradish.lite.domain.listing.ListQuery;
import com.lilradish.lite.domain.members.MemberSortColumn;
import com.lilradish.lite.domain.people.PersonName;
import com.lilradish.lite.web.ActAdmission;
import com.lilradish.lite.web.GroupPermissionRequired;
import com.lilradish.lite.web.MintedIdentifiers;
import com.lilradish.lite.web.QueryParameters;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * A group's members one page at a time, and one member of it, answered only to a member who may see
 * them. The group is the one the gate admitted the caller into.
 *
 * <p>A member is addressed only by the identifier this system minted for them, and anything else
 * addresses nobody.
 *
 * <p>The query parameters are read as {@link QueryParameters} reads them, and the filter is spaced as
 * {@link ListParameters#spacedAsNames} spaces one.
 */
@RestController
final class MembersController {

    static final String MEMBERS = ActAdmission.IN_A_GROUP + "/members";

    static final String MEMBER = MEMBERS + "/{subjectId}";

    static final String MEMBER_ROLE = MEMBER + "/roles/{role}";

    private final Members members;

    MembersController(Members members) {
        this.members = members;
    }

    @GetMapping(MEMBERS)
    @GroupPermissionRequired(GroupPermission.READ_MEMBERSHIP)
    PageAnswer<MemberAnswer> members(HttpServletRequest request) {
        Map<String, String[]> sent =
                QueryParameters.sent(request, ListParameters.TAKEN, ListParameters.PARAMETER_REFUSED);
        ListQuery<MemberSortColumn> query = ListParameters.spacedAsNames(
                ListParameters.query(Members.LISTED, GroupLists.within(ActAdmission.admittedGroup(request)), sent));
        ListPosition after = ListParameters.after(Members.LISTED, query, sent);
        ListPage<Members.MemberRow> page = members.page(query, after);
        ListPosition next = page.next();
        return new PageAnswer<>(
                page.rows().stream().map(MembersController::answer).toList(),
                next == null ? null : ListCursor.mint(Members.LISTED, query, next));
    }

    @GetMapping(MEMBER)
    @GroupPermissionRequired(GroupPermission.READ_MEMBERSHIP)
    MemberPanelAnswer member(@PathVariable String subjectId, HttpServletRequest request) {
        GroupId group = ActAdmission.admittedGroup(request);
        return panelAnswer(addressed(subjectId)
                .flatMap(person -> members.member(group, person))
                .orElseThrow(Members::notInView));
    }

    static Optional<SubjectId> addressed(String spelled) {
        return MintedIdentifiers.read(spelled).map(SubjectId::new);
    }

    static MemberPanelAnswer panelAnswer(Members.Member member) {
        return new MemberPanelAnswer(
                member.subjectId().value(),
                member.userId().value(),
                spelled(member.displayName()),
                published(member.roles()),
                published(member.lastChangingRoles()),
                member.removable());
    }

    private static MemberAnswer answer(Members.MemberRow row) {
        return new MemberAnswer(
                row.subjectId().value(), row.userId().value(), spelled(row.displayName()), published(row.roles()));
    }

    private static @Nullable String spelled(@Nullable PersonName name) {
        return name == null ? null : name.value();
    }

    private static List<String> published(Set<GroupRole> roles) {
        return roles.stream().map(GroupRole::published).toList();
    }

    /** @param displayName absent rather than empty where no name is held, so nothing stands in its place */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record MemberAnswer(
            UUID subjectId, String userId, @Nullable String displayName, List<String> roles) {}

    /**
     * Somebody as the group now holds them, whose roles are none once their membership has ended.
     *
     * @param lastChangingRoles the held roles taking which is not offered, as nobody else here could change
     *     the membership without them
     * @param removable whether taking them out is offered, as it is wherever somebody else here could still
     *     change the membership
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record MemberPanelAnswer(
            UUID subjectId,
            String userId,
            @Nullable String displayName,
            List<String> roles,
            List<String> lastChangingRoles,
            boolean removable) {}
}
