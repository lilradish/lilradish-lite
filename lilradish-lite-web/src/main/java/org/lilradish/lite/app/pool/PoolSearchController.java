package org.lilradish.lite.app.pool;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.app.listing.SearchParameters;
import org.lilradish.lite.domain.identity.EstateAct;
import org.lilradish.lite.domain.identity.GroupPermission;
import org.lilradish.lite.domain.people.PeopleFound;
import org.lilradish.lite.domain.people.PersonName;
import org.lilradish.lite.web.ActAdmission;
import org.lilradish.lite.web.ActRequired;
import org.lilradish.lite.web.CallerAdmission;
import org.lilradish.lite.web.GroupPermissionRequired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A search of the pool, answered to a caller holding the act of keeping the register of groups, and
 * inside a group to a member who may change its membership: naming somebody from the pool is part of
 * starting a group and of bringing somebody into one, and the pool is neither page's subject, so it is
 * searched there and never listed. What anybody holds is not part of what it answers.
 *
 * <p>Inside a group it leaves out whoever holds a role there already, somebody already in being nobody
 * to bring in.
 *
 * <p>The search is read as {@link SearchParameters} reads one.
 */
@RestController
final class PoolSearchController {

    private final PoolPeople people;

    PoolSearchController(PoolPeople people) {
        this.people = people;
    }

    @GetMapping(CallerAdmission.THIS_APPLICATION_ANSWERS + "/pool/search")
    @ActRequired(EstateAct.KEEP_GROUP_REGISTER)
    FoundAnswer found(HttpServletRequest request) {
        return foundAnswer(people.search(SearchParameters.searchSent(request), SearchParameters.MOST_FOUND));
    }

    @GetMapping(ActAdmission.IN_A_GROUP + "/pool/search")
    @GroupPermissionRequired(GroupPermission.CHANGE_MEMBERSHIP)
    FoundAnswer foundOutsideTheGroup(HttpServletRequest request) {
        return foundAnswer(people.searchOutside(
                ActAdmission.admittedGroup(request),
                SearchParameters.searchSent(request),
                SearchParameters.MOST_FOUND));
    }

    private static FoundAnswer foundAnswer(PeopleFound<PoolPeople.InPool> found) {
        return new FoundAnswer(
                found.people().stream().map(PoolSearchController::answer).toList(), found.more());
    }

    private static InPoolAnswer answer(PoolPeople.InPool person) {
        PersonName name = person.displayName();
        return new InPoolAnswer(
                person.subjectId().value(), person.userId().value(), name == null ? null : name.value());
    }

    /** @param more whether the pool holds somebody else the search finds, beyond those listed */
    record FoundAnswer(List<InPoolAnswer> items, boolean more) {}

    /** @param displayName absent rather than empty where no name is held, so nothing stands in its place */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record InPoolAnswer(
            UUID subjectId, String userId, @Nullable String displayName) {}
}
