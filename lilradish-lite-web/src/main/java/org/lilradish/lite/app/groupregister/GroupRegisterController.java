package org.lilradish.lite.app.groupregister;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.lilradish.lite.app.listing.ListParameters;
import org.lilradish.lite.app.listing.PageAnswer;
import org.lilradish.lite.domain.groupregister.GroupSortColumn;
import org.lilradish.lite.domain.identity.EstateAct;
import org.lilradish.lite.domain.listing.ListCursor;
import org.lilradish.lite.domain.listing.ListPage;
import org.lilradish.lite.domain.listing.ListPosition;
import org.lilradish.lite.domain.listing.ListQuery;
import org.lilradish.lite.web.ActRequired;
import org.lilradish.lite.web.CallerAdmission;
import org.lilradish.lite.web.QueryParameters;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The register of groups one page at a time, answered only to a caller holding the act of keeping it.
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

    /** The register's addresses, which every handler answering at one of them is mapped by. */
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
     * Whether it can still be administered and how many are in it, and never who: a fact about a group
     * is not a fact about anybody in it. Nor who made it, a seeded group having been made by nobody.
     */
    record GroupAnswer(UUID groupId, String key, String name, boolean canBeAdministered, long memberCount) {}
}
