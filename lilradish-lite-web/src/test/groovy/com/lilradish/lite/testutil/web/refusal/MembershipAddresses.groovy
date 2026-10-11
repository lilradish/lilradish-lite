package com.lilradish.lite.testutil.web.refusal

final class MembershipAddresses {

    private MembershipAddresses() {}

    private static final String IN_A_GROUP = "/api/groups/00000003-0000-4000-8000-000000000501"

    private static final String MEMBERS = IN_A_GROUP + "/members"

    static final List<String> ADDRESSES = [
        MEMBERS,
        MEMBERS + "/00000002-0000-4000-8000-000000000501",
        MEMBERS + "/00000002-0000-4000-8000-000000000501/roles/owner",
        IN_A_GROUP + "/pool/search",
        IN_A_GROUP + "/currency",
    ]
}
