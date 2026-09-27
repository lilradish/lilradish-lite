package org.lilradish.lite.domain.declaration;

import static java.util.Objects.requireNonNull;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.registry.EntryVersionId;

/**
 * What a field is, with what that kind alone says of it. What a draft has not chosen yet is held as
 * nothing, which {@link Declaration#problems} names and submitting refuses.
 */
public sealed interface FieldShape permits FieldShape.Text, FieldShape.Plain, FieldShape.Term, FieldShape.Nested {

    FieldKind kind();

    /** @param longest the most characters its value may run to, or none where not chosen yet */
    record Text(@Nullable Integer longest) implements FieldShape {

        public Text {
            if (longest != null && longest < 1) {
                throw new IllegalArgumentException("FieldShape.Text longest must be at least one: " + longest);
            }
        }

        @Override
        public FieldKind kind() {
            return FieldKind.TEXT;
        }
    }

    /** A number, a date, a moment, or yes or no, none of which says anything more of itself. */
    record Plain(FieldKind kind) implements FieldShape {

        public Plain {
            requireNonNull(kind, "FieldShape.Plain kind must not be null");
            if (kind == FieldKind.TEXT || kind == FieldKind.TERM || kind == FieldKind.FIELDS) {
                throw new IllegalArgumentException(
                        "FieldShape.Plain cannot be " + kind + ", which says more of itself");
            }
        }
    }

    /** @param list the reference list version its terms come from, or none where not chosen yet */
    record Term(@Nullable EntryVersionId list) implements FieldShape {

        @Override
        public FieldKind kind() {
            return FieldKind.TERM;
        }
    }

    /** Fields of its own, each with its own name, in declared order. */
    record Nested(List<Field> fields) implements FieldShape {

        public Nested {
            fields = List.copyOf(requireNonNull(fields, "FieldShape.Nested fields must not be null"));
        }

        @Override
        public FieldKind kind() {
            return FieldKind.FIELDS;
        }
    }
}
