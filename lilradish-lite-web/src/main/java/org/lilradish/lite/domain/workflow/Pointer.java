package org.lilradish.lite.domain.workflow;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import org.lilradish.lite.domain.declaration.Declaration;
import org.lilradish.lite.domain.declaration.Field;
import org.lilradish.lite.domain.declaration.FieldName;
import org.lilradish.lite.domain.declaration.FieldShape;
import org.lilradish.lite.domain.declaration.HowMany;

/**
 * Where within a declaration a binding reads or fills: field names from the first level down, and never a place
 * among many, which is only known once a run holds values.
 */
public record Pointer(List<FieldName> names) {

    private static final String JOINER = ".";

    public Pointer {
        names = List.copyOf(requireNonNull(names, "Pointer names must not be null"));
        if (names.isEmpty()) {
            throw new IllegalArgumentException("Pointer must name at least one field");
        }
        int length = names.size() - 1;
        for (FieldName name : names) {
            length += name.value().length();
        }
        if (length > Declaration.LONGEST_PATH) {
            throw new IllegalArgumentException(
                    "Pointer runs to " + length + " characters, past " + Declaration.LONGEST_PATH);
        }
    }

    /** The names joined by dots, each refused as a field's name would be. */
    public static Pointer parse(String spelled) {
        requireNonNull(spelled, "Pointer spelled must not be null");
        List<FieldName> names = new ArrayList<>();
        int from = 0;
        while (true) {
            int to = spelled.indexOf(JOINER, from);
            names.add(new FieldName(to < 0 ? spelled.substring(from) : spelled.substring(from, to)));
            if (to < 0) {
                return new Pointer(names);
            }
            from = to + 1;
        }
    }

    public String published() {
        StringBuilder spelled = new StringBuilder();
        for (FieldName name : names) {
            if (!spelled.isEmpty()) {
                spelled.append(JOINER);
            }
            spelled.append(name.value());
        }
        return spelled.toString();
    }

    /** Whether this names {@code other}'s field or one {@code other}'s field holds. */
    public boolean within(Pointer other) {
        requireNonNull(other, "Pointer other must not be null");
        return names.size() >= other.names.size()
                && names.subList(0, other.names.size()).equals(other.names);
    }

    /** What this names in {@code level}; a name first among several alike is the one reached. */
    public Reach reach(List<Field> level) {
        requireNonNull(level, "Pointer level must not be null");
        List<Field> here = level;
        boolean given = true;
        for (int index = 0; ; index++) {
            Field found = Field.named(here, names.get(index));
            if (found == null) {
                return new Reach.Nowhere();
            }
            given &= found.demand().mustBe();
            if (index == names.size() - 1) {
                return new Reach.At(found, given);
            }
            if (!(found.shape() instanceof FieldShape.Nested nested)) {
                return new Reach.Nowhere();
            }
            if (found.howMany() instanceof HowMany.Many) {
                return new Reach.IntoMany();
            }
            here = nested.fields();
        }
    }

    /** Where a pointer leads: to a field, through a field holding many, which names no place, or nowhere. */
    public sealed interface Reach permits Reach.At, Reach.IntoMany, Reach.Nowhere {

        /** @param given whether the field and every field holding it must be given, so a value is always there */
        record At(Field field, boolean given) implements Reach {

            public At {
                requireNonNull(field, "Pointer.Reach.At field must not be null");
            }
        }

        record IntoMany() implements Reach {}

        record Nowhere() implements Reach {}
    }
}
