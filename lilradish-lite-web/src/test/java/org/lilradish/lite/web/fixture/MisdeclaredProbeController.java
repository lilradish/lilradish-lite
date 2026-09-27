package org.lilradish.lite.web.fixture;

import org.lilradish.lite.domain.identity.EstateAct;
import org.lilradish.lite.domain.identity.GroupPermission;
import org.lilradish.lite.web.ActAdmission;
import org.lilradish.lite.web.ActRequired;
import org.lilradish.lite.web.CallerAdmission;
import org.lilradish.lite.web.GroupMembershipRequired;
import org.lilradish.lite.web.GroupPermissionRequired;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Handlers somebody declared wrongly rather than not at all: saying two things they ask, and asking
 * something in a group at an address naming none. The surface this application ships has none of them
 * by construction, a spec of its own holding that, so this is registered only where the gate is asked
 * what it does with them — for the reason {@link UndeclaredProbeController} is kept apart.
 */
@TestComponent
@RestController
public class MisdeclaredProbeController {

    public static final String DECLARING_TWO = ActAdmission.IN_A_GROUP + "/probe/declaring-two";

    public static final String MEMBERSHIP_AND_PERMISSION = ActAdmission.IN_A_GROUP + "/probe/membership-and-permission";

    public static final String IN_NO_GROUP = CallerAdmission.THIS_APPLICATION_ANSWERS + "/probe/in-no-group";

    public static final String MEMBERSHIP_IN_NO_GROUP =
            CallerAdmission.THIS_APPLICATION_ANSWERS + "/probe/membership-in-no-group";

    /** What only a handler that actually ran can put on the wire. */
    public static final String ANSWERED = "a misdeclared handler ran";

    @GetMapping(DECLARING_TWO)
    @ActRequired(EstateAct.KEEP_POOL)
    @GroupPermissionRequired(GroupPermission.READ_MEMBERSHIP)
    String declaringTwo() {
        return ANSWERED;
    }

    @GetMapping(MEMBERSHIP_AND_PERMISSION)
    @GroupMembershipRequired
    @GroupPermissionRequired(GroupPermission.READ_MEMBERSHIP)
    String membershipAndPermission() {
        return ANSWERED;
    }

    @GetMapping(IN_NO_GROUP)
    @GroupPermissionRequired(GroupPermission.READ_MEMBERSHIP)
    String inNoGroup() {
        return ANSWERED;
    }

    @GetMapping(MEMBERSHIP_IN_NO_GROUP)
    @GroupMembershipRequired
    String membershipInNoGroup() {
        return ANSWERED;
    }
}
