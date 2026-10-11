package com.lilradish.lite.testutil.web.refusal

final class RunAddresses {

    private RunAddresses() {}

    private static final String IN_A_GROUP = "/api/groups/00000003-0000-4000-8000-000000000501"

    private static final String A_RUN = IN_A_GROUP + "/runs/00000008-0000-4000-8000-000000000501"

    private static final String A_RAISE = A_RUN + "/ceiling-changes/0000000b-0000-4000-8000-000000000501"

    private static final String A_STEP = A_RUN + "/steps/00000009-0000-4000-8000-000000000501"

    static final List<String> ADDRESSES = [
        IN_A_GROUP + "/runs",
        A_RUN + "/stop",
        A_RUN + "/steps",
        A_RUN + "/files/0000000d-0000-4000-8000-000000000501",
        A_STEP,
        A_STEP + "/tries/1",
        A_STEP + "/tries/1/review",
        A_STEP + "/tries/1/answer",
        A_RAISE + "/approval",
        A_RAISE + "/refusal",
        A_RAISE + "/withdrawal",
    ]
}
