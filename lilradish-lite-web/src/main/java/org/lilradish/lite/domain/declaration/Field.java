package org.lilradish.lite.domain.declaration;

import static java.util.Objects.requireNonNull;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One declared field: what anything binding to it calls it, what people read it as, what it is, how many
 * it holds, and what it asks. Which demand belongs where is its host's to say, in {@link Demands}.
 *
 * @param label none where it is read by its name
 * @param help none where it says nothing
 */
public record Field(
        FieldName name,
        @Nullable FieldLabel label,
        @Nullable FieldHelp help,
        FieldShape shape,
        HowMany howMany,
        Demand demand) {

    public Field {
        requireNonNull(name, "Field name must not be null");
        requireNonNull(shape, "Field shape must not be null");
        requireNonNull(howMany, "Field howMany must not be null");
        requireNonNull(demand, "Field demand must not be null");
    }

    /** The first field at {@code level} holding {@code name}, or none. */
    public static @Nullable Field named(List<Field> level, FieldName name) {
        requireNonNull(level, "Field level must not be null");
        requireNonNull(name, "Field name must not be null");
        for (Field field : level) {
            if (field.name().equals(name)) {
                return field;
            }
        }
        return null;
    }
}
