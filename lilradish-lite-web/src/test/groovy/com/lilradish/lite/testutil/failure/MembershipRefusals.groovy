package com.lilradish.lite.testutil.failure

import static org.libprunus.core.error.ErrorCategory.CONFLICT
import static org.libprunus.core.error.ErrorCategory.INVALID_ARGUMENT
import static org.libprunus.core.error.ErrorCategory.NOT_FOUND

final class MembershipRefusals {

    private MembershipRefusals() {}

    static final List<List<Object>> EXPECTED = [
        ["CURRENCY_NOT_PRICED", INVALID_ARGUMENT],
        ["GROUP_KEY_TAKEN", CONFLICT],
        ["GROUP_KEY_UNUSABLE", INVALID_ARGUMENT],
        ["GROUP_NAME_TAKEN", CONFLICT],
        ["GROUP_NAME_UNUSABLE", INVALID_ARGUMENT],
        ["GROUP_NOT_IN_VIEW", NOT_FOUND],
        ["LAST_MEMBERSHIP_CHANGER", CONFLICT],
        ["MEMBER_NOT_IN_VIEW", NOT_FOUND],
        ["PERSON_ALREADY_IN_GROUP", CONFLICT],
        ["PERSON_NOT_IN_POOL", INVALID_ARGUMENT],
    ]
}
