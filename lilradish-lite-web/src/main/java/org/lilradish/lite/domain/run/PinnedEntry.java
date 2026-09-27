package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import org.lilradish.lite.domain.registry.EntryId;
import org.lilradish.lite.domain.registry.EntryName;
import org.lilradish.lite.domain.registry.EntryVersionId;

/**
 * The entry a step runs, at the version it pinned.
 *
 * @param number the pinned version's number within its entry
 */
public record PinnedEntry(EntryId entry, EntryName name, EntryVersionId version, int number) {

    public PinnedEntry {
        requireNonNull(entry, "PinnedEntry entry must not be null");
        requireNonNull(name, "PinnedEntry name must not be null");
        requireNonNull(version, "PinnedEntry version must not be null");
        if (number < 1) {
            throw new IllegalArgumentException("PinnedEntry number must be positive: " + number);
        }
    }
}
