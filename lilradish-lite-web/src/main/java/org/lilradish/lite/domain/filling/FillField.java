package org.lilradish.lite.domain.filling;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.declaration.Field;
import org.lilradish.lite.domain.declaration.FieldHelp;
import org.lilradish.lite.domain.declaration.FieldKind;
import org.lilradish.lite.domain.declaration.FieldLabel;
import org.lilradish.lite.domain.declaration.FieldName;
import org.lilradish.lite.domain.declaration.FieldShape;
import org.lilradish.lite.domain.declaration.HowMany;
import org.lilradish.lite.domain.declaration.OfferedTerms;
import org.lilradish.lite.domain.registry.EntryVersionId;

/**
 * One field as it is put to a person filling it in: what it is called and read as, what it is, how many and how
 * long, whether it must be given, and the terms of the list version it pins beside it, so whoever fills it reads
 * no list. What a value stands on is nothing to whoever fills it, and is left out.
 *
 * @param label none where it is read by its name
 * @param help none where it says nothing
 * @param longest how many characters its value may run to, only where it is text
 * @param most the most it holds where it holds many; none where it holds one
 * @param terms what it offers, in its list version's order with that version's note, only where it is a term
 * @param fields its own fields in declared order, exactly where it holds fields, and none otherwise
 */
public record FillField(
        FieldName name,
        @Nullable FieldLabel label,
        @Nullable FieldHelp help,
        FieldKind kind,
        @Nullable Integer longest,
        @Nullable Integer most,
        boolean mustBeGiven,
        @Nullable OfferedTerms terms,
        List<FillField> fields) {

    public FillField {
        requireNonNull(name, "FillField name must not be null");
        requireNonNull(kind, "FillField kind must not be null");
        fields = List.copyOf(requireNonNull(fields, "FillField fields must not be null"));
        if ((kind == FieldKind.TEXT) != (longest != null) || (longest != null && longest < 1)) {
            throw new IllegalArgumentException("FillField says how long, of at least one, exactly where it is text");
        }
        if (most != null && most < 1) {
            throw new IllegalArgumentException("FillField most must be at least one: " + most);
        }
        if ((kind == FieldKind.TERM) != (terms != null)) {
            throw new IllegalArgumentException("FillField offers terms exactly where it is a term");
        }
        if ((kind == FieldKind.FIELDS) == fields.isEmpty()) {
            throw new IllegalArgumentException("FillField holds fields exactly where it is fields");
        }
    }

    /**
     * Each field of a declaration some version holds as submitting requires, beside the terms of every list it
     * pins. A limit or a list not chosen, a list not handed over, or fields holding none is a store gone wrong.
     *
     * @throws IllegalStateException where a field is not as an approved version holds it
     */
    public static List<FillField> of(List<Field> fields, Map<EntryVersionId, OfferedTerms> lists) {
        requireNonNull(fields, "FillField fields must not be null");
        requireNonNull(lists, "FillField lists must not be null");
        return fields.stream().map(field -> of(field, lists)).toList();
    }

    private static FillField of(Field field, Map<EntryVersionId, OfferedTerms> lists) {
        FieldShape shape = field.shape();
        return new FillField(
                field.name(),
                field.label(),
                field.help(),
                shape.kind(),
                shape instanceof FieldShape.Text text ? chosen(text.longest(), field, "how long") : null,
                switch (field.howMany()) {
                    case HowMany.One ignored -> null;
                    case HowMany.Many many -> chosen(many.most(), field, "how many");
                },
                field.demand().mustBe(),
                shape instanceof FieldShape.Term term ? offered(chosen(term.list(), field, "which list"), lists) : null,
                shape instanceof FieldShape.Nested nested ? of(held(nested, field), lists) : List.of());
    }

    private static List<Field> held(FieldShape.Nested nested, Field field) {
        if (nested.fields().isEmpty()) {
            throw new IllegalStateException("FillField " + field.name().value() + " holds no fields");
        }
        return nested.fields();
    }

    private static <T> T chosen(@Nullable T limit, Field field, String what) {
        if (limit == null) {
            throw new IllegalStateException("FillField " + field.name().value() + " has not chosen " + what);
        }
        return limit;
    }

    private static OfferedTerms offered(EntryVersionId list, Map<EntryVersionId, OfferedTerms> lists) {
        OfferedTerms offered = lists.get(list);
        if (offered == null) {
            throw new IllegalStateException("FillField was handed no terms for list " + list.value());
        }
        return offered;
    }
}
