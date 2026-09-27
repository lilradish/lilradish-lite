package org.lilradish.lite.domain.filling;

import static java.util.Objects.requireNonNull;

import java.util.List;
import org.lilradish.lite.domain.declaration.FieldName;

/**
 * Where a filled value stands, from the first level down: a field by its name, or an element of a field holding
 * many by its place. It starts at a field, since no value is judged but a field's.
 */
public record FillPath(List<Step> steps) {

    public FillPath {
        steps = List.copyOf(requireNonNull(steps, "FillPath steps must not be null"));
        if (steps.isEmpty() || !(steps.getFirst() instanceof Named)) {
            throw new IllegalArgumentException("FillPath starts at a field");
        }
    }

    public sealed interface Step permits Named, Place {}

    public record Named(FieldName name) implements Step {

        public Named {
            requireNonNull(name, "FillPath.Named name must not be null");
        }
    }

    /** @param index counted from nought, in the order the elements were given */
    public record Place(int index) implements Step {

        public Place {
            if (index < 0) {
                throw new IllegalArgumentException("FillPath.Place index must not be negative: " + index);
            }
        }
    }
}
