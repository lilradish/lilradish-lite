package com.lilradish.lite.testutil.failure

import static org.libprunus.core.error.ErrorCategory.CONFLICT
import static org.libprunus.core.error.ErrorCategory.INVALID_ARGUMENT
import static org.libprunus.core.error.ErrorCategory.NOT_FOUND
import static org.libprunus.core.error.ErrorCategory.PERMISSION_DENIED

final class RunRefusals {

    private RunRefusals() {}

    static final List<List<Object>> EXPECTED = [
        ["ASK_AGAIN_NOT_OFFERED", CONFLICT],
        ["CEILING_RAISE_ASKED_BY_ANOTHER", PERMISSION_DENIED],
        ["CEILING_RAISE_ASKED_BY_CALLER", PERMISSION_DENIED],
        ["CEILING_RAISE_NOT_WAITING", CONFLICT],
        ["CEILING_UNUSABLE", INVALID_ARGUMENT],
        ["CODE_STEP_GIVES_OTHERWISE", CONFLICT],
        ["ENTRY_STOPPED", CONFLICT],
        ["LENGTH_VALUE_NOT_STANDING", CONFLICT],
        ["LENGTH_VALUE_USED", CONFLICT],
        ["REASON_MISSING", INVALID_ARGUMENT],
        ["REASON_UNUSABLE", INVALID_ARGUMENT],
        ["REVIEW_INCOMPLETE", INVALID_ARGUMENT],
        ["REVIEW_NOT_A_PERSONS", CONFLICT],
        ["REVIEW_OWN_PRODUCTION", PERMISSION_DENIED],
        ["RUN_BENEATH_ANOTHER", CONFLICT],
        ["RUN_NAME_UNUSABLE", INVALID_ARGUMENT],
        ["RUN_NOT_IN_VIEW", NOT_FOUND],
        ["RUN_STOPPED", CONFLICT],
        ["STEP_MOVED_ON", CONFLICT],
        ["STEP_NOT_HELD_FOR_LENGTH", CONFLICT],
        ["STEP_NOT_IN_VIEW", NOT_FOUND],
        ["TRY_SENDING_NOT_OFFERED", CONFLICT],
        ["VALUE_DOES_NOT_FIT", INVALID_ARGUMENT],
        ["WORKFLOW_NOT_OFFERED", CONFLICT],
    ]
}
