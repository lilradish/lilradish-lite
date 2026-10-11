package com.lilradish.lite.domain.registry;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.text.Legibility;

/** A version's optional display name, kept as typed and never used as model input or identity. */
public record VersionName(String value) {

    public VersionName {
        requireNonNull(value, "VersionName must not be null");
        Legibility.requireOneWellFormedLine(value, "VersionName");
        Legibility.requireNotEmpty(value, "VersionName");
        Legibility.requireSpaceDecidesNothing(value, "VersionName");
        Legibility.requireSomethingVisible(value, "VersionName");
        Legibility.requireWithinMaximumLength(value, 64, "VersionName");
    }
}
