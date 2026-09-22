package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.log.annotation.DoNotLog;

/**
 * The arguments a step ran on. A type of its own so that it cannot be handed where a result is
 * expected — the two are the same shape and sit side by side wherever an attempt is described.
 *
 * <p>Held shallowly: the map cannot be replaced, but what nests inside it can.
 */
public record InputFields(@DoNotLog Map<String, @Nullable Object> fields) {

    public InputFields {
        requireNonNull(fields, "InputFields fields must not be null");
        // Copied first: a null-hostile map answers containsKey(null) with an NPE of its own.
        Map<String, @Nullable Object> copied = new LinkedHashMap<>(fields);
        // An optional argument may arrive with no value; a field with no name may not.
        if (copied.containsKey(null)) {
            throw new IllegalArgumentException("InputFields must not carry a nameless field");
        }
        fields = Collections.unmodifiableMap(copied);
    }
}
