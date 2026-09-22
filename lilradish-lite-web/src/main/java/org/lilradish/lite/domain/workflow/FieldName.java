package org.lilradish.lite.domain.workflow;

/** The source end of a binding: a field of a structured input or output. */
public record FieldName(String value) {

    public FieldName {
        value = Identifiers.requireText(value, "FieldName");
    }
}
