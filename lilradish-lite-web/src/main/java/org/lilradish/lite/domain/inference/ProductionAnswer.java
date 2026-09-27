package org.lilradish.lite.domain.inference;

import static java.util.Objects.requireNonNull;

import java.util.Map;
import org.libprunus.core.log.annotation.DoNotLog;
import org.lilradish.lite.domain.declaration.FieldName;
import org.lilradish.lite.domain.wire.JsonValue;

/** What a model producing gave back, unwrapped once as it arrived: values that fit, or an answer that does not. */
public sealed interface ProductionAnswer permits ProductionAnswer.Produced, DoesNotFit {

    /**
     * @param values each field given back, {@link JsonValue.JsonNull} where it gave none
     * @param confidences how sure it was, of exactly the fields that asked
     */
    record Produced(
            @DoNotLog Map<FieldName, JsonValue> values,
            @DoNotLog Map<FieldName, Confidence> confidences) implements ProductionAnswer {

        public Produced {
            values = Map.copyOf(requireNonNull(values, "Produced values must not be null"));
            confidences = Map.copyOf(requireNonNull(confidences, "Produced confidences must not be null"));
        }
    }
}
