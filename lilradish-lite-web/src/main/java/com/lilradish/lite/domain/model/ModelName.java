package com.lilradish.lite.domain.model;

import com.lilradish.lite.domain.text.Identifiers;

/** Uppercase is refused, never folded, so a name reads back as it was written. */
public record ModelName(String value) {

    public ModelName {
        Identifiers.requireText(value, "ModelName");
    }
}
