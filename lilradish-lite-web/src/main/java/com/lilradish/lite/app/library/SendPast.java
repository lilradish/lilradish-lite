package com.lilradish.lite.app.library;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.declaration.Asking;
import com.lilradish.lite.domain.inference.SendMeasure;
import com.lilradish.lite.domain.model.DeployedModel;
import com.lilradish.lite.domain.model.ModelCatalog;
import com.lilradish.lite.domain.model.ModelName;
import com.lilradish.lite.domain.registry.EntryKind;
import com.lilradish.lite.domain.registry.EntryVersionId;
import com.lilradish.lite.domain.workflow.ModelChoice;
import com.lilradish.lite.domain.workflow.Producer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A step that could send a model more than it takes: worked out from what is declared, against what the deployment holds as it is read, and never stored. It is said, and never refuses anything.
 *
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
     * Only models the catalog holds are considered. Results follow step order, with producing before reviewing.
     */
    static List<SendPast> of(
            List<StoredWorkflow.Step> steps,
            Map<EntryVersionId, Asking> asked,
            Map<String, Long> codeReviewsSent,
            Map<UUID, Long> searchesSent,
            Map<UUID, Long> searchesReviewed,
            ModelCatalog models) {
        List<SendPast> found = new ArrayList<>();
        for (StoredWorkflow.Step step : steps) {
            DeployedModel producing =
                    step.producer() instanceof Producer.Model model ? held(model.choice(), models) : null;
            if (step.runs() instanceof StoredWorkflow.Runs.Search) {
                if (producing != null && searchesSent.get(step.id()) instanceof Long sent) {
                    measured(step, SendingRole.PRODUCING, producing, sent, found);
                }
                DeployedModel reviewing = step.reviewer() == null ? null : held(step.reviewer(), models);
                if (reviewing != null && searchesReviewed.get(step.id()) instanceof Long sent) {
                    measured(step, SendingRole.REVIEWING, reviewing, sent, found);
                }
                continue;
            }
            if (step.runs() instanceof StoredWorkflow.Runs.Code code) {
                DeployedModel reviewing = step.reviewer() == null ? null : held(step.reviewer(), models);
                Long sent = code.codeStep() == null ? null : codeReviewsSent.get(code.codeStep());
                if (reviewing != null && sent != null) {
                    measured(step, SendingRole.REVIEWING, reviewing, sent, found);
                }
                continue;
            }
            Asking asking =
                    step.runs() instanceof StoredWorkflow.Runs.Pinned pinned && pinned.kind() == EntryKind.QUESTION
                            ? asked.get(pinned.version())
                            : null;
            if (asking == null) {
                continue;
            }
            if (producing != null) {
                measured(step, SendingRole.PRODUCING, producing, SendMeasure.mostSent(asking, told(step)), found);
            }
            DeployedModel reviewing = step.reviewer() == null ? null : held(step.reviewer(), models);
            if (reviewing != null) {
                measured(step, SendingRole.REVIEWING, reviewing, SendMeasure.mostSentToReview(asking), found);
            }
        }
        return List.copyOf(found);
    }

    private static boolean told(StoredWorkflow.Step step) {
        return step.producer() instanceof Producer.Model producing && producing.toldWhatHappened();
    }

    private static @Nullable DeployedModel held(ModelChoice choice, ModelCatalog models) {
        return models.find(choice.model()).orElse(null);
    }

    private static void measured(
            StoredWorkflow.Step step, SendingRole role, DeployedModel model, long sent, List<SendPast> found) {
        long past = model.unitsPast(sent);
        if (past > 0) {
            found.add(new SendPast(step.id(), role, model.name(), past));
        }
    }
}
