package org.lilradish.lite.domain.declaration;

import static java.util.Objects.requireNonNull;

import java.util.LinkedHashSet;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.file.MediaTypeName;
import org.lilradish.lite.domain.registry.EntryVersionId;

/**
 * What a field is, with what that kind alone says of it. What a draft has not chosen yet is held as
 * nothing, which {@link Declaration#problems} names and submitting refuses.
 */
public sealed interface FieldShape
        permits FieldShape.Text, FieldShape.Plain, FieldShape.Term, FieldShape.Nested, FieldShape.File {

    FieldKind kind();

    /** @param longest the most characters its value may run to, or none where not chosen yet */
    record Text(@Nullable Integer longest, TextLines lines) implements FieldShape {

        public Text {
            requireNonNull(lines, "FieldShape.Text lines must not be null");
            if (longest != null && longest < 1) {
                throw new IllegalArgumentException("FieldShape.Text longest must be at least one: " + longest);
            }
        }

        public Text(@Nullable Integer longest) {
            this(longest, TextLines.SEVERAL);
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
            if (kind == FieldKind.TEXT
                    || kind == FieldKind.TERM
                    || kind == FieldKind.FIELDS
                    || kind == FieldKind.FILE) {
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
    /** Media types in declared order and the largest one file may be. */
    record File(List<MediaTypeName> types, @Nullable Long maximumBytes) implements FieldShape {

        /* Arrival and release check upper bounds so refusals can name their field and step. */
        public static final int MOST_TYPES = 64;

        public static final long LARGEST_BYTES = 9_007_199_254_740_991L;

        public static boolean typesFit(int count) {
            return count >= 1 && count <= MOST_TYPES;
        }

        public static boolean maximumBytesFit(long bytes) {
            return bytes >= 1 && bytes <= LARGEST_BYTES;
        }

        public File {
            types = List.copyOf(requireNonNull(types, "FieldShape.File types must not be null"));
            if (types.isEmpty()) {
                throw new IllegalArgumentException("FieldShape.File must name at least one type");
            }
            if (types.size() != new LinkedHashSet<>(types).size()) {
                throw new IllegalArgumentException("FieldShape.File types must not repeat");
            }
            if (maximumBytes != null && maximumBytes < 1) {
                throw new IllegalArgumentException("FieldShape.File maximumBytes must be positive");
            }
        }

        @Override
        public FieldKind kind() {
            return FieldKind.FILE;
        }
    }
}
