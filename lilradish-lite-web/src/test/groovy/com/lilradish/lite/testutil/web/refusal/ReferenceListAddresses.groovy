package com.lilradish.lite.testutil.web.refusal

final class ReferenceListAddresses {

    private ReferenceListAddresses() {}

    private static final String IN_A_GROUP = "/api/groups/00000003-0000-4000-8000-000000000501"

    private static final String AN_ENTRY = IN_A_GROUP + "/reference-lists/00000006-0000-4000-8000-000000000501"

    private static final String A_VERSION = AN_ENTRY + "/versions/00000007-0000-4000-8000-000000000501"

    private static final String A_TERM = A_VERSION + "/terms/00000009-0000-4000-8000-000000000501"

    static final List<String> ADDRESSES = [
        A_VERSION + "/note",
        A_VERSION + "/terms",
        A_TERM,
        A_TERM + "/removal",
        A_TERM + "/up",
        A_TERM + "/down",
    ]
}
