package org.lilradish.lite.domain.registry;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

/** Which entry is meant, asked in the one way renaming it cannot change. */
public record EntryId(UUID value) {

    public EntryId {
        requireNonNull(value, "EntryId must not be null");
    }
}
