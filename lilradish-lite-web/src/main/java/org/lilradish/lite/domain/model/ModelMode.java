package org.lilradish.lite.domain.model;

import org.lilradish.lite.domain.text.Identifiers;

/**
 * A way of asking a model beyond running it as it is, named by the deployment and written by a step.
 * Uppercase is refused, never folded, so a mode reads back as it was written.
 */
public record ModelMode(String value) {

    // The store writes this word for running a model as it is, so no mode may be called it.
    public static final String RESERVED_FOR_AS_IT_IS = "ordinary";

    public ModelMode {
        Identifiers.requireText(value, "ModelMode");
        if (value.equals(RESERVED_FOR_AS_IT_IS)) {
            throw new IllegalArgumentException(
                    "ModelMode must not be " + value + ", which names running a model as it is");
        }
    }
}
