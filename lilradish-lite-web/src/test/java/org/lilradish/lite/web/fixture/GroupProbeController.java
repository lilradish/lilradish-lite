package org.lilradish.lite.web.fixture;

import jakarta.servlet.http.HttpServletRequest;
import org.lilradish.lite.domain.identity.GroupPermission;
import org.lilradish.lite.web.ActAdmission;
import org.lilradish.lite.web.GroupMembershipRequired;
import org.lilradish.lite.web.GroupPermissionRequired;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Addresses inside a group that exist to be gated: one asking a permission only some roles there reach,
 * and one asking membership alone. Each answers with the group it was handed, so what reaches the wire
 * is the group the gate admitted and nothing a handler could have read off the address for itself.
 *
 * <p>Declared as a test component, for the reason {@link ActProbeController} is.
 */
@TestComponent
@RestController
public class GroupProbeController {

    public static final String CHANGING_MEMBERSHIP = ActAdmission.IN_A_GROUP + "/probe/changing-membership";

    public static final String IN_THE_GROUP = ActAdmission.IN_A_GROUP + "/probe/in-the-group";

    @GetMapping(CHANGING_MEMBERSHIP)
    @GroupPermissionRequired(GroupPermission.CHANGE_MEMBERSHIP)
    String changingMembership(HttpServletRequest request) {
        return ActAdmission.admittedGroup(request).value().toString();
    }

    @GetMapping(IN_THE_GROUP)
    @GroupMembershipRequired
    String inTheGroup(HttpServletRequest request) {
        return ActAdmission.admittedGroup(request).value().toString();
    }
}
