package com.lilradish.lite.testutil.web.refusal

final class EstateAddresses {

    private EstateAddresses() {}

    private static final String PERSON = "/api/pool/people/00000002-0000-4000-8000-000000000501"

    static final List<String> ADDRESSES = [
        "/api/standing",
        "/api/people",
        "/api/pool/people",
        PERSON,
        PERSON + "/estate-roles/steward",
        "/api/pool/search",
        "/api/groups",
        "/api/measurements",
    ]
}
