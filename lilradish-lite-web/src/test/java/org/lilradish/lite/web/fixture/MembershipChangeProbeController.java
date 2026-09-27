package org.lilradish.lite.web.fixture;

import org.lilradish.lite.web.ActAdmission;
import org.lilradish.lite.web.GroupMembershipRequired;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A change inside a group guarded by membership alone, which this application must refuse to start with. A
 * test component, so the only context registering it is one a spec builds to watch that refusal.
 */
@TestComponent
@RestController
public class MembershipChangeProbeController {

    public static final String CHANGING_ON_MEMBERSHIP = ActAdmission.IN_A_GROUP + "/probe/changing-on-membership";

    @PostMapping(CHANGING_ON_MEMBERSHIP)
    @GroupMembershipRequired
    void changingOnMembership() {}
}
