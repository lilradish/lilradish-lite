package com.lilradish.lite.testutil.web.refusal

final class WorkflowAddresses {

    private WorkflowAddresses() {}

    private static final String IN_A_GROUP = "/api/groups/00000003-0000-4000-8000-000000000501"

    private static final String A_WORKFLOW_VERSION = IN_A_GROUP + "/workflows/00000006-0000-4000-8000-000000000503/versions/00000007-0000-4000-8000-000000000503"

    static final List<String> ADDRESSES = [
        A_WORKFLOW_VERSION,
        A_WORKFLOW_VERSION + "/gives",
        A_WORKFLOW_VERSION + "/steps",
        A_WORKFLOW_VERSION + "/ceiling",
        A_WORKFLOW_VERSION + "/help",
    ]
}
