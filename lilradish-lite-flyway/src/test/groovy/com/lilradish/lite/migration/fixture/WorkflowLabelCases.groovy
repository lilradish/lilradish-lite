package com.lilradish.lite.migration.fixture

import static com.lilradish.lite.migration.fixture.SchemaRows.*

final class WorkflowLabelCases {

    static final List<Map<String, String>> CASES = [
        attack("workflow_steps_label_visible", "workflow_steps", setting("workflow_steps", "label = ''")),
        attack("workflow_steps_label_bounded", "workflow_steps",
                setting("workflow_steps", "label = ${literal('a'.repeat(129))}")),
    ]

    private WorkflowLabelCases() {}
}
