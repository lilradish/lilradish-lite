package org.lilradish.lite.domain.model;

import org.lilradish.lite.domain.text.Identifiers;

/** A tool the deployment offers to a model, named by the same rule as a model. */
public record ModelToolName(String value) {

    public ModelToolName {
        value = Identifiers.requireText(value, "ModelToolName");
    }
}
