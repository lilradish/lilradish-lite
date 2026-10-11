package com.lilradish.lite.domain.model;

import com.lilradish.lite.domain.text.Identifiers;

/** Named by the same rule as a model. */
public record ModelToolName(String value) {

    public ModelToolName {
        value = Identifiers.requireText(value, "ModelToolName");
    }
}
