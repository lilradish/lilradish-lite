package org.lilradish.lite.app.library;

import static java.util.Objects.requireNonNull;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.error.ApiErrorException;
import org.lilradish.lite.domain.registry.EntryId;
import org.lilradish.lite.domain.registry.EntryKind;
import org.lilradish.lite.domain.registry.EntryName;
import org.lilradish.lite.domain.registry.EntryVersionId;

/**
 * A version refused for pinning versions retired since, carrying each as what a member of the owning group
 * can read already, and never in its sentence: the entry pinned, the version, and the newest of that entry
 * in service where one is, which is what would take its place.
 */
final class RetiredPinsRefusal extends ApiErrorException {

    private final transient List<RetiredPin> pins;

    RetiredPinsRefusal(List<RetiredPin> pins) {
        super(LibraryRefusal.VERSION_PINS_RETIRED.code(), LibraryRefusal.VERSION_PINS_RETIRED.sentence());
        this.pins = List.copyOf(requireNonNull(pins, "RetiredPinsRefusal pins must not be null"));
    }

    List<RetiredPin> pins() {
        return pins;
    }

    /** @param newestInService the newest version of that entry in service now, or none where none is */
    record RetiredPin(
            EntryId entry,
            EntryKind kind,
            EntryName name,
            NumberedVersion pinned,
            @Nullable NumberedVersion newestInService) {}

    record NumberedVersion(EntryVersionId version, int number) {}
}
