package org.lilradish.lite.domain.registry;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

/**
 * Which version of which reference list a declaration draws its values from. Pinned by
 * construction: a floating vocabulary would reinterpret a declaration made years earlier, which is
 * the one thing versioning the list was for.
 */
public record VocabularyPin(UUID listId, int version) {

    public VocabularyPin {
        requireNonNull(listId, "VocabularyPin listId must not be null");
        if (version < 1) {
            throw new IllegalArgumentException("VocabularyPin version must be positive: " + version);
        }
    }
}
