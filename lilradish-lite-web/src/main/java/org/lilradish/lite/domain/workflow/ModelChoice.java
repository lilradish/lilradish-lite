package org.lilradish.lite.domain.workflow;

import static java.util.Objects.requireNonNull;

import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.model.DeployedModel;
import org.lilradish.lite.domain.model.ModelCatalog;
import org.lilradish.lite.domain.model.ModelMode;
import org.lilradish.lite.domain.model.ModelName;

/**
 * A model a version names, and the mode it runs in: never a version of it, what the deployment holds changing
 * only by deploying, so whether it is still held is asked again every time.
 *
 * @param mode none where the model runs as it is
 */
public record ModelChoice(ModelName model, @Nullable ModelMode mode) {

    /** What of a choice the deployment does not hold. */
    public enum Unheld {
        MODEL,
        MODE
    }

    public ModelChoice {
        requireNonNull(model, "ModelChoice model must not be null");
    }

    /** What {@code catalog} does not hold of this, the model before its mode; none where it holds both. */
    public @Nullable Unheld unheldBy(ModelCatalog catalog) {
        requireNonNull(catalog, "ModelChoice catalog must not be null");
        DeployedModel held = catalog.find(model).orElse(null);
        if (held == null) {
            return Unheld.MODEL;
        }
        return mode == null || held.modes().contains(mode) ? null : Unheld.MODE;
    }
}
