package org.lilradish.lite.app.library;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.lilradish.lite.domain.declaration.Asking;
import org.lilradish.lite.domain.inference.SendMeasure;
import org.lilradish.lite.domain.model.DeployedModel;
import org.lilradish.lite.domain.model.ModelCatalog;
import org.lilradish.lite.domain.model.ModelName;
import org.lilradish.lite.domain.registry.EntryKind;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.lilradish.lite.domain.workflow.ModelChoice;
import org.lilradish.lite.domain.workflow.Producer;

/**
 * A step that could send a model more than it takes: worked out from what is declared, against what the
 * deployment holds as it is read, and never stored. It is said, and never refuses anything.
 *
 * @param step the step's key
 * @param past about how many of the model's own units more than it takes, as {@link DeployedModel#unitsPast} counts
 */
record SendPast(UUID step, SendingRole role, ModelName model, long past) {

    SendPast {
        requireNonNull(step, "SendPast step must not be null");
        requireNonNull(role, "SendPast role must not be null");
        requireNonNull(model, "SendPast model must not be null");
        if (past < 1) {
            throw new IllegalArgumentException("SendPast is past by one unit at least: " + past);
        }
    }

    /**
     * Each step asking a question of {@code asked} whose producing or reviewing model could be sent more than it
     * takes, in the order they run, producing before reviewing; a model the deployment does not hold is passed
     * over, being refused already.
     */
    static List<SendPast> of(List<StoredWorkflow.Step> steps, Map<EntryVersionId, Asking> asked, ModelCatalog models) {
        List<SendPast> found = new ArrayList<>();
        for (StoredWorkflow.Step step : steps) {
            Asking asking =
                    step.runs() instanceof StoredWorkflow.Runs.Pinned pinned && pinned.kind() == EntryKind.QUESTION
                            ? asked.get(pinned.version())
                            : null;
            if (asking == null) {
                continue;
            }
            if (step.producer() instanceof Producer.Model producing) {
                measured(step, SendingRole.PRODUCING, producing.choice(), asking, models, found);
            }
            ModelChoice reviewer = step.reviewer();
            if (reviewer != null) {
                measured(step, SendingRole.REVIEWING, reviewer, asking, models, found);
            }
        }
        return List.copyOf(found);
    }

    /** Whether the step tells each asking what happened before it, which only a model producing is told. */
    private static boolean told(StoredWorkflow.Step step) {
        return step.producer() instanceof Producer.Model producing && producing.toldWhatHappened();
    }

    private static void measured(
            StoredWorkflow.Step step,
            SendingRole role,
            ModelChoice choice,
            Asking asking,
            ModelCatalog models,
            List<SendPast> found) {
        DeployedModel model = models.find(choice.model()).orElse(null);
        if (model == null) {
            return;
        }
        long past = model.unitsPast(
                role == SendingRole.REVIEWING
                        ? SendMeasure.mostSentToReview(asking)
                        : SendMeasure.mostSent(asking, told(step)));
        if (past > 0) {
            found.add(new SendPast(step.id(), role, model.name(), past));
        }
    }
}
