package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One binding's value as a try took it.
 *
 * @param binding the stored key of the binding filling the input
 * @param source the value a step made that it was read from; none where it read a constant or what the run was
 *     started with
 */
public record InputRecord(UUID binding, @Nullable ProductionValueId source) {

    public InputRecord {
        requireNonNull(binding, "InputRecord binding must not be null");
    }
}
