package com.lilradish.lite.testutil.web.refusal

final class LibraryAddresses {

    private LibraryAddresses() {}

    private static final String IN_A_GROUP = "/api/groups/00000003-0000-4000-8000-000000000501"

    private static final String AN_ENTRY = IN_A_GROUP + "/reference-lists/00000006-0000-4000-8000-000000000501"

    private static final String A_VERSION = AN_ENTRY + "/versions/00000007-0000-4000-8000-000000000501"

    static final List<String> ADDRESSES = [
        AN_ENTRY + "/stop",
        AN_ENTRY + "/versions",
        A_VERSION + "/submission",
        A_VERSION + "/approval",
        A_VERSION + "/retirement",
        A_VERSION,
    ]
}
