package com.lilradish.lite.app.standing;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.lilradish.lite.domain.identity.EstateAct;
import com.lilradish.lite.domain.identity.GroupPermission;
import com.lilradish.lite.domain.identity.HumanPrincipal;
import com.lilradish.lite.domain.identity.Scope;
import com.lilradish.lite.domain.identity.UserId;
import com.lilradish.lite.web.CallerAdmission;
import com.lilradish.lite.web.NoActRequired;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where the caller may go, decided afresh per request and never kept: the estate's acts, and in each
 * group they are in the permissions (never the roles) their open roles there fold to. The caller is
 * named as an answer naming a person names them, so a page can tell what the reader did themselves.
 */
@RestController
final class StandingController {

    private static final Standing NOTHING = new Standing(null, List.of(), List.of());

    private final Holdings holdings;

    StandingController(Holdings holdings) {
        this.holdings = holdings;
    }

    // Asks no act: what it answers is which acts the caller has.
    @GetMapping(CallerAdmission.THIS_APPLICATION_ANSWERS + "/standing")
    @NoActRequired
    Standing standing(HttpServletRequest request) {
        UserId caller = CallerAdmission.callerOf(request);
        return holdings.heldBy(caller).map(held -> answer(caller, held)).orElse(NOTHING);
    }

    private static Standing answer(UserId caller, Holdings.Held held) {
        HumanPrincipal principal = held.principal();
        List<String> acts =
                principal.estateReach().stream().map(EstateAct::published).toList();
        List<GroupStanding> groups = held.groups().stream()
                .map(group -> standingIn(principal, group))
                .toList();
        return new Standing(caller.value(), acts, groups);
    }

    private static GroupStanding standingIn(HumanPrincipal principal, Holdings.GroupInView group) {
        List<String> permissions = principal.permissions(new Scope.Group(group.groupId())).stream()
                .map(GroupPermission::published)
                .toList();
        return new GroupStanding(
                group.groupId().value(), group.key().value(), group.name().value(), permissions);
    }

    /**
     * Sets sent as arrays whose order means nothing, except the groups, which come in the order read.
     *
     * @param userId the caller's own, and nobody else's; absent where the store holds nothing for them
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Standing(@Nullable String userId, List<String> acts, List<GroupStanding> groups) {}

    /** Addressed by its identifier, and shown by its name with its key beside it. */
    record GroupStanding(UUID groupId, String key, String name, List<String> permissions) {}
}
