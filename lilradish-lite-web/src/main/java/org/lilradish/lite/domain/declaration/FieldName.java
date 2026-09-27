package org.lilradish.lite.domain.declaration;

import org.lilradish.lite.domain.text.Identifiers;

/** What anything binding to a declared field calls it: a field of a structured input or output. */
public record FieldName(String value) {

    public FieldName {
        value = Identifiers.requireText(value, "FieldName");
    }
}
