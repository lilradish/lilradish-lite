package org.lilradish.lite.domain.codestep;

import org.lilradish.lite.domain.text.Identifiers;

/** What a step names a code step by, and what a migration publishes it under. */
public record CodeStepName(String value) {

    public CodeStepName {
        value = Identifiers.requireText(value, "CodeStepName");
    }
}
