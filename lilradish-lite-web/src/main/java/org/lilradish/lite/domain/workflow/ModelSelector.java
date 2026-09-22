package org.lilradish.lite.domain.workflow;

import static java.util.Objects.requireNonNull;

import java.util.Set;

public sealed interface ModelSelector {

    record ByCapability(ModelProfile profile, Set<Modality> modalities) implements ModelSelector {
        public ByCapability {
            requireNonNull(profile, "ByCapability profile must not be null");
            requireNonNull(modalities, "ByCapability modalities must not be null");
            modalities = Set.copyOf(modalities);
        }
    }

    /** Reproducibility is not implied; it additionally requires {@link VersionSelector.Pinned}. */
    record ByModel(ModelRef model) implements ModelSelector {
        public ByModel {
            requireNonNull(model, "ByModel model must not be null");
        }
    }
}
