package org.lilradish.lite.app.library;

import org.lilradish.lite.app.files.FileDeployment;
import org.lilradish.lite.app.search.SearchWays;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.model.ModelCatalog;
import org.lilradish.lite.domain.registry.EntryKind;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * What a workflow's stored content is held to at submitting, and again whenever it is read: what
 * {@link WorkflowProblems} finds of it against what it pins, the code steps its group was published and what this
 * release declares of them and of the ways to search it holds, and the models this deployment holds now.
 */
@Component
final class WorkflowContentCheck implements ContentCheck {

    private final JdbcClient database;

    private final ModelCatalog models;

    private final ReleasedCodeSteps released;

    private final FileDeployment deployment;

    private final SearchWays ways;

    WorkflowContentCheck(
            JdbcClient database,
            ModelCatalog models,
            ReleasedCodeSteps released,
            FileDeployment deployment,
            SearchWays ways) {
        this.database = database;
        this.models = models;
        this.released = released;
        this.deployment = deployment;
        this.ways = ways;
    }

    @Override
    public EntryKind kind() {
        return EntryKind.WORKFLOW;
    }

    @Override
    public ContentProblemReport.Result problemsIn(GroupId group, EntryVersionId version) {
        StoredWorkflow stored = StoredWorkflow.read(database, version)
                .orElseThrow(() -> new IllegalStateException(
                        "Workflow version " + version.value() + " holds no workflow content"));
        CodeStepsHere here = stored.namesCode() ? CodeStepsHere.read(database, group, released) : CodeStepsHere.NONE;
        return WorkflowProblems.report(
                stored, stored.resolvedIn(database, group, released, here, ways), models, deployment);
    }
}
