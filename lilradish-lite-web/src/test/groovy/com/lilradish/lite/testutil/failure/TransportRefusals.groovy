package com.lilradish.lite.testutil.failure

import static org.libprunus.core.error.ErrorCategory.INVALID_ARGUMENT
import static org.libprunus.core.error.ErrorCategory.PERMISSION_DENIED
import static org.libprunus.core.error.ErrorCategory.UNAUTHENTICATED
import static org.libprunus.core.error.ErrorCategory.UNAVAILABLE

final class TransportRefusals {

    private TransportRefusals() {}

    static final List<List<Object>> EXPECTED = [
        ["ACT_NOT_PERMITTED", PERMISSION_DENIED],
        ["BODY_SENT_TOO_SLOWLY", INVALID_ARGUMENT],
        ["BODY_UNUSABLE", INVALID_ARGUMENT],
        ["LIST_CURSOR_UNUSABLE", INVALID_ARGUMENT],
        ["LIST_FILTER_UNUSABLE", INVALID_ARGUMENT],
        ["LIST_SORT_UNUSABLE", INVALID_ARGUMENT],
        ["NOT_SIGNED_IN", UNAUTHENTICATED],
        ["ORIGIN_UNVERIFIED", PERMISSION_DENIED],
        ["PARAMETER_UNKNOWN", INVALID_ARGUMENT],
        ["SERVICE_BUSY", UNAVAILABLE],
    ]
}
