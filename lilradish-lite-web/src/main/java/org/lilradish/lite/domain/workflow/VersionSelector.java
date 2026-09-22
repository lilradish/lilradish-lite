package org.lilradish.lite.domain.workflow;

public sealed interface VersionSelector {

    /** The latest approved version, resolved at run time rather than at authoring time. */
    record Latest() implements VersionSelector {}

    record Pinned(int version) implements VersionSelector {
        public Pinned {
            if (version < 1) {
                throw new IllegalArgumentException("Pinned version must be positive");
            }
        }
    }
}
