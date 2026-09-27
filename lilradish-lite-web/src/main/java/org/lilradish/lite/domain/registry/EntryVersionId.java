package org.lilradish.lite.domain.registry;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

/** Which version of an entry is meant. What points at a version holds this and never its number. */
public record EntryVersionId(UUID value) {

    public EntryVersionId {
        requireNonNull(value, "EntryVersionId must not be null");
    }
}
