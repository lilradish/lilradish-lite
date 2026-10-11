package com.lilradish.lite.testutil.failure

import static org.libprunus.core.error.ErrorCategory.CONFLICT
import static org.libprunus.core.error.ErrorCategory.INVALID_ARGUMENT
import static org.libprunus.core.error.ErrorCategory.NOT_FOUND

final class EstateRefusals {

    private EstateRefusals() {}

    static final List<List<Object>> EXPECTED = [
        ["LAST_ESTATE_ROLE_GRANTOR", CONFLICT],
        ["PEOPLE_SEARCH_UNUSABLE", INVALID_ARGUMENT],
        ["PERSON_ALREADY_IN_POOL", CONFLICT],
        ["PERSON_HOLDS_ESTATE_ROLES", CONFLICT],
        ["PERSON_IN_GROUPS", CONFLICT],
        ["PERSON_NOT_IN_VIEW", NOT_FOUND],
        ["USER_NOT_IN_DIRECTORY", INVALID_ARGUMENT],
    ]
}
