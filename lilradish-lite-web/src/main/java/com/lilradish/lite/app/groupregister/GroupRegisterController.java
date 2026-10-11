package com.lilradish.lite.app.groupregister;

import com.lilradish.lite.app.listing.ListParameters;
import com.lilradish.lite.app.listing.PageAnswer;
import com.lilradish.lite.domain.groupregister.GroupSortColumn;
import com.lilradish.lite.domain.identity.EstateAct;
import com.lilradish.lite.domain.listing.ListCursor;
import com.lilradish.lite.domain.listing.ListPage;
import com.lilradish.lite.domain.listing.ListPosition;
import com.lilradish.lite.domain.listing.ListQuery;
import com.lilradish.lite.web.ActRequired;
import com.lilradish.lite.web.CallerAdmission;
import com.lilradish.lite.web.QueryParameters;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * No group is read on its own here: the estate cannot enter a group, so a row opens nothing.
 *
 * <p>A group is addressed by the identifier this system minted for it and never by its key, which is
 * a name people say rather than what anything here points with.
 *
 * <p>The query parameters are read as {@link QueryParameters} reads them, and the filter is spaced as
 * {@link ListParameters#spacedAsNames} spaces one.
 */
@RestController
final class GroupRegisterController {

    static final String GROUPS = CallerAdmission.THIS_APPLICATION_ANSWERS + "/groups";

    static final String GROUP = GROUPS + "/{groupId}";

    private final GroupRegister register;

    GroupRegisterController(GroupRegister register) {
        this.register = register;
    }

    @GetMapping(GROUPS)
    @ActRequired(EstateAct.KEEP_GROUP_REGISTER)
    PageAnswer<GroupAnswer> groups(HttpServletRequest request) {
        Map<String, String[]> sent =
                QueryParameters.sent(request, ListParameters.TAKEN, ListParameters.PARAMETER_REFUSED);
        ListQuery<GroupSortColumn> query = ListParameters.spacedAsNames(
                ListParameters.query(GroupRegister.LISTED, GroupRegister.WHOLE_REGISTER, sent));
        ListPosition after = ListParameters.after(GroupRegister.LISTED, query, sent);
        ListPage<GroupRegister.GroupRow> page = register.page(query, after);
        ListPosition next = page.next();
        return new PageAnswer<>(
                page.rows().stream().map(GroupRegisterController::answer).toList(),
                next == null ? null : ListCursor.mint(GroupRegister.LISTED, query, next));
    }

    static GroupAnswer answer(GroupRegister.GroupRow row) {
        return new GroupAnswer(
                row.groupId().value(),
                row.key().value(),
                row.name().value(),
                row.canBeAdministered(),
                row.memberCount());
    }

    /**
     * Names no member and no maker: facts about a group do not disclose its members, and seeded groups
     * have no person as maker.
     */
    record GroupAnswer(UUID groupId, String key, String name, boolean canBeAdministered, long memberCount) {}
}
