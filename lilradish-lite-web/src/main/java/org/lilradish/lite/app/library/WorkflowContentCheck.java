package org.lilradish.lite.app.library;

import java.util.List;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.model.ModelCatalog;
import org.lilradish.lite.domain.registry.ContentProblem;
import org.lilradish.lite.domain.registry.EntryKind;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * What a workflow's stored content is held to at submitting, and again whenever it is read: what
 * {@link WorkflowProblems} finds of it against what it pins, the code steps its group was published and what this
 * release declares of them, and the models this deployment holds now.
 */
@Component
final class WorkflowContentCheck implements ContentCheck {

    private final JdbcClient database;

    private final ModelCatalog models;

    private final ReleasedCodeSteps released;

    WorkflowContentCheck(JdbcClient database, ModelCatalog models, ReleasedCodeSteps released) {
        this.database = database;
        this.models = models;
        this.released = released;
    }

    @Override
    public EntryKind kind() {
        return EntryKind.WORKFLOW;
    }

    @Override
    public List<ContentProblem> problemsIn(GroupId group, EntryVersionId version) {
        StoredWorkflow stored = StoredWorkflow.read(database, version)
                .orElseThrow(() -> new IllegalStateException(
                        "Workflow version " + version.value() + " holds no workflow content"));
        CodeStepsHere here = stored.namesCode() ? CodeStepsHere.read(database, group, released) : CodeStepsHere.NONE;
        return WorkflowProblems.of(stored, stored.resolvedIn(database, group, released, here), models);
    }
}
