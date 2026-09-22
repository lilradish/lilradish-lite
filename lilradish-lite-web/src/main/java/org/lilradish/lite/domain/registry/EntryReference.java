package org.lilradish.lite.domain.registry;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

/**
 * One entry a step read, at the version it read. A declaration may ask for the latest, but what ran
 * resolved to one version and this records what ran — without it, opening the entry shows whatever
 * it says today rather than what the answer was built from.
 *
 * <p>The kind travels beside the id rather than encoded into it, and is a reader's convenience
 * rather than a guarantee: nothing here verifies the pair.
 */
public record EntryReference(RegistryKind kind, UUID entryId, int version) {

    public EntryReference {
        requireNonNull(kind, "EntryReference kind must not be null");
        requireNonNull(entryId, "EntryReference entryId must not be null");
        if (version < 1) {
            throw new IllegalArgumentException("EntryReference version must be positive: " + version);
        }
    }
}
