package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.log.annotation.DoNotLog;

/**
 * What a step produced. Inference content: showing it is a privileged read, recorded against
 * whoever read it, which is why it is kept apart from the arguments it was produced from.
 *
 * <p>Held shallowly: the map cannot be replaced, but what nests inside it can.
 */
public record OutputFields(@DoNotLog Map<String, @Nullable Object> fields) {

    public OutputFields {
        requireNonNull(fields, "OutputFields fields must not be null");
        // Copied first: a null-hostile map answers containsKey(null) with an NPE of its own.
        Map<String, @Nullable Object> copied = new LinkedHashMap<>(fields);
        // A field of a model's result may be null; a field with no name may not.
        if (copied.containsKey(null)) {
            throw new IllegalArgumentException("OutputFields must not carry a nameless field");
        }
        fields = Collections.unmodifiableMap(copied);
    }
}
