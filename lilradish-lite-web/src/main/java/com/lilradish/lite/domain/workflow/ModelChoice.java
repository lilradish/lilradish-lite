package com.lilradish.lite.domain.workflow;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.model.DeployedModel;
import com.lilradish.lite.domain.model.ModelCatalog;
import com.lilradish.lite.domain.model.ModelMode;
import com.lilradish.lite.domain.model.ModelName;
import org.jspecify.annotations.Nullable;

/**
 * Names a model, never its version. Deployments can change it, so held-ness is checked each time.
 *
 * @param mode none where the model runs as it is
 */
public record ModelChoice(ModelName model, @Nullable ModelMode mode) {

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
