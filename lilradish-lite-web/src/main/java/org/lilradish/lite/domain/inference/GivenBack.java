package org.lilradish.lite.domain.inference;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.log.annotation.DoNotLog;
import org.lilradish.lite.domain.declaration.FieldName;
import org.lilradish.lite.domain.wire.JsonValue;

/** What a code step gave back, read once against the fields it gives: values that fit, or where they do not. */
public sealed interface GivenBack permits GivenBack.Fits, GivenBack.Misfit {

    /** @param values each field it gives, {@link JsonValue.JsonNull} for nothing at every depth, left out or not */
    record Fits(@DoNotLog Map<FieldName, JsonValue> values) implements GivenBack {

        public Fits {
            values = Map.copyOf(requireNonNull(values, "Fits values must not be null"));
        }
    }

    /**
     * @param reason the first thing found wrong
     * @param path from the first level down to the field whose value is wrong, or for a member nothing declares to
     *     the field holding it, empty at the first level; either way no further than the outermost many holding it
     * @param undeclared the name of that member exactly as it came, not cleaned; given exactly where it is one
     */
    record Misfit(
            DidNotFitReason reason,
            List<FieldName> path,
            @DoNotLog @Nullable String undeclared) implements GivenBack {

        public Misfit {
            requireNonNull(reason, "Misfit reason must not be null");
            path = List.copyOf(requireNonNull(path, "Misfit path must not be null"));
            if ((reason == DidNotFitReason.FIELD_UNKNOWN) != (undeclared != null)) {
                throw new IllegalArgumentException("Misfit names an undeclared member exactly where one is unknown");
            }
        }
    }
}
