package com.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.declaration.FieldName;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One binding's value as a try took it, traced to its ultimate makers.
 *
 * @param source the direct producing value, where one value made the input
 * @param parts the component sources where the input was assembled
 */
public record InputRecord(UUID binding, @Nullable ProductionValueId source, List<Part> parts) {

    public InputRecord {
        requireNonNull(binding, "InputRecord binding must not be null");
        parts = List.copyOf(requireNonNull(parts, "InputRecord parts must not be null"));
        if (source != null && !parts.isEmpty()) {
            throw new IllegalArgumentException("One direct value has no component sources");
        }
    }

    public record Part(List<FieldName> targetPath, @Nullable ProductionValueId source) {

        public Part {
            targetPath = List.copyOf(requireNonNull(targetPath, "InputRecord.Part targetPath must not be null"));
            if (targetPath.isEmpty()) {
                throw new IllegalArgumentException("A component must name its target path");
            }
        }
    }
}
