package org.lilradish.lite.domain.filling;

import static java.util.Objects.requireNonNull;

import org.libprunus.core.log.annotation.DoNotLog;
import org.lilradish.lite.domain.wire.JsonValue.JsonObject;

/**
 * Everything filled, as it is kept. Its values are private and must stay out of logs.
 */
public record FilledFields(@DoNotLog JsonObject values) implements FillOutcome {

    public FilledFields {
        requireNonNull(values, "FilledFields values must not be null");
    }
}
