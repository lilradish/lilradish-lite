package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.declaration.FieldName;

/** One binding's or search question part's value as a try took it, traced to its ultimate makers. */
public record InputRecord(
        @Nullable UUID binding,
        @Nullable UUID searchQuestionPart,
        @Nullable ProductionValueId source,
        List<Part> parts) {

    public InputRecord {
        if ((binding == null) == (searchQuestionPart == null)) {
            throw new IllegalArgumentException("A taken input names exactly one binding or search question part");
        }
        parts = List.copyOf(requireNonNull(parts, "InputRecord parts must not be null"));
        if (source != null && !parts.isEmpty()) {
            throw new IllegalArgumentException("One direct value has no component sources");
        }
    }

    public InputRecord(UUID binding, @Nullable ProductionValueId source, List<Part> parts) {
        this(requireNonNull(binding, "InputRecord binding must not be null"), null, source, parts);
    }

    public InputRecord(UUID binding, @Nullable ProductionValueId source) {
        this(binding, source, List.of());
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
