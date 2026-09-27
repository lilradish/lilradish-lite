package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import org.jspecify.annotations.Nullable;
import org.libprunus.core.log.annotation.DoNotLog;
import org.lilradish.lite.domain.wire.JsonValue;

/**
 * One value a try gave back, as the store holds it.
 *
 * @param field the name of the first-level field it is the value of
 * @param value what it holds, none as {@link JsonValue.JsonNull}
 * @param confidence how sure a model was, only where a model produced it and it stands above a confidence
 * @param needsReview whether it stands only once a review assures it, as the store works it out
 */
public record ValueRecord(
        ProductionValueId id,
        String field,
        @DoNotLog JsonValue value,
        @Nullable Integer confidence,
        boolean needsReview) {

    public ValueRecord {
        requireNonNull(id, "ValueRecord id must not be null");
        requireNonNull(field, "ValueRecord field must not be null");
        requireNonNull(value, "ValueRecord value must not be null");
    }
}
