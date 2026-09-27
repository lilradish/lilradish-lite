package org.lilradish.lite.app.standing;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.lilradish.lite.domain.identity.EstateAct;
import org.lilradish.lite.domain.identity.GroupPermission;
import org.lilradish.lite.domain.identity.HumanPrincipal;
import org.lilradish.lite.domain.identity.Scope;
import org.lilradish.lite.web.CallerAdmission;
import org.lilradish.lite.web.NoActRequired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where the caller may go, decided afresh per request and never kept: the estate's acts, and in each
 * group they are in the permissions (never the roles) their open roles there fold to.
 */
@RestController
final class StandingController {

    private static final Standing NOTHING = new Standing(List.of(), List.of());

    private final Holdings holdings;

    StandingController(Holdings holdings) {
        this.holdings = holdings;
    }

    // Asks no act: what it answers is which acts the caller has.
    @GetMapping(CallerAdmission.THIS_APPLICATION_ANSWERS + "/standing")
    @NoActRequired
    Standing standing(HttpServletRequest request) {
        return holdings.heldBy(CallerAdmission.callerOf(request))
                .map(StandingController::answer)
                .orElse(NOTHING);
    }

    private static Standing answer(Holdings.Held held) {
        HumanPrincipal principal = held.principal();
        List<String> acts =
                principal.estateReach().stream().map(EstateAct::published).toList();
        List<GroupStanding> groups = held.groups().stream()
                .map(group -> standingIn(principal, group))
                .toList();
        return new Standing(acts, groups);
    }

    private static GroupStanding standingIn(HumanPrincipal principal, Holdings.GroupInView group) {
        List<String> permissions = principal.permissions(new Scope.Group(group.groupId())).stream()
                .map(GroupPermission::published)
                .toList();
        return new GroupStanding(
                group.groupId().value(), group.key().value(), group.name().value(), permissions);
    }

    /** Sets sent as arrays whose order means nothing, except the groups, which come in the order read. */
    record Standing(List<String> acts, List<GroupStanding> groups) {}

    /** Addressed by its identifier, and shown by its name with its key beside it. */
    record GroupStanding(UUID groupId, String key, String name, List<String> permissions) {}
}
