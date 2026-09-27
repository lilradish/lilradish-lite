package org.lilradish.lite.domain.declaration;

import static java.util.Objects.requireNonNull;

import java.util.List;
import org.lilradish.lite.domain.registry.ContentProblemCode;

/**
 * What is wrong with one field of a {@link Declaration}, found by where it sits rather than by its name,
 * which the field beside it may share.
 *
 * @param at the field's place in declared order at each level, from the first level down, counted from zero
 */
public record FieldProblem(ContentProblemCode code, List<Integer> at) {

    public FieldProblem {
        requireNonNull(code, "FieldProblem code must not be null");
        at = List.copyOf(requireNonNull(at, "FieldProblem at must not be null"));
        if (at.isEmpty()) {
            throw new IllegalArgumentException("FieldProblem at must name a field");
        }
    }
}
