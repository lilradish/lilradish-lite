package org.lilradish.lite.domain.model;

import org.lilradish.lite.domain.text.Identifiers;

/**
 * A model as the deployment names it and a step writes it. Uppercase is refused, never folded, so a
 * name reads back as it was written.
 */
public record ModelName(String value) {

    public ModelName {
        Identifiers.requireText(value, "ModelName");
    }
}
