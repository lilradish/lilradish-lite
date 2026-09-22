package org.lilradish.lite.domain.workflow;

/** The target end of a binding: the step parameter a value lands on. */
public record ParameterName(String value) {

    public ParameterName {
        value = Identifiers.requireText(value, "ParameterName");
    }
}
