package com.lilradish.lite.testutil.web.refusal

final class QuestionAddresses {

    private QuestionAddresses() {}

    private static final String IN_A_GROUP = "/api/groups/00000003-0000-4000-8000-000000000501"

    private static final String A_QUESTION_VERSION = IN_A_GROUP + "/questions/00000006-0000-4000-8000-000000000502/versions/00000007-0000-4000-8000-000000000502"

    static final List<String> ADDRESSES = [
        IN_A_GROUP + "/questions",
        A_QUESTION_VERSION,
        A_QUESTION_VERSION + "/instruction",
        A_QUESTION_VERSION + "/takes",
    ]
}
