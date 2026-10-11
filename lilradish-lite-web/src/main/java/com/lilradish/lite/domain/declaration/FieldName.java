package com.lilradish.lite.domain.declaration;

import com.lilradish.lite.domain.text.Identifiers;

public record FieldName(String value) {

    public FieldName {
        value = Identifiers.requireText(value, "FieldName");
    }
}
