package com.lilradish.lite.testutil.failure

import static org.libprunus.core.error.ErrorCategory.CONFLICT
import static org.libprunus.core.error.ErrorCategory.INVALID_ARGUMENT
import static org.libprunus.core.error.ErrorCategory.NOT_FOUND
import static org.libprunus.core.error.ErrorCategory.PERMISSION_DENIED

final class LibraryRefusals {

    private LibraryRefusals() {}

    static final List<List<Object>> EXPECTED = [
        ["APPROVER_WROTE_VERSION", PERMISSION_DENIED],
        ["DRAFT_ALREADY_STARTED", CONFLICT],
        ["DRAFT_WRITTEN_SINCE_READ", CONFLICT],
        ["ENTRY_NAME_TAKEN", CONFLICT],
        ["ENTRY_NAME_UNUSABLE", INVALID_ARGUMENT],
        ["VERSION_NAME_UNUSABLE", INVALID_ARGUMENT],
        ["ENTRY_NOT_IN_VIEW", NOT_FOUND],
        ["ENTRY_PURPOSE_UNUSABLE", INVALID_ARGUMENT],
        ["VERSION_CONTENT_DOES_NOT_HOLD", CONFLICT],
        ["VERSION_NOT_IN_VIEW", NOT_FOUND],
        ["VERSION_NOT_PINNABLE", INVALID_ARGUMENT],
        ["VERSION_PINS_RETIRED", CONFLICT],
        ["VERSION_STANDING_REFUSES", CONFLICT],
    ]
}
