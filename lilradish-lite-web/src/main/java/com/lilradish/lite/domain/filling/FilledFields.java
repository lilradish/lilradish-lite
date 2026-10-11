package com.lilradish.lite.domain.filling;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.wire.JsonValue.JsonObject;
import org.libprunus.core.log.annotation.DoNotLog;

/** Its values are private and must stay out of logs. */
public record FilledFields(@DoNotLog JsonObject values) implements FillOutcome {

    public FilledFields {
        requireNonNull(values, "FilledFields values must not be null");
    }
}
